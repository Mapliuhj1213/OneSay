package com.onesay.assistant.v2.phone;

import com.onesay.assistant.v2.util.SafeLog;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ★ r2/fast-screen：操作第三方 App 的代理主循环（纯 Java：读屏 / 动作 / 模型 / 界面宿主都经接口注入，JVM 可测）。
 *
 * <pre>
 *   observe → 循环 {
 *     宿主要求暂停（用户点岛「接手」/ 按了返回键 / 主页键）？→ 等用户「继续」→ 重新读屏
 *     预算：模型调用 / 总时长到了 → 问用户要不要继续；无进展 / 重复动作 → 判卡住求助
 *     问模型（附精简控件树、上一批的结果和界面差异）→
 *       steps：BatchRunner 逐步执行，每步自带等待条件，代码等页面稳定，不为「还在加载」问模型；
 *              敏感步骤（付款 / 登录 / 验证 / 发送 / 删除）执行前暂停，等用户在岛上确认
 *       collect：逐屏下滑收集长列表（去重）
 *       done / ask
 *   }
 * </pre>
 * 部分移植自 Mika（Apache-2.0，Copyright 2026 wangzan101）：元素表 + 批量动作 + 代码侧等待 + 动作后带回界面变化；
 * mika-plug 插件（{@link MikaExec}）打开时，动作效果判定、守卫阶梯、风险 / 节奏、Mika 步骤写法用的是照 Mika 翻译的 Java 版
 * （com.onesay.assistant.plug.*.core，文件头有 Apache-2.0 改动声明）；插件全关时是这里原来的实现。
 */
public final class PhoneAgent {

    // ------------------------------------------------------------------ 注入接口

    /** 读屏 / 动作（Android 侧：FastScreen 常驻助手 + 防误触护罩）。所有方法阻塞、在后台线程调用。 */
    public interface Driver {
        UiTree observe(UiTree.Ids ids);

        /** 点元素（先无障碍点击，退回注入点按）。 */
        boolean tap(UiTree.El e);

        boolean longPress(UiTree.El e, int ms);

        /** 写文字（中文可）；返回读回的内容（null = 失败）。e 为 null = 当前输入焦点。 */
        String type(UiTree.El e, String text);

        /** @return 是否滚动了（false = 到头）。e 为 null = 屏幕中部。 */
        boolean scroll(UiTree.El e, int dir);

        boolean swipe(int x1, int y1, int x2, int y2, int ms);

        /** back / home / enter / search / delete。 */
        boolean key(String key);

        boolean launch(String pkg);

        /** 等界面安静 idleMs（事件驱动），最多 timeoutMs。 */
        void waitIdle(int idleMs, int timeoutMs);

        /** 树不够用时的视觉描述（没有返回 null）。 */
        String describeScreen();

        long now();

        void sleep(long ms);
    }

    /** ★ mika-plug：读屏后端额外提供的能力（FastScreenDriver 实现；JVM 假驱动可以不实现）。 */
    public interface MikaDriver extends Driver {
        /** 0–999 坐标点按（手势 → 注入）。 */
        boolean tapXY(int x999, int y999);

        /** mika.changewait：动作后等界面稳定再判变没变（事件安静 400 ms 且树变了，最多 2.5 s），返回最后读到的界面。 */
        UiTree awaitChange(UiTree before, UiTree.Ids ids);
    }

    public interface Planner {
        /** 返回模型原文（JSON）；null = 模型不可用。 */
        String next(String system, String user);
    }

    /** 界面宿主（Android 侧：灵动岛 + 护罩 + 返回键观察）。 */
    public interface Host {
        void onStep(String shortWord, int stepNo);

        /** 敏感步骤 / 模型提问：阻塞等用户（true = 允许 / 已处理，false = 拒绝 / 停止）。answerOut[0] 收用户补充的话。 */
        boolean confirm(String title, String detail, String[] answerOut);

        /** 用户是否要求接手（点岛「接手」、按返回 / 主页键）；取后清零。 */
        String takeoverRequested();

        /** 暂停等用户「继续」：返回 RESUME / STOP，补充的话放进 supplementOut[0]。 */
        Resume awaitResume(String why, String[] supplementOut);

        boolean stopRequested();
    }

    public enum Resume { RESUME, STOP }

    public enum Status { DONE, STOPPED, FAILED }

    public static final class Result {
        public Status status;
        public String answer = "";
        public String reason = "";
        public int modelCalls;
        public int actions;
        public long ms;
        public int batches;
        public final List<String> trace = new ArrayList<String>();
        /** ★ borrow-1112：每一步的结构化回执（执行了的和停下的那一步；按发生顺序）。 */
        public final List<StepReceipt> receipts = new ArrayList<StepReceipt>();
    }

    /** ★ borrow-1112：等待期间画面变化的去处（真机 = 执行时间轴的一行说明）。 */
    public interface Meanwhile {
        void onChange(String line);
    }

    // ------------------------------------------------------------------ 配置

    /** 旧行为对照（改前）：模型每次只给一步、无代码侧等待、按动作步数上限 —— 只用于改前改后的对比测试。 */
    public boolean legacyOneStep;
    public int legacyMaxSteps = 8;
    /** ★ fast-screen-v3：无人值守（定时任务等）= 8 次调用 / 3 分钟，到上限不问、直接收尾并注明「未完成」。 */
    public boolean unattended;
    public int maxElementLines = 120;
    /** ★ mika-plug：Mika 执行层插件（null 或全关 = 原行为）。 */
    public MikaExec mika;
    /**
     * ★ stable-screen：动作之后读屏前先等画面稳定（{@link StableRead}），执行一批里第一个指名元素的步骤前重读核对编号。
     * null = 关（原行为，读屏次数与改前一致）；真机入口 {@link PhoneHooks} 打开。
     */
    public StableRead.Params settle;
    /** ★ stable-screen 统计：等稳定多读的次数、超时没稳定的次数、编号失效拦下的步数、核对时发现元素挪了位置的次数。 */
    public int settleReads, settleUnstable, refStale, refMoved;
    /**
     * ★ smart-replay：成功路径回放（{@link SmartReplay}）。null = 关（原行为：不录、不放，读屏 / 模型调用与改前一致）；
     * 真机入口 {@link PhoneHooks} 按开关给。
     */
    public SmartReplay.Store replay;
    /** ★ smart-replay 统计（本次任务）：命中（整条回放完）、降级（回放失败交还模型）、作废、按计划交还（不存原文的输入 / 用户拒绝 / 接手）、新录、回放了几步。 */
    public int replayHit, replayFallback, replayVoided, replayHandoff, replayRecorded, replaySteps;
    /** ★ smart-replay：本次任务的回放 / 录制说明（phone_last.txt 用）。 */
    public String replayNote = "";
    /**
     * ★ borrow-1112：本轮任务已经读过网页、通知、短信等外部文字（调用方 AgentLoop 传下来；代理自己读到浏览器 / 短信 / 邮件 / 通知栏界面时也置位）。
     * 置位后任何发送 / 分享 / 提交类步骤一律先确认（{@link RiskLabel#taintedSendLike}）。
     */
    public boolean tainted;
    /** 第一次被什么污染的（日志 / 统计用）。 */
    public String taintedBy = "";
    /** ★ borrow-1112：发给模型前本地脱敏（null = 不脱敏，原行为）。 */
    public com.onesay.assistant.v2.privacy.Redactor redactor;
    /** ★ borrow-1112：wait_until 等待期间画面上出现 / 消失的文字送去哪里（null = 只写进结果）。 */
    public Meanwhile meanwhile;
    /** ★ borrow-1112 统计：模型自报风险让本地多问了几次、污染后发送类多问了几次。 */
    public int riskTightened, taintConfirms;

