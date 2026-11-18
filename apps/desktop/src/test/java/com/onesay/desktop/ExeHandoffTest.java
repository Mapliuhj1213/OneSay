package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import org.junit.Test;

/** ★ exe-handoff-1118：直接 javaw -jar 启动时交给 OneSay.exe，Edge 应用窗口只作最后的后备并标明。 */
public class ExeHandoffTest {

    /** 造一个安装目录：OneSay.exe + app\onesay-desktop.jar（内容 jarBytes）+ runtime\bin\server\jvm.dll。 */
    static Path install(Path root, String name, byte[] jarBytes, boolean withJvm) throws Exception {
        Path d = root.resolve(name);
        Files.createDirectories(d.resolve("app"));
        Files.write(d.resolve("OneSay.exe"), new byte[] {'M', 'Z'});
        Files.write(d.resolve("app").resolve("onesay-desktop.jar"), jarBytes);
        if (withJvm) {
            Files.createDirectories(d.resolve("runtime").resolve("bin").resolve("server"));
            Files.write(d.resolve("runtime").resolve("bin").resolve("server").resolve("jvm.dll"), new byte[] {1});
        }
        return d;
    }

    static byte[] b(String s) { return s.getBytes(StandardCharsets.UTF_8); }

    @Test public void runningTheInstalledJarDirectlyHandsOffToItsExe() throws Exception {
        Path t = Files.createTempDirectory("hof");
        Path d = install(t, "OneSay", b("JAR-A"), true);
        Path jar = ExeHandoff.appJar(d);
        // 计划任务那种：<目录>\runtime\bin\javaw.exe -jar <目录>\app\onesay-desktop.jar
        ExeHandoff.Decision r = ExeHandoff.decide(true, false, false, false, jar, d.resolve("runtime"), null);
        assertEquals(ExeHandoff.Kind.HANDOFF, r.kind);
        assertEquals(d.resolve("OneSay.exe"), r.exe);
    }

    @Test public void copyOfTheSameJarElsewhereFindsDefaultInstallDir() throws Exception {
        Path t = Files.createTempDirectory("hof");
        Path lad = t.resolve("Local");
        Path d = install(lad.resolve("Programs"), "OneSay", b("JAR-SAME"), true);
        Path dl = Files.createDirectories(t.resolve("Downloads"));
        Path copy = Files.write(dl.resolve("onesay-desktop-fix.jar"), b("JAR-SAME"));
        ExeHandoff.Decision r = ExeHandoff.decide(true, false, false, false, copy, null, lad.toString());
        assertEquals("内容相同（SHA-256）就交接", ExeHandoff.Kind.HANDOFF, r.kind);
        assertEquals(d.resolve("OneSay.exe"), r.exe);
    }

    @Test public void differentJarIsNotSilentlySwapped() throws Exception {
        Path t = Files.createTempDirectory("hof");
        Path lad = t.resolve("Local");
        Path d = install(lad.resolve("Programs"), "OneSay", b("JAR-OLD"), true);
        Path copy = Files.write(Files.createDirectories(t.resolve("Downloads")).resolve("x.jar"), b("JAR-NEW"));
        // 用安装目录自带的 javaw 跑一个不同的 jar（今天电脑上的情况）
        ExeHandoff.Decision r = ExeHandoff.decide(true, false, false, false, copy, d.resolve("runtime"), lad.toString());
        assertEquals(ExeHandoff.Kind.JAR_DIFFERS, r.kind);
        assertEquals(d.resolve("OneSay.exe"), r.exe);
        assertEquals(ExeHandoff.appJar(d), r.appJar);
        // 同样大小但内容不同也不算同一个
        Path same = Files.write(t.resolve("y.jar"), b("JAR-OLX"));
        assertFalse(ExeHandoff.sameJar(same, ExeHandoff.appJar(d)));
    }

