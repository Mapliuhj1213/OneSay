package com.onesay.assistant.v2.apifirst;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.provider.AlarmClock;
import android.provider.CalendarContract;
import android.provider.ContactsContract;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.net.HttpClient;

import org.json.JSONObject;

import java.util.List;
import java.util.TimeZone;

/**
 * ★ feat/api-first：路由层的 Android 一侧 —— {@link ApiFirstRouter.Env}（这台机器有什么）+ {@link ApiFirstRouter.Runner}（真正去做）。
 *
 * <p>已有能力盘点（第 ① 档）：闹钟 / 计时器走 {@link AlarmClock} 公开 Intent；加日程走 {@link CalendarContract} 的插入页
 * （带好标题和时间，用户在日历里点保存，不直接写库）；找联系人走 {@link ContactsContract}（只回名字，不回号码，要通讯录权限）；
 * 设置页走 android.provider.Settings 的 action。
 * 第 ② 档：和风天气（{@code RainService} 同一把 key / host，只按本机定位查）、Tavily 搜索（{@code SearchConfig} 同一把 key）、
 * 海纳商品（{@code SearchConfig#getHainaKey}）；密钥只在请求头里用，<b>不进日志、不进工具观测</b>。
 * 第 ③ 档：ACTION_VIEW + 指定包名打开 scheme；{@code launch:} 用包名的启动页；浏览器链接不指定包名。
 * 第 ④ 档：路由层<b>不直接点屏</b>，把目标交回循环，由现有的 phone.operate（过门禁 / 确认卡 / 付款硬拒）去做。
 */
public final class ApiFirstAndroid implements ApiFirstRouter.Env, ApiFirstRouter.Runner {

    private static final String TAG = "ApiFirst";

    private final Context mCtx;
    private final com.onesay.assistant.v2.net.StopScope mScope;

    public ApiFirstAndroid(Context ctx) {
        mCtx = ctx.getApplicationContext();
        mScope = com.onesay.assistant.v2.net.StopScope.current();
    }

    // ------------------------------------------------------------------ 入口（插件调用）

