package com.onesay.assistant.v2.timeline;

import java.util.concurrent.CopyOnWriteArrayList;

/**
 * ★ borrow-1112：往当前这一轮的执行时间轴里补一行说明（纯 Java）。
 * 用在不经 AgentLoop 步骤事件的地方：点屏代理「等到条件出现」期间画面上出现 / 消失的文字、主模型出错切到备用模型。
 * 面板服务启动时注册监听（{@code FloatingSheetService}），把这行写进屏上的那份 {@link ExecTimeline}；没有监听 = 丢掉（只是展示，不影响任务）。
 */
public final class TimelineNotes {

    private TimelineNotes() {}

    public interface Listener {
        void onNote(String text);
    }

    private static final CopyOnWriteArrayList<Listener> L = new CopyOnWriteArrayList<Listener>();

    public static void add(Listener l) {
        if (l != null && !L.contains(l)) L.add(l);
    }

    public static void remove(Listener l) {
        L.remove(l);
    }

    public static void post(String text) {
        if (text == null || text.trim().length() == 0) return;
        for (Listener l : L) {
            try {
                l.onNote(text.trim());
            } catch (Throwable ignored) {
            }
        }
    }
}
