package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onesay.assistant.v2.imgsave.ImageSaveHooks;
import com.onesay.assistant.v2.imgsave.ImageSaveIntent;
import com.onesay.assistant.v2.imgsave.ImageSaveSettings;
import com.onesay.assistant.v2.router.TriageDecider;
import com.onesay.assistant.v2.ui.FloatingSheetService;

/**
 * [imgsave] 语音存图调试入口（仅 debug 包，经 TestRunReceiver）。
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es imgsave status"                          # 开关 / kill-switch / 待选数 / 最近一次结果
 * su -c "am broadcast -n $R --es imgsave judge --es text '存这张图'"        # 只判定：意图 + 分诊（不截图、不存）
 * su -c "am broadcast -n $R --es imgsave run --es text '保存图片'"          # 等同在面板说这句话（只走存图流程）
 * su -c "am broadcast -n $R --es imgsave select --es text '第二张'"         # 多图待选时的回答
 * su -c "am broadcast -n $R --es imgsave set --es kv 'enabled=0'"         # enabled / cache
 * </pre>
 */
final class ImageSaveTestDriver {

    private static final String TAG = "ImageSave";
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ImageSaveTestDriver() {}

    static void handle(final Context ctx, Intent intent) {
        final String cmd = intent.getStringExtra("imgsave");
        final String text = intent.getStringExtra("text");
        final String kv = intent.getStringExtra("kv");
        try {
            if ("judge".equals(cmd)) {
                ImageSaveIntent.Hit h = ImageSaveIntent.match(text);
                boolean on = ImageSaveSettings.enabled(ctx);
                TriageDecider.Decision d = TriageDecider.decide(text, TriageDecider.Signals.offline(), true, true, on);
                Log.i(TAG, "[IMGSAVE-TEST] judge text=" + text + " hit=" + h + " enabled=" + on + " triage=" + d.label + " rule=" + d.rule
                        + " loop=" + d.useAgentLoop + " sel3=" + ImageSaveIntent.parseSelection(text, 3));
            } else if ("set".equals(cmd) && kv != null && kv.contains("=")) {
                String[] p = kv.split("=", 2);
                ImageSaveSettings.set(ctx, p[0].trim(), !"0".equals(p[1].trim()) && !"false".equalsIgnoreCase(p[1].trim()));
                Log.i(TAG, "[IMGSAVE-TEST] set " + kv + " → " + ImageSaveSettings.dump(ctx));
            } else if ("run".equals(cmd) || "select".equals(cmd)) {
                final boolean run = "run".equals(cmd);
                if (FloatingSheetService.getInstance() == null) FloatingSheetService.show(ctx, null);
                MAIN.postDelayed(new Runnable() {
                    @Override public void run() {
                        FloatingSheetService svc = FloatingSheetService.getInstance();
                        if (svc == null) {
                            Log.w(TAG, "[IMGSAVE-TEST] 面板服务不在");
                            return;
                        }
                        if (run) {
                            boolean ok = ImageSaveHooks.debugRun(svc, text == null ? "保存图片" : text);
                            Log.i(TAG, "[IMGSAVE-TEST] run text=" + text + " started=" + ok);
                        } else {
                            Log.i(TAG, "[IMGSAVE-TEST] select text=" + text + " → " + ImageSaveHooks.debugSelect(svc, text));
                        }
                    }
                }, FloatingSheetService.getInstance() == null ? 1500 : 50);
            } else {
                Log.i(TAG, "[IMGSAVE-TEST] status " + ImageSaveHooks.debugStatus(ctx));
            }
        } catch (Throwable t) {
            Log.e(TAG, "[IMGSAVE-TEST] " + cmd + " 异常: " + t.getMessage(), t);
        }
    }
}
