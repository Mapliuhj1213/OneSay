package com.onesay.assistant.v2.suggest.shot;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.s1.S1Capabilities;
import com.onesay.assistant.v2.ui.ActionChipsView;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;
import com.onesay.assistant.v2.vision.ScreenVisionEngine;
import com.onesay.assistant.v2.vision.screenref.ActionMapper;
import com.onesay.assistant.v2.vision.screenref.EntityExtractor;
import com.onesay.assistant.v2.vision.screenref.ScreenAction;
import com.onesay.assistant.v2.vision.screenref.ScreenEntity;
import com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities;

import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * ★ feat/suggest：用户点了「分析」才走到这里（不点不上传）。
 * <ol>
 *   <li>再查一次文件还在不在：不在 → 「截图已删除」，不发请求；</li>
 *   <li>ScreenVisionEngine.recognizeImage（同一套压缩：长边 1080、JPEG 75；用户已配置的 DashScope Key）；</li>
 *   <li>D4：先出结构化结果（摘要 + 图中文字，标「未核实」），再给后续芯片（单号 / 地址 / 链接 / 日期 / 电话 / 取件码 →「放到岛上」）；
 *       <b>不自动执行任何动作</b>。芯片点击走 screenRefExecute → ExecutionGate → 确认卡（拨号黄卡、建日程照常确认）。</li>
 * </ol>
 * 日志不记路径、不记识别出的文字。
 */
public final class ScreenshotAnalyzer {
    private ScreenshotAnalyzer() {
    }

    static final String TAG = "Suggest";
    static final String QUERY = "分析截图";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    public interface Done {
        void finished(boolean ok, String reason);
    }

    public static void analyze(final FloatingSheetService svc, final String path, final Done done) {
        if (svc == null || path == null) {
            if (done != null) done.finished(false, "no_svc");
            return;
        }
        if (!new File(path).isFile()) {
            Log.i(TAG, "[SUGGEST] 分析：截图已删除，不上传");
            reply(svc, "截图已删除，没有上传。");
            if (done != null) done.finished(false, "gone");
            return;
        }
        reply(svc, "正在分析截图…\n（" + ScreenVisionEngine.UPLOAD_NOTICE.replace("当前整屏画面", "这张截图") + "）");
        ScreenVisionEngine.getInstance(svc).recognizeImage(path, ScreenshotResult.PROMPT, new ScreenVisionEngine.Callback() {
            @Override public void onProgress(String stage) {
            }

            @Override public void onSuccess(String answer) {
                // 解析 / 实体抽取 / 芯片规划都在这条工作线程做完，主线程只上屏
                final ScreenshotResult r = ScreenshotResult.parse(answer);
                final String shown = r.display();
                final ChipPlan plan = planChips(r);
                Log.i(TAG, "[SUGGEST] 分析完成 structured=" + r.structured + " entities=" + r.entities.size() + " textLen=" + r.text.length());
                MAIN.post(new Runnable() {
                    @Override public void run() {
                        reply(svc, shown);
                        showChips(svc, plan);
                    }
                });
                if (done != null) done.finished(true, null);
            }

            @Override public void onError(String errorMsg) {
                Log.w(TAG, "[SUGGEST] 分析失败");
                reply(svc, "截图分析没成功：" + (errorMsg == null ? "未知原因" : errorMsg));
                if (done != null) done.finished(false, "vision_error");
            }
        });
    }

    private static void reply(final FloatingSheetService svc, final String text) {
        MAIN.post(new Runnable() {
            @Override public void run() {
                try {
                    svc.s1AttachSheet();
                    FloatingSheetView sheet = svc.getSheetView();
                    if (sheet != null) {
                        sheet.showAskReply(QUERY, text);
                        sheet.setDismissLabel("✕ 关闭");
                    }
                } catch (Throwable t) {
                    Log.w(TAG, "[SUGGEST] 面板显示失败: " + t.getClass().getSimpleName());
                }
            }
        });
    }

