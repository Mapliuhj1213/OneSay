package com.onesay.assistant.v2.s1;

import android.content.Context;
import android.util.Log;
import android.view.WindowManager;

import com.onesay.assistant.v2.ui.FloatingSheetService;

/**
 * [s1] S1「看得见的后台」的<b>全部挂钩入口</b>（写法同 screenref：既有文件里只有几行 {@code // [s1]} 调用指向这里）。
 *
 * <h3>挂钩一览（FloatingSheetService，CRLF 文件）</h3>
 * <table>
 *   <tr><td>S1-H1</td><td>onCreate 末尾</td><td>{@link #onServiceCreate}：建岛（后台任务 + 取件码；island.off 时不画浮层）/剪贴板/圈选三个控制器，注册短信接收器与剪贴板监听</td></tr>
 *   <tr><td>S1-H2</td><td>onDestroy 开头</td><td>{@link #onServiceDestroy}</td></tr>
 *   <tr><td>S1-H3</td><td>ACTION_LISTEN 分支、screenref onWake 之前</td><td>{@link #onNewWake}：胶囊收起时新唤醒接管（后台任务按取消处理）</td></tr>
 *   <tr><td>S1-H4</td><td>onSubmitCommand、screenref 拦截之前</td><td>{@link #interceptCommand}：「圈一下/框选」→ 圈选即问</td></tr>
 *   <tr><td>S1-H5</td><td>面板外部点击（保护期之后、取消之前）</td><td>{@link #onOutsideTap}：长任务在跑 → 收成胶囊而不是取消</td></tr>
 *   <tr><td>S1-H6</td><td>runAgentLoop 开头</td><td>{@link #onTaskStart}</td></tr>
 *   <tr><td>S1-H7</td><td>循环每一步执行前（「正在：xxx」）</td><td>{@link #onTaskStep}</td></tr>
 *   <tr><td>S1-H8</td><td>循环确认卡：showConfirmCard 之前 / 裁决之后</td><td>{@link #onConfirmRequired} / {@link #onConfirmResolved}</td></tr>
 *   <tr><td>S1-H9</td><td>循环结果回到主线程、取消判定之前</td><td>{@link #onTaskEnded}</td></tr>
 *   <tr><td>S1-H10</td><td>★ arch/island-progress（C 路）：B 路 AgentLoop 的任务账本 / ask_user</td><td>{@link #onSubgoals} / {@link #onAskUser} / {@link #answerAskUser} / {@link #awaitAskUser}</td></tr>
 *   <tr><td>S1-H11</td><td>★ arch/voice-sync：首个 GUI 动作前 / 动作结果 / AgentLoop 与多步计划收尾</td><td>{@link #beforeGuiAction} / {@link #onActionResult} / {@link #agentOutcomeVoice} / {@link #planOutcomeVoice}（应答、8 s 催一次、提问提示经 H6/H7/H8/H9/H10 顺带驱动 {@link VoiceSync}）</td></tr>
 *   <tr><td>S1-B</td><td>类体</td><td>{@code // ===== [s1] BEGIN … END =====} 桥接块（s1DetachForCapsule / s1AttachSheet / s1CancelActiveTask / s1HideSheetNow）</td></tr>
 * </table>
 * 所有入口在服务未初始化或对应开关关闭时什么都不做，返回"不干预"的值。
 */
public final class S1Hooks {

    private static final String TAG = "S1";

    private S1Hooks() {}

    /** [island] 合并了旧 CapsuleController + PickupCardController：后台任务（岛 / 胶囊）+ 取件码。 */
    private static IslandController sIsland;
    private static ClipboardWatcher sClip;
    private static RegionSelectController sCircle;

    /** ★ r2/fast-screen：执行模式要直接驱动岛（红点 / 图标 / 暂停 / 完成）。可能为 null。 */
    public static IslandController island() {
        return sIsland;
    }

