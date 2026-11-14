package com.onesay.assistant.v2.pclink;

import com.onesay.assistant.v2.notice.IslandNotice;

import org.junit.Test;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * [pclink] 电脑联动协议的纯 Java 部分：规范 JSON、base64、签名 / 校验 / 防重放、配对串解析、转发口径、语音口令。
 * 与电脑端源码逐字节比对的部分在 {@link PcDesktopInteropTest}（需要电脑端 jar）。
 */
public class PcProtocolTest {

    static byte[] key() {
        byte[] k = new byte[32];
        for (int i = 0; i < k.length; i++) k[i] = (byte) (i * 7 + 3);
        return k;
    }

    // ------------------------------------------------------------------ JSON

    @Test public void canonicalJsonSortsKeysAndHasNoSpaces() {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("z", 1);
        m.put("a", "x\"y\\\n\t\u0001");
        m.put("m", Arrays.<Object>asList(true, null, 2.5, 3.0, -7L));
        Map<String, Object> inner = new LinkedHashMap<String, Object>();
        inner.put("b", "中文");
        inner.put("B", false);
        m.put("o", inner);
        assertEquals("{\"a\":\"x\\\"y\\\\\\n\\t\\u0001\",\"m\":[true,null,2.5,3,-7],\"o\":{\"B\":false,\"b\":\"中文\"},\"z\":1}", PcJson.stringify(m));
    }

