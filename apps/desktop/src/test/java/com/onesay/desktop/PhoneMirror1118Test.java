package com.onesay.desktop;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

/** phone-mirror M1（2026-11-18）：adb 输出解析、设备状态机、拖文件路由、报错翻译、scrcpy 参数、控制器（假 adb / 假窗口）、上传接口、安全边界。 */
public class PhoneMirror1118Test {

    // ------------------------------------------------------------------ adb devices -l

    static final String DEVICES = "* daemon not running; starting now at tcp:5037\n* daemon started successfully\n"
            + "List of devices attached\n"
            + "M21PRO0001             device usb:1-1 product:meizu_21Pro_CN model:MEIZU_21_PRO device:meizu21pro transport_id:3\n"
            + "ce12345678             unauthorized usb:1-2 transport_id:4\n"
            + "0123ABCD               offline usb:2-1 transport_id:5\n"
            + "192.168.1.5:5555       device product:x model:Pixel_8 device:shiba transport_id:6\n"
            + "emulator-5554          device product:sdk_gphone64 model:sdk transport_id:1\n"
            + "127.0.0.1:7555         device product:mumu model:MuMu transport_id:7\n"
            + "abc;rm_-rf             device usb:1-3\n"
            + "LINUXDEV01             no permissions (missing udev rules? user is in the plugdev group); see [http://developer.android.com/tools/device.html] usb:1-4 transport_id:8\n"
            + "\n";

    @Test public void parsesDevicesWithStatesAndProps() {
        List<Adb.Device> l = Adb.parseDevices(DEVICES);
        Map<String, Adb.Device> m = new TreeMap<>();
        for (Adb.Device d : l) m.put(d.serial, d);
        assertEquals(7, l.size());
        assertFalse("带 ; 的序列号丢弃", m.containsKey("abc;rm_-rf"));
        assertEquals("device", m.get("M21PRO0001").state);
        assertEquals("MEIZU 21 PRO", m.get("M21PRO0001").model());
        assertEquals("usb", m.get("M21PRO0001").transport());
        assertEquals("unauthorized", m.get("ce12345678").state);
        assertEquals("offline", m.get("0123ABCD").state);
        assertEquals("tcp", m.get("192.168.1.5:5555").transport());
        assertEquals("emulator", m.get("emulator-5554").transport());
        assertEquals("emulator", m.get("127.0.0.1:7555").transport());
        assertEquals("no permissions", m.get("LINUXDEV01").state);
        assertEquals("1-4", m.get("LINUXDEV01").props.get("usb"));
    }

    @Test public void parsesEmptyAndGarbage() {
        assertTrue(Adb.parseDevices(null).isEmpty());
        assertTrue(Adb.parseDevices("List of devices attached\n\n").isEmpty());
        assertTrue(Adb.parseDevices("adb server version (41) doesn't match this client (39); killing...\n").isEmpty());
        assertTrue(Adb.parseDevices("error: protocol fault\n").isEmpty());
        assertEquals(1, Adb.parseDevices("X1\tdevice\r\n").size()); // 不带 -l 的 tab 格式 + CRLF
    }

    @Test public void validatesSerialsAndPackages() {
        assertTrue(Adb.validSerial("192.168.1.5:5555"));
        assertTrue(Adb.validSerial("adb-R58M1234-abc._adb-tls-connect._tcp"));
        assertFalse(Adb.validSerial("a b"));
        assertFalse(Adb.validSerial("a&b"));
        assertFalse(Adb.validSerial("$(x)"));
        assertFalse(Adb.validSerial(""));
        assertTrue(Adb.validPackage("com.tencent.mm"));
        assertFalse(Adb.validPackage("com.x;reboot"));
        assertFalse(Adb.validPackage("nodots"));
        assertFalse(Adb.validPackage("--user 0"));
    }

    @Test public void parsesGetpropAndInfo() {
        Map<String, String> p = Adb.parseGetprop("[ro.product.brand]: [meizu]\n[ro.product.model]: [MEIZU 21 PRO]\n[ro.build.version.sdk]: [36]\n"
                + "[ro.build.version.release]: [16]\n[ro.build.display.id]: [Flyme 12.7.0.0A]\n[weird line\n");
        PhoneDevices.Info i = PhoneDevices.Info.of(p);
        assertEquals(36, i.sdk);
        assertTrue(i.flyme());
        assertTrue(i.audio());
        assertTrue(i.appWindows());
        PhoneDevices.Info note8 = PhoneDevices.Info.of(Adb.parseGetprop("[ro.product.brand]: [other]\n[ro.build.version.sdk]: [26]\n[ro.build.version.release]: [8.0.0]\n"));
        assertFalse(note8.flyme());
        assertFalse(note8.audio());
        assertFalse(note8.appWindows());
        assertEquals(0, PhoneDevices.Info.of(Map.of("ro.build.version.sdk", "x")).sdk);
        assertTrue(PhoneDevices.Info.of(Map.of("ro.product.brand", "Meizu")).flyme());
    }

    @Test public void parsesPackages() {
        List<String> l = Adb.parsePackages("package:com.tencent.mm\r\npackage:com.ss.android.ugc.aweme\npackage:/data/app/~~x==/com.old.app-1/base.apk=com.old.app\n"
                + "package:com.tencent.mm\npackage:bad;pkg\nWARNING: linker\n\n");
        assertEquals(List.of("com.old.app", "com.ss.android.ugc.aweme", "com.tencent.mm"), l);
        assertTrue(Adb.parsePackages(null).isEmpty());
    }

