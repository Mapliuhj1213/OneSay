package com.onesay.assistant.v2.vision.screenref;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * [screenref] （动作, 实体）→ 能力签名 + 参数 + 芯片文案；以及唤醒后"下一步动作芯片"的挑选。纯 Java。
 *
 * <h3>映射到的能力</h3>
 * <table>
 *   <tr><td>导航</td><td>{@code screen.nav.to}（geo: Intent，交给系统默认地图：高德/百度/腾讯均可接）</td></tr>
 *   <tr><td>拨打</td><td>既有 {@code phone.call}（risk=2，<b>必弹确认卡</b>）</td></tr>
 *   <tr><td>加日历</td><td>{@code screen.calendar.add}（INSERT Intent 打开日历编辑页，用户自己点保存）</td></tr>
 *   <tr><td>打开链接 / 写邮件</td><td>{@code screen.open.url}（仅 http/https/mailto）</td></tr>
 *   <tr><td>查快递</td><td>既有 {@code browser.search}（"顺丰 SF…"，百度快递卡片）</td></tr>
 *   <tr><td>复制</td><td>{@code screen.copy}（App 内 ClipboardManager，本地执行）</td></tr>
 * </table>
 */
public final class ActionMapper {

    private ActionMapper() {}

    public static final int MAX_CHIPS = 4;
    public static final int MIN_CHIPS = 2;
    private static final int LABEL_VALUE_MAX = 12;

    /** 把消解结果落成动作；无法映射返回 null。 */
    public static ScreenAction map(DeixisResolver.Action action, ScreenEntity e) {
        if (e == null || action == null) return null;
        JSONObject a = new JSONObject();
        try {
            switch (action) {
                case NAVIGATE:
                    if (e.type != ScreenEntity.Type.ADDRESS) return null;
                    a.put("address", e.value);
                    return new ScreenAction(action, ScreenRefCapabilities.SIGN_NAV, a, "导航到 " + e.shortDisplay(LABEL_VALUE_MAX), e);
                case CALL:
                    if (e.type != ScreenEntity.Type.PHONE) return null;
                    a.put("number", e.value);
                    return new ScreenAction(action, "phone.call", a, "拨打 " + e.value, e);
                case CALENDAR: {
                    if (e.type != ScreenEntity.Type.DATETIME || e.beginMs <= 0) return null;
                    long begin = e.beginMs;
                    long end = e.endMs > begin ? e.endMs : begin + (e.allDay ? 24L : 1L) * 3600_000L;
                    a.put("title", calendarTitle(e));
                    a.put("begin_ms", String.valueOf(begin));
                    a.put("end_ms", String.valueOf(end));
                    a.put("all_day", e.allDay ? "true" : "false");
                    return new ScreenAction(action, ScreenRefCapabilities.SIGN_CALENDAR, a, "加入日历 " + e.shortDisplay(LABEL_VALUE_MAX), e);
                }
                case OPEN_URL: {
                    if (e.type != ScreenEntity.Type.URL) return null;
                    String u = safeUrl(e.value);
                    if (u == null) return null;
                    a.put("url", u);
                    return new ScreenAction(action, ScreenRefCapabilities.SIGN_OPEN_URL, a, "打开链接 " + e.shortDisplay(LABEL_VALUE_MAX), e);
                }
                case EMAIL:
                    if (e.type != ScreenEntity.Type.EMAIL) return null;
                    a.put("url", "mailto:" + e.value);
                    return new ScreenAction(action, ScreenRefCapabilities.SIGN_OPEN_URL, a, "写邮件给 " + e.shortDisplay(LABEL_VALUE_MAX), e);
                case TRACK:
                    if (e.type != ScreenEntity.Type.TRACKING) return null;
                    a.put("keyword", (e.subLabel != null ? e.subLabel + "快递 " : "快递单号 ") + e.value);
                    return new ScreenAction(action, "browser.search", a, "查快递 " + e.shortDisplay(LABEL_VALUE_MAX), e);
                case COPY:
                    a.put("text", e.value);
                    a.put("kind", e.kindLabel());
                    return new ScreenAction(action, ScreenRefCapabilities.SIGN_COPY, a, "复制" + e.kindLabel()
                            + (e.type == ScreenEntity.Type.CODE ? " " + e.value : ""), e);
                default:
                    return null;
            }
        } catch (Exception ex) {
            return null;
        }
    }