    private final Driver mD;
    private final Planner mP;
    private final Host mH;
    private final UiTree.Ids mIds = new UiTree.Ids();

    public PhoneAgent(Driver d, Planner p, Host h) {
        mD = d;
        mP = p;
        mH = h;
    }

    public Result run(String goal, String startPkg) {
        Result res = new Result();
        mRes = res;
        long t0 = mD.now();
        TaskBudget budget = TaskBudget.forTask(unattended, t0);
        List<String> history = new ArrayList<String>();
        if (startPkg != null && startPkg.length() > 0) {
            mH.onStep("打开应用", 0);
            mD.launch(startPkg);
            mD.waitIdle(400, 4000);
        }
        UiTree screen = startPkg != null && startPkg.length() > 0 ? observeAfterAction("打开应用") : mD.observe(mIds);
        String lastBatchReport = "";
        boolean wantShot = false;
        int stepNo = 0;
        mRec = null;
        if (replay != null) {   // ★ smart-replay：有记住的成功路径就先回放（不问模型），否则这次边做边录
            String keyPkg = startPkg != null && startPkg.length() > 0 ? startPkg : screen != null ? screen.pkg : "";
            String skip = legacyOneStep ? "改前口径" : mika != null && (mika.f.screen || mika.f.dosteps) ? "Mika 读屏 / 写法插件开着" : SmartReplay.skipReason(goal, keyPkg);
            if (skip != null) {
                replayNote = "不录不放：" + skip;
                res.trace.add("replay skip " + skip);
            } else {
                String key = SmartReplay.key(goal, keyPkg);
                SmartReplay.Path path = replay.get(key);
                if (path == null) {
                    mRec = new SmartReplay.Recorder(key, keyPkg);
                    mRec.onScreen(screen);
                } else {
                    ReplayOut ro = replayPath(path, screen, budget, stepNo, res);
                    stepNo = ro.stepNo;
                    screen = ro.screen;
                    if (ro.stopAll) return finish(res, Status.STOPPED, "", "已停止", budget, t0);
                    history.add(ro.history);
                    lastBatchReport = ro.report;
                }
            }
        }
        while (true) {
            if (mH.stopRequested()) return finish(res, Status.STOPPED, "", "已停止", budget, t0);
            // ① 用户接手（批内 / 收集时先取走的接手请求在这里补上）
            String tk = drainReinjectedTakeover();
            if (tk == null) tk = mH.takeoverRequested();
            if (tk != null) {
                long w0 = mD.now();
                String[] sup = new String[1];
                Resume r = mH.awaitResume(tk, sup);
                budget.pausedMs += mD.now() - w0;
                if (r == Resume.STOP) return finish(res, Status.STOPPED, "", "用户停止", budget, t0);
                if (mRec != null) mRec.taint("用户接手过（中间的手动操作没记下来）");
                history.add("用户接手（" + tk + "）后点了继续" + (sup[0] != null && sup[0].length() > 0 ? "，补充：" + sup[0] : ""));
                budget.resetProgress();
                screen = mD.observe(mIds);
                lastBatchReport = "（用户刚接手过，界面已重新读取）";
                continue;
            }
            // ② 预算
            TaskBudget.Verdict v = legacyOneStep ? legacyCheck(budget) : budget.check(mD.now());
            if (v != TaskBudget.Verdict.OK) {
                res.trace.add("budget " + v + " " + budget.reason);
                if (v == TaskBudget.Verdict.HARD_STOP || legacyOneStep) {
                    return finish(res, Status.FAILED, "", budget.reason, budget, t0);
                }
                if (budget.unattended) {   // 无人值守：没人能回答「还要继续吗」→ 收尾
                    return finish(res, Status.FAILED, "", budget.unfinishedReason(), budget, t0);
                }
                long w0 = mD.now();
                String[] sup = new String[1];
                String title = v == TaskBudget.Verdict.STUCK ? "好像卡住了：" + budget.reason : budget.reason + "，还要继续吗？";
                Resume r = mH.awaitResume(title, sup);
                budget.pausedMs += mD.now() - w0;
                if (r == Resume.STOP) return finish(res, Status.STOPPED, "", budget.reason, budget, t0);
                if (v == TaskBudget.Verdict.LIMIT) budget.extend();
                budget.resetProgress();
                history.add("（" + budget.reason + "，用户让继续" + (sup[0] != null && sup[0].length() > 0 ? "，补充：" + sup[0] : "") + "）");
                screen = mD.observe(mIds);
                continue;
            }
            // ③ 问模型
            String desc = null;
            if (screen != null && (wantShot || !UiTree.Q_OK.equals(screen.quality()))) desc = mD.describeScreen();
            wantShot = false;
            noteTaint(screen, res);
            String user = PhonePrompt.user(goal, screen, desc, history, lastBatchReport, budget.info(mD.now()), maxElementLines);
            if (redactor != null) user = redactor.redact(user);   // ★ borrow-1112：手机号 / 身份证 / 银行卡 / 邮箱 / 验证码换成占位符再发
            budget.onModelCall();
            mH.onStep("思考", stepNo);
            String sys = legacyOneStep ? PhonePrompt.systemLegacy() : PhonePrompt.system();
            if (mika != null) sys = sys + mika.promptAddendum();
            String out = mP.next(sys, user);
            if (out == null) return finish(res, Status.FAILED, "", "模型没有返回", budget, t0);
            PhonePlan plan = PhonePlan.parse(out, mika != null && mika.f.dosteps);
            budget.onTurn(screen == null ? "" : screen.signature());
            res.trace.add("model#" + budget.calls + " " + plan.kind + (plan.error.length() > 0 ? " err=" + plan.error : "") + " " + clip(out, 300));
            switch (plan.kind) {
                case DONE:
                    return finish(res, Status.DONE, restore(plan.answer), "", budget, t0);
                case NOT_DONE:   // ★ mika-plug（mika.dosteps）：模型如实放弃
                    return finish(res, Status.FAILED, "", plan.answer.length() > 0 ? restore(plan.answer) : "没有完成", budget, t0);
                case ASK: {
                    if (mRec != null) mRec.taint("中途问过用户（回放会悄悄沿用当时的决定）");
                    long w0 = mD.now();
                    String[] ans = new String[1];
                    boolean ok = mH.confirm("需要你决定", restore(plan.answer), ans);
                    budget.pausedMs += mD.now() - w0;
                    if (!ok) return finish(res, Status.STOPPED, "", "用户没有同意：" + plan.answer, budget, t0);
                    history.add("问用户「" + clip(plan.answer, 40) + "」→ " + (ans[0] != null && ans[0].length() > 0 ? ans[0] : "同意"));
                    budget.resetProgress();
                    screen = mD.observe(mIds);
                    lastBatchReport = "";
                    continue;
                }
                case SHOT:
                    wantShot = true;
                    lastBatchReport = "（下一轮附屏幕的视觉描述）";
                    continue;
                case COLLECT: {
                    if (mRec != null) mRec.taint("收集列表的步骤不回放");
                    mH.onStep("收集列表", ++stepNo);
                    String got = collect(screen, plan.collectUntil, plan.collectPages, budget);
                    UiTree after = mD.observe(mIds);
                    lastBatchReport = "收集结果（逐屏下滑，已去重）：\n" + got + "\n" + UiTree.diff(screen, after, 8);
                    history.add("collect until=" + plan.collectUntil);
                    screen = after;
                    continue;
                }
                case INVALID:
                    lastBatchReport = "上一条输出无效：" + plan.error + "。请按格式输出 JSON。";
                    history.add("无效输出：" + plan.error);
                    continue;
                default:
                    break;
            }
            if (legacyOneStep && plan.steps.size() > 1) {   // 旧行为：只执行第一步
                PhonePlan.Step first = plan.steps.get(0);
                plan.steps.clear();
                plan.steps.add(first);
            }
            // ④ 执行这一批
            BatchResult br = runBatch(plan, screen, budget, stepNo, res);
            stepNo = br.stepNo;
            res.batches++;
            if (br.stopAll) return finish(res, Status.STOPPED, "", br.report, budget, t0);
            if (mika != null && mika.stopReason != null) {   // ★ mika-plug：守卫阶梯叫停（StopRules）
                res.trace.add("guard stop " + mika.stopReason);
                return finish(res, Status.FAILED, "", mika.stopReason, budget, t0);
            }
            history.add("第 " + res.batches + " 批：" + br.summary);
            if (history.size() > 12) history.remove(0);
            lastBatchReport = br.report + "\n界面变化：" + UiTree.diff(screen, br.after, 10);
            screen = br.after;
        }
    }