    // ------------------------------------------------------------------ 安装 / 推送结果与报错翻译

    @Test public void installSuccessAndMeizuRestricted() {
        assertTrue(Adb.parseInstall("Performing Streamed Install\nSuccess\n", 0).ok);
        Adb.Outcome o = Adb.parseInstall("Performing Streamed Install\nadb: failed to install C:\\x\\a.apk: Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]\n", 1);
        assertFalse(o.ok);
        assertEquals("INSTALL_FAILED_USER_RESTRICTED", o.code);
        assertTrue(o.zh.contains("USB 安装"));
        assertTrue(o.zh.contains("开发者选项"));
        // 有 Success 也有 Failure（多包时）不算成功
        assertFalse(Adb.parseInstall("Success\nFailure [INSTALL_FAILED_ALREADY_EXISTS]\n", 1).ok);
    }

    @Test public void installErrorMapping() {
        String[][] cases = {
                {"Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE: Package com.x signatures do not match]", "INSTALL_FAILED_UPDATE_INCOMPATIBLE", "签名"},
                {"Failure [INSTALL_FAILED_VERSION_DOWNGRADE]", "INSTALL_FAILED_VERSION_DOWNGRADE", "降级"},
                {"Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]", "INSTALL_FAILED_INSUFFICIENT_STORAGE", "空间"},
                {"Failure [INSTALL_FAILED_OLDER_SDK: Requires newer sdk version #34]", "INSTALL_FAILED_OLDER_SDK", "安卓版本"},
                {"Failure [INSTALL_FAILED_NO_MATCHING_ABIS: ...]", "INSTALL_FAILED_NO_MATCHING_ABIS", "架构"},
                {"Failure [INSTALL_PARSE_FAILED_NO_CERTIFICATES: ...]", "INSTALL_PARSE_FAILED_NO_CERTIFICATES", "签名"},
                {"Failure [INSTALL_FAILED_ABORTED: User rejected permissions]", "INSTALL_FAILED_ABORTED", "取消"},
                {"Failure [INSTALL_FAILED_VERIFICATION_FAILURE]", "INSTALL_FAILED_VERIFICATION_FAILURE", "安全检测"},
                {"Failure [INSTALL_FAILED_FOO_BAR]", "INSTALL_FAILED_FOO_BAR", "INSTALL_FAILED_FOO_BAR"},
                {"adb: device unauthorized.\nThis adb server's $ADB_VENDOR_KEYS is not set", "UNAUTHORIZED", "允许 USB 调试"},
                {"adb: error: device offline", "OFFLINE", "就绪"},
                {"adb: error: device 'X' not found", "DISCONNECTED", "断开"},
        };
        for (String[] c : cases) {
            Adb.Outcome o = Adb.parseInstall(c[0], 1);
            assertFalse(c[0], o.ok);
            assertEquals(c[0], c[1], o.code);
            assertTrue(c[0] + " → " + o.zh, o.zh.contains(c[2]));
        }
        assertEquals("TIMEOUT", Adb.parseInstall("", 124).code);
    }

    @Test public void pushOutcome() {
        assertTrue(Adb.parsePush("C:\\t\\a.bin: 1 file pushed, 0 skipped. 31.2 MB/s (1234 bytes in 0.001s)\n", 0).ok);
        Adb.Outcome o = Adb.parsePush("adb: error: failed to copy 'a' to '/sdcard/Download/a': remote No space left on device\n", 1);
        assertEquals("NO_SPACE", o.code);
        assertTrue(o.zh.contains("空间"));
        assertEquals("PERMISSION", Adb.parsePush("remote secure_mkdirs failed: Permission denied", 1).code);
        assertEquals("CANNOT_CREATE", Adb.parsePush("adb: error: remote couldn't create file: Is a directory", 1).code);
        assertEquals("DISCONNECTED", Adb.parsePush("adb: error: no devices/emulators found", 1).code);
        assertFalse(Adb.parsePush("adb: error: something", 0).ok);
    }

    @Test public void scrcpyErrorMapping() {
        assertNull(Adb.scrcpyZh("INFO: Renderer: direct3d\n", 0, false, false));
        assertTrue(Adb.scrcpyZh("ERROR: Device is unauthorized", 1, false, false).contains("允许 USB 调试"));
        assertTrue(Adb.scrcpyZh("ERROR: Could not find any ADB device", 1, false, false).contains("没找到手机"));
        assertTrue(Adb.scrcpyZh("ERROR: Could not create virtual display: not supported", 1, false, true).contains("安卓 10"));
        String u = Adb.scrcpyZh("ERROR: Could not open /dev/uhid: Permission denied (UHID)", 1, true, false);
        assertTrue(u, u.contains("普通键盘"));
        assertTrue(u.contains("Ctrl+V"));
        // 不是 uhid 模式就不归到 uhid
        assertFalse(Adb.scrcpyZh("ERROR: uhid failed", 1, false, false).contains("普通键盘"));
        assertTrue(Adb.scrcpyZh("x", 3, false, false).contains("退出码 3"));
        assertTrue(Adb.scrcpyZh(null, 2, false, false).contains("2"));
    }

