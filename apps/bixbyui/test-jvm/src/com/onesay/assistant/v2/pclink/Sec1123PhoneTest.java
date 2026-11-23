package com.onesay.assistant.v2.pclink;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ sec-1123：审计（sec-audit-1118 REPORT.md）遗留的中 / 低危 M1–M6、L1–L6，每项一组测试。
 * M4 / M6 明文部分 / L2 之前已经修过（8fab2f8、68dfb8e），这里补回归，防止以后被改回去。
 */
public class Sec1123PhoneTest {

    static File root() {
        return new File(System.getProperty("bixby.root", "."));
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(root(), rel).toPath()), "UTF-8");
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> x = new TreeMap<String, Object>();
        for (int i = 0; i < kv.length; i += 2) x.put((String) kv[i], kv[i + 1]);
        return x;
    }

    static String reason(PcLanClient.Result r) {
        return r.data == null || r.data.get("reason") == null ? "" : String.valueOf(r.data.get("reason"));
    }

    /** 和 ApproveNotBlockPhoneTest 同款桩：记下问了什么、跑了什么；ask 里可以做点事（模拟等确认期间文件被换）。 */
    static final class Stub implements PcRx.Handler, PcRx.Guard, PcRx.Asker {
        final List<String> ran = new ArrayList<String>();
        final List<Map<String, Object>> ranArgs = new ArrayList<Map<String, Object>>();
        final List<byte[]> ranScripts = new ArrayList<byte[]>();
        final List<String> asked = new ArrayList<String>();
        final List<String> kinds = new ArrayList<String>();
        int answer = PcConfirm.DENY;
        Runnable onAsk;
        final File ws;

        Stub(File ws) { this.ws = ws; }

        @Override public boolean enabled(String cmd) { return true; }

        @Override public PcLanClient.Result run(String cmd, Map<String, Object> args) {
            ran.add(cmd);
            ranArgs.add(new TreeMap<String, Object>(args));
            ranScripts.add(PcRx.checkedScript());
            return new PcLanClient.Result(true, "好了", null, "");
        }

        @Override public void record(String cmd, boolean ok, String text) { }

        @Override public void appFacts(String cmd, Map<String, Object> a, LinkPolicy.Facts f) {
            String pkg = PcRxPolicy.str(a, "package");
            f.appSystem = pkg.startsWith("com.android.");
            f.appSelf = "com.onesay.me".equals(pkg);
            f.appBlocked = f.appBlocked || LinkPolicy.blockedApp(pkg, PcRxPolicy.str(a, "name"));
        }

        @Override public File workspace() { return ws; }

        @Override public String script(String cmd, Map<String, Object> a, String why) {
            return LinkPolicy.writeScript(ws, LinkPolicy.buildScript(LinkPolicy.ScriptKind.SH, cmd, why, "授权", PcRxPolicy.str(a, "command"), System.currentTimeMillis()));
        }

        @Override public int ask(String title, String detail, String rememberKind) {
            asked.add(rememberKind + "|" + detail);
            if (onAsk != null) onAsk.run();
            return answer;
        }

        @Override public java.util.Collection<String> rememberedKinds() { return kinds; }

        @Override public void remember(String kind) { kinds.add(kind); }

        @Override public boolean clipAutoClear() { return false; }
    }

    static Stub stub() throws Exception {
        return new Stub(Files.createTempDirectory("sec1123-ws").toFile());
    }

    // ================================================================== M1 系统命令红线可绕

    @Test public void m1InstallGrantAllAndRolesAreSelfGrant() {
        String[] grant = {"pm install -g /sdcard/x.apk", "pm install -r -g /sdcard/x.apk", "cmd package install -g /sdcard/x.apk",
                "pm install --grant-all-permissions /sdcard/x.apk", "pm install-create -g", "p'm' install -g x.apk",
                "cmd role add-role-holder android.app.role.SMS com.evil", "role add-role-holder android.app.role.DIALER com.evil",
                "pm set-home-activity com.evil/.Home", "cmd package set-home-activity com.evil/.Home"};
        for (String c : grant) assertEquals(c, LinkPolicy.Cat.SELF_GRANT, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, c));
        // 普通安装（不顺手给权限）照旧是系统命令
        assertEquals(LinkPolicy.Cat.SHELL_EXEC, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, "pm install -r /sdcard/x.apk"));
        assertEquals(LinkPolicy.Cat.SHELL_EXEC, LinkPolicy.classifyShell(LinkPolicy.Target.PHONE, "pm list packages -g"));
    }

    @Test public void m1GrantViaSysExecOnlyMakesScript() throws Exception {
        Stub h = stub();
        PcLanClient.Result r = PcRx.handle(h, "sys_exec", m("command", "pm install -g /sdcard/Download/x.apk"));
        assertFalse(r.ok);
        assertEquals(LinkPolicy.R_SCRIPT_GENERATED, reason(r));
        assertTrue(h.ran.isEmpty());
    }

    @Test public void m1CmdPackageUninstallChecksPackageLikePm() throws Exception {
        Stub h = stub();
        PcLanClient.Result self = PcRx.handle(h, "sys_exec", m("command", "cmd package uninstall com.onesay.me"));
        assertFalse(self.ok);
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, reason(self));
        assertFalse(PcRx.handle(h, "sys_exec", m("command", "cmd package uninstall --user 0 com.onesay.me")).ok);
        assertFalse(PcRx.handle(h, "sys_exec", m("command", "cmd package disable-user com.onesay.me")).ok);
        assertTrue(h.ran.isEmpty());
        h.answer = PcConfirm.DENY;
        PcLanClient.Result sys = PcRx.handle(h, "sys_exec", m("command", "cmd package uninstall com.android.settings"));
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, reason(sys));
        assertTrue(h.asked.get(0), h.asked.get(0).startsWith("phone.system_app|"));
        assertTrue(h.ran.isEmpty());
    }

    @Test public void m1IndirectExecutionAsksAndCannotBeRemembered() throws Exception {
        String[] indirect = {"echo com.onesay.me | xargs pm uninstall", "sh -c 'pm uninstall com.x.y'", "cat /sdcard/a.txt | sh",
                "echo cGluZw== | base64 -d | sh", "eval $X", "source /sdcard/a.sh", ". /sdcard/a.sh",
                "sh /sdcard/Download/run.sh", "find /sdcard -name x -exec rm {} ;", "chmod 755 /data/local/tmp/x.sh; /data/local/tmp/x.sh",
                "su -c id", "toybox xargs pm clear", "env A=1 sh", "app_process / com.x.Main", "bash /sdcard/x"};
        for (String c : indirect) {
            assertTrue(c, LinkPolicy.indirectShell(c).length() > 0 || LinkPolicy.oddShellWriting(c).length() > 0);
            Stub h = stub();
            h.kinds.add("phone.shell"); // 就算以前记住过别的类型，这种也照样问
            h.answer = PcConfirm.DENY;
            PcLanClient.Result r = PcRx.handle(h, "sys_exec", m("command", c));
            assertFalse(c, r.ok);
            assertTrue(c, h.ran.isEmpty());
            assertFalse(c + " 至少问一次", h.asked.isEmpty());
            boolean unremembered = false;
            for (String a : h.asked) if (a.startsWith("|")) unremembered = true;
            assertTrue(c + " 间接执行的那张卡不能记住：" + h.asked, unremembered);
        }
        // 用户允许了才跑
        Stub h = stub();
        h.answer = PcConfirm.ALLOW;
        assertTrue(PcRx.handle(h, "sys_exec", m("command", "echo a | xargs echo")).ok);
        assertEquals(Arrays.asList("sys_exec"), h.ran);
        assertTrue(h.asked.get(0), h.asked.get(0).contains("间接执行"));
    }

    @Test public void m1CommentsAndShebangIgnored() {
        assertEquals("", LinkPolicy.indirectShell("#!/system/bin/sh\necho hi\n"));
        assertEquals("", LinkPolicy.shellDoubt("#!/bin/sh\n# don't run sh foo here\necho hi\n"));
        assertTrue(LinkPolicy.indirectShell("#!/bin/sh\necho x | sh\n").length() > 0);
    }

    @Test public void m1OrdinaryCommandsUnchanged() throws Exception {
        for (String c : new String[]{"ls /sdcard", "dumpsys battery", "settings put system screen_brightness 100", "pm list packages",
                "getprop ro.build.version.release", "cmd wifi status", "am start -a android.intent.action.VIEW -d https://example.com"}) {
            assertEquals(c, "", LinkPolicy.indirectShell(c));
            Stub h = stub();
            assertTrue(c, PcRx.handle(h, "sys_exec", m("command", c)).ok);
            assertTrue(c + " 不弹卡：" + h.asked, h.asked.isEmpty());
        }
        // 词里碰巧带 sh 的不算（shortcut / shell 之类）
        assertEquals("", LinkPolicy.indirectShell("dumpsys shortcut"));
        assertEquals("", LinkPolicy.indirectShell("cmd shortcut get-default-launcher"));
    }

    // ================================================================== M2 脚本查完再读（TOCTOU）+ 脚本内容同样过红线

    static File script(Stub h, String name, String body) throws Exception {
        File d = new File(h.ws, "scripts");
        d.mkdirs();
        File f = new File(d, name);
        Files.write(f.toPath(), body.getBytes("UTF-8"));
        return f;
    }

    @Test public void m2RunsTheBytesThatWereChecked() throws Exception {
        final Stub h = stub();
        String body = "#!/system/bin/sh\nsh -c 'echo hi'\n";
        final File f = script(h, "a.sh", body);
        h.answer = PcConfirm.ALLOW;
        h.onAsk = new Runnable() {
            @Override public void run() {
                try {
                    Files.write(f.toPath(), "pm grant com.evil android.permission.CAMERA\n".getBytes("UTF-8")); // 等确认时被换掉
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        };
        PcLanClient.Result r = PcRx.handle(h, "script_run", m("name", "a.sh"));
        assertTrue(r.text, r.ok);
        assertEquals(1, h.ranArgs.size());
        byte[] b = h.ranScripts.get(0);
        assertNotNull("交给执行端的是查过的字节", b);
        assertEquals(body, new String(b, "UTF-8"));
        for (String k : h.ranArgs.get(0).keySet()) assertFalse("字节不放进参数表：" + k, k.startsWith("_"));
        assertNull("调用结束就清掉", PcRx.checkedScript());
        // 别的指令拿不到脚本字节
        Stub h2 = stub();
        assertTrue(PcRx.handle(h2, "status", m()).ok);
        assertNull(h2.ranScripts.get(0));
    }

    @Test public void m2ScriptContentHitsSameRedLines() throws Exception {
        Stub h = stub();
        script(h, "u.sh", "cmd package uninstall com.onesay.me\n");
        assertEquals(LinkPolicy.R_SELF_GRANT_BLOCKED, reason(PcRx.handle(h, "script_run", m("name", "u.sh"))));
        script(h, "g.sh", "pm install -g /sdcard/x.apk\n");
        assertEquals(LinkPolicy.R_SCRIPT_GENERATED, reason(PcRx.handle(h, "script_run", m("name", "g.sh"))));
        script(h, "x.sh", "echo com.android.settings | xargs pm disable-user\n");
        h.answer = PcConfirm.DENY;
        assertEquals(LinkPolicy.R_CONFIRM_DENIED, reason(PcRx.handle(h, "script_run", m("name", "x.sh"))));
        assertTrue(h.ran.isEmpty());
        // 普通脚本照旧直接跑
        script(h, "ok.sh", "echo hello\nls /sdcard\n");
        assertTrue(PcRx.handle(h, "script_run", m("name", "ok.sh")).ok);
        // 没有 / 太大
        int before = h.ranScripts.size();
        PcRx.handle(h, "script_run", m("name", "none.sh"));
        assertNull("没有文件：执行端拿不到字节（Android 侧回「没有这个脚本」、不跑）", h.ranScripts.get(before));
        StringBuilder big = new StringBuilder();
        while (big.length() <= PcRxPolicy.FILE_MAX) big.append("echo 0123456789abcdef\n");
        script(h, "big.sh", big.toString());
        assertFalse(PcRx.handle(h, "script_run", m("name", "big.sh")).ok);
    }

    @Test public void m2AndroidRunsPrivateCopyNotWorkspaceFile() throws Exception {
        String a = src("app/src/com/onesay/assistant/v2/pclink/PcRxAndroid.java");
        int i = a.indexOf("PcLanClient.Result scriptRun(String name, byte[] checked)");
        assertTrue(i > 0);
        String body = a.substring(i, a.indexOf("PcLanClient.Result shell(String command)", i));
        assertFalse("不再按文件名回工作区重读", body.contains("resolveInside") || body.contains("workspace()"));
        assertTrue(body.contains("PrivateScratch.dir(app, \"pcrun\")") && body.contains("out.write(checked)") && body.contains("copy.delete()"));
        assertTrue(a.contains("scriptRun(PcRxPolicy.str(a, \"name\"), PcRx.checkedScript())"));
    }

    // ================================================================== M3 读屏助手 socket 不核对端

    @Test public void m3HelperPeerMustBeRootOrShell() throws Exception {
        assertTrue(com.onesay.assistant.v2.fastscreen.FsProtocol.helperPeerOk(0));
        assertTrue(com.onesay.assistant.v2.fastscreen.FsProtocol.helperPeerOk(2000));
        for (int u : new int[]{1000, 1001, 10123, 10456, -1, 99999}) assertFalse(String.valueOf(u), com.onesay.assistant.v2.fastscreen.FsProtocol.helperPeerOk(u));
        String fs = src("app/src/com/onesay/assistant/v2/fastscreen/FastScreen.java");
        int c = fs.indexOf("private boolean connectOnce()");
        String body = fs.substring(c, fs.indexOf("private void closeQuiet()", c));
        assertTrue(body.contains("getPeerCredentials().getUid()") && body.contains("FsProtocol.helperPeerOk(peer)"));
        assertTrue("核完才当成已连上", body.indexOf("helperPeerOk") < body.indexOf("mSock = s"));
    }

    // ================================================================== M4 全局按键（之前已修，补回归）

    @Test public void m4GlobalKeysStillDenied() {
        com.onesay.plugin.exec.shizuku.ShizukuWhitelist wl = new com.onesay.plugin.exec.shizuku.ShizukuWhitelist("com.onesay.assistant");
        for (String k : new String[]{"5", "6", "79", "27", "231", "KEYCODE_ENDCALL", "KEYCODE_CALL", "KEYCODE_HEADSETHOOK", "KEYCODE_CAMERA", "KEYCODE_VOICE_ASSIST", "006"}) {
            assertFalse(k, wl.check("input keyevent " + k).allowed);
            assertFalse(k, wl.check("input keyevent --longpress " + k).allowed);
        }
        assertTrue(wl.check("input keyevent 4").allowed);
    }

    // ================================================================== M5 多设备（明文、配对竞争、通知按钮直接批准）

    @Test public void m5TailscaleOnlyByDefaultAndConfirmNeedsRealTap() throws Exception {
        assertTrue("只允许 Tailscale 默认开", com.onesay.assistant.v2.phongent.PgApprove.defaultOn("tailscale_only"));
        assertFalse(com.onesay.assistant.v2.phongent.PgApprove.defaultOn("relay_notifications"));
        assertFalse(com.onesay.assistant.v2.phongent.PgApprove.defaultOn("screen_sharing"));
        assertTrue(com.onesay.assistant.v2.phongent.PgApprove.allowed(PcConfirm.ALLOW));
        assertFalse(com.onesay.assistant.v2.phongent.PgApprove.allowed(PcConfirm.DENY));
        assertFalse(com.onesay.assistant.v2.phongent.PgApprove.allowed(PcConfirm.TIMEOUT));
        String d = com.onesay.assistant.v2.phongent.PgApprove.pairDetail("iPad", "100.64.1.2");
        assertTrue(d, d.contains("iPad") && d.contains("100.64.1.2") && d.contains("拒绝"));
        assertFalse(com.onesay.assistant.v2.phongent.PgApprove.pairDetail("iPad", null).contains("来自"));
        String svc = src("app/src/com/onesay/assistant/v2/phongent/PhongentService.java");
        int i = svc.indexOf("public int onStartCommand");
        String on = svc.substring(i, svc.indexOf("return START_STICKY", i));
        assertFalse("通知按钮不再直接批准", on.contains("decidePair(id, true)") || on.contains("decideScreen(true)"));
        assertTrue(on.contains("confirmThenAllow(p, id, false)") && on.contains("confirmThenAllow(p, null, true)"));
        assertTrue(svc.contains("PcConfirm.ask(") && svc.contains("PgApprove.allowed(r)"));
        String st = src("app/src/com/onesay/assistant/v2/phongent/PgSettings.java");
        assertTrue(st.contains("getBoolean(key, defaultOn(key))"));
        String srv = src("app/src/com/onesay/assistant/plug/phongent/core/PgServer.java");
        assertTrue(srv.contains("rq.remote = "));
        String api = src("app/src/com/onesay/assistant/plug/phongent/core/PgApi.java");
        assertTrue(api.contains("mAuth.request(rq.json(), rq.remote)") && api.contains("mAuth.poll(p.substring(6), rq.remote)"));
    }

    // ================================================================== M6 防重放只在内存（明文部分之前已修，补回归）

    static final class MemStore implements PcProtocol.NonceStore {
        String data = "";
        int saves;

        @Override public String load() { return data; }

        @Override public void save(String d) {
            data = d;
            saves++;
        }
    }

    @After public void detach() {
        PcProtocol.attachNonceStore(null);
    }

    @Test public void m6NonceRememberedAcrossSessionsAndRestart() {
        MemStore st = new MemStore();
        PcProtocol.attachNonceStore(st);
        long t = 1_800_000_000_000L;
        assertTrue(new PcProtocol.ReplayGuard().firstSeen("nonce-aaa", t));
        assertFalse("换一条会话重放", new PcProtocol.ReplayGuard().firstSeen("nonce-aaa", t + 1000));
        assertTrue(new PcProtocol.ReplayGuard().firstSeen("nonce-bbb", t + 1000));
        assertFalse("存的是摘要，不存原文", st.data.contains("nonce-aaa"));
        // App 重启：从存的那份接着认
        PcProtocol.attachNonceStore(st);
        assertFalse("重启后重放", new PcProtocol.ReplayGuard().firstSeen("nonce-aaa", t + 5000));
        // 时间窗过了自动清掉（那时报文本身也因为时间戳过期被拒）
        assertTrue(new PcProtocol.ReplayGuard().firstSeen("nonce-aaa", t + 2 * PcProtocol.MAX_SKEW_MS + 2000));
        // 坏数据 / 读不了：从空开始，不抛
        st.data = "zz,12:ab,::,";
        PcProtocol.attachNonceStore(st);
        assertTrue(new PcProtocol.ReplayGuard().firstSeen("nonce-ccc", t));
        // 不接（电脑端 / 测试）：行为和以前一样，只有每条会话自己的内存
        PcProtocol.attachNonceStore(null);
        assertTrue(new PcProtocol.ReplayGuard().firstSeen("nonce-ddd", t));
        assertTrue(new PcProtocol.ReplayGuard().firstSeen("nonce-ddd", t));
    }

    @Test public void m6AndroidAttachesStoreAndPlainPairingStillRefused() throws Exception {
        String a = src("app/src/com/onesay/assistant/v2/pclink/PcRxAndroid.java");
        assertTrue(a.contains("attachNonceStore(app);") && a.contains("PcProtocol.attachNonceStore(new PcProtocol.NonceStore()"));
        String pp = src("app/src/com/onesay/assistant/v2/pclink/PcPairing.java");
        assertTrue("v1 明文配对默认拒（68dfb8e）", pp.contains("PLAIN_REFUSED"));
    }

    // ================================================================== L1 可变 PendingIntent

    static final Pattern PI = Pattern.compile("PendingIntent\\s*\\.\\s*get(Activity|Service|Broadcast|ForegroundService|Activities)\\s*\\(");

    static void scanPi(File dir, List<String> bad) throws Exception {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) {
                scanPi(f, bad);
                continue;
            }
            if (!f.getName().endsWith(".java")) continue;
            String s = new String(Files.readAllBytes(f.toPath()), "UTF-8");
            Matcher m = PI.matcher(s);
            while (m.find()) {
                int j = m.end(), depth = 1;
                while (depth > 0 && j < s.length()) {
                    char c = s.charAt(j++);
                    if (c == '(') depth++;
                    else if (c == ')') depth--;
                }
                String call = s.substring(m.start(), j);
                if (!(call.contains("FLAG_IMMUTABLE") || call.contains("0x04000000"))) bad.add(f.getName() + ": " + call.replaceAll("\\s+", " "));
            }
        }
    }

    @Test public void l1AllPendingIntentsImmutable() throws Exception {
        List<String> bad = new ArrayList<String>();
        scanPi(new File(root(), "app/src"), bad);
        scanPi(new File(root(), "../clock/app/src"), bad);
        scanPi(new File(root(), "../plan/app/src"), bad);
        assertTrue("可变 PendingIntent：" + bad, bad.isEmpty());
        assertTrue("真的扫到了", src("app/src/com/onesay/assistant/v2/s3/S3Scheduler.java").contains("FLAG_IMMUTABLE"));
    }

    // ================================================================== L2 电脑界面页口令（之前已修，补回归）

    @Test public void l2DesktopPageNeedsLaunchKey() throws Exception {
        String ui = src("../desktop/src/main/java/com/onesay/desktop/UiServer.java");
        assertTrue(ui.contains("SEC L2"));
        assertTrue(ui.contains("入口钥匙"));
    }

    // ================================================================== L4 截图写外部缓存（Android 10 及以下）

    @Test public void l4OldAndroidRootSkipsExternalCache() {
        com.onesay.assistant.v2.vision.CapturePlan.Step[] none = {};
        assertEquals("[private+shell, internal+shell]",
                com.onesay.assistant.v2.vision.CapturePlan.forSdk(com.onesay.assistant.v2.vision.CapturePlan.steps(false), 28).toString());
        assertEquals("[private+shell, internal+shell]",
                com.onesay.assistant.v2.vision.CapturePlan.forSdk(com.onesay.assistant.v2.vision.CapturePlan.steps(false), 29).toString());
        assertEquals("Android 11+ 外部缓存目录别的 App 读不了，照旧", "[private+shell, internal+shell, external+shell]",
                com.onesay.assistant.v2.vision.CapturePlan.forSdk(com.onesay.assistant.v2.vision.CapturePlan.steps(false), 30).toString());
        assertEquals("只有 Shizuku：外部缓存是唯一能截的地方", "[private, external+shell]",
                com.onesay.assistant.v2.vision.CapturePlan.forSdk(com.onesay.assistant.v2.vision.CapturePlan.steps(true), 28).toString());
        assertEquals(0, none.length);
    }

    @Test public void l4ExternalShotNameRandomAndWhitelistSafe() throws Exception {
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (int i = 0; i < 50; i++) {
            String n = com.onesay.assistant.v2.vision.CapturePlan.externalShotName(new java.security.SecureRandom());
            assertTrue(n, n.matches("vs_[0-9a-f]{16}\\.png"));
            seen.add(n);
        }
        assertEquals(50, seen.size());
        // 外部缓存目录里的随机名过得了 Shizuku 截图白名单
        com.onesay.plugin.exec.shizuku.ShizukuWhitelist wl = new com.onesay.plugin.exec.shizuku.ShizukuWhitelist("com.onesay.assistant");
        String n = com.onesay.assistant.v2.vision.CapturePlan.externalShotName(null);
        assertTrue(wl.check("screencap -p /storage/emulated/0/Android/data/com.onesay.assistant/cache/" + n).allowed);
        String v = src("app/src/com/onesay/assistant/v2/vision/ScreenVisionEngine.java");
        assertTrue(v.contains("CapturePlan.forSdk(CapturePlan.steps(shzOnly), android.os.Build.VERSION.SDK_INT)"));
        assertTrue(v.contains("CapturePlan.externalShotName(SHOT_RANDOM)"));
    }

    // ================================================================== L5 打电话接受 MMI 码（执行层再核一次）

    @Test public void l5ExecGuardRefusesMmiAtExecutionLayer() throws Exception {
        String[] bad = {"**21*13800138000#", "*#06#", "#31#10086", "10086,1", "10086;2", "1380013800p1", "张三", "12"};
        for (String n : bad)
            assertNotNull(n, com.onesay.assistant.v2.executor.CommsRisk.execGuard("phone.call", new JSONObject().put("number", n)));
        assertNotNull(com.onesay.assistant.v2.executor.CommsRisk.execGuard("sms.send", new JSONObject().put("number", "*100#")));
        for (String n : new String[]{"10086", "138 0013 8000", "+86 138-0013-8000", "110"})
            assertNull(n, com.onesay.assistant.v2.executor.CommsRisk.execGuard("phone.call", new JSONObject().put("number", n)));
        assertNull(com.onesay.assistant.v2.executor.CommsRisk.execGuard("app.open", new JSONObject().put("number", "*#06#")));
        String ue = src("app/src/com/onesay/assistant/v2/executor/UnifiedExecutor.java");
        int g = ue.indexOf("CommsRisk.execGuard(capability.sign, args)");
        assertTrue(g > 0);
        assertTrue("填模板之前就拦", g < ue.indexOf("filledCmd = ShellCommand.fill(capability.cmdTemplate, slots)"));
    }

    // ================================================================== L6 OneSay 声明了时钟的权限（可被抢注挡住安装）

    @Test public void l6OneSayOnlyRequestsClockPermission() throws Exception {
        String mf = src("app/AndroidManifest.xml").replaceAll("(?s)<!--.*?-->", "");
        assertTrue(mf.contains("<uses-permission android:name=\"com.onesay.clock.permission.ALARM_CONTROL\" />"));
        Matcher p = Pattern.compile("(?s)<permission\\s+android:name=\"([^\"]+)\"").matcher(mf);
        List<String> declared = new ArrayList<String>();
        while (p.find()) declared.add(p.group(1));
        assertFalse("不再声明时钟的权限", declared.contains("com.onesay.clock.permission.ALARM_CONTROL"));
        for (String d : declared) assertTrue("只声明自己名下的权限：" + d, d.startsWith("com.onesay.assistant."));
        String clock = src("../clock/app/AndroidManifest.xml");
        assertTrue("时钟自己照旧声明", clock.contains("android:name=\"com.onesay.clock.permission.ALARM_CONTROL\"") && clock.contains("protectionLevel=\"signature\""));
    }
}