    private TaskBudget.Verdict legacyCheck(TaskBudget b) {
        if (b.calls >= legacyMaxSteps) {
            b.reason = "已走满 " + legacyMaxSteps + " 步";
            return TaskBudget.Verdict.LIMIT;
        }
        return TaskBudget.Verdict.OK;
    }

    static final class BatchResult {
        UiTree after;
        String report = "";
        String summary = "";
        int stepNo;
        boolean stopAll;
        /** ★ smart-replay：执行了几步、有没有被用户拒绝、最后一步的结果说法、条件没等到。 */
        int executed;
        boolean refused, condFailed;
        String lastEffect = "";
    }

    /** 逐步执行；条件不满足 / 界面意外变化（弹窗、换了 App）/ 用户接手 → 提前停，剩下的步交回模型。 */
    BatchResult runBatch(PhonePlan plan, UiTree screen, TaskBudget budget, int stepNo, Result res) {
        BatchResult br = new BatchResult();
        StringBuilder rep = new StringBuilder();
        StringBuilder sum = new StringBuilder();
        UiTree cur = screen;
        int done = 0;
        for (int i = 0; i < plan.steps.size(); i++) {
            PhonePlan.Step s = plan.steps.get(i);
            if (mH.stopRequested()) {
                br.stopAll = true;
                br.report = "已停止";
                receipt(res, StepReceipt.Code.STOPPED);
                break;
            }
            String tk = mH.takeoverRequested();
            if (tk != null) {
                rep.append("第 ").append(i + 1).append(" 步前用户接手（").append(tk).append("），剩下的步没做。").append(receipt(res, StepReceipt.Code.USER_TAKEOVER)).append("\n");
                pendingTakeover = tk;
                break;
            }
            if (i == 0 && !mReplaying && settle != null && settle.recheckRefs && targetsRef(s) && cur != null && cur.find(s.id) != null) {
                // ★ stable-screen：模型思考的这几秒里界面可能自己变了（加载完、弹窗、列表刷新）。执行前重读一次，
                // 编号还在就按新位置执行；不在了 / 分不清 → 明确报 REF_STALE，把新界面交回模型，不点旧坐标。
                UiTree fresh = mD.observe(mIds);
                if (fresh != null) {
                    String stale = StableRead.checkRef(s.id, cur, fresh);
                    UiTree.El was = cur.find(s.id), now = fresh.find(s.id);
                    cur = fresh;
                    if (stale != null) {
                        refStale++;
                        res.trace.add("ref stale [" + s.id + "]");
                        rep.append("第 ").append(i + 1).append(" 步 ").append(s).append(" 没执行：").append(stale).append(receipt(res, StepReceipt.Code.STALE_ELEMENT)).append("\n");
                        break;
                    }
                    if (was != null && now != null && (was.cx() != now.cx() || was.cy() != now.cy())) {
                        refMoved++;
                        res.trace.add("ref moved [" + s.id + "] " + was.cx() + "," + was.cy() + "→" + now.cx() + "," + now.cy());
                    }
                }
            }
            String bad = PhonePlan.validate(s, cur);
            if (bad != null) {
                rep.append("第 ").append(i + 1).append(" 步 ").append(s).append(" 没执行：").append(bad).append(receipt(res, StepReceipt.ofValidate(bad))).append("\n");
                break;
            }
            String mustTap = SensitiveGuard.userMustTap(s, cur);
            if (mustTap != null) {   // ★ approve-not-block 硬规则 ①：最终付款按钮机器人不点，批了也不点
                res.trace.add("pay_final_user_tap");
                if (mRec != null) mRec.onSensitive(mustTap);   // 含付款的任务照旧不记成可回放
                rep.append("第 ").append(i + 1).append(" 步 ").append(s).append(" 没执行：").append(mustTap)
                        .append("。我停在这里，你自己点一下就完成了（这一步批了也不会替你点）。").append(receipt(res, StepReceipt.Code.NEEDS_USER_TAP)).append("\n");
                break;
            }
            String sens = SensitiveGuard.check(s, cur);
            if (mika != null) {   // ★ mika-plug（mika.actgate）：RiskGate 补 SensitiveGuard 没拦的发送 / 提交 / 付款 / 删除，屏上有金额按付款问
                String extra = mika.consent(s, cur, sens);
                if (extra != null) sens = extra;
            }
            // ★ borrow-1112：模型自报的风险只能加严（本地判出的永远保留，模型标 none 放不松）
            String merged = RiskLabel.merge(sens, s.risk);
            if (sens == null && merged != null) {
                riskTightened++;
                res.trace.add("risk tightened by model: " + s.risk.id);
            }
            sens = merged;
            if (tainted) {   // ★ borrow-1112：读过外部文字后，发送 / 分享 / 提交类一律确认（防提示注入；记住过、标了 none 都一样）
                String ts = RiskLabel.taintedSendLike(s, cur);
                if (ts != null) {
                    taintConfirms++;
                    res.trace.add("taint confirm (" + taintedBy + ")");
                    sens = sens == null ? ts : sens + "；读过外部内容，发出去前再确认";
                }
            }
            if (sens == null && mForceSens != null) sens = mForceSens;   // ★ smart-replay：录制时判过敏感的步骤，回放时照样确认
            if (mRec != null && sens != null) mRec.onSensitive(sens);
            if (sens != null) {
                long w0 = mD.now();
                String[] ans = new String[1];
                boolean ok = mH.confirm("这一步要你确认", sens, ans);
                budget.pausedMs += mD.now() - w0;
                res.trace.add("sensitive " + sens + " → " + (ok ? "允许" : "拒绝"));
                if (!ok) {
                    br.refused = true;
                    rep.append("第 ").append(i + 1).append(" 步 ").append(s).append(" 被用户拒绝（").append(sens).append("），别再尝试这一步。").append(receipt(res, StepReceipt.Code.USER_REFUSED)).append("\n");
                    break;
                }
                UiTree asked = cur;
                cur = mD.observe(mIds);   // 用户可能动过
                String staleAfterAsk = settle != null && settle.recheckRefs && targetsRef(s) ? StableRead.checkRef(s.id, asked, cur) : null;
                if (staleAfterAsk != null) {   // ★ stable-screen：确认期间编号失效（换了位置且有同名元素）
                    refStale++;
                    res.trace.add("ref stale after confirm [" + s.id + "]");
                    rep.append("用户确认期间界面变了，第 ").append(i + 1).append(" 步没执行：").append(staleAfterAsk).append(receipt(res, StepReceipt.Code.STALE_ELEMENT)).append("\n");
                    break;
                }
                if (PhonePlan.validate(s, cur) != null) {
                    rep.append("用户确认期间界面变了，第 ").append(i + 1).append(" 步没执行。").append(receipt(res, StepReceipt.Code.STALE_ELEMENT)).append("\n");
                    break;
                }
            }
            String paceSite = mika != null ? mika.paceSite(s, cur) : null;
            if (paceSite != null) {   // ★ mika-plug（mika.pacing）：同一 App 里连着对外动作，像人一样隔一会
                long w = mika.paceWait(paceSite, mD.now());
                if (w > 0) {
                    mH.onStep("稍等", stepNo);
                    long until = mD.now() + w;
                    while (mD.now() < until && !mH.stopRequested()) mD.sleep(Math.min(500, until - mD.now()));
                    res.trace.add("pacing " + w + "ms @" + paceSite);
                }
            }
            mH.onStep(word(s, cur), ++stepNo);
            long a0 = mD.now();
            String effect = exec(s, cur);
            if (paceSite != null) mika.paceDone(paceSite, mD.now());
            UiTree after;
            String wait;
            mLastUnstable = false;
            if (s.isWaitUntil()) {   // ★ borrow-1112：等到条件出现 / 消失（复用稳定读屏轮询，期间画面变化记进时间轴）
                Object[] w = waitUntil(s, cur);
                after = (UiTree) w[0];
                wait = (String) w[1];
            } else if (s.hasCondition() || s.waitStable || s.op.equals("wait")) {
                Object[] w = waitFor(s, cur);
                after = (UiTree) w[0];
                wait = (String) w[1];
            } else if (mika != null && mika.f.changewait && mD instanceof MikaDriver) {   // ★ mika-plug：ChangeWait（树版）
                after = ((MikaDriver) mD).awaitChange(cur, mIds);
                wait = "";
            } else {
                mD.waitIdle(250, 1500);
                after = observeAfterAction(s.op);
                wait = "";
            }
            if (mLastUnstable && settle != null)
                wait = wait.length() == 0 ? "界面仍在变化（等了 " + settle.maxWaitMs + "ms 没稳定，读的是最后一次）" : wait + "，界面仍在变化";
            boolean changed = after != null && cur != null && !after.signature().equals(cur.signature());
            budget.onAction(s.sig(), changed);
            done++;
            br.lastEffect = effect;
            br.condFailed = wait.startsWith("没等到");
            if (mRec != null && com.onesay.assistant.v2.privacy.Redactor.hasPlaceholder(s.text))
                mRec.taint("输入内容里有隐藏的个人信息（占位符），不记成可回放");
            if (mRec != null) {   // ★ smart-replay：边做边录（cur = 动作前那屏）
                mRec.onExecuted(s, cur, effect, wait, sens);
                mRec.onScreen(after);
            }
            String mk = mika != null ? mika.afterStep(s, cur, after, effect) : "";   // ★ mika-plug：effect + 守卫阶梯 + 卡住建议
            noteTaint(after, res);
            String line = "第 " + (i + 1) + " 步 " + s + " → " + effect + (wait.length() > 0 ? "，" + wait : "") + "（" + (mD.now() - a0) + "ms）" + mk
                    + receipt(res, StepReceipt.ofEffect(s.op, effect, wait, changed));
            rep.append(line).append('\n');
            res.trace.add(line);
            if (sum.length() > 0) sum.append("；");
            sum.append(s).append(" ").append(effect);
            UiTree prev = cur;
            cur = after;
            if (mika != null && mika.stopReason != null) break;   // ★ mika-plug：守卫叫停
            if (wait.startsWith("没等到")) break;   // 条件没满足：交回模型
            boolean last = i == plan.steps.size() - 1;
            if (!last && after != null && prev != null) {
                String why = unexpected(prev, after, s, plan.steps.get(i + 1));
                if (why != null) {
                    rep.append("界面意外变化（").append(why).append("），剩下 ").append(plan.steps.size() - i - 1).append(" 步交回给你重新安排。")
                            .append(receipt(res, StepReceipt.Code.UNEXPECTED_SCREEN)).append("\n");
                    break;
                }
            }
        }
        if (pendingTakeover != null) {
            // 下一轮主循环会处理（Host.takeoverRequested 已经取走，这里放回去）
            reinjectTakeover = pendingTakeover;
            pendingTakeover = null;
        }
        br.after = cur != null ? cur : mD.observe(mIds);
        br.executed = done;
        br.report = "执行了 " + done + "/" + plan.steps.size() + " 步" + (done < plan.steps.size() && !br.stopAll ? "（停在第 " + (done + 1) + " 步，这一批没做完）" : "") + "：\n" + rep;
        br.summary = sum.length() > 0 ? clip(sum.toString(), 160) : "没有执行";
        br.stepNo = stepNo;
        return br;
    }

