package com.onesay.assistant.v2.pclink;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.Assert.*;

/**
 * 找茬 · 电脑联动协议 v1（PcProtocol）：签名 → 版本 → 时间窗 ±120 s → nonce 一次性。
 * <ul>
 *   <li>tamper：签好的指令改任何一处（外层 id / from / to、cmd、args 里一个值、加一个字段、删 nonce、换 v、改 ts、换钥匙、改 sig 一个字符、
 *       sig 换成非字符串）→ 不是 OK（对应的 Check）；</li>
 *   <li>skew：ts 与本机时间差在 ±120 000 ms 以内 OK，超出一毫秒 STALE（边界逐点）；</li>
 *   <li>replay：同一 nonce 在保留期内第二次 → REPLAY；乱序到达（后签的先到）各自第一次都 OK；保留期过后重放也会因时间窗 STALE 被拒。</li>
 * </ul>
 */
@RunWith(Parameterized.class)
public class AdvProtocolFrameTest {

    static final byte[] KEY = new byte[32], KEY2 = new byte[32];

    static {
        for (int i = 0; i < 32; i++) { KEY[i] = (byte) (i * 7 + 1); KEY2[i] = (byte) (i * 7 + 2); }
    }

    static final long T0 = 1_790_000_000_000L;

    @Parameterized.Parameters(name = "{index}: {0} {1}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        String[] tamper = {"id", "from", "to", "cmd", "argVal", "argAdd", "bodyAdd", "dropNonce", "v2", "vString", "tsPlus1", "tsString", "key", "sigChar",
                "sigNum", "sigEmpty", "dropSig", "sigPad", "kindHello", "argsNull", "argKeyCase", "unicodeArg"};
        for (String t : tamper) out.add(new Object[]{"tamper", t, 0L});
        long[] skews = {0, 1, -1, 1000, -1000, 60000, -60000, 119999, -119999, 120000, -120000, 120001, -120001, 121000, -121000, 3600000, -3600000,
                86400000L, -86400000L, T0};
        for (long s : skews) out.add(new Object[]{"skew", "d=" + s, s});
        String[] replay = {"same", "sameLater100s", "sameLater239s", "sameLater240s", "sameLater241s", "outOfOrder", "twoNonces", "afterOther", "differentId"};
        for (String r : replay) out.add(new Object[]{"replay", r, 0L});
        return out;
    }

    private final String kind, name;
    private final long arg;

    public AdvProtocolFrameTest(String kind, String name, long arg) {
        this.kind = kind;
        this.name = name;
        this.arg = arg;
    }

    static Map<String, Object> args() {
        Map<String, Object> a = new TreeMap<String, Object>();
        a.put("percent", 30L);
        a.put("note", "调音量");
        return a;
    }

    @SuppressWarnings("unchecked")
    @Test public void frame() {
        if (kind.equals("tamper")) {
            Map<String, Object> b = PcProtocol.command(KEY, "m1", "pc1", "ph1", "volume_set", args(), T0);
            assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "m1", "pc1", "ph1", b, T0, null));
            String id = "m1", from = "pc1", to = "ph1";
            byte[] k = KEY;
            PcProtocol.Check want = PcProtocol.Check.BAD_SIG;
            Map<String, Object> x = new TreeMap<String, Object>(b);
            if (name.equals("id")) id = "m2";
            else if (name.equals("from")) from = "pc2";
            else if (name.equals("to")) to = "ph2";
            else if (name.equals("cmd")) x.put("cmd", "sys_exec");
            else if (name.equals("argVal")) { Map<String, Object> a = args(); a.put("percent", 100L); x.put("args", a); }
            else if (name.equals("argAdd")) { Map<String, Object> a = args(); a.put("_sensitive", Boolean.TRUE); x.put("args", a); }
            else if (name.equals("bodyAdd")) x.put("extra", "1");
            else if (name.equals("dropNonce")) x.remove("nonce");
            else if (name.equals("v2")) x.put("v", 2L);
            else if (name.equals("vString")) x.put("v", "1");
            else if (name.equals("tsPlus1")) x.put("ts", T0 + 1);
            else if (name.equals("tsString")) x.put("ts", String.valueOf(T0));
            else if (name.equals("key")) k = KEY2;
            else if (name.equals("sigChar")) { String s = (String) x.get("sig"); x.put("sig", (s.charAt(0) == 'A' ? 'B' : 'A') + s.substring(1)); }
            else if (name.equals("sigNum")) { x.put("sig", 12345L); want = PcProtocol.Check.NO_SIG; }
            else if (name.equals("sigEmpty")) x.put("sig", "");
            else if (name.equals("dropSig")) { x.remove("sig"); want = PcProtocol.Check.NO_SIG; }
            else if (name.equals("sigPad")) x.put("sig", x.get("sig") + "=");
            else if (name.equals("kindHello")) x.put("kind", "hello");
            else if (name.equals("argsNull")) x.put("args", null);
            else if (name.equals("argKeyCase")) { Map<String, Object> a = new TreeMap<String, Object>(); a.put("Percent", 30L); a.put("note", "调音量"); x.put("args", a); }
            else if (name.equals("unicodeArg")) { Map<String, Object> a = args(); a.put("note", "调音\u200b量"); x.put("args", a); }
            PcProtocol.Check got = PcProtocol.verify(k, id, from, to, x, T0, new PcProtocol.ReplayGuard());
            assertEquals(name, want, got);
            // 经过 JSON 来回（电脑端发来的就是文本）结论不变
            Map<String, Object> rt = PcJson.obj(PcJson.stringify(x));
            assertEquals(name + " 序列化后", want, PcProtocol.verify(k, id, from, to, rt, T0, null));
        } else if (kind.equals("skew")) {
            long ts = arg == T0 ? 0 : T0 + arg;   // T0 这一格：ts = 0（1970 年）
            Map<String, Object> b = PcProtocol.command(KEY, "m1", "pc1", "ph1", "ping", null, ts);
            PcProtocol.Check got = PcProtocol.verify(KEY, "m1", "pc1", "ph1", b, T0, new PcProtocol.ReplayGuard());
            boolean in = Math.abs(T0 - ts) <= PcProtocol.MAX_SKEW_MS;
            assertEquals(name, in ? PcProtocol.Check.OK : PcProtocol.Check.STALE, got);
        } else {
            PcProtocol.ReplayGuard g = new PcProtocol.ReplayGuard();
            Map<String, Object> a = PcProtocol.command(KEY, "m1", "pc1", "ph1", "ping", null, T0);
            Map<String, Object> b = PcProtocol.command(KEY, "m2", "pc1", "ph1", "ping", null, T0 + 5000);
            if (name.equals("same")) {
                assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "m1", "pc1", "ph1", a, T0, g));
                assertEquals(PcProtocol.Check.REPLAY, PcProtocol.verify(KEY, "m1", "pc1", "ph1", a, T0, g));
            } else if (name.startsWith("sameLater")) {
                long later = Long.parseLong(name.substring(9, name.length() - 1)) * 1000L;
                assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "m1", "pc1", "ph1", a, T0, g));
                PcProtocol.Check c = PcProtocol.verify(KEY, "m1", "pc1", "ph1", a, T0 + later, g);
                // 时间窗内一律 REPLAY；超出时间窗（> 120 s）一律 STALE —— 不管 nonce 记录还在不在，重放都不会是 OK
                assertEquals(name, later <= PcProtocol.MAX_SKEW_MS ? PcProtocol.Check.REPLAY : PcProtocol.Check.STALE, c);
            } else if (name.equals("outOfOrder")) {
                assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "m2", "pc1", "ph1", b, T0 + 5000, g));
                assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "m1", "pc1", "ph1", a, T0 + 5001, g));
                assertEquals(PcProtocol.Check.REPLAY, PcProtocol.verify(KEY, "m2", "pc1", "ph1", b, T0 + 5002, g));
            } else if (name.equals("twoNonces")) {
                assertNotEquals(a.get("nonce"), b.get("nonce"));
                assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "m1", "pc1", "ph1", a, T0, g));
                assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "m2", "pc1", "ph1", b, T0 + 1, g));
            } else if (name.equals("afterOther")) {
                assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "m1", "pc1", "ph1", a, T0, g));
                assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "m2", "pc1", "ph1", b, T0 + 6000, g));
                assertEquals(PcProtocol.Check.REPLAY, PcProtocol.verify(KEY, "m1", "pc1", "ph1", a, T0 + 7000, g));
            } else if (name.equals("differentId")) {
                // 同一 body 换外层 id：签名不对（id 在签名里），不是重放
                assertEquals(PcProtocol.Check.OK, PcProtocol.verify(KEY, "m1", "pc1", "ph1", a, T0, g));
                assertEquals(PcProtocol.Check.BAD_SIG, PcProtocol.verify(KEY, "m9", "pc1", "ph1", a, T0, g));
            } else fail(name);
        }
    }
}
