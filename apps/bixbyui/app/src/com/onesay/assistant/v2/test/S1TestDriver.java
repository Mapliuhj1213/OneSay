package com.onesay.assistant.v2.test;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.onesay.assistant.v2.s1.S1Hooks;
import com.onesay.assistant.v2.s1.S1Settings;

/**
 * [s1] 真机验收用的调试驱动（<b>只在 debug 包</b>：release 构建剔除整个 test 包；TestRunReceiver 受签名级权限保护，adb 需 su）。
 *
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es s1 status"
 * su -c "am broadcast -n $R --es s1 sms --es text '【菜鸟驿站】…取件码5-2-1234…'"   # 注入一条"收到的短信"（真机无法伪造短信广播）
 * su -c "am broadcast -n $R --es s1 setclip --es text '广州市天河区…'"           # 以"外部来源"写系统剪贴板 → 走真实监听回调
 * su -c "am broadcast -n $R --es s1 clip --es text '…'"                         # 只跑裁决与芯片（不经系统剪贴板）
 * su -c "am broadcast -n $R --es s1 circle [--es text '这是什么']"                # 打开圈选遮罩
 * su -c "am broadcast -n $R --es s1 collapse"                                     # 把在跑的长任务收成胶囊（等同点面板外）
 * su -c "am broadcast -n $R --es s1 clearpickup"                                  # 清空取件码卡片
 * su -c "am broadcast -n $R --es s1 set --es kv 'capsule=0'"                      # 改开关（capsule/auto_collapse/pickup_card/circle/clip_chip/clip_deixis）
 * </pre>
 * 日志 tag：{@code S1TestDriver}（结果）、{@code S1}（功能本身）。
 */
final class S1TestDriver {

    private static final String TAG = "S1TestDriver";

    private S1TestDriver() {}

    static void handle(final Context ctx, Intent intent) {
        final String cmd = intent.getStringExtra("s1");
        final String text = intent.getStringExtra("text");
        final String kv = intent.getStringExtra("kv");
        new Handler(Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                try {
                    dispatch(ctx, cmd == null ? "status" : cmd.trim(), text, kv);
                } catch (Throwable t) {
                    Log.e(TAG, "[S1-TEST] " + cmd + " 异常: " + t.getMessage(), t);
                }
            }
        });
    }

    private static void dispatch(Context ctx, String cmd, String text, String kv) {
        if ("status".equals(cmd)) {
            Log.i(TAG, "[S1-TEST] status " + S1Hooks.debugStatus(ctx));
        } else if ("sms".equals(cmd)) {
            int n = S1Hooks.debugInjectSms(text);
            Log.i(TAG, "[S1-TEST] sms 注入完成 newCards=" + n + "（-1 = 服务未初始化）");
        } else if ("clip".equals(cmd)) {
            Log.i(TAG, "[S1-TEST] clip 裁决 " + S1Hooks.debugClip(text));
        } else if ("setclip".equals(cmd)) {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            // 刻意不登记 markOwnWrite：模拟"别的 App 复制"，走真实的剪贴板监听回调
            cm.setPrimaryClip(ClipData.newPlainText("s1-test", text == null ? "" : text));
            Log.i(TAG, "[S1-TEST] setclip 已写入系统剪贴板 len=" + (text == null ? 0 : text.length()));
        } else if ("circle".equals(cmd)) {
            Log.i(TAG, "[S1-TEST] circle started=" + S1Hooks.debugCircle(text));
        } else if ("collapse".equals(cmd)) {
            Log.i(TAG, "[S1-TEST] collapse → " + S1Hooks.debugCollapse());
        } else if ("clearpickup".equals(cmd)) {
            S1Hooks.debugClearPickup();
            Log.i(TAG, "[S1-TEST] clearpickup 完成");
        } else if ("set".equals(cmd) && kv != null && kv.contains("=")) {
            String[] p = kv.split("=", 2);
            boolean on = "1".equals(p[1].trim()) || "true".equalsIgnoreCase(p[1].trim());
            S1Settings.set(ctx, p[0].trim(), on);
            Log.i(TAG, "[S1-TEST] set " + p[0].trim() + "=" + on + " → " + S1Settings.dump(ctx));
        } else {
            Log.w(TAG, "[S1-TEST] 未知子命令: " + cmd);
        }
    }
}