    private String pendingTakeover, reinjectTakeover;

    /** 给 Android 宿主包装用：批内取走的接手请求要在主循环再生效一次。 */
    public String drainReinjectedTakeover() {
        String t = reinjectTakeover;
        reinjectTakeover = null;
        return t;
    }

    String exec(PhonePlan.Step s, UiTree cur) {
        UiTree.El e = s.id >= 0 && cur != null ? cur.find(s.id) : null;
        if (s.op.equals("tap") && e == null && s.x >= 0 && s.y >= 0 && mD instanceof MikaDriver)   // ★ mika-plug：0–999 坐标点
            return ((MikaDriver) mD).tapXY(s.x, s.y) ? "已点击坐标" : "点击失败";
        if (s.op.equals("tap")) return mD.tap(e) ? "已点击" : "点击失败";
        if (s.op.equals("long")) return mD.longPress(e, s.holdMs) ? "已长按" : "长按失败";
        if (s.op.equals("type")) {
            String real = restore(s.text);   // ★ borrow-1112：占位符在本地换回真实值再输入（报告 / 轨迹里仍是占位符）
            String got = mD.type(e, real);
            String r = got == null ? "输入失败" : got.contains(real) ? "已输入" : "输入后读回「" + clip(redactor != null ? redactor.redact(got) : got, 20) + "」";
            if (got != null && s.enter) r += mD.key("enter") ? "，已回车" : "，回车失败";
            return r;
        }
        if (s.op.equals("scroll")) return mD.scroll(e, s.dir) ? "已滚动" : "滚到头了";
        if (s.op.equals("back")) return mD.key("back") ? "已返回" : "返回失败";
        if (s.op.equals("home")) return mD.key("home") ? "已回桌面" : "失败";
        if (s.op.equals("key")) return mD.key(s.key) ? "已按 " + s.key : "按键失败";
        if (s.op.equals("launch")) return mD.launch(s.app) ? "已打开 " + s.app : "打不开 " + s.app;
        if (s.op.equals("swipe")) {
            int w = cur == null ? 1080 : cur.screenW, h = cur == null ? 2220 : cur.screenH;
            boolean ok = s.dir > 0 ? mD.swipe(w / 2, h * 70 / 100, w / 2, h * 30 / 100, 300) : mD.swipe(w / 2, h * 30 / 100, w / 2, h * 70 / 100, 300);
            return ok ? "已滑动" : "滑动失败";
        }
        if (s.op.equals("wait") || s.isWaitUntil()) return "等待";
        return "不支持";
    }

