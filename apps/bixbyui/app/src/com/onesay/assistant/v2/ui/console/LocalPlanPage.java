package com.onesay.assistant.v2.ui.console;

import android.widget.LinearLayout;

/**
 * [console 计划区] 本机计划页：没装「OneSay Plan」（或计划开关关着）时，首页「打开计划」/「今天」回落到这里。
 * 一页列出 OneSay 本机的日程、待办、定时任务，以及记在「记一记」里的票和取件码；各行点进原来的本机管理页。
 */
final class LocalPlanPage extends Console.BuiltPage {

    LocalPlanPage(Console c) {
        super(c, "计划（本机）");
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        LinearLayout g = k.group(col, null);
        row(k, g, "日程", "本机日程：建 / 查 / 改 / 删", "events");
        row(k, g, "待办", "本机待办清单", "todos");
        row(k, g, "定时任务", "本机自动任务（每天 / 每周…）", "automations");
        row(k, g, "票和取件码", "记在「记一记」里", "tickets");
        k.footer(col, "没装「OneSay Plan」或计划开关关着时，这些数据留在 OneSay 本机。装上计划 App 并打开开关后，首页「计划」会直接跳过去。"
                + "日程、待办、定时任务的开关在「功能与权限」。");
    }

    private void row(ConsoleKit k, LinearLayout g, String title, String sub, final String tab) {
        k.linkRow(g, title, sub, 0, new Runnable() {
            @Override public void run() {
                openLocal(c, tab);
            }
        });
    }

    /** 本机回落：日程 / 待办 / 定时任务 → 原本机管理页；票 / 取件码 → 记一记；其余 → 本机计划总页。 */
    static void openLocal(Console c, String tab) {
        String pid = PlanSection.localPage(tab);
        if (PlanSection.LOCAL_QUICKNOTE.equals(pid)) {
            com.onesay.assistant.v2.quicknote.ui.QuickNotePage.open(c.act, -1);
        } else if (pid != null) {
            c.openPage(pid, PlanSection.label(tab));
        } else {
            c.push(new LocalPlanPage(c));
        }
    }
}