    /** [S1-H1] 主线程。 */
    public static void onServiceCreate(FloatingSheetService svc) {
        try {
            WindowManager wm = (WindowManager) svc.getSystemService(Context.WINDOW_SERVICE);
            sIsland = new IslandController(svc, wm);
            FullscreenPolicy.sShell = new com.onesay.assistant.v2.executor.ShellExecutor(svc);   // [island v3] 全屏检测 root 兜底
            sCircle = new RegionSelectController(svc, wm);
            sIsland.start();
            com.onesay.assistant.v2.kernel.FeatureKernel.get().attachIsland(sIsland);   // 第 4 阶段（D-3a）：插件经内核往岛上放活动
            sClip = new ClipboardWatcher(svc, wm);
            sClip.start();
            Log.i(TAG, "[S1] 已初始化：" + S1Settings.dump(svc));
        } catch (Throwable t) {
            Log.w(TAG, "[S1] 初始化异常（不影响面板）: " + t.getMessage());
        }
    }

    /** [S1-H2] 主线程。 */
    public static void onServiceDestroy(FloatingSheetService svc) {
        try {
            if (sIsland != null) com.onesay.assistant.v2.kernel.FeatureKernel.get().detachIsland(sIsland);
            if (sIsland != null) sIsland.destroy();
            if (sClip != null) sClip.destroy();
            if (sCircle != null) sCircle.destroy();
        } catch (Throwable t) {
            warn("onServiceDestroy", t);   // 审计 B-8：以前静默吞掉
        }
        sAsk.expireAll();   // ★ arch/island-progress
        sIsland = null;
        sClip = null;
        sCircle = null;
    }

    /** [S1-H3] 新一次唤醒（主线程）。 */
    public static void onNewWake(FloatingSheetService svc) {
        try {
            if (sIsland != null) sIsland.newWake();
        } catch (Throwable t) {
            Log.w(TAG, "[S1] onNewWake 异常: " + t.getMessage());
        }
    }

    /** 面板挂上 / 摘下（FloatingSheetService.reportPanel，主线程）→ 灵动岛提醒让位 / 恢复。 */
    public static void onPanelVisibility(boolean on) {
        try {
            if (sIsland != null) sIsland.panelVisibility(on);
        } catch (Throwable t) {
            Log.w(TAG, "[S1] onPanelVisibility 异常: " + t.getMessage());
        }
    }

    /** [S1-H4] 指令入口（主线程）。true = 已由圈选即问接管。 */
    public static boolean interceptCommand(FloatingSheetService svc, String command) {
        try {
            return sCircle != null && sCircle.intercept(command);
        } catch (Throwable t) {
            Log.w(TAG, "[S1] 圈选拦截异常，回落原流程: " + t.getMessage());
            return false;
        }
    }

    /** [S1-H5] 面板外部点击（主线程）。true = 已收成胶囊，调用方不要再取消/收起。 */
    public static boolean onOutsideTap(FloatingSheetService svc) {
        try {
            return sIsland != null && sIsland.outsideTap();
        } catch (Throwable t) {
            return false;
        }
    }

    /** [S1-H6] 长任务开始（主线程）。等价于 {@code onTaskStart(svc, command, true)}（一开始就上岛，旧行为）。 */
    public static void onTaskStart(FloatingSheetService svc, String command) {
        onTaskStart(svc, command, true);
    }

    /**
     * [S1-H6] ★ arch/island-progress：{@code islandNow} 由分诊决定（{@link com.onesay.assistant.v2.router.TriageDecider#islandUpFront}）：
     * true = 多步 / 混合任务，照旧立即收进岛；false = 单步请求，<b>不包成岛上的任务</b> —— 留在面板上，
     * 直到真的需要（GUI 操作开始 / 子目标 ≥ 2 / 第 2 次工具调用 / ask_user）才上岛。
     */
    public static void onTaskStart(FloatingSheetService svc, String command, boolean islandNow) {
        try {
            sAsk.expireAll();   // 新任务：上一个任务没答的问题作废
            if (sIsland != null) sIsland.taskStarted(command == null ? "" : command, islandNow);
        } catch (Throwable t) {
            warn("onTaskStart", t);   // 审计 B-8：以前静默吞掉
        }
        try {
            VoiceSync.taskStart(svc, command, islandNow);   // ★ arch/voice-sync：多步立即应答 / 懒上岛等资格；8 s 催一次
        } catch (Throwable t) {
            warn("voiceSync.taskStart", t);
        }
    }