    @Test public void incompleteInstallIsIgnored() throws Exception {
        Path t = Files.createTempDirectory("hof");
        Path d = install(t, "OneSay", b("J"), false); // 没有 jvm.dll：OneSay.exe 会报「文件不完整」
        assertFalse(ExeHandoff.complete(d));
        assertEquals(ExeHandoff.Kind.NO_EXE, ExeHandoff.decide(true, false, false, false, ExeHandoff.appJar(d), null, null).kind);
        Path none = Files.write(t.resolve("lone.jar"), b("J"));
        assertEquals(ExeHandoff.Kind.NO_EXE, ExeHandoff.decide(true, false, false, false, none, null, t.resolve("nowhere").toString()).kind);
    }

    @Test public void guardsPreventLoopsAndRespectOptOut() throws Exception {
        Path t = Files.createTempDirectory("hof");
        Path d = install(t, "OneSay", b("J"), true);
        Path jar = ExeHandoff.appJar(d);
        assertEquals(ExeHandoff.Kind.NOT_WINDOWS, ExeHandoff.decide(false, false, false, false, jar, null, null).kind);
        assertEquals("已经是 OneSay.exe 托管：不再交接", ExeHandoff.Kind.HOSTED, ExeHandoff.decide(true, true, false, false, jar, null, null).kind);
        assertEquals(ExeHandoff.Kind.DISABLED, ExeHandoff.decide(true, false, true, false, jar, null, null).kind);
        assertEquals("被交接拉起来的：不再来回拉", ExeHandoff.Kind.ALREADY_HANDED, ExeHandoff.decide(true, false, false, true, jar, null, null).kind);
        assertEquals(ExeHandoff.Kind.NO_JAR, ExeHandoff.decide(true, false, false, false, null, null, null).kind);
        assertEquals(ExeHandoff.Kind.NO_JAR, ExeHandoff.decide(true, false, false, false, t.resolve("missing.jar"), null, null).kind);
    }

    @Test public void candidateOrderAndTrayFlag() {
        Path jar = Paths.get("/x/OneSay/app/onesay-desktop.jar");
        List<Path> c = ExeHandoff.candidates(jar, Paths.get("/y/OneSay2/runtime"), "/lad");
        assertEquals(Paths.get("/x/OneSay").toAbsolutePath(), c.get(0));
        assertEquals(Paths.get("/y/OneSay2").toAbsolutePath(), c.get(1));
        assertEquals(Paths.get("/lad", "Programs", "OneSay"), c.get(2));
        assertEquals(List.of("/e/OneSay.exe", "--tray"), ExeHandoff.command(Paths.get("/e/OneSay.exe"), true));
        assertEquals(List.of("/e/OneSay.exe"), ExeHandoff.command(Paths.get("/e/OneSay.exe"), false));
    }

    @Test public void notWindowsHereSoCurrentNeverHandsOffInTests() {
        if (App.isWindows()) return;
        assertFalse(ExeHandoff.tryHandoff(true));
    }

    @Test public void edgeFallbackIsLabelled() throws Exception {
        assertEquals("http://127.0.0.1:47830/?k=abc#edge-fallback", App.edgeFallbackUrl("http://127.0.0.1:47830/?k=abc"));
        assertEquals("http://127.0.0.1:1/?k=a#edge-fallback", App.edgeFallbackUrl("http://127.0.0.1:1/?k=a#x"));
        String html = new String(UiServer.resource("onesay/ui/index.html"), StandardCharsets.UTF_8);
        assertTrue(html.contains("location.hash==='#edge-fallback'"));
        assertTrue(html.contains("Edge 后备窗口"));
        String app = Files.readString(Path.of("src/main/java/com/onesay/desktop/App.java"));
        int launch = app.indexOf("public static void launch(");
        assertTrue("交接在开端口之前", app.indexOf("ExeHandoff.tryHandoff(", launch) < app.indexOf("app.start();", launch));
        int ow = app.indexOf("static void openWindow(");
        assertTrue("先试 OneSay.exe 的窗口，Edge 在后", app.indexOf("Host.open(url)", ow) < app.indexOf("msedge.exe", ow));
        assertTrue(app.indexOf("\"--app=\" + edgeUrl", ow) > 0);
    }
}