    /** 代码侧等待：每次界面安静后读一次屏，看条件成立没有；不是固定 sleep。返回 {after, 说明}。 */
    Object[] waitFor(PhonePlan.Step s, UiTree before) {
        long end = mD.now() + s.timeoutMs;
        long w0 = mD.now();
        UiTree t = null;
        String lastSig = null;
        int stable = 0;
        // ★ fast-screen-v2（真机：设置首页的「连接」摘要里本来就有「蓝牙」，点完 223 ms 就判「条件已满足」，
        //   紧接着的返回键在新页面打开前按下去被吞掉）：条件在动作之前就已成立时，还要求界面确实变过一次
        boolean preMet = before != null && !s.op.equals("wait")
                && ((s.expect.length() > 0 && before.hasText(s.expect)) || (s.expectApp.length() > 0 && s.expectApp.equals(before.pkg)));
        String beforeSig = before == null ? null : before.signature();
        boolean changedOnce = false;
        // ★ stable-screen：条件看起来成立时，再确认画面已稳定（读到的不是转场半截），稳定后的那屏仍满足才算数；
        //   waitStable 自己就要求两次一致，wait 步骤之前没有动作，这两种不再额外确认
        boolean confirmStable = settle != null && !s.waitStable && !s.op.equals("wait");
        while (true) {
            mD.waitIdle(s.waitStable ? 500 : 250, 1500);
            t = mD.observe(mIds);
            if (t != null && beforeSig != null && !t.signature().equals(beforeSig)) changedOnce = true;
            boolean ok = condMet(s, t, before, preMet, changedOnce);
            if (ok && confirmStable) {
                StableRead.Result<UiTree> sr = settleFrom(t, Math.max(1, end - mD.now()));
                if (sr.value != null) {
                    t = sr.value;
                    if (beforeSig != null && !t.signature().equals(beforeSig)) changedOnce = true;
                }
                ok = condMet(s, t, before, preMet, changedOnce);   // 超时没稳定也按最后一次判（已记 unstable），不拖到条件超时
            }
            if (ok && s.waitStable) {
                String sig = t.signature();
                stable = sig.equals(lastSig) ? stable + 1 : 0;
                lastSig = sig;
                ok = stable >= 1;
            }
            if (ok) return new Object[]{t, (s.hasCondition() ? "条件已满足" : "页面已稳定") + "（等了 " + (mD.now() - w0) + "ms）"};
            if (mD.now() >= end || mH.stopRequested()) break;
            mD.sleep(120);
        }
        return new Object[]{t, "没等到" + cond(s) + "（等了 " + (mD.now() - w0) + "ms）"};
    }

    /** waitFor 的条件判断（拆出来给「稳定后再判一次」复用）。 */
    static boolean condMet(PhonePlan.Step s, UiTree t, UiTree before, boolean preMet, boolean changedOnce) {
        boolean ok = t != null;
        if (ok && preMet && !changedOnce) ok = false;
        // 条件文字本来就在屏上：要求它出现在一个「新」元素里（「连接 · Wi-Fi， 蓝牙」不算，新页面的「蓝牙」开关才算）
        if (ok && s.expect.length() > 0) ok = preMet ? t.hasNewText(s.expect, before) : t.hasText(s.expect);
        if (ok && s.expectGone.length() > 0) ok = !t.hasText(s.expectGone);
        if (ok && s.expectApp.length() > 0) ok = s.expectApp.equals(t.pkg);
        return ok;
    }

