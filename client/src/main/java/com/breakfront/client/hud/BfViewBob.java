package com.breakfront.client.hud;

import net.minecraft.entity.player.PlayerEntity;

/**
 * 视野视差（view bob，静态）：根据玩家每帧位置增量累积相位，返回轻微屏幕视差偏移
 * dx/dy（像素系数）。仅在「地面移动 / 落地冲击」时产生，幅度克制（走路~跑步 0.5-2px，
 * 平滑过渡）。HUD 侧会再乘 0.4 并封顶 ~1.2px 以保阅读舒适。
 *
 * <p>实现纪律：
 * - 不依赖任何 tick 计数，直接用玩家坐标帧间 delta（渲染线程每帧调用一次）。
 * - 静立 / 空中（非 onGround）不产生偏移，避免眩晕。
 * - 落地冲击：onGround 由 false→true 且下落速度较大时，注入一段随时间衰减的下沉冲击。
 */
public final class BfViewBob {

    private static double lastX, lastY, lastZ;
    private static boolean havePrev = false;
    private static boolean lastOnGround = true;
    private static double phase = 0;
    private static double land = 0; // 落地冲击 0..1，随时间衰减
    private static long lastMs = 0;

    private static final double AMP_WALK = 1.0;   // 走路幅值（px）
    private static final double AMP_RUN = 2.0;    // 跑步幅值（px）
    private static final double CAP = 2.0;        // 单轴封顶（px）
    private static final double STRIDE = 2.2;     // 每步距（方块/整 bob 周期），决定步频
    private static final double SPEED_REF = 6.0;  // 视为「全速（冲刺）」的近似水平速度（方块/秒）

    private static final Bob ZERO = new Bob(0, 0, 0);

    private BfViewBob() {
    }

    /** 屏幕视差系数（dx/dy 横向/纵向，dz 暂未用于 2D HUD）。 */
    public record Bob(double dx, double dy, double dz) {
    }

    public static Bob compute(PlayerEntity player) {
        if (player == null) {
            return ZERO;
        }
        long now = System.currentTimeMillis();
        double dt = (now - lastMs) / 1000.0;
        lastMs = now;
        if (dt <= 0) {
            dt = 1.0 / 60.0;
        }
        if (dt > 0.1) {
            dt = 0.1; // 卡顿/切后台时钳制，避免跳变
        }

        double x = player.getX(), y = player.getY(), z = player.getZ();
        if (!havePrev) {
            lastX = x;
            lastY = y;
            lastZ = z;
            havePrev = true;
            lastOnGround = player.isOnGround();
            return ZERO;
        }

        double ddx = x - lastX, ddz = z - lastZ;
        double horiz = Math.hypot(ddx, ddz);
        double speed = horiz / dt;               // 近似水平速度（方块/秒）
        double move = Math.min(1.0, speed / SPEED_REF); // 0 静立 .. 1 冲刺

        boolean onGround = player.isOnGround();
        boolean moving = onGround && horiz > 1e-4;

        // 步频：相位随水平位移累积，每 STRIDE 方块走完一个整周期（两步）
        if (moving) {
            phase += (horiz / STRIDE) * Math.PI * 2.0;
            if (phase > 1e7) {
                phase -= 1e7;
            }
        }

        // 落地冲击：刚着地且有明显下落速度
        if (onGround && !lastOnGround) {
            double vy = (y - lastY) / dt;
            double impact = Math.min(1.0, (-vy) / 6.0);
            if (impact > 0.05) {
                land = Math.min(1.0, Math.max(land, impact));
            }
        }

        lastX = x;
        lastY = y;
        lastZ = z;
        lastOnGround = onGround;
        land = Math.max(0, land - dt * 2.4); // 衰减（约 0.4s 内归零）

        // 偏移：横向随相位摆动，纵向取 |cos| 模拟踩踏起伏 + 落地下沉
        double amp = AMP_WALK + (AMP_RUN - AMP_WALK) * move;
        double ox = Math.sin(phase) * amp * 0.6 * move;
        double oy = Math.abs(Math.cos(phase)) * amp * move + land * 2.0;

        return new Bob(clamp(ox), clamp(oy), 0);
    }

    private static double clamp(double v) {
        return Math.max(-CAP, Math.min(CAP, v));
    }
}
