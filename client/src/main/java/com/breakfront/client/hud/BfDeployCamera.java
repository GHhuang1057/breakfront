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
    /**
     * 部署屏是否处于打开状态。
     *
     * <p>2026-09-11 修「没开局就反复落地-俯瞰」：此前 cameraPose 只要玩家存活就把
     * OVERHEAD 自动切到 GLIDE，而部署屏每帧又通过 engage 把模式拉回 OVERHEAD，
     * 于是形成 OVERHEAD→GLIDE(900ms)→NONE→OVERHEAD→… 的死循环（观感即反复落地-俯瞰）。
     * 现约定：<b>只有当部署屏关闭、玩家已自行存活在世界里</b>（如 vanilla 重生）才自动滑落，
     * 部署屏打开期间始终稳稳停在 OVERHEAD，不再自跳。
     */
    private static volatile boolean deployScreenOpen = false;
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

    /** 当前状态机阶段（部署屏据此判断是否隐藏第一人称手部）。 */
    public static Mode mode() {
        return mode;
    }

    /** 部署屏打开/关闭时由 BfDeployScreen 调用，避免相机在屏开期间自跳。 */
    public static void setDeployScreenOpen(boolean open) {
        deployScreenOpen = open;
        if (!open && mode == Mode.OVERHEAD) {
            // 屏关闭且仍停在俯瞰（如初始部署 ESC 退出）：交由 cameraPose 正常滑落到第一人称
            glideStart = System.currentTimeMillis();
            mode = Mode.GLIDE;
        }
    }

    /**
     * CameraMixin 在 Camera.update TAIL 调用。
     * 返回覆写相机坐标；rotOut[0]=yaw、rotOut[1]=pitch；返回 null 表示不接管。
     */
    public static double[] cameraPose(float[] rotOut, Entity focused) {
        if (focused == null) {
            return null;
        }
        // 仅在「部署屏已关闭、玩家已存活于世界」时自动滑落（如 vanilla 重生键）。
        // 部署屏打开期间（deployScreenOpen=true）一律稳住 OVERHEAD，不自动跳，根治反复落地-俯瞰。
        if (mode == Mode.OVERHEAD && focused.isAlive() && !deployScreenOpen) {
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
