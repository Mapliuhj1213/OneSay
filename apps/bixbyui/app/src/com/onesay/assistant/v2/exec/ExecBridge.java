package com.onesay.assistant.v2.exec;

/** ★ r2/fast-screen：灵动岛执行按钮 → 当前执行会话（同一时刻最多一个前台执行任务）。 */
public final class ExecBridge {

    private ExecBridge() {}

    public interface Listener {
        void onIslandAction(String id, String action);
    }

    private static volatile Listener sListener;
    private static volatile String sId;

    public static void attach(String id, Listener l) {
        sId = id;
        sListener = l;
    }

    public static void detach(String id) {
        if (id != null && id.equals(sId)) {
            sListener = null;
            sId = null;
        }
    }

    public static String currentId() {
        return sId;
    }

    public static void onIslandAction(String id, String action) {
        Listener l = sListener;
        if (l != null) l.onIslandAction(id, action);
    }
}