    @Test public void shQuote() {
        assertEquals("'a b'", Adb.shQuote("a b"));
        assertEquals("'a'\\''b'", Adb.shQuote("a'b"));
    }

    // ------------------------------------------------------------------ 拖文件路由

    @Test public void dropRouting() {
        assertEquals(DropRoute.Kind.INSTALL_APK, DropRoute.route("微信.apk", 10).kind);
        assertEquals(DropRoute.Kind.INSTALL_APK, DropRoute.route("APP.APK", 10).kind);
        assertEquals(DropRoute.Kind.PUSH_FILE, DropRoute.route("照片.jpg", 10).kind);
        assertEquals(DropRoute.Kind.PUSH_FILE, DropRoute.route("notes", 10).kind);
        assertEquals(DropRoute.Kind.PUSH_FILE, DropRoute.route("a.apk.txt", 10).kind);
        assertEquals(DropRoute.Kind.REJECT, DropRoute.route("game.xapk", 10).kind);
        assertEquals(DropRoute.Kind.REJECT, DropRoute.route("bundle.apks", 10).kind);
        assertEquals(DropRoute.Kind.REJECT, DropRoute.route("x.aab", 10).kind);
        assertEquals(DropRoute.Kind.REJECT, DropRoute.route("empty.txt", 0).kind);
        assertEquals(DropRoute.Kind.REJECT, DropRoute.route("big.mkv", DropRoute.MAX_BYTES + 1).kind);
        assertEquals(DropRoute.Kind.REJECT, DropRoute.route("..", 5).kind);
        assertEquals(DropRoute.Kind.REJECT, DropRoute.route("", 5).kind);
        assertEquals(DropRoute.Kind.REJECT, DropRoute.route(null, 5).kind);
        assertEquals(DropRoute.Kind.PUSH_FILE, DropRoute.route("unknown-size.bin", -1).kind); // 大小未知按流实收
    }

    @Test public void safeNames() {
        assertEquals("passwd", DropRoute.safeName("../../etc/passwd"));
        assertEquals("evil.txt", DropRoute.safeName("C:\\Users\\me\\evil.txt"));
        assertEquals("a_b_.txt", DropRoute.safeName("a\"b?.txt"));
        assertEquals("ab.txt", DropRoute.safeName("a\u0000\nb.txt"));
        assertEquals("cbatxt.exe", DropRoute.safeName("cba\u202Etxt.exe"));
        assertEquals("hidden", DropRoute.safeName(".hidden"));
        assertEquals("_-rf", DropRoute.safeName("-rf"));
        assertEquals("x_ y", DropRoute.safeName("x; y"));
        assertEquals("_(id)", DropRoute.safeName("$(id)"));
        assertNull(DropRoute.safeName("..."));
        assertNull(DropRoute.safeName("dir/"));
        String longName = "长".repeat(150) + ".jpg";
        String s = DropRoute.safeName(longName);
        assertTrue(s.endsWith(".jpg"));
        assertTrue(s.getBytes(StandardCharsets.UTF_8).length <= 200);
    }

    @Test public void uniqueNames() {
        assertEquals("a.txt", DropRoute.unique("a.txt", List.of("b.txt")));
        assertEquals("a (1).txt", DropRoute.unique("a.txt", List.of("A.TXT")));
        assertEquals("a (2).txt", DropRoute.unique("a.txt", List.of("a.txt", "a (1).txt")));
        assertEquals("README (1)", DropRoute.unique("README", List.of("README")));
    }

    // ------------------------------------------------------------------ 状态机

    static Adb.Device dev(String serial, String state) {
        return Adb.parseDevices(serial + " " + state + " usb:1-1 model:Phone_X\n").get(0);
    }

    static List<String> kinds(List<PhoneDevices.Event> ev) {
        List<String> l = new ArrayList<>();
        for (PhoneDevices.Event e : ev) l.add(e.kind);
        return l;
    }

    @Test public void stateMachinePlugAuthorizeMirrorUnplug() {
        PhoneDevices d = new PhoneDevices();
        assertEquals(List.of("added", "needs_auth"), kinds(d.update(List.of(dev("S1", "unauthorized")), 1)));
        assertEquals(PhoneDevices.State.UNAUTHORIZED, d.get("S1").state());
        assertTrue(d.canMirror("S1").contains("允许 USB 调试"));
        assertTrue(kinds(d.update(List.of(dev("S1", "unauthorized")), 2)).isEmpty()); // 没变化不重复提示
        assertEquals(List.of("authorized"), kinds(d.update(List.of(dev("S1", "device")), 3)));
        assertEquals(PhoneDevices.State.AVAILABLE, d.get("S1").state());
        assertNull(d.canMirror("S1"));
        assertEquals(List.of("S1"), d.needProbe());
        d.setInfo("S1", new PhoneDevices.Info("meizu", "MEIZU 21 PRO", 36, "16", "Flyme"));
        assertTrue(d.needProbe().isEmpty());
        assertEquals("MEIZU 21 PRO", d.get("S1").name());
        d.mirrorStarted("S1");
        assertEquals(PhoneDevices.State.MIRRORING, d.get("S1").state());
        assertEquals("已经在投屏了", d.canMirror("S1"));
        assertTrue(kinds(d.update(List.of(dev("S1", "device")), 4)).isEmpty());
        d.mirrorStopped("S1", "和手机的连接中断了");
        assertEquals(PhoneDevices.State.AVAILABLE, d.get("S1").state());
        assertEquals("和手机的连接中断了", d.view().get(0).get("note"));
        d.mirrorStarted("S1");
        assertEquals(List.of("removed"), kinds(d.update(List.of(), 5)));
        assertNull(d.get("S1"));
        assertEquals("这台手机已经拔掉了", d.canMirror("S1"));
    }