    /** [S1-H7] 长任务的一步（任意线程）。 */
    public static void onTaskStep(String label) {
        try {
            if (sIsland != null) sIsland.step(label);
        } catch (Throwable t) {
            warn("onTaskStep", t);   // 审计 B-8：以前静默吞掉
        }
        com.onesay.assistant.v2.tts.VoiceSyncSession vs = VoiceSync.current();
        if (vs != null) vs.onStep();   // ★ arch/voice-sync：第 2 次工具调用 → 懒上岛任务补应答
    }

    /** ★ 11-04 午 进度反馈：进入 / 离开「思考中」（AgentLoop 每轮模型调用前后）。任意线程。 */
    public static void onThinking(boolean on) {
        try {
            if (sIsland != null) sIsland.thinking(on);
        } catch (Throwable t) {
            warn("onThinking", t);
        }
    }

    // ------------------------------------------------------------------ ★ 11-04 相机对话：岛上「相机对话中 · 计时」/「思考中」
    //   不走 onTaskStart：那里会经 VoiceSync 播「收到，我来弄」并 8 s 催一次，相机对话不要这些。

    public static void onLiveStart(String title) {
        try {
            if (sIsland != null) sIsland.taskStarted(title == null ? "相机对话中" : title, true);
        } catch (Throwable t) {
            warn("onLiveStart", t);
        }
    }

    /** 改写岛上当前一行（计时 / 思考中）。任意线程。 */
    public static void onLiveLine(String line) {
        onTaskNote(line);
    }

    public static void onLiveEnd() {
        try {
            if (sIsland != null) {
                sIsland.taskOutcome(true, null, null, false);
                sIsland.taskEnded(true, false);   // 按「取消」收：岛上直接清掉，不弹完成卡
            }
        } catch (Throwable t) {
            warn("onLiveEnd", t);
        }
    }

    /** ★ 11-04 ①：只改当前一行、不算一步（失败如实显示 / 整理结论 / 卡住了）。任意线程。 */
    public static void onTaskNote(String text) {
        try {
            if (sIsland != null) sIsland.note(text);
        } catch (Throwable t) {
            warn("onTaskNote", t);
        }
    }

    // ------------------------------------------------------------------ ★ arch/island-progress（C 路）S1-H10：账本 + ask_user

    /** ask_user 默认超时（秒）。 */
    public static final int ASK_DEFAULT_TIMEOUT_SEC = 60;

    private static final AskUserBroker sAsk = new AskUserBroker();
    private static volatile java.util.List<com.onesay.assistant.v2.island.Subgoal> sLastSubgoals =
            java.util.Collections.<com.onesay.assistant.v2.island.Subgoal>emptyList();

    /** 回填中转（岛 / 面板 / 语音回填，B 路等待）。 */
    public static AskUserBroker askBroker() {
        return sAsk;
    }

    /**
     * [S1-H10a] 任务账本变了（任意线程）：<b>完整列表</b>，全量替换。子目标 ≥ 2 → 岛显示「第 i/n 项」+ 当前子目标；
     * 没有在跑的前台任务时忽略。B 路在 task.plan / task.update 之后调用。
     */
    public static void onSubgoals(java.util.List<com.onesay.assistant.v2.island.Subgoal> goals) {
        try {
            sLastSubgoals = com.onesay.assistant.v2.island.Subgoal.sanitize(goals);   // 调试 / 测试用（B 路 lastSubgoals）
            com.onesay.assistant.v2.tts.VoiceSyncSession vs = VoiceSync.current();
            if (vs != null) vs.onSubgoals(sLastSubgoals.size());   // ★ arch/voice-sync：子目标 ≥2 补应答（≥3「这个有点多」）
            if (sIsland != null) sIsland.subgoals(goals == null ? java.util.Collections.<com.onesay.assistant.v2.island.Subgoal>emptyList()
                    : new java.util.ArrayList<com.onesay.assistant.v2.island.Subgoal>(goals));
        } catch (Throwable t) {
            warn("onSubgoals", t);
        }
    }

