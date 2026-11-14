package com.onesay.assistant.v2.ui.console;

import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.kernel.FeatureKernel;

/**
 * ★ console-align 2026-11-12：最上面两行「灵动岛」「主动建议」（原在首页设置区）；连接类（电脑联动 / 多设备 / 连接器）搬到「连接」，
 * 替你操作类（替你操作 App / 接口优先 / 浏览器跑腿）搬到「替你操作」（{@link ConsoleGroups#FEATURES_EXCLUDE}）。
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
        // ★ console-align：原首页「灵动岛」「主动建议」两行搬到这里最上面（页面本身不变）
        LinearLayout top = k.group(col, null);
        k.linkRow(top, "灵动岛", ConsoleGroups.defaultIslandSub(), 0, new Runnable() {
            @Override public void run() {
                c.openPage(ConsoleHost.PAGE_ISLAND, "灵动岛");
            }
        });
        k.linkRow(top, "主动建议", "总开关 · 场景 · 截图权限", 0, new Runnable() {
            @Override public void run() {
                c.push(new GroupPages.SuggestPage(c));
            }
        });
        LinearLayout feats = k.group(col, "功能");
        FeatureKernel.get().addConsolePage(c, ConsoleHost.PAGE_FEATURES, feats, null, ConsoleGroups.FEATURES_EXCLUDE);
        dropIfEmpty(col, feats);
        k.footer(col, "每项点开有细项。关掉总开关，下面的细项一起停用。");
    }
}
