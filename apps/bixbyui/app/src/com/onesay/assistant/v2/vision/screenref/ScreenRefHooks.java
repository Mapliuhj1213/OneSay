package com.onesay.assistant.v2.vision.screenref;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.router.ConversationMemory;
import com.onesay.assistant.v2.ui.ActionChipsView;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;
import com.onesay.assistant.v2.wake.WakeService;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.TimeZone;

/**
 * [screenref] 识屏指代 + 动作芯片的<b>全部挂钩入口</b>。既有文件里只有几行 {@code // [screenref]} 调用指向这里，
 * 合并冲突时照着 的挂钩清单逐行重放即可。
 *
 * <h3>挂钩一览</h3>
 * <ul>
 *   <li>{@link #onWake}：FloatingSheetService 处理 ACTION_LISTEN（新一次唤醒）时、面板挂载前调用 —— 让面板暂不抢焦点并开始采集；</li>
 *   <li>{@link #interceptCommand}：FloatingSheetService.onSubmitCommand 开头 —— 指代命中则接管，否则原流程照旧；</li>
 *   <li>{@link #restoreFocus}：FloatingSheetService.onTextModeRequested —— 用户要打字时立刻还焦点；</li>
 *   <li>{@link #appendTools} / {@link #withScreenHint} / {@link #handleLocalTool} / {@link #describe}：AgentLoop 内四处。</li>
 * </ul>
 * 所有入口在功能关闭（{@link ScreenRefSettings#isEnabled}）时立即返回"什么都不做"的值。
 */
public final class ScreenRefHooks {

    private static final String TAG = "ScreenRef";

    private ScreenRefHooks() {}

    /**
     * 主线程 Handler，<b>懒加载</b>（2026-10-07 集成）：AgentLoop 现在在 JVM 单测里也会调用本类的挂钩，
     * 静态初始化时 new Handler 会在 android.jar 桩上抛 "Stub!" 并让整个类加载失败。
     */
    private static Handler sMain;

    private static synchronized Handler main() {
        if (sMain == null) sMain = new Handler(Looper.getMainLooper());
        return sMain;
    }

    /** ★ R14：唤醒后多久才起 uiautomator dump（卡片入场弹簧约 450 ms + 余量）。 */
    static final long DUMP_DEFER_MS = 600L;

    /** {@link #withScreenHint} 读不到屏幕时追加的说明（不含屏幕内容，不算污点）。 */
    static final String NO_SCREEN_NOTE = "\n\n[屏幕上下文] 唤醒时没能读取屏幕文字；如需屏幕内容请调用 screen__context 工具。";

    // ---- 会话状态（主线程读写）----
    private static int sWakeSeq;
    private static boolean sSubmittedSinceWake;
    private static boolean sFocusTrick;
    /** ★ r2/sysux P1-4：本次唤醒的识屏 dump 已排期 / 在途（面板这时不能抢焦点）。主线程读写。 */
    private static boolean sCaptureBusy;
    /** ★ r2/sysux P2-2：本次唤醒时刻（currentTimeMillis，和 ScreenContext.capturedAtMs 同一时钟）。 */
    private static long sWakeWallAt;

    /** ★ r2/sysux P1-4：识屏还没做完（推迟期 + dump 在途）。 */
    public static boolean captureBusy() {
        return sCaptureBusy;
    }

    private static void setBusy(boolean b) {
        sCaptureBusy = b;
        ScreenContextCapture.setWakeCaptureBusy(b);
    }
    private static WeakReference<View> sSheetRef;
    private static WeakReference<WindowManager> sWmRef;
    private static WeakReference<WindowManager.LayoutParams> sParamsRef;
    private static final List<ScreenAction> sChipActions = new ArrayList<ScreenAction>();
    private static List<ScreenEntity> sPendingCands;
    private static DeixisResolver.Action sPendingAction;
    private static long sPendingAt;

    // =====================================================================
    // 面板侧挂钩
    // =====================================================================

