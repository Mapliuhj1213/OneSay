package com.onesay.assistant.v2.test;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.onesay.assistant.v2.agent.Outcome;
import com.onesay.assistant.v2.s1.S1Hooks;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.TtsSpeaker;

/**
 * ★ arch/voice-sync debug 直测：不调大模型，按 FloatingSheetService.runAgentLoop 的挂钩顺序模拟一个前台任务
 * （S1Hooks.onTaskStart → onTaskStep / onSubgoals / beforeGuiAction / onAskUser → agentOutcomeVoice + showAskReply → onTaskEnded），
 * 用来在真机上看「先听到应答再开始点」「单步不应答」「提问提示 + 芯片」「8 s 只催一次」「结局语音与屏幕一致」。只在 debug 包里。
 */
final class VoiceSyncDemo {

    private static final String TAG = "VSDEMO";

    private VoiceSyncDemo() {}

    static void run(final Context app, final String mode0, final String outcome0) {
        final String mode = mode0 == null ? "multi" : mode0;
        final String oc = outcome0 == null ? ("multi".equals(mode) || "many".equals(mode) ? "plan" : "done") : outcome0;
        final FloatingSheetService fs = FloatingSheetService.getInstance();
        if (fs == null) {
            Log.w(TAG, "[VSDEMO] 面板服务没起来");
            return;
        }
        final Handler main = new Handler(Looper.getMainLooper());
        final boolean islandNow = "multi".equals(mode) || "many".equals(mode) || "nudge".equals(mode);
        final String cmd = "many".equals(mode) ? "关掉WiFi，打开飞行模式，再把亮度调低" : islandNow ? "打开蓝牙然后把音量调到一半" : "打开蓝牙";
        TtsSpeaker.setVoiceTurn(true);
        final long t0 = SystemClock.elapsedRealtime();
        main.post(new Runnable() {
            @Override public void run() {
                Log.i(TAG, "[VSDEMO] t=0 任务开始 mode=" + mode + " islandNow=" + islandNow + " cmd=" + cmd);
                S1Hooks.onTaskStart(fs, cmd, islandNow);
            }
        });
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    Thread.sleep(300);   // 模拟 LLM 规划（与应答并行）
                    S1Hooks.onTaskStep("正在：打开蓝牙");
                    if ("gui".equals(mode)) {
                        Log.i(TAG, "[VSDEMO] t=" + (SystemClock.elapsedRealtime() - t0) + " 要点界面了（phone.operate 之前）");
                        long w = S1Hooks.beforeGuiAction();
                        Log.i(TAG, "[VSDEMO] t=" + (SystemClock.elapsedRealtime() - t0) + " 第一个 GUI 动作开始（门控等了 " + w + "ms）");
                        Thread.sleep(1500);
                    } else if ("ask".equals(mode)) {
                        java.util.List<String> opts = java.util.Arrays.asList("黑色", "白色", "蓝色");
                        boolean onIsland = S1Hooks.onAskUser("vsdemo-ask", "要哪个颜色？", opts, 20);
                        Log.i(TAG, "[VSDEMO] t=" + (SystemClock.elapsedRealtime() - t0) + " ask_user 岛=" + onIsland);
                        String a = S1Hooks.awaitAskUser("vsdemo-ask", 6000);
                        if (a == null) S1Hooks.cancelAskUser("vsdemo-ask");
                        Log.i(TAG, "[VSDEMO] t=" + (SystemClock.elapsedRealtime() - t0) + " ask_user 回答=" + a);
                    } else if (islandNow) {
                        S1Hooks.onSubgoals(subgoals("many".equals(mode) ? 3 : 2));
                        Thread.sleep(400);
                        S1Hooks.onTaskStep("正在：调音量");
                    }
                    if ("nudge".equals(mode)) Thread.sleep(11000);
                    else if (!"single".equals(mode)) Thread.sleep(1200);
                } catch (InterruptedException ignored) {
                }
                main.post(new Runnable() {
                    @Override public void run() {
                        finish(app, fs, mode, oc, t0);
                    }
                });
            }
        }, "vsdemo").start();
    }

    private static java.util.List<com.onesay.assistant.v2.island.Subgoal> subgoals(int n) {
        java.util.List<com.onesay.assistant.v2.island.Subgoal> l = new java.util.ArrayList<com.onesay.assistant.v2.island.Subgoal>();
        for (int i = 0; i < n; i++) {
            l.add(new com.onesay.assistant.v2.island.Subgoal("g" + (i + 1), "第" + (i + 1) + "项",
                    i == 0 ? com.onesay.assistant.v2.island.Subgoal.State.DOING : com.onesay.assistant.v2.island.Subgoal.State.PENDING));
        }
        return l;
    }

    private static void finish(Context app, FloatingSheetService fs, String mode, String oc, long t0) {
        boolean nul = "null".equals(oc);
        Outcome o = Outcome.DONE;
        String r = null;
        int sg = "plan".equals(oc) ? 2 : 0;
        if ("login".equals(oc)) { o = Outcome.NEEDS_USER; r = Outcome.Reason.LOGIN; }
        else if ("noanswer".equals(oc)) { o = Outcome.NEEDS_USER; r = Outcome.Reason.NO_ANSWER; }
        else if ("budget".equals(oc)) { o = Outcome.NOT_DONE; r = Outcome.Reason.BUDGET; }
        else if ("policy".equals(oc)) { o = Outcome.BLOCKED; r = Outcome.Reason.POLICY; }
        else if ("unverified".equals(oc)) {
            S1Hooks.onActionResult(null, com.onesay.assistant.v2.executor.ExecutionResult.success(null, null, "已执行", false, ""), false);
        }
        com.onesay.assistant.v2.tts.VoiceTemplate spoken = S1Hooks.agentOutcomeVoice(app, nul, o, r, sg);
        String detail = o == Outcome.DONE ? "共 2 步" : com.onesay.assistant.v2.agent.OutcomeText.line(o, r, "演示");
        String sub = com.onesay.assistant.v2.tts.VoiceSyncPolicy.statusSub(spoken, detail);
        Log.i(TAG, "[VSDEMO] t=" + (SystemClock.elapsedRealtime() - t0) + " 结局 " + o + "/" + r + " 播「" + spoken.text + "」 屏幕状态行「"
                + com.onesay.assistant.v2.agent.OutcomeText.title(o) + " | " + sub + "」");
        S1Hooks.onTaskOutcome(o == Outcome.DONE && !nul, "演示结论：" + spoken.text, sub);
        fs.debugShowAskReplyNow("任务结果", "演示结论（" + mode + "）", com.onesay.assistant.v2.agent.OutcomeText.title(o), sub);
        TtsSpeaker.clearReplyOverride();
        S1Hooks.onTaskEnded(fs, false, false);
    }
}
