package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin;
import com.onesay.assistant.v2.quicknote.QuickNoteBook;
import com.onesay.assistant.v2.quicknote.QuickNoteClassifier;
import com.onesay.assistant.v2.quicknote.QuickNoteHooks;
import com.onesay.assistant.v2.quicknote.QuickNoteIntent;
import com.onesay.assistant.v2.util.Threads;
import com.onesay.assistant.v2.vision.screenref.EntityExtractor;
import com.onesay.assistant.v2.wake.KeyTrigger;
import com.onesay.assistant.v2.wake.WakeService;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/**
 * [quicknote] 调试入口（仅 debug 包）：{@code am broadcast -n com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver --es quicknote <cmd> [--es text …]}。
 * 日志 tag QuickNoteTestDriver，前缀 [QUICKNOTE-TEST]。
 *
 * <ul>
 *   <li>status / on / off：开关（off 同时删掉手势标记）；flag：同步并报告 files/bixby.gesture_v2；</li>
 *   <li>classify：text 用 {@code |} 分行 → 本地实体抽取 + 分类（不存、不读屏）；</li>
 *   <li>intent：text → QuickNoteIntent；parse：text → KeyTrigger；</li>
 *   <li>run：真读当前屏幕并存（与语音「记一记」同一路）；gesture：模拟守护 v7 的长按（走 FeatureKernel.onKeyGesture）；</li>
 *   <li>recall / list / dellast：同语音回复；clear：删掉全部记一记条目（只删 relation=quicknote）。</li>
 * </ul>
 */
final class QuickNoteTestDriver {

    private static final String TAG = "QuickNoteTestDriver";

    private QuickNoteTestDriver() {}

    static void handle(final Context ctx, Intent intent) {
        final String cmd = intent.getStringExtra("quicknote");
        final String text = intent.getStringExtra("text");
        Threads.start("QuickNoteTestDriver", new Runnable() {
            @Override public void run() {
                try {
                    dispatch(ctx.getApplicationContext(), cmd == null ? "status" : cmd.trim(), text);
                } catch (Throwable t) {
                    Log.e(TAG, "[QUICKNOTE-TEST] " + cmd + " 异常: " + t.getMessage(), t);
                }
            }
        });
    }

