package com.onesay.assistant.v2.executor.backend;

import com.onesay.assistant.v2.fastscreen.FsTree;

/**
 * ★ fix/triage-fast：清空后台的纯逻辑部分（JVM 可测）—— 守护模板名、三星最近任务「全部关闭」按钮识别。
 */
public final class RecentsClear {

    private RecentsClear() {}

    /** exec 守护 v4.4 内建固定模板（守护执行自己的实现，不带参数）。见 tools/kdaemon/exec_daemon.v4.sh clear_recents。 */
    public static final String DAEMON_TEMPLATE = "bixby-clear-recents";

    /** 三星 One UI / AOSP 最近任务里「全部关闭」按钮的文字（中 / 英 / 韩），逐字相等才算。 */
    static final String[] CLEAR_TEXTS = {"全部关闭", "关闭全部", "全部清除", "清除全部", "全部清理", "清除所有", "关闭所有",
            "close all", "clear all", "모두 닫기"};
    /** 资源 id 片段（SystemUI / 三星 recents）。 */
    static final String[] CLEAR_IDS = {"clear_all", "clearall", "recents_close_all", "close_all", "dismiss_all"};
    static final String[] EMPTY_TEXTS = {"没有最近使用的应用", "无最近使用的应用", "没有最近使用的项目", "no recent items", "无最近项目"};

    /** @return 「全部关闭」节点；没有返回 null */
    public static FsTree.Node findClearAll(FsTree tree) {
        if (tree == null) return null;
        FsTree.Node byId = null;
        for (FsTree.Node n : tree.nodes) {
            String t = n.text == null ? "" : n.text.trim().toLowerCase(java.util.Locale.ROOT);
            String d = n.desc == null ? "" : n.desc.trim().toLowerCase(java.util.Locale.ROOT);
            for (String c : CLEAR_TEXTS) {
                if (t.equals(c) || d.equals(c)) return n;
            }
            String rid = n.rid == null ? "" : n.rid.toLowerCase(java.util.Locale.ROOT);
            if (byId == null && rid.length() > 0) {
                for (String c : CLEAR_IDS) if (rid.endsWith("/" + c) || rid.endsWith(":id/" + c) || rid.equals(c)) byId = n;
            }
        }
        return byId;
    }

    /** 最近任务是空的（「没有最近使用的应用」）。 */
    public static boolean isEmptyRecents(FsTree tree) {
        if (tree == null) return false;
        for (FsTree.Node n : tree.nodes) {
            String t = n.text == null ? "" : n.text.trim().toLowerCase(java.util.Locale.ROOT);
            for (String e : EMPTY_TEXTS) if (t.equals(e)) return true;
        }
        return false;
    }

    /** 守护输出里只留「removed=N killed」这类摘要（日志用，不回显别的）。 */
    static String summarize(String out) {
        if (out == null) return "";
        String s = out.trim();
        return s.length() > 60 ? s.substring(0, 60) : s;
    }
}
