package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;

/**
 * [kernel] 插件调试入口分发（仅 debug 包；审计 §8 第 3 阶段）。
 *
 * <p>插件在 {@link FeaturePlugin#debugExtra()} 里声明自己的 {@code --es <名>}；驱动类（XxxTestDriver）在 test 包、
 * release 不编译，所以名字 → 驱动的映射写在这里。加一个插件的调试入口 = 插件声明 debugExtra + 这里加一个 case
 * （JVM 测试 KernelPhase3Test 校验两边一致）。命令参数与各驱动原样相同。
 */
final class PluginDebugDrivers {

    private static final String TAG = "TestRunReceiver";

    private PluginDebugDrivers() {}

    /** 按内核注册顺序找第一个声明了、且 intent 里带着对应 extra 的插件；处理了返回 true。 */
    static boolean dispatch(Context ctx, Intent intent) {
        for (FeaturePlugin p : FeatureKernel.get().plugins()) {
            String extra = p.debugExtra();
            if (extra == null || !intent.hasExtra(extra)) continue;
            if (run(extra, ctx, intent)) return true;
            Log.w(TAG, "[KERNEL-TEST] 插件 " + p.id() + " 声明了 --es " + extra + " 但没有对应驱动");
        }
        return false;
    }

    private static boolean run(String extra, Context ctx, Intent intent) {
        if ("s1".equals(extra)) { S1TestDriver.handle(ctx, intent); return true; }
        if ("island".equals(extra)) { IslandTestDriver.handle(ctx, intent); return true; }
        if ("s3".equals(extra)) { S3TestDriver.handle(ctx, intent); return true; }
        if ("s2".equals(extra)) { S2TestDriver.handle(ctx, intent); return true; }
        if ("diag".equals(extra)) { DiagTestDriver.handle(ctx, intent); return true; }
        if ("roles".equals(extra)) { RolesTestDriver.handle(ctx, intent); return true; }
        if ("imgsave".equals(extra)) { ImageSaveTestDriver.handle(ctx, intent); return true; }
        if ("summary".equals(extra)) { SummaryTestDriver.handle(ctx, intent); return true; }
        if ("quicknote".equals(extra)) { QuickNoteTestDriver.handle(ctx, intent); return true; }
        if ("conn".equals(extra)) { ConnTestDriver.handle(ctx, intent); return true; }   // 2026-10-22 B 线：连接器
        if ("notice".equals(extra)) { NoticeTestDriver.handle(ctx, intent); return true; }   // ★ 灵动岛消息提醒注入
        if ("event".equals(extra)) { EventTestDriver.handle(ctx, intent); return true; }   // 2026-10-23 事件任务（充电）注入：防抖 / 每天上限 / 重启
        return false;
    }
}
