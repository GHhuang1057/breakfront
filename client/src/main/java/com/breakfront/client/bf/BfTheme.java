package com.breakfront.client.bf;

/**
 * BREAKFRONT UI 设计令牌（BF2042 精神：深色冷底 + 高对比色块 + 橙金强调）。
 * 所有颜色 0xAARRGGBB，全屏界面与游戏内 HUD 共用同一套语言。
 */
public final class BfTheme {

    private BfTheme() {
    }

    // 背景 / 表面
    public static final int BG_DEEP = 0xFF0A0D12;      // 画布底色
    public static final int BG_UP = 0xFF12171F;        // 渐变高处
    public static final int PANEL = 0xE61A222D;        // 面板
    public static final int PANEL_LINE = 0xFF2C3644;   // 分隔/描边
    public static final int SCREEN_DIM = 0xCC05070A;   // 全屏蒙层

    // 语义色
    public static final int ORANGE = 0xFFE8622C;       // 主强调（攻方/行动）
    public static final int ORANGE_SOFT = 0xCCE8622C;
    public static final int AMBER = 0xFFE8B93C;        // 次级强调
    public static final int BLUE = 0xFF4DA6FF;         // 守方/信息
    public static final int RED = 0xFFFF4A3C;          // 危险
    public static final int GREEN = 0xFF5FCE6A;        // 成功/就绪

    // 文本
    public static final int TEXT = 0xFFF2F4F8;
    public static final int TEXT_DIM = 0xB8FFFFFF;
    public static final int MUTED = 0xFF8A94A3;
    public static final int FAINT = 0x668A94A3;

    // 动效（秒）
    public static final double T_FAST = 0.18;
    public static final double T_MID = 0.32;
    public static final double T_SLOW = 0.55;

    /** argb 拆 rgba(0..255)。 */
    public static int[] rgba(int argb) {
        return new int[]{(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF};
    }
}