    /** 最近一次账本（调试 / 测试用；B 路）。 */
    public static java.util.List<com.onesay.assistant.v2.island.Subgoal> lastSubgoals() {
        return sLastSubgoals;
    }

    /**
     * [S1-H10b] 中途问人（任意线程，<b>不阻塞</b>）：登记问题 {@code id}，能上岛就在岛上显示问题 + 2–4 个选项芯片
     * （多于 4 个只取前 4 个），{@code timeoutSec}（≤0 → 60）秒内没人选就收起，等待方拿到 null（no_answer）。
     *
     * @return true = 已上岛；false = 岛接不了（无浮层 / 没有在跑的前台任务 / 确认卡未决 / 有效选项 &lt; 2）——
     *         问题仍已登记，调用方可以改用面板 / 语音提问，答案照样经 {@link #answerAskUser} 回填。
     */
    public static boolean onAskUser(String id, String question, java.util.List<String> options, int timeoutSec) {
        if (id == null) return false;
        sAsk.register(id);
        com.onesay.assistant.v2.tts.VoiceSyncSession vs = VoiceSync.current();
        if (vs != null) vs.onAskUser(question);   // ★ arch/voice-sync：提问提示 + 字幕（岛上芯片同步出）；问的时候不催
        try {
            long ms = (timeoutSec <= 0 ? ASK_DEFAULT_TIMEOUT_SEC : timeoutSec) * 1000L;
            return sIsland != null && sIsland.askUser(id, question, options, ms);
        } catch (Throwable t) {
            warn("onAskUser", t);
            return false;
        }
    }

    /** 同上，超时 60 秒。 */
    public static boolean onAskUser(String id, String question, java.util.List<String> options) {
        return onAskUser(id, question, options, ASK_DEFAULT_TIMEOUT_SEC);
    }

    /**
     * [S1-H10c] 回填入口（任意线程）：岛上点芯片由岛自己调；面板 / 语音答了也调这里。true = 这个问题还在等、已收下。
     * 回填后岛上的芯片随即收起。
     */
    public static boolean answerAskUser(String id, String choice) {
        boolean ok = sAsk.answer(id, choice);
        com.onesay.assistant.v2.tts.VoiceSyncSession vs = VoiceSync.current();
        if (ok && vs != null) vs.onAskResolved();   // ★ arch/voice-sync
        try {
            if (ok && sIsland != null) sIsland.askResolved(id);
        } catch (Throwable t) {
            warn("answerAskUser", t);
        }
        return ok;
    }

    /** [S1-H10d] B 路撤回问题（任务取消 / 改用别的方式问到了）：等待方拿到 no_answer，岛上芯片收起。 */
    public static void cancelAskUser(String id) {
        sAsk.expire(id);
        com.onesay.assistant.v2.tts.VoiceSyncSession vs = VoiceSync.current();
        if (vs != null) vs.onAskResolved();   // ★ arch/voice-sync
        try {
            if (sIsland != null) sIsland.askResolved(id);
        } catch (Throwable t) {
            warn("cancelAskUser", t);
        }
    }

