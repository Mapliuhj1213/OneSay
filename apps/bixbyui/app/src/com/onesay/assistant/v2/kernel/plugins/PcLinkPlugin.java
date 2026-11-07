package com.onesay.assistant.v2.kernel.plugins;

import android.content.Context;
import android.os.Looper;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.notice.IslandNotice;
import com.onesay.assistant.v2.notice.NoticeHooks;
import com.onesay.assistant.v2.pclink.PcLanClient;
import com.onesay.assistant.v2.pclink.PcLink;
import com.onesay.assistant.v2.pclink.PcLinkUi;
import com.onesay.assistant.v2.pclink.PcVoice;
import com.onesay.assistant.v2.router.Invocation;
import com.onesay.assistant.v2.router.L2LLMCompiler;

import org.json.JSONObject;

import java.io.File;
import java.util.List;

/**
 * [kernel] ★ feat/pclink 电脑联动：扫电脑端 OneSay（0.4.1 起）设置页的二维码配对，局域网直连（UDP 发现 + WebSocket + HMAC 签名，
 * 协议见 {@code pclink/PcProtocol}）。
 * <ul>
 *   <li>控制台：设置 › 功能 › 「电脑联动」页（配对、在线状态、锁屏 / 截图 / 剪贴板 / 打开网址 / 睡眠 / 关机、提醒同步开关）；</li>
 *   <li>提醒中枢新出现的日程 / 定时提醒 / 自动任务结果 / 降水 → 电脑在线时发 notify，离线丢弃；</li>
 *   <li>语音口令：「电脑锁屏」「让电脑截个图」「电脑在线吗」（关机 / 睡眠只引导去控制台确认）。</li>
 * </ul>
 * 默认开（上机版与开源构建一样）：没配对时什么都不做、不联网。总开关 / {@code pclink.off} 可停。设计：docs/PCLINK.md。
 */
public final class PcLinkPlugin extends FeaturePlugin {

    public static final String ID = "pclink";

    @Override public String id() { return ID; }
    @Override public String title() { return "电脑联动"; }
    @Override public String logTag() { return "[PCLINK]"; }
    @Override public String acceptanceModule() { return null; }
    @Override public String prefsName() { return PcLink.PREFS; }
    @Override public String[] killFiles() { return new String[]{PcLink.KILL}; }
    @Override public boolean defaultOn() { return true; }
    @Override public boolean enabled(Context ctx) { return PcLink.SWITCH.on(ctx); }
    /** 控制台入口要在关着时也能画出来（好让用户重新打开），所以不由内核门控；各挂钩自己现查开关 + 配对。 */
    @Override public boolean kernelGated() { return false; }
    @Override public String[] dependsOn() { return new String[]{"notice"}; }

    @Override public void onInit(Context ctx) {
        final Context app = ctx.getApplicationContext();
        NoticeHooks.addSurfaceListener(new NoticeHooks.SurfaceListener() {
            @Override public void onSurfaced(Context c, List<IslandNotice> surfaced) { PcLink.onNoticesSurfaced(app, surfaced); }
        });
    }

    @Override public int priority(FeatureHook hook) {
        switch (hook) {
            case TAKEOVER: return 275;
            case CLAIMS: return 275;
            default: return 1000;
        }
    }

    // ------------------------------------------------------------------ 语音口令

    @Override public Invocation route(Context ctx, String query, L2LLMCompiler l2) {
        PcVoice.Kind k = PcVoice.of(query);
        if (k == PcVoice.Kind.NONE || ctx == null || !PcLink.active(ctx)) return null;
        Context app = ctx.getApplicationContext();
        String text;
        if (k == PcVoice.Kind.POWER) {
            text = "关机和睡眠要在控制台「设置 › 功能 › 电脑联动」里点，会先让你确认一次。";
        } else if (Looper.myLooper() == Looper.getMainLooper()) {
            text = "现在没法等电脑回话，请在控制台「电脑联动」页里点。";   // 路由正常在后台线程；万一在主线程不做网络
        } else if (k == PcVoice.Kind.LOCK) {
            PcLanClient.Result r = PcLink.runNow(app, "lock", null, 1800, 8000);
            text = r.ok ? "电脑已锁屏。" : "没锁上：" + r.text;
        } else if (k == PcVoice.Kind.SHOT) {
            PcLanClient.Result r = PcLink.runNow(app, "screenshot", null, 1800, 20000);
            File f = r.ok ? PcLink.saveShot(app, r) : null;
            text = !r.ok ? "没截到：" + r.text : f == null ? "截到了，但存不下来。" : "截好了，存在" + (f.getAbsolutePath().contains("/Pictures/OneSay/") ? "相册「OneSay」" : "应用私有目录")
                    + "，在控制台「电脑联动」页可以查看和分享。";
        } else {
            PcLanClient.Result r = PcLink.probe(app);
            text = r.ok ? PcLink.pcName(app) + " 在线。" : "AUTH".equalsIgnoreCase(r.fail) ? "电脑拒绝了连接，配对可能失效了，请重新扫码。"
                    : "电脑不在线（局域网里没找到 " + PcLink.pcName(app) + "）。";
        }
        JSONObject args = new JSONObject();
        try {
            args.put("text", text);
            args.put("query", query.trim());
        } catch (Exception ignored) {
        }
        return new Invocation("ask.reply", args, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    @Override public String routeLog() { return "[ROUTE-PCLINK] 电脑联动接管: "; }

    @Override public boolean claims(Context ctx, String query) {
        return ctx != null && PcVoice.of(query) != PcVoice.Kind.NONE && PcLink.active(ctx);
    }

    // ------------------------------------------------------------------ 控制台

    @Override public String consoleCard() { return "设置 › 功能 › 电脑联动（细项页 pclink）"; }
    @Override public boolean consoleV2() { return true; }

    @Override public String consoleSummary(Context ctx) {
        if (!PcLink.SWITCH.on(ctx)) return "已关闭";
        return PcLink.paired(ctx) ? "已配对 " + PcLink.pcName(ctx) : "未配对";
    }

    @Override public void addConsoleEntries(ConsoleHost host, String page, LinearLayout root) {
        if (ConsoleHost.PAGE_FEATURES.equals(page)) PcLinkUi.addEntryRow(host, root);
        else if (PcLinkUi.PAGE.equals(page)) PcLinkUi.buildPage(host, root);
    }

    @Override public String debugStatus(Context ctx) { return PcLink.status(ctx); }
}