    @Test public void stateMachineOfflineAndOther() {
        PhoneDevices d = new PhoneDevices();
        d.update(List.of(dev("S2", "offline")), 1);
        assertEquals(PhoneDevices.State.CONNECTING, d.get("S2").state());
        assertEquals(List.of("authorized"), kinds(d.update(List.of(dev("S2", "device")), 2)));
        d.mirrorStarted("S2");
        d.update(List.of(dev("S2", "offline")), 3);
        assertEquals("掉线时投屏标记清掉", PhoneDevices.State.CONNECTING, d.get("S2").state());
        d.update(List.of(dev("S2", "recovery")), 4);
        assertEquals(PhoneDevices.State.OTHER, d.get("S2").state());
        assertNotNull(d.canMirror("S2"));
        d.update(List.of(dev("S3", "no permissions")), 5);
        assertEquals(PhoneDevices.State.NO_PERMISSION, d.get("S3").state());
        assertNull("S2 拔掉了", d.get("S2"));
    }

    @Test public void appWindowLimitsAndDedupe() {
        PhoneDevices d = new PhoneDevices();
        d.update(List.of(dev("S1", "device")), 1);
        d.setInfo("S1", new PhoneDevices.Info("meizu", "M", 36, "16", "Flyme"));
        for (String p : List.of("com.a.a", "com.b.b", "com.c.c")) {
            assertNull(d.canOpenApp("S1", p));
            assertTrue(d.appOpened("S1", p));
        }
        assertTrue(d.canOpenApp("S1", "com.d.d").contains("最多同时开 3 个"));
        assertTrue(d.canOpenApp("S1", "com.a.a").contains("已经开着"));
        assertEquals("包名不对", d.canOpenApp("S1", "x;y"));
        d.appClosed("S1", "com.a.a", null);
        assertNull(d.canOpenApp("S1", "com.d.d"));
        d.update(List.of(dev("S1", "offline")), 2);
        assertTrue("掉线时 App 窗口清空", d.get("S1").apps.isEmpty());
        d.update(List.of(dev("S1", "device")), 3);
        d.setInfo("S1", new PhoneDevices.Info("other", "N", 26, "8.0.0", ""));
        assertTrue(d.canOpenApp("S1", "com.a.a").contains("安卓 10"));
    }

    // ------------------------------------------------------------------ scrcpy 参数

    @Test public void scrcpyArgs() {
        List<String> m = ScrcpyArgs.mirror("scrcpy.exe", "M21", "MEIZU \"21\"\nPRO", false, true);
        assertEquals(List.of("scrcpy.exe", "--serial=M21", "--window-title=OneSay 投屏 · MEIZU 21PRO"), m);
        List<String> m2 = ScrcpyArgs.mirror("scrcpy.exe", "M21", "x", true, false);
        assertTrue(m2.contains("--keyboard=uhid"));
        assertTrue(m2.contains("--no-audio"));
        List<String> a = ScrcpyArgs.app("scrcpy.exe", "M21", "com.tencent.mm", true);
        assertTrue(a.contains("--new-display=" + ScrcpyArgs.APP_DISPLAY));
        assertTrue(a.contains("--start-app=com.tencent.mm"));
        assertTrue(a.contains("--display-ime-policy=local"));
        assertTrue(a.contains("--no-vd-destroy-content"));
        assertTrue(a.contains("--no-audio"));
        assertTrue(a.contains("--keyboard=uhid"));
        for (String x : a.subList(1, a.size())) assertTrue("只用白名单参数：" + x, x.startsWith("--"));
        try { ScrcpyArgs.mirror("s", "a b", "x", false, false); fail(); } catch (IllegalArgumentException expected) { }
        try { ScrcpyArgs.app("s", "S", "--help", false); fail(); } catch (IllegalArgumentException expected) { }
        try { ScrcpyArgs.app("s", "S", "+com.x.y", false); fail(); } catch (IllegalArgumentException expected) { } // + 前缀（强停）不放进来
        assertEquals(80, ScrcpyArgs.title("长".repeat(200)).length());
    }

    // ------------------------------------------------------------------ 控制器（假 adb / 假窗口）

    static final class FakeAdb implements Adb.Runner {
        final List<List<String>> calls = Collections.synchronizedList(new ArrayList<>());
        volatile String devices = "List of devices attached\n";
        volatile String install = "Success\n";
        volatile int installExit = 0;
        volatile String ls = "a.txt\nb.jpg\n";