    // ------------------------------------------------------------------ ★ smart-replay

    /** 这次任务的录制器（null = 不录：开关关 / 走了回放 / 不录不放）。 */
    private SmartReplay.Recorder mRec;
    /** 正在回放：一批里第一步前不再额外重读核对编号（回放刚读过稳定画面并按特征重新定位过）。 */
    private boolean mReplaying;
    /** 回放的这一步录制时判过敏感：现判不敏感也照样确认。 */
    private String mForceSens;

    static final class ReplayOut {
        UiTree screen;
        String report = "", history = "";
        int stepNo;
        boolean stopAll;
    }

    /**
     * 逐步回放：读稳定画面（StableRead）→ 有没有弹窗 → 比回放指纹 → 按特征重新定位节点 → 走和模型步骤同一个执行口
     * （{@link #runBatch}：校验、敏感确认、代码侧等待）。任何一步不对就停，交还模型（降级）并记一次失败；
     * 按计划停下（输入内容没存原文）或用户拒绝 / 接手不算失败。
     */
    ReplayOut replayPath(SmartReplay.Path path, UiTree screen, TaskBudget budget, int stepNo, Result res) {
        ReplayOut out = new ReplayOut();
        out.stepNo = stepNo;
        UiTree cur = screen;
        boolean stable = settle != null && cur != null;   // 开局那屏：settle 开时已是稳定读到的
        int n = path.steps.size(), k = 0;
        String why = null;
        boolean planned = false, secret = false;
        String extra = "";
        StringBuilder done = new StringBuilder();
        res.trace.add("replay start " + n + " steps");
        for (; k < n; k++) {
            SmartReplay.RStep r = path.steps.get(k);
            if (mH.stopRequested()) {
                out.stopAll = true;
                replayHandoff++;
                replay.handedOff();
                replayNote = "回放中用户停止";
                return out;
            }
            if (!stable || cur == null) cur = replayRead(res);
            stable = false;
            String pop = SmartReplay.popup(r.before, cur);
            if (pop != null) {
                why = pop;
                break;
            }
            String mis = SmartReplay.mismatch(r.before, cur);
            if (mis != null) {
                why = "画面对不上：" + mis;
                break;
            }
            if (r.secret) {
                why = "这一步要输入的内容没有保存（验证码 / 密码 / 个人信息），需要你来填";
                planned = secret = true;
                break;
            }
            int id = -1;
            if (r.target != null) {
                SmartReplay.Found f = SmartReplay.locate(r.target, cur);
                if (f.el == null) {
                    why = f.error;
                    break;
                }
                id = f.el.id;
            }
            PhonePlan plan = new PhonePlan();
            plan.kind = PhonePlan.Kind.STEPS;
            plan.steps.add(r.toStep(id));
            BatchResult br;
            mReplaying = true;
            mForceSens = r.sens;
            try {
                br = runBatch(plan, cur, budget, out.stepNo, res);
            } finally {
                mReplaying = false;
                mForceSens = null;
            }
            out.stepNo = br.stepNo;
            if (br.after != null) cur = br.after;
            if (br.stopAll) {
                out.stopAll = true;
                replayHandoff++;
                replay.handedOff();
                replayNote = "回放中用户停止";
                return out;
            }
            if (reinjectTakeover != null) {
                why = "用户接手";
                planned = true;
                break;
            }
            if (br.refused) {
                why = "用户拒绝了这一步";
                extra = br.report;
                planned = true;
                break;
            }
            if (br.executed == 0 || SmartReplay.failedEffect(br.lastEffect) || br.condFailed) {
                why = "这一步没做成";
                extra = br.report;
                break;
            }
            done.append(k + 1).append(". ").append(r).append(" → ").append(br.lastEffect).append('\n');
            stable = settle != null;
        }
        replaySteps = k;
        out.screen = cur;
        if (why == null) {
            replayHit++;
            replay.succeeded(path.key, true);
            replayNote = "命中：回放了全部 " + n + " 步";
            res.trace.add("replay hit " + n);
            out.history = "按记住的成功步骤回放了全部 " + n + " 步（没有问模型）";
            out.report = "【回放】已按上次成功完成这件事的步骤做完 " + n + " 步：\n" + done
                    + "请看当前界面确认：任务已完成就输出 done（写给用户的结果）；还差什么就接着做。";
            return out;
        }
        if (planned) {
            replayHandoff++;
            if (secret) replay.succeeded(path.key, false);
            else replay.handedOff();
            replayNote = "交还模型（第 " + (k + 1) + " 步：" + why + "）";
            res.trace.add("replay handoff @" + (k + 1) + " " + why);
        } else {
            replayFallback++;
            boolean voided = replay.failed(path.key);
            if (voided) replayVoided++;
            replayNote = "降级（第 " + (k + 1) + " 步：" + why + "）" + (voided ? "，连续失败 " + SmartReplay.MAX_FAILS + " 次，路径已作废" : "");
            res.trace.add("replay fallback @" + (k + 1) + " " + why + (voided ? " voided" : ""));
        }
        out.history = "按记住的步骤回放了 " + k + " 步，第 " + (k + 1) + " 步停下（" + clip(why, 60) + "）";
        out.report = "【回放】按上次成功的步骤回放到第 " + (k + 1) + " 步停下：" + why + "。\n"
                + (k > 0 ? "已经做完的（不要重复）：\n" + done : "还没有做任何一步。\n")
                + (extra.length() > 0 ? extra + "\n" : "")
                + "请从当前界面接着完成任务。";
        return out;
    }

    /** 回放读屏一律用稳定读（settle 关时用默认参数），不稳定也用最后一次（指纹比对会兜住）。 */
    private UiTree replayRead(Result res) {
        StableRead.Params p = settle != null ? settle : StableRead.Params.defaults();
        StableRead.Result<UiTree> r = StableRead.await(mClock, mSource, p);
        if (!r.stable) res.trace.add("replay read unstable " + r.waitedMs + "ms");
        return r.value;
    }

    // ------------------------------------------------------------------ ★ stable-screen

    private Result mRes;
    /** 最近一次 observeAfterAction 超时没稳定。 */
    private boolean mLastUnstable;

    static boolean targetsRef(PhonePlan.Step s) {
        return s.id >= 0 && (s.op.equals("tap") || s.op.equals("long") || s.op.equals("type") || s.op.equals("scroll"));
    }

