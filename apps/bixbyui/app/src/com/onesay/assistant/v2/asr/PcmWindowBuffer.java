package com.onesay.assistant.v2.asr;

import android.os.SystemClock;

import java.io.ByteArrayOutputStream;

/**
 * 捕获窗 PCM 缓冲。
 *
 * <p>现有的语音链路是**流式**的：{@code AudioRecord} 每 100ms 读一帧 {@code short[]}，
 * 直接喂给 vosk，**从不保留原始音频**。而云端一句话识别需要"整段音频一起发"，
 * 所以在窗内必须把 PCM 攒下来。
 *
 * <h3>线程模型</h3>
 * <ul>
 *   <li>{@link #append} 在**麦克风采集线程**调用（热路径，每 100ms 一次）</li>
 *   <li>{@link #snapshot} 在**后台识别线程**调用</li>
 *   <li>全部方法 {@code synchronized} 保证可见性；临界区只做内存拷贝，不阻塞采集循环</li>
 * </ul>
 *
 * <h3>内存上限</h3>
 * 硬顶 {@link #MAX_SECONDS}（12 秒 = 384KB）。正常捕获窗只有 8 秒，
 * 余量是给"窗起点微调"留的，不是给"往前捞音频"留的。
 * 超出即停止累积 —— 宁可丢尾部音频，也不能让麦克风线程无限增长内存。
 *
 * <h3>★ 隐私边界（2026-10-02 明确）</h3>
 * 本缓冲**只装"唤醒之后"的音频**。调用方 {@code WakeService} 把起点定在
 * {@code 唤醒时刻 + 1100ms}（应答音播完之后），因此：
 * <ul>
 *   <li>唤醒词本身、以及唤醒前的常听流 —— <b>从未进入本缓冲</b>；</li>
 *   <li>{@link #snapshot()} 出来的字节只会是"用户唤醒后说的指令"。</li>
 * </ul>
 * <b>严禁把起点前移去"顺便识别唤醒词"</b>：那会把本机常听流送上公网。
 * 早年的注释里写过这种念头（"唤醒词可能在窗开启前就已经在说了"），已废弃 ——
 * 唤醒词由本地 KWS 判定，不需要，也不允许经由这里回传。
 * 另有一道运行期硬闸门兜底，见 {@code WakeService.WAKE_LOCAL_GUARD_MS}。
 */
public final class PcmWindowBuffer {

    private static final int BYTES_PER_SAMPLE = 2; // PCM 16-bit

    /** 缓冲上限（秒）。12s × 16000Hz × 2B = 384KB（公开：声纹录制等场景要在 UI 上向用户提示该上限） */
    public static final int MAX_SECONDS = 12;

    private final ByteArrayOutputStream mBuf = new ByteArrayOutputStream(16000 * 2 * 8);
    private final int mSampleRate;
    private final int mMaxBytes;

    /** 复用的转换暂存区，避免每帧分配（采集热路径，每 100ms 一次） */
    private byte[] mScratch = new byte[0];

    private boolean mActive = false;
    private boolean mOverflowed = false;
    private long mStartedAtElapsed = 0L;

    /**
     * ★ R14：捕获窗专用上限（秒）= 整段窗上限 {@code WakeService.CAPTURE_MAX_MS}（20 s）。
     * 10-15 真机第 1 次尝试：窗续期到 16 s，缓冲却在 12 s 就满了（「缓冲已达上限 12s，后续音频丢弃」），续出来的那段白听。
     * 两个上限现在对齐；声纹录制仍用 {@link #MAX_SECONDS}（12 s，界面提示用的是它）。
     */
    public static final int CAPTURE_MAX_SECONDS = 30;   // ★ r2/input：与 WakeService.CAPTURE_MAX_MS 同步放宽到 30 s

    private final int mMaxSeconds;

    public PcmWindowBuffer(int sampleRate) {
        this(sampleRate, MAX_SECONDS);
    }

    public PcmWindowBuffer(int sampleRate, int maxSeconds) {
        mSampleRate = sampleRate;
        mMaxSeconds = Math.max(1, maxSeconds);
        mMaxBytes = sampleRate * BYTES_PER_SAMPLE * mMaxSeconds;
    }

    /** 本缓冲的上限（秒）。 */
    public int maxSeconds() {
        return mMaxSeconds;
    }

    /** 开始一段新的捕获（清空旧内容）。在 {@code armCaptureWindow} 时调用。 */
    public synchronized void start() {
        mBuf.reset();
        mActive = true;
        mOverflowed = false;
        mStartedAtElapsed = SystemClock.elapsedRealtime();
    }

    /** 停止累积（窗关闭 / 已拿到指令）。已攒下的内容保留，仍可 snapshot。 */
    public synchronized void stop() {
        mActive = false;
    }

    public synchronized boolean isActive() {
        return mActive;
    }

    /**
     * 追加一帧 PCM（short 数组，小端转字节）。
     *
     * @param src 采集缓冲
     * @param len 本次有效样本数
     */
    public synchronized void append(short[] src, int len) {
        if (!mActive || src == null || len <= 0) return;
        if (mBuf.size() >= mMaxBytes) {
            if (!mOverflowed) {
                mOverflowed = true;
                android.util.Log.w("AsrPcmBuffer", "缓冲已达上限 " + mMaxSeconds + "s，后续音频丢弃");
            }
            return;
        }

        int need = len * BYTES_PER_SAMPLE;
        if (mScratch.length < need) {
            mScratch = new byte[need];
        }
        // short[] -> little-endian byte[]
        for (int i = 0; i < len; i++) {
            short v = src[i];
            mScratch[i * 2] = (byte) (v & 0xFF);
            mScratch[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
        }

        int room = mMaxBytes - mBuf.size();
        mBuf.write(mScratch, 0, Math.min(need, room));
    }

    /** 取当前内容的**副本**（线程安全，调用方可安全持有）。 */
    public synchronized byte[] snapshot() {
        return mBuf.toByteArray();
    }

    /** 当前已攒字节数 */
    public synchronized int size() {
        return mBuf.size();
    }

    /** 已攒音频时长（毫秒） */
    public synchronized int durationMs() {
        return mBuf.size() / BYTES_PER_SAMPLE * 1000 / mSampleRate;
    }

    /** 自 start() 起经过的墙钟时间（毫秒） */
    public synchronized long elapsedMs() {
        return (mStartedAtElapsed == 0L) ? 0L : (SystemClock.elapsedRealtime() - mStartedAtElapsed);
    }

    /** 是否曾经溢出过（用于日志诊断，提示用户说话太长） */
    public synchronized boolean overflowed() {
        return mOverflowed;
    }

    /** 清空并复位 */
    public synchronized void reset() {
        mBuf.reset();
        mActive = false;
        mOverflowed = false;
        mStartedAtElapsed = 0L;
    }
}
