package com.onesay.desktop;

import static org.junit.Assert.*;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;

/**
 * [rel-1123 合并] 三条线合并后用户拍板的改动：固定配对码 + SPAKE2 的免扫码配对、手机确认卡「允许本次任务」、
 * 手机页交给手机的目标显示回传结果、主页 / 设置页的配对界面。
 */
public class Merge1123Test {

    static String hex(byte[] b) {
        StringBuilder s = new StringBuilder();
        for (byte x : b) s.append(String.format("%02x", x));
        return s.toString();
    }

    static byte[] kSalt() {
        byte[] s = new byte[16];
        for (int i = 0; i < 16; i++) s[i] = (byte) i;
        return s;
    }

    // ------------------------------------------------------------------ SPAKE2

    /** 已知答案：和独立写的 Python 参考实现（hashlib / pow）算出的一样；手机端 PcSpake2 锁同一组值。 */
    @Test public void spakeKnownAnswer() {
        byte[] salt = kSalt(), w = Spake2.w("4827", salt, 10000);
        assertEquals("95432166f9f717d15e05b75adb75b0a254f606a4ffd8546a9e5aeca7af6e9a13", hex(w));
        Spake2.Ctx c = new Spake2.Ctx("phone-kat", "pc-kat", "AA:BB:CC", "dd:ee:ff", salt, 10000);
        Spake2.Party a = new Spake2.Party(true, w, BigInteger.valueOf(123456789L)), b = new Spake2.Party(false, w, BigInteger.valueOf(987654321L));
        assertTrue(hex(a.message()).startsWith("ed14db8a0e14be497290040ab96b16a0"));
        assertTrue(hex(b.message()).startsWith("ec4772ed30569b63d641b5f12067c1f8"));
        Spake2.Keys ka = a.finish(b.message(), c), kb = b.finish(a.message(), c);
        assertEquals("3dd923849afd643952264f909bc5cf35d55158b06f9ba309e03f6bb55b465c14", hex(ka.h));
        assertArrayEquals(ka.h, kb.h);
        assertEquals("1e796d2dcf93ba5db464128c544b8310263c16139858b35dce2179c93a3d783c", hex(ka.cA));
        assertEquals("d2519f017bed7ea51f2c025de9e35628337724a446c250e908bd7b97c5f74cc7", hex(ka.cB));
        // 盲化不改结果：同一组随机数再算一遍还是一样
        assertArrayEquals(a.message(), new Spake2.Party(true, w, BigInteger.valueOf(123456789L)).message());
    }

    @Test public void spakeGroupConstantsAreSane() {
        assertEquals(2048, Spake2.P.bitLength());
        assertTrue(Spake2.P.isProbablePrime(40));
        assertTrue("安全素数：q 也是素数", Spake2.Q.isProbablePrime(40));
        assertEquals(BigInteger.ONE, Spake2.G.modPow(Spake2.Q, Spake2.P));
        assertEquals(BigInteger.ONE, Spake2.M.modPow(Spake2.Q, Spake2.P));
        assertEquals(BigInteger.ONE, Spake2.N.modPow(Spake2.Q, Spake2.P));
        assertNotEquals(Spake2.M, Spake2.N);
    }

    @Test public void spakeRejectsBadElementsAndMitm() {
        byte[] salt = Spake2.salt(), w = Spake2.w("3951", salt, Spake2.ITER_MIN);
        Spake2.Ctx c = new Spake2.Ctx("ph", "pc", "aa", "bb", salt, Spake2.ITER_MIN);
        Spake2.Party b = new Spake2.Party(false, w);
        assertNull(b.finish(Spake2.fixed(BigInteger.ZERO), c));
        assertNull(b.finish(Spake2.fixed(BigInteger.ONE), c));
        assertNull(b.finish(Spake2.fixed(Spake2.P.subtract(BigInteger.ONE)), c));
        assertNull("长度不对", b.finish(new byte[255], c));
        BigInteger nonQr = BigInteger.valueOf(3);
        while (nonQr.modPow(Spake2.Q, Spake2.P).equals(BigInteger.ONE)) nonQr = nonQr.add(BigInteger.ONE);
        assertNull("不在 q 阶子群里的元素不收", b.finish(Spake2.fixed(nonQr), c));
        // 中间人：两边看到的证书不一样 → 确认码对不上
        Spake2.Party a = new Spake2.Party(true, w), b2 = new Spake2.Party(false, w);
        Spake2.Keys ka = a.finish(b2.message(), new Spake2.Ctx("ph", "pc", "aa", "bb", salt, Spake2.ITER_MIN));
        Spake2.Keys kb = b2.finish(a.message(), new Spake2.Ctx("ph", "pc", "aa", "cc", salt, Spake2.ITER_MIN));
        assertFalse(Spake2.same(ka.cA, kb.cA));
        // 证书指纹写法不同（大小写 / 冒号）算同一张
        assertEquals(Spake2.normFp("AA:BB"), Spake2.normFp("aabb"));
    }

