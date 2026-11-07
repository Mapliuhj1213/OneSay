package com.onesay.assistant.v2.suggest;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.plug.api.Clock;
import com.onesay.assistant.plug.api.PlugLog;
import com.onesay.assistant.plug.suggest.core.Outcome;
import com.onesay.assistant.plug.suggest.core.RoutedPresenter;
import com.onesay.assistant.plug.suggest.core.SAction;
import com.onesay.assistant.plug.suggest.core.Ports;
import com.onesay.assistant.plug.suggest.core.SceneConfig;
import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.SuggestEngine;
import com.onesay.assistant.plug.suggest.core.SuggestPlug;
import com.onesay.assistant.plug.suggest.core.Suggestion;
import com.onesay.assistant.plug.suggest.core.scenes.ClipScene;
import com.onesay.assistant.plug.suggest.core.scenes.LinkScene;
import com.onesay.assistant.plug.suggest.core.scenes.LinkTable;
import com.onesay.assistant.plug.suggest.core.scenes.MusicApps;
import com.onesay.assistant.plug.suggest.core.scenes.shot.PermPrompt;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotCandidate;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotScene;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.suggest.sources.BridgeSource;
import com.onesay.assistant.v2.suggest.sources.FileObserverScreenshotSource;
import com.onesay.assistant.v2.suggest.sources.HeadsetSource;
import com.onesay.assistant.v2.suggest.sources.MediaStoreScreenshotSource;
import com.onesay.assistant.v2.suggest.sources.PackageSource;
import com.onesay.assistant.v2.suggest.sources.RootInotifyScreenshotSource;
import com.onesay.assistant.v2.suggest.sources.ScreenshotDirProbe;
import com.onesay.assistant.v2.suggest.sources.TorchSource;
import com.onesay.assistant.v2.ui.FloatingSheetService;

import java.io.File;
import java.util.ArrayList;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.Executor;

/**
 * ★ feat/suggest：宿主适配（薄）。组装端口 → {@link SuggestPlug#start}；管理信号源生命周期；给 S1 现有链路留几个静态入口。
 *
 * <p><b>零副作用回落</b>：总开关关（release 出厂即关）时内核插件不调 {@link #start}，所有静态入口都是空操作 / 返回 false，
 * ClipboardWatcher、取件码、岛走原来的路径。
 */
public final class SuggestHost {
    private SuggestHost() {
    }

    static final String TAG = "Suggest";
    static final String K_ROOT_INOTIFY = "root_inotify";
    /** ★ fix/shot-1104：root inotifyd 默认关（App uid 的 su 被 Magisk 拒，见 RootInotifyScreenshotSource）；debug {@code --es inotify on} 才开。 */
    static final boolean ROOT_INOTIFY_DEFAULT = false;
    static final String K_PROMPT_SHOWN = "storage_prompt_shown";
    static final String K_PROMPT_TRIES = "storage_prompt_tries";

    private static SuggestPlug sPlug;
    private static HandlerThread sThread;
    private static Handler sH;
    private static volatile FloatingSheetService sSvc;
    private static final BridgeSource sClip = new BridgeSource(Signal.CLIP);
    private static final BridgeSource sPickup = new BridgeSource(Signal.PICKUP);
    private static final BridgeSource sDebug = new BridgeSource("debug");
    private static MediaStoreScreenshotSource sMs;
    private static RootInotifyScreenshotSource sRoot;
    private static FileObserverScreenshotSource sFo;
    private static RoutedPresenter sRouted;
    private static ScreenshotDirProbe sProbe;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public static synchronized boolean running() {
        return sPlug != null && sPlug.running();
    }

    public static FloatingSheetService service() {
        return sSvc;
    }