        @Override public Adb.Run run(List<String> argv, long t) {
            calls.add(argv);
            String j = String.join(" ", argv);
            if (j.endsWith("devices -l")) return new Adb.Run(0, devices);
            if (j.contains(" shell getprop")) return new Adb.Run(0, "[ro.product.brand]: [meizu]\n[ro.product.model]: [MEIZU 21 PRO]\n[ro.build.version.sdk]: [36]\n[ro.build.version.release]: [16]\n[ro.build.display.id]: [Flyme 12.7]\n");
            if (j.contains(" install ")) return new Adb.Run(installExit, install);
            if (j.contains(" shell ls ")) return new Adb.Run(0, ls);
            if (j.contains(" push ")) return new Adb.Run(0, "1 file pushed, 0 skipped.\n");
            if (j.contains("pm list packages -3")) return new Adb.Run(0, "package:com.tencent.mm\npackage:com.x.y\n");
            return new Adb.Run(0, "");
        }

        long count(String needle) {
            synchronized (calls) { return calls.stream().filter(c -> String.join(" ", c).contains(needle)).count(); }
        }
    }

    static final class FakeLauncher implements PhoneMirror.Launcher {
        final List<List<String>> started = Collections.synchronizedList(new ArrayList<>());
        final List<Map<String, String>> envs = Collections.synchronizedList(new ArrayList<>());
        final CountDownLatch release = new CountDownLatch(1);
        volatile String out = "";
        volatile int exit = 0;
        volatile boolean destroyed;

        @Override public PhoneMirror.Proc start(List<String> argv, Map<String, String> env) {
            started.add(argv);
            envs.add(env);
            return new PhoneMirror.Proc() {
                @Override public int await(StringBuilder o) throws InterruptedException {
                    release.await(10, TimeUnit.SECONDS);
                    o.append(out);
                    return exit;
                }

                @Override public void destroy() { destroyed = true; release.countDown(); }
            };
        }
    }

    static PhoneMirror controller(Path tmp, FakeAdb a, FakeLauncher l) throws IOException {
        Path sc = Files.writeString(tmp.resolve("scrcpy"), "x"), adb = Files.writeString(tmp.resolve("adb"), "x");
        MirrorTools t = new MirrorTools(tmp.resolve("tools"), false, "amd64", MirrorTools.RELEASES, sc.toString(), adb.toString());
        PhoneMirror m = new PhoneMirror(t, tmp.resolve("drop"), null);
        m.adb = a;
        m.launcher = l;
        return m;
    }

    @Test public void pollNeverAutoStartsMirroring() throws Exception {
        Path tmp = Files.createTempDirectory("pm");
        FakeAdb a = new FakeAdb();
        FakeLauncher l = new FakeLauncher();
        PhoneMirror m = controller(tmp, a, l);
        List<String> events = new ArrayList<>();
        m.onEvent = e -> events.add(e.kind);
        a.devices = "List of devices attached\nM21 unauthorized usb:1-1\n";
        m.poll(1);
        a.devices = "List of devices attached\nM21 device usb:1-1 model:MEIZU_21_PRO\n";
        m.poll(2);
        m.poll(3);
        assertEquals(List.of("added", "needs_auth", "authorized"), events);
        assertTrue("插上 / 授权都不自动开投屏", l.started.isEmpty());
        assertEquals(1, a.count("shell getprop")); // 只探测一次
        Map<String, Object> v = m.devices.view().get(0);
        assertEquals("available", v.get("state"));
        assertEquals(Boolean.TRUE, v.get("flyme"));
        assertEquals(Boolean.TRUE, v.get("can_mirror"));
    }

    @Test public void userClickStartsMirrorAndUnplugStopsIt() throws Exception {
        Path tmp = Files.createTempDirectory("pm");
        FakeAdb a = new FakeAdb();
        FakeLauncher l = new FakeLauncher();
        PhoneMirror m = controller(tmp, a, l);
        m.uhid = () -> true;
        assertEquals("这台手机已经拔掉了", m.mirror("M21"));
        a.devices = "M21 device usb:1-1 model:MEIZU_21_PRO\n";
        m.poll(1);
        assertNull(m.mirror("M21"));
        assertEquals(1, l.started.size());
        List<String> argv = l.started.get(0);
        assertTrue(argv.contains("--serial=M21"));
        assertTrue(argv.contains("--keyboard=uhid"));
        assertFalse("安卓 16 转发声音", argv.contains("--no-audio"));
        assertEquals(tmp.resolve("adb").toString(), l.envs.get(0).get("ADB"));
        assertEquals(PhoneDevices.State.MIRRORING, m.devices.get("M21").state());
        assertEquals("已经在投屏了", m.mirror("M21"));
        a.devices = "List of devices attached\n";
        m.poll(2);
        assertTrue("拔线时结束窗口进程", l.destroyed);
        assertNull(m.devices.get("M21"));
    }

    @Test public void mirrorExitWithErrorShowsNote() throws Exception {
        Path tmp = Files.createTempDirectory("pm");
        FakeAdb a = new FakeAdb();
        FakeLauncher l = new FakeLauncher();
        PhoneMirror m = controller(tmp, a, l);
        a.devices = "M21 device usb:1-1\n";
        m.poll(1);
        l.out = "ERROR: Device is unauthorized\n";
        l.exit = 1;
        assertNull(m.mirror("M21"));
        l.release.countDown();
        for (int i = 0; i < 100 && m.devices.get("M21").state() == PhoneDevices.State.MIRRORING; i++) Thread.sleep(20);
        assertEquals(PhoneDevices.State.AVAILABLE, m.devices.get("M21").state());
        assertTrue(String.valueOf(m.devices.view().get(0).get("note")).contains("允许 USB 调试"));
    }