    /** 后续芯片规划（工作线程）：本地 ActionMapper（每类一个，≤3）+ 取件码「放到岛上」。只是建议，点了才执行。 */
    static final class ChipPlan {
        final List<ScreenAction> acts;
        final List<String> labels;
        final String code;

        ChipPlan(List<ScreenAction> acts, List<String> labels, String code) {
            this.acts = acts;
            this.labels = labels;
            this.code = code;
        }
    }

    static ChipPlan planChips(ScreenshotResult r) {
        List<ScreenEntity> ents = EntityExtractor.extractFromTexts(r.lines(), System.currentTimeMillis(), TimeZone.getDefault());
        final List<ScreenAction> acts = ActionMapper.planChips(ents);
        final List<String> codes = new ArrayList<String>();
        for (ScreenEntity e : ents) if (e.type == ScreenEntity.Type.CODE && !codes.contains(e.value)) codes.add(e.value);
        for (String c : r.pickupCodes()) if (!codes.contains(c)) codes.add(c);
        final List<String> labels = new ArrayList<String>();
        for (ScreenAction a : acts) labels.add(a.label);
        final String code = codes.isEmpty() ? null : codes.get(0);
        if (code != null) labels.add("取件码放到岛上");
        return new ChipPlan(acts, labels, code);
    }

    /** 兼容旧调用（主线程直接给结果）：规划 + 上屏。 */
    static void showChips(final FloatingSheetService svc, ScreenshotResult r) {
        showChips(svc, planChips(r));
    }

    /** 芯片上屏（主线程，只建 / 复用 View）。 */
    static void showChips(final FloatingSheetService svc, ChipPlan plan) {
        final List<ScreenAction> acts = plan.acts;
        final List<String> labels = plan.labels;
        final String code = plan.code;
        if (labels.isEmpty()) return;
        FloatingSheetView sheet = svc.getSheetView();
        if (sheet == null) return;
        ActionChipsView v;
        View existing = sheet.findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (existing instanceof ActionChipsView) v = (ActionChipsView) existing;
        else {
            v = new ActionChipsView(svc);
            sheet.addView(v, Math.min(2, sheet.getChildCount()), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        final ActionChipsView chips = v;
        v.setListener(new ActionChipsView.Listener() {
            @Override public void onChipClicked(int index) {
                chips.hideChips();
                if (index >= 0 && index < acts.size()) run(svc, acts.get(index));
                else if (code != null && index == acts.size()) pinCode(svc, code);
            }
        });
        v.showChips(labels);
        Log.i(TAG, "[SUGGEST] 截图后续芯片 " + labels.size() + " 个");
    }

    /** 与剪贴板芯片同一条链路：能力 → screenRefExecute → ExecutionGate → 确认卡 → 执行器。 */
    static void run(FloatingSheetService svc, ScreenAction a) {
        Capability cap = S1Capabilities.localCapability(a.sign);
        if (cap == null) cap = CapabilityRegistry.getInstance().describe(a.sign);
        JSONObject args = ScreenRefCapabilities.isScreenSign(a.sign)
                ? ScreenRefCapabilities.normalizeArgs(a.sign, a.args, System.currentTimeMillis(), TimeZone.getDefault()) : a.args;
        if (cap == null || args == null) {
            Log.w(TAG, "[SUGGEST] 芯片缺能力或参数: " + a.sign);
            return;
        }
        Log.i(TAG, "[SUGGEST] 截图芯片 → " + cap.sign + "（经 ExecutionGate）");
        svc.screenRefExecute(cap, args, "截图：" + a.label);
    }

    static void pinCode(FloatingSheetService svc, String code) {
        boolean ok = com.onesay.assistant.v2.kernel.FeatureKernel.get().island(com.onesay.assistant.v2.suggest.SuggestPlugs.MASTER)
                .addCode("取件码", code, null, null, com.onesay.assistant.v2.s1.S1Settings.PICKUP_TTL_MS, 0L);
        Log.i(TAG, "[SUGGEST] 截图取件码上岛 " + (ok ? "ok" : "已在岛上 / 岛不可用"));
    }
}
