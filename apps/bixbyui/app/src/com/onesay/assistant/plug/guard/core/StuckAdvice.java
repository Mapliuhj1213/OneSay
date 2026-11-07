/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/agent/StuckAdvice.kt, core/agent/StopRules.kt guard part,
 * prompts/feedback/loop.md give_up_advice / repeated_input_advice) to Java 7, texts in Chinese. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.guard.core;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 卡住时给模型的建议（放进结果的 advice）：卡住类提醒第二次起建议找遍了就如实放弃；同一段字在同一个地方又输了一遍时说已经输过几次。
 * 另含 StopRules 的守卫叫停说法。一次任务一份（有状态，非线程安全）。
 */
public final class StuckAdvice {
    static final String[] STUCK = {"no_progress", "no_new_state", "read_loop"};
    public static final String ANSWER_OR_FAILURE = "已经拿到的结果就直接交出来，说明还缺什么；什么都没拿到就如实说明做不成（end_not_done / done 里写清楚）。";

    private int warnings;
    private final Map<String, Integer> typed = new HashMap<String, Integer>();

    static boolean stuckCode(String c) {
        for (String s : STUCK) if (s.equals(c)) return true;
        return false;
    }

    /** 一个动作的守卫结论 + 这次成功输入的明文（不是输入 / 密码框为 null）+ 当时在哪 + 是否有新进展；返回建议或 null。 */
    public String next(List<OperationGuards.Verdict> verdicts, String typedText, String place, boolean progressed) {
        if (progressed) {
            warnings = 0;
            typed.clear();
        }
        boolean stuckNow = false;
        for (OperationGuards.Verdict v : verdicts) {
            if (stuckCode(v.code)) stuckNow = true;
            if (v.severity == GuardRules.Severity.WARN && stuckCode(v.code)) warnings++;
        }
        String again = null;
        if (typedText != null && typedText.trim().length() > 0) {
            String t = typedText.trim();
            String key = place + "\u0000" + t;
            Integer n0 = typed.get(key);
            int n = (n0 == null ? 0 : n0) + 1;
            typed.put(key, n);
            if (n >= 2 && stuckNow) again = "「" + t + "」在这个任务里已经输过 " + n + " 次了，再输一遍结果还一样。查过的地方说明目标不在那里，就停下。" + ANSWER_OR_FAILURE;
        }
        if (warnings >= 2 && stuckNow)
            return "你一直在重复，没有进展。别再回到同样的地方。如果用户的某个具体选择或信息能决定下一条路，问一次；否则只在有明确没试过的路时继续，没有就带着已确认的部分结果收尾，说明还缺什么、查过哪些。" + ANSWER_OR_FAILURE;
        return again;
    }

    /** StopRules.guard：守卫叫停时给用户看的结局说明。 */
    public static String stopReason(OperationGuards.Verdict v) {
        String why;
        if ("repeating_action".equals(v.code)) why = "同一个操作反复执行仍没有效果";
        else if ("no_progress".equals(v.code)) why = "操作多次后画面一直没有变化";
        else if ("read_loop".equals(v.code)) why = "页面没有变化，反复读也读不出要找的内容";
        else if ("too_many_failures".equals(v.code)) why = "操作连续失败";
        else why = "触发了操作保护";
        return "已安全停止（" + v.code + "）：" + why + "。";
    }
}