    private final StableRead.Clock mClock = new StableRead.Clock() {
        @Override public long now() {
            return mD.now();
        }

        @Override public void sleep(long ms) {
            if (ms > 0) mD.sleep(ms);
        }
    };

    private final StableRead.Source<UiTree> mSource = new StableRead.Source<UiTree>() {
        @Override public UiTree read() {
            return mD.observe(mIds);
        }

        @Override public String fingerprint(UiTree t) {
            return t.settleFingerprint();
        }
    };

    /** 动作之后的读屏：settle 关 = 读一次（原行为）；开 = 等到连续两次指纹一致，最长 maxWaitMs，超时用最后一次并记 unstable。 */
    UiTree observeAfterAction(String what) {
        mLastUnstable = false;
        if (settle == null) return mD.observe(mIds);
        StableRead.Result<UiTree> r = StableRead.await(mClock, mSource, settle);
        noteSettle(r, what, Math.max(0, r.reads - 1));
        return r.value;
    }

    private StableRead.Result<UiTree> settleFrom(UiTree seed, long capMs) {
        mLastUnstable = false;
        StableRead.Result<UiTree> r = StableRead.await(mClock, mSource, seed, true, settle, capMs);
        noteSettle(r, "wait", r.reads);
        return r;
    }

    private void noteSettle(StableRead.Result<UiTree> r, String what, int extraReads) {
        settleReads += extraReads;
        if (r.stable) return;
        settleUnstable++;
        mLastUnstable = true;
        String line = "settle unstable " + what + " " + r.waitedMs + "ms reads=" + r.reads;
        if (mRes != null) mRes.trace.add(line);
        try {
            SafeLog.w("PhoneAgent", "[PHONE] 读屏 unstable：" + what + " 等了 " + r.waitedMs + "ms、读了 " + r.reads
                    + " 次指纹仍不一致，用最后一次结果");
        } catch (Throwable ignored) {   // JVM 测试没有 Log 时不影响逻辑
        }
    }

    // ------------------------------------------------------------------ ★ borrow-1112：回执 / 污染 / 脱敏 / wait_until

    /** 记一条回执，返回跟在中文说明后面的那一截。 */
    private String receipt(Result res, StepReceipt.Code c) {
        return receipt(res, StepReceipt.of(c));
    }

    private String receipt(Result res, StepReceipt r) {
        if (res != null) res.receipts.add(r);
        return r.tail();
    }

    /** 读到浏览器 / 短信 / 邮件 / 通知栏的界面 = 本轮读过外部文字。 */
    private void noteTaint(UiTree t, Result res) {
        if (tainted || t == null || !RiskLabel.externalTextPkg(t.pkg)) return;
        tainted = true;
        taintedBy = t.pkg;
        if (res != null) res.trace.add("taint by screen " + t.pkg);
    }

    private String restore(String s) {
        return redactor == null ? s : redactor.restore(s);
    }

    /** wait_until：轮询读屏（每次等画面稳定，复用 {@link StableRead}），直到条件成立或超时（上限 {@link PhonePlan#UNTIL_MAX_MS}）。 */
    Object[] waitUntil(PhonePlan.Step s, UiTree before) {
        long w0 = mD.now();
        long end = w0 + Math.min(Math.max(500, s.timeoutMs), PhonePlan.UNTIL_MAX_MS);
        StableRead.Params p = settle != null ? settle : StableRead.Params.defaults();
        UiTree prev = before, t = before;
        Set<String> appeared = new LinkedHashSet<String>(), gone = new LinkedHashSet<String>();
        String stopWhy = null;
        while (true) {
            long left = end - mD.now();
            if (left <= 0) break;
            StableRead.Result<UiTree> r = StableRead.await(mClock, mSource, null, false, p, left);
            settleReads += Math.max(0, r.reads - 1);
            if (r.value != null) {
                t = r.value;
                String ch = meanwhileDiff(prev, t, appeared, gone);
                if (ch != null && meanwhile != null) {
                    try {
                        meanwhile.onChange(ch);   // 本机时间轴给用户看，用真实内容（不发给模型）
                    } catch (Throwable ignored) {
                    }
                }
                prev = t;
            }
            if (untilMet(s, t)) return new Object[]{t, "条件已满足（等了 " + (mD.now() - w0) + "ms）" + meanwhileText(appeared, gone)};
            if (mH.stopRequested()) {
                stopWhy = "已停止";
                break;
            }
            String tk = mH.takeoverRequested();
            if (tk != null) {
                pendingTakeover = tk;
                stopWhy = "用户接手";
                break;
            }
            if (mD.now() >= end) break;
            mD.sleep(Math.min(250, Math.max(1, end - mD.now())));
        }
        return new Object[]{t, "没等到" + untilDesc(s) + "（" + (stopWhy != null ? stopWhy + "，" : "") + "等了 " + (mD.now() - w0) + "ms）" + meanwhileText(appeared, gone)};
    }

    static boolean untilMet(PhonePlan.Step s, UiTree t) {
        if (t == null) return false;
        if (s.untilText.length() > 0 && !t.hasText(s.untilText)) return false;
        if (s.untilGone.length() > 0 && t.hasText(s.untilGone)) return false;
        if (s.untilEl.length() > 0 && !hasEl(t, s.untilEl)) return false;
        if (s.untilElGone.length() > 0 && hasEl(t, s.untilElGone)) return false;
        return true;
    }

    /** 名字或资源 id（不分大小写）含这个词的元素。 */
    static boolean hasEl(UiTree t, String w) {
        String k = w.toLowerCase(java.util.Locale.ROOT);
        for (UiTree.El e : t.els) {
            if (e.label.toLowerCase(java.util.Locale.ROOT).contains(k)) return true;
            if (e.rid != null && e.rid.toLowerCase(java.util.Locale.ROOT).contains(k)) return true;
        }
        return false;
    }

    static String untilDesc(PhonePlan.Step s) {
        StringBuilder b = new StringBuilder();
        if (s.untilText.length() > 0) b.append("「").append(s.untilText).append("」出现");
        if (s.untilGone.length() > 0) b.append(b.length() > 0 ? "、" : "").append("「").append(s.untilGone).append("」消失");
        if (s.untilEl.length() > 0) b.append(b.length() > 0 ? "、" : "").append("元素「").append(s.untilEl).append("」出现");
        if (s.untilElGone.length() > 0) b.append(b.length() > 0 ? "、" : "").append("元素「").append(s.untilElGone).append("」消失");
        return b.toString();
    }

