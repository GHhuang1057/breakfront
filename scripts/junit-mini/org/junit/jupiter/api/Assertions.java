package org.junit.jupiter.api;

import java.util.Objects;

/**
 * JUnit 5 {@code Assertions} 的最小本地替身（仅供 scripts/run_core_tests.sh 使用）。
 *
 * <p>只实现 core 测试实际用到的断言重载。语义与 JUnit 一致：失败抛
 * {@link AssertionError}（消息优先取调用方传入的 message）。
 *
 * <p><b>不要放进 src/test/java</b>：会与真实 JUnit 依赖冲突。
 */
public final class Assertions {

    private Assertions() {
    }

    // ---- assertEquals ----

    public static void assertEquals(Object expected, Object actual) {
        assertEquals(expected, actual, (String) null);
    }

    public static void assertEquals(Object expected, Object actual, String message) {
        if (!Objects.equals(expected, actual)) {
            fail(message, "期望 <" + expected + "> 但实际为 <" + actual + ">");
        }
    }

    public static void assertEquals(long expected, long actual) {
        assertEquals(expected, actual, (String) null);
    }

    public static void assertEquals(long expected, long actual, String message) {
        if (expected != actual) {
            fail(message, "期望 <" + expected + "> 但实际为 <" + actual + ">");
        }
    }

    public static void assertEquals(double expected, double actual, double delta) {
        assertEquals(expected, actual, delta, null);
    }

    public static void assertEquals(double expected, double actual, double delta, String message) {
        if (Math.abs(expected - actual) > delta) {
            fail(message, "期望 <" + expected + "> (±" + delta + ") 但实际为 <" + actual + ">");
        }
    }

    // ---- assertTrue / assertFalse ----

    public static void assertTrue(boolean condition) {
        assertTrue(condition, null);
    }

    public static void assertTrue(boolean condition, String message) {
        if (!condition) {
            fail(message, "期望为 true 但实际为 false");
        }
    }

    public static void assertFalse(boolean condition) {
        assertFalse(condition, null);
    }

    public static void assertFalse(boolean condition, String message) {
        if (condition) {
            fail(message, "期望为 false 但实际为 true");
        }
    }

    // ---- assertNull / assertNotNull ----

    public static void assertNull(Object actual) {
        if (actual != null) {
            fail(null, "期望为 null 但实际为 <" + actual + ">");
        }
    }

    public static void assertNotNull(Object actual) {
        if (actual == null) {
            fail(null, "期望非 null 但实际为 null");
        }
    }

    // ---- fail ----

    public static void fail(String message) {
        throw new AssertionError(message == null ? "断言失败" : message);
    }

    private static void fail(String message, String detail) {
        throw new AssertionError(message == null ? detail : message + " —— " + detail);
    }
}
