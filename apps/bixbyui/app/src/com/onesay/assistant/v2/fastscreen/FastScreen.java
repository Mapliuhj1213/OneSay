package com.onesay.assistant.v2.fastscreen;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;

/**
 * ★ r2/fast-screen：App 侧的常驻 root 读屏助手客户端（任意后台线程调用，内部串行）。
 *
 * <ul>
 *   <li>第一次用（或助手死了）→ 经 exec 守护的固定模板 {@link FsProtocol#START_TEMPLATE} 拉起助手，然后连抽象 LocalSocket；</li>
 *   <li>任何一步失败都返回 null，调用方<b>回退到原来的路径</b>（AssistStructure → uiautomator dump / screencap）；</li>
 *   <li>连续失败后 {@link #BACKOFF_MS} 内不再尝试拉起，免得每次读屏都白等守护往返；</li>
 *   <li>助手持有 UiAutomation 时 {@code uiautomator dump} 会失败 → 回退前先 {@link #releaseForFallback()}。</li>
 * </ul>
 * 开关：prefs {@link #PREF} / {@link #KEY_ENABLED}（默认开）。
 */
public final class FastScreen {

    private static final String TAG = "FastScreen";
    public static final String PREF = "fast_screen";
    public static final String KEY_ENABLED = "enabled";
    static final long BACKOFF_MS = 60_000L;
    static final int CONNECT_WAIT_MS = 6000;
    static final int SO_TIMEOUT_MS = 8000;

    private static FastScreen sInst;

    public static synchronized FastScreen get(Context ctx) {
        if (sInst == null) sInst = new FastScreen(ctx.getApplicationContext());
        return sInst;
    }

    private final Context mApp;
    private final Object mLock = new Object();
    private LocalSocket mSock;
    private DataInputStream mIn;
    private DataOutputStream mOut;
    private long mBackoffUntil;
    private volatile String mLastError = "";
    /** 统计（控制台 / 报告用）。 */
    public volatile int calls, fails, starts;
    public volatile long lastStartMs;

    private FastScreen(Context app) {
        mApp = app;
    }

