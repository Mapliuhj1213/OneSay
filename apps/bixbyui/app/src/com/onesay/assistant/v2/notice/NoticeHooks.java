package com.onesay.assistant.v2.notice;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.IslandHost;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 灵动岛消息提醒的 Android 胶水：
 * <ul>
 *   <li>持有唯一的 {@link NoticeCenter}（jobs.db 的 island_notice 表）；面板服务起来时恢复（被杀 / 重启后未过期的提醒回来）；</li>
 *   <li>到点：和 S3 / A 线<b>共用一个闹钟</b>（{@code S3Scheduler.reschedule} 取 {@link #earliestNext}）；{@code S3Runner.fireDueLocked} 末尾调 {@link #onFire}；</li>
 *   <li>来源：回调中枢（{@link #postCallback}，推）、日程（{@link CalendarSource}，拉）、定时邮件（{@link MailSource}，拉）+ 扩展（{@link #register}）；</li>
 *   <li>上岛：经内核通道 {@code FeatureKernel.island("notice")}；岛没开时日程 / 邮件提醒退回一条系统通知；</li>
 *   <li>动作：中心改状态，这里执行 {@link NoticeCenter.Outcome}。<b>邮件动作只会打开确认卡 / 看任务 / 推迟 / 取消，没有发送路径。</b></li>
 * </ul>
 */
public final class NoticeHooks {

    private NoticeHooks() {}

    static final String TAG = "Notice";
    public static final String OWNER = "notice";

    private static volatile NoticeCenter sCenter;
    private static final List<NoticeSource> EXTRA = new CopyOnWriteArrayList<NoticeSource>();
    private static volatile CalendarSource sCal;
    private static volatile MailSource sMail;
    private static volatile boolean sPolling;

    /** 调试注入（只由 debug 包的 NoticeTestDriver 设置）：强制免打扰 / 通话中。 */
    public static volatile Boolean sQuietOverride, sCallOverride;

    // ================================================================== 中枢 / 环境

    public static NoticeCenter center(Context ctx) {
        NoticeCenter c = sCenter;
        if (c != null) return c;
        synchronized (NoticeHooks.class) {
            if (sCenter == null) {
                Context app = ctx.getApplicationContext();
                NoticeStore store;
                try {
                    store = new SqliteNoticeStore(com.onesay.assistant.v2.jobs.SqliteJobStore.get(app, com.onesay.assistant.v2.jobs.SqliteJobStore.DB_PROD));
                } catch (Throwable t) {
                    Log.w(TAG, "[NOTICE] 打不开 jobs.db，提醒只存内存: " + t.getMessage());
                    store = new NoticeStore.Mem();
                }
                sCenter = new NoticeCenter(store, new NoticeCenter.Clock() {
                    @Override public long now() { return System.currentTimeMillis(); }
                });
            }
            return sCenter;
        }
    }

    /** 中枢已经建过（面板服务起来过）。 */
    public static boolean started() {
        return sCenter != null;
    }

    static NoticeCenter restored(Context ctx) {
        NoticeCenter c = center(ctx);
        if (!c.restored()) {
            NoticeCenter.Tick t = c.restore(env(ctx));
            Log.i(TAG, "[NOTICE] 从库恢复 " + c.active().size() + " 条在排提醒（撤下 " + t.gone.size() + " 条过期）");
        }
        return c;
    }

    public static NoticePolicy.Env env(Context ctx) {
        Context app = ctx.getApplicationContext();
        NoticePolicy.Env e = new NoticePolicy.Env();
        e.master = NoticeSettings.master(app);
        if (!NoticeSettings.source(app, NoticeSettings.K_SRC_CALLBACK)) e.disabled.add(IslandNotice.Source.CALLBACK);
        if (!NoticeSettings.source(app, NoticeSettings.K_SRC_CALENDAR)) e.disabled.add(IslandNotice.Source.CALENDAR);
        if (!NoticeSettings.source(app, NoticeSettings.K_SRC_MAIL)) e.disabled.add(IslandNotice.Source.MAIL);
        // 新来源各自的开关
        if (!NoticeSettings.flag(app, NoticeSettings.K_SRC_PRIVACY)) e.disabled.add(IslandNotice.Source.PRIVACY);
        if (!NoticeSettings.flag(app, NoticeSettings.K_SRC_CALL)) e.disabled.add(IslandNotice.Source.CALL);
        if (!NoticeSettings.flag(app, NoticeSettings.K_SRC_NOTIFY)) e.disabled.add(IslandNotice.Source.NOTIFY);
        if (!NoticeSettings.flag(app, NoticeSettings.K_SRC_SYSTEM)) e.disabled.add(IslandNotice.Source.SYSTEM);
        if (!NoticeSettings.flag(app, NoticeSettings.K_SRC_ERRAND)) e.disabled.add(IslandNotice.Source.ERRAND);
        e.quietExemptSources = java.util.EnumSet.noneOf(IslandNotice.Source.class);
        if (NoticeSettings.flag(app, NoticeSettings.K_QUIET_PRIVACY)) e.quietExemptSources.add(IslandNotice.Source.PRIVACY);
        if (NoticeSettings.flag(app, NoticeSettings.K_QUIET_CALL)) e.quietExemptSources.add(IslandNotice.Source.CALL);
        for (NoticeSource s : EXTRA) {
            if (s.switchKey() != null && !NoticeSettings.prefs(app).getBoolean(s.switchKey(), true)) e.disabledTags.add(s.id());
        }
        e.quietHighCalendar = NoticeSettings.quietHighCalendar(app);
        e.tz = TimeZone.getDefault();
        try {
            // A 线：通话 / 勿扰 / 免打扰时段同一套判断与设置
            com.onesay.assistant.v2.callback.DeliveryPolicy.Env a = com.onesay.assistant.v2.callback.CallbackHub.env(app);
            e.inCall = a.inCall;
            e.dnd = a.dnd;
            e.quietEnabled = a.quietEnabled;
            e.quietStartMin = a.quietStartMin;
            e.quietEndMin = a.quietEndMin;
        } catch (Throwable ignored) {
        }
        if (sCallOverride != null) e.inCall = sCallOverride;
        e.quietOverride = sQuietOverride;
        return e;
    }

    public static IslandHost.Channel island() {
        return FeatureKernel.get().island(OWNER);
    }

    // ================================================================== 来源

    /** 扩展接口：注册一个新来源（拉型来源会在每次 {@link #onFire} 时按 nextPollAt 被调用）。 */
    public static void register(NoticeSource s) {
        if (s == null) return;
        for (NoticeSource x : EXTRA) if (x.id().equals(s.id())) EXTRA.remove(x);
        EXTRA.add(s);
    }

    static List<NoticeSource> sources(Context ctx) {
        Context app = ctx.getApplicationContext();
        if (sCal == null) sCal = new CalendarSource(app);
        if (sMail == null) sMail = new MailSource(app);
        List<NoticeSource> l = new ArrayList<NoticeSource>();
        l.add(sCal);
        l.add(sMail);
        l.addAll(EXTRA);
        return l;
    }

    static NoticeSource sourceByTag(Context ctx, String tag) {
        for (NoticeSource s : sources(ctx)) if (s.id().equals(tag)) return s;
        return null;
    }

    // ================================================================== 推

    /** 任意来源推一条（任意线程）。 */
    public static NoticeCenter.PostResult post(Context ctx, IslandNotice n) {
        NoticeCenter c = restored(ctx);
        NoticeCenter.Tick t = new NoticeCenter.Tick();
        NoticeCenter.PostResult r = c.post(n, env(ctx), t);
        Log.i(TAG, "[NOTICE] post " + n.dedupKey + " → " + r);
        present(ctx, t);
        reschedule(ctx, "post");
        return r;
    }

    /** 回调中枢的岛渠道（A 线 CallbackHub.deliver）：走提醒队列。返回 false = 提醒总开关 / 回调来源关着，调用方用旧的岛活动。 */
    public static boolean postCallback(Context ctx, String id, String title, String text, com.onesay.assistant.v2.callback.DeliveryPolicy.Urgency u) {
        Context app = ctx.getApplicationContext();
        if (!NoticeSettings.master(app) || !NoticeSettings.source(app, NoticeSettings.K_SRC_CALLBACK)) return false;
        IslandNotice n = new IslandNotice(IslandNotice.Source.CALLBACK, "cb:" + id,
                u == com.onesay.assistant.v2.callback.DeliveryPolicy.Urgency.HIGH ? IslandNotice.Priority.HIGH : IslandNotice.Priority.NORMAL,
                title == null || title.length() == 0 ? "定时任务" : title, text);
        n.sourceTag = "callback";
        n.action(new NoticeAction("回调历史", NoticeAction.Kind.OPEN_PAGE, "callback.history"));
        post(app, n);
        return true;
    }

    /** 定时任务到点存好草稿、等确认（OutboundMailHooks）：岛上一条「草稿待确认」。返回 false = 提醒关着，调用方走旧逻辑。 */
    public static boolean postDraftPending(Context ctx, String sessionId, String recipient, String subject) {
        Context app = ctx.getApplicationContext();
        if (!NoticeSettings.master(app) || !NoticeSettings.source(app, NoticeSettings.K_SRC_MAIL)) return false;
        post(app, ScheduledMailNotices.draftPending(sessionId, recipient, subject, System.currentTimeMillis()));
        return true;
    }

    // ================================================================== 生命周期 / 到点

    /** 面板服务 onCreate（S3Runner 之后）：登记岛回调、从库恢复、上岛（不展开）、拉一次来源。 */
    public static void onServiceCreate(final Context ctx) {
        final Context app = ctx.getApplicationContext();
        try {
            island().setNoticeListener(LISTENER_FOR(app));
            NoticeCenter c = restored(app);
            present(app, null);
            Log.i(TAG, "[NOTICE] 启动：" + NoticeSettings.dump(app) + " 在排 " + c.active().size());
            pollAsync(app, true, "service-create");
        } catch (Throwable t) {
            Log.w(TAG, "[NOTICE] 启动异常（不影响面板）: " + t.getMessage());
        }
        try {
            // 灵动岛智能化：麦克风 / 相机、通话、系统状态的回调与广播（通知筛选经 EventNotificationListener）
            IslandSmartHooks.start(app);
        } catch (Throwable t) {
            Log.w(TAG, "[NOTICE] 智能来源启动异常（不影响面板）: " + t.getClass().getSimpleName());
        }
    }

    /** S3Runner.fireDueLocked 末尾（后台线程）：拉到点的来源、tick、上岛。 */
    public static void onFire(Context ctx, String why) {
        Context app = ctx.getApplicationContext();
        try {
            NoticeCenter c = restored(app);
            NoticeCenter.Tick t = c.tick(env(app));
            present(app, t);
            pollAsync(app, false, why);
        } catch (Throwable t) {
            Log.w(TAG, "[NOTICE] 到点处理异常: " + t.getMessage());
        }
    }

    /** S3 任务库改了（新建 / 删除 / 改时间 / 推迟）：重扫定时邮件。 */
    public static void onTasksChanged(Context ctx) {
        final Context app = ctx.getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("notice-mail", new Runnable() {
            @Override public void run() {
                syncSource(app, sources(app).get(1));
                reschedule(app, "s3-change");
            }
        });
    }

    /** 共用闹钟要在什么时候叫醒我们（-1 = 不需要）。 */
    public static long earliestNext(Context ctx) {
        try {
            Context app = ctx.getApplicationContext();
            if (!NoticeSettings.master(app)) return -1L;
            long now = System.currentTimeMillis();
            long best = restored(app).nextWakeAt();
            for (NoticeSource s : sources(app)) best = NoticeCenter.min(best, s.nextPollAt(now));
            return best > 0 ? Math.max(best, now + 1000L) : -1L;
        } catch (Throwable t) {
            return -1L;
        }
    }

    static void reschedule(Context ctx, String why) {
        try {
            com.onesay.assistant.v2.s3.S3Scheduler.reschedule(ctx.getApplicationContext(), "notice-" + why);
        } catch (Throwable ignored) {
        }
    }

    /** 拉型来源（网络在后台线程；同一时刻只跑一轮）。force = 不看 nextPollAt。 */
    static void pollAsync(final Context app, final boolean force, final String why) {
        if (sPolling) return;
        sPolling = true;
        com.onesay.assistant.v2.util.Threads.start("notice-poll", new Runnable() {
            @Override public void run() {
                try {
                    long now = System.currentTimeMillis();
                    for (NoticeSource s : sources(app)) {
                        long at = s.nextPollAt(now);
                        if (at < 0 || (!force && at > now)) continue;
                        syncSource(app, s);
                    }
                } finally {
                    sPolling = false;
                    reschedule(app, "poll(" + why + ")");
                }
            }
        });
    }

    /** ★ feat/rain-minutely：立刻同步一个扩展来源（后台线程跑；开关翻转 / 亮屏 / 来源拿到新数据时用），之后重排共用闹钟。 */
    public static void syncNow(Context ctx, final String sourceId) {
        final Context app = ctx.getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("notice-sync-" + sourceId, new Runnable() {
            @Override public void run() {
                try {
                    NoticeSource s = sourceByTag(app, sourceId);
                    if (s == null) return;
                    syncSource(app, s);
                } catch (Throwable t) {
                    Log.w(TAG, "[NOTICE] 同步 " + sourceId + " 异常: " + t.getClass().getSimpleName());
                } finally {
                    reschedule(app, "sync(" + sourceId + ")");
                }
            }
        });
    }

    static void syncSource(Context app, NoticeSource s) {
        List<IslandNotice> fresh = null;
        try {
            fresh = s.poll(System.currentTimeMillis());
        } catch (NoticeSource.Unavailable u) {
            // 连接器缺失 / 未登录 / 授权失效：撤下这个来源的提醒（BUG-I1）
            withdrawUnavailable(app, s, u.getMessage());
            return;
        } catch (Throwable t) {
            // 普通失败（网络 / 超时 / 解析）：保留已有提醒，来源自己 2 分钟后重试（BUG-I1 收窄）
            Log.i(TAG, "[NOTICE] 来源 " + s.id() + " 这次拿不到（静默，保留已有提醒）: " + t.getClass().getSimpleName());
            return;
        }
        if (fresh == null) {
            // 普通失败 / 来源关着：什么都不动（开关关着由 tick 按「来源已关闭」撤）
            if (s instanceof NoticeSource.WithdrawWhenUnavailable && center(app).liveCount(s.kind(), s.id()) > 0) {
                Log.i(TAG, "[NOTICE] 来源 " + s.id() + " 这次读不到（不是连接器缺失 / 授权失效）→ 保留 "
                        + center(app).liveCount(s.kind(), s.id()) + " 条已有提醒，稍后重试");
            }
            return;
        }
        NoticeCenter.Tick t = restored(app).sync(s.kind(), s.id(), fresh, env(app));
        if (t.changed()) Log.i(TAG, "[NOTICE] 来源 " + s.id() + " 同步 " + fresh.size() + " 条：新上岛 " + t.surfaced.size() + "，撤下 " + t.gone.size());
        present(app, t);
    }

    /**
     * BUG-I1：连接器缺失 / 未登录 / 授权失效的来源（poll 抛 {@link NoticeSource.Unavailable}；实现了 {@link NoticeSource.WithdrawWhenUnavailable}，目前是日程）——
     * 撤下这个来源所有没处理的提醒（没展示的 + 已在岛上的），别的来源不动。来源开关关着时不归这里管（tick 按「来源已关闭」撤）。
     * 不弹错误、不发通知，只记一行日志。
     */
    static void withdrawUnavailable(Context app, NoticeSource s, String why) {
        if (!(s instanceof NoticeSource.WithdrawWhenUnavailable)) return;
        NoticePolicy.Env env = env(app);
        if (!NoticePolicy.sourceOn(env, s.kind(), s.id())) return;
        NoticeCenter c = restored(app);
        int live = c.liveCount(s.kind(), s.id());
        if (live == 0) return;
        NoticeCenter.Tick t = c.withdrawSource(s.kind(), s.id(), NoticeCenter.NOTE_SOURCE_LOST);
        Log.i(TAG, "[NOTICE] 来源 " + s.id() + " 不可用（" + why + "）→ 撤下 " + live + " 条（其中岛上 " + t.gone.size() + " 条），别的来源不动");
        present(app, t);
        reschedule(app, "withdraw(" + s.id() + ")");
    }

    // ================================================================== 上岛

    static String metaOf(IslandNotice n) {
        switch (n.source) {
            case CALENDAR: return "日程";
            case MAIL: return "邮件";
            case CALLBACK: return "定时任务";
            case PRIVACY: return "隐私";
            case CALL: return "通话";
            case NOTIFY: return n.sourceTag.length() > 0 ? n.sourceTag : "通知";
            case SYSTEM: return "系统";
            case ERRAND: return "进度";
            case WEATHER: return "天气";
            default: return n.sourceTag.length() > 0 ? n.sourceTag : "提醒";
        }
    }

    public static List<IslandModel.NoticeRow> rows(List<IslandNotice> active) {
        List<IslandModel.NoticeRow> rows = new ArrayList<IslandModel.NoticeRow>();
        for (IslandNotice n : active) {
            int keep = 0;
            for (int i = 0; i < n.actions.size(); i++) if (n.actions.get(i).kind == NoticeAction.Kind.INLINE) keep |= 1 << i;
            IslandModel.NoticeRow row = new IslandModel.NoticeRow(n.dedupKey, n.title, n.summary, metaOf(n), n.priority == IslandNotice.Priority.HIGH,
                    n.actionLabels(), n.chronoBase, n.countdown, n.tone, keep);
            row.bars = n.bars == null ? "" : n.bars;   // ★ feat/rain-minutely
            rows.add(row);
        }
        return rows;
    }

    /** 把中心的队列交给岛（t = 本次的变化；null = 只刷新、不展开）。 */
    static void present(Context ctx, NoticeCenter.Tick t) {
        Context app = ctx.getApplicationContext();
        NoticeCenter c = center(app);
        List<IslandNotice> act = c.active();
        IslandHost.Channel ch = island();
        boolean islandOk = ch.ready() && ch.islandMode();
        if (!NoticeSettings.master(app)) {
            ch.cleanupNotices();
            return;
        }
        if (islandOk) {
            ch.setNotices(rows(act), t != null && t.alert, t != null && t.high);
        } else if (t != null) {
            // 岛没开（island.off / 没有悬浮窗权限）：日程 / 邮件提醒退回系统通知（回调中枢自己有通知，不重复）
            for (IslandNotice n : t.surfaced) {
                if (n.source == IslandNotice.Source.CALLBACK) continue;
                com.onesay.assistant.v2.callback.CallbackHub.notify(app, 0x5D000000 | (n.dedupKey.hashCode() & 0xFFFFFF), n.title, n.summary);
            }
        }
    }

    // ================================================================== 岛上操作

    static IslandHost.NoticeListener LISTENER_FOR(final Context app) {
        return new IslandHost.NoticeListener() {
            @Override public void onNoticeDismiss(String id) {
                NoticeCenter.Tick t = new NoticeCenter.Tick();
                restored(app).dismiss(id, t);
                present(app, null);
                reschedule(app, "dismiss");
            }

            @Override public void onNoticeDismissAll() {
                restored(app).dismissAll(new NoticeCenter.Tick());
                present(app, null);
                reschedule(app, "dismissAll");
            }

            @Override public void onNoticeAction(String id, int index) {
                NoticeCenter.Outcome o = restored(app).act(id, index, new NoticeCenter.Tick());
                Log.i(TAG, "[NOTICE] 动作 " + id + "#" + index + " → " + o);
                present(app, null);
                execute(app, o);
                reschedule(app, "action");
            }
        };
    }

    /**
     * 执行动作（主线程调用；耗时的放后台）。
     * <b>安全</b>：这里不调用 OutboundMail.confirmSend / cardToken / MailConfirmTokens / sendDraft / ExecutionGate.approve /
     * UnifiedExecutor —— 单测扫描本包源码断言。邮件只能在确认卡上点「发送」。
     */
    public static void execute(final Context app, final NoticeCenter.Outcome o) {
        if (o == null || o.kind == null) return;
        try {
            switch (o.kind) {
                case OPEN_URL: {
                    if (!NoticeAction.safeUrl(o.arg)) return;
                    Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(o.arg.trim()));
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(it);
                    return;
                }
                case OPEN_PAGE:
                    openConsole(app, o.arg, o.action == null ? "" : o.action.label);
                    return;
                case VIEW_TASK:
                    openConsole(app, "s3.tasks", "定时任务");
                    return;
                case OPEN_MAIL_CARD:
                    // 只打开确认卡：令牌由卡片渲染时自己签发，发送只在卡上点「发送」
                    com.onesay.assistant.v2.callback.OutboundMailHooks.show(app, o.arg);
                    return;
                case POSTPONE_MAIL:
                case CANCEL_MAIL:
                    com.onesay.assistant.v2.util.Threads.start("notice-mail-act", new Runnable() {
                        @Override public void run() {
                            mailAction(app, o);
                        }
                    });
                    return;
                case CUSTOM:
                case INLINE: {
                    NoticeSource s = o.notice == null ? null : sourceByTag(app, o.notice.sourceTag);
                    if (s != null) s.onAction(o.notice, o.action);
                    return;
                }
                case COPY_TEXT:
                    // 验证码「复制」：只在用户点了以后写剪贴板；2 分钟后剪贴板里还是它就清掉
                    IslandSmartHooks.copyToClipboard(app, o.arg);
                    return;
                case OPEN_APP:
                    if (!NoticeAction.safePackage(o.arg)) return;
                    IslandSmartHooks.openApp(app, o.arg);
                    return;
                default:
            }
        } catch (Throwable t) {
            Log.w(TAG, "[NOTICE] 执行动作失败 " + o + ": " + t.getMessage());
        }
    }

    /** 推迟 / 取消：只改 S3 这一次的计划，或删「待确认」的草稿。不起草、不发送、不提前执行任务。 */
    static void mailAction(Context app, NoticeCenter.Outcome o) {
        long now = System.currentTimeMillis();
        if (o.kind == NoticeAction.Kind.CANCEL_MAIL && o.arg.startsWith("draft:")) {
            com.onesay.assistant.v2.callback.OutboundMail.Session s = com.onesay.assistant.v2.callback.OutboundMail.cancel(o.arg.substring(6),
                    com.onesay.assistant.v2.conn.port.Ports.mailDraft());
            Log.i(TAG, "[NOTICE] 草稿待确认 → 取消（删草稿）" + (s == null ? "：会话已不在" : "：" + s.state));
            return;
        }
        long[] occ = ScheduledMailNotices.parseOccurrence(ScheduledMailNotices.occurrenceOf(o.arg));
        if (occ == null) return;
        com.onesay.assistant.v2.s3.S3Engine e = com.onesay.assistant.v2.s3.S3Hooks.engine(app);
        boolean ok;
        if (o.kind == NoticeAction.Kind.POSTPONE_MAIL) {
            int min = ScheduledMailNotices.postponeMinutes(o.arg);
            ok = e.postpone(occ[0], occ[1], min * 60000L, now);
            Log.i(TAG, "[NOTICE] 定时邮件 task#" + occ[0] + " 推迟 " + min + " 分钟 → " + (ok ? "新时刻 " + new Date(occ[1] + min * 60000L) : "没改（计划已变）"));
        } else {
            ok = e.skipOccurrence(occ[0], occ[1], now);
            Log.i(TAG, "[NOTICE] 定时邮件 task#" + occ[0] + " 取消这次 → " + ok);
        }
        syncSource(app, sources(app).get(1));
        reschedule(app, "mail-action");
    }

    static void openConsole(Context app, String page, String title) {
        try {
            Intent it = new Intent(app, com.onesay.assistant.v2.ui.MainActivity.class);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            it.putExtra("page", "console:" + page);
            it.putExtra("title", title);
            it.putExtra(com.onesay.assistant.v2.ui.ConsoleDeepLink.EXTRA_TOKEN, com.onesay.assistant.v2.ui.ConsoleDeepLink.token());   // ★ IPC-3：内部深链口令
            app.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "[NOTICE] 打开控制台失败: " + t.getMessage());
        }
    }

    // ================================================================== 状态（调试 / 控制台）

    public static String status(Context ctx) {
        Context app = ctx.getApplicationContext();
        NoticeCenter c = restored(app);
        StringBuilder sb = new StringBuilder(NoticeSettings.dump(app));
        sb.append(" | next=").append(earliestNext(app));
        for (IslandNotice n : c.all()) {
            sb.append("\n  ").append(n.state).append(' ').append(n.dedupKey).append(' ').append(n.priority)
                    .append(" show=").append(n.showAt > 0 ? new Date(n.showAt).toString() : "0")
                    .append(" exp=").append(new Date(n.expireAt)).append(n.heldUntil > 0 ? " held→" + new Date(n.heldUntil) : "")
                    .append(n.note.length() > 0 ? " (" + n.note + ")" : "");
        }
        return sb.toString();
    }

    /** 调试：模拟进程被杀后重启（丢掉内存，按库恢复）。 */
    public static void simulateRestart(Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        Context app = ctx.getApplicationContext();
        center(app).dropMemory();
        restored(app);
        present(app, null);
    }

    /** 调试注入：一条假日程（走真实的日程来源流水线：提前量 / 去重 / 优先级 / 会议链接），不碰网络。 */
    public static void injectFakeEvent(Context ctx, CalendarNotices.Event e) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        CalendarSource.FAKE.add(e);
        Context app = ctx.getApplicationContext();
        sources(app);
        syncSource(app, sCal);
        reschedule(app, "fake-event");
    }

    /** 调试（只 debug）：模拟日程来源拉取失败。kind = NET（网络失败 / 超时，不撤）/ AUTH（授权失效，撤）/ null（取消模拟）；随后立刻拉一次。 */
    public static String simulateCalendarFailure(Context ctx, String kind) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "只在 debug 包可用";
        Context app = ctx.getApplicationContext();
        CalendarSource.sFailSim = kind;
        sources(app);
        syncSource(app, sCal);
        reschedule(app, "calfail");
        return "calfail=" + kind;
    }

    public static void clearFakeEvents(Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        CalendarSource.FAKE.clear();
        Context app = ctx.getApplicationContext();
        sources(app);
        syncSource(app, sCal);
    }

    /**
     * 设置改了（开关 / 提前量）：tick + 所有来源立刻重拉（不等轮询间隔）。
     * BUG-I3：开关重开时 tick 先把关之前在排、没过期的提醒从库里放回来（{@link NoticeCenter#NOTE_SWITCHED_OFF}），
     * 拉型来源（日程 / 定时邮件）随后重拉一次核对（任务删了 / 日程改了的照常撤下）。
     */
    public static void repoll(Context ctx) {
        Context app = ctx.getApplicationContext();
        try {
            present(app, restored(app).tick(env(app)));
        } catch (Throwable ignored) {
        }
        pollAsync(app, true, "repoll");
    }

    /** 调试：只扫一次定时邮件。 */
    public static void rescanMail(Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        Context app = ctx.getApplicationContext();
        syncSource(app, sources(app).get(1));
        reschedule(app, "rescan");
    }

    /** 调试：立即 tick（到点 / 过期 / 免打扰结束）并上岛。 */
    public static void tickNow(Context ctx) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        Context app = ctx.getApplicationContext();
        present(app, restored(app).tick(env(app)));
        reschedule(app, "tick");
    }

    /** 调试：模拟岛上的操作（与点岛一样走监听器）。 */
    public static IslandHost.NoticeListener listener(Context ctx) {
        return LISTENER_FOR(ctx.getApplicationContext());
    }

    // ================================================================== 内置拉型来源

    /**
     * 日程：读 B 线 Google Calendar 连接器。都静默（不报错、不发通知）：
     * 没配置 / 没登录 / 没日历权限 / 授权失效 → 抛 {@link NoticeSource.Unavailable}（撤下已有日程提醒，BUG-I1）；
     * 网络不可达 / 超时 / 远端错误 → 返回 null（保留已有提醒，2 分钟后重试）。
     */
    static final class CalendarSource implements NoticeSource, NoticeSource.WithdrawWhenUnavailable {
        static final long POLL_MS = 20L * 60000L;
        /** 上一次拉取失败（BUG-I1：失败时提醒已撤下）→ 2 分钟后再试，恢复了尽快放回来。 */
        static final long RETRY_MS = 2L * 60000L;
        static final long WINDOW_MS = 90L * 60000L;
        private final Context mApp;
        private volatile long mLastPoll;
        private volatile boolean mLastFailed;
        /** 调试注入的假事件（NoticeTestDriver），有就不走网络。 */
        static final List<CalendarNotices.Event> FAKE = new CopyOnWriteArrayList<CalendarNotices.Event>();
        /** 调试：模拟拉取失败（只 debug，NoticeTestDriver calfail）。null = 不模拟；NET = 网络失败 / 超时；AUTH = 授权失效。 */
        static volatile String sFailSim;

        CalendarSource(Context app) {
            mApp = app;
        }

        @Override public String id() { return CalendarNotices.TAG; }
        @Override public IslandNotice.Source kind() { return IslandNotice.Source.CALENDAR; }
        @Override public String switchKey() { return NoticeSettings.K_SRC_CALENDAR; }

        @Override public long nextPollAt(long now) {
            if (!NoticeSettings.master(mApp) || !NoticeSettings.source(mApp, NoticeSettings.K_SRC_CALENDAR)) return -1L;
            if (FAKE.isEmpty() && sFailSim == null && !connectorReady()) {
                // 没配置连接器：不叫醒、不轮询。BUG-I1：但岛上 / 队列里还有日程提醒（连接器是后来掉的：退出登录、关了连接器）
                // → 立刻拉一次，poll 返回 null，撤下这些提醒；撤完 liveCount = 0，回到不叫醒。
                return center(mApp).liveCount(IslandNotice.Source.CALENDAR, id()) > 0 ? now : -1L;
            }
            return mLastPoll <= 0 ? now : mLastPoll + (mLastFailed ? RETRY_MS : POLL_MS);
        }

        boolean connectorReady() {
            try {
                if (!com.onesay.assistant.v2.conn.ConnSettings.SWITCH.on(mApp)) return false;
                com.onesay.assistant.v2.conn.google.GoogleAuth a = com.onesay.assistant.v2.conn.google.GoogleHooks.auth(mApp);
                return a.configured() && a.signedIn() && a.hasScope(com.onesay.assistant.v2.conn.google.GoogleScopes.CALENDAR_EVENTS);
            } catch (Throwable t) {
                return false;
            }
        }

        @Override public List<IslandNotice> poll(long now) throws Exception {
            mLastPoll = now;
            mLastFailed = true;   // 走到成功返回才清掉（抛异常 / 返回 null 都算失败）
            if (!NoticeSettings.source(mApp, NoticeSettings.K_SRC_CALENDAR)) return null;
            int lead = NoticeSettings.calLead(mApp);
            TimeZone tz = TimeZone.getDefault();
            String sim = com.onesay.assistant.v2.BuildVariant.IS_DEBUG ? sFailSim : null;
            if ("NET".equals(sim)) {
                Log.i(TAG, "[NOTICE] 日历这次读不到（TIMEOUT，调试模拟），静默跳过");
                return null;
            }
            if ("AUTH".equals(sim)) throw new NoticeSource.Unavailable("AUTH_REVOKED（调试模拟）");
            if (!FAKE.isEmpty()) {
                mLastFailed = false;
                return CalendarNotices.plan(new ArrayList<CalendarNotices.Event>(FAKE), lead, now, tz);
            }
            if (!connectorReady()) throw new NoticeSource.Unavailable("连接器缺失 / 未登录");   // 已有的日程提醒由 syncSource 撤下（BUG-I1）
            com.onesay.assistant.v2.conn.google.GoogleApi api = com.onesay.assistant.v2.conn.google.GoogleHooks.api(mApp);
            String url = "https://www.googleapis.com/calendar/v3/calendars/primary/events?singleEvents=true&orderBy=startTime&maxResults=20"
                    + "&timeMin=" + java.net.URLEncoder.encode(com.onesay.assistant.v2.conn.google.GTime.rfc3339(now - CalendarNotices.STARTED_GRACE_MS, tz), "UTF-8")
                    + "&timeMax=" + java.net.URLEncoder.encode(com.onesay.assistant.v2.conn.google.GTime.rfc3339(now + lead * 60000L + WINDOW_MS, tz), "UTF-8");
            com.onesay.assistant.v2.conn.ConnResult r = api.get(url);
            if (!r.ok()) {
                if (NoticePolicy.connectorLost(r.code)) throw new NoticeSource.Unavailable(String.valueOf(r.code));
                Log.i(TAG, "[NOTICE] 日历这次读不到（" + r.code + "），静默跳过，保留已有提醒");
                return null;
            }
            List<IslandNotice> l = CalendarNotices.plan(CalendarNotices.parseGoogle(r.data, tz), lead, now, tz);
            mLastFailed = false;
            return l;
        }

        @Override public boolean onAction(IslandNotice n, NoticeAction a) {
            return false;
        }
    }

    /** 定时邮件：扫 S3 里要发邮件的定时 AGENT 任务。 */
    static final class MailSource implements NoticeSource {
        private final Context mApp;

        MailSource(Context app) {
            mApp = app;
        }

        @Override public String id() { return ScheduledMailNotices.TAG; }
        @Override public IslandNotice.Source kind() { return IslandNotice.Source.MAIL; }
        @Override public String switchKey() { return NoticeSettings.K_SRC_MAIL; }

        volatile long mLastPoll;

        /** 本地扫描很便宜，但不能恒返回 now（共用闹钟会每秒叫醒）：任务库改动走 onTasksChanged 立刻重扫，这里 15 分钟兜底一次。 */
        @Override public long nextPollAt(long now) {
            return ScheduledMailNotices.nextPollAt(NoticeSettings.master(mApp) && NoticeSettings.source(mApp, NoticeSettings.K_SRC_MAIL), mLastPoll, now);
        }

        @Override public List<IslandNotice> poll(long now) {
            mLastPoll = now;
            if (!NoticeSettings.source(mApp, NoticeSettings.K_SRC_MAIL)) return null;
            List<ScheduledMailNotices.Candidate> cands = new ArrayList<ScheduledMailNotices.Candidate>();
            try {
                for (com.onesay.assistant.v2.s3.S3Store.Task t : com.onesay.assistant.v2.s3.S3Hooks.engine(mApp).store().tasks()) {
                    if (t.kind != com.onesay.assistant.v2.s3.S3Parser.KIND_AGENT) continue;
                    cands.add(new ScheduledMailNotices.Candidate(t.id, t.command, t.nextAt, t.enabled));
                }
            } catch (Throwable e) {
                return null;
            }
            List<IslandNotice> l = ScheduledMailNotices.plan(cands, NoticeSettings.mailLead(mApp), now, TimeZone.getDefault());
            // 「草稿待确认」是推进来的，不归这次扫描管：保留在排的那几条
            for (IslandNotice n : center(mApp).all()) {
                if (n.isLive() && n.dedupKey.startsWith("mail:draft:")) l.add(n);
            }
            return l;
        }

        @Override public boolean onAction(IslandNotice n, NoticeAction a) {
            return false;
        }
    }
}
