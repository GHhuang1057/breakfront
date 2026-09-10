package com.breakfront.server;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器（2026-09-10 v1）。
 *
 * 背景：管理台原用 {@code body.indexOf("\"key\"")} 之类的字符串截取解析请求体，
 * 一旦 body 中出现同名键（如平铺字段 + payload 嵌套）或含空格/转义的值，
 * 就会取错字段 —— 这是「地图编辑器完全不可用」的地基性缺陷。
 * 本类提供正确的一次性解析：对象 → {@link Map}，数组 → {@link List}，
 * 字符串/数值/布尔/null 分别映射为 String/Double/Boolean/null。
 *
 * 设计取舍：只服务管理台的请求体（体积小、结构浅），因此
 *   - 不做流式解析，整体递归下降，代码可控可审；
 *   - 支持 \ uXXXX 转义、常用转义序列、整数/浮点/科学计数；
 *   - 非法输入抛 {@link JsonException}，由调用方转成 400。
 */
final class Json {

    /** 解析失败（语法错误/结构不符）。 */
    static final class JsonException extends RuntimeException {
        JsonException(String msg) {
            super(msg);
        }
    }

    private final String s;
    private int i;

    private Json(String s) {
        this.s = s;
        this.i = 0;
    }

    /** 解析一个完整 JSON 值；要求前后无非空白内容。 */
    static Object parse(String text) {
        if (text == null) {
            throw new JsonException("空请求体");
        }
        Json p = new Json(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i < p.s.length()) {
            throw new JsonException("尾部多余内容 @" + p.i);
        }
        return v;
    }

    /** 解析并断言为对象（管理台请求体一律为对象）。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof Map)) {
            throw new JsonException("请求体必须是 JSON 对象");
        }
        return (Map<String, Object>) v;
    }

    // ---- 递归下降 ----

    private Object value() {
        if (i >= s.length()) {
            throw new JsonException("内容意外结束");
        }
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object();
            case '[' -> array();
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        expect('{');
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') {
                throw new JsonException("对象的键必须是字符串 @" + i);
            }
            String k = string();
            ws();
            expect(':');
            ws();
            m.put(k, value());
            ws();
            char c = peek();
            if (c == ',') {
                i++;
                continue;
            }
            if (c == '}') {
                i++;
                return m;
            }
            throw new JsonException("对象缺少 , 或 } @" + i);
        }
    }

    private List<Object> array() {
        List<Object> list = new ArrayList<>();
        expect('[');
        ws();
        if (peek() == ']') {
            i++;
            return list;
        }
        while (true) {
            ws();
            list.add(value());
            ws();
            char c = peek();
            if (c == ',') {
                i++;
                continue;
            }
            if (c == ']') {
                i++;
                return list;
            }
            throw new JsonException("数组缺少 , 或 ] @" + i);
        }
    }

    private String string() {
        expect('"');
        StringBuilder sb = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') {
                return sb.toString();
            }
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            if (i >= s.length()) {
                throw new JsonException("转义序列不完整");
            }
            char e = s.charAt(i++);
            switch (e) {
                case '"' -> sb.append('"');
                case '\\' -> sb.append('\\');
                case '/' -> sb.append('/');
                case 'b' -> sb.append('\b');
                case 'f' -> sb.append('\f');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 't' -> sb.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) {
                        throw new JsonException("unicode 转义不完整");
                    }
                    sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                }
                default -> throw new JsonException("未知转义 \\" + e);
            }
        }
        throw new JsonException("字符串未闭合");
    }

    private Object literal(String word, Object val) {
        if (!s.startsWith(word, i)) {
            throw new JsonException("非法字面量 @" + i);
        }
        i += word.length();
        return val;
    }

    private Double number() {
        int start = i;
        if (peek() == '-') {
            i++;
        }
        while (i < s.length()) {
            char c = s.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                i++;
            } else {
                break;
            }
        }
        if (i == start) {
            throw new JsonException("非法数值 @" + start);
        }
        try {
            return Double.parseDouble(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw new JsonException("数值格式错误：" + s.substring(start, i));
        }
    }

    // ---- 便捷读取（供 WebAdminConsole 使用）----

    /** 取字符串字段；缺失或非字符串返回 null。 */
    static String str(Map<String, Object> o, String key) {
        Object v = o.get(key);
        return v instanceof String sv ? sv : null;
    }

    /** 取数值字段；缺失/非数值返回默认值。 */
    static double dbl(Map<String, Object> o, String key, double dflt) {
        Object v = o.get(key);
        if (v instanceof Double d) {
            return d;
        }
        if (v instanceof String sv) {
            try {
                return Double.parseDouble(sv.trim());
            } catch (NumberFormatException ignored) {
                return dflt;
            }
        }
        return dflt;
    }

    /** 取布尔字段；缺失返回默认值。 */
    static boolean bool(Map<String, Object> o, String key, boolean dflt) {
        Object v = o.get(key);
        return v instanceof Boolean b ? b : dflt;
    }

    /** 取嵌套对象；缺失/类型不符返回 null。 */
    @SuppressWarnings("unchecked")
    static Map<String, Object> obj(Map<String, Object> o, String key) {
        Object v = o.get(key);
        return v instanceof Map ? (Map<String, Object>) v : null;
    }

    // ---- 基础 ----

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                i++;
            } else {
                break;
            }
        }
    }

    private char peek() {
        if (i >= s.length()) {
            throw new JsonException("内容意外结束");
        }
        return s.charAt(i);
    }

    private void expect(char c) {
        if (i >= s.length() || s.charAt(i) != c) {
            throw new JsonException("期望 '" + c + "' @" + i);
        }
        i++;
    }
}