    public static ExecutionResult execute(Context ctx, Capability cap, JSONObject args) {
        ApiIntent in = RouteTool.parse(args);
        if (!in.known()) {
            return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, "不认识的 intent：" + in.kind, "unknown_intent", cap, args);
        }
        ApiFirstAndroid a = new ApiFirstAndroid(ctx);
        ApiFirstRouter.Plan plan = ApiFirstRouter.plan(in, a);
        ApiFirstRouter.Outcome o = ApiFirstRouter.run(plan, a);
        Log.i(TAG, ApiFirstRouter.logLine(o));
        String obs = o.toJson().toString();
        String via = o.tier == null ? "" : "走 " + o.tier.mark() + o.tier.label + " " + o.via;
        // 观测给模型：成功 → result 就是完整 JSON（route / trace / data / handoff）；失败 → result 是一句原因，完整 JSON 放 hint（不截断）
        if (o.stopped) return ExecutionResult.failed(ErrorCode.CANCELLED, "已停止", "stopped", obs, cap, args);
        if (o.ok) return ExecutionResult.success(cap, args, obs, false, via);
        return ExecutionResult.failed(in.payment() ? ErrorCode.GATE_DENIED : ErrorCode.EXEC_FAILED,
                o.handoff.length() > 0 ? o.handoff : "几条路都没走通", o.code, obs, cap, args);
    }

    // ------------------------------------------------------------------ Env

    @Override public boolean systemAvailable(String kind) {
        try {
            if (ApiIntent.ALARM_SET.equals(kind)) return resolves(new Intent(AlarmClock.ACTION_SET_ALARM));
            if (ApiIntent.TIMER_SET.equals(kind)) return resolves(new Intent(AlarmClock.ACTION_SET_TIMER));
            if (ApiIntent.CALENDAR_ADD.equals(kind)) return resolves(new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI));
            if (ApiIntent.CONTACT_FIND.equals(kind)) return mCtx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED;
            if (ApiIntent.SETTINGS_OPEN.equals(kind)) return resolves(new Intent(android.provider.Settings.ACTION_SETTINGS));
        } catch (Throwable t) {
            Log.w(TAG, "[API-FIRST] 查系统接口异常: " + t.getClass().getSimpleName());
        }
        return false;
    }

    @Override public boolean keyedEnabled() {
        return ApiFirstSettings.KEYED.on(mCtx);
    }

    @Override public boolean hasKey(String api) {
        try {
            if (ApiFirstRouter.API_QWEATHER.equals(api)) return com.onesay.assistant.v2.rain.RainService.unavailableReason(mCtx) == null;
            com.onesay.assistant.v2.search.SearchConfig sc = new com.onesay.assistant.v2.search.SearchConfig(mCtx);
            if (ApiFirstRouter.API_TAVILY.equals(api)) return sc.getTavilyKey().length() > 0;
            if (ApiFirstRouter.API_HAINA.equals(api)) return sc.getHainaKey().length() > 0;
        } catch (Throwable t) {
            Log.w(TAG, "[API-FIRST] 查密钥配置异常: " + t.getClass().getSimpleName());
        }
        return false;
    }

    @Override public boolean installed(String pkg) {
        if (pkg == null) return true;
        try {
            mCtx.getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    @Override public boolean guiAvailable() {
        try {
            return com.onesay.assistant.v2.phone.PhoneHooks.enabled(mCtx);
        } catch (Throwable t) {
            return false;
        }
    }

    private boolean resolves(Intent i) {
        return i.resolveActivity(mCtx.getPackageManager()) != null;
    }

    // ------------------------------------------------------------------ Runner

    @Override public boolean stopped() {
        return mScope != null && mScope.isStopped();
    }

    @Override public ApiFirstRouter.Attempt system(ApiIntent in) {
        try {
            String k = in.kind;
            if (ApiIntent.ALARM_SET.equals(k)) {
                int[] hm = RouteTool.parseTime(in.slot("time"));
                if (hm == null) return ApiFirstRouter.Attempt.fail("缺少时间");
                Intent i = new Intent(AlarmClock.ACTION_SET_ALARM)
                        .putExtra(AlarmClock.EXTRA_HOUR, hm[0]).putExtra(AlarmClock.EXTRA_MINUTES, hm[1])
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                if (in.has("title")) i.putExtra(AlarmClock.EXTRA_MESSAGE, in.slot("title"));
                return start(i, "已设 " + hm[0] + ":" + (hm[1] < 10 ? "0" : "") + hm[1] + " 的闹钟（系统闹钟接口）");
            }
            if (ApiIntent.TIMER_SET.equals(k)) {
                int sec = RouteTool.timerSeconds(in.slot("minutes"));
                if (sec <= 0) return ApiFirstRouter.Attempt.fail("缺少时长");
                Intent i = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, sec)
                        .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
                return start(i, "已开 " + (sec / 60) + " 分钟计时（系统闹钟接口）");
            }
            if (ApiIntent.CALENDAR_ADD.equals(k)) {
                long now = System.currentTimeMillis();
                TimeZone tz = TimeZone.getDefault();
                long begin = RouteTool.epochMillis(in.slot("date"), in.slot("time"), tz, now);
                Intent i = new Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI);
                if (in.has("title")) i.putExtra(CalendarContract.Events.TITLE, in.slot("title"));
                if (begin > 0) {
                    i.putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin);
                    long end = RouteTool.epochMillis(in.slot("date"), in.slot("end_time"), tz, now);
                    i.putExtra(CalendarContract.EXTRA_EVENT_END_TIME, end > begin ? end : begin + 3600000L);
                }
                return start(i, "已打开日历的新建日程页（标题和时间已填好，点保存就行）");
            }
            if (ApiIntent.CONTACT_FIND.equals(k)) {
                if (!in.has("name")) return ApiFirstRouter.Attempt.fail("缺少名字");
                Uri u = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(in.slot("name")));
                StringBuilder sb = new StringBuilder();
                Cursor c = mCtx.getContentResolver().query(u, new String[]{ContactsContract.Contacts.DISPLAY_NAME}, null, null, null);
                int n = 0;
                if (c != null) {
                    try {
                        while (c.moveToNext() && n < 5) {
                            if (sb.length() > 0) sb.append("、");
                            sb.append(c.getString(0));
                            n++;
                        }
                    } finally {
                        c.close();
                    }
                }
                return n == 0 ? ApiFirstRouter.Attempt.fail("通讯录里没找到「" + in.slot("name") + "」")
                        : ApiFirstRouter.Attempt.ok("通讯录里有：" + sb);
            }
            if (ApiIntent.SETTINGS_OPEN.equals(k)) {
                return start(new Intent(RouteTool.settingsAction(in.slot("page"))), "已打开设置页");
            }
        } catch (Throwable t) {
            return ApiFirstRouter.Attempt.fail(t.getClass().getSimpleName());
        }
        return ApiFirstRouter.Attempt.fail("没有对应的系统接口");
    }

    @Override public ApiFirstRouter.Attempt keyed(String api, ApiIntent in) {
        try {
            if (ApiFirstRouter.API_QWEATHER.equals(api)) return weather(in);
            if (ApiFirstRouter.API_TAVILY.equals(api)) return tavily(in);
            if (ApiFirstRouter.API_HAINA.equals(api)) return haina(in);
        } catch (Throwable t) {
            return ApiFirstRouter.Attempt.fail(t.getClass().getSimpleName());
        }
        return ApiFirstRouter.Attempt.fail("不认识的接口");
    }

    private ApiFirstRouter.Attempt weather(ApiIntent in) {
        // 和风天气这里只按本机定位查（与分钟级降水同一把 key / host）；问别的城市 → 交给下一档（浏览器搜索）
        if (in.has("city") && !in.slot("city").matches("(这里|本地|当地|我这)")) {
            return ApiFirstRouter.Attempt.fail("和风天气接口这里只按本机定位查，问别的城市换下一条");
        }
        com.onesay.assistant.v2.rain.RainService.Outcome o = com.onesay.assistant.v2.rain.RainService.fetch(mCtx, true, com.onesay.assistant.v2.rain.RainService.VOICE_REUSE_MS);
        if (o == null || !o.ok()) return ApiFirstRouter.Attempt.fail(o == null ? "没有结果" : o.why);
        StringBuilder sb = new StringBuilder();
        if (o.now != null) {
            if (o.now.temp.length() > 0) sb.append("现在 ").append(o.now.temp).append(" 度");
            if (o.now.text.length() > 0) sb.append(sb.length() > 0 ? "，" : "").append(o.now.text);
        }
        if (o.forecast.summary.length() > 0) sb.append(sb.length() > 0 ? "；" : "").append(o.forecast.summary);
        return ApiFirstRouter.Attempt.ok(sb.length() > 0 ? sb.toString() : "已拿到天气数据");
    }

    private ApiFirstRouter.Attempt tavily(ApiIntent in) {
        String q = in.slot("q");
        if (ApiIntent.TRAIN_QUERY.equals(in.kind)) {
            q = in.slot("from") + " 到 " + in.slot("to") + " " + in.slot("date") + " 火车 高铁 车次 时刻 票价";
        }
        if (q.trim().length() == 0) return ApiFirstRouter.Attempt.fail("缺少关键词");
        com.onesay.assistant.v2.search.TavilySearchProvider p = new com.onesay.assistant.v2.search.TavilySearchProvider(new com.onesay.assistant.v2.search.SearchConfig(mCtx));
        List<com.onesay.assistant.v2.search.SearchResult> rs = p.search(q.trim(), 5);
        if (rs == null || rs.isEmpty()) return ApiFirstRouter.Attempt.fail(p.lastSearchFailed() ? "搜索接口没回结果" : "没搜到");
        StringBuilder sb = new StringBuilder();
        for (com.onesay.assistant.v2.search.SearchResult r : rs) {
            if (sb.length() > 1800) break;
            sb.append("· ").append(r.title).append("（").append(r.url).append("）：").append(cut(r.snippet, 220)).append('\n');
        }
        return ApiFirstRouter.Attempt.ok(com.onesay.assistant.v2.router.AgentLoop.wrapUntrusted(sb.toString().trim()));
    }

    private ApiFirstRouter.Attempt haina(ApiIntent in) {
        if (!in.has("q")) return ApiFirstRouter.Attempt.fail("缺少关键词");
        final String key = new com.onesay.assistant.v2.search.SearchConfig(mCtx).getHainaKey();
        if (key.length() == 0) return ApiFirstRouter.Attempt.fail("没配密钥");
        com.onesay.assistant.v2.errand.compare.HainaClient hc = haina();
        long now = System.currentTimeMillis();
        if (hc.exhausted(now)) return ApiFirstRouter.Attempt.fail("今天的接口额度快用完了");
        List<com.onesay.assistant.v2.errand.compare.CompareDeal> ds = hc.products(key, in.slot("q"), now);
        if (ds == null || ds.isEmpty()) return ApiFirstRouter.Attempt.fail("接口没回商品");
        StringBuilder sb = new StringBuilder();
        int n = 0;
        for (com.onesay.assistant.v2.errand.compare.CompareDeal d : ds) {
            if (n++ >= 8) break;
            sb.append("· ").append(d.title).append(" ").append(d.priceText).append(d.mall.length() > 0 ? " · " + d.mall : "").append('\n');
        }
        return ApiFirstRouter.Attempt.ok(com.onesay.assistant.v2.router.AgentLoop.wrapUntrusted("什么值得买商品检索：\n" + sb.toString().trim()));
    }

    private static volatile com.onesay.assistant.v2.errand.compare.HainaClient sHaina;

    /** 一个进程一个客户端（带 30 分钟缓存与当日计数）；HTTP 走 HttpClient（停止键能断开）。key 只放请求头。 */
    static com.onesay.assistant.v2.errand.compare.HainaClient haina() {
        if (sHaina == null) {
            sHaina = new com.onesay.assistant.v2.errand.compare.HainaClient(new com.onesay.assistant.v2.errand.compare.HainaClient.Post() {
                @Override public String post(String url, String apiKey, String jsonBody, int timeoutMs) {
                    HttpClient.Response r = HttpClient.execute(new HttpClient.Request(url).method("POST")
                            .header("x-api-key", apiKey).json(jsonBody).timeouts(timeoutMs, timeoutMs));
                    return r != null && r.code == 200 ? r.body : null;
                }
            });
        }
        return sHaina;
    }

    @Override public ApiFirstRouter.Attempt open(DeepLinks.Link link, String uri) {
        try {
            Intent i;
            if (uri.startsWith(DeepLinks.LAUNCH)) {
                i = mCtx.getPackageManager().getLaunchIntentForPackage(link.pkg);
                if (i == null) return ApiFirstRouter.Attempt.fail("打不开 " + link.app);
            } else {
                i = new Intent(Intent.ACTION_VIEW, Uri.parse(uri));
                if (link.pkg != null) i.setPackage(link.pkg);
                else i.addCategory(Intent.CATEGORY_BROWSABLE);
                if (i.resolveActivity(mCtx.getPackageManager()) == null) return ApiFirstRouter.Attempt.fail(link.app + " 不认这个链接");
            }
            return start(i, "已打开 " + link.app);
        } catch (Throwable t) {
            return ApiFirstRouter.Attempt.fail(t.getClass().getSimpleName());
        }
    }

    @Override public ApiFirstRouter.Attempt gui(ApiIntent in) {
        // 第 ④ 档不在路由层里点：交回循环，由 phone.operate 走门禁 / 确认卡（付款类根本到不了这里）
        return ApiFirstRouter.Attempt.ok("前三档都没走通，需要点界面：先调 gui.unfold 说明原因，再用 phone.operate 完成（目标："
                + in.kind + " " + in.slots() + "）");
    }

    private ApiFirstRouter.Attempt start(Intent i, String okText) {
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            mCtx.startActivity(i);
            return ApiFirstRouter.Attempt.ok(okText);
        } catch (ActivityNotFoundException e) {
            return ApiFirstRouter.Attempt.fail("没有能打开的应用");
        } catch (SecurityException e) {
            return ApiFirstRouter.Attempt.fail("没有权限");
        }
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        String t = s.replaceAll("\\s+", " ").trim();
        return t.length() <= n ? t : t.substring(0, n) + "…";
    }
}
