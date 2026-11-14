package com.onesay.desktop;

import static org.junit.Assert.*;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Test;

/**
 * 2026-11-14 fix/branded-host：OneSay.exe 在本进程里托管 Java（jvm.dll），主窗口用 WebView2 开在 OneSay.exe 里，
 * 进程 / 防火墙弹窗 / Dock 软件看到的都是 OneSay；AppUserModelID 固定为 OneSay.Desktop。
 */
public class BrandedHostTest {

    private static String read(String p) throws Exception {
        return Files.readString(Path.of(p));
    }

    @Test public void notHostedMeansEveryCallFallsBack() {
        String old = System.getProperty("onesay.host");
        try {
            System.clearProperty("onesay.host");
            assertFalse(Host.present());
            assertFalse("不是托管：窗口照旧由 Java 开 Edge", Host.open("http://127.0.0.1:1/"));
            assertFalse(Host.shortcut("x.lnk", "OneSay.exe"));
            assertNull(Host.desktopDir());
            Host.ready("http://127.0.0.1:1/"); // 不抛
        } finally {
            if (old != null) System.setProperty("onesay.host", old);
        }
    }

    @Test public void hostedButNativesMissingStillFallsBack() {
        String old = System.getProperty("onesay.host");
        try {
            System.setProperty("onesay.host", "1");
            assertTrue(Host.present());
            assertFalse("native 没注册（旧启动器 / 注册失败）：返回 false，不抛 UnsatisfiedLinkError", Host.open("http://127.0.0.1:1/"));
            assertFalse(Host.shortcut("x.lnk", "OneSay.exe"));
            assertNull(Host.desktopDir());
            Host.ready("http://127.0.0.1:1/");
        } finally {
            if (old == null) System.clearProperty("onesay.host"); else System.setProperty("onesay.host", old);
        }
    }

    @Test public void appUserModelIdIsTheSameEverywhere() throws Exception {
        assertEquals("OneSay.Desktop", Host.AUMID);
        assertTrue(read("launcher/Program.cs").contains("public const string AppId = \"OneSay.Desktop\";"));
        String nsi = read("installer/OneSay.nsi");
        assertTrue(nsi.contains("!define APPID     \"OneSay.Desktop\""));
        assertTrue("开始菜单快捷方式写 AppUserModelID", nsi.contains("ExecWait '\"$INSTDIR\\${APPEXE}\" --shortcut \"$SMPROGRAMS\\OneSay.lnk\"'"));
        assertTrue("桌面快捷方式写 AppUserModelID", nsi.contains("ExecWait '\"$INSTDIR\\${APPEXE}\" --shortcut \"$DESKTOP\\OneSay.lnk\"'"));
        String native_ = read("launcher/Native.cs");
        assertTrue("PKEY_AppUserModel_ID", native_.contains("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3") && native_.contains("pid = 5"));
        assertTrue(read("launcher/Program.cs").contains("Native.SetCurrentProcessExplicitAppUserModelID(AppId);"));
    }

    /** JvmHost.cs 里手写的 JNI 函数表下标必须和 jni.h 对得上（错一个就是崩溃）。 */
    @Test public void jniTableIndicesMatchJniHeader() throws Exception {
        Path h = Paths.get(System.getProperty("java.home"), "include", "jni.h");
        if (!Files.exists(h)) h = Paths.get(System.getenv().getOrDefault("JAVA_HOME", "/nonexistent"), "include", "jni.h");
        org.junit.Assume.assumeTrue("找不到 jni.h（只有 JRE）", Files.exists(h));
        String src = Files.readString(h);
        List<String> env = table(src, "struct JNINativeInterface_ {");
        List<String> vm = table(src, "struct JNIInvokeInterface_ {");
        String cs = read("launcher/JvmHost.cs");
        Matcher m = Pattern.compile("(\\w+)Idx = (\\d+)").matcher(cs);
        int n = 0;
        while (m.find()) {
            String name = m.group(1);
            int idx = Integer.parseInt(m.group(2));
            List<String> t = name.equals("DestroyJavaVM") ? vm : env;
            assertEquals(name + " 的下标", idx, t.indexOf(name));
            n++;
        }
        assertTrue("核对了全部用到的函数", n >= 17);
    }

    private static List<String> table(String src, String head) {
        String s = src.substring(src.indexOf(head));
        s = s.substring(0, s.indexOf("};"));
        s = s.replaceAll("(?s)/\\*.*?\\*/", "");
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("void \\*(reserved\\d)|JNICALL \\*(\\w+)\\)").matcher(s);
        while (m.find()) out.add(m.group(1) != null ? m.group(1) : m.group(2));
        return out;
    }