    /**
     * [S1-H10e] B 路阻塞等答案（<b>不要在主线程调</b>）：返回选项文字；超时 / 取消 / 收起 → null（no_answer）。
     * timeoutMs 建议 = 问题超时 + 2 s（岛的 60 s 计时先到，这里只是兜底，防止岛不在时永远等）。
     */
    public static String awaitAskUser(String id, long timeoutMs) {
        try {
            return sAsk.await(id, timeoutMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /** [S1-H8i] ★ island：确认卡的标题/参数（岛上确认卡显示用；主线程，onConfirmRequired 之前）。 */
    public static void onConfirmInfo(com.onesay.assistant.v2.capability.Capability cap, org.json.JSONObject args) {
        try {
            if (sIsland != null) sIsland.confirmInfo(cap, args);
        } catch (Throwable t) {
            warn("onConfirmInfo", t);   // 审计 B-8：以前静默吞掉
        }
    }

    /** [S1-H8a] 确认卡即将弹出（主线程，showConfirmCard 之前）：收起状态必须先展开（岛：待确认 → 1 s 后展开确认卡）。 */
    public static void onConfirmRequired(FloatingSheetService svc) {
        com.onesay.assistant.v2.tts.VoiceSyncSession vs = VoiceSync.current();
        if (vs != null) vs.onConfirm(true);   // ★ arch/voice-sync：确认期间不催
        try {
            if (sIsland != null) sIsland.confirmRequired();
        } catch (Throwable t) {
            Log.w(TAG, "[S1] confirmRequired 异常: " + t.getMessage());
        }
    }

    /** [S1-H8b] 确认卡已裁决（任意线程）。 */
    public static void onConfirmResolved() {
        com.onesay.assistant.v2.tts.VoiceSyncSession vs = VoiceSync.current();
        if (vs != null) vs.onConfirm(false);   // ★ arch/voice-sync
        try {
            if (sIsland != null) sIsland.confirmResolved();
        } catch (Throwable t) {
            warn("onConfirmResolved", t);   // 审计 B-8：以前静默吞掉
        }
    }

    /** 错误态自动收起前复核用 —— 长任务是否在跑。 */
    public static boolean isTaskRunning() {
        IslandController c = sIsland;
        return c != null && c.mModel.isRunning();
    }

    /** 确认卡是否未决（S1 不变量：确认卡永远不被自动藏起）。 */
    public static boolean isConfirmPending() {
        IslandController c = sIsland;
        return c != null && c.mModel.isConfirmPending();
    }

    /** [S1-H9o] ★ island：长任务的结果（主线程，onTaskEnded 之前）：是否正常收工、要给用户读的结论、失败原因。 */
    public static void onTaskOutcome(boolean finished, String answer, String reason) {
        onTaskOutcome(finished, answer, reason, false);
    }

    /** [r2/bike-chooser ⑤] needsCard = 结论在卡片上带可点内容（应用网格），收工挂回卡片而不是只进岛。 */
    public static void onTaskOutcome(boolean finished, String answer, String reason, boolean needsCard) {
        try {
            // [richout] 灵动岛不显示富内容：代码 / 图 / 表格压成一行纯文字（插件关 = 原样）
            if (sIsland != null) sIsland.taskOutcome(finished, com.onesay.assistant.plug.richout.host.RichOutHost.islandText(answer), reason, needsCard);
        } catch (Throwable t) {
            warn("onTaskOutcome", t);   // 审计 B-8：以前静默吞掉
        }
    }

    /** [S1-H9] 长任务结束（主线程，取消判定之前）。 */
    public static void onTaskEnded(FloatingSheetService svc, boolean cancelled, boolean navLaunched) {
        try {
            sAsk.expireAll();   // ★ arch/island-progress：任务收工，没答的问题按 no_answer 放行
            if (sIsland != null) sIsland.taskEnded(cancelled, navLaunched);
        } catch (Throwable t) {
            warn("onTaskEnded", t);   // 审计 B-8：以前静默吞掉
        }
        VoiceSync.taskEnded();   // ★ arch/voice-sync：取消催、放开门控
    }

    // ------------------------------------------------------------------ ★ arch/voice-sync S1-H11：语音 × 交互时序

    /**
     * [S1-H11a] 第一个 GUI 动作之前（<b>循环线程，阻塞</b>，FSS 执行回调里 phone.operate 之前调）：还没应答就先应答，
     * 等应答播完再放行（最多 {@link com.onesay.assistant.v2.tts.VoiceSyncPolicy#GATE_MAX_WAIT_MS}）。之后的 GUI 动作直接放行。
     */
    public static long beforeGuiAction() {
        com.onesay.assistant.v2.tts.VoiceSyncSession vs = VoiceSync.current();
        if (vs == null) return 0L;
        try {
            return vs.beforeGui(0L);   // ★ 11-04 午：TTS 不阻塞执行 —— 应答照播，GUI 动作不等它播完
        } catch (Throwable t) {
            warn("beforeGuiAction", t);
            return 0L;
        }
    }

    /** [S1-H11b] 一个动作执行完（任意线程）：记「最后一个改动类动作有没有回读确认」（OK_UNVERIFIED 用）。 */
    public static void onActionResult(com.onesay.assistant.v2.capability.Capability cap, com.onesay.assistant.v2.executor.ExecutionResult r, boolean navigation) {
        com.onesay.assistant.v2.tts.VoiceSyncSession vs = VoiceSync.current();
        if (vs == null || r == null) return;
        boolean readOnly = cap != null && (cap.sideEffect == com.onesay.assistant.v2.capability.SideEffect.NONE
                || cap.sideEffect == com.onesay.assistant.v2.capability.SideEffect.PRIVATE_DATA_READ);
        vs.onAction(r.ok, r.verified, navigation, readOnly);
    }

    /**
     * [S1-H11c] AgentLoop 收尾（主线程，showAskReply 之前）：按结局挑一句结果模板，交给回答播放层（一次性覆盖）；
     * 静音条件命中 → 这句不出声（屏幕照样显示）。宿主播完后调 {@link com.onesay.assistant.v2.ui.TtsSpeaker#clearReplyOverride}。
     *
     * @return 所播那句（屏幕状态行副文字以它开头）
     */
    public static com.onesay.assistant.v2.tts.VoiceTemplate agentOutcomeVoice(Context ctx, boolean resultNull, com.onesay.assistant.v2.agent.Outcome o,
                                                                     String reason, int subgoals) {
        com.onesay.assistant.v2.tts.VoiceSyncSession vs = VoiceSync.current();
        com.onesay.assistant.v2.tts.VoiceTemplate t = vs != null ? vs.outcomeTemplate(resultNull, o, reason, subgoals)
                : com.onesay.assistant.v2.tts.VoiceSyncPolicy.outcome(resultNull, o, reason, subgoals >= 2, false);
        String silent = VoiceSync.silenceReason(ctx);
        Log.i(TAG, "[VOICE-SYNC] 结局 " + (o == null ? "null" : o.wire()) + "/" + reason + " → 「" + t.text + "」"
                + (silent == null ? "" : "（不出声：" + silent + "）"));
        com.onesay.assistant.v2.ui.TtsSpeaker.setReplyOverride(t, silent != null);
        return t;
    }

    /** [S1-H11d] 多步计划（executePlan）收尾：同上，按全成 / 中止 / 有成有败（★ v3b）/ 全败挑一句。 */
    public static com.onesay.assistant.v2.tts.VoiceTemplate planOutcomeVoice(Context ctx, boolean allOk, boolean aborted, boolean anyOk) {
        com.onesay.assistant.v2.tts.VoiceTemplate t = com.onesay.assistant.v2.tts.VoiceSyncPolicy.plan(allOk, aborted, anyOk);
        String silent = VoiceSync.silenceReason(ctx);
        Log.i(TAG, "[VOICE-SYNC] 多步计划收尾 allOk=" + allOk + " aborted=" + aborted + " anyOk=" + anyOk + " → 「" + t.text + "」"
                + (silent == null ? "" : "（不出声：" + silent + "）"));
        com.onesay.assistant.v2.ui.TtsSpeaker.setReplyOverride(t, silent != null);
        return t;
    }

    /** [S1-H11e] 有前台长任务的语音会话在跑（执行模式收尾时不再单独播「好了」，结局由任务统一说一次）。 */
    public static boolean voiceSyncOwnsOutcome() {
        com.onesay.assistant.v2.tts.VoiceSyncSession vs = VoiceSync.current();
        return vs != null && !vs.ended();
    }

    // ------------------------------------------------------------------ 调试入口（S1TestDriver 用）

    /** 调试：注入一段"收到的短信"。主线程。 */
    public static int debugInjectSms(String body) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return 0;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        return sIsland != null ? sIsland.onIncomingText(body, "debug") : -1;
    }

    /** ★ feat/suggest：岛上剪贴板建议卡的芯片 → 原执行链路（主线程）。 */
    public static boolean runClipChip(String token, int index) {
        ClipboardWatcher w = sClip;
        return w != null && w.runSuggestChip(token, index);
    }

    /** ★ feat/suggest：岛放不下剪贴板建议 → 退回旧卡片（主线程）。 */
    public static boolean clipFallback(String token) {
        ClipboardWatcher w = sClip;
        return w != null && w.showLegacyFor(token);
    }

    // ★ fix/shot-1104：非岛模式下主动建议的旧芯片卡（截图分析 / 授权提示等），任意线程调
    public interface SuggestChipListener {
        void onChip(String id, int index);

        void onClosed(String id, boolean timeout);
    }

    private static SuggestChipCard sSuggestChip;

    /** @return false = 服务没起来（没有可挂浮窗的 Context）。 */
    public static synchronized boolean showSuggestChip(Context ctx, String id, String icon, String title, java.util.List<String> labels,
                                                       long ttlMs, final SuggestChipListener l) {
        if (ctx == null) return false;
        if (sSuggestChip == null) sSuggestChip = new SuggestChipCard(ctx.getApplicationContext());
        sSuggestChip.show(id, icon, title, labels, ttlMs, new SuggestChipCard.Listener() {
            @Override public void onChip(String cid, int index) {
                if (l != null) l.onChip(cid, index);
            }

            @Override public void onClosed(String cid, boolean timeout) {
                if (l != null) l.onClosed(cid, timeout);
            }
        });
        return true;
    }

    public static synchronized void updateSuggestChip(String id, String icon, String title, java.util.List<String> labels) {
        if (sSuggestChip != null) sSuggestChip.update(id, title, icon, labels);
    }

    public static synchronized void withdrawSuggestChip(String id) {
        if (sSuggestChip != null) sSuggestChip.withdraw(id);
    }

    /** 调试：按"外部复制"处理一段文字（不经系统剪贴板）。主线程。 */
    public static String debugClip(String text) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // r2/fixes 9：debug 驱动专用，release 里是空操作
        return sClip != null ? String.valueOf(sClip.handleText(text)) : "watcher=null";
    }

    /** ★ ui-K1 对话窗底栏「圈选」按钮：开始一次圈选即问（功能开关关着 → false）。主线程。 */
    public static boolean beginCircle(Context ctx, String question) {
        if (sCircle == null || !S1Settings.circleEnabled(ctx)) return false;
        sCircle.begin(question);
        return true;
    }

    /** 调试：开始一次圈选。主线程。 */
    public static boolean debugCircle(String question) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return false;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        if (sCircle == null) return false;
        sCircle.begin(question);
        return true;
    }