    /** 实体的"主动作"：取件码→复制，单号→查快递，地址→导航，电话→拨打，时间→加日历，链接→打开，邮箱→写邮件，金额→复制。 */
    public static DeixisResolver.Action primaryAction(ScreenEntity e) {
        switch (e.type) {
            case CODE: return DeixisResolver.Action.COPY;
            case TRACKING: return DeixisResolver.Action.TRACK;
            case ADDRESS: return DeixisResolver.Action.NAVIGATE;
            case PHONE: return DeixisResolver.Action.CALL;
            case DATETIME: return DeixisResolver.Action.CALENDAR;
            case URL: return DeixisResolver.Action.OPEN_URL;
            case EMAIL: return DeixisResolver.Action.EMAIL;
            default: return DeixisResolver.Action.COPY;
        }
    }

    private static final ScreenEntity.Type[] CHIP_PRIORITY = {
            ScreenEntity.Type.CODE, ScreenEntity.Type.TRACKING, ScreenEntity.Type.ADDRESS, ScreenEntity.Type.PHONE,
            ScreenEntity.Type.DATETIME, ScreenEntity.Type.URL, ScreenEntity.Type.EMAIL};

    /**
     * 唤醒后的下一步动作芯片：每种类型先出一个（按优先级），再补同类第二个；
     * 不足 {@link #MIN_CHIPS} 个时补一个"复制"兜底；最多 {@link #MAX_CHIPS} 个。
     * 时间只收"日期级"（置信度≥0.6）的，避免聊天列表的时间戳刷屏；金额不单独出芯片。
     */
    public static List<ScreenAction> planChips(List<ScreenEntity> entities) {
        List<ScreenAction> out = new ArrayList<ScreenAction>();
        if (entities == null || entities.isEmpty()) return out;
        Set<String> seen = new HashSet<String>();
        // 第一轮：每类一个
        for (ScreenEntity.Type t : CHIP_PRIORITY) {
            if (out.size() >= MAX_CHIPS) break;
            ScreenEntity e = firstUsable(entities, t, seen);
            if (e == null) continue;
            ScreenAction a = map(primaryAction(e), e);
            if (a != null && seen.add(key(a))) out.add(a);
        }
        // 第二轮：同类补位（例如两个地址）
        for (ScreenEntity.Type t : CHIP_PRIORITY) {
            if (out.size() >= MAX_CHIPS) break;
            for (ScreenEntity e : entities) {
                if (out.size() >= MAX_CHIPS) break;
                if (e.type != t || !chipWorthy(e)) continue;
                ScreenAction a = map(primaryAction(e), e);
                if (a != null && seen.add(key(a))) out.add(a);
            }
        }
        // 兜底：不足 2 个时，给第一个实体补"复制"
        if (out.size() > 0 && out.size() < MIN_CHIPS) {
            ScreenEntity e = out.get(0).entity;
            if (out.get(0).action != DeixisResolver.Action.COPY) {
                ScreenAction c = map(DeixisResolver.Action.COPY, e);
                if (c != null && seen.add(key(c))) out.add(c);
            }
        }
        return out;
    }

    private static ScreenEntity firstUsable(List<ScreenEntity> entities, ScreenEntity.Type t, Set<String> seen) {
        for (ScreenEntity e : entities) {
            if (e.type == t && chipWorthy(e)) return e;
        }
        return null;
    }

    private static boolean chipWorthy(ScreenEntity e) {
        if (e.type == ScreenEntity.Type.DATETIME) return e.confidence >= 0.6 && e.beginMs > 0;
        if (e.type == ScreenEntity.Type.AMOUNT) return false;
        return e.confidence >= 0.5;
    }

    private static String key(ScreenAction a) {
        return a.sign + "|" + a.args.toString();
    }

    /** 只放行 http/https/mailto；其余（intent:、file:、javascript:）一律拒绝。 */
    public static String safeUrl(String u) {
        if (u == null) return null;
        String t = u.trim();
        String lower = t.toLowerCase();
        if (!(lower.startsWith("http://") || lower.startsWith("https://") || lower.startsWith("mailto:"))) return null;
        if (t.length() > 1000) return null;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c < 0x21 || c == '"' || c == '\'' || c == '`' || c == '\\' || c == '<' || c == '>') return null;
        }
        return t;
    }

    /** 日程标题：实体所在行去掉时间片段与标点，截 20 字；取不到用"屏幕日程"。 */
    static String calendarTitle(ScreenEntity e) {
        String line = e.contextLine;
        if (line == null) return "屏幕日程";
        String t = line;
        if (e.raw != null) t = t.replace(e.raw, " ");
        t = t.replaceAll("(?:会议时间|活动时间|开始时间|时间|日期)[:：]?", " ");
        t = t.replaceAll("[\\s:：,，。；;!！?？【】\\[\\]()（）\"“”]+", " ").trim();
        if (t.length() == 0) return "屏幕日程";
        return t.length() > 20 ? t.substring(0, 20) : t;
    }
}
