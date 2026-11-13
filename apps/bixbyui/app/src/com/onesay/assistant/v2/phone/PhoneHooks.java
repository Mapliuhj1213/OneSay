package com.onesay.assistant.v2.phone;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.exec.ExecModeController;

import java.io.File;
import java.io.FileOutputStream;

/**
 * ★ r2/fast-screen：「替我在某 App 里做一件事」的入口（AgentLoop 工具 phone.operate / 调试广播）。
 * 一次只跑一个前台执行任务；执行期间进入执行模式（岛取代小窗 + 防误触护罩）。
 */
public final class PhoneHooks {

    private PhoneHooks() {}

    private static final String TAG = "PhoneAgent";
    public static final String PREF = "bixby_phone";
    public static final String KEY_ENABLED = "enabled";
    private static volatile boolean sBusy;
    private static volatile String sLast = "";

    public static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
    }

    /** ★ stable-screen：动作后等画面稳定再读（默认开）。 */
    public static final String KEY_SETTLE = "settle_after_action";

    public static boolean settleEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_SETTLE, true);
    }

    /** 最近一次任务的稳定读屏统计（dump 用）。 */
    private static volatile String sSettleStats = "";

    /** ★ smart-replay：成功路径回放总开关（开源版默认关）；kill 文件 phone.replay.off 存在即关。 */
    public static final String KEY_REPLAY = "smart_replay";
    public static final String KILL_REPLAY = "phone.replay.off";
    /** 自测 / 调试覆盖（null = 按设置走）。 */
    public static volatile Boolean sReplayOverride;
    private static volatile SmartReplay.Store sReplayStore;
    private static volatile String sReplayStats = "";

    public static boolean replayPref(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_REPLAY, SmartReplay.DEFAULT_ON);
        } catch (Throwable t) {
            return SmartReplay.DEFAULT_ON;
        }
    }

    public static void setReplay(Context ctx, boolean on) {
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putBoolean(KEY_REPLAY, on).apply();
    }

    public static boolean replayKilled(Context ctx) {
        try {
            return com.onesay.assistant.v2.kernel.FeatureSwitch.killFilePresent(ctx, KILL_REPLAY);
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean replayEnabled(Context ctx) {
        return SmartReplay.decide(sReplayOverride, replayKilled(ctx), replayPref(ctx));
    }

    /** 回放存储（应用私有 files/phone_replay.json，一个进程一份）。 */
    public static SmartReplay.Store replayStore(Context ctx) {
        SmartReplay.Store s = sReplayStore;
        if (s == null) {
            synchronized (PhoneHooks.class) {
                if (sReplayStore == null)
                    sReplayStore = new SmartReplay.Store(new File(ctx.getApplicationContext().getFilesDir(), SmartReplay.FILE));
                s = sReplayStore;
            }
        }
        return s;
    }

    /** 设置里「清空记住的步骤」。 */
    public static void clearReplay(Context ctx) {
        replayStore(ctx).clear();
        Log.i(TAG, "[PHONE] 已清空记住的成功步骤");
    }

    public static boolean busy() {
        return sBusy;
    }

    public static String last() {
        return sLast;
    }

    /** 阻塞执行（后台线程）。 */
    /**
     * ★ 11-04 B（「把三星浏览器的默认搜索引擎改为必应」：模型传 app=「三星浏览器（com.sec.android.app.sbrowser）」，
     * getLaunchIntentForPackage 拿整串当包名 → 拉起失败，执行从桌面开始，靠读屏 + 截图识别绕路，中途进了 Chrome）：
     * 参数里带包名就只取包名；纯标签原样返回（驱动层认不出就不拉起）。
     */
    public static String normalizePkg(String app) {
        if (app == null) return "";
        String a = app.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([a-zA-Z][a-zA-Z0-9_]*(?:\\.[a-zA-Z0-9_]+)+)").matcher(a);
        while (m.find()) {
            String p = m.group(1);
            if (p.split("\\.").length >= 3) return p;
        }
        return a;
    }

    public static PhoneAgent.Result runBlocking(Context ctx, String goal, String pkg, boolean legacy, boolean execMode) {
        return runBlocking(ctx, goal, pkg, legacy, execMode, null);
    }

    /** script != null：用脚本化 planner（debug 直测执行层，不需要大模型 Key）。 */
    public static PhoneAgent.Result runBlocking(Context ctx, String goal, String pkg, boolean legacy, boolean execMode, PhoneAgent.Planner script) {
        Context app = ctx.getApplicationContext();
        PhoneAgent.Result res;
        if (sBusy) {
            res = new PhoneAgent.Result();
            res.status = PhoneAgent.Status.FAILED;
            res.reason = "已有一个操作任务在进行";
            return res;
        }
        sBusy = true;
        sSettleStats = "";
        sReplayStats = "";
        FastScreenDriver drv = new FastScreenDriver(app);
        LlmPhonePlanner planner = new LlmPhonePlanner(com.onesay.assistant.v2.errand.ErrandHooks.llmFor(app));
        final ExecModeController exec = execMode ? new ExecModeController(app, PhoneAgent.clip(goal, 16), pkg) : null;
        try {
            if (script == null && !planner.ready()) {
                res = new PhoneAgent.Result();
                res.status = PhoneAgent.Status.FAILED;
                res.reason = "没有配置大模型 Key";
                return res;
            }
            MikaExec mika0 = com.onesay.assistant.v2.mikaplug.MikaPlugs.phoneExec(app);   // ★ mika-plug：B2 可用时助手不可用也能跑
            if (mika0 != null) drv.setMika(mika0);
            if (!drv.observeOk() && !drv.b2Ready()) {
                res = new PhoneAgent.Result();
                res.status = PhoneAgent.Status.FAILED;
                res.reason = "读屏助手不可用：" + com.onesay.assistant.v2.fastscreen.FastScreen.get(app).lastError();
                return res;
            }
            PhoneAgent.Host host = exec != null ? exec : headlessHost();
            if (exec != null) exec.begin(drv);
            final PhoneAgent.Host h = host;
            PhoneAgent agent = new PhoneAgent(new TrackingDriver(drv, exec), script != null ? script : planner, h);
            MikaExec mika = mika0;   // ★ mika-plug：执行层插件（全关 = null，原行为）
            if (mika != null) {
                drv.setMika(mika);
                agent.mika = mika;
                Log.i(TAG, "[PHONE] mika-plug 执行层：" + mika.f);
            }
            if (script != null) Log.i(TAG, "[PHONE] 用脚本化 planner（debug 直测，不调大模型）");
            agent.legacyOneStep = legacy;
            if (settleEnabled(app)) {   // ★ stable-screen：动作后等画面稳定再读 + 执行前核对编号（偏好里可关，排查用）
                agent.settle = StableRead.Params.defaults();
                Log.i(TAG, "[PHONE] 画面稳定再读：" + agent.settle);
            }
            if (replayEnabled(app) && !legacy) agent.replay = replayStore(app);   // ★ smart-replay：开关关 = null，原行为
            Log.i(TAG, "[PHONE] 开始：" + goal + " pkg=" + pkg + (legacy ? "（改前口径：单步 + 8 步上限）" : ""));
            res = agent.run(goal, pkg);
            sSettleStats = "settle=" + (agent.settle != null) + " settleReads=" + agent.settleReads + " unstable=" + agent.settleUnstable
                    + " refStale=" + agent.refStale + " refMoved=" + agent.refMoved;
            sReplayStats = "replay=" + (agent.replay != null) + " hit=" + agent.replayHit + " fallback=" + agent.replayFallback
                    + " voided=" + agent.replayVoided + " handoff=" + agent.replayHandoff + " recorded=" + agent.replayRecorded
                    + " replayedSteps=" + agent.replaySteps + (agent.replayNote.length() > 0 ? " note=" + agent.replayNote : "")
                    + (agent.replay != null ? " | " + agent.replay.stats() : "");
            if (agent.replay != null) Log.i(TAG, "[PHONE] 回放：" + sReplayStats);
            Log.i(TAG, "[PHONE] 结束 " + res.status + " 模型 " + res.modelCalls + " 次 动作 " + res.actions + " 个 " + res.ms + "ms："
                    + (res.answer.length() > 0 ? res.answer : res.reason));
            if (exec != null) exec.finish(res.status == PhoneAgent.Status.DONE, res.status == PhoneAgent.Status.DONE ? res.answer : res.reason);
            return res;
        } catch (Throwable t) {
            Log.w(TAG, "[PHONE] 异常", t);
            if (exec != null) exec.finish(false, "出错了：" + t.getMessage());
            res = new PhoneAgent.Result();
            res.status = PhoneAgent.Status.FAILED;
            res.reason = String.valueOf(t);
            return res;
        } finally {
            sBusy = false;
            sLast = goal;
            dump(app, goal, drv, planner, exec);
        }
    }

    /** 调试：把这次任务的统计写到 files/phone_last.txt。 */
    static void dump(Context app, String goal, FastScreenDriver d, LlmPhonePlanner p, ExecModeController e) {
        try {
            StringBuilder b = new StringBuilder();
            b.append("goal=").append(goal).append('\n');
            b.append("observes=").append(d.observes).append(" avgMs=").append(d.observes == 0 ? 0 : d.observeMsTotal / d.observes)
                    .append(" maxMs=").append(d.observeMsMax).append(" a11yClicks=").append(d.a11yClicks).append(" injectedTaps=")
                    .append(d.injectedTaps).append(" setTexts=").append(d.setTexts).append(" pastes=").append(d.pastes).append('\n');
            b.append("modelMs=").append(p.callMs).append('\n');
            b.append(sSettleStats).append('\n');
            b.append(sReplayStats).append('\n');
            if (e != null) {
                b.append("stray=").append(e.state.strayTouches).append(" navBack=").append(e.navBack).append(" navHome=").append(e.navHome)
                        .append(" confirms=").append(e.confirms).append('\n');
                synchronized (e.events) {
                    for (String s : e.events) b.append("ev ").append(s).append('\n');
                }
            }
            FileOutputStream o = new FileOutputStream(new File(app.getFilesDir(), "phone_last.txt"));
            o.write(b.toString().getBytes("UTF-8"));
            o.close();
        } catch (Throwable ignored) {
        }
    }

    /** 没有执行模式时（单测 / 调试）：不暂停、不确认（敏感步骤一律拒绝）。 */
    static PhoneAgent.Host headlessHost() {
        return new PhoneAgent.Host() {
            @Override public void onStep(String w, int n) {
            }

            @Override public boolean confirm(String title, String detail, String[] out) {
                Log.i(TAG, "[PHONE] 无执行模式：敏感步骤一律拒绝 " + detail);
                return false;
            }

            @Override public String takeoverRequested() {
                return null;
            }

            @Override public PhoneAgent.Resume awaitResume(String why, String[] s) {
                return PhoneAgent.Resume.STOP;
            }

            @Override public boolean stopRequested() {
                return false;
            }
        };
    }

    /** 读屏时顺手把岛上的 App 图标换成当前前台。 */
    static final class TrackingDriver implements PhoneAgent.MikaDriver {
        final FastScreenDriver d;
        final ExecModeController e;

        TrackingDriver(FastScreenDriver d, ExecModeController e) {
            this.d = d;
            this.e = e;
        }

        @Override public UiTree observe(UiTree.Ids ids) {
            UiTree t = d.observe(ids);
            if (t != null && e != null) e.setPkg(t.pkg);
            return t;
        }

        @Override public boolean tap(UiTree.El el) {
            return d.tap(el);
        }

        @Override public boolean tapXY(int x, int y) {   // ★ mika-plug
            return d.tapXY(x, y);
        }

        @Override public UiTree awaitChange(UiTree before, UiTree.Ids ids) {   // ★ mika-plug
            UiTree t = d.awaitChange(before, ids);
            if (t != null && e != null) e.setPkg(t.pkg);
            return t;
        }

        @Override public boolean longPress(UiTree.El el, int ms) {
            return d.longPress(el, ms);
        }

        @Override public String type(UiTree.El el, String text) {
            return d.type(el, text);
        }

        @Override public boolean scroll(UiTree.El el, int dir) {
            return d.scroll(el, dir);
        }

        @Override public boolean swipe(int a, int b, int c, int dd, int ms) {
            return d.swipe(a, b, c, dd, ms);
        }

        @Override public boolean key(String k) {
            return d.key(k);
        }

        @Override public boolean launch(String pkg) {
            return d.launch(pkg);
        }

        @Override public void waitIdle(int i, int t) {
            d.waitIdle(i, t);
        }

        @Override public String describeScreen() {
            return d.describeScreen();
        }

        @Override public long now() {
            return d.now();
        }

        @Override public void sleep(long ms) {
            d.sleep(ms);
        }
    }
}