    @Test public void appWindowsThroughController() throws Exception {
        Path tmp = Files.createTempDirectory("pm");
        FakeAdb a = new FakeAdb();
        FakeLauncher l = new FakeLauncher();
        PhoneMirror m = controller(tmp, a, l);
        a.devices = "M21 device usb:1-1\n";
        m.poll(1);
        assertEquals(List.of("com.tencent.mm", "com.x.y"), m.apps("M21"));
        assertNull(m.openApp("M21", "com.tencent.mm"));
        assertTrue(m.openApp("M21", "com.tencent.mm").contains("已经开着"));
        assertTrue(l.started.get(0).contains("--start-app=com.tencent.mm"));
        assertTrue(m.close("M21", "com.tencent.mm"));
        assertFalse(m.close("M21", "com.none.x"));
    }

    @Test public void dropApkInstallsAndOtherFilesPush() throws Exception {
        Path tmp = Files.createTempDirectory("pm");
        FakeAdb a = new FakeAdb();
        PhoneMirror m = controller(tmp, a, new FakeLauncher());
        a.devices = "M21 device usb:1-1\n";
        m.poll(1);
        byte[] apk = "PK fake apk".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> r = m.drop("M21", "微信.apk", apk.length, new ByteArrayInputStream(apk));
        assertEquals(r.toString(), Boolean.TRUE, r.get("ok"));
        assertEquals(1, a.count(" install -r "));
        // 推文件：和已有的 a.txt 重名 → a (1).txt；临时文件删掉
        byte[] txt = "hello".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> p = m.drop("M21", "..\\..\\a.txt", txt.length, new ByteArrayInputStream(txt));
        assertEquals(p.toString(), Boolean.TRUE, p.get("ok"));
        assertEquals("/sdcard/Download/a (1).txt", p.get("remote"));
        List<String> push = null;
        synchronized (a.calls) { for (List<String> c : a.calls) if (c.contains("push")) push = c; }
        assertEquals("/sdcard/Download/a (1).txt", push.get(push.size() - 1));
        assertEquals(1, a.count("MEDIA_SCANNER_SCAN_FILE"));
        try (var s = Files.list(tmp.resolve("drop"))) { assertEquals("临时文件都删掉了", 0, s.count()); }
        // 拆包拒绝、大小对不上拒绝
        assertEquals(Boolean.FALSE, m.drop("M21", "x.xapk", 3, new ByteArrayInputStream(new byte[3])).get("ok"));
        assertEquals(Boolean.FALSE, m.drop("M21", "x.txt", 10, new ByteArrayInputStream(new byte[3])).get("ok"));
        assertEquals(Boolean.FALSE, m.drop("NOPE", "x.txt", 3, new ByteArrayInputStream(new byte[3])).get("ok"));
    }

    @Test public void meizuRestrictedOffersPushInstead() throws Exception {
        Path tmp = Files.createTempDirectory("pm");
        FakeAdb a = new FakeAdb();
        PhoneMirror m = controller(tmp, a, new FakeLauncher());
        a.devices = "M21 device usb:1-1\n";
        m.poll(1);
        a.install = "Performing Streamed Install\nadb: failed to install /t/x.apk: Failure [INSTALL_FAILED_USER_RESTRICTED: Install canceled by user]\n";
        a.installExit = 1;
        byte[] apk = new byte[] {1, 2, 3};
        Map<String, Object> r = m.drop("M21", "app.apk", 3, new ByteArrayInputStream(apk));
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertEquals("INSTALL_FAILED_USER_RESTRICTED", r.get("code"));
        assertEquals(Boolean.TRUE, r.get("flyme"));
        assertTrue(String.valueOf(r.get("text")).contains("USB 安装"));
        String id = String.valueOf(r.get("pending"));
        Map<String, Object> p = m.pushInstead(id);
        assertEquals(p.toString(), Boolean.TRUE, p.get("ok"));
        assertEquals("/sdcard/Download/app.apk", p.get("remote"));
        assertEquals(Boolean.FALSE, m.pushInstead(id).get("ok")); // 只能用一次
        try (var s = Files.list(tmp.resolve("drop"))) { assertEquals(0, s.count()); }
    }

    @Test public void pendingExpires() throws Exception {
        Path tmp = Files.createTempDirectory("pm");
        PhoneMirror m = controller(tmp, new FakeAdb(), new FakeLauncher());
        Files.createDirectories(tmp.resolve("drop"));
        Path f = Files.writeString(tmp.resolve("drop/x.apk"), "x");
        m.pending.put("old", new PhoneMirror.Pending("S", "x.apk", f, 0));
        m.sweep(11 * 60_000);
        assertFalse(Files.exists(f));
        assertTrue(m.pending.isEmpty());
    }

