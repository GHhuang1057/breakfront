package com.breakfront.client.bf;

import org.lwjgl.glfw.GLFW;

/**
 * BREAKFRONT 自定义按键统一定义。
 *
 * 管理键为何不用 F8/F6：F8 被原版硬绑定为「平滑镜头 / 电影镜头」，组合按下会
 * 同步切换电影镜头，叠加光影的运动模糊/TAA 后画面表现为「动了但发糊」；F6 在
 * 部分渲染环境（Sodium/Iris）仍会触发可疑的画面变化（用户实测仍有模糊）。最终
 * 弃用 F 功能行，改走无任何默认/渲染绑定的 M 键组合 Ctrl+Shift+M（Manager）。
 */
public final class BfKeys {

    /** 管理员门禁 / 管控面板主键（需与 CONTROL|SHIFT 组合触发）。 */
    public static final int ADMIN = GLFW.GLFW_KEY_M;

    /** 显示用组合名（与 {@link #ADMIN} 配套）。 */
    public static final String ADMIN_CHORD = "Ctrl+Shift+M";

    private BfKeys() {
    }
}
