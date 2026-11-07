package com.onesay.assistant.v2.wake;

/**
 * 最近 N 秒音频的滚动（环形）缓冲。
 *
 * <p>sherpa-onnx KWS 报告"命中唤醒词"这件事发生在
 * <b>唤醒词已经说完之后</b>（自带几个 blank 帧的收尾延迟）。要做"是不是主人在喊"的
 * 声纹验证，必须能<b>回溯</b>刚刚过去的那一两秒音频 —— 本类就是这份"回溯能力"。
 *
 * <p>并发模型：只有一个写者（{@link WakeService} 的 micLoop 采集线程，每 ~100ms 写入一次），
 * 读者是唤醒验证线程。写入是极小的临界区（一次 arraycopy），读取返回拷贝，
 * 之后与缓冲再无引用关系 —— 不会把采集线程拖慢。
 *
 * <p>短样本按语音惯例存 short（16bit PCM）。容量按"毫秒 × 16 样本"换算。
 */
public class RollingAudioBuffer {

    private final short[] mBuf;
    /** 下一个写入位置。 */
    private int mWritePos;
    /** 已填充样本数（<= mBuf.length）。 */
    private int mFilled;

    /**
     * @param capacitySamples 容量（样本数）。例如 2.5s @16kHz = 40000。
     */
    public RollingAudioBuffer(int capacitySamples) {
        if (capacitySamples <= 0) throw new IllegalArgumentException("capacity must be > 0");
        mBuf = new short[capacitySamples];
    }

    /** 追加一段 PCM。超出容量时自动覆盖最旧的数据。 */
    public synchronized void append(short[] data, int len) {
        if (data == null || len <= 0) return;
        if (len >= mBuf.length) {
            // 单块比整个缓冲还长：只保留最后 mBuf.length 个样本
            System.arraycopy(data, len - mBuf.length, mBuf, 0, mBuf.length);
            mWritePos = 0;
            mFilled = mBuf.length;
            return;
        }
        int first = Math.min(len, mBuf.length - mWritePos);
        System.arraycopy(data, 0, mBuf, mWritePos, first);
        if (first < len) {
            // 绕回头部
            System.arraycopy(data, first, mBuf, 0, len - first);
        }
        mWritePos = (mWritePos + len) % mBuf.length;
        mFilled = Math.min(mBuf.length, mFilled + len);
    }

    /**
     * 快照最近 wantSamples 个样本。
     *
     * @return 拷贝出来的数组；缓冲为空或参数非法时返回 {@code null}。
     *         不足 wantSamples 时返回当前全部（调用方自行判断时长是否够用）。
     */
    public synchronized short[] snapshotLast(int wantSamples) {
        if (mFilled <= 0 || wantSamples <= 0) return null;
        int n = Math.min(wantSamples, mFilled);
        short[] out = new short[n];
        int start = ((mWritePos - n) % mBuf.length + mBuf.length) % mBuf.length;
        int first = Math.min(n, mBuf.length - start);
        System.arraycopy(mBuf, start, out, 0, first);
        if (first < n) {
            System.arraycopy(mBuf, 0, out, first, n - first);
        }
        return out;
    }

    /** 当前已填充样本数。 */
    public synchronized int available() {
        return mFilled;
    }

    /** 清空（不释放底层数组）。 */
    public synchronized void clear() {
        mWritePos = 0;
        mFilled = 0;
    }
}
