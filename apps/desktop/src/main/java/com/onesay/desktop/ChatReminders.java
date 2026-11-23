package com.onesay.desktop;

import com.onesay.desktop.AlwaysOn.Action;
import com.onesay.desktop.AlwaysOn.Job;
import com.onesay.desktop.AlwaysOn.Where;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [fix-1120 晚] 对话里的「提醒 / 日程」工具：在这台电脑上建、列、改、删提醒。
 *
 * <p>用常驻分工的任务模型（{@link AlwaysOnService}，存在预览版自己的数据目录 always-on.json）：执行位置默认「电脑本地」，
 * 到点由 OneSay 在电脑上弹通知；可选「电脑离线时交给常驻节点（中转）送到手机」。不建 Windows 计划任务。
 * 建 / 改 / 删要用户批准（低风险，可「允许本次任务」一次批准）；列出不用。
 *
 * <p>日期是推测的（没查到官方来源）时，提醒标题前加「【待确认】」、内容写明先去核实，不把推测日期当事实提醒。
 */
public final class ChatReminders {

    /** 批准：detail 给人看；返回 null = 允许，否则是没批准的结果（带 reason，和别的步骤一样）。 */
    public interface Approver { Actions.Result approve(String detail); }

    final AlwaysOnService svc;
    final Approver approver;
    final LongSupplier clock;
    final ZoneId zone;

    public ChatReminders(AlwaysOnService svc, Approver approver, LongSupplier clock, ZoneId zone) {
        this.svc = svc;
        this.approver = approver;
        this.clock = clock == null ? System::currentTimeMillis : clock;
        this.zone = zone == null ? ZoneId.systemDefault() : zone;
    }

    public static final String PENDING_TAG = "【待确认】";
    /** 文字里说明日期是推测 / 未核实的说法：没标 date_verified 也按推测处理。 */
    static final Pattern GUESS = Pattern.compile("推测|估计|预计|往年|大概|大约|可能在|可能是|应该是|一般在|通常在|待定|待确认|未核实|没核实|未确认|没查到|未公布");
    static final Pattern VERIFY_WORDS = Pattern.compile("待确认|核实|核对|确认日期|以官方为准");

    // ------------------------------------------------------------------ 工具定义

    public static Map<String, Object> toolDef() {
        Map<String, Object> p = new TreeMap<>();
        p.put("description", ChatAgent.prop("string", ChatAgent.DESC));
        p.put("action", ChatAgent.prop("string", "add = 建一条；list = 列出这台电脑上的提醒；update = 改一条（给 id 和要改的字段）；delete = 删一条（给 id）"));
        p.put("id", ChatAgent.prop("string", "update / delete 用：提醒的 id（先 list 查）"));
        p.put("title", ChatAgent.prop("string", "提醒标题，一句话（60 字以内），如「体检前一晚：20 点后别吃东西」"));
        p.put("text", ChatAgent.prop("string", "可选：提醒正文（500 字以内），写清要做什么、带什么"));
        p.put("at", ChatAgent.prop("string", "提醒时间，这台电脑的本地时间，格式 YYYY-MM-DD HH:mm（如 2026-11-23 20:00）。先按今天的日期算准星期再填"));
        p.put("repeat", ChatAgent.prop("string", "可选：none（默认，只一次）/ hourly / daily / weekly"));
        p.put("date_verified", ChatAgent.prop("boolean", "add 必填：true = 时间是用户明确给的，或查到了官方来源；false = 推测 / 按往年惯例 / 没查到官方日期（提醒会自动标「待确认」）"));
        p.put("phone_backup", ChatAgent.prop("boolean", "可选：电脑关着到点时交给常驻节点（中转）送到手机（要先在设置里配好中转、手机配对）。默认 false：只在电脑上提醒"));
        return ChatAgent.fn("reminder", "在这台电脑上建 / 列 / 改 / 删提醒（日程提醒、待办提醒）。提醒存在 OneSay 里（不建 Windows 计划任务），"
                + "到点在电脑上弹通知；电脑关着错过的，下次打开 OneSay 2 小时内补提醒一次。不需要手机：手机没连也能建。建 / 改 / 删要用户点一次批准。",
                p, List.of("description", "action"));
    }

