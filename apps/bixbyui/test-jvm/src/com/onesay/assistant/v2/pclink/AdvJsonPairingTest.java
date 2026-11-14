package com.onesay.assistant.v2.pclink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · 电脑联动报文解析：
 * <ul>
 *   <li>json：PcJson 合法输入 → 规范串（键字典序、无空白、整数写成 long、整值 double 写成 long）；畸形（截断、尾随垃圾、缺逗号 / 冒号、
 *       单引号、注释、非法转义、裸词、NaN / Infinity 字面量、深度超过 64）→ IllegalArgumentException；嵌套深度逐层到边界；</li>
 *   <li>pair：配对串（含输入法全角改写、零宽、首字母大写、前后夹杂文字）→ 解析或给出不含密钥的错误；v2 指纹格式、版本、钥匙长度、端口。</li>
 * </ul>
 * 「\\u+041」「+1」「01」「1e999」这类不合 JSON 语法却被接受的写法在 AdvPcJsonLenientTest（2026-11-14 已修）。
 */
@RunWith(Parameterized.class)
public class AdvJsonPairingTest {

    static String key32() {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) (200 - i);
        return PcB64.encodeUrl(k);
    }

    static String fp() {
        byte[] k = new byte[32];
        for (int i = 0; i < 32; i++) k[i] = (byte) (i * 3);
        return PcB64.encodeUrl(k);
    }

    static String nest(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append('[');
        for (int i = 0; i < n; i++) b.append(']');
        return b.toString();
    }

    static String nestObj(int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append("{\"a\":");
        b.append("1");
        for (int i = 0; i < n; i++) b.append('}');
        return b.toString();
    }

    @Parameterized.Parameters(name = "{index}: {0} {1}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        String[][] good = {
                {"{}", "{}"}, {"[]", "[]"}, {" { } ", "{}"}, {"{\"b\":1,\"a\":2}", "{\"a\":2,\"b\":1}"}, {"{\"a\":1.0}", "{\"a\":1}"}, {"{\"a\":-0.5}", "{\"a\":-0.5}"},
                {"{\"a\":1e3}", "{\"a\":1000}"}, {"{\"a\":9223372036854775807}", "{\"a\":9223372036854775807}"}, {"{\"a\":\"x\\ny\"}", "{\"a\":\"x\\ny\"}"},
                {"{\"a\":\"\\u4e2d\"}", "{\"a\":\"中\"}"}, {"{\"a\":\"\\/\"}", "{\"a\":\"/\"}"}, {"{\"a\":\"\\u0001\"}", "{\"a\":\"\\u0001\"}"},
                {"{\"a\":true,\"b\":false,\"c\":null}", "{\"a\":true,\"b\":false,\"c\":null}"}, {"[1,\"2\",[3],{\"4\":4}]", "[1,\"2\",[3],{\"4\":4}]"},
                {"\t\r\n{\"a\"\n:\n1\n}\n", "{\"a\":1}"}, {"{\"a\":1,\"a\":2}", "{\"a\":2}"}, {"{\"B\":1,\"a\":1,\"_\":1}", "{\"B\":1,\"_\":1,\"a\":1}"},
                {"\"str\"", "\"str\""}, {"123", "123"}, {"-7", "-7"}, {"{\"emoji\":\"\\ud83d\\ude00\"}", "{\"emoji\":\"\uD83D\uDE00\"}"},
                {"{\"q\":\"\\\"\"}", "{\"q\":\"\\\"\"}"}, {"{\"k\":\"\\b\\f\\r\\t\"}", "{\"k\":\"\\b\\f\\r\\t\"}"}, {nest(65), nest(65)}, {nestObj(64), nestObj(64)},
        };
        for (String[] g : good) add(out, seen, "json", g[0], g[1]);
        String[] bad = {"", " ", "{", "}", "[", "{\"a\":1", "{\"a\":1}x", "{\"a\" 1}", "{\"a\":1 \"b\":2}", "{'a':1}", "{a:1}", "{\"a\":1,}", "[1,]", "[,1]",
                "{\"a\":tru}", "{\"a\":nul}", "{\"a\":NaN}", "{\"a\":Infinity}", "{\"a\":-Infinity}", "{\"a\":\"x}", "{\"a\":\"\\x\"}", "{\"a\":\"\\u12\"}",
                "{\"a\":\"\\uZZZZ\"}", "/*c*/{}", "{}//c", "{\"a\":1}{}", "{\"a\":-}", "{\"a\":1.2.3}", "{\"a\":--1}", "[1 2]", "{\"a\"::1}", "{,}",
                nest(66), nestObj(65), "\u0000", "{\"a\":1}\u0000", "{\"a\":\u00a01}"};
        for (String b : bad) add(out, seen, "jsonBad", b, "");
        String k = key32(), f = fp();
        String base = "onesay://pair?key=" + k + "&peer=pc-home&lan=192.168.31.20%3A47820";
        String[][] pair = {
                {base, "ok|pc-home|192.168.31.20:47820|47820|1"},
                {"配对串：" + base + "（在电脑上复制的）", "ok|pc-home|192.168.31.20:47820|47820|1"},
                {base.replace("?", "？").replace("&", "＆").replace("=", "＝"), "ok|pc-home|192.168.31.20:47820|47820|1"},
                {base.replace("onesay://pair?key=", "Onesay://pair?Key="), "ok|pc-home|192.168.31.20:47820|47820|1"},
                {base.replace("&peer", "\u200b&peer").replace("lan=", "lan= "), "ok|pc-home|192.168.31.20:47820|47820|1"},
                {base + "。", "ok|pc-home|192.168.31.20:47820|47820|1"},
                {base + "&fp=" + f + "&v=2", "ok|pc-home|192.168.31.20:47820|47820|2"},
                {"onesay://pair?key=" + k + "&peer=pc&lan=0.0.0.0%3A48000", "ok|pc||48000|1"},
                {"onesay://pair?key=" + k + "&peer=pc&lan=1.2.3.4%3A5000%2C0.0.0.0%3A5000%2C5.6.7.8%3A5000", "ok|pc|1.2.3.4:5000,5.6.7.8:5000|5000|1"},
                {"onesay://pair?key=" + k + "&peer=a_b.c-d&lan=1.2.3.4%3A1", "ok|a_b.c-d|1.2.3.4:1|1|1"},
                {"onesay://pair?key=" + k + "&peer=pc&lan=1.2.3.4%3A65534", "ok|pc|1.2.3.4:65534|65534|1"},
                {"onesay://pair?peer=pc&lan=1.2.3.4%3A5000", "bad"}, {"onesay://pair?key=" + k + "&lan=1.2.3.4%3A5000", "bad"},
                {"onesay://pair?key=" + k + "&peer=pc", "bad"}, {"onesay://pair?key=AAAA&peer=pc&lan=1.2.3.4%3A5000", "bad"},
                {"onesay://pair?key=" + k + "&peer=p%20c&lan=1.2.3.4%3A5000", "bad"}, {"onesay://pair?key=" + k + "&peer=" + new String(new char[65]).replace('\0', 'a') + "&lan=1.2.3.4%3A5000", "bad"},
                {"onesay://pair?key=" + k + "&peer=pc&lan=1.2.3.4%3A5000&v=3", "bad"}, {"onesay://pair?key=" + k + "&peer=pc&lan=1.2.3.4%3A5000&v=0", "bad"},
                {"onesay://pair?key=" + k + "&peer=pc&lan=1.2.3.4%3A5000&v=x", "bad"}, {"onesay://pair?key=" + k + "&peer=pc&lan=1.2.3.4%3A5000&v=2", "bad"},
                {"onesay://pair?key=" + k + "&peer=pc&lan=1.2.3.4%3A5000&fp=short&v=2", "bad"}, {"onesay://pair?key=" + k + "&peer=pc&lan=1.2.3.4%3A5000&fp=" + f + "x", "bad"},
                {"onesay://pair?key=" + k + "&peer=pc&relay=https%3A%2F%2Fr.example&token=short", "bad"}, {"onesay://pair?key=" + k + "&peer=pc&relay=https%3A%2F%2Fr.example&token=abcdefghijklmnopqrstuvwxyz", "bad"},
                {"onesay://pair?key=" + k + "&peer=pc&lan=1.2.3.4%3A65535", "bad"}, {"onesay://pair?key=" + k + "&peer=pc&lan=1.2.3.4%3A0", "ok|pc|1.2.3.4:0|47820|1"},
                {"onesay://pair?key=%%%&peer=pc&lan=1.2.3.4%3A5000", "bad"}, {"onesay://pair?key=" + k + "!&peer=pc&lan=1.2.3.4%3A5000", "bad"},
                {"https://example.com/?key=" + k, "bad"}, {"", "bad"}, {"onesay://pair?", "bad"}, {"onesay://pairkey=" + k, "bad"},
        };
        for (String[] p : pair) add(out, seen, "pair", p[0], p[1]);
        return out;
    }

    static void add(List<Object[]> out, Set<String> seen, String kind, String in, String want) {
        if (!seen.add(kind + "\u0001" + in)) throw new IllegalStateException("dup " + kind + " " + in);
        out.add(new Object[]{kind, in.length() > 60 ? in.substring(0, 60) + "…(" + in.length() + ")" : in, want, in});
    }

    private final String kind, want, in;

    public AdvJsonPairingTest(String kind, String shown, String want, String in) {
        this.kind = kind;
        this.want = want;
        this.in = in;
    }

    @Test public void parse() {
        String at = kind + " [" + in + "]";
        if (kind.equals("json")) {
            String s = PcJson.stringify(PcJson.parse(in));
            assertEquals(at, want, s);
            assertEquals(at + " 规范串再解析一遍不变", s, PcJson.stringify(PcJson.parse(s)));
        } else if (kind.equals("jsonBad")) {
            try {
                Object v = PcJson.parse(in);
                fail(at + " 应拒绝，得到 " + v);
            } catch (IllegalArgumentException expected) {
                // ok
            }
        } else {
            if (want.equals("bad")) {
                try {
                    PcPairing p = PcPairing.parse(in);
                    fail(at + " 应拒绝，得到 " + p);
                } catch (IllegalArgumentException e) {
                    assertFalse("错误信息不含密钥", e.getMessage().contains(key32()));
                }
                return;
            }
            PcPairing p = PcPairing.parse(in);
            String[] w = want.split("\\|", -1);
            assertEquals(at, w[1], p.peer);
            assertEquals(at, w[2], p.lanHosts);
            assertEquals(at, Integer.parseInt(w[3]), p.lanPort);
            assertEquals(at, Integer.parseInt(w[4]), p.version);
            assertEquals(32, p.key.length);
            assertFalse("日志串不含密钥", p.toString().contains(key32()));
            assertTrue(PcPairing.looksLike(in));
        }
    }
}
