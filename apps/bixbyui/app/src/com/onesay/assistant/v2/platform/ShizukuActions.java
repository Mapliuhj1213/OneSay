package com.onesay.assistant.v2.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * 「Shizuku 特权」插件的一次性授权动作（纯 Java 拼命令，JVM 可测；执行走 {@code ShellExecutor} → {@link ExecTierRouter} → 白名单）。
 * <ul>
 *   <li>后台读剪贴板（电脑联动同步剪贴板）：{@code appops set <本包> READ_CLIPBOARD allow}；</li>
 *   <li>通知使用权（Android 13+ 侧载应用要先放开「受限设置」）：{@code appops set <本包> ACCESS_RESTRICTED_SETTINGS allow}
 *       + {@code cmd notification allow_listener <本包>/<监听服务>}。</li>
 * </ul>
 */
public final class ShizukuActions {
    private ShizukuActions() {}

    public static final String LISTENER_CLASS = "com.onesay.assistant.v2.events.EventNotificationListener";

    public static List<String> clipboardGrant(String pkg) {
        List<String> l = new ArrayList<String>();
        l.add("appops set " + pkg + " READ_CLIPBOARD allow");
        return l;
    }

    public static List<String> notificationAccess(String pkg, int sdk) {
        List<String> l = new ArrayList<String>();
        if (sdk >= 33) l.add("appops set " + pkg + " ACCESS_RESTRICTED_SETTINGS allow");
        l.add("cmd notification allow_listener " + pkg + "/" + LISTENER_CLASS);
        return l;
    }
}