    /** 模型参数 → 规整后的参数（认不出返回 null）。 */
    static Map<String, Object> mapArgs(Map<String, Object> a) {
        String act = AlwaysOn.str(a.get("action")).toLowerCase(Locale.ROOT);
        switch (act) {
            case "create": case "new": act = "add"; break;
            case "remove": case "del": act = "delete"; break;
            case "edit": case "modify": act = "update"; break;
            case "ls": act = "list"; break;
            default: break;
        }
        if (!act.matches("add|list|update|delete")) return null;
        Map<String, Object> out = new TreeMap<>();
        out.put("action", act);
        for (String k : new String[] {"id", "title", "text", "at", "repeat"}) {
            String v = AlwaysOn.str(a.get(k));
            if (!v.isEmpty()) out.put(k, v);
        }
        for (String k : new String[] {"date_verified", "phone_backup"}) {
            Object v = a.get(k);
            if (v != null) out.put(k, Boolean.TRUE.equals(v) || "true".equalsIgnoreCase(String.valueOf(v).trim()));
        }
        if (("update".equals(act) || "delete".equals(act)) && !out.containsKey("id")) return null;
        if ("add".equals(act) && (!out.containsKey("title") || !out.containsKey("at"))) return null;
        return out;
    }

    // ------------------------------------------------------------------ 时间

    static final Pattern AT = Pattern.compile("^\\s*(\\d{4})[-/.年](\\d{1,2})[-/.月](\\d{1,2})日?(?:[ T]+|\\s*)(\\d{1,2})[:：点](\\d{2})?分?(?::\\d{2})?\\s*$");

