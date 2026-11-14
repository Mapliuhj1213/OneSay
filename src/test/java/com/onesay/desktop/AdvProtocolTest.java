package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 联动协议（Protocol 类说明：签名绑定 id / from / to / 规范 JSON；verify 查签名 → 版本 → 时间窗 ±120 秒 → 一次性 nonce），
 * 极简 JSON 的畸形输入（Json）、配对串导入（Config#importPairing）、局域网 hello 的证书指纹核对（LanServer#checkHello：签名里的指纹必须是 TLS 上这张证书；
 * 首次信任后记住，换了证书 4003；记住过的设备不许退回明文 4002）。
 */
@RunWith(Parameterized.class)
public class AdvProtocolTest {

    static final byte[] KEY = new byte[32], KEY2 = new byte[32];
    static {
        for (int i = 0; i < 32; i++) { KEY[i] = (byte) (i + 1); KEY2[i] = (byte) (i + 2); }
    }
    static final long NOW = 1_800_000_000_000L;

    static String rep(String s, int n) { return s.repeat(n); }

    static final String[] BAD_JSON = {"", " ", "{", "}", "[", "]", "{\"a\":}", "[1,]", "{\"a\":1,}", "nul", "tru", "fals", "\"abc", "\"\\x\"", "\"\\u12\"",
            "\"\\u12G4\"", "{'a':1}", "{a:1}", "{\"a\" 1}", "{\"a\":1 \"b\":2}", "[1 2]", "-", "--1", "1.2.3", "{}x", "[]]", "{\"a\":[1,2}", "\"\\\"",
            "{\"a\":1}}", ".5e", "1e", "[,1]", "{,}", "{\"a\"}", "nulll", "truefalse", "\u0000", "[\"a\" \"b\"]", "{\"a\":01x}",
            rep("[", 66) + rep("]", 66), rep("{\"a\":", 66) + "1" + rep("}", 66), "{\"a\":\"\\uD800\"", "[1,,2]", "\"\\q\""};
    static final String[][] OK_JSON = {{"{}", "{}"}, {" {\"b\":1,\"a\":2} ", "{\"a\":2,\"b\":1}"}, {"[]", "[]"}, {"\"\\u4e2d\"", "\"中\""},
            {"{\"a\":1,\"a\":2}", "{\"a\":2}"}, {"-0", "0"}, {"1.5", "1.5"}, {"1e2", "100"}, {"true", "true"}, {"null", "null"}, {"\"\\/\"", "\"/\""},
            {"\"a\\nb\"", "\"a\\nb\""}, {"\"\\u0001\"", "\"\\u0001\""}, {rep("[", 65) + rep("]", 65), rep("[", 65) + rep("]", 65)},
            {"{\"k\":[1,{\"x\":null}]}", "{\"k\":[1,{\"x\":null}]}"}, {"\t\r\n[1]\n", "[1]"}, {"9007199254740993", "9007199254740993"}};

