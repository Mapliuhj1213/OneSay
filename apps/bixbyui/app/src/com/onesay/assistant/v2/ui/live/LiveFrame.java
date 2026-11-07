package com.onesay.assistant.v2.ui.live;

/**
 * ★ 11-04 相机对话：一轮用的那一帧（JPEG 字节，只在内存里）。用完 {@link #wipe()} 清零并丢掉引用 ——
 * 帧从不写盘、从不进日志；日志里只允许出现字节数。纯 Java，JVM 单测。
 */
public final class LiveFrame {

    private byte[] mJpeg;
    private final int mWidth, mHeight;

    public LiveFrame(byte[] jpeg, int width, int height) {
        mJpeg = jpeg;
        mWidth = width;
        mHeight = height;
    }

    public synchronized byte[] bytes() {
        return mJpeg;
    }

    public synchronized int size() {
        return mJpeg == null ? 0 : mJpeg.length;
    }

    public int width() { return mWidth; }

    public int height() { return mHeight; }

    public synchronized boolean wiped() {
        return mJpeg == null;
    }

    /** 清零字节并释放引用（幂等）。 */
    public synchronized void wipe() {
        byte[] b = mJpeg;
        mJpeg = null;
        if (b != null) java.util.Arrays.fill(b, (byte) 0);
    }

    /** 日志用的描述：只有尺寸和字节数。 */
    public synchronized String describe() {
        return mWidth + "x" + mHeight + " " + size() + "B";
    }
}
