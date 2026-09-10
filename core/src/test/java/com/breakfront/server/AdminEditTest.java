package com.breakfront.server;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 管理台地图编辑后端逻辑的纯 Java 单测（2026-09-10 v1）。
 *
 * 覆盖两个关键回归点：
 *   1) {@link Json} 解析器 —— 原 indexOf 式解析在「顶层平铺 + payload 嵌套」并存时取错字段，
 *      是「地图编辑器完全不可用」的地基性缺陷；本测试钉死正确取值语义。
 *   2) 坐标语义 —— ZoneAnchor.radius = 半边长（方形），X/Z 为中心。
 */
public final class AdminEditTest {

    private static int pass = 0, fail = 0;

    public static void main(String[] args) {
        testFlatBody();
        testNestedPayload();
        testNegativeAndDecimal();
        testSameKeyShadowing();
        testEscapes();
        testMalformed();
        testZoneAnchorSemantics();

        System.out.println("---- AdminEditTest: " + pass + " passed, " + fail + " failed ----");
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 平铺请求体：所有字段在顶层。 */
    private static void testFlatBody() {
        Map<String, Object> m = Json.parseObject(
                "{\"token\":\"abc\",\"op\":\"add\",\"name\":\"add\",\"x\":12.5,\"z\":-30,\"r\":6,\"payload\":{\"x\":12.5,\"z\":-30}}");
        check("flat: op", "add".equals(Json.str(m, "op")));
        check("flat: x", Json.dbl(m, "x", -1) == 12.5);
        check("flat: z 负数", Json.dbl(m, "z", 0) == -30.0);
        check("flat: r", Json.dbl(m, "r", 0) == 6.0);
        Map<String, Object> p = Json.obj(m, "payload");
        check("flat: payload 可读", p != null);
        check("flat: payload 优先取值", Json.dbl(p, "z", 0) == -30.0);
    }

    /** 仅嵌套 payload（前端 sendEdit 同时发两种，服务端应兼容）。 */
    private static void testNestedPayload() {
        Map<String, Object> m = Json.parseObject(
                "{\"op\":\"move\",\"payload\":{\"id\":\"A1\",\"x\":100,\"z\":200}}");
        Map<String, Object> p = Json.obj(m, "payload");
        check("nested: payload 非空", p != null);
        check("nested: id", "A1".equals(Json.str(p, "id")));
        check("nested: x", Json.dbl(p, "x", 0) == 100.0);
    }

    /** 负数、小数、科学计数、整数键共存。 */
    private static void testNegativeAndDecimal() {
        Map<String, Object> m = Json.parseObject(
                "{\"x\":-12.75,\"z\":0.5,\"r\":1e2,\"n\":0}");
        check("num: 负小数", Json.dbl(m, "x", 0) == -12.75);
        check("num: 正小数", Json.dbl(m, "z", 0) == 0.5);
        check("num: 科学计数", Json.dbl(m, "r", 0) == 100.0);
        check("num: 零", Json.dbl(m, "n", -1) == 0.0);
    }

    /** 同名键遮蔽：旧解析器会取到第一个 "x"（可能是无关字段）→ 钉死取值正确性。 */
    private static void testSameKeyShadowing() {
        // 顶层 x 与嵌套 x 不同，payload 应胜出
        Map<String, Object> m = Json.parseObject(
                "{\"op\":\"move\",\"x\":1,\"payload\":{\"id\":\"B2\",\"x\":999}}");
        Map<String, Object> p = Json.obj(m, "payload");
        check("shadow: 顶层 x=1", Json.dbl(m, "x", 0) == 1.0);
        check("shadow: payload x=999", Json.dbl(p, "x", 0) == 999.0);
        // 键名含在别处（如 "max" 里含 "x"）不应误命中
        Map<String, Object> m2 = Json.parseObject("{\"max\":7,\"x\":42}");
        check("shadow: max 不干扰 x", Json.dbl(m2, "x", 0) == 42.0);
        check("shadow: max 本身", Json.dbl(m2, "max", 0) == 7.0);
    }

    /** 转义与 Unicode。 */
    private static void testEscapes() {
        Map<String, Object> m = Json.parseObject(
                "{\"name\":\"扇区 \\\"一号\\\"\",\"p\":\"a\\\\b\",\"u\":\"\\u4e2d\\u6587\"}");
        check("esc: 引号", "扇区 \"一号\"".equals(Json.str(m, "name")));
        check("esc: 反斜杠", "a\\b".equals(Json.str(m, "p")));
        check("esc: unicode", "中文".equals(Json.str(m, "u")));
    }

    /** 畸形输入必须抛异常（而非静默返回错误值）。 */
    private static void testMalformed() {
        checkThrows("malformed: 未闭合", "{\"a\":1");
        checkThrows("malformed: 缺冒号", "{\"a\" 1}");
        checkThrows("malformed: 空", "");
        checkThrows("malformed: 尾部垃圾", "{\"a\":1}xyz");
        // parseObject 额外要求顶层是对象（parse 本身允许任意 JSON 值）
        checkThrowsObject("malformed: 非对象", "\"just a string\"");
        checkThrowsObject("malformed: 数组", "[1,2,3]");
    }

    /** ZoneAnchor 方形语义：radius 为半边长，边界含等号。 */
    private static void testZoneAnchorSemantics() {
        ZoneAnchor a = new ZoneAnchor("Z1", 10, 20, 6);
        check("anchor: 中心内", a.contains(10, 20));
        check("anchor: 边界点内", a.contains(16, 26));
        check("anchor: 越界（x）", !a.contains(16.1, 20));
        check("anchor: 越界（z）", !a.contains(10, 26.1));
        check("anchor: 对角外（方形非圆）", a.contains(15.9, 25.9));
    }

    // ---- 断言工具 ----

    private static void check(String name, boolean cond) {
        if (cond) {
            pass++;
        } else {
            fail++;
            System.out.println("[FAIL] " + name);
        }
    }

    private static void checkThrows(String name, String input) {
        try {
            Json.parse(input);
            fail++;
            System.out.println("[FAIL] " + name + "（未抛异常）");
        } catch (Json.JsonException e) {
            pass++;
        }
    }

    private static void checkThrowsObject(String name, String input) {
        try {
            Json.parseObject(input);
            fail++;
            System.out.println("[FAIL] " + name + "（未抛异常）");
        } catch (Json.JsonException e) {
            pass++;
        }
    }
}
