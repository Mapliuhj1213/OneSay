package com.onesay.assistant.v2.vision.screenref;

import org.json.JSONObject;

/**
 * [screenref] 屏幕实体：从界面文字里抽出来的一个可操作对象（电话/地址/时间/链接/单号/取件码/邮箱/金额）。
 *
 * <p>纯 Java（运行期不碰 android.*），可在 JVM 单测里直接构造与断言。
 */
public final class ScreenEntity {

    /** 实体类型。{@link #label} 是给用户看的中文名。 */
    public enum Type {
        PHONE("电话"),
        ADDRESS("地址"),
        DATETIME("时间"),
        URL("链接"),
        TRACKING("快递单号"),
        CODE("取件码"),
        EMAIL("邮箱"),
        AMOUNT("金额");

        public final String label;

        Type(String label) {
            this.label = label;
        }
    }

    public final Type type;
    /** 规范化后的值：电话=纯数字（可含前导 +）、URL=原样、时间=原文、单号=大写。 */
    public final String value;
    /** 原文片段（界面上实际显示的样子）。 */
    public final String raw;
    /** 0~1 的置信度（规则打分；歧义裁决用）。 */
    public final double confidence;
    /** 在原文行序列里的序号（行号 * 1000 + 行内偏移），用于"上面的/第一个"等位置指代。 */
    public final int order;
    /** 细分标签：取件码/取餐码/验证码；快递公司；"手机"/"座机"/"客服" 等。可为 null。 */
    public final String subLabel;
    /** 仅 DATETIME：起始时刻（epoch ms），无法定位到具体时刻时为 0。 */
    public final long beginMs;
    /** 仅 DATETIME：结束时刻（epoch ms），未给出时为 0。 */
    public final long endMs;
    /** 仅 DATETIME：是否只有日期没有钟点（全天）。 */
    public final boolean allDay;
    /** 来源节点在屏幕上的中心点 y（像素，-1 = 未知），用于"上面的"判定。 */
    public final int screenY;
    /** 实体所在的原文行（截断到 80 字），用于日程标题、给模型看的上下文。可为 null。 */
    public final String contextLine;

    public ScreenEntity(Type type, String value, String raw, double confidence, int order,
                        String subLabel, long beginMs, long endMs, boolean allDay, int screenY) {
        this(type, value, raw, confidence, order, subLabel, beginMs, endMs, allDay, screenY, null);
    }

    public ScreenEntity(Type type, String value, String raw, double confidence, int order,
                        String subLabel, long beginMs, long endMs, boolean allDay, int screenY, String contextLine) {
        this.type = type;
        this.value = value;
        this.raw = raw;
        this.confidence = confidence;
        this.order = order;
        this.subLabel = subLabel;
        this.beginMs = beginMs;
        this.endMs = endMs;
        this.allDay = allDay;
        this.screenY = screenY;
        this.contextLine = contextLine;
    }

    public static ScreenEntity simple(Type type, String value, String raw, double confidence, int order, String subLabel) {
        return new ScreenEntity(type, value, raw, confidence, order, subLabel, 0L, 0L, false, -1);
    }

    /** 拷贝并替换屏幕坐标。 */
    public ScreenEntity withScreenY(int y) {
        return new ScreenEntity(type, value, raw, confidence, order, subLabel, beginMs, endMs, allDay, y, contextLine);
    }

    /** 拷贝并附上原文行（超长截断）。 */
    public ScreenEntity withContext(String line) {
        String c = line == null ? null : (line.length() > 80 ? line.substring(0, 80) : line);
        return new ScreenEntity(type, value, raw, confidence, order, subLabel, beginMs, endMs, allDay, screenY, c);
    }

    /** 拷贝并替换序号。 */
    public ScreenEntity withOrder(int newOrder) {
        return new ScreenEntity(type, value, raw, confidence, newOrder, subLabel, beginMs, endMs, allDay, screenY, contextLine);
    }

    /** 给用户看的名字：取件码/取餐码等用细分标签，其余用类型名。 */
    public String kindLabel() {
        if (type == Type.CODE && subLabel != null) return subLabel;
        return type.label;
    }

    /** 芯片/候选里显示的短文本（超长截断）。 */
    public String shortDisplay(int max) {
        String s = (type == Type.DATETIME || type == Type.ADDRESS) && raw != null ? raw : value;
        if (s == null) return "";
        s = s.trim();
        if (s.length() <= max) return s;
        return s.substring(0, Math.max(1, max - 1)) + "…";
    }

    /** 去重键：类型 + 规范值。 */
    public String dedupKey() {
        return type.name() + "|" + (value == null ? "" : value);
    }

    public JSONObject toJson(int id) {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("type", type.name().toLowerCase());
            o.put("kind", kindLabel());
            o.put("value", value);
            if (raw != null && !raw.equals(value)) o.put("raw", raw);
            if (subLabel != null) o.put("sub", subLabel);
            if (type == Type.DATETIME && beginMs > 0) {
                o.put("begin_ms", beginMs);
                if (endMs > 0) o.put("end_ms", endMs);
                if (allDay) o.put("all_day", true);
            }
        } catch (Exception ignored) {
        }
        return o;
    }

    @Override
    public String toString() {
        return type + "(" + value + (subLabel != null ? "," + subLabel : "") + ")";
    }
}
