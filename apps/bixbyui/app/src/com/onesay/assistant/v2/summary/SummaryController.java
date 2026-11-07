package com.onesay.assistant.v2.summary;

import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.router.L2LLMCompiler;
import com.onesay.assistant.v2.router.UnifiedRouter;
import com.onesay.assistant.v2.s2.S2Capabilities;
import com.onesay.assistant.v2.s2.S2Hooks;
import com.onesay.assistant.v2.s2.S2Settings;
import com.onesay.assistant.v2.s2.SensitiveGuard;
import com.onesay.assistant.v2.ui.ActionChipsView;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;
import com.onesay.assistant.v2.vision.ScreenVisionEngine;
import com.onesay.assistant.v2.vision.screenref.ScreenContext;
import com.onesay.assistant.v2.vision.screenref.ScreenContextCapture;
import com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities;
import com.onesay.assistant.v2.wake.WakeService;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [summary] 「总结这页」流程（Android 侧；纯逻辑在 SummaryIntent / SummaryInput / SummaryGuard / SummaryPrompt / SummaryResult）。
 *
 * <pre>
 * 面板让位（s1HideSheetNow，与语音存图 / 圈一下同一个入口）→ 350 ms →
 * 既有识屏采集 ScreenContextCapture.captureSync（uiautomator dump --compressed，不写会话缓存）→
 *   隐私闸门 SummaryGuard.screenReason（密码框 / 验证码）→ 命中：面板回来说明原因，不调用任何模型；
 *   文字 ≥ 60 字 → 面板回来「正在总结」→ <b>一次</b> DeepSeek（共用 L2 客户端，预算 3000 字、max_tokens 320、读超时 15 s）；
 *   文字太少 → （开关 vl）既有截图 + Qwen-VL <b>一次</b>（截完图才挂回面板；全黑 = FLAG_SECURE → 不上传、明确提示）；
 * → SummaryResult.parse → 面板「一句话 + • 要点」+ 芯片「复制摘要」「记下来」。
 * </pre>
 * 摘要是不可信内容的转述：只上屏，不进 AgentLoop、不写 L1 / ASK 缓存、不进会话记忆、不触发动作；
 * 两个芯片都是用户点了才执行，并且走 ExecutionGate（复制 = screen.copy，记下来 = s2.memory.add + 敏感闸门）。
 */
final class SummaryController {

    private static final String TAG = "Summary";

    private final FloatingSheetService mSvc;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private volatile boolean mBusy;
    private volatile int mSeq;
    volatile String mLastResult = "";
    private volatile SummaryResult mLast;

    SummaryController(FloatingSheetService svc) {
        this.mSvc = svc;
    }

    boolean busy() {
        return mBusy;
    }

