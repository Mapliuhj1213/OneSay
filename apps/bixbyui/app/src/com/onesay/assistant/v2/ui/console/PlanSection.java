package com.onesay.assistant.v2.ui.console;

/**
 * [console 计划区] 首页「计划」区的纯逻辑（不碰 View，JVM 可测）：直达页签表、跳转 / 回落决策、副标题文案。
 * <ul>
 *   <li>页签 id 与计划 App {@code com.onesay.plan.action.OPEN} 的 extra {@code tab} 一一对应（见 PlannerLink.open）；</li>
 *   <li>先试计划 App（PlannerLink.manageInPlanner：计划开关开着 + 计划 App 能打开）；打不开 → 本机计划页：
 *       日程 / 待办 / 自动任务各进本机对应页，票 / 取件码进「记一记」，「打开计划」「今天」进本机计划总页 {@link LocalPlanPage}。</li>
 * </ul>
 */
final class PlanSection {

    private PlanSection() {
    }

    /** 直达页签：{显示名, 计划 App 页签 id}，顺序 = 首页从左到右。 */
    static final String[][] TABS = {
            {"今天", "today"},
            {"日程", "events"},
            {"待办", "todos"},
            {"票", "tickets"},
            {"取件码", "pickups"},
            {"自动任务", "automations"},
    };

    /** 本机回落用的页 id：记一记（票 / 取件码）走 QuickNotePage，不经 openPage。 */
    static final String LOCAL_QUICKNOTE = "quicknote";

    /** 打开计划 App（返回 true = 已跳过去）。 */
    interface Opener {
        boolean open(String tab);
    }

    /** 计划 App 打不开时：进本机计划页（tab 可为 null = 总页）。 */
    interface Local {
        void show(String tab);
    }

    static final String WENT_PLANNER = "planner", WENT_LOCAL = "local";

    /** 首页「打开计划」（tab = null）和直达页签共用：先计划 App，打不开回落本机。返回去了哪儿。 */
    static String go(Opener planner, Local local, String tab) {
        boolean ok;
        try {
            ok = planner != null && planner.open(tab);
        } catch (RuntimeException e) {
            ok = false;
        }
        if (ok) return WENT_PLANNER;
        if (local != null) local.show(tab);
        return WENT_LOCAL;
    }

    /** 本机回落的目标页：null = 本机计划总页（LocalPlanPage）。 */
    static String localPage(String tab) {
        if (tab == null) return null;
        if ("events".equals(tab)) return "sched.list";
        if ("todos".equals(tab)) return "s3.todo";
        if ("automations".equals(tab)) return "s3.tasks";
        if ("tickets".equals(tab) || "pickups".equals(tab)) return LOCAL_QUICKNOTE;
        return null;   // today / 未知页签
    }

    static String label(String tab) {
        for (String[] t : TABS) if (t[1].equals(tab)) return t[0];
        return "计划";
    }

    /** 「打开计划」行的副标题。 */
    static String sub(boolean installed, boolean switchOn) {
        if (!installed) return "没装「OneSay Plan」· 点开看本机数据";
        if (!switchOn) return "计划开关关着 · 点开看本机数据";
        return "日程、待办、票都在「OneSay Plan」里";
    }
}
