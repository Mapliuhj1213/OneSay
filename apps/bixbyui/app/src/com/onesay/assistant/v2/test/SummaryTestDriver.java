package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onesay.assistant.v2.router.TriageDecider;
import com.onesay.assistant.v2.summary.SummaryGuard;
import com.onesay.assistant.v2.summary.SummaryHooks;
import com.onesay.assistant.v2.summary.SummaryInput;
import com.onesay.assistant.v2.summary.SummaryIntent;
import com.onesay.assistant.v2.summary.SummarySettings;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.vision.screenref.ScreenContext;
import com.onesay.assistant.v2.vision.screenref.ScreenContextCapture;

/**
 * [summary] #18 长内容摘要调试入口（仅 debug 包，经 TestRunReceiver；adb 需 su）。日志只打长度 / 计数，<b>不打屏幕原文</b>。
 *
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es summary status"                          # 开关 / kill-switch / 是否在跑 / 最近一次结果
 * su -c "am broadcast -n $R --es summary judge --es text '这篇讲了什么'"     # 只判定：意图 + 分诊（不读屏、不调模型）
 * su -c "am broadcast -n $R --es summary input"                           # 读一次当前屏幕（--compressed dump）：敏感判定 + 输入字数/截断（不调模型）
 * su -c "am broadcast -n $R --es summary run --es text '总结一下'"          # 等同在面板说这句话（只走摘要流程，会调一次模型）
 * su -c "am broadcast -n $R --es summary chip --es text '复制摘要'"          # 点最近一次摘要的芯片（复制摘要 / 记下来）
 * su -c "am broadcast -n $R --es summary set --es kv 'vl=0'"              # enabled / vl
 * </pre>
 */
final class SummaryTestDriver {

    private static final String TAG = "Summary";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private SummaryTestDriver() {}

    static void handle(final Context ctx, Intent intent) {
        final String cmd = intent.getStringExtra("summary");
        final String text = intent.getStringExtra("text");
        final String kv = intent.getStringExtra("kv");
        try {
            if ("judge".equals(cmd)) {
                boolean on = SummarySettings.enabled(ctx);
                TriageDecider.Decision d = TriageDecider.decide(text, TriageDecider.Signals.offline(), true, true, true, on);
                Log.i(TAG, "[SUMMARY-TEST] judge text=" + text + " hit=" + SummaryIntent.isTrigger(text) + " enabled=" + on
                        + " triage=" + d.label + " rule=" + d.rule + " loop=" + d.useAgentLoop);
            } else if ("set".equals(cmd) && kv != null && kv.contains("=")) {
                String[] p = kv.split("=", 2);
                SummarySettings.set(ctx, p[0].trim(), !"0".equals(p[1].trim()) && !"false".equalsIgnoreCase(p[1].trim()));
                Log.i(TAG, "[SUMMARY-TEST] set " + kv + " → " + SummarySettings.dump(ctx));
            } else if ("input".equals(cmd)) {
                new Thread(new Runnable() {
                    @Override public void run() {
                        ScreenContext sc = ScreenContextCapture.captureSync(ctx);
                        String why = SummaryGuard.screenReason(sc.texts, sc.redacted);
                        SummaryInput.Built in = SummaryInput.fromNodes(sc.nodes, 0, SummaryInput.BUDGET);
                        Log.i(TAG, "[SUMMARY-TEST] input app=" + sc.pkg + " source=" + sc.source + " nodes=" + sc.nodes.size()
                                + " redacted=" + sc.redacted + " sensitive=" + why + " " + in + " enough=" + in.enough()
                                + (sc.error != null ? " note=" + sc.error : ""));
                    }
                }, "SummaryTestInput").start();
            } else if ("run".equals(cmd) || "chip".equals(cmd)) {
                final boolean run = "run".equals(cmd);
                if (FloatingSheetService.getInstance() == null) FloatingSheetService.show(ctx, null);
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        FloatingSheetService svc = FloatingSheetService.getInstance();
                        if (svc == null) {
                            Log.w(TAG, "[SUMMARY-TEST] 面板服务不在");
                            return;
                        }
                        if (run) {
                            Log.i(TAG, "[SUMMARY-TEST] run started=" + SummaryHooks.debugRun(svc, text == null ? "总结一下" : text));
                        } else {
                            Log.i(TAG, "[SUMMARY-TEST] chip " + SummaryHooks.debugChip(svc, text == null ? SummaryHooks.CHIP_COPY : text));
                        }
                    }
                }, FloatingSheetService.getInstance() == null ? 1500 : 50);
            } else {
                Log.i(TAG, "[SUMMARY-TEST] status " + SummaryHooks.debugStatus(ctx));
            }
        } catch (Throwable t) {
            Log.e(TAG, "[SUMMARY-TEST] " + cmd + " 异常: " + t.getMessage(), t);
        }
    }
}
