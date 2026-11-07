package com.onesay.assistant.v2.kernel;

import android.widget.LinearLayout;

/**
 * [kernel R12] 插件画控制台 v2 的常用组合（功能行 / 细项页），省掉每个插件一份样板。
 * kill-switch 文件存在时：行副标题显示 {@link ConsoleHost#KILLED}、开关点不动（文件名只在开发者页出现）。
 */
public final class ConsoleRows {

    private ConsoleRows() {}

    /** 一个布尔设置的读写。 */
    public interface Pref {
        boolean get();

        void set(boolean on);
    }

    private static ConsoleHost.Toggle toggle(final Pref p) {
        return new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                p.set(on);
            }
        };
    }

    /** 功能行：左边点进细项页（pageId 为 null = 没有细项，只是开关行），右边开关。 */
    public static ConsoleHost.Row featureRow(final ConsoleHost host, LinearLayout group, String title, String sub, boolean killed,
                                             Pref p, final String pageId, final String pageTitle) {
        String s = killed ? ConsoleHost.KILLED : sub;
        ConsoleHost.Row r = pageId == null ? host.switchRow(group, title, s, p.get(), toggle(p))
                : host.switchLinkRow(group, title, s, p.get(), toggle(p), new Runnable() {
                    @Override public void run() {
                        host.openPage(pageId, pageTitle);
                    }
                });
        if (killed) r.setEnabled(false);
        return r;
    }

    /** 细项页：主开关条 + 「细项」分组（细项跟主开关联动变灰）+ 页脚。 */
    public static void detailPage(ConsoleHost host, LinearLayout root, String mainTitle, final boolean killed, final Pref main,
                                  String[] titles, String[] subs, Pref[] prefs, boolean[] subKilled, String footer) {
        final ConsoleHost.Row[] rows = new ConsoleHost.Row[titles.length];
        final boolean[] sk = subKilled != null ? subKilled : new boolean[titles.length];
        ConsoleHost.Row m = host.mainSwitch(root, mainTitle, main.get(), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                main.set(on);
                for (int i = 0; i < rows.length; i++) if (rows[i] != null) rows[i].setEnabled(on && !killed && !sk[i]);
            }
        });
        if (titles.length > 0) {
            LinearLayout g = host.group(root, "细项");
            for (int i = 0; i < titles.length; i++) {
                rows[i] = host.switchRow(g, titles[i], sk[i] ? ConsoleHost.KILLED : subs[i], prefs[i].get(), toggle(prefs[i]));
                rows[i].setEnabled(m.isOn() && !killed && !sk[i]);
            }
        }
        if (killed) {
            m.setEnabled(false);
            host.footer(root, ConsoleHost.KILLED);
        }
        if (footer != null) host.footer(root, footer);
    }
}
