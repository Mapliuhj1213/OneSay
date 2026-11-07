package com.onesay.assistant.v2.sched;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import com.onesay.assistant.v2.kernel.FeatureSwitch;
import com.onesay.assistant.v2.notice.IslandNotice;
import com.onesay.assistant.v2.notice.NoticeAction;
import com.onesay.assistant.v2.notice.NoticeSettings;
import com.onesay.assistant.v2.notice.NoticeSource;
import com.onesay.assistant.v2.router.Invocation;

import org.json.JSONObject;

import java.util.List;
import java.util.TimeZone;
import java.util.regex.Pattern;

/**
 * [sched] 本地日程的 Android 挂钩入口（插件 {@code SchedulePlugin} 全部委托到这里）。
 *
 * <table>
 *   <tr><td>路由</td><td>{@link #route}：UnifiedRouter 接管点（S3 之前）→ 本地回执 ask.reply；{@link #claims}：不进 AgentLoop</td></tr>
 *   <tr><td>提醒</td><td>{@link Source}：NoticeCenter 的拉型来源（kind = CALENDAR，id = sched）；改了日程 {@link #refreshNotices} 立刻重排</td></tr>
 *   <tr><td>播报</td><td>JobsHooks.schedule → {@link SchedBrief}（每日播报 + 「我的事」导出）</td></tr>
 *   <tr><td>界面</td><td>{@link SchedConsole}：控制台「日程」页（今天 / 未来、编辑、删除确认、撤销、导入）</td></tr>
 * </table>
 *
 * <p>开关：prefs {@code bixby_sched}（enabled 默认开；kill-switch {@code sched.off}）；{@code route_local}（默认开）=
 * 日程语音默认走本地；关掉就退回旧路径（AgentLoop → Google 日历 / 系统日历预填页）。Google 日历工具本身一直保留。
 */
public final class SchedHooks {

    private SchedHooks() {}

    static final String TAG = "SCHED";
    public static final String PREFS = "bixby_sched";
    public static final FeatureSwitch SWITCH = new FeatureSwitch(PREFS, "enabled", true, "sched.off");
    public static final String K_LEAD = "lead_min", K_ROUTE_LOCAL = "route_local";
    public static final int DEFAULT_LEAD = 10;
    static final long PURGE_AFTER_MS = 7L * 86400000L;

    private static volatile SchedEngine sEngine;
    private static volatile Source sSource;

    public static boolean enabled(Context ctx) {
        return SWITCH.on(ctx);
    }

    public static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static boolean routeLocal(Context ctx) {
        return prefs(ctx).getBoolean(K_ROUTE_LOCAL, true);
    }

    public static int defaultLead(Context ctx) {
        int v = prefs(ctx).getInt(K_LEAD, DEFAULT_LEAD);
        return v < 0 ? 0 : v > 1440 ? 1440 : v;
    }

    public static void setDefaultLead(Context ctx, int min) {
        prefs(ctx).edit().putInt(K_LEAD, min).apply();
        SchedEngine e = sEngine;
        if (e != null) e.settings.defaultLead = min;
        refreshNotices(ctx);
    }

    public static SchedEngine engine(Context ctx) {
        SchedEngine e = sEngine;
        if (e != null) return e;
        synchronized (SchedHooks.class) {
            if (sEngine == null) {
                Context app = ctx.getApplicationContext();
                com.onesay.assistant.v2.sched.SchedStore st = com.onesay.assistant.v2.planner.PlannerHooks.sched(app, SqliteSchedStore.get(app));   // ★ planner：计划 App 在 → 存计划 App
                SchedEngine n = new SchedEngine(st, TimeZone.getDefault());
                n.settings.defaultLead = defaultLead(app);
                try {
                    int purged = st.purgeDeleted(System.currentTimeMillis() - PURGE_AFTER_MS);
                    if (purged > 0) Log.i(TAG, "[SCHED] 清掉 " + purged + " 条 7 天前软删的日程");
                } catch (Throwable ignored) {
                }
                sEngine = n;
                registerSource(app);
            }
            return sEngine;
        }
    }

