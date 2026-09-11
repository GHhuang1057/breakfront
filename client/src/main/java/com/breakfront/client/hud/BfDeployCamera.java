package com.breakfront.client.hud;

import net.minecraft.entity.Entity;

/**
 * 部署阶段相机（2026-09-11）：
 *   玩家阵亡、部署屏（BfDeployScreen）打开时，相机切到**所选部署目标上空的实时 3D 俯瞰**
 *   —— 直接渲染真实世界（同一渲染管线，只是覆写相机位姿），部署阶段不需要高帧率，不做任何优化。
 *   点击「部署」后相机从上空**平滑滑落**到重生后的第一人称（smoothstep，900ms），落地即接管。
 *   不可缩放（用户明确要求固定俯瞰）。由 CameraMixin 在 Camera.update TAIL 调用 apply 相关方法。
 */
public final class BfDeployCamera {

    public enum Mode { NONE, OVERHEAD, GLIDE }

    private static volatile Mode mode = Mode.NONE;
    /** 俯瞰锚点 / 滑落起点（世界坐标）。 */
    private static double ax, ay, az;
    private static long glideStart;
    private static final long GLIDE_MS = 900;
    /** 俯瞰高度（绝对 Y）。Metro 地形顶 ~90，取 112 保证全场景可见。 */
    private static final double OVERHEAD_Y = 112.0;

    private BfDeployCamera() {}

    /** 部署屏激活（respawning 且有选中目标）：锚定目标上空。幂等。 */
    public static void engage(double x, double z) {
        ax = x;
        az = z;
        ay = OVERHEAD_Y;
        if (mode == Mode.NONE) {
            mode = Mode.OVERHEAD;
        }
    }

    /** 点击「部署」：记录上空起点进入滑落（目标=重生后玩家眼睛，逐帧取实时值）。 */
    public static void beginGlide() {
        if (mode == Mode.NONE) {
            return;
        }
        glideStart = System.currentTimeMillis();
        mode = Mode.GLIDE;
    }

    public static void disengage() {
        mode = Mode.NONE;
    }

    public static boolean active() {
        return mode != Mode.NONE;
    }

    /**
     * CameraMixin 在 Camera.update TAIL 调用。
     * 返回覆写相机坐标；rotOut[0]=yaw、rotOut[1]=pitch；返回 null 表示不接管。
     */
    public static double[] cameraPose(float[] rotOut, Entity focused) {
        if (focused == null) {
            return null;
        }
        // 部署屏未点部署但玩家已复活（如原版重生键）→ 自动转入滑落，避免卡在俯瞰
        if (mode == Mode.OVERHEAD && focused.isAlive()) {
            glideStart = System.currentTimeMillis();
            mode = Mode.GLIDE;
        }
        switch (mode) {
            case OVERHEAD:
                rotOut[0] = 0f;
                rotOut[1] = 90f;
                return new double[]{ax, ay, az};
            case GLIDE: {
                double t = Math.min(1.0, (System.currentTimeMillis() - glideStart) / (double) GLIDE_MS);
                double e = t * t * (3 - 2 * t);          // smoothstep
                net.minecraft.util.math.Vec3d eye = focused.getEyePos();
                rotOut[0] = (float) (0.0 + (focused.getYaw() - 0.0) * e);
                rotOut[1] = (float) (90.0 + (focused.getPitch() - 90.0) * e);
                double[] p = {
                        ax + (eye.x - ax) * e,
                        ay + (eye.y - ay) * e,
                        az + (eye.z - az) * e
                };
                if (t >= 1.0) {
                    mode = Mode.NONE;
                }
                return p;
            }
            default:
                return null;
        }
    }
}
