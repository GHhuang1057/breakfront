package com.breakfront.client.hud;

import com.breakfront.client.state.ClientMatchState;
import net.minecraft.client.MinecraftClient;
import org.lwjgl.glfw.GLFW;

import java.util.Set;

/**
 * BREAKFRONT 键位方案（2026-09-11 用户确认）：
 *   保留底层移动（W A S D / Space / 蹲 / 疾跑）、Tab 计分板、Esc、1-9 武器位；
 *   新增 R 装填（TaCZ 自带键位）、Y 聊天（替换被屏蔽的原版 T）、B 死亡时打开部署屏。
 *   **战斗阶段（COUNTDOWN/BATTLE）白名单制**：未列出的原版键一律拦截
 *   （E 背包 / Q 丢弃 / T 聊天 / F 副手 / F1/F3/F5 / 各功能字母……），
 *   大厅与非游戏界面不拦截；Screen 打开时交给 Screen 自理（Esc 关屏等）。
 */
public final class BfKeymap {

    private static final Set<Integer> ALLOWED = Set.of(
            GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_D,
            GLFW.GLFW_KEY_SPACE, GLFW.GLFW_KEY_LEFT_SHIFT, GLFW.GLFW_KEY_LEFT_CONTROL,
            GLFW.GLFW_KEY_C,                                  // 2042 布局：蹲=C（见 BreakfrontClient 重映射）
            GLFW.GLFW_KEY_1, GLFW.GLFW_KEY_2, GLFW.GLFW_KEY_3, GLFW.GLFW_KEY_4,
            GLFW.GLFW_KEY_5, GLFW.GLFW_KEY_6, GLFW.GLFW_KEY_7, GLFW.GLFW_KEY_8,
            GLFW.GLFW_KEY_9,
            GLFW.GLFW_KEY_TAB, GLFW.GLFW_KEY_ESCAPE,
            GLFW.GLFW_KEY_R, GLFW.GLFW_KEY_Y, GLFW.GLFW_KEY_B);

    private BfKeymap() {}

    /** 部署/战斗阶段（含倒计时——部署屏即在此弹）。 */
    public static boolean battle() {
        int p = ClientMatchState.phaseOrdinal();
        return p == 1 || p == 2;
    }

    /** true = Keyboard.onKey 拦截该按键（只拦 PRESS/REPEAT，RELEASE 永远放行防卡键）。 */
    public static boolean shouldBlock(int key, int action, MinecraftClient client) {
        if (action == GLFW.GLFW_RELEASE) {
            return false;
        }
        if (client.currentScreen != null) {
            return false;                       // Screen 打开：由 Screen 键盘处理自理
        }
        if (!battle()) {
            return false;                       // 大厅/回合结束不锁键
        }
        return !ALLOWED.contains(key);
    }
}