    private static void dispatch(Context ctx, String cmd, String text) {
        long now = System.currentTimeMillis();
        if ("status".equals(cmd)) {
            Log.i(TAG, "[QUICKNOTE-TEST] status " + FeatureKernel.get().find("quicknote").debugStatus(ctx)
                    + " flag=" + flagFile(ctx).exists() + " count=" + QuickNoteHooks.book(ctx).list().size());
        } else if ("on".equals(cmd) || "off".equals(cmd)) {
            QuickNotePlugin.SWITCH.set(ctx, "on".equals(cmd));
            WakeService.syncGestureFlag(ctx);
            Log.i(TAG, "[QUICKNOTE-TEST] " + cmd + " → enabled=" + QuickNotePlugin.SWITCH.on(ctx) + " flag=" + flagFile(ctx).exists());
        } else if ("reset".equals(cmd)) {
            // 删掉 prefs 里的显式值 → 回到默认（现在默认开）
            ctx.getSharedPreferences(QuickNotePlugin.SWITCH.prefs, Context.MODE_PRIVATE).edit().remove(QuickNotePlugin.SWITCH.key).apply();
            WakeService.syncGestureFlag(ctx);
            Log.i(TAG, "[QUICKNOTE-TEST] reset → enabled=" + QuickNotePlugin.SWITCH.on(ctx) + " flag=" + flagFile(ctx).exists());
        } else if ("flag".equals(cmd)) {
            WakeService.syncGestureFlag(ctx);
            Log.i(TAG, "[QUICKNOTE-TEST] flag enabled=" + QuickNotePlugin.SWITCH.on(ctx) + " flag=" + flagFile(ctx).exists());
        } else if ("classify".equals(cmd)) {
            List<String> lines = new ArrayList<String>(Arrays.asList((text == null ? "" : text).split("\\|")));
            QuickNoteClassifier.Note n = QuickNoteClassifier.classify("debug",
                    lines, EntityExtractor.extractFromTexts(lines, now, TimeZone.getDefault()));
            Log.i(TAG, "[QUICKNOTE-TEST] classify " + n.category + " title=" + n.title
                    + (n.saved() ? "" : " refused=" + n.reason));
        } else if ("intent".equals(cmd)) {
            Log.i(TAG, "[QUICKNOTE-TEST] intent " + QuickNoteIntent.of(text) + " ← " + text);
        } else if ("parse".equals(cmd)) {
            Log.i(TAG, "[QUICKNOTE-TEST] parse " + KeyTrigger.parse(text) + " ← " + text);
        } else if ("run".equals(cmd)) {
            QuickNoteClassifier.Note n = QuickNoteHooks.captureAndSave(ctx, "debug");
            Log.i(TAG, "[QUICKNOTE-TEST] run " + n.category + " saved=" + n.saved() + " title=" + n.title
                    + (n.saved() ? "" : " refused=" + n.reason));
        } else if ("gesture".equals(cmd)) {
            boolean handled = FeatureKernel.get().onKeyGesture(ctx, text == null || text.length() == 0 ? "long" : text);
            Log.i(TAG, "[QUICKNOTE-TEST] gesture handled=" + handled);
        } else if ("recall".equals(cmd)) {
            Log.i(TAG, "[QUICKNOTE-TEST] recall " + QuickNoteHooks.book(ctx).recallReply(now).replace('\n', ' '));
        } else if ("list".equals(cmd)) {
            Log.i(TAG, "[QUICKNOTE-TEST] list " + QuickNoteHooks.book(ctx).listReply(now, 5).replace('\n', ' '));
        } else if ("dellast".equals(cmd)) {
            Log.i(TAG, "[QUICKNOTE-TEST] dellast " + QuickNoteHooks.book(ctx).deleteLastReply());
        } else if ("clear".equals(cmd)) {
            int n = QuickNoteHooks.clearAll(ctx);
            Log.i(TAG, "[QUICKNOTE-TEST] clear removed=" + n);
        } else if ("dump".equals(cmd)) {
            // R10：列出最近 8 条（含码状态 / 记账 / 缩略图）+ 最近删除条数
            QuickNoteHooks.housekeep(ctx);
            QuickNoteBook b = QuickNoteHooks.book(ctx);
            StringBuilder sb = new StringBuilder();
            int i = 0;
            for (com.onesay.assistant.v2.quicknote.QuickNoteEntry e : b.list()) {
                if (i++ >= 8) break;
                sb.append(" | #").append(e.id).append(' ').append(e.source).append(' ').append(e.category).append(' ').append(e.title)
                        .append(" code=").append(e.codeState).append(" ledger=").append(e.ledgerId).append(" thumb=").append(e.thumbPath.length() > 0)
                        .append(" app=").append(e.appLabel).append(" tags=").append(e.tags);
            }
            Log.i(TAG, "[QUICKNOTE-TEST] dump count=" + b.list().size() + " deleted=" + b.deletedList().size() + " "
                    + com.onesay.assistant.v2.quicknote.QuickNoteSettings.dump(ctx) + " v8seen=" + WakeService.daemonV8Seen() + sb);
        } else if ("ask".equals(cmd)) {
            // R10：一句话走记一记插件的 route（与语音同一路），打印回复
            com.onesay.assistant.v2.router.Invocation inv = FeatureKernel.get().find("quicknote").route(ctx, text == null ? "" : text, null);
            Log.i(TAG, "[QUICKNOTE-TEST] ask kind=" + QuickNoteIntent.of(text) + " → "
                    + (inv == null ? "null（不接管）" : inv.args.optString("text", "").replace('\n', ' ')) + " ← " + text);
        } else if ("voice".equals(cmd)) {
            com.onesay.assistant.v2.quicknote.QuickNoteEntry e = QuickNoteHooks.saveVoice(ctx, text, "debug");
            Log.i(TAG, "[QUICKNOTE-TEST] voice " + (e == null ? "refused" : e.toString()));
        } else if ("set".equals(cmd)) {
            // text = "thumbs=1" / "vl=0" / "ledger=1" / "hold=1"
            String[] kv = (text == null ? "" : text).split("=", 2);
            if (kv.length == 2) com.onesay.assistant.v2.quicknote.QuickNoteSettings.set(ctx, kv[0].trim(), "1".equals(kv[1].trim()) || "true".equals(kv[1].trim()));
            Log.i(TAG, "[QUICKNOTE-TEST] set " + com.onesay.assistant.v2.quicknote.QuickNoteSettings.dump(ctx));
        } else if ("page".equals(cmd)) {
            QuickNoteHooks.openDetail(ctx, text == null || text.length() == 0 ? -1 : Long.parseLong(text.trim()));
            Log.i(TAG, "[QUICKNOTE-TEST] page opened id=" + text);
        } else if ("hold".equals(cmd)) {
            boolean handled = FeatureKernel.get().onKeyGesture(ctx, "hold");
            Log.i(TAG, "[QUICKNOTE-TEST] hold handled=" + handled);
        } else {
            Log.w(TAG, "[QUICKNOTE-TEST] 未知子命令: " + cmd);
        }
    }

    private static File flagFile(Context ctx) {
        return new File(ctx.getFilesDir(), KeyTrigger.GESTURE_FLAG);
    }
}
