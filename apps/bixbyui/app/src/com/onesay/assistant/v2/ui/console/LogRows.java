package com.onesay.assistant.v2.ui.console;

import android.widget.LinearLayout;

import com.onesay.assistant.v2.applog.AppLog;
import com.onesay.assistant.v2.applog.AppLogAndroid;
import com.onesay.assistant.v2.applog.AppLogExport;

/**
 * [settings-diag-1124] 「高级 › 日志」两行：导出日志（打包 → 系统分享）、清空日志（先确认）。
 */
final class LogRows {

    private LogRows() {}

    static final String EXPORT_TITLE = "导出日志";
    static final String EXPORT_SUB = "出问题时用：把最近的运行日志打成一个压缩包，用微信 / 邮件等发给开发者。不含聊天原文、Key 和个人信息";
    static final String CLEAR_TITLE = "清空日志";

    static void add(final Console c, LinearLayout g) {
        final ConsoleKit k = c.kit;
        AppLogAndroid.init(c.act);
        final ConsoleKit.RowImpl size = k.actionRow(g, EXPORT_TITLE, EXPORT_SUB + "\n现在有 " + AppLog.sizeText(AppLog.totalBytes()), "导出", new Runnable() {
            @Override public void run() {
                export(c);
            }
        });
        k.actionRow(g, CLEAR_TITLE, "删掉手机上存的全部运行日志", "清空", new Runnable() {
            @Override public void run() {
                k.confirm("清空日志？", "手机上存的运行日志会全部删掉，删了就找不回来。出问题前先导出一份更好。", "清空", new Runnable() {
                    @Override public void run() {
                        AppLogAndroid.clear(c.act);
                        c.toast("日志已清空");
                        size.setSub(EXPORT_SUB + "\n现在有 " + AppLog.sizeText(AppLog.totalBytes()));
                    }
                });
            }
        });
    }

    static void export(final Console c) {
        c.toast("正在打包日志…");
        com.onesay.assistant.v2.util.Threads.start("applog-export", new Runnable() {
            @Override public void run() {
                AppLogExport.Result r = null;
                try {
                    r = AppLogAndroid.export(c.act);
                } catch (Throwable t) {
                    r = null;
                }
                final AppLogExport.Result res = r;
                c.ui(new Runnable() {
                    @Override public void run() {
                        if (res == null) {
                            c.toast("没打包成功：手机存储可能满了");
                            return;
                        }
                        if (!AppLogAndroid.share(c.act, res.file)) c.toast("打包好了，但没找到能分享的 App");
                    }
                });
            }
        });
    }
}
