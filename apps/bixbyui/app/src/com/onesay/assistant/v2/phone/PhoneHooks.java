package com.onesay.assistant.v2.phone;

import com.onesay.assistant.v2.util.SafeLog;
import android.content.Context;

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
    /** [overnight-1119] 正在做的目标（拒绝新任务时说是哪个）。 */
    private static volatile String sCurrentGoal = "";
    private static volatile String sLast = "";

    public static boolean enabled(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true);
    }

    /** ★ stable-screen：动作后等画面稳定再读（默认开）。 */
    public static final String KEY_SETTLE = "settle_after_action";

    public static boolean settleEnabled(Context ctx) {
        return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_SETTLE, true);
    }

    /** ★ a11y-labels：精准标签 + 动作绑定快照（默认开；偏好里可关，排查用）。 */
    public static final String KEY_LABELS = "a11y_labels";

    public static boolean labelsEnabled(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(KEY_LABELS, true);
        } catch (Throwable t) {
            return true;
        }
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
        SafeLog.i(TAG, "[PHONE] 已清空记住的成功步骤");
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
        return runBlocking(ctx, goal, pkg, legacy, execMode, script, null);
    }

    /**
     * [chat-1124] userSaid = 用户原话（不是模型改写的 goal）：从里面认出点名的 App，打开前核对「要打开的 App 和用户说的对不对得上」、
     * 做的过程中前台漂到别的 App 时提醒模型。null = 用 goal 认（直测入口）。
     */
    public static PhoneAgent.Result runBlocking(Context ctx, String goal, String pkg, boolean legacy, boolean execMode, PhoneAgent.Planner script,
                                                String userSaid) {
        Context app = ctx.getApplicationContext();
        PhoneAgent.Result res;
        // ★ overnight-1119 魅族：暂停中（你来操作 / 卡住等继续）的旧任务不挡新任务——停掉它，等它收尾（最多 5 秒），岛上提示换成了新任务
        if (sBusy) {
            ExecModeController cur = ExecModeController.current();
            com.onesay.assistant.v2.exec.ExecState.NewTask nt = com.onesay.assistant.v2.exec.ExecState.onNewTask(sBusy,
                    cur != null && cur.state.paused(), cur != null && cur.state.stopped());
            if (nt == com.onesay.assistant.v2.exec.ExecState.NewTask.REPLACE && cur != null) {
                SafeLog.i(TAG, "[PHONE] 暂停中的任务让给新任务");
                cur.stop("新任务来了，换成新任务");
                long end = android.os.SystemClock.elapsedRealtime() + 5000;
                while (sBusy && android.os.SystemClock.elapsedRealtime() < end) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }
        // ★ a11y-labels：屏幕输入独占租约（语音任务 / 定时任务 / 编号点选不抢屏；持有方异常没释放，到期自动作废）
        final com.onesay.assistant.v2.a11ylabel.ScreenLease.Lease lease = com.onesay.assistant.v2.a11ylabel.ScreenLease.get().tryAcquire("手机操作任务", com.onesay.assistant.v2.a11ylabel.ScreenLease.TASK_TTL_MS);
        if (sBusy || lease == null) {
            if (lease != null) com.onesay.assistant.v2.a11ylabel.ScreenLease.get().release(lease);
            res = new PhoneAgent.Result();
            res.status = PhoneAgent.Status.FAILED;
            res.reason = sBusy ? com.onesay.assistant.v2.exec.ExecState.refuseText(sCurrentGoal) : com.onesay.assistant.v2.a11ylabel.ScreenLease.get().busySay();
            return res;
        }
        sBusy = true;
        sCurrentGoal = goal;
        sSettleStats = "";
        sReplayStats = "";
        FastScreenDriver drv = new FastScreenDriver(app);
        LlmPhonePlanner planner = new LlmPhonePlanner(com.onesay.assistant.v2.errand.ErrandHooks.llmFor(app));
        final ExecModeController exec = execMode ? new ExecModeController(app, PhoneAgent.clip(goal, 16), pkg) : null;
        final com.onesay.assistant.v2.privacy.Redactor[] redactorRef = new com.onesay.assistant.v2.privacy.Redactor[1];
        try {
            if (script == null && !planner.ready()) {
                res = new PhoneAgent.Result();
                res.status = PhoneAgent.Status.FAILED;
                res.reason = "没有配置大模型 Key";
                return res;
            }
            MikaExec mika0 = com.onesay.assistant.v2.mikaplug.MikaPlugs.phoneExec(app);   // ★ mika-plug：B2 可用时助手不可用也能跑
            if (mika0 != null) drv.setMika(mika0);
            // ★ a11y-1118：助手不可用（只有 Shizuku / 没有 root）但无障碍服务连着 → 整个任务走无障碍；都不行 → 原因带「打开无障碍」入口
            com.onesay.assistant.v2.a11y.A11yState.Choice be = drv.chooseBackend();
            com.onesay.assistant.v2.a11y.A11yEntry.note(be.offerA11ySettings);
            if (be.backend == com.onesay.assistant.v2.a11y.A11yState.Backend.NONE) {
                res = new PhoneAgent.Result();
                res.status = PhoneAgent.Status.FAILED;
                res.reason = be.reason;
                return res;
            }
            PhoneAgent.Host host = exec != null ? exec : headlessHost();
            if (exec != null) exec.begin(drv);
            final PhoneAgent.Host h = host;
            PhoneAgent agent = new PhoneAgent(new TrackingDriver(drv, exec), script != null ? script : planner, h);
            if (!legacy) {   // [chat-1124] 用户点名的 App：打开前核对 + 前台漂移提醒（改前口径不加）
                try {
                    AppNameMatch.Guard g = new AppNameMatch.Guard(userSaid != null && userSaid.trim().length() > 0 ? userSaid : goal,
                            new com.onesay.assistant.v2.executor.IntentExecutor(app).labelIndex(), app.getPackageName());
                    agent.appGuard = g;
                    SafeLog.i(TAG, "[PHONE] 用户点名的 App：" + g.named());
                } catch (Throwable t) {
                    SafeLog.w(TAG, "[PHONE] 点名 App 核对没装上（按改前处理）: " + t);
                }
            }
            MikaExec mika = mika0;   // ★ mika-plug：执行层插件（全关 = null，原行为）
            if (mika != null) {
                drv.setMika(mika);
                agent.mika = mika;
                SafeLog.i(TAG, "[PHONE] mika-plug 执行层：" + mika.f);
            }
            if (script != null) SafeLog.i(TAG, "[PHONE] 用脚本化 planner（debug 直测，不调大模型）");
            agent.legacyOneStep = legacy;
            if (settleEnabled(app)) {   // ★ stable-screen：动作后等画面稳定再读 + 执行前核对编号（偏好里可关，排查用）
                agent.settle = StableRead.Params.defaults();
                SafeLog.i(TAG, "[PHONE] 画面稳定再读：" + agent.settle);
            }
            agent.labels = !legacy && labelsEnabled(app);
            // ★ fix/1123-bug2：没配识屏 Key → 这次任务从头就不截屏、不给模型「要看画面」
            agent.visionKnownOff = !legacy && !com.onesay.assistant.v2.vision.VisionAvailability.usableForPhoneAgent(app);
            if (agent.visionKnownOff) SafeLog.i(TAG, "[PHONE] 识屏用不了（没配识屏 Key 或看图兜底关着）：这次只靠读屏");
            if (!legacy) agent.whitelist = com.onesay.assistant.v2.whitelist.WhitelistAndroid.state(app);   // ★ app-whitelist-1116   // ★ a11y-labels：改前口径（legacy）保持原样
            if (replayEnabled(app) && !legacy) agent.replay = replayStore(app);   // ★ smart-replay：开关关 = null，原行为
            // ★ borrow-1112：发给模型前本地脱敏（默认开；映射只在这次任务的内存里）、本轮污染标记、等待期间画面变化进时间轴
            if (com.onesay.assistant.v2.privacy.PrivacySettings.REDACT.on(app)) agent.redactor = new com.onesay.assistant.v2.privacy.Redactor();
            if (RiskLabel.taskTainted()) {
                agent.tainted = true;
                agent.taintedBy = "本轮任务读过外部内容";
            }
            agent.meanwhile = new PhoneAgent.Meanwhile() {
                @Override public void onChange(String line) {
                    com.onesay.assistant.v2.timeline.TimelineNotes.post(line);
                }
            };
            redactorRef[0] = agent.redactor;
            SafeLog.i(TAG, "[PHONE] 开始：" + com.onesay.assistant.v2.util.LogText.u(goal) + " pkg=" + pkg + (legacy ? "（改前口径：单步 + 8 步上限）" : ""));
            res = agent.run(goal, pkg);
            sSettleStats = "settle=" + (agent.settle != null) + " settleReads=" + agent.settleReads + " unstable=" + agent.settleUnstable
                    + " refStale=" + agent.refStale + " refMoved=" + agent.refMoved
                    + " labels=" + agent.labels + " bindRefused=" + agent.bindRefused + " bindRebound=" + agent.bindRebound;
            sReplayStats = "replay=" + (agent.replay != null) + " hit=" + agent.replayHit + " fallback=" + agent.replayFallback
                    + " voided=" + agent.replayVoided + " handoff=" + agent.replayHandoff + " recorded=" + agent.replayRecorded
                    + " replayedSteps=" + agent.replaySteps + (agent.replayNote.length() > 0 ? " note=" + agent.replayNote : "")
                    + (agent.replay != null ? " | " + agent.replay.stats() : "");
            if (agent.replay != null) SafeLog.i(TAG, "[PHONE] 回放：" + sReplayStats);
            if (agent.riskTightened > 0 || agent.taintConfirms > 0 || agent.tainted)
                SafeLog.i(TAG, "[PHONE] 风险：模型标高多问 " + agent.riskTightened + " 次，污染后发送类多问 " + agent.taintConfirms + " 次" + (agent.tainted ? "（" + agent.taintedBy + "）" : ""));
            if (agent.redactor != null && agent.redactor.size() > 0) SafeLog.i(TAG, "[PHONE] 脱敏：" + agent.redactor.kindsSummary());   // 只记类别和个数，不记值
            SafeLog.i(TAG, "[PHONE] 结束 " + res.status + " 模型 " + res.modelCalls + " 次 动作 " + res.actions + " 个 " + res.ms + "ms："
                    + com.onesay.assistant.v2.util.LogText.u(res.answer.length() > 0 ? res.answer : res.reason));
            if (exec != null) exec.finish(res.status == PhoneAgent.Status.DONE, res.status == PhoneAgent.Status.DONE ? res.answer : res.reason);
            return res;
        } catch (Throwable t) {
            SafeLog.w(TAG, "[PHONE] 异常", t);
            if (exec != null) exec.finish(false, "出错了：" + t.getMessage());
            res = new PhoneAgent.Result();
            res.status = PhoneAgent.Status.FAILED;
            res.reason = String.valueOf(t);
            return res;
        } finally {
            if (redactorRef[0] != null) redactorRef[0].clear();   // ★ borrow-1112：映射只活到任务结束
            sBusy = false;
            com.onesay.assistant.v2.a11ylabel.ScreenLease.get().release(lease);
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
                SafeLog.i(TAG, "[PHONE] 无执行模式：敏感步骤一律拒绝 " + detail);
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
    static final class TrackingDriver implements PhoneAgent.MikaDriver, PhoneAgent.LaunchInfo {
        @Override public boolean lastLaunchSkipped() {   // [fix-1123-dexec B40]
            return d.lastLaunchSkipped();
        }

        @Override public String lastLaunchNote() {   // [exec-1124 C]
            return d.lastLaunchNote();
        }

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
