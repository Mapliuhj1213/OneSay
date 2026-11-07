package com.onesay.assistant.v2.conn.watch;

/**
 * [conn/watch] 监控任务与 A 线（定时 / 回调）之间的接口桩。B 线只做数据层与判定；<b>什么时候跑</b>、<b>结果怎么送达</b>都归 A 线。
 *
 * <h3>A 线要实现 / 注册的</h3>
 * <pre>
 *   WatchPorts.setTrigger(t)   // 定时：B 在监控增删改 / 每次运行后调 t.schedule(id, nextAt) 或 t.cancel(id)；
 *                              //   A 到点后调 WatchService.runOne(id)（或批量 runDue(now)），在后台线程里
 *   WatchPorts.setSink(s)      // 回调：判定为 NOTIFY 时 B 调 s.onChange(spec, items, message)；
 *                              //   A 按自己的送达策略（岛 / TTS / 通知、免打扰 23–7）决定怎么说
 *                              //   连续失败 ≥ 3 次时 B 调 s.onBroken(spec, error)，A 决定要不要告诉用户「这个监控查不了了」
 * </pre>
 * 权限：建监控时 A 线的权限卡应拆出「联网搜索」（Perm.WEB_SEARCH）；监控只读，不会触发任何有副作用的动作。
 * 没注册时用下面的桩：trigger 什么都不做（只能手动 / 调试命令跑），sink 只打日志。
 */
public final class WatchPorts {

    private WatchPorts() {}

    public interface Trigger {
        /** 安排在 atMillis（墙钟）运行监控 id；同 id 重复调用 = 改期。 */
        void schedule(String watchId, long atMillis);

        void cancel(String watchId);
    }

    public interface Sink {
        /** 有变化，需要告诉用户。message 已拼好（标题 + 来源域名，不含摘要）；items 供 A 做卡片 / 链接。 */
        void onChange(WatchSpec spec, java.util.List<WatchItem> items, String message);

        /** 连续失败（搜索 Key 失效 / 断网太久）。 */
        void onBroken(WatchSpec spec, String error);
    }

    public static final Trigger STUB_TRIGGER = new Trigger() {
        @Override public void schedule(String watchId, long atMillis) { }
        @Override public void cancel(String watchId) { }
    };

    public static final Sink STUB_SINK = new Sink() {
        @Override public void onChange(WatchSpec spec, java.util.List<WatchItem> items, String message) {
            android.util.Log.i("Conn", "[WATCH] 桩：监控 " + spec.id + " 有 " + items.size() + " 条新结果（A 线回调未接入）");
        }

        @Override public void onBroken(WatchSpec spec, String error) {
            android.util.Log.i("Conn", "[WATCH] 桩：监控 " + spec.id + " 连续失败（A 线回调未接入）");
        }
    };

    private static volatile Trigger sTrigger = STUB_TRIGGER;
    private static volatile Sink sSink = STUB_SINK;

    public static Trigger trigger() { return sTrigger; }

    public static Sink sink() { return sSink; }

    public static void setTrigger(Trigger t) { sTrigger = t == null ? STUB_TRIGGER : t; }

    public static void setSink(Sink s) { sSink = s == null ? STUB_SINK : s; }
}