    /** 插件 onInit / 服务创建：把本地日程登记成提醒来源（多次调用无副作用）。 */
    public static void registerSource(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            if (sSource == null) sSource = new Source(app);
            com.onesay.assistant.v2.notice.NoticeHooks.register(sSource);
        } catch (Throwable t) {
            Log.w(TAG, "[SCHED] 登记提醒来源失败: " + t.getMessage());
        }
    }

    // ================================================================== 路由

    /** 后台线程。null = 不接管（不是日程的话 / 功能关着 / 默认路由不是本地）。 */
    public static Invocation route(Context ctx, String query) {
        try {
            if (ctx == null || query == null || !enabled(ctx) || !routeLocal(ctx)) return null;
            SchedEngine e = engine(ctx);
            e.settings.defaultLead = defaultLead(ctx);
            loadAskOnce(ctx, e.settings);
            try { e.settings.tableList = com.onesay.assistant.plug.richout.host.RichOutHost.on(); } catch (Throwable ignored) { e.settings.tableList = false; }
            long now = System.currentTimeMillis();
            SchedEngine.Reply r = e.handle(query, now);
            if (r == null) return null;
            Log.i(TAG, "[SCHED] 接管 " + r.kind + (r.eventId > 0 ? " #" + r.eventId : ""));   // 不打标题原文
            if (r.kind == SchedEngine.Kind.CREATED || r.kind == SchedEngine.Kind.UPDATED || r.kind == SchedEngine.Kind.DELETED
                    || r.kind == SchedEngine.Kind.UNDONE) {
                refreshNotices(ctx);
            }
            if (r.prefKey != null && r.prefVal != null) {
                prefs(ctx).edit().putString(r.prefKey, r.prefVal).apply();   // ★ int/1102 问一次记住（设置 › 记忆 › 常用默认 可改）
                Log.i(TAG, "[SCHED] [ASK-ONCE] 记住偏好 " + r.prefKey + "=" + r.prefVal);
            }
            JSONObject a = new JSONObject();
            a.put("text", r.text);
            a.put("query", query.trim());
            a.put("sched", r.kind.name());
            if (r.chips != null && !r.chips.isEmpty()) a.put("chips", new org.json.JSONArray(r.chips));
            return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, query);
        } catch (Throwable t) {
            Log.w(TAG, "[SCHED] route 异常，回落原流程: " + t.getMessage());
            return null;
        }
    }

    /** ★ int/1102 问一次记住：prefs → 引擎设置（没记过 = -1 / null，引擎会问一次）。 */
    static void loadAskOnce(Context ctx, SchedEngine.Settings s) {
        SharedPreferences sp = prefs(ctx);
        int m = -1;
        try { m = Integer.parseInt(sp.getString(SchedEngine.PREF_REMIND_MINUTE, "-1")); } catch (Exception ignored) {}
        s.remindMinute = m >= 0 && m < 1440 ? m : -1;
        String late = sp.getString(SchedEngine.PREF_TODAY_LATE, null);
        s.todayLate = SchedEngine.lateLabel(late) != null ? late : null;
    }

    /** 设置页：改 / 清掉（null）问一次记住的两个偏好。 */
    public static void setAskOnce(Context ctx, String key, String val) {
        SharedPreferences.Editor ed = prefs(ctx).edit();
        if (val == null) ed.remove(key); else ed.putString(key, val);
        ed.apply();
        SchedEngine e = sEngine;
        if (e != null) loadAskOnceFrom(val, key, e.settings);
    }

    private static void loadAskOnceFrom(String val, String key, SchedEngine.Settings s) {
        if (SchedEngine.PREF_REMIND_MINUTE.equals(key)) {
            int m = -1;
            try { m = val == null ? -1 : Integer.parseInt(val); } catch (Exception ignored) {}
            s.remindMinute = m;
        } else if (SchedEngine.PREF_TODAY_LATE.equals(key)) {
            s.todayLate = val;
        }
    }

    /** 后台线程：只判断，不落库。 */
    public static boolean claims(Context ctx, String query) {
        try {
            if (ctx == null || query == null || !enabled(ctx) || !routeLocal(ctx)) return false;
            SchedEngine e = engine(ctx);
            long now = System.currentTimeMillis();
            return e.recentlyHandled(query, now) || e.plan(query, now) != null;
        } catch (Throwable t) {
            return false;
        }
    }

    static final Pattern REF = Pattern.compile("那个|这个|那条|这条");
    static final Pattern REF_SUBJ = Pattern.compile("日程|行程|安排|会|面试|聚餐|课");

    /** 主线程，不读库：「删掉那个会」「把这个日程改到周五」的「那个 / 这个」不是屏幕指代。 */
    public static boolean bypassScreenRef(Context ctx, String command) {
        try {
            if (ctx == null || command == null || !enabled(ctx) || !routeLocal(ctx)) return false;
            if (!REF.matcher(command).find()) return false;
            SchedEngine e = sEngine;
            boolean pending = e != null && e.pendingType(System.currentTimeMillis()) != null;
            return pending || (REF_SUBJ.matcher(command).find() && command.matches(".*(删|取消|改|挪|推迟|提前|换到|调到).*"));
        } catch (Throwable t) {
            return false;
        }
    }

    // ================================================================== 提醒

    /** 日程改了：让 NoticeCenter 立刻按新数据重排（后台线程）。 */
    public static void refreshNotices(final Context ctx) {
        try {
            final Context app = ctx.getApplicationContext();
            registerSource(app);
            if (sSource != null) sSource.dirty = true;
            com.onesay.assistant.v2.util.Threads.start("sched-notice", new Runnable() {
                @Override public void run() {
                    try {
                        com.onesay.assistant.v2.notice.NoticeHooks.onFire(app, "sched-change");
                    } catch (Throwable t) {
                        Log.w(TAG, "[SCHED] 重排提醒失败: " + t.getMessage());
                    }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "[SCHED] refreshNotices: " + t.getMessage());
        }
    }

    /**
     * NoticeCenter 的拉型来源。本地读库很便宜：30 分钟拉一次（覆盖未来 26 小时，提醒的 showAt 进共用闹钟），
     * 改了日程标 dirty 立刻拉。功能关了返回空表（撤下已有的本地日程提醒）。
     */
    static final class Source implements NoticeSource {
        static final long POLL_MS = 30L * 60000L;
        private final Context mApp;
        volatile long lastPoll;
        volatile boolean dirty = true;

        Source(Context app) {
            mApp = app;
        }

        @Override public String id() { return SchedNotices.TAG; }
        @Override public IslandNotice.Source kind() { return IslandNotice.Source.CALENDAR; }
        @Override public String switchKey() { return NoticeSettings.K_SRC_CALENDAR; }

        @Override public long nextPollAt(long now) {
            if (!NoticeSettings.master(mApp)) return -1L;
            if (dirty || lastPoll <= 0) return now;
            return lastPoll + POLL_MS;
        }

        @Override public List<IslandNotice> poll(long now) {
            lastPoll = now;
            dirty = false;
            if (!enabled(mApp)) return new java.util.ArrayList<IslandNotice>();
            SchedEngine e = engine(mApp);
            e.settings.defaultLead = defaultLead(mApp);
            // ★ planner：计划 App 在时由它发系统通知，岛不重复（PlannerDedupe）
            return com.onesay.assistant.v2.planner.PlannerDedupe.island(SchedNotices.plan(e, now), com.onesay.assistant.v2.planner.PlannerLink.get(mApp));
        }

        @Override public boolean onAction(IslandNotice n, NoticeAction a) {
            return false;
        }
    }

    // ================================================================== 面板芯片（★ int/1102：日程卡可撤销 / 可改 / 看今天）

    /** 主线程：日程回执上屏后给芯片；点芯片 = 当作用户说了这句话（走同一条路由）。 */
    public static void onReplyShown(final com.onesay.assistant.v2.ui.FloatingSheetService svc, Invocation inv) {
        try {
            if (svc == null || inv == null || inv.args == null || !inv.args.has("sched")) return;
            final java.util.List<String> labels = inv.args.has("chips") ? toList(inv.args.optJSONArray("chips")) : chipsFor(inv.args.optString("sched"));
            if (labels.isEmpty()) return;
            com.onesay.assistant.v2.ui.FloatingSheetView sheet = svc.getSheetView();
            if (sheet == null) return;
            android.view.View existing = sheet.findViewWithTag(com.onesay.assistant.v2.ui.ActionChipsView.TAG_IN_SHEET);
            com.onesay.assistant.v2.ui.ActionChipsView v;
            if (existing instanceof com.onesay.assistant.v2.ui.ActionChipsView) v = (com.onesay.assistant.v2.ui.ActionChipsView) existing;
            else {
                v = new com.onesay.assistant.v2.ui.ActionChipsView(svc);
                sheet.addView(v, Math.min(2, sheet.getChildCount()), new android.widget.LinearLayout.LayoutParams(
                        android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            final com.onesay.assistant.v2.ui.ActionChipsView fv = v;
            v.setListener(new com.onesay.assistant.v2.ui.ActionChipsView.Listener() {
                @Override public void onChipClicked(int index) {
                    if (index < 0 || index >= labels.size()) return;
                    try { com.onesay.assistant.v2.wake.WakeService.cancelCaptureFromUi(); } catch (Throwable ignored) {}
                    fv.hideChips();
                    svc.onSubmitCommand(labels.get(index));
                }
            });
            v.showChips(labels);
            Log.i(TAG, "[SCHED] 芯片 " + labels);
        } catch (Throwable t) {
            Log.w(TAG, "[SCHED] 芯片异常: " + t.getMessage());
        }
    }

    static java.util.List<String> toList(org.json.JSONArray a) {
        java.util.List<String> l = new java.util.ArrayList<String>();
        if (a != null) for (int i = 0; i < a.length(); i++) l.add(a.optString(i));
        return l;
    }

    /** 纯函数（JVM 单测）：回执种类 → 芯片。 */
    public static java.util.List<String> chipsFor(String kind) {
        java.util.List<String> l = new java.util.ArrayList<String>();
        if ("CREATED".equals(kind)) { l.add("撤销"); l.add("今天有什么安排"); }
        else if ("LISTED".equals(kind)) { l.add("加个日程"); }
        else if ("UPDATED".equals(kind) || "DELETED".equals(kind)) { l.add("撤销"); }
        return l;
    }

    // ================================================================== 状态

    public static String debugStatus(Context ctx) {
        try {
            SchedEngine e = engine(ctx);
            long now = System.currentTimeMillis();
            int n = e.store().all(false).size();
            int today = e.occurrences(CnTime.dayStart(now, e.tz()), CnTime.addDays(CnTime.dayStart(now, e.tz()), 1, e.tz())).size();
            return "enabled=" + enabled(ctx) + " routeLocal=" + routeLocal(ctx) + " lead=" + defaultLead(ctx) + " events=" + n + " today=" + today
                    + " schema=" + e.store().schemaVersion() + " pending=" + e.pendingType(now);
        } catch (Throwable t) {
            return "enabled=" + enabled(ctx) + " err=" + t.getMessage();
        }
    }
}