    /** 两屏之间出现 / 消失的文字（记进 appeared / gone，各最多 {@link #MEANWHILE_MAX} 条）；有变化返回一句话，否则 null。 */
    static String meanwhileDiff(UiTree prev, UiTree cur, Set<String> appeared, Set<String> gone) {
        if (prev == null || cur == null) return null;
        Set<String> a = labels(prev), b = labels(cur);
        List<String> in = new ArrayList<String>(), out = new ArrayList<String>();
        for (String x : b) if (!a.contains(x)) in.add(x);
        for (String x : a) if (!b.contains(x)) out.add(x);
        if (in.isEmpty() && out.isEmpty()) return null;
        for (String x : in) if (appeared.size() < MEANWHILE_MAX) appeared.add(x);
        for (String x : out) if (gone.size() < MEANWHILE_MAX) gone.add(x);
        StringBuilder sb = new StringBuilder("等待中画面变化：");
        if (!in.isEmpty()) sb.append("出现").append(quoteList(in, 3));
        if (!out.isEmpty()) sb.append(in.isEmpty() ? "" : "，").append("消失").append(quoteList(out, 3));
        return sb.toString();
    }

    static final int MEANWHILE_MAX = 8;

    static Set<String> labels(UiTree t) {
        Set<String> s = new LinkedHashSet<String>();
        for (UiTree.El e : t.els) if (e.label.length() > 0 && e.label.length() <= 40) s.add(e.label);
        return s;
    }

    static String quoteList(List<String> l, int max) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < l.size() && i < max; i++) b.append("「").append(clip(l.get(i), 16)).append("」");
        if (l.size() > max) b.append("等 ").append(l.size()).append(" 处");
        return b.toString();
    }

    static String meanwhileText(Set<String> appeared, Set<String> gone) {
        if (appeared.isEmpty() && gone.isEmpty()) return "";
        StringBuilder b = new StringBuilder("；期间画面");
        if (!appeared.isEmpty()) b.append("出现过").append(quoteList(new ArrayList<String>(appeared), MEANWHILE_MAX));
        if (!gone.isEmpty()) b.append(appeared.isEmpty() ? "" : "，").append("消失过").append(quoteList(new ArrayList<String>(gone), MEANWHILE_MAX));
        return b.toString();
    }

    static String cond(PhonePlan.Step s) {
        if (s.expect.length() > 0) return "「" + s.expect + "」出现";
        if (s.expectGone.length() > 0) return "「" + s.expectGone + "」消失";
        if (s.expectApp.length() > 0) return "切到 " + s.expectApp;
        return "页面稳定";
    }

    /** 下一步之前界面意外变化：换了 App（这一步不是 launch/back/home、下一步也没写 expect_app）或冒出了对话框。 */
    static String unexpected(UiTree prev, UiTree after, PhonePlan.Step s, PhonePlan.Step next) {
        boolean nav = s.op.equals("launch") || s.op.equals("back") || s.op.equals("home") || s.expectApp.length() > 0;
        if (!nav && !prev.pkg.equals(after.pkg) && after.pkg.length() > 0) return "前台换成了 " + after.pkg;
        if (next.id >= 0 && after.find(next.id) == null && !next.op.equals("type")) return "下一步要点的 [" + next.id + "] 不见了";
        for (UiTree.El e : after.els) {
            if (prev.findText(e.label) != null || e.label.length() == 0) continue;
            String l = e.label;
            if (l.equals("允许") || l.equals("拒绝") || l.equals("始终允许") || l.equals("仅在使用中允许") || l.equals("取消") && after.els.size() < 12)
                return "弹出了「" + l + "」对话框";
        }
        return null;
    }

    /** 长列表：逐屏下滑收集新出现的文字（去重），直到看到 until、到底，或页数用完。 */
    String collect(UiTree start, String until, int pages, TaskBudget budget) {
        Set<String> seen = new LinkedHashSet<String>();
        UiTree t = start;
        int p = 0;
        boolean hit = false;
        String why = "页数用完";
        while (t != null) {
            for (UiTree.El e : t.els) if (e.label.length() > 0) seen.add(e.label);
            if (until.length() > 0 && t.hasText(until)) {
                hit = true;
                why = "看到了「" + until + "」";
                break;
            }
            if (p >= pages || mH.stopRequested()) break;
            String tk = mH.takeoverRequested();
            if (tk != null) {
                reinjectTakeover = tk;
                why = "用户接手";
                break;
            }
            String before = t.signature();
            UiTree.El list = null;
            for (UiTree.El e : t.els) if (e.scroll && (list == null || area(e) > area(list))) list = e;
            boolean moved = mD.scroll(list, 1);
            budget.onAction("collect:scroll", true);
            mD.waitIdle(300, 1500);
            t = observeAfterAction("collect");
            p++;
            if (!moved || t == null || t.signature().equals(before)) {
                why = "到底了";
                break;
            }
        }
        StringBuilder b = new StringBuilder();
        b.append("（翻了 ").append(p).append(" 屏，").append(why).append("，共 ").append(seen.size()).append(" 条）\n");
        int k = 0;
        for (String s : seen) {
            if (k++ >= 200) {
                b.append("…\n");
                break;
            }
            b.append("- ").append(clip(s, 50)).append('\n');
        }
        return b.toString();
    }

    static int area(UiTree.El e) {
        return (e.r - e.l) * (e.b - e.t);
    }

    static String word(PhonePlan.Step s, UiTree cur) {
        UiTree.El e = s.id >= 0 && cur != null ? cur.find(s.id) : null;
        String n = e == null ? "" : clip(e.label, 6);
        if (s.op.equals("tap")) return n.length() > 0 ? "点" + n : "点击";
        if (s.op.equals("type")) return "输入";
        if (s.op.equals("scroll")) return "滚动";
        if (s.op.equals("back")) return "返回";
        if (s.op.equals("launch")) return "打开应用";
        if (s.op.equals("wait")) return "等待";
        return s.op;
    }

    private Result finish(Result r, Status st, String answer, String reason, TaskBudget b, long t0) {
        if (mRec != null && replay != null) {   // ★ smart-replay：成功才存；不能存的写明原因
            if (st == Status.DONE) {
                SmartReplay.Path p = mRec.finish(mD.now());
                if (p != null) {
                    replay.put(p);
                    replayRecorded++;
                    replayNote = "已记下 " + p.steps.size() + " 步";
                    r.trace.add("replay recorded " + p.steps.size() + " steps");
                } else {
                    replayNote = "没记：" + mRec.taint;
                    r.trace.add("replay not recorded: " + mRec.taint);
                }
            } else replayNote = "没记：任务没成功";
            mRec = null;
        }
        r.status = st;
        r.answer = answer == null ? "" : answer;
        r.reason = reason == null ? "" : reason;
        r.modelCalls = b.calls;
        r.actions = b.actions;
        r.ms = mD.now() - t0;
        return r;
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