    @Test public void spakeMessagesDoNotCarryTheCode() {
        byte[] salt = Spake2.salt(), w = Spake2.w("3951", salt, Spake2.ITER_MIN);
        byte[] m1 = new Spake2.Party(true, w).message(), m2 = new Spake2.Party(true, w).message();
        assertFalse("同一个码每次发出去的都不一样（带一次性随机数）", java.util.Arrays.equals(m1, m2));
        String s = new String(m1, StandardCharsets.ISO_8859_1);
        assertFalse(s.contains("3951"));
        assertFalse(hex(m1).contains(hex(w)));
        // 没有 x 的人拿 m1 和猜的码算不出同一把：错码那边确认码对不上（每次只能在线猜一个）
        Spake2.Party a = new Spake2.Party(true, w), evil = new Spake2.Party(false, Spake2.w("3952", salt, Spake2.ITER_MIN));
        Spake2.Ctx c = new Spake2.Ctx("ph", "pc", "aa", "bb", salt, Spake2.ITER_MIN);
        assertFalse(Spake2.same(a.finish(evil.message(), c).cB, evil.finish(a.message(), c).cB));
    }

    // ------------------------------------------------------------------ 配对码 / 限次

    @Test public void weakCodesAndVerifier() {
        for (String bad : new String[]{"0000", "1111", "1234", "4321", "6789", "123", "12345", "12a4", "", null}) assertNotNull(bad, LanPair.weakCode(bad));
        for (String ok : new String[]{"4827", "0428", "1357", "2580"}) assertNull(ok, LanPair.weakCode(ok));
        LanPair.Verifier v = LanPair.Verifier.of("4827");
        String f = v.format();
        assertFalse("存的不是码本身", f.contains("4827"));
        LanPair.Verifier back = LanPair.Verifier.parse(f);
        assertArrayEquals(v.w, back.w);
        assertArrayEquals(v.salt, back.salt);
        assertArrayEquals("同一个码同一个盐算出同一个 w", v.w, Spake2.w("4827", v.salt, v.iter));
        assertNull(LanPair.Verifier.parse("v1:5:AAAA:BBBB"));
        assertNull(LanPair.Verifier.parse("junk"));
        assertNull(LanPair.Verifier.parse(null));
    }

    @Test public void lockFiveTenMinutesThenLonger() {
        LanPair.Lock l = new LanPair.Lock(0, 0);
        long t = 1_000_000;
        for (int i = 0; i < 4; i++) assertEquals(0, l.fail(t));
        assertEquals(1, l.left());
        assertEquals(t + LanPair.Lock.L1, l.fail(t));
        assertTrue(l.locked(t + LanPair.Lock.L1 - 1));
        assertFalse(l.locked(t + LanPair.Lock.L1));
        for (int i = 0; i < 4; i++) l.fail(t);
        assertEquals("累计 10 次锁 1 小时", t + LanPair.Lock.L2, l.fail(t));
        for (int i = 0; i < 4; i++) l.fail(t);
        assertEquals("之后每 5 次锁 24 小时", t + LanPair.Lock.L3, l.fail(t));
        l.ok();
        assertFalse(l.locked(t));
        assertEquals(5, l.left());
    }

    @Test public void pairSettingsPersist() throws Exception {
        Config c = new Config(Files.createTempFile("pc", ".properties"));
        assertFalse(c.pairInvite);
        c.pairInvite = true;
        c.pairCodeSet = true;
        c.pairFails = 3;
        c.pairLockUntil = 1234L;
        c.save();
        Config d = Config.load(c.file);
        assertTrue(d.pairInvite && d.pairCodeSet);
        assertEquals(3, d.pairFails);
        assertEquals(1234L, d.pairLockUntil);
        String raw = new String(Files.readAllBytes(c.file), StandardCharsets.UTF_8);
        assertFalse("配置文件里没有码 / w", raw.contains("v1:"));
        assertTrue(LanPair.whyZh("nocode").contains("这台电脑还没设配对码，请在电脑上先设一个"));
        assertTrue(LanPair.whyZh("code", 3, 0).contains("还能试 3 次"));
        assertTrue(LanPair.whyZh("locked", 0, 10).contains("10 分钟后再试"));
    }

    // ------------------------------------------------------------------ 手机确认卡「允许本次任务」