    /** 主线程。 */
    void start(final String command) {
        if (mBusy) {
            Log.i(TAG, "[SUMMARY] 上一次总结还在进行，忽略");
            return;
        }
        mBusy = true;
        final int seq = ++mSeq;
        hideChips();
        Log.i(TAG, "[SUMMARY] 开始 | " + SummarySettings.dump(mSvc));
        mSvc.s1HideSheetNow();   // 面板先让位：dump 要拿到前台 App 的窗口，截图兜底也不能截进面板
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                com.onesay.assistant.v2.util.Threads.start("Summary", new Runnable() {
                    @Override public void run() {
                        try {
                            run0(seq, command);
                        } catch (Throwable t) {
                            Log.w(TAG, "[SUMMARY] 异常: " + t);
                            finish(seq, command, "总结失败：" + t.getMessage(), null);
                        }
                    }
                });
            }
        }, 350L);
    }

    void destroy() {
        mMain.removeCallbacksAndMessages(null);
        mSeq++;
        mBusy = false;
    }

    // ------------------------------------------------------------------ 后台线程

    private void run0(final int seq, final String command) {
        long t0 = SystemClock.elapsedRealtime();
        ScreenContext sc = ScreenContextCapture.captureSync(mSvc);
        long tCap = SystemClock.elapsedRealtime() - t0;
        if (sc == null || (sc.error != null && sc.error.contains("root"))) {
            finish(seq, command, "总结需要读取屏幕文字，但 root 守护现在不可用。", null);
            return;
        }
        String why = SummaryGuard.screenReason(sc.texts, sc.redacted);
        Log.i(TAG, "[SUMMARY] 采集 " + tCap + "ms app=" + sc.pkg + " source=" + sc.source + " 节点=" + sc.nodes.size()
                + " 文字行=" + sc.texts.size() + " redacted=" + sc.redacted + (why != null ? " 敏感=" + why : ""));
        if (why != null) {
            finish(seq, command, "这一页有" + why + "，为了安全不做摘要（密码、验证码页面都不会读给模型）。", null);
            return;
        }
        SummaryInput.Built in = SummaryInput.fromNodes(sc.nodes, screenHeight(), SummaryInput.BUDGET);
        Log.i(TAG, "[SUMMARY] 输入 " + in);
        if (in.enough()) {
            attach("正在总结", "读到屏幕文字 " + in.totalChars + " 字" + (in.truncated ? "（太长，保留开头、结尾和这一屏）" : ""));
            summarizeText(seq, command, sc.pkg, in, t0);
            return;
        }
        if (!SummarySettings.vlEnabled(mSvc)) {
            finish(seq, command, "屏幕上能读到的文字太少（" + in.totalChars + " 字），没法总结。看图总结已在控制台关闭。", null);
            return;
        }
        summarizeVision(seq, command, t0);
    }

    private void summarizeText(int seq, String command, String pkg, SummaryInput.Built in, long t0) {
        L2LLMCompiler l2 = UnifiedRouter.getInstance(mSvc).getL2();
        if (l2 == null || !l2.hasApiKey()) {
            finish(seq, command, "还没有配置大模型 API Key，总结需要它（在控制台「大模型」卡片里填）。", null);
            return;
        }
        try {
            com.onesay.assistant.v2.net.LlmClient c = l2.client().withReadTimeout(SummaryPrompt.READ_TIMEOUT_MS);
            String body = com.onesay.assistant.v2.net.LlmClient.body(SummaryPrompt.MODEL, SummaryPrompt.TEMPERATURE, SummaryPrompt.MAX_TOKENS,
                    com.onesay.assistant.v2.net.LlmClient.messages(SummaryPrompt.system(), SummaryPrompt.user(pkg, in)), null, false).toString();
            com.onesay.assistant.v2.net.LlmResponse r = c.post(body);
            long cost = SystemClock.elapsedRealtime() - t0;
            if (!r.ok()) {
                Log.w(TAG, "[SUMMARY] 文字模型失败 " + cost + "ms: " + r.error);
                finish(seq, command, "总结失败：大模型没有返回（" + r.error + "）。", null);
                return;
            }
            SummaryResult res = SummaryResult.parse(r.content());
            Log.i(TAG, "[SUMMARY] 文字路径 " + cost + "ms 输出 " + (r.content() != null ? r.content().length() : 0) + " 字 → " + res);
            // 2026-10-10 真机：WebView（三星浏览器等）在没开无障碍服务时不出正文节点，文字树只剩界面碎字 → 模型 EMPTY。
            // 这时改走看图（与「文字太少」同一条路径；开关 vl 关着则照旧提示内容太少）。
            if (res.kind == SummaryResult.Kind.EMPTY && SummarySettings.vlEnabled(mSvc)) {
                Log.i(TAG, "[SUMMARY] 文字路径 EMPTY → 改走截图 + 视觉模型");
                summarizeVision(seq, command, t0);
                return;
            }
            deliver(seq, command, res, "text");
        } catch (Throwable t) {
            finish(seq, command, "总结失败：" + t.getMessage(), null);
        }
    }

    private void summarizeVision(final int seq, final String command, final long t0) {
        Log.i(TAG, "[SUMMARY] 屏幕文字太少 → 截图 + 视觉模型");
        ScreenVisionEngine.getInstance(mSvc).recognizeScreenGuarded(SummaryPrompt.vision(), new Runnable() {
            @Override public void run() {
                attach("正在总结", "屏幕文字太少，改为看图总结");
            }
        }, new ScreenVisionEngine.Callback() {
            @Override public void onProgress(String stage) {
            }

            @Override public void onSuccess(String answer) {
                SummaryResult res = SummaryResult.parse(answer);
                Log.i(TAG, "[SUMMARY] 视觉路径 " + (SystemClock.elapsedRealtime() - t0) + "ms 输出 " + (answer != null ? answer.length() : 0)
                        + " 字 → " + res);
                // 看图路径没有文字闸门：模型把验证码 / 卡号抄进了输出（被擦掉）⇒ 这页本身就是敏感页，整页拒绝，不只擦一行
                if (res.kind == SummaryResult.Kind.OK && res.scrubbed > 0) {
                    Log.i(TAG, "[SUMMARY] 视觉输出含敏感信息（scrubbed=" + res.scrubbed + "）→ 按敏感页拒绝");
                    res = SummaryResult.sensitive();
                }
                deliver(seq, command, res, "vl");
            }

            @Override public void onError(String errorMsg) {
                if (ScreenVisionEngine.ERR_SECURE_BLACK.equals(errorMsg)) {
                    Log.i(TAG, "[SUMMARY] 截图全黑（FLAG_SECURE），跳过");
                    finish(seq, command, "这个页面禁止截图（FLAG_SECURE），截出来是全黑的，也读不到足够的文字，没法总结。", null);
                    return;
                }
                Log.w(TAG, "[SUMMARY] 视觉模型失败: " + errorMsg);
                finish(seq, command, "总结失败：" + errorMsg, null);
            }
        });
    }

    private void deliver(int seq, String command, SummaryResult res, String via) {
        switch (res.kind) {
            case SENSITIVE:
                finish(seq, command, "这一页像是密码或验证码界面，为了安全不做摘要。", null);
                return;
            case EMPTY:
                finish(seq, command, "这一页内容太少，看不出主题，没什么可总结的。", null);
                return;
            case FAIL:
                finish(seq, command, "总结失败：模型没有给出可用的内容。", null);
                return;
            default:
                Log.i(TAG, "[SUMMARY] 完成 via=" + via + " " + res);
                finish(seq, command, res.format(), res);
        }
    }

    // ------------------------------------------------------------------ 面板（主线程）

    private void attach(final String badge, final String detail) {
        mMain.post(new Runnable() {
            @Override public void run() {
                mSvc.s1AttachSheet();
                FloatingSheetView sheet = mSvc.getSheetView();
                if (sheet != null) sheet.updateState(badge, Color.parseColor("#0A84FF"), detail);
            }
        });
    }

    private void finish(final int seq, final String command, final String msg, final SummaryResult res) {
        mLastResult = (res != null ? "OK " + res : "MSG " + msg.replace('\n', ' '));
        mMain.post(new Runnable() {
            @Override public void run() {
                if (seq != mSeq) return;   // 已被新的一次覆盖 / 服务销毁
                mBusy = false;
                mLast = res;
                mSvc.s1AttachSheet();
                FloatingSheetView sheet = mSvc.getSheetView();
                if (sheet == null) return;
                sheet.showAskReply(command == null || command.length() == 0 ? "总结一下" : command, msg);
                if (res != null) showChips(res);
            }
        });
    }

    private void showChips(final SummaryResult res) {
        ActionChipsView v = ensureChipsView();
        if (v == null) return;
        final List<String> labels = new ArrayList<String>();
        labels.add(SummaryHooks.CHIP_COPY);
        if (S2Settings.memoryEnabled(mSvc)) labels.add(SummaryHooks.CHIP_NOTE);
        v.setListener(new ActionChipsView.Listener() {
            @Override public void onChipClicked(int index) {
                if (index < 0 || index >= labels.size()) return;
                stopListening();
                hideChips();
                onChip(labels.get(index), res);
            }
        });
        v.showChips(labels);
        Log.i(TAG, "[SUMMARY] 芯片 " + labels);
    }

    /** 用户点了芯片（主线程）。两条都走 screenRefExecute → ExecutionGate（与识屏芯片同一入口：用户亲手点的芯片不带污点）。 */
    void onChip(String label, SummaryResult res) {
        if (res == null) return;
        if (SummaryHooks.CHIP_COPY.equals(label)) {
            Capability cap = ScreenRefCapabilities.get(ScreenRefCapabilities.SIGN_COPY);
            JSONObject a = new JSONObject();
            try {
                a.put("text", res.plain(800));
                a.put("kind", "摘要");
            } catch (Exception ignored) {
            }
            Log.i(TAG, "[SUMMARY] 芯片 复制摘要（" + res.plain(800).length() + " 字）");
            if (cap != null) mSvc.screenRefExecute(cap, a, "复制摘要");
        } else if (SummaryHooks.CHIP_NOTE.equals(label)) {
            String content = "屏幕摘要：" + res.plain(180);
            String why = SensitiveGuard.reason(content);
            if (why != null) {
                Log.i(TAG, "[SUMMARY] 芯片 记下来 → 敏感闸门拒绝: " + why);
                FloatingSheetView sheet = mSvc.getSheetView();
                if (sheet != null) sheet.showAskReply("记下来", SensitiveGuard.refusal(why));
                return;
            }
            Capability cap = S2Hooks.describe(S2Capabilities.SIGN_NOTE_ADD);
            JSONObject a = new JSONObject();
            try {
                a.put("content", content);
            } catch (Exception ignored) {
            }
            Log.i(TAG, "[SUMMARY] 芯片 记下来（" + content.length() + " 字）→ s2.memory.add");
            if (cap != null) mSvc.screenRefExecute(cap, a, "记下来");
        }
    }

    SummaryResult last() {
        return mLast;
    }

    private int screenHeight() {
        try {
            WindowManager wm = (WindowManager) mSvc.getSystemService(android.content.Context.WINDOW_SERVICE);
            DisplayMetrics dm = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            return Math.max(dm.heightPixels, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    private static void stopListening() {
        try {
            WakeService.cancelCaptureFromUi();
        } catch (Throwable ignored) {
        }
    }

    private ActionChipsView ensureChipsView() {
        FloatingSheetView sheet = mSvc.getSheetView();
        if (sheet == null) return null;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) return (ActionChipsView) existing;
        ActionChipsView v = new ActionChipsView(mSvc);
        int idx = Math.min(2, sheet.getChildCount());   // 与识屏 / 诊断芯片同一位置
        sheet.addView(v, idx, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return v;
    }

    private void hideChips() {
        FloatingSheetView sheet = mSvc.getSheetView();
        if (sheet == null) return;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) ((ActionChipsView) existing).hideChips();
    }
}
