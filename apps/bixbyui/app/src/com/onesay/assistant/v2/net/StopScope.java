package com.onesay.assistant.v2.net;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ feat/api-first（2026-11-11）：停止键的「真中断」范围。纯 Java，JVM 可测。
 *
 * <p>原来停止只是置一个取消标志，循环在下一轮之前才看；正在等的网络请求（模型、搜索、读网页）要等到超时才回来，
 * 点屏代理也要等它自己查 stopRequested。现在：任务线程开始时 {@link #bind} 一个范围，网络层（{@link HttpClient}、
 * {@link LlmClient}）在发请求时把「断开这条连接」登记进当前范围；点停止 → {@link #stop} 立刻把登记的连接全断开、
 * 跑其余的中断钩子（宿主登记的点屏代理停止等），被断开的请求按「已取消」返回。
 *
 * <p>范围用普通 {@link ThreadLocal}，只在任务线程（RouteStream / AgentLoop / ExecSingle）里显式 bind：
 * <b>不让子线程继承</b> —— 任务里顺手懒建的线程池 / 常驻线程如果继承了一个已经停掉的范围，之后它发的请求会永远被当成「已停止」。
 * 子线程里的请求（并行搜索等）不在范围内，仍靠取消标志在下一轮前收尾。
 */
public final class StopScope {

    private static final ThreadLocal<StopScope> CURRENT = new ThreadLocal<StopScope>();

    private final String mName;
    private final List<Runnable> mHooks = new ArrayList<Runnable>();
    private volatile boolean mStopped;
    private volatile String mWhy = "";

    public StopScope(String name) {
        mName = name == null ? "" : name;
    }

    /** 当前线程所在的范围（没有 → null）。 */
    public static StopScope current() {
        return CURRENT.get();
    }

    /** 把当前线程放进这个范围（子线程不继承）。 */
    public static void bind(StopScope s) {
        CURRENT.set(s);
    }

    public static void unbind() {
        CURRENT.remove();
    }

    /** 当前线程所在范围是否已停止（没有范围 = 没停）。 */
    public static boolean currentStopped() {
        StopScope s = CURRENT.get();
        return s != null && s.mStopped;
    }

    public String name() {
        return mName;
    }

    public boolean isStopped() {
        return mStopped;
    }

    public String why() {
        return mWhy;
    }

    /**
     * 登记一个中断钩子。已经停了 → 立刻执行并返回空操作。
     * 返回值：用完后调用它注销（请求正常结束时注销，免得停止时去断一条已经还回连接池的连接）。
     */
    public Runnable register(final Runnable abort) {
        if (abort == null) return NOOP;
        synchronized (mHooks) {
            if (!mStopped) {
                mHooks.add(abort);
                return new Runnable() {
                    @Override public void run() {
                        synchronized (mHooks) {
                            mHooks.remove(abort);
                        }
                    }
                };
            }
        }
        safeRun(abort);
        return NOOP;
    }

    /** 停止：置标志 + 依次执行全部钩子（每个只执行一次，钩子异常不影响后面的）。幂等。 */
    public void stop(String why) {
        List<Runnable> run;
        synchronized (mHooks) {
            if (mStopped) return;
            mStopped = true;
            mWhy = why == null ? "" : why;
            run = new ArrayList<Runnable>(mHooks);
            mHooks.clear();
        }
        for (Runnable r : run) safeRun(r);
    }

    /** 测试 / 调试：还挂着几个钩子。 */
    public int pending() {
        synchronized (mHooks) {
            return mHooks.size();
        }
    }

    private static void safeRun(Runnable r) {
        try {
            r.run();
        } catch (Throwable ignored) {
        }
    }

    static final Runnable NOOP = new Runnable() {
        @Override public void run() {
        }
    };

    /**
     * 任务线程的入口包一层：运行期间 bind 到 s，结束（含异常）解绑。s 为 null → 原样运行。
     * FloatingSheetService 的 RouteStream / AgentLoop / ExecSingle 三个线程用它（子线程仍不继承）。
     */
    public static Runnable wrap(final StopScope s, final Runnable r) {
        return new Runnable() {
            @Override public void run() {
                StopScope prev = CURRENT.get();
                if (s != null) CURRENT.set(s);
                try {
                    r.run();
                } finally {
                    if (prev == null) CURRENT.remove();
                    else CURRENT.set(prev);
                }
            }
        };
    }

    /** 当前范围登记一个钩子（没有范围 → 空操作）。 */
    public static Runnable onStop(Runnable abort) {
        StopScope s = CURRENT.get();
        return s == null ? NOOP : s.register(abort);
    }
}