    /**
     * [挂钩] 新一次唤醒（ACTION_LISTEN），在 setListeningPrompt/showSheet <b>之前</b>调用。主线程。
     */
    public static void onWake(final FloatingSheetService svc, View sheet, WindowManager wm, WindowManager.LayoutParams params) {
        try {
            if (svc == null || !ScreenRefSettings.isEnabled(svc)) return;
            sWakeSeq++;
            final int seq = sWakeSeq;
            sSubmittedSinceWake = false;
            clearPending();
            hideChips(svc);
            boolean attached = sheet != null && sheet.getParent() != null;
            if (attached) {
                // 面板已在屏幕上（追加唤醒）：此刻 dump 只会拿到自家面板，沿用 TTL 内的缓存
                ScreenContext c = ScreenContextCapture.fresh();
                Log.i(TAG, "[WAKE] 面板已在屏幕上，沿用缓存: " + c);
                if (c != null) maybeShowChips(svc, c, seq);
                return;
            }
            // ★ R11 真机复核（10-12 21:42）：锁屏时唤醒（随口记 / 短按）也会 dump 锁屏界面，
            //   把锁屏时钟做成「加入日历 10月12日」芯片 —— 和记一记一样：锁着就不读屏、不出芯片
            if (keyguardLocked(svc)) {
                Log.i(TAG, "[WAKE] 锁屏中 → 不读屏、不出识屏芯片 seq=" + seq);
                return;
            }
            if (params != null && (params.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) == 0) {
                params.flags |= WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
                sFocusTrick = true;
                sSheetRef = new WeakReference<View>(sheet);
                sWmRef = new WeakReference<WindowManager>(wm);
                sParamsRef = new WeakReference<WindowManager.LayoutParams>(params);
            }
            // ★ R14（diag C2 / P1-4）：dump 推迟到卡片入场动画之后（{@link #DUMP_DEFER_MS}）再起，并降优先级（nice 10）——
            //   uiautomator 要拉起一个 app_process JVM，2.2–2.6 s 的重 CPU 原来正好压在入场弹簧和光晕点亮上。
            //   新界面（v2）的卡片从挂窗起就是 NOT_FOCUSABLE（FloatingSheetService），这里不再需要 focusTrick，也就没有 2.3 s 后的
            //   updateViewLayout 整窗跳 126 px。旧界面照旧走 focusTrick。
            Log.i(TAG, "[WAKE] " + DUMP_DEFER_MS + "ms 后采集屏幕上下文 seq=" + seq + " focusTrick=" + sFocusTrick);
            setBusy(true);
            sWakeWallAt = System.currentTimeMillis();
            main().postDelayed(new Runnable() {
                @Override
                public void run() {
                    // ★ r2/sysux P2-2：默认助理已经把这次唤醒的 AssistStructure 交来了 → 不再起 root uiautomator（优先系统上下文）
                    ScreenContext ac = ScreenContextCapture.fresh();
                    if (ac != null && ScreenContext.SRC_ASSIST.equals(ac.source) && seq == sWakeSeq && !sSubmittedSinceWake
                            && com.onesay.assistant.v2.assist.AssistEntryPolicy.freshForWake(ac.capturedAtMs, sWakeWallAt - 1500L,
                                    System.currentTimeMillis(), ScreenRefSettings.CONTEXT_TTL_MS)) {
                        Log.i(TAG, "[WAKE] 已有系统助理上下文（AssistStructure）→ 跳过 uiautomator seq=" + seq + " " + ac);
                        setBusy(false);
                        restoreFocus();
                        maybeShowChips(svc, ac, seq);
                        return;
                    }
                    if (seq != sWakeSeq || sSubmittedSinceWake) {
                        Log.i(TAG, "[WAKE] 推迟的采集作废（已有新唤醒 / 已提交）seq=" + seq);
                        if (seq == sWakeSeq) setBusy(false);
                        restoreFocus();
                        return;
                    }
                    ScreenContextCapture.captureAsync(svc, new ScreenContextCapture.Listener() {
                        @Override
                        public void onCaptured(final ScreenContext ctx) {
                            main().post(new Runnable() {
                                @Override
                                public void run() {
                                    if (seq == sWakeSeq) setBusy(false);
                                    restoreFocus();
                                    if (ctx != null && seq == sWakeSeq && !sSubmittedSinceWake) maybeShowChips(svc, ctx, seq);
                                }
                            });
                        }
                    });
                }
            }, DUMP_DEFER_MS);
            // 双保险：无论采集结果如何，最迟 DUMP_TIMEOUT+3 秒把焦点还给面板
            main().postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (seq == sWakeSeq) setBusy(false);
                    restoreFocus();
                }
            }, ScreenRefSettings.DUMP_TIMEOUT_S * 1000L + 3000L);
        } catch (Throwable t) {
            Log.w(TAG, "[WAKE] 挂钩异常（不影响唤醒）: " + t.getMessage());
            restoreFocus();
        }
    }

    /** [挂钩] 把面板的可获焦状态恢复原样（幂等）。主线程。 */
    public static void restoreFocus() {
        if (!sFocusTrick) return;
        sFocusTrick = false;
        try {
            WindowManager.LayoutParams p = sParamsRef != null ? sParamsRef.get() : null;
            View v = sSheetRef != null ? sSheetRef.get() : null;
            WindowManager wm = sWmRef != null ? sWmRef.get() : null;
            if (p == null) return;
            p.flags &= ~WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
            if (v != null && wm != null && v.getParent() != null) wm.updateViewLayout(v, p);
            Log.i(TAG, "[FOCUS] 面板焦点已恢复");
        } catch (Throwable t) {
            Log.w(TAG, "[FOCUS] 恢复焦点异常: " + t.getMessage());
        }
    }

    /**
     * [挂钩] onSubmitCommand 开头调用。返回 true = 本条已由识屏指代接管（原流程不再执行）。主线程。
     */
    public static boolean interceptCommand(final FloatingSheetService svc, final String command) {
        try {
            if (svc == null || command == null || !ScreenRefSettings.isEnabled(svc)) return false;
            // ★ lane C3-5：识屏芯片带编号（①②③…），说「第三个 / 3 号 / 最后一个」= 点第 N 个芯片
            //   歧义追问中（sPendingCands）交给下面原有的候选挑选（编号与追问文字一致），这里不抢
            if (sPendingCands == null && !sChipActions.isEmpty() && NumberedRefs.isLatest(NumberedRefs.OWNER_SCREEN, System.currentTimeMillis())) {
                List<ScreenAction> shown = new ArrayList<ScreenAction>(sChipActions);
                int idx = NumberedRefs.pick(command, shown.size());
                if (idx >= 0) {
                    sSubmittedSinceWake = true;
                    clearPending();
                    hideChips(svc);
                    restoreFocus();
                    ScreenAction a = shown.get(idx);
                    Log.i(TAG, "[CHIPS] 按编号选中第 " + (idx + 1) + " 个: " + a);
                    runAction(svc, a, command);
                    return true;
                }
                if (idx == NumberedRefs.OUT_OF_RANGE) {
                    FloatingSheetView sheet = svc.getSheetView();
                    if (sheet != null) {
                        sheet.showAskReply(command, "只有 " + shown.size() + " 个选项，可以说「第一个」到「第" + shown.size() + "个」。");
                        sheet.setDismissLabel("✕ 关闭");
                    }
                    return true;   // 芯片留着，让用户重说
                }
            }
            sSubmittedSinceWake = true;
            hideChips(svc);
            restoreFocus();

            // 歧义追问后的回答（"第二个""北京那个"）
            if (sPendingCands != null && System.currentTimeMillis() - sPendingAt < ScreenRefSettings.CONTEXT_TTL_MS) {
                ScreenEntity pick = DeixisResolver.pickFromCandidates(command, sPendingCands);
                if (pick == null) {   // ★ C3-5：「2 号」「就最后那个吧」也认
                    int k = NumberedRefs.pick(command, sPendingCands.size());
                    if (k >= 0) pick = sPendingCands.get(k);
                }
                DeixisResolver.Action act = sPendingAction;
                if (pick != null) {
                    clearPending();
                    ScreenAction a = ActionMapper.map(act, pick);
                    if (a != null) {
                        Log.i(TAG, "[DEIXIS] 追问选中: " + pick);
                        runAction(svc, a, command);
                        return true;
                    }
                }
                if (!DeixisResolver.hasDeixis(command)) clearPending();
            }

            if (!DeixisResolver.hasDeixis(command)) return false;
            // [round7] 「看看屏幕上写的什么然后打开微信」这类复合任务：识屏只会做单实体动作（10-10 真机：被当成
            //   「打开链接」→ NO_MATCH「屏幕上没找到链接」，后半句丢了、也不出红卡）→ 不接管，交路由层分诊 → AgentLoop
            //   （AgentLoop 仍追加 screen.* 工具 / 注入屏幕上下文；读屏后置污点，app.launch 弹红卡）。
            if (DeixisResolver.isCompoundTask(command)) {
                clearPending();
                Log.i(TAG, "[DEIXIS] 复合任务（读屏 + 下一步动作），不接管，交路由层 / AgentLoop: " + com.onesay.assistant.v2.util.LogText.u(command));
                return false;
            }

            // 纯询问（「屏幕上这是什么」）直接识屏作答，不消解实体、不进多步循环、不弹确认卡
            if (DeixisResolver.isPureQuestion(command)) {
                clearPending();
                Log.i(TAG, "[DEIXIS] 纯询问 → 直接识屏作答: " + com.onesay.assistant.v2.util.LogText.u(command));
                svc.triggerScreenVision(command);
                return true;
            }
            Log.i(TAG, "[DEIXIS] 指代命中，接管: " + com.onesay.assistant.v2.util.LogText.u(command));
            final FloatingSheetView sheet = svc.getSheetView();
            if (sheet != null) sheet.updateState("正在解析", Color.parseColor("#0A84FF"), "“" + command + "” · 正在看屏幕…");
            com.onesay.assistant.v2.util.Threads.start("ScreenRefResolve", new Runnable() {
                @Override
                public void run() {
                    resolveInBackground(svc, command);
                }
            });
            return true;
        } catch (Throwable t) {
            Log.w(TAG, "[DEIXIS] 拦截异常，回落原流程: " + t.getMessage());
            return false;
        }
    }

    private static void resolveInBackground(final FloatingSheetService svc, final String command) {
        List<ScreenEntity> none = Collections.emptyList();
        // [s1] 剪贴板兜底：5 分钟内外部复制过的地址/电话等（只在屏幕上没有对应实体时使用；话里说「刚复制的」则只用它）
        final List<ScreenEntity> clip = clipEntities(svc);
        if (DeixisResolver.refersToClipboard(command)) {
            final DeixisResolver.Resolution cr = DeixisResolver.resolve(command, none, clip);
            Log.i(TAG, "[DEIXIS] 指向剪贴板，消解结果: " + cr + " clip=" + clip.size());
            main().post(new Runnable() {
                @Override
                public void run() {
                    handleResolution(svc, command, cr, null);
                }
            });
            return;
        }
        ScreenContext c = ScreenContextCapture.await(ScreenRefSettings.AWAIT_CAPTURE_MS);
        DeixisResolver.Resolution r = (c != null && !c.needsVl) ? DeixisResolver.resolve(command, c.entities, clip) : null;
        boolean alreadyVl = c != null && ScreenContext.SRC_VL.equals(c.source);
        boolean wantVl = (c == null || c.needsVl || (r != null && r.kind == DeixisResolver.Kind.NO_MATCH))
                && !alreadyVl && ScreenRefSettings.vlFallbackEnabled(svc);
        if (wantVl) {
            Log.i(TAG, "[DEIXIS] 文字树不可用/无匹配，走截图+VL 兜底（ctx=" + c + "）");
            main().post(new Runnable() {
                @Override
                public void run() {
                    FloatingSheetView sheet = svc.getSheetView();
                    if (sheet != null && svc.screenRefSheetAlive()) {
                        sheet.updateState("正在识屏", Color.parseColor("#5E5CE6"), "“" + command + "” · 正在识别屏幕文字…");
                    }
                }
            });
            ScreenContext v = ScreenContextCapture.ensureVl(svc, c);
            if (v != null && ScreenContext.SRC_VL.equals(v.source)) {
                c = v;
                r = DeixisResolver.resolve(command, c.entities, clip);
            }
        }
        if (r == null) r = DeixisResolver.resolve(command, c != null ? c.entities : none, clip);
        final DeixisResolver.Resolution fr = r;
        final ScreenContext fc = c;
        Log.i(TAG, "[DEIXIS] 消解结果: " + fr + " ctx=" + fc);
        main().post(new Runnable() {
            @Override
            public void run() {
                handleResolution(svc, command, fr, fc);
            }
        });
    }

    private static void handleResolution(FloatingSheetService svc, String command, DeixisResolver.Resolution r, ScreenContext c) {
        if (!svc.screenRefSheetAlive()) {
            Log.i(TAG, "[DEIXIS] 面板已收起，丢弃迟到的消解结果");
            return;
        }
        FloatingSheetView sheet = svc.getSheetView();
        switch (r.kind) {
            case RESOLVED: {
                ScreenAction a = ActionMapper.map(r.action, r.entity);
                if (a != null) {
                    runAction(svc, a, command);
                    return;
                }
                svc.screenRefRunAgentLoop(command);
                return;
            }
            case AMBIGUOUS: {
                sPendingCands = new ArrayList<ScreenEntity>(r.candidates);
                sPendingAction = r.action;
                sPendingAt = System.currentTimeMillis();
                StringBuilder sb = new StringBuilder();
                String kind = r.wantedType != null ? r.wantedType.label : "内容";
                sb.append("屏幕上有 ").append(r.candidates.size()).append(" 个").append(kind).append("，要哪一个？");
                List<ScreenAction> acts = new ArrayList<ScreenAction>();
                for (int i = 0; i < r.candidates.size(); i++) {
                    ScreenEntity e = r.candidates.get(i);
                    sb.append("\n").append(NumberedRefs.label(i, e.shortDisplay(40)));
                    ScreenAction a = ActionMapper.map(r.action, e);
                    if (a != null) acts.add(new ScreenAction(a.action, a.sign, a.args, NumberedRefs.label(i, a.label), e));   // ★ C3-5
                }
                sb.append("\n可以点下面的选项，或说“第二个”。");
                if (sheet != null) {
                    sheet.showAskReply(command, sb.toString());
                    sheet.setDismissLabel("✕ 关闭");
                }
                showChipActions(svc, acts);
                return;
            }
            case NO_MATCH: {
                String kind = r.wantedType != null ? r.wantedType.label : "可用的内容";
                String msg = DeixisResolver.refersToClipboard(command)
                        ? "最近 5 分钟没有复制过" + kind + "（或剪贴板芯片已关闭）。可以先复制一下，或直接说出具体内容。"
                        : (c == null)
                        ? "这次没能读取到屏幕内容，所以不知道“这个”指的是什么。可以先说“识别屏幕”，或直接说出具体内容。"
                        : "屏幕上没找到" + kind + "。可以直接说出具体内容，或说“识别屏幕”让我先看一遍。";
                if (sheet != null) {
                    sheet.showAskReply(command, msg);
                    sheet.setDismissLabel("✕ 关闭");
                }
                if (c != null && c.hasEntities()) maybeShowChips(svc, c, sWakeSeq);
                return;
            }
            case NEEDS_AGENT:
            default:
                if (DeixisResolver.isPureQuestion(command)) {   // 纯询问不进多步循环
                    Log.i(TAG, "[DEIXIS] NEEDS_AGENT 但是纯询问 → 直接识屏作答");
                    svc.triggerScreenVision(command);
                    return;
                }
                svc.screenRefRunAgentLoop(command);
        }
    }

    /** 把一个屏幕动作交给面板：与 dispatch 同一套门禁 → 确认卡 → 执行器。主线程。 */
    static void runAction(FloatingSheetService svc, ScreenAction a, String query) {
        Capability cap = ScreenRefCapabilities.isScreenSign(a.sign)
                ? ScreenRefCapabilities.get(a.sign)
                : CapabilityRegistry.getInstance().describe(a.sign);
        FloatingSheetView sheet = svc.getSheetView();
        JSONObject args = ScreenRefCapabilities.normalizeArgs(a.sign, copy(a.args), System.currentTimeMillis(), TimeZone.getDefault());
        if (cap == null || args == null) {
            Log.w(TAG, "[ACTION] 无法执行: " + a + " cap=" + cap);
            if (sheet != null) {
                sheet.showAskReply(query, "这个操作暂时做不了（缺少能力 " + a.sign + "）。");
                sheet.setDismissLabel("✕ 关闭");
            }
            return;
        }
        Log.i(TAG, "[ACTION] " + a.label + " → " + cap.sign + " " + args);
        svc.screenRefExecute(cap, args, query);
    }

    /**
     * 本地执行（复制 / 打开链接），由 FloatingSheetService.screenRefExecute 在门禁放行后回调。主线程。
     */
    public static void executeLocal(FloatingSheetService svc, Capability cap, JSONObject args, String query) {
        FloatingSheetView sheet = svc.getSheetView();
        ExecutionResult res = executeLocalSync(svc, cap, args);
        if (sheet != null) {
            sheet.showResult(res);
            sheet.setDismissLabel("✕ 关闭");
        }
        try {
            ConversationMemory.getInstance().record(query, "[识屏指代回执] " + res.userMessage, true);   // ★ LOGIC-1
        } catch (Throwable ignored) {
        }
        if (res.ok && ScreenRefCapabilities.SIGN_OPEN_URL.equals(cap.sign)) {
            final FloatingSheetService s = svc;
            main().postDelayed(new Runnable() {
                @Override
                public void run() {
                    s.hideSheet();
                }
            }, 220);
        }
    }

    /** 本地执行的同步实现（任意线程）。 */
    static ExecutionResult executeLocalSync(Context ctx, Capability cap, JSONObject args) {
        try {
            if (ScreenRefCapabilities.SIGN_COPY.equals(cap.sign)) {
                final String text = args.optString("text", "");
                final String kind = args.optString("kind", "内容");
                final Context app = ctx.getApplicationContext();
                final boolean[] ok = {false};
                final boolean[] readBack = {false};
                Runnable r = new Runnable() {
                    @Override
                    public void run() {
                        ClipboardManager cm = (ClipboardManager) app.getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            // [s1] 先登记"这是本 App 写的"，剪贴板芯片监听据此忽略，防止复制→弹芯片的回环
                            com.onesay.assistant.v2.s1.ClipboardMemory.markOwnWrite(text, System.currentTimeMillis());
                            cm.setPrimaryClip(ClipData.newPlainText(kind, text));
                            ok[0] = true;
                            // 三态回读（2026-10-07 集成）：读回剪贴板与写入一致才算 VERIFIED
                            try {
                                ClipData cd = cm.getPrimaryClip();
                                CharSequence got = cd != null && cd.getItemCount() > 0 ? cd.getItemAt(0).getText() : null;
                                readBack[0] = got != null && text.equals(got.toString());
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                };
                runOnMainBlocking(r, 2000);
                if (!ok[0]) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "复制失败：拿不到剪贴板服务", null, cap, args);
                Log.i(TAG, "[LOCAL] 已复制 " + kind + "（" + text.length() + " 字）readBack=" + readBack[0]);
                return ExecutionResult.success(cap, args, "已复制" + kind + "：" + text,
                        readBack[0] ? ExecutionResult.Verification.VERIFIED : ExecutionResult.Verification.UNVERIFIED, text);
            }
            if (ScreenRefCapabilities.SIGN_OPEN_URL.equals(cap.sign)) {
                String u = ActionMapper.safeUrl(args.optString("url", ""));
                if (u == null) return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, "链接不安全或格式不对，未打开", null, cap, args);
                Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(u));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.getApplicationContext().startActivity(i);
                Log.i(TAG, "[LOCAL] 已打开链接 " + u);
                // 只拉起了浏览器/邮件应用，页面是否打开无从回读 → UNVERIFIED
                return ExecutionResult.success(cap, args, u.startsWith("mailto:") ? "正在写邮件" : "正在打开链接",
                        ExecutionResult.Verification.UNVERIFIED, u);
            }
        } catch (Throwable t) {
            Log.w(TAG, "[LOCAL] 执行异常: " + t.getMessage());
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "执行失败：" + t.getMessage(), t.toString(), cap, args);
        }
        return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, "未知的本地能力 " + cap.sign, null, cap, args);
    }

    // =====================================================================
    // [s1] 供 S1（剪贴板芯片 / 圈选即问）复用的入口
    // =====================================================================

    /** [s1] 剪贴板实体（开关关闭或过期返回空列表）。 */
    static List<ScreenEntity> clipEntities(Context ctx) {
        try {
            if (ctx == null || !com.onesay.assistant.v2.s1.S1Settings.clipDeixisEnabled(ctx)) return Collections.emptyList();
            return com.onesay.assistant.v2.s1.ClipboardMemory.recentEntities(System.currentTimeMillis());
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    /** [s1] 圈选完成：用区域上下文显示动作芯片（面板须已挂载）。主线程。返回显示的芯片数。 */
    public static int showChipsForContext(FloatingSheetService svc, ScreenContext c) {
        if (svc == null || c == null || !ScreenRefSettings.isEnabled(svc)) return 0;
        sSubmittedSinceWake = false;
        clearPending();
        List<ScreenAction> acts = ActionMapper.planChips(c.entities);
        if (acts.isEmpty()) return 0;
        Log.i(TAG, "[CHIPS] 圈选区域芯片 " + acts.size() + " 个: " + acts);
        showChipActions(svc, acts);
        return acts.size();
    }

    // =====================================================================
    // 芯片
    // =====================================================================

    private static void maybeShowChips(FloatingSheetService svc, ScreenContext c, int seq) {
        if (!ScreenRefSettings.chipsEnabled(svc) || c == null || !svc.screenRefSheetAlive()) return;
        List<ScreenAction> acts = ActionMapper.planChips(c.entities);
        if (acts.isEmpty()) return;
        Log.i(TAG, "[CHIPS] 显示 " + acts.size() + " 个芯片: " + acts);
        showChipActions(svc, acts);
    }

    private static void showChipActions(final FloatingSheetService svc, List<ScreenAction> acts) {
        ActionChipsView v = ensureChipsView(svc);
        if (v == null) return;
        sChipActions.clear();
        sChipActions.addAll(acts);
        List<String> labels = new ArrayList<String>();
        // ★ lane C3-5：芯片加编号（歧义候选已自带编号的不重复加），并登记为「最近摆出的编号列表」
        for (int i = 0; i < acts.size(); i++) {
            String l = acts.get(i).label;
            labels.add(l != null && l.startsWith(NumberedRefs.label(i, "").trim()) ? l : NumberedRefs.label(i, l));
        }
        NumberedRefs.publish(NumberedRefs.OWNER_SCREEN, acts.size(), System.currentTimeMillis(), ScreenRefSettings.CONTEXT_TTL_MS);
        v.setListener(new ActionChipsView.Listener() {
            @Override
            public void onChipClicked(int index) {
                if (index < 0 || index >= sChipActions.size()) return;
                ScreenAction a = sChipActions.get(index);
                Log.i(TAG, "[CHIPS] 点击: " + a);
                try {
                    WakeService.cancelCaptureFromUi();   // 用户改用手点：停止本轮收音
                } catch (Throwable ignored) {
                }
                sSubmittedSinceWake = true;
                clearPending();
                hideChips(svc);
                runAction(svc, a, a.label);
            }
        });
        v.showChips(labels);
    }

    private static ActionChipsView ensureChipsView(FloatingSheetService svc) {
        FloatingSheetView sheet = svc.getSheetView();
        if (sheet == null) return null;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) return (ActionChipsView) existing;
        ActionChipsView v = new ActionChipsView(svc);
        int idx = Math.min(2, sheet.getChildCount());   // 状态行、模式胶囊行之后
        sheet.addView(v, idx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private static void hideChips(FloatingSheetService svc) {
        if (!sChipActions.isEmpty()) NumberedRefs.withdraw(NumberedRefs.OWNER_SCREEN);   // ★ C3-5
        sChipActions.clear();
        FloatingSheetView sheet = svc != null ? svc.getSheetView() : null;
        if (sheet == null) return;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) ((ActionChipsView) existing).hideChips();
    }

    private static void clearPending() {
        sPendingCands = null;
        sPendingAction = null;
        sPendingAt = 0;
    }

    // =====================================================================
    // AgentLoop 侧挂钩（后台线程）
    // =====================================================================

    /** [挂钩] AgentLoop 导出 tools 之后：功能开启且（指代命中或会话里有屏幕缓存）时追加 screen.* 工具。 */
    public static void appendTools(Context ctx, JSONArray tools, String query) {
        try {
            if (ctx == null || tools == null || !ScreenRefSettings.isEnabled(ctx)) return;
            if (!DeixisResolver.hasDeixis(query) && ScreenContextCapture.fresh() == null) return;
            Set<String> names = new HashSet<String>();
            for (int i = 0; i < tools.length(); i++) {
                JSONObject fn = tools.optJSONObject(i) != null ? tools.optJSONObject(i).optJSONObject("function") : null;
                if (fn != null) names.add(fn.optString("name"));
            }
            JSONArray mine = CapabilityRegistry.getInstance().exportToolsSchema(ScreenRefCapabilities.all());
            int added = 0;
            for (int i = 0; i < mine.length(); i++) {
                JSONObject t = mine.optJSONObject(i);
                JSONObject fn = t != null ? t.optJSONObject("function") : null;
                if (fn == null || names.contains(fn.optString("name"))) continue;
                tools.put(t);
                added++;
            }
            Log.i(TAG, "[AGENT] 追加屏幕工具 " + added + " 个");
        } catch (Throwable t) {
            Log.w(TAG, "[AGENT] 追加工具异常: " + t.getMessage());
        }
    }

    /** [挂钩] AgentLoop 组装用户消息时：指代命中则在末尾附上屏幕上下文。 */
    public static String withScreenHint(Context ctx, String query, String content) {
        try {
            if (ctx == null || !ScreenRefSettings.isEnabled(ctx) || !DeixisResolver.hasDeixis(query)) return content;
            ScreenContext c = ScreenContextCapture.await(3000);
            if (c != null && (!c.needsVl || ScreenContext.SRC_VL.equals(c.source))) {
                Log.i(TAG, "[AGENT] 注入屏幕上下文 entities=" + c.entities.size());
                return content + c.promptHint(8);
            }
            return content + NO_SCREEN_NOTE;
        } catch (Throwable t) {
            return content;
        }
    }

    /** [挂钩] 模型回传签名不在注册表时，解析 screen.* 能力（功能关闭返回 null）。 */
    public static Capability describe(Context ctx, String sign) {
        if (ctx == null || !ScreenRefSettings.isEnabled(ctx)) return null;
        return ScreenRefCapabilities.get(sign);
    }

    /**
     * [挂钩] AgentLoop 内 {@code withScreenHint} 之后：用户消息里是否真的注入了屏幕内容。
     * 注入了 = 不可信内容已进入上下文 → 本任务从第一步起按 tainted 过闸（与"读屏成功后置污点"同一规则）。
     */
    public static boolean isScreenInjected(String before, String after) {
        if (after == null || after.equals(before)) return false;
        return !(before != null && after.equals(before + NO_SCREEN_NOTE));
    }

    /**
     * [挂钩] AgentLoop 处理每个工具调用、<b>过闸之前</b>（2026-10-07 集成改造）：
     * <ul>
     *   <li>非 screen.* 工具 → null（不干预）；</li>
     *   <li>screen.* → 只做参数兜底/校验（原地修正 args：日历时间解析、URL 白名单等），返回 null 表示继续走
     *       {@code ExecutionGate.evaluate → 确认卡 → 执行}；参数无法修复或功能已关闭 → 返回错误观测，本步不执行。</li>
     * </ul>
     * <b>不再在这里直接执行</b> screen.context / screen.copy / screen.open.url：旧实现绕过了门禁，
     * 读屏不会置污点、污点状态下的"打开链接"也不弹卡。现在这三项在门禁放行（或用户确认）后由
     * {@link #executeForAgent} 执行；screen.context 声明为 PRIVATE_DATA_READ，读取成功后 AgentLoop 会把后续步骤标为 tainted。
     */
    public static String handleLocalTool(Context ctx, String sign, JSONObject args) {
        try {
            if (ctx == null || sign == null || !ScreenRefCapabilities.isScreenSign(sign)) return null;
            if (!ScreenRefSettings.isEnabled(ctx)) return "{\"error\":\"识屏指代功能已关闭\"}";
            if (ScreenRefCapabilities.SIGN_CONTEXT.equals(sign)) return null;
            JSONObject fixed = ScreenRefCapabilities.normalizeArgs(sign, args, System.currentTimeMillis(), TimeZone.getDefault());
            if (fixed == null) return "{\"error\":\"参数不完整或不安全，未执行（" + sign + "）\"}";
            return null;
        } catch (Throwable t) {
            return "{\"error\":\"屏幕工具异常: " + t.getMessage() + "\"}";
        }
    }

    /**
     * [挂钩] AgentLoop 的执行一步（门禁已放行或令牌已批准之后）：App 进程内执行 screen.context / copy / open.url。
     * 非本地 screen 工具返回 null（交宿主执行器）。任意线程。
     */
    public static ExecutionResult executeForAgent(Context ctx, Capability cap, JSONObject args) {
        if (ctx == null || cap == null || !ScreenRefCapabilities.isLocal(cap.sign)) return null;
        try {
            if (ScreenRefCapabilities.SIGN_CONTEXT.equals(cap.sign)) {
                ScreenContext c = ScreenContextCapture.await(3000);
                if (c == null || (c.needsVl && !ScreenContext.SRC_VL.equals(c.source))) c = ScreenContextCapture.ensureVl(ctx, c);
                if (c == null) {
                    return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "唤醒时未采集到屏幕，识屏兜底也不可用",
                            "screen context unavailable", "可改用 vision__screen__understand", cap, args);
                }
                String type = args != null ? args.optString("type", "") : "";
                boolean withTexts = args != null && args.optBoolean("with_texts", false);
                ScreenContext view = c;
                if (type.length() > 0) {
                    List<ScreenEntity> f = new ArrayList<ScreenEntity>();
                    for (ScreenEntity e : c.entities) if (e.type.name().equalsIgnoreCase(type)) f.add(e);
                    view = new ScreenContext(c.pkg, c.activity, c.source, c.capturedAtMs, c.costMs, c.nodes, c.texts, f, c.error, c.needsVl, c.redacted);
                }
                String json = view.toCompactJson(12, withTexts ? 20 : 0).toString();
                // 读取类：拿到了真实数据即 VERIFIED（与 mail.* 读取同口径）
                return ExecutionResult.success(cap, args, json, ExecutionResult.Verification.VERIFIED, "screen context " + c.source);
            }
            JSONObject fixed = ScreenRefCapabilities.normalizeArgs(cap.sign, args, System.currentTimeMillis(), TimeZone.getDefault());
            if (fixed == null) {
                return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, "参数不完整或不安全，未执行", null, cap, args);
            }
            return executeLocalSync(ctx, cap, fixed);
        } catch (Throwable t) {
            return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "屏幕工具异常: " + t.getMessage(), t.toString(), cap, args);
        }
    }

    // =====================================================================
    // 工具
    // =====================================================================

    private static JSONObject copy(JSONObject in) {
        JSONObject o = new JSONObject();
        if (in == null) return o;
        try {
            Iterator<?> it = in.keys();
            while (it.hasNext()) {
                String k = (String) it.next();
                o.put(k, in.opt(k));
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    private static void runOnMainBlocking(final Runnable r, long timeoutMs) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
            return;
        }
        final java.util.concurrent.CountDownLatch l = new java.util.concurrent.CountDownLatch(1);
        main().post(new Runnable() {
            @Override
            public void run() {
                try {
                    r.run();
                } finally {
                    l.countDown();
                }
            }
        });
        try {
            l.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    static boolean keyguardLocked(android.content.Context c) {
        try {
            android.app.KeyguardManager km = (android.app.KeyguardManager) c.getSystemService(android.content.Context.KEYGUARD_SERVICE);
            return km != null && km.isKeyguardLocked();
        } catch (Throwable t) {
            return false;
        }
    }
}
