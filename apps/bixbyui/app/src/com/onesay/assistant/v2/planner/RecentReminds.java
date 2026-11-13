package com.onesay.assistant.v2.planner;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

/**
 * ★ borrow-1112：助手自己发过的待办提醒（S3 到点提醒、带 todoId 的那种），给「说『做完了』勾待办」用（纯 Java）。
 * 计划 App 自己响的提醒由它的 listRemindedTasks 返回，这里只记助手本地发的。进程内、最多 {@link #MAX} 条、过 {@link TodoVoiceDone#WINDOW_MIN} 分钟就不算「最近」。
 */
public final class RecentReminds {

    private RecentReminds() {}

    static final int MAX = 16;
    private static final LinkedList<TodoVoiceDone.Cand> L = new LinkedList<TodoVoiceDone.Cand>();

    /** 发了一条待办提醒。 */
    public static synchronized void note(long todoId, String title, long at) {
        if (todoId <= 0) return;
        for (Iterator<TodoVoiceDone.Cand> it = L.iterator(); it.hasNext(); ) if (it.next().id == todoId) it.remove();
        String t = title == null ? "" : title.replaceFirst("^待办[：:]", "");
        L.addFirst(new TodoVoiceDone.Cand(todoId, t, "notified", at, "local"));
        while (L.size() > MAX) L.removeLast();
    }

    /** 这条已经完成 / 删除了。 */
    public static synchronized void forget(long todoId) {
        for (Iterator<TodoVoiceDone.Cand> it = L.iterator(); it.hasNext(); ) if (it.next().id == todoId) it.remove();
    }

    /** 最近窗口内的（新的在前）。 */
    public static synchronized List<TodoVoiceDone.Cand> recent(long now) {
        List<TodoVoiceDone.Cand> out = new ArrayList<TodoVoiceDone.Cand>();
        long from = now - TodoVoiceDone.WINDOW_MIN * 60_000L;
        for (TodoVoiceDone.Cand c : L) if (c.remindedAt >= from && c.remindedAt <= now + 60_000L) out.add(c);
        return out;
    }

    public static synchronized void clear() {
        L.clear();
    }
}
