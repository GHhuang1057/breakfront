package com.breakfront.client.bf;

import org.lwjgl.glfw.GLFW;

/**
 * BREAKFRONT 自定义按键统一定义。
 *
 * 管理员面板键为何不用 F8：原版 Minecraft 把 F8 硬绑定为「平滑镜头 / 电影镜头」
 * （非可配置功能键），与 Ctrl+Shift 组合按下时仍会同步切换电影镜头，叠加光影的
 * 运动模糊/TAA 后画面表现为「动了但发糊」。改用原版无绑定的 F6 规避冲突。
 */
public final class BfKeys {

    /** 管理员门禁 / 管控面板主键（需与 CONTROL|SHIFT 组合触发）。 */
    public static final int ADMIN = GLFW.GLFW_KEY_F6;

    /** 显示用组合名（与 {@link #ADMIN} 配套）。 */
    public static final String ADMIN_CHORD = "Ctrl+Shift+F6";

    private BfKeys() {
    }
}
