package com.onesay.assistant.v2.planner;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import com.onesay.assistant.v2.events.EventStore;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.s3.S3Store;
import com.onesay.assistant.v2.sched.SchedStore;

import org.json.JSONObject;

import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * [planner] 计划 App 适配层的宿主挂钩（由 kernel/plugins/PlannerPlugin 调）。
 * <table>
 *   <tr><td>存储</td><td>{@link #sched} / {@link #s3} / {@link #events}：给三个引擎的「会切换」存储（计划 App 在 → 计划 App；不在 → 本地）</td></tr>
 *   <tr><td>路由</td><td>{@link #route}：取件码（存 / ★ 11-06 按码查、列出待取）/ 存票 / 今天有啥事 / 打开计划（{@link PlannerVoice}）</td></tr>
 *   <tr><td>镜像</td><td>{@link #onPickupCard} / {@link #onPickupTaken} / {@link #onQuickNoteSaved}：岛上的码、记一记的码和票写进计划 App</td></tr>
 *   <tr><td>变更</td><td>{@link #onServiceCreate}：监听计划 App 的变化（ContentObserver）→ 重排 S3 闹钟、刷新岛提醒、刷新待办 chip</td></tr>
 * </table>
 */
public final class PlannerHooks {
    private PlannerHooks() {}

    static final String TAG = "BixbyPlanner";

    // ------------------------------------------------------------------ 存储

    public static SchedStore sched(Context c, SchedStore local) {
        PlannerLink l = PlannerLink.get(c);
        return new SwitchStores.Sched(local, new PlannerSchedStore(l.backend()), l);
    }

    public static S3Store s3(Context c, S3Store local) {
        PlannerLink l = PlannerLink.get(c);
        return new SwitchStores.S3(local, new PlannerS3Store(l.backend()), l);
    }

    public static EventStore events(Context c, EventStore local) {
        PlannerLink l = PlannerLink.get(c);
        return new SwitchStores.Events(local, new PlannerEventStore(l.backend()), l);
    }

    // ------------------------------------------------------------------ 路由

    public static boolean claims(Context ctx, String q) {
        if (q == null) return false;
        if (PlannerVoice.open(q)) return PlannerLink.installed(ctx);
        if (PlannerVoice.pickup(q) == null && PlannerVoice.pickupAsk(q) == null && !PlannerVoice.ticket(q) && !PlannerVoice.today(q)) return false;
        return PlannerLink.get(ctx).on();
    }

    /** 后台线程。null = 不接管（交回原流程：S3 / 日程 / 记一记）。 */
    public static Invocation route(Context ctx, String q) {
        try {
            if (q == null) return null;
            if (PlannerVoice.open(q)) {
                if (!PlannerLink.installed(ctx)) return null;
                PlannerLink.open(ctx, null);
                return reply(q, "打开计划了。");
            }
            PlannerVoice.PickupAsk ask = PlannerVoice.pickupAsk(q);
            PlannerVoice.Pickup p = ask != null ? null : PlannerVoice.pickup(q);
            boolean tk = PlannerVoice.ticket(q), td = PlannerVoice.today(q);
            if (ask == null && p == null && !tk && !td) return null;
            PlannerLink link = PlannerLink.get(ctx);
            if (!link.on()) return null;
            PlannerBackend b = link.backend();
            if (ask != null) {   // 取件码问答（只读，不改取件记录）；null = 只问「取件码是多少」而计划里没有 → 交给记一记
                String ans = pickupReply(b, ask, System.currentTimeMillis(), TimeZone.getDefault());
                return ans == null ? null : reply(q, ans);
            }
            if (p != null) {
                b.insert(PlannerContract.T_PICKUPS, PlannerMap.pickup(p.code, p.kind, p.station, "", 0, null, "voice"));
                return reply(q, "记好了：" + p.kind + " " + p.code + (p.station.length() > 0 ? "（" + p.station + "）" : "") + "。取了跟我说一声，或者在计划里点「已取」。");
            }
            if (td) {
                List<Map<String, Object>> rows = b.list(PlannerContract.P_TODAY);
                return reply(q, PlannerVoice.todayReply(rows));
            }
            return reply(q, saveTicketFromScreen(ctx, b));
        } catch (Throwable t) {
            Log.w(TAG, "[PLANNER] 路由出错，交回原流程: " + t.getMessage());
            return null;
        }
    }

    /** 取件码问句的答复（纯读 pickups 表；JVM 单测用 {@link PlannerBackend.Mem}）。 */
    static String pickupReply(PlannerBackend b, PlannerVoice.PickupAsk ask, long now, TimeZone tz) {
        return PlannerVoice.pickupAnswer(ask, b.list(PlannerContract.T_PICKUPS), now, tz);
    }

    static Invocation reply(String q, String text) throws Exception {
        JSONObject a = new JSONObject();
        a.put("text", text);
        a.put("query", q.trim());
        a.put("planner", true);
        return new Invocation("ask.reply", a, 1.0, Invocation.RouteSource.L0_RULE, q);
    }

    /** 「把这张票存起来」：收面板 → 读屏 → 认票 → 存进票夹。锁屏不读；认不出就如实说，不猜。 */
    static String saveTicketFromScreen(Context ctx, PlannerBackend b) {
        Context app = ctx.getApplicationContext();
        try {
            com.onesay.assistant.v2.ui.FloatingSheetService.hide(app);
            Thread.sleep(450);
        } catch (Throwable ignored) {
        }
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager) app.getSystemService(Context.KEYGUARD_SERVICE);
            if (km != null && km.isKeyguardLocked()) return "手机锁着，我看不到票。解锁后打开订单页再说一次。";
        } catch (Throwable ignored) {
        }
        com.onesay.assistant.v2.vision.screenref.ScreenContext sc = com.onesay.assistant.v2.vision.screenref.ScreenContextCapture.captureSync(app);
        StringBuilder sb = new StringBuilder();
        if (sc != null && sc.texts != null) for (String s : sc.texts) sb.append(s).append('\n');
        PRow t = PlannerTicketText.parse(sb.toString(), System.currentTimeMillis(), TimeZone.getDefault());
        if (t == null) return "这页上我没认出车次、航班或场次。打开订单详情页（有时间和座位的那页）再说一次，或者把购票短信复制下来，在计划的票夹里点「从剪贴板识别」。";
        t.remove("source");
        t.with("source", "voice").with("src_key", "bixbyui:ticket:" + t.s("kind") + ":" + t.s("number") + ":" + t.l("depart_at") + ":" + t.s("seat"));
        if (sc != null && sc.pkg != null) t.with("note", "来自 " + sc.pkg);
        b.insert(PlannerContract.T_TICKETS, t);
        String when = new java.text.SimpleDateFormat("M月d日 HH:mm", java.util.Locale.CHINA).format(new java.util.Date(t.l("depart_at")));
        return "存进票夹了：" + t.s("title") + "，" + when + (t.s("seat").length() > 0 ? "，" + t.s("seat") : "")
                + (t.s("gate").length() > 0 ? "，检票口 " + t.s("gate") : "") + "。出发前两小时提醒你。";
    }

    // ------------------------------------------------------------------ 镜像：岛上的码 / 记一记

    /** 灵动岛收到短信取件码（记一记来的不在这里，走 onQuickNoteSaved）。后台写，失败不影响岛。 */
    public static void onPickupCard(final Context ctx, final String cardId, final String code, final String kind, final String station,
                                    final String pkg, final long expiresAt, final long noteId) {
        if (noteId > 0 || code == null) return;
        bg(ctx, "pickup", new Runnable() {
            @Override public void run() {
                PlannerBackend b = PlannerLink.get(ctx).backend();
                b.insert(PlannerContract.T_PICKUPS, PlannerMap.pickup(code, kind, station, pkg, 0, "bixbyui:card:" + cardId, "sms"));
            }
        });
    }

    /** 岛上关掉码 / 记一记标「已取」→ 计划 App 里标已取（按 src_key 找）。 */
    public static void onPickupTaken(final Context ctx, final String srcKey) {
        if (srcKey == null) return;
        bg(ctx, "pickup-taken", new Runnable() {
            @Override public void run() {
                PlannerBackend b = PlannerLink.get(ctx).backend();
                for (Map<String, Object> m : b.list(PlannerContract.T_PICKUPS)) {
                    if (srcKey.equals(PlannerMap.s(m, "src_key")) && PlannerMap.l(m, "taken_at") == 0) {
                        java.util.Map<String, Object> v = new java.util.LinkedHashMap<String, Object>();
                        v.put("taken_at", System.currentTimeMillis());
                        b.update(PlannerContract.T_PICKUPS, PlannerMap.l(m, "_id"), v);
                    }
                }
            }
        });
    }

    /** 记一记存好一条：码（取件码 / 取餐码）→ pickups；认得出的票 → tickets。src_key 与一次性迁移一致（bixbyui:qn:&lt;id&gt;）。 */
    public static void onQuickNoteSaved(final Context ctx, final long id, final String codeKind, final String codeValue, final String where,
                                        final String pkg, final String text) {
        bg(ctx, "quicknote", new Runnable() {
            @Override public void run() {
                PlannerBackend b = PlannerLink.get(ctx).backend();
                String key = "bixbyui:qn:" + id;
                if (codeValue != null && codeValue.length() > 0 && ("取件码".equals(codeKind) || "取餐码".equals(codeKind))) {
                    b.insert(PlannerContract.T_PICKUPS, PlannerMap.pickup(codeValue, codeKind, where, pkg, 0, key, "quicknote"));
                    return;
                }
                PRow t = PlannerTicketText.parse(text, System.currentTimeMillis(), TimeZone.getDefault());
                if (t != null) {
                    t.remove("source");
                    t.with("source", "quicknote").with("src_key", key);
                    b.insert(PlannerContract.T_TICKETS, t);
                }
            }
        });
    }

    static void bg(final Context ctx, String name, final Runnable r) {
        final Context app = ctx.getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("planner-" + name, new Runnable() {
            @Override public void run() {
                try {
                    if (PlannerLink.get(app).on()) r.run();
                } catch (Throwable t) {
                    Log.w(TAG, "[PLANNER] 镜像失败（不影响原功能）: " + t.getMessage());
                }
            }
        });
    }

    // ------------------------------------------------------------------ 变更监听

    private static ContentObserver sObs;
    private static HandlerThread sThread;

    public static synchronized void onServiceCreate(Context ctx) {
        final Context app = ctx.getApplicationContext();
        com.onesay.assistant.v2.shortcut.ShortcutHooks.installJobPerms(app);   // ★ feat/shortcuts：定时任务的权限清单认得「执行快捷指令#id」
        com.onesay.assistant.v2.util.Threads.start("planner-init", new Runnable() {
            @Override public void run() {
                PlannerLink.get(app).on();   // 第一次连上会顺手触发一次性迁移
                com.onesay.assistant.v2.shortcut.ShortcutHooks.pushCatalog(app);   // ★ feat/shortcuts：动作清单（带可用性）推给计划 App
                com.onesay.assistant.v2.shortcut.ShortcutHooks.onPlannerChanged(app);   // 服务起来前在计划 App 点的运行：认领（过期的标 stale）
            }
        });
        if (sObs != null) return;
        sThread = new HandlerThread("planner-obs");
        sThread.start();
        final Handler h = new Handler(sThread.getLooper());
        final Runnable apply = new Runnable() {
            @Override public void run() {
                try {
                    if (!PlannerLink.get(app).on()) return;
                    com.onesay.assistant.v2.s3.S3Scheduler.reschedule(app, "planner");
                    com.onesay.assistant.v2.notice.NoticeHooks.onTasksChanged(app);
                    com.onesay.assistant.v2.sched.SchedHooks.refreshNotices(app);
                    com.onesay.assistant.v2.s3.S3Runner r = com.onesay.assistant.v2.s3.S3Runner.get();
                    if (r != null) r.refreshTodos();
                    com.onesay.assistant.v2.shortcut.ShortcutHooks.onPlannerChanged(app);   // ★ feat/shortcuts：认领计划 App 发起的运行
                    Log.i(TAG, "[PLANNER] 计划 App 有变化 → 已重排定时任务、刷新提醒");
                } catch (Throwable t) {
                    Log.w(TAG, "[PLANNER] 处理变化失败: " + t.getMessage());
                }
            }
        };
        sObs = new ContentObserver(h) {
            @Override public void onChange(boolean self, Uri uri) {
                h.removeCallbacks(apply);
                h.postDelayed(apply, 800);
            }
        };
        try {
            app.getContentResolver().registerContentObserver(Uri.parse(PlannerContract.BASE_URI), true, sObs);
        } catch (Throwable t) {
            Log.w(TAG, "[PLANNER] 注册监听失败（计划 App 没装时正常）: " + t.getMessage());
        }
    }

    public static synchronized void onServiceDestroy(Context ctx) {
        if (sObs != null) {
            try {
                ctx.getApplicationContext().getContentResolver().unregisterContentObserver(sObs);
            } catch (Throwable ignored) {
            }
            sObs = null;
        }
        if (sThread != null) {
            sThread.quitSafely();
            sThread = null;
        }
    }

    public static String debugStatus(Context ctx) {
        return PlannerLink.get(ctx).status() + " · 迁移：" + PlannerMigration.lastResult(ctx);
    }
}
