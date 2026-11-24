package com.onesay.assistant.v2.ui.console;

import com.onesay.assistant.v2.selfset.SelfCatalog;

import java.util.List;

/**
 * [settings-diag-1124] 「唤起方式」页每个入口开关下面那行字（纯 Java，JVM 可测）：说明 + 真实状态。
 * 不写系统接口名、权限名、能力代号（以前会出现「缺少：MIC、ARM64」「需要 Android API 24 及以上」）。
 */
final class EntryRows {

    private EntryRows() {}

    static String sub(String id, String desc, boolean on, List<SelfCatalog.Need> missing, boolean blockedForever) {
        StringBuilder b = new StringBuilder(desc == null ? "" : desc);
        if (missing == null || missing.isEmpty()) {
            if (on && "entry.assistant".equals(id)) b.append("\n已是默认助理，开着就能用");
            return b.toString();
        }
        StringBuilder names = new StringBuilder();
        for (SelfCatalog.Need n : missing) {
            if (names.length() > 0) names.append("、");
            names.append(n.title);
        }
        if (blockedForever) {
            b.append("\n这台手机用不了：缺 ").append(names);
        } else if (on) {
            b.append("\n开关开着，但还差一步：").append(names).append("（点下面一行去开）");
        } else {
            b.append("\n打开前还要：").append(names);
        }
        return b.toString();
    }
}
