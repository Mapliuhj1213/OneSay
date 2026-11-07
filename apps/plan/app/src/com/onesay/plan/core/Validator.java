package com.onesay.plan.core;

import org.json.JSONObject;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 字段校验（后门与 App 内部写入走同一套）。纯 Java，JVM 全测。
 *
 * <ul>
 *   <li>不认识的列、外部不可写的列（_id / created_at / updated_at / qr_path）→ 拒绝；</li>
 *   <li>类型：INT 列接受 Long/Integer/数字字符串；TEXT 列一律转字符串、去首尾空白、按 maxLen 拒绝超长；</li>
 *   <li>必填（插入时）；枚举（票的种类、自动任务的 mode / trigger_type / action_type、运行状态）；</li>
 *   <li>跨字段：结束 ≥ 开始、时刻范围、mode 与 trigger_type 一致、JSON 列能解析、取件码只含字母数字和 -；</li>
 * </ul>
 * 更新时把 existing（库里原行）与新值合并后再做跨字段校验。
 */
public final class Validator {
    private Validator() {}

    /** 校验结果：{@link #error} 为 null 表示通过，{@link #values} 是规范化后的可写列。 */
    public static final class Result {
        public final String error;
        public final Map<String, Object> values;

        Result(String error, Map<String, Object> values) { this.error = error; this.values = values; }

        /** 是否通过。 */
        public boolean ok() { return error == null; }

        @Override public String toString() { return ok() ? "OK " + values : "ERR " + error; }
    }

