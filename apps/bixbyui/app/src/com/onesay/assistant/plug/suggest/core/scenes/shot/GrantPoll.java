/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes.shot;

/**
 * ★ fix/shot-1104「授权后自动注册」：存储权限没授予时，截图 A 源 / 免 root 文件监听源按这里的节奏重查权限，
 * 一旦变成已授予就注册（不用重启服务）。Android 8 授予运行时权限不会杀进程，所以只在启动时查一次的话，
 * 用户事后去设置里授权也永远不生效（db1e0ce 真机现象）。
 *
 * <p>节奏：前 20 次每 15 s（头 5 分钟，覆盖「刚点了去授权」），之后每 60 s；亮屏 / 解锁 / 控制台回前台 / 授权页返回
 * 由宿主额外 poke 立刻查一次。{@link #onCheck} 只在「未授予 → 已授予」那一次返回 true。
 */
public final class GrantPoll {
    public static final long FAST_MS = 15000L;
    public static final long SLOW_MS = 60000L;
    public static final int FAST_TRIES = 20;

    private int mTries;
    private boolean mGranted;

    /** 下一次定时重查的间隔。 */
    public synchronized long nextDelayMs() {
        return mTries < FAST_TRIES ? FAST_MS : SLOW_MS;
    }

    /** 记一次检查结果；true = 刚变成已授予（调用方此时注册），之后永远 false。 */
    public synchronized boolean onCheck(boolean granted) {
        if (mGranted) return false;
        mTries++;
        if (!granted) return false;
        mGranted = true;
        return true;
    }

    public synchronized boolean granted() {
        return mGranted;
    }

    public synchronized int tries() {
        return mTries;
    }
}
