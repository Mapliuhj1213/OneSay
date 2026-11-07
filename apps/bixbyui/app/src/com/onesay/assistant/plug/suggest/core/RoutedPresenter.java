/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

/**
 * ★ fix/shot-1104：展示路由。岛模式 → 灵动岛 suggest 卡；<b>非岛模式 → 旧芯片卡</b>（以前直接 SUPPRESSED，用户截图后什么都看不到）。
 * 另外 id 以 {@link #SYS_PREFIX} 开头的卡（宿主自己的系统提示，比如「截图分析需要存储权限 · 去授权」）的按钮 / 超时
 * 不进引擎，交给 {@link SysHandler}。纯 Java，岛 / 芯片卡都是端口，单测用假的。
 */
public final class RoutedPresenter implements Presenter {
    public static final String SYS_PREFIX = "sys:";

    public interface Mode {
        boolean islandMode();
    }

    public interface SysHandler {
        void onSysAction(String id, String actionId);

        void onSysClosed(String id, boolean timeout);
    }

    private final Mode mMode;
    private final Presenter mIsland;
    private final Presenter mFallback;
    private volatile Listener mEngine;
    private volatile SysHandler mSys;
    /** 每张卡是哪一路展示的（撤卡 / 改副标题要发给同一路）。 */
    private final java.util.Map<String, Presenter> mWhere = new java.util.LinkedHashMap<String, Presenter>();

    public RoutedPresenter(Mode mode, Presenter island, Presenter fallback) {
        mMode = mode;
        mIsland = island == null ? Presenter.NONE : island;
        mFallback = fallback == null ? Presenter.NONE : fallback;
        Listener fan = new Listener() {
            @Override public void onAction(String id, String actionId) {
                SysHandler h = mSys;
                if (isSys(id)) {
                    if (h != null) h.onSysAction(id, actionId);
                    return;
                }
                Listener l = mEngine;
                if (l != null) l.onAction(id, actionId);
            }

            @Override public void onTimeout(String id) {
                SysHandler h = mSys;
                if (isSys(id)) {
                    if (h != null) h.onSysClosed(id, true);
                    return;
                }
                Listener l = mEngine;
                if (l != null) l.onTimeout(id);
            }

            @Override public void onSwipedAway(String id) {
                SysHandler h = mSys;
                if (isSys(id)) {
                    if (h != null) h.onSysClosed(id, false);
                    return;
                }
                Listener l = mEngine;
                if (l != null) l.onSwipedAway(id);
            }
        };
        mIsland.setListener(fan);
        mFallback.setListener(fan);
    }

    public static boolean isSys(String id) {
        return id != null && id.startsWith(SYS_PREFIX);
    }

    public void setSysHandler(SysHandler h) {
        mSys = h;
    }

    /** 当前走哪一路（给日志 / 状态）。 */
    public String route() {
        boolean island;
        try {
            island = mMode.islandMode();
        } catch (Throwable t) {
            island = false;
        }
        return island ? "island" : "chip";
    }

    @Override public boolean show(Suggestion s) {
        if (s == null) return false;
        Presenter p = "island".equals(route()) ? mIsland : mFallback;
        boolean ok = p.show(s);
        if (ok) {
            synchronized (mWhere) {
                mWhere.put(s.id, p);
                java.util.Iterator<String> it = mWhere.keySet().iterator();
                while (mWhere.size() > 16) {
                    it.next();
                    it.remove();
                }
            }
        }
        return ok;
    }

    private Presenter where(String id) {
        synchronized (mWhere) {
            Presenter p = mWhere.get(id);
            return p != null ? p : mIsland;
        }
    }

    @Override public void update(String id, String subtitle) {
        where(id).update(id, subtitle);
    }

    @Override public void withdraw(String id) {
        Presenter p;
        synchronized (mWhere) {
            p = mWhere.remove(id);
        }
        (p != null ? p : mIsland).withdraw(id);
    }

    @Override public void setListener(Listener l) {
        mEngine = l;
    }
}