    /** 调试：手动把在跑的长任务收成胶囊（等同点面板外）。主线程。 */
    public static boolean debugCollapse() {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return false;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        return sIsland != null && sIsland.outsideTap();
    }

    public static void debugClearPickup() {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return;   // r2/fixes 9：debug 驱动专用，release 里是空操作
        if (sIsland != null) sIsland.clearAllPickups();
    }

    /** [island] 调试：岛/胶囊状态一行。主线程。 */
    public static String debugIsland() {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // r2/fixes 9：debug 驱动专用，release 里是空操作
        return sIsland != null ? sIsland.dump() : "island=null（面板服务未初始化）";
    }

    /** [island v3] 调试：锚点、窗口 x/y/w/h、density、状态栏 px。主线程。 */
    public static String debugGeom() {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // r2/fixes 9：debug 驱动专用，release 里是空操作
        return sIsland != null ? sIsland.geom() : "island=null";
    }

    // ---- ★ R13 卡 ↔ 岛 形变接力（只被 FloatingSheetService 的新唤醒界面调用；全部主线程）----

    public static void armIslandMorph() {
        try {
            if (sIsland != null) sIsland.armMorph();
        } catch (Throwable t) {
            warn("armIslandMorph", t);
        }
    }

    public static boolean islandAppearFrom(android.graphics.RectF screenRect, float vw, float vh) {
        try {
            return sIsland != null && sIsland.appearFrom(screenRect, vw, vh);
        } catch (Throwable t) {
            warn("islandAppearFrom", t);
            return false;
        }
    }