    static final String FP = Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
    static final String KEY_B64 = Base64.getUrlEncoder().withoutPadding().encodeToString(KEY);

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("proto");
        for (String j : BAD_JSON) r.add("json bad [" + j + "]", "jsonbad", j, null);
        for (String[] j : OK_JSON) r.add("json ok [" + j[0] + "]", "jsonok", j[0], j[1]);
        String[][] tamper = {{"cmd", "BAD_SIG"}, {"argval", "BAD_SIG"}, {"argadd", "BAD_SIG"}, {"ts", "BAD_SIG"}, {"nonce", "BAD_SIG"}, {"v", "BAD_SIG"},
                {"kind", "BAD_SIG"}, {"extra", "BAD_SIG"}, {"noargs", "BAD_SIG"}, {"id", "BAD_SIG"}, {"from", "BAD_SIG"}, {"to", "BAD_SIG"}, {"swap", "BAD_SIG"},
                {"key", "BAD_SIG"}, {"sigtrunc", "BAD_SIG"}, {"sigplus", "BAD_SIG"}, {"sigpad", "BAD_SIG"}, {"sigcase", "BAD_SIG"}, {"signum", "NO_SIG"},
                {"nosig", "NO_SIG"}, {"signull", "NO_SIG"}, {"sigempty", "BAD_SIG"}, {"roundtrip", "OK"}, {"double", "OK"}, {"intcol", "OK"},
                {"v2", "BAD_VERSION"}, {"vstr", "BAD_VERSION"}, {"vnone", "BAD_VERSION"}, {"ts+119999", "OK"}, {"ts-119999", "OK"}, {"ts+120000", "OK"},
                {"ts-120000", "OK"}, {"ts+120001", "STALE"}, {"ts-120001", "STALE"}, {"tsstr", "STALE"}, {"tsnone", "STALE"}, {"tsdouble", "OK"},
                {"replay", "REPLAY"}, {"replay-resigned", "REPLAY"}, {"nonce-missing", "REPLAY"}, {"nonce-num", "REPLAY"}, {"out-of-order", "OK"},
                {"no-guard-twice", "OK"}, {"flood", "REPLAY"}, {"unicode-args", "OK"}, {"argcase", "BAD_SIG"}};
        for (String[] t : tamper) r.add("verify " + t[0], "verify", t[0], t[1]);
        r.add("guard expiry 240000", "expiry", "240000", Boolean.FALSE);
        r.add("guard expiry 240001", "expiry", "240001", Boolean.TRUE);
        // 配对串
        String base = "onesay://pair?key=" + KEY_B64 + "&peer=pc-1";
        String[][] pairBad = {{"onesay://pairx?key=a", "不是 OneSay 配对串"}, {"http://x", "不是 OneSay 配对串"}, {"", "不是 OneSay 配对串"},
                {base + "&lan=1.2.3.4:47831&fp=" + FP + "&v=3", "更新的版本"}, {base + "&lan=1.2.3.4:1&v=0", "版本不对"}, {base + "&lan=1.2.3.4:1&v=abc", "版本不对"},
                {base + "&lan=1.2.3.4:1&v=-1", "版本不对"}, {base + "&lan=1.2.3.4:1&fp=" + FP.substring(1) + "&v=2", "证书指纹不对"},
                {base + "&lan=1.2.3.4:1&fp=" + FP.substring(1) + "+&v=2", "证书指纹不对"}, {base + "&lan=1.2.3.4:1&v=2", "缺证书指纹"},
                {"onesay://pair?peer=pc-1&lan=1.2.3.4:1", "不完整"}, {"onesay://pair?key=" + KEY_B64 + "&lan=1.2.3.4:1", "不完整"},
                {"onesay://pair?key=" + KEY_B64 + "&peer=a%20b&lan=1.2.3.4:1", "不完整"}, {"onesay://pair?key=" + KEY_B64 + "&peer=../x&lan=1.2.3.4:1", "不完整"},
                {"onesay://pair?key=" + KEY_B64 + "&peer=" + rep("a", 65) + "&lan=1.2.3.4:1", "不完整"},
                {"onesay://pair?key=" + KEY_B64 + "&peer=%E4%B8%AD&lan=1.2.3.4:1", "不完整"}, {base + "&relay=https://r.example", "中转令牌不完整"},
                {base + "&relay=https://r.example&token=" + rep("t", 23), "中转令牌不完整"}, {base, "既没有局域网也没有中转"},
                {"onesay://pair?key=" + Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[31]) + "&peer=pc-1&lan=1.2.3.4:1", "配对密钥太短"},
                {"onesay://pair?key=!!!&peer=pc-1&lan=1.2.3.4:1", ""}, {base + "&lan=1.2.3.4:1&x=%zz", ""}};
        for (String[] p : pairBad) r.add("pair bad [" + p[0] + "]", "pairbad", p[0], p[1]);
        String[][] pairOk = {{base + "&lan=192.168.1.5:47831", "47831|192.168.1.5:47831||"}, {base + "&lan=192.168.1.5:47831&fp=" + FP + "&v=2", "47831|192.168.1.5:47831||" + FP},
                {base + "&relay=https://r.example&token=" + rep("t", 24) + "&v=2", "0|||"}, {base + "&lan=0.0.0.0:9", "9|||"},
                {base + "&lan=a:1,b:x,c:3", "3|a:1,c:3||"}, {base + "&lan=10.0.0.2:5000&extra=1&zz=%E4%B8%AD", "5000|10.0.0.2:5000||"},
                {"onesay://pair?key=" + KEY_B64 + "&peer=pc_1.A-b&lan=10.0.0.3:1", "1|10.0.0.3:1||"}, {base + "&lan=noport", LanServer.DEFAULT_PORT + "|||"},
                {base + "&lan=10.0.0.4:7&lan=10.0.0.5:8", "8|10.0.0.5:8||"}};
        for (String[] p : pairOk) r.add("pair ok [" + p[0] + "]", "pairok", p[0], p[1]);
        // hello 指纹
        String[] hello = {"first-pin", "same-again", "changed-cert", "cfp-mismatch", "cfp-missing", "plain-pinned", "plain-unpinned", "plain-off", "from-self",
                "from-bad", "bad-sig", "replay", "type-not-hello", "kind-not-hello", "peer-not-allowed", "not-json", "null", "hold-flag"};
        for (String h : hello) r.add("hello " + h, "hello", h, null);
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String kind;
    @Parameterized.Parameter(2) public String in;
    @Parameterized.Parameter(3) public Object want;

    @Test public void proto() throws Exception {
        switch (kind) {
            case "jsonbad":
                try {
                    Object o = Json.parse(in);
                    fail(name + " 应报错，实际解析成 " + o);
                } catch (RuntimeException expected) {
                    // 坏输入：抛运行时异常（调用方 catch 住丢掉这一帧），不会被当成合法消息
                }
                break;
            case "jsonok":
                assertEquals(name, want, Json.stringify(Json.parse(in)));
                break;
            case "verify": verify(); break;
            case "expiry": {
                Protocol.ReplayGuard g = new Protocol.ReplayGuard();
                assertTrue(g.firstSeen("n1", 0));
                assertEquals(name, want, g.firstSeen("n1", Long.parseLong(in)));
                break;
            }
            case "pairbad": {
                Config c = new Config(null);
                try {
                    c.importPairing(in);
                    fail(name + " 应拒绝");
                } catch (IllegalArgumentException e) {
                    if (!((String) want).isEmpty()) assertTrue(name + " → " + e.getMessage(), String.valueOf(e.getMessage()).contains((String) want));
                }
                assertEquals("拒绝时不动密钥", 0, c.pairKey.length);
                break;
            }
            case "pairok": {
                Config c = new Config(null);
                String peer = c.importPairing(in);
                String[] w = ((String) want).split("\\|", -1);
                assertEquals(name, in.contains("peer=pc_1.A-b") ? "pc_1.A-b" : "pc-1", peer);
                assertEquals(name, Integer.parseInt(w[0]), c.lanPort);
                assertEquals(name, w[1], c.lanHosts);
                assertEquals(name, w[3], c.peerFp);
                assertTrue(java.util.Arrays.equals(KEY, c.pairKey));
                break;
            }
            case "hello": hello(in); break;
            default: throw new AssertionError(kind);
        }
    }

    static Map<String, Object> body(long ts) {
        Map<String, Object> args = new TreeMap<>();
        args.put("q", 80L);
        args.put("s", "中文");
        Map<String, Object> b = Protocol.command(KEY, "m1", "phone-a", "pc-adv", "screenshot", args, ts);
        return resign(b, "nonce", "n" + ts); // 固定 nonce：签名每次都一样，用例完全可重复
    }

    @SuppressWarnings("unchecked")
    void verify() {
        Map<String, Object> b = body(NOW);
        String id = "m1", from = "phone-a", to = "pc-adv";
        byte[] key = KEY;
        Protocol.ReplayGuard g = new Protocol.ReplayGuard();
        Protocol.Check got;
        switch (in) {
            case "cmd": b.put("cmd", "shutdown"); break;
            case "argval": ((Map<String, Object>) b.get("args")).put("q", 81L); break;
            case "argadd": ((Map<String, Object>) b.get("args")).put("confirm", true); break;
            case "argcase": { Map<String, Object> a = (Map<String, Object>) b.get("args"); a.put("Q", a.remove("q")); break; }
            case "ts": b.put("ts", NOW + 1); break;
            case "nonce": b.put("nonce", "zzzz"); break;
            case "v": b.put("v", 2L); break;
            case "kind": b.put("kind", "result"); break;
            case "extra": b.put("_outside", true); break;
            case "noargs": b.remove("args"); break;
            case "id": id = "m2"; break;
            case "from": from = "phone-b"; break;
            case "to": to = "pc-other"; break;
            case "swap": from = "pc-adv"; to = "phone-a"; break;
            case "key": key = KEY2; break;
            case "sigtrunc": b.put("sig", ((String) b.get("sig")).substring(1)); break;
            case "sigplus": b.put("sig", b.get("sig") + "A"); break;
            case "sigpad": b.put("sig", b.get("sig") + "="); break;
            case "sigcase": { // 把第一个字母翻大小写（数字 / - / _ 翻不了，跳过）
                String s = (String) b.get("sig");
                int k = 0;
                while (!Character.isLetter(s.charAt(k))) k++;
                char c = s.charAt(k);
                b.put("sig", s.substring(0, k) + (Character.isUpperCase(c) ? Character.toLowerCase(c) : Character.toUpperCase(c)) + s.substring(k + 1));
                break;
            }
            case "signum": b.put("sig", 12345L); break;
            case "nosig": b.remove("sig"); break;
            case "signull": b.put("sig", null); break;
            case "sigempty": b.put("sig", ""); break;
            case "roundtrip": b = Json.obj(Json.stringify(b)); break;
            case "double": { // 80 和 80.0 的规范 JSON 一样
                Map<String, Object> a = (Map<String, Object>) b.get("args");
                a.put("q", 80.0);
                break;
            }
            case "intcol": { Map<String, Object> a = (Map<String, Object>) b.get("args"); a.put("q", Integer.valueOf(80)); break; }
            case "unicode-args": b = Json.obj(Json.stringify(b).replace("中文", "\\u4e2d\\u6587")); break;
            case "v2": b = resign(b, "v", 2L); break;
            case "vstr": b = resign(b, "v", "1"); break;
            case "vnone": b = resign(b, "v", null); break;
            case "tsstr": b = resign(b, "ts", String.valueOf(NOW)); break;
            case "tsnone": b = resign(b, "ts", null); break;
            case "tsdouble": b = resign(b, "ts", (double) NOW + 0.5); break;
            case "nonce-missing": b = resign(b, "nonce", null); break;
            case "nonce-num": b = resign(b, "nonce", 7L); break;
            default: break;
        }
        if (in.startsWith("ts+") || in.startsWith("ts-")) {
            long d = Long.parseLong(in.substring(3));
            got = Protocol.verify(KEY, id, from, to, body(in.charAt(2) == '+' ? NOW + d : NOW - d), NOW, g);
        } else if (in.equals("replay")) {
            assertEquals(Protocol.Check.OK, Protocol.verify(key, id, from, to, b, NOW, g));
            got = Protocol.verify(key, id, from, to, b, NOW + 5, g);
        } else if (in.equals("replay-resigned")) {
            assertEquals(Protocol.Check.OK, Protocol.verify(key, id, from, to, b, NOW, g));
            Map<String, Object> again = resign(b, "cmd", "lock"); // 同一个 nonce 换了内容重新签
            got = Protocol.verify(key, id, from, to, again, NOW, g);
        } else if (in.equals("out-of-order")) {
            Map<String, Object> newer = body(NOW + 50_000), older = body(NOW - 50_000);
            assertEquals(Protocol.Check.OK, Protocol.verify(key, id, from, to, newer, NOW, g));
            got = Protocol.verify(key, id, from, to, older, NOW, g);
        } else if (in.equals("no-guard-twice")) {
            assertEquals(Protocol.Check.OK, Protocol.verify(key, id, from, to, b, NOW, null));
            got = Protocol.verify(key, id, from, to, b, NOW, null);
        } else if (in.equals("flood")) {
            for (int i = 0; i <= 10_000; i++) g.firstSeen("n" + i, NOW);
            got = Protocol.verify(key, id, from, to, b, NOW, g);
        } else got = Protocol.verify(key, id, from, to, b, NOW, g);
        assertEquals(name, Protocol.Check.valueOf((String) want), got);
    }

    /** 改一个字段后用正确的钥匙重新签（测签名之后的检查：版本 / 时间 / nonce）。 */
    static Map<String, Object> resign(Map<String, Object> b0, String k, Object v) {
        TreeMap<String, Object> b = new TreeMap<>(b0);
        b.remove("sig");
        if (v == null) b.remove(k); else b.put(k, v);
        b.put("sig", Protocol.sign(KEY, "m1", "phone-a", "pc-adv", b));
        return b;
    }

    // ------------------------------------------------------------------ hello

    static String helloFrame(String type, String from, String kindv, String cfp, boolean hold, byte[] key, String nonceOverride) {
        Map<String, Object> body = new TreeMap<>();
        body.put("kind", kindv);
        if (cfp != null) body.put("cfp", cfp);
        body.put("hold", hold);
        Map<String, Object> sealed = Protocol.seal(key, "h1", from, "pc-adv", body, System.currentTimeMillis());
        if (nonceOverride != null) {
            sealed.put("nonce", nonceOverride);
            sealed.remove("sig");
            sealed.put("sig", Protocol.sign(key, "h1", from, "pc-adv", sealed));
        }
        Map<String, Object> f = new TreeMap<>();
        f.put("type", type);
        f.put("from", from);
        f.put("id", "h1");
        f.put("body", sealed);
        return Json.stringify(f);
    }

    static String fp(int seed) {
        byte[] b = new byte[32];
        b[0] = (byte) seed;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    void hello(String sc) throws Exception {
        Config c = AdvSupport.cfg(LinkPolicy.Mode.DEFAULT);
        c.pairKey = KEY;
        LanServer ls = new LanServer(c, null);
        String a = fp(1), b2 = fp(2);
        LanServer.Hello h;
        switch (sc) {
            case "first-pin":
                h = ls.checkHello(helloFrame("hello", "phone-a", "hello", a, false, KEY, null), a);
                assertEquals("phone-a", h.who);
                assertEquals("首次信任：记住指纹", a, c.pinOf("phone-a"));
                assertEquals("存盘了", a, Config.load(c.file).pinOf("phone-a"));
                break;
            case "same-again":
                c.pin("phone-a", a);
                assertEquals("phone-a", ls.checkHello(helloFrame("hello", "phone-a", "hello", a, false, KEY, null), a).who);
                break;
            case "changed-cert":
                c.pin("phone-a", a);
                h = ls.checkHello(helloFrame("hello", "phone-a", "hello", b2, false, KEY, null), b2);
                assertNull(h.who);
                assertEquals(4003, h.code);
                assertEquals("pin", h.why);
                assertEquals("记住的指纹没被换掉", a, c.pinOf("phone-a"));
                break;
            case "cfp-mismatch":
                h = ls.checkHello(helloFrame("hello", "phone-a", "hello", a, false, KEY, null), b2);
                assertNull(h.who);
                assertNull("对不上就不记", c.pinOf("phone-a"));
                break;
            case "cfp-missing":
                assertNull(ls.checkHello(helloFrame("hello", "phone-a", "hello", null, false, KEY, null), a).who);
                break;
            case "plain-pinned":
                c.pin("phone-a", a);
                h = ls.checkHello(helloFrame("hello", "phone-a", "hello", null, false, KEY, null), null);
                assertNull(h.who);
                assertEquals(4002, h.code);
                assertEquals("tls_required", h.why);
                break;
            case "plain-unpinned":
                assertEquals("phone-a", ls.checkHello(helloFrame("hello", "phone-a", "hello", null, false, KEY, null), null).who);
                assertNull("明文不记指纹", c.pinOf("phone-a"));
                break;
            case "plain-off":
                c.lanPlain = false;
                assertEquals(4002, ls.checkHello(helloFrame("hello", "phone-a", "hello", null, false, KEY, null), null).code);
                break;
            case "from-self":
                assertNull(ls.checkHello(helloFrame("hello", "pc-adv", "hello", a, false, KEY, null), a).who);
                break;
            case "from-bad":
                assertNull(ls.checkHello(helloFrame("hello", "phone a/..", "hello", a, false, KEY, null), a).who);
                break;
            case "bad-sig":
                assertNull(ls.checkHello(helloFrame("hello", "phone-a", "hello", a, false, KEY2, null), a).who);
                assertNull(c.pinOf("phone-a"));
                break;
            case "replay": {
                String f = helloFrame("hello", "phone-a", "hello", a, false, KEY, "fixed-nonce-1114");
                assertEquals("phone-a", ls.checkHello(f, a).who);
                assertNull("同一个 hello 重放不收", ls.checkHello(f, a).who);
                break;
            }
            case "type-not-hello":
                assertNull(ls.checkHello(helloFrame("msg", "phone-a", "hello", a, false, KEY, null), a).who);
                break;
            case "kind-not-hello":
                assertNull(ls.checkHello(helloFrame("hello", "phone-a", "cmd", a, false, KEY, null), a).who);
                break;
            case "peer-not-allowed":
                c.peers = "phone-b";
                assertNull(ls.checkHello(helloFrame("hello", "phone-a", "hello", a, false, KEY, null), a).who);
                assertNull(c.pinOf("phone-a"));
                break;
            case "not-json":
                assertNull(ls.checkHello("{not json", a).who);
                break;
            case "null":
                assertNull(ls.checkHello(null, a).who);
                break;
            case "hold-flag":
                h = ls.checkHello(helloFrame("hello", "phone-a", "hello", a, true, KEY, null), a);
                assertTrue(h.hold);
                assertFalse(ls.checkHello(helloFrame("hello", "phone-b", "hello", fp(3), false, KEY, null), fp(3)).hold);
                break;
            default:
                throw new AssertionError(sc);
        }
    }
}