    /** 面板服务起来时（内核插件 onServiceCreate / onEnable）调；总开关关就什么都不做。 */
    public static synchronized void start(final FloatingSheetService svc) {
        if (svc == null) return;
        sSvc = svc;
        if (running() || !SuggestPlugs.masterOn(svc)) return;
        final Context ctx = svc.getApplicationContext();
        sThread = new HandlerThread("suggest-engine");
        sThread.start();
        sH = new Handler(sThread.getLooper());
        final Handler h = sH;
        SuggestPlug.Deps d = new SuggestPlug.Deps();
        d.config = new SceneConfig() {
            @Override public boolean masterOn() { return SuggestPlugs.masterOn(ctx); }
            @Override public boolean sceneOn(String sceneId, boolean def) { return SuggestPlugs.sceneSelfOn(ctx, sceneId); }
            @Override public void setSceneOn(String sceneId, boolean on) { SuggestPlugs.setScene(ctx, sceneId, on); }
            @Override public Set<String> privatePkgs() { return SuggestPlugs.privatePkgs(ctx); }
            @Override public int quietLevel() { return SuggestPlugs.quietLevel(ctx); }
        };
        d.host = new SuggestHostState(svc);
        // ★ fix/shot-1104：岛模式 → 岛卡；非岛模式 → 旧芯片卡（以前直接 SUPPRESSED，看不到任何反馈）；sys:* 卡（授权提示）不进引擎
        final RoutedPresenter rp = new RoutedPresenter(new RoutedPresenter.Mode() {
            @Override public boolean islandMode() {
                return FeatureKernel.get().island(SuggestPlugs.MASTER).islandMode();
            }
        }, new IslandSuggestPresenter(), new ChipSuggestPresenter());
        rp.setSysHandler(new RoutedPresenter.SysHandler() {
            @Override public void onSysAction(String id, String actionId) {
                Log.i(TAG, "[SUGGEST] 授权提示：点了 " + actionId);
                if (PermPrompt.ID.equals(id) && PermPrompt.ACT_GRANT.equals(actionId)) {
                    FloatingSheetService s = sSvc;
                    if (s != null) StoragePermActivity.open(s);
                }
            }

            @Override public void onSysClosed(String id, boolean timeout) {
                Log.i(TAG, "[SUGGEST] 授权提示：" + (timeout ? "到时间收起" : "被关掉") + "（只提示这一次，控制台里还能去授权）");
            }
        });
        sRouted = rp;
        d.presenter = rp;
        d.runner = new SuggestActionRunner(svc);
        d.clock = Clock.SYSTEM;
        d.sched = new Ports.Sched() {
            @Override public Ports.Cancel schedule(final Runnable r, long delayMs) {
                h.postDelayed(r, delayMs);
                return new Ports.Cancel() {
                    @Override public void cancel() {
                        h.removeCallbacks(r);
                    }
                };
            }
        };
        d.exec = new Executor() {
            @Override public void execute(Runnable r) {
                h.post(r);
            }
        };
        d.storage = new PrefsStorage(ctx);
        d.log = new PlugLog() {
            @Override public void i(String m) { Log.i(TAG, "[SUGGEST] " + m); }
            @Override public void w(String m) { Log.w(TAG, "[SUGGEST] " + m); }
        };
        d.files = new Ports.FileProbe() {
            @Override public boolean exists(String p) { return p != null && new File(p).exists(); }
            @Override public long size(String p) {
                if (p == null) return -1;
                File f = new File(p);
                return f.exists() ? f.length() : -1;
            }
        };
        d.tz = TimeZone.getDefault();
        sMs = new MediaStoreScreenshotSource(ctx, d.files);
        sFo = new FileObserverScreenshotSource(ctx, d.files);
        sMs.setPermListener(new MediaStoreScreenshotSource.PermListener() {
            @Override public void onNeedPermission() {
                maybePromptStorage("new_image");   // 没授权时媒体库来了新图（多半刚截了图）→ 一次性「去授权」
            }

            @Override public void onGranted() {
                FileObserverScreenshotSource fo = sFo;
                if (fo != null) fo.start();   // 授权后免 root 监听也跟着起来
            }
        });
        d.sources.add(sMs);
        d.sources.add(sFo);
        if (ctx.getSharedPreferences(SuggestPlugs.PREFS, Context.MODE_PRIVATE).getBoolean(K_ROOT_INOTIFY, ROOT_INOTIFY_DEFAULT)) {
            sRoot = new RootInotifyScreenshotSource(ctx, d.files);
            d.sources.add(sRoot);
        }
        d.sources.add(sClip);
        d.sources.add(sPickup);
        d.sources.add(sDebug);
        d.sources.add(new TorchSource(ctx, h));
        d.sources.add(new HeadsetSource(ctx, h));
        d.sources.add(new PackageSource(ctx, h));
        sPlug = new SuggestPlug();
        sPlug.start(d);
        sPlug.engine().setOutcomeListener(new SuggestEngine.OutcomeListener() {
            @Override public void onOutcome(Suggestion s, Outcome o, String reason) {
                clipFallbackIfNeeded(s, o, reason);
            }
        });
        if (com.onesay.assistant.v2.BuildVariant.IS_DEBUG) {
            sProbe = new ScreenshotDirProbe();
            sProbe.start();
        }
        Log.i(TAG, "[SUGGEST] 主动建议已启动 route=" + rp.route());
        if (!MediaStoreScreenshotSource.storageGranted(ctx)) {
            h.postDelayed(new Runnable() {
                @Override public void run() {
                    maybePromptStorage("start");
                }
            }, 5000L);
        }
    }

