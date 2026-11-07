package com.onesay.assistant.v2.test;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.plug.leakguard.core.PromptLeakGuard;
import com.onesay.assistant.v2.leakguard.LeakGuardHost;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/**
 * ★ fix/leak-guard debug 直测（只在 debug 编译）。见 TestRunReceiver 里 --es leakguard / leakcheck / leakdemo 的说明。
 * 日志 tag：BixbyTestRun / LeakGuard，grep「LEAK-GUARD」。
 */
final class LeakGuardDemo {

    private static final String TAG = "BixbyTestRun";

    private LeakGuardDemo() {}

    static String sample(String kind) {
        String[] w = com.onesay.assistant.v2.asr.AsrHotwords.staticWords();
        if ("hotwords".equals(kind)) {
            StringBuilder sb = new StringBuilder(com.onesay.assistant.v2.asr.AsrHotwords.HOTWORD_PROMPT_PREFIX);
            for (int i = 0; i < 30 && i < w.length; i++) sb.append(i == 0 ? "" : "、").append(w[i]);
            return sb.toString();
        }
        if ("midlist".equals(kind)) {
            StringBuilder sb = new StringBuilder();
            for (int i = 8; i < 18 && i < w.length; i++) sb.append(i == 8 ? "" : "、").append(w[i]);
            return sb.toString();
        }
        if ("prompt".equals(kind)) {
            return "好的。你是智能手机语音助手的执行体（不是翻译器，也不是脚本播放器）。你可以调用工具来真正操作这台手机、或读取本机真实数据。";
        }
        return "微信、支付宝哪个好";
    }

    static void run(Context app, String sw, String check, String demo, String where) {
        PromptLeakGuard g = LeakGuardHost.ensure(app);
        if (sw != null) {
            if ("on".equalsIgnoreCase(sw) || "off".equalsIgnoreCase(sw)) LeakGuardHost.setEnabled(app, "on".equalsIgnoreCase(sw));
            Log.i(TAG, "[LEAK-GUARD-CHECK] 开关=" + (g.isEnabled() ? "开" : "关") + " 热词=" + g.hotwordCount()
                    + " 强指纹=" + g.strongFingerprintCount() + " 弱指纹=" + g.weakFingerprintCount()
                    + " 来源=" + g.promptSources() + " 命中累计=" + LeakGuardHost.hits());
        }
        if (check != null) {
            Log.i(TAG, "[LEAK-GUARD-CHECK] head=" + PromptLeakGuard.head20(check) + " len=" + check.length()
                    + " input=" + g.check(check) + " partial=" + g.checkPartial(check) + " output=" + g.checkOutput(check));
        }
        if (demo != null) {
            String text = sample(demo);
            String w = where == null ? "all" : where;
            Log.i(TAG, "[LEAK-GUARD-CHECK] demo=" + demo + " where=" + w + " head=" + PromptLeakGuard.head20(text)
                    + " input=" + g.check(text) + " output=" + g.checkOutput(text));
            FloatingSheetService fs = FloatingSheetService.getInstance();
            if ("asr".equals(w) || "all".equals(w)) {
                boolean ok = com.onesay.assistant.v2.wake.WakeService.debugLeakInject(text);
                Log.i(TAG, "[LEAK-GUARD-CHECK] asr 注入 " + (ok ? "已投递" : "失败（WakeService 未运行）"));
            }
            if (("reply".equals(w) || "all".equals(w)) && fs != null) fs.debugShowAskReply("泄漏测试", text);
            if (("stream".equals(w)) && fs != null) fs.debugAskStream("泄漏测试", text);
            if ("tts".equals(w) || "all".equals(w)) com.onesay.assistant.v2.ui.TtsSpeaker.get(app).speakProactive(text, 300);
        }
    }
}