    /** 启动器注册的 native 方法名和签名必须和 Host.java 的声明一致。 */
    @Test public void registeredNativesMatchHostDeclarations() throws Exception {
        String cs = read("launcher/JvmHost.cs");
        int count = 0;
        for (Method m : Host.class.getDeclaredMethods()) {
            if (!Modifier.isNative(m.getModifiers())) continue;
            StringBuilder sig = new StringBuilder("(");
            for (Class<?> p : m.getParameterTypes()) sig.append(desc(p));
            sig.append(')').append(desc(m.getReturnType()));
            assertTrue("启动器注册了 " + m.getName() + sig, cs.contains("Method(\"" + m.getName() + "\", \"" + sig + "\""));
            assertTrue(Modifier.isStatic(m.getModifiers()));
            count++;
        }
        assertEquals(4, count);
        assertTrue(cs.contains("\"com/onesay/desktop/Host\""));
    }

    private static String desc(Class<?> c) {
        if (c == boolean.class) return "Z";
        if (c == void.class) return "V";
        if (c == String.class) return "Ljava/lang/String;";
        throw new AssertionError("没处理的类型 " + c);
    }

    @Test public void jvmOptionsKeepTheOldCommandLine() throws Exception {
        String cs = read("launcher/JvmHost.cs");
        for (String o : new String[] { "\"-Xmx256m\"", "\"-Dfile.encoding=UTF-8\"", "\"-Dsun.java2d.uiScale.enabled=true\"",
                "\"-Donesay.exe=\" + exe", "\"-Donesay.host=1\"", "\"-Djava.class.path=\" + jar" })
            assertTrue("保留 " + o, cs.contains(o));
        assertTrue("--tray 照传", cs.contains("tray ? new[] { \"app\", \"--tray\" } : new[] { \"app\" }"));
        assertTrue("jvm.dll 从自带运行时加载", read("launcher/Program.cs").contains("Path.Combine(Dir, \"runtime\", \"bin\", \"server\", \"jvm.dll\")"));
        assertTrue("启动失败有中文报错框", cs.contains("OneSay 启动失败：Java 运行时没能启动"));
        assertFalse("不再起 javaw 子进程", read("launcher/Program.cs").contains("javaw.exe"));
    }

    @Test public void singleInstanceOnlyShowsTheWindow() throws Exception {
        String p = read("launcher/Program.cs");
        assertTrue(p.contains("new Mutex(true, MutexName, out first)"));
        assertTrue("第二次启动叫出窗口", p.contains("EventWaitHandle.OpenExisting(ShowEventName)) ev.Set()"));
        assertTrue("--tray 的第二次启动什么都不做", p.contains("if (!tray)"));
        String app = read("src/main/java/com/onesay/desktop/App.java");
        assertTrue("Java 侧的已开着检测还在", app.contains("if (up > 0 && ping(up))"));
        assertTrue("窗口先交给 OneSay.exe", app.contains("if (Host.open(url)) return;"));
        assertTrue(app.contains("Host.ready(ui.url());"));
        assertTrue("快捷方式托管时由启动器写（带 AUMID）", app.contains("Host.shortcut(start.toString(), exe)"));
    }

    @Test public void installerDetectsRunningHostAndStaysPerUser() throws Exception {
        String nsi = read("installer/OneSay.nsi");
        assertTrue("运行检测也看 OneSay.exe（Java 在它进程里）", nsi.contains("StrCpy $R2 \"$INSTDIR\\${APPEXE}\""));
        assertTrue("旧版 javaw 仍检测", nsi.contains("StrCpy $R2 \"$INSTDIR\\runtime\\bin\\javaw.exe\""));
        assertTrue(nsi.contains("RequestExecutionLevel user"));
        String low = nsi.toLowerCase();
        assertFalse("不为防火墙加规则 / 提权", low.contains("netsh") || low.contains("advfirewall") || low.contains("requestexecutionlevel admin"));
        for (String f : new String[] { "Microsoft.Web.WebView2.Core.dll", "Microsoft.Web.WebView2.WinForms.dll", "WebView2Loader.dll" })
            assertTrue("卸载删 " + f, nsi.contains("Delete \"$INSTDIR\\" + f + "\""));
        String b = read("installer/build-installer.sh");
        assertTrue("启动器从源码编", b.contains("bash launcher/build-launcher.sh"));
        assertTrue(b.contains("runtime/bin/server/jvm.dll"));
    }

    @Test public void launcherVersionInfoIsOneSay() throws Exception {
        String p = read("launcher/OneSay.csproj");
        for (String t : new String[] { "<AssemblyTitle>OneSay</AssemblyTitle>", "<Product>OneSay</Product>", "<Company>OneSay</Company>",
                "<Version>" + Main.VERSION + "</Version>", "<ApplicationIcon>onesay.ico</ApplicationIcon>", "<PlatformTarget>x64</PlatformTarget>" })
            assertTrue(t, p.contains(t));
        assertTrue("asInvoker，不弹 UAC", read("launcher/app.manifest").contains("level=\"asInvoker\""));
        assertTrue("WebView2 SDK 登记在第三方说明里", read("THIRD_PARTY_NOTICES.md").contains("Microsoft WebView2 SDK"));
    }
}