    @Test public void toolsMissingMeansNothingRuns() throws Exception {
        Path tmp = Files.createTempDirectory("pm");
        MirrorTools t = new MirrorTools(tmp, true, "amd64", MirrorTools.RELEASES, "", "");
        FakeAdb a = new FakeAdb();
        FakeLauncher l = new FakeLauncher();
        PhoneMirror m = new PhoneMirror(t, tmp.resolve("drop"), null);
        m.adb = a;
        m.launcher = l;
        assertFalse(t.installed());
        assertEquals("还没安装投屏组件", t.verify());
        m.devices.update(List.of(dev("S1", "device")), 1);
        assertEquals("还没安装投屏组件", m.mirror("S1"));
        assertEquals(Boolean.FALSE, m.drop("S1", "a.txt", 1, new ByteArrayInputStream(new byte[1])).get("ok"));
        try { m.apps("S1"); fail(); } catch (IOException expected) { }
        assertTrue(a.calls.isEmpty());
        assertTrue(l.started.isEmpty());
        m.start(); // 没装组件：后台线程空转，不起 adb
        Thread.sleep(300);
        m.shutdown();
        assertTrue(a.calls.isEmpty());
    }

    // ------------------------------------------------------------------ 组件（scrcpy 官方包）

    static final String SUMS_V501 = "764eb6f79811d5211fe9df341120882ba9994c7a61b897d7bf3fb662e53bc536  scrcpy-server-v5.0.1\n"
            + "9f969d30cc574816077edecda65719c1c70b0aee1df1ba5fa00779ac07b8fd6e  scrcpy-linux-x86_64-v5.0.1.tar.gz\n"
            + "0cb65ebf1bce892fa4d1502d699af0c7a1a7f24d7febcb9ef72894e3f23fc2ad  scrcpy-win32-v5.0.1.zip\n"
            + "b12a2c4ee8be317422451fc7dcf8ee20a71b5ea7ef9ad73ddd825a25316227a5  scrcpy-win64-v5.0.1.zip\n"
            + "16c3fc2068df64946f670e3504b4752073ebae47a416a45b46e79a5ab19c689b  scrcpy-winarm64-v5.0.1.zip\n";

    @Test public void pinnedHashesMatchOfficialSums() {
        assertEquals("scrcpy-win64-v5.0.1.zip", MirrorTools.asset("amd64"));
        assertEquals("scrcpy-winarm64-v5.0.1.zip", MirrorTools.asset("aarch64"));
        for (Map.Entry<String, String> e : MirrorTools.PINNED.entrySet()) assertEquals(e.getKey(), MirrorTools.sumFor(SUMS_V501, e.getKey()), e.getValue());
        assertNull(MirrorTools.sumFor(SUMS_V501, "scrcpy-win64-v9.zip"));
        assertTrue(MirrorTools.RELEASES.startsWith("https://github.com/Genymobile/scrcpy/"));
    }