    /**
     * ★ fix/shot-1104：控制台回前台 / 授权页返回时调 —— 立刻重查存储权限，授予了就地注册 A 源、启动 B 源（不用重启服务）。任意线程。
     */
    public static void permissionPoke() {
        MediaStoreScreenshotSource ms = sMs;
        if (ms != null) ms.poke();
        FloatingSheetService svc = sSvc;
        FileObserverScreenshotSource fo = sFo;
        if (fo != null && svc != null && MediaStoreScreenshotSource.storageGranted(svc)) fo.start();
    }

    /**
     * ★ fix/shot-1104 一次性授权提示（{@link PermPrompt}：只成功显示一次；岛模式出岛卡、非岛模式出旧芯片卡）。
     * 触发：插件启动 5 s 后、没授权时媒体库来了新图（多半刚截了图）。亮屏且未锁、助手不忙才弹。
     */
    static void maybePromptStorage(String why) {
        FloatingSheetService svc = sSvc;
        RoutedPresenter rp = sRouted;
        if (svc == null || rp == null || !running()) return;
        try {
            Context ctx = svc.getApplicationContext();
            android.content.SharedPreferences sp = ctx.getSharedPreferences(SuggestPlugs.PREFS, Context.MODE_PRIVATE);
            SuggestHostState hs = new SuggestHostState(svc);
            boolean granted = MediaStoreScreenshotSource.storageGranted(ctx);
            boolean sceneOn = SuggestPlugs.masterOn(ctx) && SuggestPlugs.sceneOn(ctx, ScreenshotScene.ID);
            int tries = sp.getInt(K_PROMPT_TRIES, 0);
            if (!PermPrompt.shouldTry(granted, sceneOn, sp.getBoolean(K_PROMPT_SHOWN, false), tries, hs.screenOn(), hs.keyguardLocked())) return;
            if (hs.assistantBusy()) return;
            sp.edit().putInt(K_PROMPT_TRIES, tries + 1).apply();
            Suggestion s = Suggestion.builder("system").id(PermPrompt.ID).title(PermPrompt.TITLE).subtitle(PermPrompt.SUB).icon("screenshot")
                    .action(SAction.run(PermPrompt.ACT_GRANT, PermPrompt.LABEL_GRANT))
                    .action(new SAction(PermPrompt.ACT_LATER, PermPrompt.LABEL_LATER, SAction.DISMISS))
                    .ttl(PermPrompt.TTL_MS).priority(0).build();
            boolean ok = rp.show(s);
            if (ok) sp.edit().putBoolean(K_PROMPT_SHOWN, true).apply();
            Log.i(TAG, "[SUGGEST] 授权提示（" + why + "，第 " + (tries + 1) + " 次尝试）→ " + (ok ? "已显示 route=" + rp.route() : "没放下，下次再试"));
        } catch (Throwable t) {
            Log.w(TAG, "[SUGGEST] 授权提示异常: " + t.getClass().getSimpleName());
        }
    }

    public static synchronized void stop() {
        if (sPlug != null) sPlug.stop();
        sPlug = null;
        if (sProbe != null) sProbe.stop();
        sProbe = null;
        if (sThread != null) sThread.quitSafely();
        sThread = null;
        sH = null;
        sMs = null;
        sRoot = null;
        sFo = null;
        sRouted = null;
        Log.i(TAG, "[SUGGEST] 主动建议已停止");
    }

    private static synchronized SuggestEngine engine() {
        return sPlug == null ? null : sPlug.engine();
    }

    // ------------------------------------------------------------------ 场景 3 / 2：剪贴板（S1 ClipboardWatcher 调）