    @Test public void phoneCardTaskScope() throws Exception {
        AtomicLong t = new AtomicLong(0);
        PcGuard.Confirms cf = ApprovalGuardTest.fake(t);
        AtomicReference<PcGuard.PhoneOffer> sent = new AtomicReference<>();
        cf.phoneOffer = o -> { sent.set(o); return true; };
        // 给了「允许本次任务」：手机卡上点 → TASK_ALLOW
        CompletableFuture<Integer> r = CompletableFuture.supplyAsync(() -> cf.ask("phone", "sys_exec", "建文件夹", LinkPolicy.CONFIRM_TIMEOUT_MS, false, "", true));
        int id = ApprovalGuardTest.waitId(cf);
        for (int i = 0; i < 200 && sent.get() == null; i++) Thread.sleep(5);
        PcGuard.PhoneOffer o = sent.get();
        assertTrue("发到手机的卡也带「允许本次任务」", o.task);
        t.addAndGet(800);
        assertEquals("语音不算", ConfirmGate.Answer.NOT_LOCAL, cf.answerPhone("phone", id, o.token, true, false, false, "task", "voice", 900));
        assertEquals("显示不满 0.7 秒不算", ConfirmGate.Answer.TOO_EARLY, cf.answerPhone("phone", id, o.token, true, false, false, "task", "touch", 100));
        assertEquals(ConfirmGate.Answer.ACCEPTED, cf.answerPhone("phone", id, o.token, true, false, false, "task", "touch", 900));
        assertEquals(PcGuard.TASK_ALLOW, (int) r.get(2, TimeUnit.SECONDS));
        // 没给（危险项）：手机上说 task 也只算允许一次
        sent.set(null);
        r = CompletableFuture.supplyAsync(() -> cf.ask("phone", "uninstall", "卸载", LinkPolicy.CONFIRM_TIMEOUT_MS, false, "", false));
        id = ApprovalGuardTest.waitId(cf);
        for (int i = 0; i < 200 && sent.get() == null; i++) Thread.sleep(5);
        assertFalse(sent.get().task);
        t.addAndGet(800);
        assertEquals(ConfirmGate.Answer.ACCEPTED, cf.answerPhone("phone", id, sent.get().token, true, false, false, "task", "touch", 900));
        assertEquals(PcGuard.ALLOW, (int) r.get(2, TimeUnit.SECONDS));
        // 手机上不给「这次运行都允许」
        sent.set(null);
        r = CompletableFuture.supplyAsync(() -> cf.ask("phone", "sys_exec", "x", LinkPolicy.CONFIRM_TIMEOUT_MS, false, "", true));
        id = ApprovalGuardTest.waitId(cf);
        for (int i = 0; i < 200 && sent.get() == null; i++) Thread.sleep(5);
        t.addAndGet(800);
        assertEquals(ConfirmGate.Answer.ACCEPTED, cf.answerPhone("phone", id, sent.get().token, true, false, false, "session", "touch", 900));
        assertEquals(PcGuard.ALLOW, (int) r.get(2, TimeUnit.SECONDS));
    }

    // ------------------------------------------------------------------ 界面（静态）

    static String ui() throws Exception {
        return new String(Files.readAllBytes(Paths.get("src/main/resources/onesay/ui/index.html")), StandardCharsets.UTF_8);
    }

    @Test public void pairUiWiring() throws Exception {
        String h = ui();
        for (String must : new String[]{"function nearbyBlock(", "function codeForm(", "op:'code'", "op:'invite'", "op:'code_off'", "op:'unlock'", "r.need_code",
                "配对新手机", "找附近的电脑", "允许新手机配对（免扫码）", "S.phone_why", "function opResult(", "S.phone_goal", "POPGID"})
            assertTrue(must, h.contains(must));
        assertFalse("电脑弹框核对码的界面删掉了", h.contains("pairCodeBox"));
        assertTrue("配对码输入框是密码框、只收数字", h.contains("type=\"password\" inputmode=\"numeric\""));
        for (String bad : new String[]{"Bixby", "三星", "DeX", "小布", "啥", "咋", "呗"}) {
            int i = h.indexOf("function nearbyBlock(");
            assertFalse(bad, h.substring(i, i + 3000).contains(bad));
        }
    }

    /** 顶层函数 / 常量不重名（合并时 hm 重名过一次，整个界面脚本起不来；JDK 里没有 JS 引擎，用这个静态检查兜底，另外用无头浏览器加载过页面）。 */
    @Test public void noDuplicateTopLevelJsNames() throws Exception {
        String h = ui();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?m)^(?:async\\s+)?function\\s+([A-Za-z_$][\\w$]*)\\s*\\(|^(?:const|let)\\s+([A-Za-z_$][\\w$]*)\\s*=").matcher(h);
        java.util.Set<String> seen = new java.util.HashSet<>();
        while (m.find()) {
            String n = m.group(1) != null ? m.group(1) : m.group(2);
            assertTrue("界面脚本里重名：" + n, seen.add(n));
        }
        assertTrue(seen.contains("nearbyBlock") && seen.contains("hm"));
    }

    @Test public void lanPairHasNoPopupFlowLeft() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get("src/main/java/com/onesay/desktop/LanServer.java")), StandardCharsets.UTF_8);
        assertFalse(src.contains("pairAsk"));
        assertFalse(src.contains("pair_reveal"));
        assertTrue(src.contains("pairFail(System.currentTimeMillis()); // 先记一次错"));
        Map<String, Object> m = Json.obj(LanPair.inviteText(47920, "DESKTOP-O1", true));
        assertEquals(2L, ((Number) m.get("v")).longValue());
    }
}