    /** 「2026-11-23 20:00」→ 毫秒（本地时区）；认不出返回 -1。 */
    long parseAt(String s) {
        if (s == null) return -1;
        Matcher m = AT.matcher(s.trim());
        if (!m.matches()) return -1;
        try {
            LocalDateTime t = LocalDateTime.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), m.group(5) == null ? 0 : Integer.parseInt(m.group(5)));
            return t.atZone(zone).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return -1;
        }
    }

    static final String[] WEEK = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    String when(long ms) {
        ZonedDateTime z = Instant.ofEpochMilli(ms).atZone(zone);
        DayOfWeek d = z.getDayOfWeek();
        return z.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + "（" + WEEK[d.getValue()] + "）" + z.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    static long every(String r) {
        switch (r == null ? "" : r.trim().toLowerCase(Locale.ROOT)) {
            case "hourly": case "每小时": return 3600_000L;
            case "daily": case "每天": return 24 * 3600_000L;
            case "weekly": case "每周": return 7 * 24 * 3600_000L;
            default: return 0;
        }
    }

    static String repeatZh(long every) {
        return every == 3600_000L ? "每小时" : every == 24 * 3600_000L ? "每天" : every == 7 * 24 * 3600_000L ? "每周" : every > 0 ? "每 " + every / 60000 + " 分钟" : "一次";
    }

    // ------------------------------------------------------------------ 推测的日期

    /** 日期是推测的：模型标了 date_verified=false，或标题 / 正文里自己说了推测 / 往年 / 没查到。 */
    static boolean guessed(Map<String, Object> a, String title, String text) {
        if (Boolean.FALSE.equals(a.get("date_verified"))) return true;
        if (Boolean.TRUE.equals(a.get("date_verified"))) return false;
        return GUESS.matcher(title + " " + text).find();
    }

    /** 推测日期的提醒：标题前加「【待确认】」，正文写明先核实（已经写了就不重复）。 */
    static String[] markPending(String title, String text) {
        String t = title.startsWith(PENDING_TAG) ? title : PENDING_TAG + title;
        if (t.length() > 60) t = t.substring(0, 60);
        String x = text == null ? "" : text;
        if (!VERIFY_WORDS.matcher(x).find()) x = (x.isEmpty() ? "" : x + "\n") + "日期是推测的，还没核实：先去官方渠道确认日期，再按确认的日期安排。";
        if (x.length() > 500) x = x.substring(0, 500);
        return new String[] {t, x};
    }

    // ------------------------------------------------------------------ 执行

    public Actions.Result run(Map<String, Object> a) {
        if (svc == null) return Actions.Result.fail("提醒功能没启动（常驻分工没接上）");
        String act = String.valueOf(a.getOrDefault("action", ""));
        switch (act) {
            case "list": return list();
            case "add": return add(a);
            case "update": return update(a);
            case "delete": return delete(a);
            default: return Actions.Result.fail("action 只能是 add / list / update / delete");
        }
    }

    Actions.Result list() {
        List<Object> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        long now = clock.getAsLong();
        for (Job j : svc.jobs()) {
            if (j.action != Action.NOTIFY) continue;
            long nx = AlwaysOn.nextDue(j, now);
            Map<String, Object> m = new TreeMap<>();
            m.put("id", j.id);
            m.put("title", j.title);
            m.put("text", j.text);
            m.put("next", nx > 0 ? when(nx) : "已经过了");
            m.put("repeat", repeatZh(j.every));
            m.put("where", j.where.zh);
            m.put("enabled", j.enabled);
            if (j.unverified) m.put("date_verified", false);
            out.add(m);
            b.append("\n- ").append(j.id).append("：「").append(j.title).append("」 ").append(nx > 0 ? when(nx) : "已经过了").append(" · ").append(repeatZh(j.every))
                    .append(" · ").append(j.where.zh).append(j.enabled ? "" : " · 已暂停");
        }
        Map<String, Object> d = new TreeMap<>();
        d.put("reminders", out);
        return Actions.Result.ok(out.isEmpty() ? "这台电脑上还没有提醒" : "这台电脑上的提醒（" + out.size() + " 条）：" + b, d);
    }

    String placeNote(Job j, boolean wantPhone) {
        String s = j.where == Where.NODE
                ? "放在常驻节点：电脑开着时在电脑上弹通知；电脑关着时由中转送到手机（手机要已配对、在线）。"
                : "建在这台电脑上：到点由 OneSay 在电脑右下角弹通知（OneSay 要开着；电脑关着错过的，下次打开 2 小时内补提醒一次）。不需要手机。";
        if (wantPhone && j.where != Where.NODE) s += "要电脑关着时也送到手机，得先在设置 › 连接里配好中转并配对手机，这次先只建在电脑上。";
        return s + "用户可以在 设置 › 常驻与唤醒 里看到、暂停或删除。";
    }

    Actions.Result add(Map<String, Object> a) {
        String title = String.valueOf(a.getOrDefault("title", "")).trim(), text = String.valueOf(a.getOrDefault("text", "")).trim();
        long at = parseAt(String.valueOf(a.getOrDefault("at", "")));
        if (at < 0) return Actions.Result.fail("时间格式认不出：写成 YYYY-MM-DD HH:mm（这台电脑的本地时间），如 2026-11-23 20:00");
        long now = clock.getAsLong();
        long ev = every(String.valueOf(a.getOrDefault("repeat", "")));
        if (at <= now && ev == 0) return Actions.Result.fail("这个时间已经过了（现在是 " + when(now) + "）：换一个以后的时间");
        boolean pending = guessed(a, title, text);
        if (pending) { String[] p = markPending(title, text); title = p[0]; text = p[1]; }
        boolean wantPhone = Boolean.TRUE.equals(a.get("phone_backup"));
        Job j = new Job();
        j.title = title;
        j.text = text;
        j.at = at;
        j.every = ev;
        j.action = Action.NOTIFY;
        j.where = wantPhone && svc.nodeConfigured ? Where.NODE : Where.LOCAL;
        j.origin = "chat";
        j.unverified = pending;
        String bad = AlwaysOn.validate(j, svc.nodeConfigured);
        if (bad != null) return Actions.Result.fail("没建：" + bad);
        Actions.Result no = approver == null ? null : approver.approve("建提醒「" + title + "」：" + when(at) + (ev > 0 ? " 起" + repeatZh(ev) : "") + " · " + j.where.zh
                + (text.isEmpty() ? "" : "\n内容：" + ChatAgent.clip(text, 120)));
        if (no != null) return no;
        bad = svc.add(j);
        if (bad != null) return Actions.Result.fail("没建：" + bad);
        Map<String, Object> d = new TreeMap<>();
        d.put("id", j.id);
        d.put("at", when(at));
        d.put("where", j.where.id);
        if (pending) d.put("date_verified", false);
        return Actions.Result.ok("已建提醒 " + j.id + "：「" + title + "」 " + when(at) + (ev > 0 ? " 起" + repeatZh(ev) : "") + "。" + placeNote(j, wantPhone)
                + (pending ? "日期是推测的，提醒里已标「待确认」；给用户的答复里也要写明待确认和去哪核实。" : ""), d);
    }

    Actions.Result update(Map<String, Object> a) {
        String id = String.valueOf(a.get("id"));
        Job old = svc.find(id);
        if (old == null || old.action != Action.NOTIFY) return Actions.Result.fail("没有这条提醒（先用 list 查 id）");
        long at = a.containsKey("at") ? parseAt(String.valueOf(a.get("at"))) : old.at;
        if (at < 0) return Actions.Result.fail("时间格式认不出：写成 YYYY-MM-DD HH:mm，如 2026-11-23 20:00");
        long ev = a.containsKey("repeat") ? every(String.valueOf(a.get("repeat"))) : old.every;
        long now = clock.getAsLong();
        if (a.containsKey("at") && at <= now && ev == 0) return Actions.Result.fail("这个时间已经过了（现在是 " + when(now) + "）");
        String title = a.containsKey("title") ? String.valueOf(a.get("title")).trim() : old.title.replaceFirst("^" + Pattern.quote(PENDING_TAG), "");
        String text = a.containsKey("text") ? String.valueOf(a.get("text")).trim() : old.text;
        boolean pending = a.containsKey("date_verified") ? Boolean.FALSE.equals(a.get("date_verified")) : old.unverified || GUESS.matcher(title + " " + text).find();
        if (pending) { String[] p = markPending(title, text); title = p[0]; text = p[1]; }
        boolean wantPhone = a.containsKey("phone_backup") ? Boolean.TRUE.equals(a.get("phone_backup")) : old.where == Where.NODE;
        Where where = wantPhone && svc.nodeConfigured ? Where.NODE : Where.LOCAL;
        final String ft = title, fx = text;
        final boolean fp = pending;
        Actions.Result no = approver == null ? null : approver.approve("改提醒「" + old.title + "」→「" + ft + "」：" + when(AlwaysOn.nextDue(old, now) > 0 ? AlwaysOn.nextDue(old, now) : old.at) + " → " + when(at) + " · " + where.zh);
        if (no != null) return no;
        String bad = svc.update(id, j -> { j.title = ft; j.text = fx; j.at = at; j.every = ev; j.where = where; j.unverified = fp; j.enabled = true; });
        if (bad != null) return Actions.Result.fail("没改：" + bad);
        Job j = svc.find(id);
        return Actions.Result.ok("已改提醒 " + id + "：「" + ft + "」 " + when(at) + (ev > 0 ? " 起" + repeatZh(ev) : "") + "。" + placeNote(j, wantPhone), new TreeMap<>(Map.of("id", id)));
    }

    Actions.Result delete(Map<String, Object> a) {
        String id = String.valueOf(a.get("id"));
        Job old = svc.find(id);
        if (old == null || old.action != Action.NOTIFY) return Actions.Result.fail("没有这条提醒（先用 list 查 id）");
        Actions.Result no = approver == null ? null : approver.approve("删除提醒「" + old.title + "」");
        if (no != null) return no;
        String bad = svc.remove(id);
        if (bad != null) return Actions.Result.fail("没删：" + bad);
        return Actions.Result.ok("已删除提醒「" + old.title + "」（常驻节点上的同一条也一起删了）", new TreeMap<>(Map.of("id", id)));
    }
}