    @Test public void jsonRoundTripTypes() {
        Map<String, Object> m = PcJson.obj(" {\"a\" : 12, \"b\":1.5,\"c\":\"\\u4e2d\\/x\",\"d\":[],\"e\":{},\"f\":null,\"g\":99999999999999999999} ");
        assertEquals(Long.valueOf(12), m.get("a"));
        assertEquals(Double.valueOf(1.5), m.get("b"));
        assertEquals("中/x", m.get("c"));
        assertTrue(m.get("d") instanceof List);
        assertTrue(m.get("e") instanceof Map);
        assertTrue(m.containsKey("f") && m.get("f") == null);
        assertTrue(m.get("g") instanceof Double);
        try {
            PcJson.obj("{\"a\":1} x");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    // ------------------------------------------------------------------ base64

    @Test public void base64MatchesJdk() {
        SecureRandom r = new SecureRandom();
        for (int n = 0; n < 70; n++) {
            byte[] b = new byte[n];
            r.nextBytes(b);
            assertEquals(java.util.Base64.getEncoder().encodeToString(b), PcB64.encode(b));
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(b), PcB64.encodeUrl(b));
            assertArrayEquals(b, PcB64.decode(PcB64.encode(b)));
            assertArrayEquals(b, PcB64.decode(PcB64.encodeUrl(b)));
        }
        try {
            PcB64.decode("ab*d");
            fail();
        } catch (IllegalArgumentException expected) {
        }
    }

    // ------------------------------------------------------------------ 签名

    @Test public void sealThenVerifyOk() {
        byte[] k = key();
        long now = 1_700_000_000_000L;
        Map<String, Object> args = new TreeMap<String, Object>();
        args.put("text", "你好");
        Map<String, Object> b = PcProtocol.command(k, "m1", "phone-a", "pc-b", "notify", args, now);
        assertEquals(Long.valueOf(1), b.get("v"));
        assertEquals(Long.valueOf(now), b.get("ts"));
        assertEquals("cmd", b.get("kind"));
        assertNotNull(b.get("sig"));
        PcProtocol.ReplayGuard g = new PcProtocol.ReplayGuard();
        assertEquals(PcProtocol.Check.OK, PcProtocol.verify(k, "m1", "phone-a", "pc-b", b, now + 1000, g));
        // 同一 nonce 再来一次 = 重放
        assertEquals(PcProtocol.Check.REPLAY, PcProtocol.verify(k, "m1", "phone-a", "pc-b", b, now + 2000, g));
    }

    @Test public void fromToAndIdAreBoundIntoSignature() {
        byte[] k = key();
        long now = 1_700_000_000_000L;
        Map<String, Object> b = PcProtocol.command(k, "m1", "phone-a", "pc-b", "lock", null, now);
        assertEquals(PcProtocol.Check.BAD_SIG, PcProtocol.verify(k, "m2", "phone-a", "pc-b", b, now, null));
        assertEquals(PcProtocol.Check.BAD_SIG, PcProtocol.verify(k, "m1", "phone-x", "pc-b", b, now, null));
        assertEquals(PcProtocol.Check.BAD_SIG, PcProtocol.verify(k, "m1", "phone-a", "pc-x", b, now, null));
        byte[] other = key();
        other[0] ^= 1;
        assertEquals(PcProtocol.Check.BAD_SIG, PcProtocol.verify(other, "m1", "phone-a", "pc-b", b, now, null));
        Map<String, Object> tampered = new TreeMap<String, Object>(b);
        tampered.put("cmd", "shutdown");
        assertEquals(PcProtocol.Check.BAD_SIG, PcProtocol.verify(k, "m1", "phone-a", "pc-b", tampered, now, null));
        Map<String, Object> nosig = new TreeMap<String, Object>(b);
        nosig.remove("sig");
        assertEquals(PcProtocol.Check.NO_SIG, PcProtocol.verify(k, "m1", "phone-a", "pc-b", nosig, now, null));
    }

    @Test public void timeWindowIs120Seconds() {
        byte[] k = key();
        long now = 1_700_000_000_000L;
        Map<String, Object> b = PcProtocol.hello(k, "h1", "phone-a", "pc-b", now);
        assertEquals(PcProtocol.Check.OK, PcProtocol.verify(k, "h1", "phone-a", "pc-b", b, now + 120_000, null));
        assertEquals(PcProtocol.Check.STALE, PcProtocol.verify(k, "h1", "phone-a", "pc-b", b, now + 120_001, null));
        assertEquals(PcProtocol.Check.STALE, PcProtocol.verify(k, "h1", "phone-a", "pc-b", b, now - 120_001, null));
    }

    @Test public void tagIsHmacOverLanPrefix() {
        byte[] k = key();
        String t = PcProtocol.tag(k, "probe\na\nb\nn");
        assertTrue(PcProtocol.tagOk(k, "probe\na\nb\nn", t));
        assertFalse(PcProtocol.tagOk(k, "probe\na\nb\nm", t));
        assertFalse(PcProtocol.tagOk(k, "probe\na\nb\nn", 5));
        assertEquals(43, t.length());   // 32 字节 HMAC → 43 位无填充 base64url
    }

    @Test public void probeAndAnswerRoundTrip() {
        byte[] k = key();
        PcLanClient.Peer p = new PcLanClient.Peer("phone-a", "pc-b", k, "", 47820, "");
        String probe = PcLanClient.probe(p, "nonce12345");
        Map<String, Object> m = PcJson.obj(probe);
        assertEquals("probe", m.get("t"));
        assertTrue(PcProtocol.tagOk(k, "probe\nphone-a\npc-b\nnonce12345", m.get("tag")));
        Map<String, Object> r = new TreeMap<String, Object>();
        r.put("t", "here");
        r.put("id", "pc-b");
        r.put("port", 47820L);
        r.put("n", "nonce12345");
        r.put("tag", PcProtocol.tag(k, "here\npc-b\n47820\nnonce12345"));
        assertEquals(47820, PcLanClient.answerPort(p, "nonce12345", PcJson.stringify(r)));
        assertEquals(-1, PcLanClient.answerPort(p, "othernonce", PcJson.stringify(r)));
        r.put("port", 47821L);
        assertEquals(-1, PcLanClient.answerPort(p, "nonce12345", PcJson.stringify(r)));
    }

    // ------------------------------------------------------------------ 配对串

    static String pairStr(byte[] k, String peer, String lan, String relay) {
        String s = PcPairing.PREFIX + "key=" + PcB64.encodeUrl(k) + "&peer=" + peer;
        if (lan != null) s += "&lan=" + lan;
        if (relay != null) s += relay;
        return s;
    }

    @Test public void parsesLanPairing() {
        PcPairing p = PcPairing.parse(pairStr(key(), "pc-home", "192.168.1.5%3A47820%2C10.0.0.2%3A47820", null));
        assertEquals("pc-home", p.peer);
        assertArrayEquals(key(), p.key);
        assertEquals("192.168.1.5:47820,10.0.0.2:47820", p.lanHosts);
        assertEquals(47820, p.lanPort);
        assertFalse(p.hasRelay);
        assertFalse(p.toString().contains(PcB64.encodeUrl(key())));
        assertTrue(p.redacted().contains("keyLen=32"));
    }

    @Test public void zeroAddressGivesPortOnly() {
        PcPairing p = PcPairing.parse(pairStr(key(), "pc-1", "0.0.0.0:48000", null));
        assertEquals("", p.lanHosts);
        assertEquals(48000, p.lanPort);
    }

    @Test public void rejectsBadPairings() {
        String[] bad = {
                "",
                "https://example.com",
                PcPairing.PREFIX + "peer=pc&lan=1.2.3.4:1",
                pairStr(key(), "", "1.2.3.4:47820", null),
                pairStr(key(), "pc%20x", "1.2.3.4:47820", null),
                pairStr(Arrays.copyOf(key(), 16), "pc", "1.2.3.4:47820", null),
                pairStr(key(), "pc", null, null),
                pairStr(key(), "pc", null, "&relay=https%3A%2F%2Fr.example&token=short"),
                pairStr(key(), "pc", null, "&relay=https%3A%2F%2Fr.example&token=abcdefghijklmnopqrstuvwxyz"),   // 只有中转：手机端 v1 不支持
        };
        for (String s : bad) {
            try {
                PcPairing.parse(s);
                fail("应拒绝：" + s.length());
            } catch (IllegalArgumentException expected) {
                assertFalse(expected.getMessage().contains(PcB64.encodeUrl(key())));
            }
        }
    }

    @Test public void relayPlusLanKeepsLan() {
        PcPairing p = PcPairing.parse(pairStr(key(), "pc", "192.168.0.9:47820", "&relay=https%3A%2F%2Fr.example&token=abcdefghijklmnopqrstuvwxyz"));
        assertTrue(p.hasRelay);
        assertEquals("192.168.0.9:47820", p.lanHosts);
    }

    // ------------------------------------------------------------------ 转发口径

    @Test public void forwardKinds() {
        assertEquals("reminder", PcForward.kindOf(new IslandNotice(IslandNotice.Source.CALENDAR, "cal:1", null, "开会", "10:00")));
        assertEquals("reminder", PcForward.kindOf(new IslandNotice(IslandNotice.Source.CALLBACK, "cb:1", null, "提醒", "喝水")));
        assertEquals("reminder", PcForward.kindOf(new IslandNotice(IslandNotice.Source.CALLBACK, "cb:2", null, "定时任务", "提醒你喝水")));
        assertEquals("auto", PcForward.kindOf(new IslandNotice(IslandNotice.Source.CALLBACK, "cb:3", null, "金价", "今天 612 元/克")));
        assertEquals("rain", PcForward.kindOf(new IslandNotice(IslandNotice.Source.WEATHER, "rain:1", null, "12 分钟后下雨", "")));
        assertNull(PcForward.kindOf(new IslandNotice(IslandNotice.Source.NOTIFY, "nl:otp:1", null, "验证码", "123456")));
        assertNull(PcForward.kindOf(new IslandNotice(IslandNotice.Source.CALL, "call:1", null, "来电", "")));
        assertNull(PcForward.kindOf(null));
        Map<String, Object> a = PcForward.args("rain", new IslandNotice(IslandNotice.Source.WEATHER, "rain:1", null, "12 分钟后下雨", "带伞"));
        assertEquals("rain", a.get("kind"));
        assertEquals("12 分钟后下雨", a.get("title"));
        assertEquals("带伞", a.get("text"));
    }

    @Test public void dedupSendsOnce() {
        PcForward.Dedup d = new PcForward.Dedup();
        assertTrue(d.first("a"));
        assertFalse(d.first("a"));
        for (int i = 0; i < 300; i++) d.first("k" + i);
        assertTrue(d.first("a"));   // 老的被挤掉
    }

    // ------------------------------------------------------------------ 语音口令

    @Test public void voiceIntents() {
        List<String> lock = new ArrayList<String>(Arrays.asList("电脑锁屏", "让电脑锁屏", "把电脑锁上", "锁一下电脑", "锁定电脑", "帮我让电脑锁屏。"));
        for (String s : lock) assertEquals(s, PcVoice.Kind.LOCK, PcVoice.of(s));
        for (String s : new String[]{"电脑截图", "让电脑截个图", "截一下电脑屏幕", "电脑截屏"}) assertEquals(s, PcVoice.Kind.SHOT, PcVoice.of(s));
        assertEquals(PcVoice.Kind.STATUS, PcVoice.of("电脑在线吗"));
        assertEquals(PcVoice.Kind.POWER, PcVoice.of("让电脑关机"));
        for (String s : new String[]{"电脑怎么截图", "锁屏", "截图", "买个电脑", "电脑锁屏密码忘了怎么办", "手机锁屏", ""}) {
            assertEquals(s, PcVoice.Kind.NONE, PcVoice.of(s));
        }
    }

    /** 输入法转写（全角标点、首字母大写、夹空格）后的配对码也要能认（oss-device-1108 真机）。 */
    @Test public void pairingCodeToleratesImeTransliteration() {
        String k = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8g";
        PcPairing p = PcPairing.parse("Onesay：//pair？peer＝fake-test-pc＆lan=192.0.2.1:47801 &key=" + k);
        org.junit.Assert.assertEquals("fake-test-pc", p.peer);
        try {
            PcPairing.parse("http://example.com/?key=" + k);
            org.junit.Assert.fail("非配对码要拒绝");
        } catch (IllegalArgumentException expected) {
        }
    }

    /** 电脑端 0.4.2 {@code Config.pairingString()} 的真实形态（lan 值经 URLEncoder 编码、多地址、0.0.0.0 兜底、可带中转），以及常见输入法改写。 */
    @Test public void pairingCodeDesktopFormsAndImeVariants() {
        String k = "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8g"; // 33 字节
        String real = "onesay://pair?key=" + k + "&peer=pc-home&lan=192.168.31.20%3A47820%2C10.1.2.3%3A47820";
        PcPairing p = PcPairing.parse(real);
        org.junit.Assert.assertEquals("pc-home", p.peer);
        org.junit.Assert.assertEquals("192.168.31.20:47820,10.1.2.3:47820", p.lanHosts);
        org.junit.Assert.assertEquals(47820, p.lanPort);
        org.junit.Assert.assertEquals(33, p.key.length);
        PcPairing z = PcPairing.parse("onesay://pair?key=" + k + "&peer=pc-x&lan=0.0.0.0%3A47821");
        org.junit.Assert.assertEquals("", z.lanHosts);
        org.junit.Assert.assertEquals(47821, z.lanPort);
        PcPairing r = PcPairing.parse(real + "&relay=https%3A%2F%2Fr.example&token=" + "t0123456789abcdefghijklmn");
        org.junit.Assert.assertTrue(r.hasRelay);

        String[] variants = {
                real.replace(":", "\uFF1A").replace("?", "\uFF1F").replace("&", "\uFF06").replace("=", "\uFF1D"), // 中文模式全角标点
                real.replace("//", "\u3001\u3001"), // 中文模式 / → 、
                real.replace("%", "\uFF05").replace(".", "\u3002"), // ％ 和 。
                "Onesay://Pair?Key=" + k + "&Peer=pc-home&Lan=192.168.31.20%3A47820%2C10.1.2.3%3A47820", // 自动大写（只在名字上，值不动）
                " \u200B" + real + "\uFEFF\n", // 零宽 / BOM / 换行
                real.substring(0, 40) + " \n" + real.substring(40), // 网页框里折行后复制
                "配对串：" + real + "（别发群里）", // 聊天里带着前后文字
                "电脑发来的：" + real + "。", // 句末句号
        };
        for (String v : variants) {
            PcPairing q = PcPairing.parse(v);
            org.junit.Assert.assertEquals(v, "pc-home", q.peer);
            org.junit.Assert.assertEquals(v, p.lanHosts, q.lanHosts);
            org.junit.Assert.assertArrayEquals(v, p.key, q.key);
            org.junit.Assert.assertTrue(v, PcPairing.looksLike(v));
            org.junit.Assert.assertEquals(v, real, PcPairing.extract(v));
        }
        String under = "onesay://pair?key=AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwd_x8g&peer=pc_1&lan=1.2.3.4%3A5";
        org.junit.Assert.assertEquals("pc_1", PcPairing.parse(under.replace("_", "\u2014\u2014")).peer); // 中文模式 _ → ——

        // 不是配对码的照样拒绝，提示不变
        for (String bad : new String[]{"", "   ", "http://example.com/?key=" + k, "onesay://other?key=" + k, "pair?key=" + k + "&peer=p&lan=1.2.3.4:5"}) {
            org.junit.Assert.assertFalse(bad, PcPairing.looksLike(bad));
            org.junit.Assert.assertNull(bad, PcPairing.extract(bad));
            try {
                PcPairing.parse(bad);
                org.junit.Assert.fail(bad);
            } catch (IllegalArgumentException e) {
                org.junit.Assert.assertEquals("不是电脑端的配对码", e.getMessage());
            }
        }
        // 前缀对、内容坏的：给具体原因，不是「不是电脑端的配对码」
        try {
            PcPairing.parse("onesay://pair?key=AAAA&peer=p&lan=1.2.3.4:5");
            org.junit.Assert.fail();
        } catch (IllegalArgumentException e) {
            org.junit.Assert.assertEquals("配对密钥太短", e.getMessage());
        }
    }
}
