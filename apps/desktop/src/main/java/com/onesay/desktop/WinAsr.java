package com.onesay.desktop;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * [overnight-1119] 按住说话的第三个识别来源：Windows 自带语音识别（Windows.Media.SpeechRecognition，asr-winrt.ps1）。
 *
 * <p>用户的电脑上既没填识别 Key、System.Speech 也没有中文识别器，所以「按住说话」一直自己关掉、界面却还写着能用。
 * 这个来源由 Windows 自己从麦克风听（不经 OneSay 录音，不用任何 Key）；Windows 的听写要在「设置 › 隐私和安全性 › 语音」里打开「联机语音识别」，
 * 没打开时如实提示。为了按下就能听，提前起好一个等「GO」的进程（PowerShell + WinRT 加载要一两秒），用完再起下一个。
 */
public final class WinAsr {
    private WinAsr() {}

    private static volatile Boolean avail;
    private static volatile String lang = "";

    /** 能不能用（只查一次）。 */
    public static boolean available() {
        Boolean a = avail;
        if (a != null) return a;
        boolean ok = false;
        if (SafeExec.WINDOWS) {
            try {
                String out = runCheck();
                for (String l : out.split("\\R")) if (l.startsWith("OK ")) { ok = true; lang = l.substring(3).trim(); }
            } catch (Exception ignored) { }
        }
        avail = ok;
        return ok;
    }

    static String runCheck() throws Exception {
        Path dir = Files.createTempDirectory("onesay-winasr");
        Path ps = dir.resolve("asr-winrt.ps1");
        try {
            PsScript.write("/onesay/asr-winrt.ps1", ps);
            Process p = SafeExec.start(new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", ps.toString(), "-Check").redirectErrorStream(true));
            p.getOutputStream().close();
            byte[] b = p.getInputStream().readAllBytes();
            if (!p.waitFor(20, TimeUnit.SECONDS)) { p.destroyForcibly(); return ""; }
            return new String(b, StandardCharsets.UTF_8).trim();
        } finally {
            try { Files.deleteIfExists(ps); Files.deleteIfExists(dir); } catch (Exception ignored) { }
        }
    }

    /** 一次「按下 → 松开」的会话。 */
    public static final class Session {
        final Process p;
        final BufferedReader out;
        final OutputStream in;
        final Path script;

        Session(Process p, Path script) {
            this.p = p;
            this.script = script;
            this.out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
            this.in = p.getOutputStream();
        }

        void send(String s) throws Exception {
            in.write((s + "\n").getBytes(StandardCharsets.UTF_8));
            in.flush();
        }

        void close() {
            try { send("QUIT"); } catch (Exception ignored) { }
            p.destroy();
            try { Files.deleteIfExists(script); Files.deleteIfExists(script.getParent()); } catch (Exception ignored) { }
        }
    }

    private static volatile Session ready;

    /** 提前起一个（读到 READY 才算好）。失败返回 null。 */
    static synchronized Session prepare() {
        if (ready != null && ready.p.isAlive()) return ready;
        try {
            Path dir = Files.createTempDirectory("onesay-winasr");
            Path ps = dir.resolve("asr-winrt.ps1");
            PsScript.write("/onesay/asr-winrt.ps1", ps);
            Process p = SafeExec.start(new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", ps.toString()).redirectErrorStream(true));
            Session s = new Session(p, ps);
            long until = System.currentTimeMillis() + 15_000;
            String line;
            while ((line = s.out.readLine()) != null) {
                if (line.trim().equals("READY")) { ready = s; return s; }
                if (line.startsWith("ERR:") || System.currentTimeMillis() > until) break;
            }
            s.close();
        } catch (Exception ignored) { }
        return null;
    }

    /** 后台先备一个。 */
    public static void warm() {
        Thread t = new Thread(WinAsr::prepare, "onesay-winasr-warm");
        t.setDaemon(true);
        t.start();
    }

    private static volatile Session live;

    /** 按下：开始听（用备好的进程；没备好就现起，开头可能听漏一两个字）。 */
    public static boolean begin() {
        Session s = prepare();
        ready = null;
        if (s == null) return false;
        try {
            s.send("GO");
            live = s;
            return true;
        } catch (Exception e) {
            s.close();
            return false;
        }
    }

    /** 松开：停，拿结果（最多等 6 秒）。cancel = 不要结果。 */
    public static String end(boolean cancel) throws Exception {
        Session s = live;
        live = null;
        if (s == null) throw new IllegalStateException("Windows 语音识别没起来");
        try {
            s.send("STOP");
            if (cancel) return "";
            long until = System.currentTimeMillis() + 6000;
            String line;
            while (System.currentTimeMillis() < until && (line = s.out.readLine()) != null) {
                if (line.startsWith("TEXT:")) return line.substring(5).trim();
                if (line.startsWith("ERR:")) throw new IllegalStateException(errZh(line.substring(4).trim()));
            }
            throw new IllegalStateException("Windows 语音识别没回结果");
        } finally {
            s.close();
            warm();
        }
    }

    /** Windows 识别的错误 → 人话。 */
    static String errZh(String e) {
        if (e.equals("privacy")) return "Windows 的「联机语音识别」没打开（设置 › 隐私和安全性 › 语音），或者在设置里填识别 Key";
        if (e.equals("no_zh")) return "Windows 里没有中文语音识别（设置 › 时间和语言 › 语音 里添加），或者在设置里填识别 Key";
        if (e.startsWith("compile_")) return "Windows 语音识别没准备好（" + e.substring(8) + "）";
        if (e.equals("TimeoutExceeded") || e.equals("UserCanceled")) return "没听清";
        if (e.equals("AudioQualityFailure") || e.equals("MicrophoneUnavailable")) return "麦克风不可用";
        if (e.equals("NetworkFailure")) return "Windows 语音识别连不上网";
        return "Windows 语音识别出错：" + e;
    }

    public static String language() { return lang; }
}
