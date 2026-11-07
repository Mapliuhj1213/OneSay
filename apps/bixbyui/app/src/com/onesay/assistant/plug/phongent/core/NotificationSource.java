/*
 * [phongent 插件 · 宿主端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，
 * 宿主（OneSay 或别的 App）实现这些窄接口把执行端接进来。见 PHONGENT-PORT.md §插件规范。
 */
package com.onesay.assistant.plug.phongent.core;

import java.util.List;

/**
 * 通知来源。插件 start 时 attach、stop 时 detach；宿主只在 attach 期间往 sink 推，且<b>只推白名单 App</b>
 * （白名单归宿主管；不在白名单的连标题都不该读）。
 */
public interface NotificationSource {

    void attach(Sink sink);

    void detach();

    interface Sink {
        void post(Notice n);
    }

    /** 打开原通知（PendingIntent.send 之类）。 */
    interface Opener {
        void open() throws Exception;
    }

    final class Notice {
        public final String key, pkg, appName, title, shortcutId;
        /** 每条 [CharSequence/String 正文, Long 时间毫秒]。 */
        public final List<Object[]> entries;
        public final Opener opener;

        public Notice(String key, String pkg, String appName, String title, String shortcutId, List<Object[]> entries, Opener opener) {
            this.key = key;
            this.pkg = pkg;
            this.appName = appName;
            this.title = title;
            this.shortcutId = shortcutId;
            this.entries = entries;
            this.opener = opener;
        }
    }
}
