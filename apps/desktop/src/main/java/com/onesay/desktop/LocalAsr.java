package com.onesay.desktop;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 本机离线语音识别：Windows 自带的语音识别引擎（System.Speech，听写语法），不联网、不上传。
 * 需要系统里装了中文（简体）语音识别（Windows 中文版一般自带；没有时到「设置 › 时间和语言 › 语音」添加）。
 * 做法：录音写成临时 WAV（16 kHz 单声道 16 位）→ 交给 asr-local.ps1 识别 → 读结果 → 删临时文件。子进程经 {@link SafeExec}，不提权。
 */
public final class LocalAsr {

    private LocalAsr() {}

    private static volatile Boolean avail;

    /** 这台电脑能不能做本机离线识别（只查一次，结果缓存到程序退出）。 */
    public static boolean available() {
        Boolean a = avail;
        if (a != null) return a;
        boolean ok = false;
        if (SafeExec.WINDOWS) {
            try {
                String out = run(null, true, 15);
                ok = out.lines().anyMatch(l -> l.startsWith("OK "));
            } catch (Exception ignored) {
                ok = false;
            }
        }
        avail = ok;
        return ok;
    }

    public static String recognize(byte[] pcm, int rate) throws Exception {
        if (!SafeExec.WINDOWS) throw new IllegalStateException("本机离线识别只在 Windows 上有");
        Path wav = Files.createTempFile("onesay-asr-", ".wav");
        try {
            Files.write(wav, wav(pcm, rate));
            String out = run(wav, false, 30);
            int i = out.lastIndexOf("TEXT:");
            if (i >= 0) return out.substring(i + 5).trim();
            if (out.contains("no_recognizer")) throw new IllegalStateException("这台电脑没有中文离线语音识别（设置 › 时间和语言 › 语音 里添加）");
            throw new IllegalStateException("本机识别失败");
        } finally {
            try { Files.deleteIfExists(wav); } catch (Exception ignored) { }
        }
    }

    /** 16 位单声道小端 PCM → WAV。 */
    static byte[] wav(byte[] pcm, int rate) {
        ByteBuffer b = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("RIFF".getBytes(StandardCharsets.US_ASCII)).putInt(36 + pcm.length).put("WAVE".getBytes(StandardCharsets.US_ASCII));
        b.put("fmt ".getBytes(StandardCharsets.US_ASCII)).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(rate).putInt(rate * 2).putShort((short) 2).putShort((short) 16);
        b.put("data".getBytes(StandardCharsets.US_ASCII)).putInt(pcm.length).put(pcm);
        return b.array();
    }

    private static String run(Path wav, boolean check, int seconds) throws Exception {
        Path dir = Files.createTempDirectory("onesay-asr-ps");
        Path ps = dir.resolve("asr-local.ps1");
        try {
            PsScript.write("/onesay/asr-local.ps1", ps); // 带 BOM，理由见 PsScript
            ProcessBuilder pb = check
                    ? new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", ps.toString(), "-Check")
                    : new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-File", ps.toString(), "-Wav", wav.toString());
            Process p = SafeExec.start(pb.redirectErrorStream(true));
            p.getOutputStream().close();
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            p.getInputStream().transferTo(bo);
            if (!p.waitFor(seconds, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IllegalStateException("本机识别超时");
            }
            return bo.toString(StandardCharsets.UTF_8).trim();
        } finally {
            try { Files.deleteIfExists(ps); Files.deleteIfExists(dir); } catch (Exception ignored) { }
        }
    }
}