    /**
     * 这次复制由主动建议接管吗：插件在跑 + 岛形态 + 剪贴板或链接场景开着。接管时旧芯片卡不弹，岛上出 suggest 卡；
     * 岛拒绝（忙 / 面板开着 / 静默）时由 {@link #clipFallbackIfNeeded} 退回旧卡片，行为不回退。
     */
    public static boolean takeoverClip(Context ctx) {
        if (!running() || ctx == null) return false;
        if (!SuggestPlugs.sceneOn(ctx, ClipScene.ID) && !SuggestPlugs.sceneOn(ctx, LinkScene.ID)) return false;
        try {
            if (!FeatureKernel.get().island(SuggestPlugs.MASTER).islandMode()) return false;
            // 面板开着 / 任务在跑 / 锁屏息屏：岛反正放不下（还会被推迟 10 s），直接让旧卡片接手，行为与以前一样
            FloatingSheetService svc = sSvc;
            if (svc != null) {
                SuggestHostState hs = new SuggestHostState(svc);
                if (hs.assistantBusy() || !hs.screenOn() || hs.keyguardLocked()) return false;
            }
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * @param safeText 过了 ClipChipPlanner 敏感过滤、≤300 字的原文（给链接场景认域名）；敏感 / 超长传 null
     * @param labels   ClipChipPlanner 的芯片文字（换行分隔）；没有芯片传 null
     */
    public static void offerClip(Context ctx, String safeText, String labels, String title, String token, String hash) {
        StringBuilder installed = new StringBuilder();
        if (safeText != null && LinkTable.match(safeText) != null) {
            for (String p : LinkTable.allPackages()) {
                try {
                    if (ctx.getPackageManager().getLaunchIntentForPackage(p) != null) installed.append(installed.length() > 0 ? "," : "").append(p);
                } catch (Throwable ignored) {
                }
            }
        }
        sClip.emit(Signal.of(Signal.CLIP, SystemClock.elapsedRealtime(), System.currentTimeMillis(), "text", safeText,
                "len", safeText == null ? null : String.valueOf(safeText.length()), "labels", labels, "title", title, "token", token,
                "hash", hash, "installed", installed.toString()));
    }

    static void clipFallbackIfNeeded(Suggestion s, Outcome o, String reason) {
        if (s == null || !ClipFallbackRule.wants(s.sceneId, o, reason)) return;   // 频控 / 去重 / 关闭不退回
        final String clipToken = ClipFallbackRule.token(s.sceneId, s.get("token"));
        MAIN.post(new Runnable() {
            @Override public void run() {
                boolean ok = com.onesay.assistant.v2.s1.S1Hooks.clipFallback(clipToken);
                Log.i(TAG, "[SUGGEST] 岛上放不下 → 退回旧剪贴板卡片 " + (ok ? "已显示" : "无旧芯片"));
            }
        });
    }

    // ------------------------------------------------------------------ 场景 4：取件码（S1 IslandController 调）

    /** 一张取件码 / 取餐码上岛了（S1 原链路照常显示），这里只计入总频控。不在跑 = 空操作。 */
    public static void onPickupShown(String kind, String code) {
        if (!running() || code == null) return;
        sPickup.emit(Signal.of(Signal.PICKUP, SystemClock.elapsedRealtime(), System.currentTimeMillis(), "kind", kind,
                "code_hash", com.onesay.assistant.plug.suggest.core.scenes.BaseScene.hash(kind + "|" + code)));
    }

    // ------------------------------------------------------------------ 自产图片登记

    /** OneSay 自己要往外部存储写图前调（60 s 内同路径的「截图」不弹）。 */
    public static void registerOwnWrite(String path) {
        SuggestPlug p;
        synchronized (SuggestHost.class) {
            p = sPlug;
        }
        if (p != null) p.ownWrites().register(path, SystemClock.elapsedRealtime());
    }

    // ------------------------------------------------------------------ 调试 / 状态

    public static String status() {
        SuggestEngine e = engine();
        StringBuilder b = new StringBuilder();
        b.append("running=").append(e != null);
        MediaStoreScreenshotSource ms = sMs;
        RootInotifyScreenshotSource rt = sRoot;
        FileObserverScreenshotSource fo = sFo;
        RoutedPresenter rp = sRouted;
        b.append(" A=").append(ms == null ? "-" : ms.state()).append(" B=").append(fo == null ? "-" : fo.state())
                .append(" root=").append(rt == null ? "关" : rt.state()).append(" route=").append(rp == null ? "-" : rp.route())
                .append(" fg=").append(ForegroundProbe.lastVia());
        if (e != null) b.append('\n').append(e.status());
        return b.toString();
    }

    /**
     * debug 注入（TestRunReceiver {@code --es plug suggest --es inject <种类> …}，只 debug 包）：
     * {@code screenshot --es path <文件> [--es src A|B]}、{@code clip --es text <文字>}、{@code headset}、{@code torch}、
     * {@code pkg --es pkg <包名>}、{@code status}；另 {@code --es inotify on|off} 开关 root 快路径（下次启动生效）。
     */
    public static String debugIntent(Context ctx, Intent it) {
        if (it.hasExtra("inotify")) {
            boolean on = "on".equals(it.getStringExtra("inotify"));
            ctx.getSharedPreferences(SuggestPlugs.PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ROOT_INOTIFY, on).apply();
            return "root_inotify=" + on + "（下次启动生效）";
        }
        String kind = it.getStringExtra("inject");
        if (kind == null || "status".equals(kind)) return status();
        if (!running()) return "suggest 没在跑（总开关关 / 面板服务没起来）";
        long el = SystemClock.elapsedRealtime(), now = System.currentTimeMillis();
        if ("screenshot".equals(kind)) {
            String path = it.getStringExtra("path");
            if (path == null) return "缺 --es path";
            File f = new File(path);
            String src = it.hasExtra("src") ? it.getStringExtra("src") : "A";
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(path, o);
            ScreenshotCandidate c = new ScreenshotCandidate(path, -1, f.length(), now, o.outWidth, o.outHeight,
                    "A".equals(src) ? now / 1000L : 0, src.charAt(0), el, true);
            java.util.Map<String, String> a = c.toAttrs();
            String[] fg = ForegroundProbe.now(ctx, true);
            if (fg != null) {
                a.put(ScreenshotCandidate.K_FG, fg[0]);
                if (fg[1] != null) a.put(ScreenshotCandidate.K_FG_LABEL, fg[1]);
            }
            sDebug.emit(new Signal(Signal.SCREENSHOT, el, now, a));
            return "已注入截图 size=" + o.outWidth + "x" + o.outHeight + " exists=" + f.exists();
        }
        if ("clip".equals(kind)) {
            String r = com.onesay.assistant.v2.s1.S1Hooks.debugClip(it.getStringExtra("text"));
            return "剪贴板注入（走 ClipboardWatcher）→ " + r;
        }
        if ("headset".equals(kind)) {
            ArrayList<MusicApps.Usage> l = new ArrayList<MusicApps.Usage>();
            for (String p : MusicApps.KNOWN.keySet()) {
                try {
                    if (ctx.getPackageManager().getLaunchIntentForPackage(p) != null) l.add(new MusicApps.Usage(p, ForegroundProbe.label(ctx, p), 1000L + l.size()));
                } catch (Throwable ignored) {
                }
            }
            sDebug.emit(Signal.of(Signal.HEADSET, el, now, "state", "on", "kind", "debug", "candidates", MusicApps.encode(l)));
            return "已注入耳机（候选 " + l.size() + " 个，调试用假时长）";
        }
        if ("torch".equals(kind)) {
            sDebug.emit(Signal.of(Signal.TORCH, el, now, "on", "1", "on_since", String.valueOf(el - 4L * 60000L), "event", "screen_on"));
            return "已注入手电筒（开了 4 分钟 + 亮屏）";
        }
        if ("pkg".equals(kind)) {
            String p = it.getStringExtra("pkg");
            boolean launch = false;
            try {
                launch = p != null && ctx.getPackageManager().getLaunchIntentForPackage(p) != null;
            } catch (Throwable ignored) {
            }
            sDebug.emit(Signal.of(Signal.PKG_ADDED, el, now, "pkg", p, "label", p == null ? null : ForegroundProbe.label(ctx, p),
                    "launchable", launch ? "1" : "0", "replacing", "0"));
            return "已注入新装应用 launchable=" + launch;
        }
        return "看不懂 inject=" + kind;
    }
}
