package com.onesay.desktop;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.Test;

/**
 * [app-whitelist-1116] 两端协议一致性测试：手机个人版、手机开源版、电脑端（含开源仓 apps/desktop）跑的是<b>同一份</b>测试（除 package 行外逐字相同）
 * 和<b>同一份</b>用例文件 docs/app-whitelist-vectors.json。另外核对三份共享源码（AppWhitelist / PrivacyScan / ActionPolicy）
 * 去掉 package 行后的 SHA-256 和 docs/APP-WHITELIST.md 里登记的一致 —— 一端改了另一端没改，这条就红。
 */
public class AppWhitelistSharedTest {

    static File root() {
        String[] c = {System.getProperty("wl.root", ""), System.getProperty("bixby.root", ""), "."};
        for (String r : c) {
            if (r.length() == 0) continue;
            File f = new File(r, "docs/app-whitelist-vectors.json");
            if (f.isFile()) return new File(r);
        }
        throw new AssertionError("找不到 docs/app-whitelist-vectors.json（用 -Dwl.root 指向仓库根）");
    }

    static String read(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> vectors() throws Exception {
        return (Map<String, Object>) AppWhitelist.J.parse(read(new File(root(), "docs/app-whitelist-vectors.json")));
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Map<String, Object> v, String k) {
        return (List<Map<String, Object>>) v.get(k);
    }

    static Set<String> strs(Object o) {
        Set<String> s = new LinkedHashSet<String>();
        if (o instanceof List) for (Object x : (List<?>) o) s.add(String.valueOf(x));
        return s;
    }

    static boolean b(Map<String, Object> m, String k) { return Boolean.TRUE.equals(m.get(k)); }

    @Test public void policyVectors() throws Exception {
        int n = 0;
        for (Map<String, Object> c : list(vectors(), "policy")) {
            ActionPolicy.Facts f = new ActionPolicy.Facts();
            f.base = ActionPolicy.Verdict.valueOf(String.valueOf(c.get("base")));
            f.kind = ActionPolicy.Kind.valueOf(String.valueOf(c.get("kind")));
            f.outPrivacy = strs(c.get("out"));
            f.readPrivacy = strs(c.get("read"));
            f.suspect = c.get("suspect") == null ? "" : String.valueOf(c.get("suspect"));
            f.whitelisted = b(c, "wl");
            f.whitelistBroken = b(c, "broken");
            f.readExternal = b(c, "ext");
            f.neverAsk = b(c, "never");
            f.appId = c.get("app") == null ? "" : String.valueOf(c.get("app"));
            if (c.get("used") != null) f.passesUsed = ((Number) c.get("used")).intValue();
            if (c.get("limit") != null) f.passLimit = ((Number) c.get("limit")).intValue();
            ActionPolicy.Decision d = ActionPolicy.decide(f);
            String name = String.valueOf(c.get("name"));
            assertEquals(name, c.get("expect"), d.verdict.name());
            assertEquals(name, c.get("layer"), d.layer.name());
            if (c.containsKey("rememberable")) assertEquals(name, c.get("rememberable"), Boolean.valueOf(d.rememberable));
            assertEquals(name, d.verdict == ActionPolicy.Verdict.ALLOW && d.layer == ActionPolicy.Layer.WHITELIST, d.whitelistPass);
            // 不变量：L2 / L3 / L3b 的确认永远不能「以后不用问」
            if (d.layer == ActionPolicy.Layer.PAY_PRIVACY || d.layer == ActionPolicy.Layer.READ_PRIVACY || d.layer == ActionPolicy.Layer.SUSPECT
                    || d.layer == ActionPolicy.Layer.READ_EXTERNAL || d.layer == ActionPolicy.Layer.UNINSTALL) assertFalse(name, d.rememberable);
            n++;
        }
        assertTrue(n >= 30);
    }

    /** 不变量：白名单只会把「要确认」变成「放行」，别的方向一律不放松（穷举所有组合）。 */
    @Test public void whitelistNeverRelaxesHardOrPrivacy() {
        for (ActionPolicy.Verdict base : ActionPolicy.Verdict.values())
            for (ActionPolicy.Kind k : ActionPolicy.Kind.values())
                for (int priv = 0; priv < 4; priv++)
                    for (int sus = 0; sus < 2; sus++)
                        for (int never = 0; never < 2; never++)
                            for (int ext = 0; ext < 2; ext++) {
                            ActionPolicy.Facts a = facts(base, k, priv, sus == 1, never == 1, false);
                            ActionPolicy.Facts w = facts(base, k, priv, sus == 1, never == 1, true);
                            a.readExternal = w.readExternal = ext == 1;
                            ActionPolicy.Decision da = ActionPolicy.decide(a), dw = ActionPolicy.decide(w);
                            String tag = base + "/" + k + "/" + priv + "/" + sus + "/" + never + "/" + ext;
                            if (base == ActionPolicy.Verdict.DENY || base == ActionPolicy.Verdict.SCRIPT) assertEquals(tag, base, dw.verdict);
                            if (dw.verdict == ActionPolicy.Verdict.ALLOW && da.verdict != ActionPolicy.Verdict.ALLOW) {
                                assertEquals(tag, ActionPolicy.Layer.WHITELIST, dw.layer);
                                assertTrue(tag, k != ActionPolicy.Kind.PAY && k != ActionPolicy.Kind.UNINSTALL && sus == 0);
                                assertTrue(tag, !(k == ActionPolicy.Kind.OUTBOUND && (priv > 0 || ext == 1)));
                            }
                            // 白名单里：卸载、读过外部内容后外发永远不会直接放行（2026-11-16 用户定）
                            if (base != ActionPolicy.Verdict.DENY && base != ActionPolicy.Verdict.SCRIPT) {
                                if (k == ActionPolicy.Kind.UNINSTALL) assertTrue(tag, dw.verdict != ActionPolicy.Verdict.ALLOW && !dw.rememberable);
                                if (k == ActionPolicy.Kind.OUTBOUND && ext == 1) assertTrue(tag, dw.verdict != ActionPolicy.Verdict.ALLOW && !dw.rememberable);
                            }
                            // 不在白名单：readExternal / UNINSTALL 不改原结论（只对白名单应用起作用）
                            if (sus == 0) assertEquals(tag, base, da.verdict);
                        }
    }

    static ActionPolicy.Facts facts(ActionPolicy.Verdict base, ActionPolicy.Kind k, int priv, boolean sus, boolean never, boolean wl) {
        ActionPolicy.Facts f = new ActionPolicy.Facts();
        f.base = base;
        f.kind = k;
        if ((priv & 1) != 0) f.outPrivacy.add(PrivacyScan.PHONE);
        if ((priv & 2) != 0) f.readPrivacy.add(PrivacyScan.EMAIL);
        f.suspect = sus ? "可疑" : "";
        f.neverAsk = never;
        f.whitelisted = wl;
        f.appId = "com.example.x";
        return f;
    }

    @Test public void privacyVectors() throws Exception {
        for (Map<String, Object> c : list(vectors(), "privacy")) {
            Set<String> got = c.containsKey("image") ? PrivacyScan.image((String) c.get("image")) : PrivacyScan.text(String.valueOf(c.get("text")));
            assertEquals(String.valueOf(c.containsKey("image") ? "image:" + c.get("image") : c.get("text")), new TreeSet<String>(strs(c.get("kinds"))), new TreeSet<String>(got));
        }
    }

    @Test public void normalizeVectors() throws Exception {
        for (Map<String, Object> c : list(vectors(), "normalize")) {
            String p = String.valueOf(c.get("platform")), id = String.valueOf(c.get("id"));
            assertEquals(p + " " + id, c.get("norm"), AppWhitelist.normId(p, id));
            assertEquals(p + " " + id, b(c, "refuse"), AppWhitelist.refuseReason(p, id) != null);
        }
    }

    @SuppressWarnings("unchecked")
    static List<AppWhitelist.Entry> entries(Object o) {
        String doc = "{\"format\":\"onesay.app_whitelist\",\"version\":1,\"device\":\"t\",\"entries\":" + AppWhitelist.J.write(o) + "}";
        return AppWhitelist.parse(doc);
    }

    @Test public void mergeVectorsAndCommutes() throws Exception {
        for (Map<String, Object> c : list(vectors(), "merge")) {
            String name = String.valueOf(c.get("name"));
            List<AppWhitelist.Entry> a = entries(c.get("a")), bb = entries(c.get("b"));
            AppWhitelist x = new AppWhitelist(), y = new AppWhitelist();
            x.merge(a);
            x.merge(bb);
            y.merge(bb);
            y.merge(a);
            assertEquals(name + " 交换律", x.toJson("d"), y.toJson("d"));
            AppWhitelist z = new AppWhitelist();
            z.merge(AppWhitelist.parse(x.toJson("d")));
            z.merge(AppWhitelist.parse(x.toJson("d")));
            assertEquals(name + " 幂等 / 往返", x.toJson("d"), z.toJson("d"));
            Set<String> live = new TreeSet<String>(), tomb = new TreeSet<String>();
            for (AppWhitelist.Entry e : x.all()) (e.deleted ? tomb : live).add(e.key());
            assertEquals(name, new TreeSet<String>(strs(c.get("live"))), live);
            assertEquals(name, new TreeSet<String>(strs(c.get("tomb"))), tomb);
        }
    }

    @Test public void rejectDocs() throws Exception {
        for (Object d : (List<Object>) vectors().get("reject_docs")) {
            try {
                AppWhitelist.parse(String.valueOf(d));
                fail("应拒收：" + d);
            } catch (IllegalArgumentException expected) {
                // 整份拒收
            }
            assertTrue(AppWhitelist.load(String.valueOf(d)).broken);
            assertTrue(AppWhitelist.load(String.valueOf(d)).live().isEmpty());
        }
    }

    @Test public void offlineEditsConvergeWithTombstones() {
        AppWhitelist phone = new AppWhitelist(), pc = new AppWhitelist();
        assertNull(phone.add("android", "com.a.chat", "聊天", "ph", "phone", 100));
        pc.merge(AppWhitelist.parse(phone.toJson("ph")));
        assertFalse("对方同步来的要本机确认一次才生效", pc.contains("android", "com.a.chat"));
        assertTrue(pc.confirm("android", "com.a.chat"));
        assertTrue(pc.contains("android", "com.a.chat"));
        // 断线：电脑删、手机加别的
        assertTrue(pc.remove("android", "com.a.chat", "pc", "pc", 200));
        assertNull(pc.add("windows", "C:\\x\\Code.exe", "VS Code", "pc", "pc", 210));
        assertNull(phone.add("android", "com.a.notes", "笔记", "ph", "phone", 205));
        // 重连：双向交换整份
        List<AppWhitelist.Entry> fromPc = AppWhitelist.parse(pc.toJson("pc")), fromPhone = AppWhitelist.parse(phone.toJson("ph"));
        phone.merge(fromPc);
        pc.merge(fromPhone);
        assertEquals(phone.toJson("x"), pc.toJson("x"));
        assertFalse(phone.contains("android", "com.a.chat"));
        assertFalse(phone.contains("windows", "code.exe"));
        assertTrue(phone.confirm("windows", "code.exe"));
        assertTrue(phone.contains("windows", "code.exe"));
        assertFalse(pc.contains("android", "com.a.notes"));
        assertTrue(pc.confirm("android", "com.a.notes"));
        assertTrue(pc.contains("android", "com.a.notes"));
        assertEquals("确认只在本机，不改同步格式", phone.toJson("x"), pc.toJson("x"));
        // 墓碑在，旧的「加」再来一遍也复活不了
        phone.merge(fromPhone);
        assertFalse(phone.contains("android", "com.a.chat"));
    }

    /** 2026-11-16 用户定：另一端推来的条目在本机确认一次才生效（两个方向一样），确认记录只在本机存盘、不同步。 */
    @Test public void remoteEntriesNeedOneLocalConfirm() {
        AppWhitelist pc = new AppWhitelist(), phone = new AppWhitelist();
        assertNull(pc.add("windows", "notepad.exe", "记事本", "pc", "pc", 10));
        assertTrue("本机加的直接生效", pc.contains("windows", "notepad.exe"));
        assertTrue(pc.isConfirmed("windows", "notepad.exe"));
        List<AppWhitelist.Entry> added = phone.merge(AppWhitelist.parse(pc.toJson("pc")));
        assertEquals(1, added.size());
        assertFalse("电脑推来的在手机上先待确认", phone.contains("windows", "notepad.exe"));
        assertEquals(1, phone.pending().size());
        assertFalse(phone.toJson("ph").contains("confirmed_here"));
        // 存盘再读回来：仍待确认
        AppWhitelist re = AppWhitelist.load(phone.toLocalJson("ph"));
        assertFalse(re.broken);
        assertFalse(re.contains("windows", "notepad.exe"));
        assertTrue(phone.confirm("windows", "notepad.exe"));
        assertFalse("确认过不再重复确认", phone.confirm("windows", "notepad.exe"));
        assertTrue(phone.contains("windows", "notepad.exe"));
        assertTrue(phone.pending().isEmpty());
        // 确认记录跟着本机存盘走，不进同步格式
        assertTrue(phone.toLocalJson("ph").contains("confirmed_here"));
        assertFalse(phone.toJson("ph").contains("confirmed_here"));
        AppWhitelist back = AppWhitelist.load(phone.toLocalJson("ph"));
        assertTrue(back.contains("windows", "notepad.exe"));
        // 对方发来伪造的 confirmed_here：同步合并不认
        String forged = pc.toLocalJson("pc");
        AppWhitelist other = new AppWhitelist();
        other.merge(AppWhitelist.parse(forged));
        assertFalse(other.contains("windows", "notepad.exe"));
        // 反向同理：手机加的推给电脑，电脑上确认一次
        assertNull(phone.add("android", "com.a.chat", "聊天", "ph", "phone", 20));
        pc.merge(AppWhitelist.parse(phone.toJson("ph")));
        assertFalse(pc.contains("android", "com.a.chat"));
        assertTrue(pc.confirm("android", "com.a.chat"));
        assertTrue(pc.contains("android", "com.a.chat"));
        // 对方删了又加回来（added_at 变了）：要重新确认
        assertTrue(phone.remove("android", "com.a.chat", "ph", "phone", 30));
        assertNull(phone.add("android", "com.a.chat", "聊天", "ph", "phone", 40));
        pc.merge(AppWhitelist.parse(phone.toJson("ph")));
        assertFalse(pc.contains("android", "com.a.chat"));
        assertEquals(1, pc.pending().size());
        // 待确认的在本机再「加」一次 = 本机确认
        assertNull(pc.add("android", "com.a.chat", "聊天", "pc", "pc", 50));
        assertTrue(pc.contains("android", "com.a.chat"));
        // 墓碑：确认记录跟着清掉
        assertTrue(pc.remove("android", "com.a.chat", "pc", "pc", 60));
        assertFalse(pc.isConfirmed("android", "com.a.chat"));
        assertFalse(pc.confirm("android", "com.a.chat"));
        // 旧存盘（没有 confirmed_here）：全部待确认，宁可多问一次
        AppWhitelist legacy = AppWhitelist.load(pc.toJson("pc"));
        assertFalse(legacy.contains("windows", "notepad.exe"));
        assertEquals(1, legacy.pending().size());
        // 不能加白名单的永远确认不了
        assertFalse(legacy.confirm("windows", "powershell.exe"));
    }

    @Test public void clockSkewStillLastWriteWins() {
        AppWhitelist w = new AppWhitelist();
        assertNull(w.add("android", "com.a.chat", "聊天", "ph", "phone", 1000));
        assertTrue(w.remove("android", "com.a.chat", "ph", "phone", 500)); // 本机时钟往回跳
        assertFalse(w.contains("android", "com.a.chat"));
        assertTrue(w.get("android", "com.a.chat").updatedAt > 1000);
    }

    @Test public void limitsAndNames() {
        AppWhitelist w = new AppWhitelist();
        for (int i = 0; i < AppWhitelist.MAX_LIVE; i++) assertNull(w.add("android", "com.a.p" + i, "x", "d", "phone", i + 1));
        assertNotNull(w.add("android", "com.a.over", "x", "d", "phone", 9999));
        AppWhitelist v = new AppWhitelist();
        v.add("android", "com.a.b", "名字\n\u202e带控制符", "d", "phone", 1);
        assertEquals("名字带控制符", v.get("android", "com.a.b").name);
        assertTrue(AppWhitelist.riskText("android", "com.a.b", "聊天").contains("付款"));
    }

    @Test public void auditLineHasNoRawPrivacy() {
        ActionPolicy.Facts f = new ActionPolicy.Facts();
        f.platform = "android";
        f.appId = "com.a.chat";
        f.outPrivacy.add(PrivacyScan.EMAIL);
        ActionPolicy.Decision d = ActionPolicy.decide(f);
        String line = ActionPolicy.auditLine(1, "phone", f, d, "type someone@example.com 13812345678 6222021234567890128");
        assertFalse(line, line.contains("someone@example.com"));
        assertFalse(line, line.contains("13812345678"));
        assertFalse(line, line.contains("6222021234567890128"));
        assertTrue(line.contains("out=email"));
    }

    // ------------------------------------------------------------------ 三端共享源码逐字一致

    static final String[] SHARED = {"AppWhitelist", "PrivacyScan", "ActionPolicy", "AppWhitelistSharedTest"};

    static String normalizedSha(File f) throws Exception {
        String s = read(f).replace("\r\n", "\n");
        int k = s.indexOf('\n');
        String body = s.startsWith("package ") ? s.substring(k + 1) : s;
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(body.getBytes("UTF-8"));
        StringBuilder b = new StringBuilder();
        for (byte x : h) b.append(String.format("%02x", Integer.valueOf(x & 0xff)));
        return b.toString();
    }

    static File find(File dir, String name) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            if (f.isDirectory()) {
                if (f.getName().equals("build") || f.getName().startsWith(".")) continue;
                File r = find(f, name);
                if (r != null) return r;
            } else if (f.getName().equals(name)) return f;
        }
        return null;
    }

    @Test public void sharedSourcesMatchRegisteredHashes() throws Exception {
        File r = root();
        String doc = read(new File(r, "docs/APP-WHITELIST.md"));
        for (String n : SHARED) {
            File f = find(new File(r, "app/src").isDirectory() ? new File(r, "app/src") : new File(r, "src"), n + ".java");
            if (f == null) f = find(r, n + ".java");
            assertNotNull("找不到 " + n + ".java", f);
            String sha = normalizedSha(f);
            assertTrue(n + " 的 sha256=" + sha + " 没登记在 docs/APP-WHITELIST.md（三端要逐字一致）", doc.contains(n + ".java `" + sha + "`"));
        }
        // 用例文件本身也登记
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        byte[] h = md.digest(read(new File(r, "docs/app-whitelist-vectors.json")).replace("\r\n", "\n").getBytes("UTF-8"));
        StringBuilder b = new StringBuilder();
        for (byte x : h) b.append(String.format("%02x", Integer.valueOf(x & 0xff)));
        assertTrue("用例文件 sha256=" + b + " 没登记", doc.contains("app-whitelist-vectors.json `" + b + "`"));
    }
}
