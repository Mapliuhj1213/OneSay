package com.onesay.desktop;

import java.io.ByteArrayOutputStream;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.TargetDataLine;

/** 麦克风录音：16 kHz 单声道 16 位小端，最多 60 秒。 */
public final class Recorder {
    public static final int RATE = 16000;
    private TargetDataLine line;
    private Thread th;
    private final ByteArrayOutputStream buf = new ByteArrayOutputStream();
    private volatile boolean running;

    public synchronized void start() throws Exception {
        AudioFormat f = new AudioFormat(RATE, 16, 1, true, false);
        line = (TargetDataLine) AudioSystem.getLine(new DataLine.Info(TargetDataLine.class, f));
        line.open(f, RATE / 5 * 2);
        buf.reset();
        running = true;
        line.start();
        th = new Thread(() -> {
            byte[] b = new byte[RATE / 10 * 2];
            while (running && buf.size() < RATE * 2 * Asr.MAX_SECONDS) {
                int n = line.read(b, 0, b.length);
                if (n > 0) synchronized (buf) { buf.write(b, 0, n); }
            }
        }, "onesay-rec");
        th.setDaemon(true);
        th.start();
    }

    /** 停止并返回 PCM。 */
    public synchronized byte[] stop() {
        running = false;
        if (line != null) { line.stop(); line.close(); }
        try { if (th != null) th.join(500); } catch (InterruptedException ignored) { }
        synchronized (buf) { return buf.toByteArray(); }
    }

    public static double seconds(byte[] pcm) { return pcm.length / (double) (RATE * 2); }

    /** 均方根音量（0–32767），用来判断是不是根本没出声。 */
    public static double rms(byte[] pcm) {
        long sum = 0;
        int n = pcm.length / 2;
        if (n == 0) return 0;
        for (int i = 0; i + 1 < pcm.length; i += 2) { int s = (short) ((pcm[i] & 0xFF) | (pcm[i + 1] << 8)); sum += (long) s * s; }
        return Math.sqrt(sum / (double) n);
    }
}