    @Test public void unzipOnlyTopLevelSafeFiles() throws Exception {
        Path tmp = Files.createTempDirectory("pmz");
        Path zip = tmp.resolve("s.zip");
        try (ZipOutputStream z = new ZipOutputStream(Files.newOutputStream(zip))) {
            for (String n : List.of("scrcpy-win64-v5.0.1/scrcpy.exe", "scrcpy-win64-v5.0.1/adb.exe", "scrcpy-win64-v5.0.1/scrcpy-server",
                    "scrcpy-win64-v5.0.1/LICENSE.txt", "scrcpy-win64-v5.0.1/open_a_terminal_here.bat", "scrcpy-win64-v5.0.1/scrcpy-noconsole.vbs",
                    "scrcpy-win64-v5.0.1/sub/deep.dll", "../evil.dll", "scrcpy-win64-v5.0.1/bad name.dll")) {
                z.putNextEntry(new ZipEntry(n));
                z.write(n.getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        Path out = Files.createDirectories(tmp.resolve("o"));
        Map<String, Path> got = MirrorTools.unzip(zip, out);
        assertEquals(List.of("LICENSE.txt", "adb.exe", "scrcpy-server", "scrcpy.exe"), new ArrayList<>(got.keySet()));
        assertFalse(Files.exists(tmp.resolve("evil.dll")));
    }

    @Test public void verifyDetectsTampering() throws Exception {
        Path tmp = Files.createTempDirectory("pmv");
        MirrorTools t = new MirrorTools(tmp, true, "amd64", MirrorTools.RELEASES, "", "");
        Files.createDirectories(t.dir());
        Path exe = Files.writeString(t.dir().resolve("scrcpy.exe"), "scrcpy"), adb = Files.writeString(t.dir().resolve("adb.exe"), "adb");
        Map<String, Object> files = new TreeMap<>();
        files.put("scrcpy.exe", MediaTools.sha256(exe));
        files.put("adb.exe", MediaTools.sha256(adb));
        Map<String, Object> man = new TreeMap<>();
        man.put("version", MirrorTools.VERSION);
        man.put("files", files);
        Files.writeString(t.manifestFile(), Json.stringify(man));
        assertTrue(t.installed());
        assertNull(t.verify());
        assertEquals(Boolean.TRUE, t.view().get("ok"));
        Files.writeString(adb, "evil adb!");
        assertTrue(t.verify().contains("SHA-256"));
        Files.delete(adb);
        assertTrue(t.verify().contains("不在了"));
    }

    @Test public void installOnlyOnWindows() {
        MirrorTools t = new MirrorTools(Path.of("/tmp/none"), false, "amd64", MirrorTools.RELEASES, "", "");
        try { t.install(null); fail(); } catch (Exception e) { assertTrue(e.getMessage().contains("Windows")); }
    }

    // ------------------------------------------------------------------ 上传接口

    @Test public void uploadNeedsTokenAndDecodesQuery() throws Exception {
        UiServer ui = new UiServer("phonemirrortestkey1118");
        List<Map<String, String>> got = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        ui.onUpload("mirror_drop", (q, size, in) -> { got.add(q); sizes.add(size); in.readAllBytes(); return Map.of("ok", true, "text", "收到"); });
        assertTrue(ui.start(0));
        try {
            String base = "http://127.0.0.1:" + ui.port();
            String q = "/upload/mirror_drop?serial=M21&name=" + java.net.URLEncoder.encode("我的 文件+1.txt", StandardCharsets.UTF_8).replace("+", "%20");
            HttpURLConnection c = post(base + q, null, "abc");
            assertEquals(401, c.getResponseCode());
            HttpURLConnection page = (HttpURLConnection) new URL(base + "/?k=phonemirrortestkey1118").openConnection();
            String html = new String(page.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("const TOKEN='([^']+)'").matcher(html);
            assertTrue(m.find());
            HttpURLConnection ok = post(base + q, m.group(1), "abc");
            assertEquals(200, ok.getResponseCode());
            assertTrue(new String(ok.getInputStream().readAllBytes(), StandardCharsets.UTF_8).contains("收到"));
            assertEquals("我的 文件+1.txt", got.get(0).get("name"));
            assertEquals("M21", got.get(0).get("serial"));
            assertEquals(Long.valueOf(3), sizes.get(0));
            assertEquals(404, post(base + "/upload/nope", m.group(1), "x").getResponseCode());
        } finally {
            ui.stop();
        }
    }

    static HttpURLConnection post(String url, String token, String body) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        if (token != null) c.setRequestProperty("X-OneSay-Token", token);
        try (OutputStream o = c.getOutputStream()) { o.write(body.getBytes(StandardCharsets.UTF_8)); }
        return c;
    }

    @Test public void queryParsing() {
        assertEquals(Map.of("a", "b c", "d", "+"), UiServer.query("a=b%20c&d=%2B&bad&=x"));
        assertTrue(UiServer.query(null).isEmpty());
    }

    // ------------------------------------------------------------------ 安全边界 / 界面

    static String src(String f) throws IOException { return Files.readString(Path.of("src/main/java/com/onesay/desktop/" + f)); }

    @Test public void notAnAgentTool() throws IOException {
        for (String f : List.of("Agent.java", "ChatAgent.java", "PcGuard.java", "Chat.java", "Actions.java", "Intent.java", "PhoneCmds.java")) {
            String s = src(f);
            assertFalse(f + " 不能碰投屏", s.contains("PhoneMirror") || s.contains("MirrorTools") || s.contains("mirror_drop") || s.contains("scrcpy"));
        }
        String app = src("App.java");
        for (String line : app.split("\n")) if (line.contains("agent.local.put")) assertFalse(line, line.toLowerCase().contains("mirror"));
        assertTrue(app.contains("ui.on(\"mirror\""));
    }

    @Test public void uiHasMirrorPageWithoutAutoStart() throws IOException {
        String ui = Files.readString(Path.of("src/main/resources/onesay/ui/index.html"));
        assertTrue(ui.contains("data-p=\"mirror\""));
        assertTrue(ui.contains("P.mirror="));
        assertTrue(ui.contains(">投屏</button>"));
        assertTrue(ui.contains("在手机上允许 USB 调试"));
        assertTrue(ui.contains("USB 安装"));
        assertTrue(ui.contains("无线调试"));
        assertTrue(ui.contains("mirror_keyboard"));
        assertFalse("没有自动投屏开关", ui.contains("mirror_auto") || ui.contains("插上自动投屏") || ui.contains("自动开始投屏"));
        assertTrue(ui.contains("插上手机不会自动投屏"));
        assertFalse(src("Config.java").contains("mirrorAuto"));
        assertFalse(Pattern.compile("\\bdex\\b", Pattern.CASE_INSENSITIVE).matcher(ui).find());
    }

    @Test public void configRoundTripsKeyboard() throws Exception {
        Path tmp = Files.createTempDirectory("pmc");
        Config c = Config.load(tmp.resolve("onesay.properties"));
        assertEquals("sdk", c.mirrorKeyboard);
        c.mirrorKeyboard = "uhid";
        c.save();
        assertEquals("uhid", Config.load(tmp.resolve("onesay.properties")).mirrorKeyboard);
        Files.writeString(tmp.resolve("o2.properties"), "mirror_keyboard=aoa\n");
        assertEquals("sdk", Config.load(tmp.resolve("o2.properties")).mirrorKeyboard);
    }

    @Test public void thirdPartyNoticesListScrcpy() throws IOException {
        String n = Files.readString(Path.of("THIRD_PARTY_NOTICES.md"));
        assertTrue(n.contains("scrcpy"));
        assertTrue(n.contains("Apache-2.0"));
        assertTrue(n.contains("Romain Vimont"));
        assertTrue(n.contains("adb"));
        assertTrue(n.contains("TODO"));
    }

    @SuppressWarnings("unused") private static InputStream nothing() { return InputStream.nullInputStream(); }
}
