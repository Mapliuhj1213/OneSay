package com.onesay.assistant.v2.fastscreen;

/**
 * [fix-1123-c] 触摸屏原始事件（/dev/input/eventN）解析，纯 Java（JVM 可测）。从读屏助手原来的 readTouches 拆出来。
 *
 * <p>2026-10-31 真机上返回键紧急接管一次都没计到（backs 一直 0）。按代码推断的原因（没上机复现，见 OUT.md）：
 * 原来固定按 24 字节一条事件读（64 位进程的 struct input_event），而读屏助手如果跑在 32 位 app_process 里，内核按 32 位兼容格式给
 * 16 字节一条，按 24 字节切就全错位，坐标、按下都认不出来。现在按进程位数选 16 / 24，读到的字节数对不上时再按能整除的那个改过来。
 *
 * <p>一条事件：timeval（8 或 16 字节）+ type(2) + code(2) + value(4)，小端。
 */
public final class NavTouchParser {

    public static final int EV64 = 24, EV32 = 16;

    /** 按下的落点（屏幕坐标）：哪个键。 */
    public interface Sink {
        void onDown(int sx, int sy);
    }

    private int mSize;
    private final int mMaxX, mMaxY, mScreenW, mScreenH;
    private int x = -1, y = -1;
    private boolean down, pendingDown;
    private final byte[] mCarry = new byte[EV64];
    private int mCarryN;

    public NavTouchParser(int eventSize, int maxX, int maxY, int screenW, int screenH) {
        mSize = eventSize == EV32 ? EV32 : EV64;
        mMaxX = maxX;
        mMaxY = maxY;
        mScreenW = screenW;
        mScreenH = screenH;
    }

    /** 当前按的事件长度。 */
    public int eventSize() { return mSize; }

    /** 进程位数 → 事件长度。 */
    public static int sizeFor(boolean is64Bit) { return is64Bit ? EV64 : EV32; }

    /**
     * 一次 read 读到的字节（evdev 一次只给整条事件）。第一次读到的长度只能被另一种长度整除时改用那种。
     */
    public void feed(byte[] buf, int n, Sink sink) {
        if (n <= 0) return;
        if (mCarryN == 0 && n % mSize != 0) {
            int other = mSize == EV64 ? EV32 : EV64;
            if (n % other == 0) mSize = other;
        }
        int i = 0;
        if (mCarryN > 0) { // 理论上不会有半条；万一有，先补齐
            int need = mSize - mCarryN, k = Math.min(need, n);
            System.arraycopy(buf, 0, mCarry, mCarryN, k);
            mCarryN += k;
            i = k;
            if (mCarryN < mSize) return;
            one(mCarry, 0, sink);
            mCarryN = 0;
        }
        for (; i + mSize <= n; i += mSize) one(buf, i, sink);
        if (i < n) {
            mCarryN = n - i;
            System.arraycopy(buf, i, mCarry, 0, mCarryN);
        }
    }

    private void one(byte[] b, int off, Sink sink) {
        int t = mSize - 8; // type 所在位置
        int type = u16(b, off + t), code = u16(b, off + t + 2), val = s32(b, off + t + 4);
        if (type == 3 && code == 0x35) x = val;
        else if (type == 3 && code == 0x36) y = val;
        else if (type == 3 && code == 0x39) { // ABS_MT_TRACKING_ID：-1 = 抬起
            if (val >= 0 && !down) pendingDown = true;
            if (val < 0) down = false;
        } else if (type == 1 && code == 0x14a) { // BTN_TOUCH
            if (val == 1 && !down) pendingDown = true;
            if (val == 0) down = false;
        } else if (type == 0 && code == 0 && pendingDown && x >= 0 && y >= 0) { // SYN_REPORT
            pendingDown = false;
            down = true;
            sink.onDown(scale(x, mMaxX, mScreenW), scale(y, mMaxY, mScreenH));
        }
    }

    static int scale(int v, int max, int screen) {
        return max > 0 && screen > 0 ? Math.round(v * (screen / (float) (max + 1))) : v;
    }

    static int u16(byte[] b, int i) { return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8; }

    static int s32(byte[] b, int i) { return (b[i] & 0xff) | (b[i + 1] & 0xff) << 8 | (b[i + 2] & 0xff) << 16 | (b[i + 3] & 0xff) << 24; }

    /** 测试 / 自检用：拼一条事件。 */
    public static byte[] event(int size, int type, int code, int value) {
        byte[] b = new byte[size];
        int t = size - 8;
        b[t] = (byte) type;
        b[t + 1] = (byte) (type >> 8);
        b[t + 2] = (byte) code;
        b[t + 3] = (byte) (code >> 8);
        b[t + 4] = (byte) value;
        b[t + 5] = (byte) (value >> 8);
        b[t + 6] = (byte) (value >> 16);
        b[t + 7] = (byte) (value >> 24);
        return b;
    }
}