    public static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
    }

    public String lastError() {
        return mLastError;
    }

    public String status() {
        return "connected=" + (mSock != null) + " calls=" + calls + " fails=" + fails + " starts=" + starts + " lastStart=" + lastStartMs
                + "ms err=" + mLastError;
    }

    /** 预热（唤醒 / 服务启动时调；后台线程）。 */
    public void warmAsync() {
        if (!enabled(mApp)) return;
        com.onesay.assistant.v2.util.Threads.start("FastScreenWarm", new Runnable() {
            @Override public void run() {
                synchronized (mLock) {
                    ensure();
                }
            }
        });
    }

    // ------------------------------------------------------------------ 连接

    private boolean connectOnce() {
        try {
            LocalSocket s = new LocalSocket();
            s.connect(new LocalSocketAddress(FsProtocol.socketName(mApp.getPackageName()), LocalSocketAddress.Namespace.ABSTRACT));
            s.setSoTimeout(SO_TIMEOUT_MS);
            mSock = s;
            mIn = new DataInputStream(new BufferedInputStream(s.getInputStream()));
            mOut = new DataOutputStream(s.getOutputStream());
            return true;
        } catch (Throwable t) {
            closeQuiet();
            return false;
        }
    }

    private void closeQuiet() {
        try {
            if (mSock != null) mSock.close();
        } catch (Throwable ignored) {
        }
        mSock = null;
        mIn = null;
        mOut = null;
    }

    /** 已连上返回 true；否则尝试连接 / 经守护拉起。调用方持锁。 */
    private boolean ensure() {
        if (mSock != null) return true;
        if (!enabled(mApp)) {
            mLastError = "disabled";
            return false;
        }
        if (connectOnce()) return true;
        long now = SystemClock.elapsedRealtime();
        if (now < mBackoffUntil) {
            mLastError = "backoff";
            return false;
        }
        long t0 = now;
        com.onesay.assistant.v2.executor.ShellExecutor.ShellResponse r =
                new com.onesay.assistant.v2.executor.ShellExecutor(mApp).execute(FsProtocol.START_TEMPLATE);
        starts++;
        String out = r == null ? "null" : r.output;
        Log.i(TAG, "[FS] 拉起助手 rc=" + (r == null ? -1 : r.rc) + " out=" + (out == null ? "" : out.trim()));
        long end = SystemClock.elapsedRealtime() + CONNECT_WAIT_MS;
        while (SystemClock.elapsedRealtime() < end) {
            if (connectOnce()) {
                lastStartMs = SystemClock.elapsedRealtime() - t0;
                Log.i(TAG, "[FS] 助手已连上，拉起耗时 " + lastStartMs + "ms");
                return true;
            }
            SystemClock.sleep(100);
        }
        mBackoffUntil = SystemClock.elapsedRealtime() + BACKOFF_MS;
        mLastError = "start failed: " + (r == null ? "null" : (r.denyReason != null ? "DENY " + r.denyReason : "rc=" + r.rc + " " + out));
        Log.w(TAG, "[FS] 助手拉起失败，" + (BACKOFF_MS / 1000) + " 秒内走回退路径：" + mLastError);
        return false;
    }

    /** 发一条请求；失败（含断线）重连一次。返回 null = 不可用 / 出错（调用方回退）。 */
    public FsProtocol.Resp call(String op, String... args) {
        String line = FsProtocol.encode(op, args);
        synchronized (mLock) {
            calls++;
            for (int attempt = 0; attempt < 2; attempt++) {
                if (!ensure()) {
                    fails++;
                    return null;
                }
                try {
                    FsProtocol.writeRequest(mOut, line);
                    FsProtocol.Resp r = FsProtocol.readResponse(mIn);
                    if (r.status == FsProtocol.ST_DENY) {
                        mLastError = r.text();
                        Log.w(TAG, "[FS] 被拒：" + op + " " + mLastError);
                        fails++;
                        if (mLastError.startsWith("DENY UID") || mLastError.startsWith("DENY CERT") || mLastError.startsWith("DENY PKG")) {
                            closeQuiet();
                            mBackoffUntil = SystemClock.elapsedRealtime() + BACKOFF_MS;
                        }
                        return null;
                    }
                    if (r.status != FsProtocol.ST_OK) {
                        mLastError = op + ": " + r.text();
                        fails++;
                    }
                    return r;
                } catch (Throwable t) {
                    mLastError = op + ": " + t;
                    closeQuiet();   // 断线（助手被杀 / 自己空闲退出）→ 下一轮重连或重新拉起
                }
            }
            fails++;
            return null;
        }
    }

    private String text(String op, String... args) {
        FsProtocol.Resp r = call(op, args);
        return r != null && r.ok() ? r.text() : null;
    }

    // ------------------------------------------------------------------ 方法

    public boolean ping() {
        return text(FsProtocol.OP_PING) != null;
    }

    public FsTree tree() {
        return tree(false);
    }

    /** ★ mika-plug：allWindows = 读全部内容窗口（助手 v-mika 起支持；旧助手不认第三个参数时退回单根读法）。 */
    public FsTree tree(boolean allWindows) {
        String s = allWindows ? text(FsProtocol.OP_TREE, "1500", "w") : text(FsProtocol.OP_TREE);
        if (s == null && allWindows) s = text(FsProtocol.OP_TREE);
        if (s == null) return null;
        try {
            return FsTree.fromJson(s);
        } catch (Throwable t) {
            mLastError = "tree json: " + t;
            return null;
        }
    }

    /** uiautomator 兼容 XML（给 UiDumpParser）。 */
    public String xml(boolean compressed) {
        return text(FsProtocol.OP_XML, compressed ? "1" : "0");
    }

    public byte[] shotJpeg(int quality, int maxWidth) {
        FsProtocol.Resp r = call(FsProtocol.OP_SHOT, String.valueOf(quality), String.valueOf(maxWidth));
        return r != null && r.ok() ? r.body : null;
    }

    /** 与 screencap（无 -p）同格式的原始帧。 */
    public byte[] shotRaw() {
        FsProtocol.Resp r = call(FsProtocol.OP_SHOT_RAW);
        return r != null && r.ok() ? r.body : null;
    }

    public boolean tap(int x, int y) {
        return text(FsProtocol.OP_TAP, i(x), i(y)) != null;
    }

    /** 无障碍点击 (x,y) 处可点节点；返回 false = 没有可点节点或失败（调用方可退回 {@link #tap}）。 */
    /** ★ mika-plug：按范围点节点或其可点祖先；返回 "clicked:N"（N = 往上找了几层），null = 失败 / 旧助手不支持。 */
    public String clickBounds(int l, int t, int r, int b, String cls) {
        return text(FsProtocol.OP_CLICKB, i(l), i(t), i(r), i(b), cls == null ? "" : cls);
    }

    public boolean click(int x, int y) {
        return text(FsProtocol.OP_CLICK, i(x), i(y)) != null;
    }

    public boolean longPress(int x, int y, int ms) {
        return text(FsProtocol.OP_LONG, i(x), i(y), i(ms)) != null;
    }

    public boolean swipe(int x1, int y1, int x2, int y2, int ms) {
        return text(FsProtocol.OP_SWIPE, i(x1), i(y1), i(x2), i(y2), i(ms)) != null;
    }

    /** @return "scrolled" / "edge" / "swipe:…"；null = 失败 */
    public String scroll(int x, int y, int dir) {
        return text(FsProtocol.OP_SCROLL, i(x), i(y), i(dir));
    }

    public boolean key(int code) {
        return text(FsProtocol.OP_KEY, i(code)) != null;
    }

    /** @return 写入后读回的文字（"set:…"）；null = 失败 */
    public String setText(int x, int y, String text) {
        return text(FsProtocol.OP_SETTEXT, i(x), i(y), text);
    }

    public boolean paste() {
        return text(FsProtocol.OP_PASTE) != null;
    }

    public boolean launch(String pkg) {
        return text(FsProtocol.OP_LAUNCH, pkg) != null;
    }

    /** {"pkg","act","focusWin","ime","inputFocus"}。 */
    public String top() {
        return text(FsProtocol.OP_TOP);
    }

    /** 等界面安静；返回 "idle N" / "busy N"。 */
    public String idle(int idleMs, int timeoutMs) {
        return text(FsProtocol.OP_IDLE, i(idleMs), i(timeoutMs));
    }

    /** 开始观察导航栏返回 / 主页键的真人触摸（返回 "watching …"；null = 不可用）。区域按 {@link NavKeys} 从系统设置推算。 */
    public String backWatch() {
        int[] r = NavKeys.rects(mApp);
        String[] a = new String[8];
        for (int k = 0; k < 8; k++) a[k] = String.valueOf(r[k]);
        return text(FsProtocol.OP_BACKWATCH, a);
    }

    /** "back=N home=M on=…"（取后清零）。 */
    public String takeNavTouches() {
        return text(FsProtocol.OP_BACKS);
    }

    public void backWatchOff() {
        text(FsProtocol.OP_BACKWATCH_OFF);
    }

    /** 回退到 uiautomator 前调用：让助手松开 UiAutomation（否则 uiautomator dump 失败）。没连着就什么都不做。 */
    public void releaseForFallback() {
        synchronized (mLock) {
            if (mSock == null) return;
        }
        text(FsProtocol.OP_RELEASE);
    }

    public void quit() {
        text(FsProtocol.OP_QUIT);
        synchronized (mLock) {
            closeQuiet();
        }
    }

    private static String i(int v) {
        return String.valueOf(v);
    }
}