    static final long MIN_TIME = 946684800000L;      // 2000-01-01
    static final long MAX_TIME = 4102444800000L;     // 2100-01-01
    static final Pattern CODE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9\\-]{1,39}");

    /**
     * 校验并规范化一次写入。
     *
     * @param tableName 表名（{@link Contract#TABLES} 之一）
     * @param in        调用方给的列
     * @param insert    true = 插入（检查必填）；false = 更新
     * @param existing  更新时库里的原行（合并后做跨字段校验）；插入传 null
     */
    public static Result validate(String tableName, Map<String, Object> in, boolean insert, Map<String, Object> existing) {
        Schema.Table t = Schema.table(tableName);
        if (t == null) return err("未知的表: " + tableName);
        if (in == null || in.isEmpty()) return err("没有字段");
        Map<String, Object> out = new LinkedHashMap<String, Object>();
        for (Map.Entry<String, Object> e : in.entrySet()) {
            Schema.Col c = t.col(e.getKey());
            if (c == null) return err("未知字段: " + e.getKey());
            if (!c.writable) return err("字段不可写: " + e.getKey());
            Object v = e.getValue();
            if (c.type == Schema.INT) {
                Long n = toLong(v);
                if (n == null) return err("字段 " + c.name + " 需要整数");
                out.put(c.name, n);
            } else {
                String s = v == null ? "" : String.valueOf(v).trim();
                if (c.maxLen > 0 && s.length() > c.maxLen) return err("字段 " + c.name + " 超长（上限 " + c.maxLen + "）");
                out.put(c.name, s);
            }
        }
        if (insert) {
            for (Schema.Col c : t.cols) {
                if (!c.required) continue;
                Object v = out.get(c.name);
                if (v == null || (v instanceof String && ((String) v).length() == 0)) return err("缺少必填字段: " + c.name);
            }
        } else {
            for (Schema.Col c : t.cols) {
                if (c.required && out.containsKey(c.name)) {
                    Object v = out.get(c.name);
                    if (v instanceof String && ((String) v).length() == 0) return err("必填字段不能清空: " + c.name);
                }
            }
        }
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        if (existing != null) m.putAll(existing);
        m.putAll(out);
        String x = crossCheck(tableName, m);
        if (x != null) return err(x);
        return new Result(null, out);
    }

    static String crossCheck(String t, Map<String, Object> m) {
        if (Contract.T_EVENTS.equals(t)) {
            if (has(m, "start_at") && !timeOk(l(m, "start_at"))) return "start_at 不是合理时间";
            long s = l(m, "start_at"), e = l(m, "end_at");
            if (e != 0 && e < s) return "end_at 早于 start_at";
            long rep = l(m, "repeat");
            if (rep < 0 || rep > 5) return "repeat 只能是 0–5";
            if (l(m, "week_mask") < 0 || l(m, "week_mask") > 127) return "week_mask 只能是 0–127";
            if (rep == 2 && l(m, "week_mask") == 0) return "每周重复需要 week_mask";
            long lead = has(m, "lead_min") ? l(m, "lead_min") : -1;
            if (lead < -1 || lead > 10080) return "lead_min 只能是 -1 或 0–10080";
        } else if (Contract.T_TODOS.equals(t)) {
            if (l(m, "due_at") != 0 && !timeOk(l(m, "due_at"))) return "due_at 不是合理时间";
            if (l(m, "priority") < 0 || l(m, "priority") > 2) return "priority 只能是 0–2";
        } else if (Contract.T_TICKETS.equals(t)) {
            if (has(m, "kind") && !in(s(m, "kind"), Contract.TICKET_KINDS)) return "kind 只能是 " + Arrays.toString(Contract.TICKET_KINDS);
            if (has(m, "depart_at") && !timeOk(l(m, "depart_at"))) return "depart_at 不是合理时间";
            if (l(m, "arrive_at") != 0 && l(m, "arrive_at") < l(m, "depart_at")) return "arrive_at 早于 depart_at";
            if (l(m, "status") < 0 || l(m, "status") > 2) return "status 只能是 0 有效 / 1 已用 / 2 取消";
        } else if (Contract.T_PICKUPS.equals(t)) {
            if (has(m, "code") && !CODE.matcher(s(m, "code")).matches()) return "code 只能含字母、数字和 -（2–40 位）";
            if (l(m, "deadline_at") != 0 && !timeOk(l(m, "deadline_at"))) return "deadline_at 不是合理时间";
        } else if (Contract.T_AUTOMATIONS.equals(t)) {
            String mode = s(m, "mode"), tt = s(m, "trigger_type"), at = s(m, "action_type");
            if (has(m, "mode") && !Contract.MODE_TIME.equals(mode) && !Contract.MODE_TRIGGER.equals(mode)) return "mode 只能是 time / trigger";
            if (Contract.MODE_TIME.equals(mode) && has(m, "trigger_type") && !"time".equals(tt)) return "mode=time 时 trigger_type 必须是 time";
            if (Contract.MODE_TRIGGER.equals(mode) && has(m, "trigger_type") && !in(tt, Contract.TRIGGERS))
                return "trigger_type 不认识: " + tt;
            if (has(m, "action_type") && !in(at, new String[]{Contract.ACT_REMIND, Contract.ACT_AGENT, Contract.ACT_BUILTIN}))
                return "action_type 只能是 remind / agent / builtin";
            if (Contract.ACT_AGENT.equals(at) && has(m, "action") && s(m, "action").length() == 0) return "agent 任务需要 action（指令）";
            if (l(m, "hour") < 0 || l(m, "hour") > 23) return "hour 只能是 0–23";
            if (l(m, "minute") < 0 || l(m, "minute") > 59) return "minute 只能是 0–59";
            long rep = l(m, "repeat");
            if (rep < 0 || rep > 3) return "repeat 只能是 0 一次 / 1 每天 / 2 每周 / 3 工作日";
            if (Contract.MODE_TIME.equals(mode) && rep == 0 && has(m, "once_at") && l(m, "once_at") != 0 && !timeOk(l(m, "once_at")))
                return "once_at 不是合理时间";
            for (String j : new String[]{"trigger_json", "guard_json", "state_json"}) {
                if (has(m, j) && s(m, j).length() > 0 && !jsonOk(s(m, j))) return j + " 不是 JSON 对象";
            }
        } else if (Contract.T_RUNS.equals(t)) {
            if (has(m, "status") && !in(s(m, "status"), Contract.RUN_STATUS)) return "status 只能是 " + Arrays.toString(Contract.RUN_STATUS);
            if (has(m, "started_at") && !timeOk(l(m, "started_at"))) return "started_at 不是合理时间";
            if (has(m, "automation_id") && l(m, "automation_id") <= 0) return "automation_id 无效";
        } else if (Contract.T_SHORTCUTS.equals(t)) {
            if (has(m, "name")) {
                String x = Shortcuts.checkName(s(m, "name"));
                if (x != null) return x;
            }
            if (has(m, "steps_json")) {
                String x = Shortcuts.checkSteps(s(m, "steps_json"));
                if (x != null) return x;
            }
            if (has(m, "triggers_json") && s(m, "triggers_json").length() > 0 && !jsonOk(s(m, "triggers_json"))) return "triggers_json 不是 JSON 对象";
            for (String j : new String[]{"aliases_json", "history_json"}) {
                if (has(m, j) && s(m, j).length() > 0 && !jsonArrayOk(s(m, j))) return j + " 不是 JSON 数组";
            }
            if (has(m, "origin") && !in(s(m, "origin"), new String[]{"ui", "chat"})) return "origin 只能是 ui / chat";
        } else if (Contract.T_SHORTCUT_RUNS.equals(t)) {
            if (has(m, "status") && !in(s(m, "status"), Contract.SC_STATUS)) return "status 只能是 " + Arrays.toString(Contract.SC_STATUS);
            if (has(m, "started_at") && !timeOk(l(m, "started_at"))) return "started_at 不是合理时间";
            if (has(m, "shortcut_id") && l(m, "shortcut_id") <= 0) return "shortcut_id 无效";
            if (has(m, "mode") && !in(s(m, "mode"), new String[]{"shortcut", "normal"})) return "mode 只能是 shortcut / normal";
            if (has(m, "trigger_info") && !in(s(m, "trigger_info"), Contract.SC_TRIGGERS)) return "trigger_info 不认识: " + s(m, "trigger_info");
        }
        return null;
    }

    static boolean jsonOk(String s) {
        try {
            new JSONObject(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean jsonArrayOk(String s) {
        try {
            new org.json.JSONArray(s);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static boolean timeOk(long t) { return t >= MIN_TIME && t <= MAX_TIME; }

    static boolean has(Map<String, Object> m, String k) { return m.containsKey(k) && m.get(k) != null; }

    static long l(Map<String, Object> m, String k) {
        Long v = toLong(m.get(k));
        return v == null ? 0 : v;
    }

    static String s(Map<String, Object> m, String k) {
        Object v = m.get(k);
        return v == null ? "" : String.valueOf(v);
    }

    static boolean in(String v, String[] a) {
        for (String x : a) if (x.equals(v)) return true;
        return false;
    }

    /** 宽松转整数：null / 空串 → 0；整数类、布尔、整数值的浮点、"true"/"false"、数字字符串照转；无法表示为整数返回 null。 */
    public static Long toLong(Object v) {
        if (v == null) return 0L;
        if (v instanceof Long) return (Long) v;
        if (v instanceof Integer || v instanceof Short || v instanceof Byte) return ((Number) v).longValue();
        if (v instanceof Boolean) return ((Boolean) v) ? 1L : 0L;
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (d != Math.rint(d)) return null;
            return (long) d;
        }
        String s = String.valueOf(v).trim();
        if (s.length() == 0) return 0L;
        if ("true".equalsIgnoreCase(s)) return 1L;
        if ("false".equalsIgnoreCase(s)) return 0L;
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static Result err(String e) { return new Result(e, null); }
}
