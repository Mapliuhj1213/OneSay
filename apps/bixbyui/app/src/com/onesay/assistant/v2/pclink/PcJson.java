package com.onesay.assistant.v2.pclink;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * [pclink] 电脑联动用的极简 JSON —— <b>逐字节照抄电脑端 OneSay {@code com.onesay.desktop.Json}</b>（0.4.1），因为签名是对
 * 「键字典序、无空白」的规范串算的，两端规范化必须一模一样（不能用 org.json：键序不定、数字/转义写法不同）。
 *
 * <p>解析为 TreeMap / ArrayList / String / Long / Double / Boolean / null；输出键按 {@link String#compareTo} 排序，
 * 整数写成 long，有限双精度整值写成 long，其余用 {@link Double#toString}；字符串只转义 {@code " \ \n \r \t \b \f} 和 &lt; 0x20 的控制字符。
 * 纯 Java（Java 7 语法），JVM 可测。
 */
public final class PcJson {

    private PcJson() {}

    /** JSON 数字语法：-?(0|[1-9]数字*)(.数字+)?([eE][+-]?数字+)? */
    private static final java.util.regex.Pattern NUM = java.util.regex.Pattern.compile("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");

    public static Object parse(String s) {
        P p = new P(s);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != s.length()) throw new IllegalArgumentException("trailing data at " + p.i);
        return v;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(String s) {
        Object o = parse(s);
        if (!(o instanceof Map)) throw new IllegalArgumentException("not an object");
        return (Map<String, Object>) o;
    }

    public static String stringify(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void write(StringBuilder sb, Object v) {
        if (v == null) sb.append("null");
        else if (v instanceof String) str(sb, (String) v);
        else if (v instanceof Boolean) sb.append(v);
        else if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) sb.append(((Number) v).longValue());
        else if (v instanceof Number) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("non-finite number");
            if (d == Math.rint(d) && Math.abs(d) < 1e15) sb.append((long) d);
            else sb.append(d);
        } else if (v instanceof Map) {
            TreeMap<String, Object> m = new TreeMap<String, Object>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) v).entrySet()) m.put(String.valueOf(e.getKey()), e.getValue());
            sb.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : m.entrySet()) {
                if (!first) sb.append(',');
                first = false;
                str(sb, e.getKey());
                sb.append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
        } else if (v instanceof List) {
            sb.append('[');
            boolean first = true;
            for (Object x : (List<Object>) v) {
                if (!first) sb.append(',');
                first = false;
                write(sb, x);
            }
            sb.append(']');
        } else throw new IllegalArgumentException("unsupported type " + v.getClass());
    }

    private static void str(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }

    private static final class P {
        final String s;
        int i;

        P(String s) {
            this.s = s;
        }

        void ws() {
            while (i < s.length() && " \t\r\n".indexOf(s.charAt(i)) >= 0) i++;
        }

        char peek() {
            if (i >= s.length()) throw new IllegalArgumentException("unexpected end");
            return s.charAt(i);
        }

        Object value(int depth) {
            if (depth > 64) throw new IllegalArgumentException("too deep");
            char c = peek();
            if (c == '{') return object(depth);
            if (c == '[') return array(depth);
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return null; }
            return number();
        }

        Map<String, Object> object(int depth) {
            TreeMap<String, Object> m = new TreeMap<String, Object>();
            i++;
            ws();
            if (peek() == '}') { i++; return m; }
            while (true) {
                ws();
                if (peek() != '"') throw new IllegalArgumentException("expected key at " + i);
                String k = string();
                ws();
                if (peek() != ':') throw new IllegalArgumentException("expected : at " + i);
                i++;
                ws();
                m.put(k, value(depth + 1));
                ws();
                char c = peek();
                i++;
                if (c == '}') return m;
                if (c != ',') throw new IllegalArgumentException("expected , at " + (i - 1));
            }
        }

        List<Object> array(int depth) {
            List<Object> l = new ArrayList<Object>();
            i++;
            ws();
            if (peek() == ']') { i++; return l; }
            while (true) {
                ws();
                l.add(value(depth + 1));
                ws();
                char c = peek();
                i++;
                if (c == ']') return l;
                if (c != ',') throw new IllegalArgumentException("expected , at " + (i - 1));
            }
        }

        String string() {
            i++;
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = peek();
                i++;
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    char e = peek();
                    i++;
                    switch (e) {
                        case '"': sb.append('"'); break;
                        case '\\': sb.append('\\'); break;
                        case '/': sb.append('/'); break;
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'b': sb.append('\b'); break;
                        case 'f': sb.append('\f'); break;
                        case 'u':
                            if (i + 4 > s.length()) throw new IllegalArgumentException("bad \\u");
                            // 必须正好 4 位十六进制（parseInt 会收 +041、-001 这种，JSON 不收）
                            for (int k = i; k < i + 4; k++)
                                if (Character.digit(s.charAt(k), 16) < 0 || s.charAt(k) > 'f') throw new IllegalArgumentException("bad \\u");
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw new IllegalArgumentException("bad escape");
                    }
                } else sb.append(c);
            }
        }

        Object number() {
            int st = i;
            if (peek() == '-') i++;
            while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
            String t = s.substring(st, i);
            if (t.length() == 0 || t.equals("-")) throw new IllegalArgumentException("bad value at " + st);
            // 2026-11-14 对抗测试 P2-3：按 JSON 语法收数字（不收前导 +、前导 0），超出 double 范围（1e999）当坏数据，不变成 Infinity
            if (!NUM.matcher(t).matches()) throw new IllegalArgumentException("bad number at " + st);
            if (t.indexOf('.') < 0 && t.indexOf('e') < 0 && t.indexOf('E') < 0) {
                try {
                    return Long.parseLong(t);
                } catch (NumberFormatException ignored) {
                    // 超出 long：按 double
                }
            }
            double d = Double.parseDouble(t);
            if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("number out of range at " + st);
            return d;
        }
    }
}
