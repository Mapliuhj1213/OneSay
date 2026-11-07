package com.onesay.assistant.v2.planner;

import java.util.ArrayList;
import java.util.List;

/**
 * [planner] 提醒去重（2026-11-05 默认决定）：同一条日程 / 待办 / 票 / 取件码，<b>计划 App 发系统通知</b>，OneSay 灵动岛不再重复提醒。
 * <ul>
 *   <li>计划 App 连上（gate.on()）时，数据唯一真相源在计划 App，它的本地提醒（Reminders）覆盖日程 / 待办 / 票 / 取件码 → 岛上的同类到点提醒整批让掉；</li>
 *   <li>计划 App 不在 / 出错 → 回落本地存储，岛照旧提醒（功能不丢）；</li>
 *   <li>按条目去重（{@link #dropOwned}）：岛上要出的条目 src_key 若已在计划 App（bixbyui:sched:&lt;id&gt; 等），也让掉。</li>
 * </ul>
 * 只管「到点提醒」；岛上的即时卡片（识屏出码、记一记出码）不受影响。
 */
public final class PlannerDedupe {
    private PlannerDedupe() {}

    public interface KeyOf<T> {
        String key(T t);
    }

    /** 整批：计划 App 在 → 岛上这类到点提醒全部让给计划 App 的系统通知。 */
    public static <T> List<T> island(List<T> planned, PlannerGate g) {
        if (planned == null || planned.isEmpty()) return planned;
        boolean on;
        try {
            on = g != null && g.on();
        } catch (RuntimeException e) {
            on = false;
        }
        return on ? new ArrayList<T>() : planned;
    }

    /** 按条目：plannerKeys 里已有的（计划 App 会提醒的）src_key 不再上岛。 */
    public static <T> List<T> dropOwned(List<T> planned, KeyOf<T> keyOf, java.util.Set<String> plannerKeys) {
        if (planned == null || plannerKeys == null || plannerKeys.isEmpty()) return planned;
        List<T> out = new ArrayList<T>();
        for (T t : planned) {
            String k = keyOf.key(t);
            if (k == null || !plannerKeys.contains(k)) out.add(t);
        }
        return out;
    }
}
