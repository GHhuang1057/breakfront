package com.breakfront.client.bf;

/**
 * 交互动画缓动工具。t∈[0,1] 进出函数。
 */
public final class BfEasing {

    private BfEasing() {
    }

    public static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    public static double easeOutCubic(double t) {
        t = clamp01(t);
        double u = 1 - t;
        return 1 - u * u * u;
    }

    public static double easeInOutCubic(double t) {
        t = clamp01(t);
        return t < 0.5 ? 4 * t * t * t : 1 - Math.pow(-2 * t + 2, 3) / 2;
    }

    public static double easeOutQuint(double t) {
        t = clamp01(t);
        double u = 1 - t;
        return 1 - u * u * u * u * u;
    }

    public static double easeOutBack(double t) {
        t = clamp01(t);
        double c1 = 1.70158;
        double c3 = c1 + 1;
        double u = t - 1;
        return 1 + c3 * u * u * u + c1 * u * u;
    }

    /** 线性插值。 */
    public static int lerp(int a, int b, double t) {
        return (int) Math.round(a + (b - a) * clamp01(t));
    }

    /** 把元素做「浮入」过渡：返回当前透明度（0..255），位移供调用方自取。 */
    public static double staged(double age, double delay, double dur) {
        return easeOutCubic((age - delay) / dur);
    }
}
