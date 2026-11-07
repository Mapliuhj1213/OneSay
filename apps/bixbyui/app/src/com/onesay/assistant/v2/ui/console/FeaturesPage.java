package com.onesay.assistant.v2.ui.console;

import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureKernel;

/**
 * [console R12 → 控制台整理第二步 2026-11-05] 设置 › 功能：插件各画一行（右边开关、左边点进细项页）——日程、定时任务、待办、计划 App、
 * 定时任务与主动播报、晨间简报、连接器、浏览器跑腿、多设备执行端，以及以后新加的插件。
 * 原「功能与权限」拆开：权限 → 权限保活、屏幕与剪贴板 → 隐私、主动建议 → 主动建议、诊断 / 摘要 / 旧卡片 → 开发者（{@link ConsoleGroups}）。
 */
final class FeaturesPage extends Console.BuiltPage {

    FeaturesPage(Console c) {
        super(c, "功能");
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        LinearLayout feats = k.group(col, "功能");
        FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_FEATURES, feats, null, ConsoleGroups.FEATURES_EXCLUDE);
        dropIfEmpty(col, feats);
        k.footer(col, "每项点开有细项。关掉总开关，下面的细项一起停用。");
    }
}
