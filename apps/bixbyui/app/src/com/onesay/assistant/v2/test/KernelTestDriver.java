package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.plugins.HelloPlugin;

/**
 * [kernel] 插件内核调试入口（仅 debug 包，经 TestRunReceiver；adb 需 su）。
 *
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es kernel status"                 # 每个插件一行：状态 / 验收模块 / 日志 tag / 插件自报状态
 * su -c "am broadcast -n $R --es kernel order"                  # 每个挂钩上的插件调用顺序
 * su -c "am broadcast -n $R --es kernel set --es kv 'hello=1'"  # 打开 / 关闭示例插件（hello=0 关）
 * </pre>
 * 日志 tag：Kernel，行前缀 [KERNEL-TEST]。
 */
final class KernelTestDriver {

    private static final String TAG = "Kernel";

    private KernelTestDriver() {}

    static void handle(Context ctx, Intent intent) {
        String cmd = intent.getStringExtra("kernel");
        String kv = intent.getStringExtra("kv");
        FeatureKernel k = FeatureKernel.get();
        try {
            if ("order".equals(cmd)) {
                for (FeatureHook h : FeatureHook.values()) {
                    Log.i(TAG, "[KERNEL-TEST] order " + h + " = " + k.orderIds(h));
                }
            } else if ("set".equals(cmd) && kv != null && kv.startsWith("hello=")) {
                boolean on = !"0".equals(kv.substring(6).trim()) && !"false".equalsIgnoreCase(kv.substring(6).trim());
                HelloPlugin.SWITCH.set(ctx, on);
                k.refresh(ctx);
                Log.i(TAG, "[KERNEL-TEST] set " + kv + " → hello " + k.state("hello"));
            } else {
                k.refresh(ctx);
                for (String line : k.dump(ctx).split("\n")) {
                    if (line.length() > 0) Log.i(TAG, "[KERNEL-TEST] status " + line);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[KERNEL-TEST] " + cmd + " 异常: " + t);
        }
    }
}