    public static void releaseIslandMorph() {
        try {
            if (sIsland != null) sIsland.releaseMorph();
        } catch (Throwable t) {
            warn("releaseIslandMorph", t);
        }
    }

    /** {cx, top, seed, windowBottom, compactW}（px）或 null（不画岛）。 */
    public static float[] islandMorphTarget() {
        try {
            return sIsland == null ? null : sIsland.morphTarget();
        } catch (Throwable t) {
            warn("islandMorphTarget", t);
            return null;
        }
    }

    /** {l, t, r, b, radius}（屏幕 px）或 null（岛不可见）。 */
    public static float[] islandShape() {
        try {
            return sIsland == null ? null : sIsland.islandShape();
        } catch (Throwable t) {
            return null;
        }
    }

    /** 回程：卡片排版期间岛冻在当前形状（≤ms，handOffIsland 或超时解冻）。 */
    public static void holdIslandForHandoff(long ms) {
        try {
            if (sIsland != null) sIsland.holdIslandForHandoff(ms);
        } catch (Throwable t) {
            warn("holdIslandForHandoff", t);
        }
    }

    public static void handOffIsland() {
        try {
            if (sIsland != null) sIsland.handOffIsland();
        } catch (Throwable t) {
            warn("handOffIsland", t);
        }
    }

