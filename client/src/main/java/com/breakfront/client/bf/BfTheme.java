package com.breakfront.client.bf;

/**
 * BREAKFRONT UI 设计令牌（#44 主题：2042 科幻蓝绿——深空冷底 + 荧光青绿强调 +
 * 自然的辉光层次）。所有颜色 0xAARRGGBB，全屏界面与游戏内 HUD 共用同一套语言。
 * 强调色块的自然发光请走 {@link BfGlow}（多层半透明外扩辉光），勿直接用实心纯色。
 */
public final class BfTheme {

    private BfTheme() {
    }

    // 背景 / 表面
    public static final int BG_DEEP = 0xFF070B12;      // 画布底色（深空蓝黑）
    public static final int BG_UP = 0xFF101A26;        // 渐变高处（深海蓝）
    public static final int PANEL = 0xE6131E2C;        // 面板
    public static final int PANEL_LINE = 0xFF22364A;   // 分隔/描边
    public static final int SCREEN_DIM = 0xCC02050A;   // 全屏蒙层

    // 语义色（功能色：攻/守/危险/成功）
    public static final int ORANGE = 0xFFE8622C;       // 警示
    public static final int ORANGE_SOFT = 0xCCE8622C;
    public static final int AMBER = 0xFFE8B93C;        // 次级强调（保留少量暖色区分）
    public static final int YELLOW = 0xFFF5D44A;       // 旧强调（HUD 遗留语义用，新 UI 勿用）
    public static final int YELLOW_DIM = 0x80F5D44A;
    public static final int BLUE = 0xFF4DA6FF;         // 守方/信息
    public static final int RED = 0xFFFF4A3C;          // 危险
    public static final int GREEN = 0xFF3EE88C;        // 成功/就绪/攻方占点

    // 2042 科幻蓝绿主强调（新 UI 一律使用；色块需要辉光时配 BfGlow）
    public static final int TEAL = 0xFF35E6D2;         // 主强调：荧光青
    public static final int TEAL_DIM = 0x8035E6D2;
    public static final int TEAL_SOFT = 0xCC35E6D2;
    public static final int CYAN = 0xFF6FE8FF;         // 冷青高光（争夺/信息）
    public static final int CYAN_DIM = 0x806FE8FF;

    // 文本
    public static final int TEXT = 0xFFEAF2F8;
    public static final int TEXT_DIM = 0xB8CFE4F5;
    public static final int MUTED = 0xFF7E93A8;
    public static final int FAINT = 0x667E93A8;

    // 动效（秒）
    public static final double T_FAST = 0.18;
    public static final double T_MID = 0.32;
    public static final double T_SLOW = 0.55;

    /** argb 拆 rgba(0..255)。 */
    public static int[] rgba(int argb) {
        return new int[]{(argb >> 16) & 0xFF, (argb >> 8) & 0xFF, argb & 0xFF, (argb >>> 24) & 0xFF};
    }
}
