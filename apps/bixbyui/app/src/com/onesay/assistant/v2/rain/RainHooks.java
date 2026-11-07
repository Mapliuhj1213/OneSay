package com.onesay.assistant.v2.rain;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.PowerManager;
import android.util.Log;

import com.onesay.assistant.v2.notice.IslandNotice;
import com.onesay.assistant.v2.notice.NoticeAction;
import com.onesay.assistant.v2.notice.NoticeHooks;
import com.onesay.assistant.v2.notice.NoticeSource;
import com.onesay.assistant.v2.router.Invocation;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * [rain] 分钟级降水接进宿主：语音本地接管（{@link #route}）、灵动岛提醒来源（{@link Source}，拉型，挂在提醒中枢上，由共用闹钟按 nextPollAt 叫醒）。
 */
public final class RainHooks {

    private static final String TAG = "Rain";

    private RainHooks() {}

    // ================================================================== 语音

    /** 用户的常用城市（手动设置 → 已缓存的系统天气 / 定位城市；不触发 root / 网络）。 */
    static String homeCity(Context ctx) {
        try {
            String m = new com.onesay.assistant.v2.search.SearchConfig(ctx).getHomeCity();
            if (m.length() > 0) return m;
            return ctx.getApplicationContext().getSharedPreferences(com.onesay.assistant.v2.city.HomeCityProvider.PREF, Context.MODE_PRIVATE).getString("city", "");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 本地接管（后台线程，会联网，约 0.3–1 s）。不是降水问句 / 功能不可用（没 Key、被关）→ null，交回原流程（联网问答照旧答天气）。
     * 「天气怎么样」查失败也交回原流程；其余问句查失败如实说。
     */
    public static Invocation route(Context ctx, String query) {
        RainVoice.Q q = RainVoice.classify(query, homeCity(ctx));
        if (q == null) return null;
        String why = RainService.unavailableReason(ctx);
        if (why != null) {
            Log.i(TAG, "[RAIN] 降水问句（" + q + "）但分钟级降水不可用：" + why + " → 交回原流程");
            return null;
        }
        RainService.Outcome o = RainService.fetch(ctx, q == RainVoice.Q.GENERAL, RainService.VOICE_REUSE_MS);
        String text;
        RainAnalysis a = o.ok() ? RainAnalysis.of(o.forecast, System.currentTimeMillis()) : null;
        if (a != null) {
            text = RainVoice.answer(q, a, o.forecast.summary, o.now);
        } else {
            if (q == RainVoice.Q.GENERAL) {
                Log.i(TAG, "[RAIN] 「天气怎么样」查不到（" + o.fail + "）→ 交回原流程");
                return null;
            }
            text = RainVoice.failure(o.ok() ? RainVoice.Fail.BAD_DATA : o.fail);
        }
        Log.i(TAG, "[RAIN] 语音 " + q + " → " + (a == null ? "失败 " + o.fail : a.state.name()) + (o.fake ? "（测试注入）" : ""));
        if (a != null) Source.kick(ctx);   // 刚拿到新数据：岛上提醒顺手对一下（不额外联网）
        try {
            JSONObject args = new JSONObject();
            args.put("text", text);
            args.put("query", query == null ? "" : query.trim());
            args.put("rain", q.name());
            return new Invocation("ask.reply", args, 1.0, Invocation.RouteSource.L0_RULE, query);
        } catch (Exception e) {
            return null;
        }
    }

    /** 快捷指令「播报天气」：一句话（现在的天气 + 未来两小时降水）。不可用 / 失败 → 如实说。后台线程。 */
    public static String sayForShortcut(Context ctx) {
        String why = RainService.unavailableReason(ctx);
        if (why != null) return RainVoice.failure(RainService.reasonToFail(ctx));
        RainService.Outcome o = RainService.fetch(ctx, true, 0L);
        RainAnalysis a = o.ok() ? RainAnalysis.of(o.forecast, System.currentTimeMillis()) : null;
        if (a == null) return RainVoice.failure(o.ok() ? RainVoice.Fail.BAD_DATA : o.fail);
        return RainVoice.answer(RainVoice.Q.GENERAL, a, o.forecast.summary, o.now);
    }

    // ================================================================== 灵动岛来源

    /** 拉型提醒来源：联网节奏按 {@link RainPolicy#nextDelayMs}；有提醒在岛上且亮屏时每分钟用缓存重算一次标题（不联网）。 */
    public static final class Source implements NoticeSource {
        public static final String ID = RainPolicy.SOURCE_TAG;
        static volatile Source sInstance;
        static volatile BroadcastReceiver sScreen;

        final Context mApp;
        final RainPolicy.Episode mEp = new RainPolicy.Episode();
        long mNextNet;
        long mLastRecalc;
        int mFailures;
        boolean mHard;
        boolean mLive;

        Source(Context app) {
            mApp = app;
            SharedPreferences sp = RainService.sp(app);
            mEp.restore(sp.getString("ep_kind", ""), sp.getLong("ep_id", 0L));
        }

        /** 面板服务 onCreate：登记来源 + 亮屏广播（亮屏时立刻对一下，息屏期间标题可能过时了）。 */
        public static void install(final Context ctx) {
            final Context app = ctx.getApplicationContext();
            if (sInstance == null) sInstance = new Source(app);
            NoticeHooks.register(sInstance);
            if (sScreen == null) {
                sScreen = new BroadcastReceiver() {
                    @Override public void onReceive(Context c, Intent i) {
                        if (RainService.alertsOn(app)) kick(app);
                    }
                };
                try {
                    app.registerReceiver(sScreen, new IntentFilter(Intent.ACTION_SCREEN_ON));
                } catch (Throwable t) {
                    sScreen = null;
                }
            }
        }

        public static Source sInstanceForStatus() {
            return sInstance;
        }

        public static void uninstall(Context ctx) {
            BroadcastReceiver r = sScreen;
            sScreen = null;
            if (r != null) try { ctx.getApplicationContext().unregisterReceiver(r); } catch (Throwable ignored) { }
        }

        /** 立刻同步一次（开关翻转、亮屏、语音刚拿到新数据）。 */
        public static void kick(Context ctx) {
            if (sInstance == null) return;
            NoticeHooks.syncNow(ctx, ID);
        }

        static boolean screenOn(Context c) {
            try {
                PowerManager pm = (PowerManager) c.getSystemService(Context.POWER_SERVICE);
                return pm == null || pm.isInteractive();
            } catch (Throwable t) {
                return true;
            }
        }

        @Override public String id() { return ID; }
        @Override public IslandNotice.Source kind() { return IslandNotice.Source.WEATHER; }
        @Override public String switchKey() { return null; }

        @Override public synchronized long nextPollAt(long now) {
            if (!RainService.alertsOn(mApp)) return -1L;
            if (mNextNet <= 0) return now;
            long at = mNextNet;
            if (mLive && screenOn(mApp)) at = Math.min(at, mLastRecalc + 60000L);
            return at;
        }

        @Override public synchronized List<IslandNotice> poll(long now) {
            if (!RainService.alertsOn(mApp)) {
                mLive = false;
                return Collections.emptyList();   // 关了 → 同步撤下
            }
            RainService.Outcome last = RainService.last();
            boolean cacheFresh = last != null && last.ok() && now - last.fetchedAt < 30L * 60000L;
            if (now >= mNextNet || !cacheFresh) {
                RainService.Outcome o = RainService.fetch(mApp, false, 60000L);
                boolean on = screenOn(mApp);
                if (o.ok()) {
                    mFailures = 0;
                    mHard = false;
                    last = o;
                } else {
                    mFailures++;
                    mHard = o.hard();
                }
                RainAnalysis a0 = last != null && last.ok() ? RainAnalysis.of(last.forecast, now) : null;
                long d = RainPolicy.nextDelayMs(o.ok() ? a0 : null, on, o.ok() ? 0 : mFailures, mHard);
                mNextNet = now + d;
                Log.i(TAG, "[RAIN] 后台检查 " + (o.ok() ? "成功" : "失败×" + mFailures + " " + o.fail) + (on ? "" : "（息屏）")
                        + " → " + (d / 60000L) + " 分钟后再查");
                if (!o.ok() && !cacheFresh) return null;   // 拿不到：岛上已有的保留（中枢约定）
            }
            mLastRecalc = now;
            RainAnalysis a = RainAnalysis.of(last.forecast, now);
            IslandNotice n = a == null ? null : RainPolicy.notice(a, last.forecast.summary, mEp, now);
            RainService.sp(mApp).edit().putString("ep_kind", mEp.kind()).putLong("ep_id", mEp.id()).apply();
            mLive = n != null;
            List<IslandNotice> out = new ArrayList<IslandNotice>();
            if (n != null) out.add(n);
            return out;
        }

        @Override public boolean onAction(IslandNotice n, NoticeAction a) {
            return false;
        }

        public synchronized String status() {
            return "nextNet=" + mNextNet + " failures=" + mFailures + " hard=" + mHard + " live=" + mLive + " ep=" + mEp.kind() + ":" + mEp.id();
        }
    }
}