    /** [island v3] 屏幕旋转 / 尺寸变化（FSS.onConfigurationChanged，主线程）。 */
    public static void onDisplayChanged() {
        if (sIsland != null) sIsland.onDisplayChanged();
    }

    /** [island v3] 关键事件（「已接单」）公开入口：目前只有调试驱动调，执行器以后再接。任意线程。 */
    public static void onKeyUpdate(String text) {
        if (sIsland != null) sIsland.keyUpdate(text);
    }

    // [s3] 定时任务 / 提醒 / 待办上岛：第 4 阶段（D-3a）起经内核 FeatureKernel.island("s3")（IslandHost），不再从这里转。

    /** [island] 调试：把岛推到某个状态（见 IslandTestDriver）。主线程。 */
    public static String debugIslandDemo(String cmd, String text) {
        if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return "（仅 debug 包可用）";   // r2/fixes 9：debug 驱动专用，release 里是空操作
        return sIsland != null ? sIsland.demo(cmd, text) : "island=null";
    }

    /** [island] 浮层形态重新判定（岛开关、island.off、悬浮窗权限变化后由控制台/调试调用）。主线程。 */
    public static void refreshIslandMode(String why) {
        if (sIsland != null) sIsland.refreshMode(why);
    }

    public static String debugStatus(Context ctx) {
        StringBuilder sb = new StringBuilder();
        sb.append(S1Settings.dump(ctx));
        if (sIsland != null) sb.append(" | capsule state=").append(sIsland.mModel.sheet()).append(" running=").append(sIsland.mModel.isRunning())
                .append(" | island ").append(sIsland.dump());
        sb.append(" | clipEntities=").append(ClipboardMemory.recentEntities(System.currentTimeMillis()).size());
        return sb.toString();
    }

    /** 审计 B-8：S1 任务模型 / 灵动岛挂钩的异常不打断宿主，但要留日志（不改控制流）。 */
    static void warn(String where, Throwable t) {
        Log.w(TAG, "[S1] " + where + " 异常（已忽略，不影响面板）: " + t);
    }
}
