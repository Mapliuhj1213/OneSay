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
            Log.i(TAG, "[PHONE] 开始：" + goal + " pkg=" + pkg + (legacy ? "（改前口径：单步 + 8 步上限）" : ""));
            res = agent.run(goal, pkg);
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
