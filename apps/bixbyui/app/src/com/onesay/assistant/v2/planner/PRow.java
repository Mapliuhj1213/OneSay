package com.onesay.assistant.v2.planner;

import java.util.LinkedHashMap;

/** [planner] 计划 App core/Row 的精简副本（PlannerTicketText 用）。 */
public final class PRow extends LinkedHashMap<String, Object> {
    public PRow with(String k, Object v) {
        put(k, v);
        return this;
    }

    public String s(String k) {
        Object v = get(k);
        return v == null ? "" : String.valueOf(v);
    }

    public long l(String k) {
        Object v = get(k);
        if (v instanceof Number) return ((Number) v).longValue();
        try {
            return v == null ? 0 : Long.parseLong(String.valueOf(v).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
