package com.onesay.assistant.v2.planner;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * ★ borrow-1112：「做完了」勾待办的整条流程（纯 Java：取候选 / 标完成都经 {@link Backend}，JVM 全测）。
 * 先看是不是在回答上一句「是哪一条」，再认「做完了」类的话；候选 = 计划 App 正在提醒 / 最近提醒的 + 助手本地最近发过提醒的（按 id 去重）。
 */
public final class TodoVoiceFlow {

    private TodoVoiceFlow() {}

    public interface Backend {
        /** 计划 App 那边的（null = 计划 App 不可用 / 没有这个接口）。 */
        List<TodoVoiceDone.Cand> planner();

        /** 助手本地最近发过提醒的。 */
        List<TodoVoiceDone.Cand> local(long now);

        /** 经计划 App 标完成；null = 成功，否则原因。 */
        String completePlanner(long id);

        /** 助手本地待办标完成；null = 成功，否则原因。 */
        String completeLocal(long id);
    }

    public static final TodoVoiceDone.Pending PENDING = new TodoVoiceDone.Pending();

    /** 这句话会不会被 {@link #handle} 接（不改任何东西；给路由的 claims 用）。 */
    public static boolean wouldHandle(String q, Backend b, long now) {
        List<TodoVoiceDone.Cand> opts = PENDING.get(now);
        if (opts != null && TodoVoiceDone.answer(q, opts) != null) return true;
        TodoVoiceDone.Intent in = TodoVoiceDone.parse(q);
        if (in == null) return false;
        return TodoVoiceDone.resolve(in, candidates(b, now)).kind != TodoVoiceDone.Kind.NONE;
    }

    /** null = 不接（交回原流程）；否则是给用户的回答。 */
    public static String handle(String q, Backend b, long now) {
        List<TodoVoiceDone.Cand> opts = PENDING.get(now);
        if (opts != null) {
            List<TodoVoiceDone.Cand> pick = TodoVoiceDone.answer(q, opts);
            if (pick != null) {
                PENDING.clear();
                if (pick.isEmpty()) return "好，那先不勾。";
                return complete(pick, b);
            }
        }
        TodoVoiceDone.Intent in = TodoVoiceDone.parse(q);
        if (in == null) return null;
        TodoVoiceDone.Decision d = TodoVoiceDone.resolve(in, candidates(b, now));
        if (d.kind == TodoVoiceDone.Kind.NONE) return null;
        if (d.kind == TodoVoiceDone.Kind.ASK) {
            PENDING.set(d.picks, now);
            return d.say;
        }
        PENDING.clear();
        return complete(d.picks, b);
    }

    static List<TodoVoiceDone.Cand> candidates(Backend b, long now) {
        List<TodoVoiceDone.Cand> out = new ArrayList<TodoVoiceDone.Cand>();
        Set<Long> ids = new HashSet<Long>();
        List<TodoVoiceDone.Cand> p = b.planner();
        if (p != null) for (TodoVoiceDone.Cand c : p) if (ids.add(c.id)) out.add(c);
        List<TodoVoiceDone.Cand> l = b.local(now);
        if (l != null) for (TodoVoiceDone.Cand c : l) if (ids.add(c.id)) out.add(c);
        return out;
    }

    static String complete(List<TodoVoiceDone.Cand> picks, Backend b) {
        List<TodoVoiceDone.Cand> ok = new ArrayList<TodoVoiceDone.Cand>();
        StringBuilder fail = new StringBuilder();
        for (TodoVoiceDone.Cand c : picks) {
            String why = "planner".equals(c.source) ? b.completePlanner(c.id) : b.completeLocal(c.id);
            if (why == null) {
                ok.add(c);
                RecentReminds.forget(c.id);
            } else fail.append(fail.length() > 0 ? "；" : "").append("「").append(c.title).append("」没勾上：").append(why);
        }
        if (ok.isEmpty()) return fail.length() > 0 ? fail.append("。").toString() : "没勾上。";
        String s = TodoVoiceDone.doneText(ok);
        return fail.length() > 0 ? s + fail + "。" : s;
    }
}
