package com.onesay.assistant.v2.vision.screenref;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * [screenref] 一次唤醒时刻的屏幕快照：前台 App/Activity + 文字树 + 抽出的实体。纯 Java（JVM 可测）。
 *
 * <p>来源 {@link #source}：
 * <ul>
 *   <li>{@code uiautomator}：root 下 {@code uiautomator dump} 的结构化文字树（精确、零网络，首选）；</li>
 *   <li>{@code vl}：dump 失败（FLAG_SECURE / WebView / Canvas 自绘）时，走既有截图 + Qwen-VL 路径拿到的文字；</li>
 *   <li>{@code none}：两者都没有（只有前台 App 信息或完全失败）。</li>
 * </ul>
 */
public final class ScreenContext {

    public static final String SRC_UIAUTOMATOR = "uiautomator";
    public static final String SRC_VL = "vl";
    public static final String SRC_NONE = "none";
    /** ★ r2/sysux P2：默认数字助理拿到的 AssistStructure（系统直接给，不经 root / uiautomator）。 */
    public static final String SRC_ASSIST = "assist";

    /** dump 至少要有这么多条有文字的节点才算"可用"，否则判定为 WebView/自绘界面，需要 VL 兜底。 */
    public static final int MIN_TEXT_NODES = 2;

    public final String pkg;
    public final String activity;
    public final String source;
    /** 采集完成时刻（wall clock ms）。 */
    public final long capturedAtMs;
    /** 采集耗时（ms）。 */
    public final long costMs;
    public final List<UiNode> nodes;
    public final List<String> texts;
    public final List<ScreenEntity> entities;
    /** 失败/降级原因（成功时 null）。 */
    public final String error;
    /** 文字树不可用、需要（或可以）用 VL 兜底。 */
    public final boolean needsVl;
    public final int redacted;

    public ScreenContext(String pkg, String activity, String source, long capturedAtMs, long costMs,
                         List<UiNode> nodes, List<String> texts, List<ScreenEntity> entities,
                         String error, boolean needsVl, int redacted) {
        this.pkg = pkg;
        this.activity = activity;
        this.source = source;
        this.capturedAtMs = capturedAtMs;
        this.costMs = costMs;
        this.nodes = nodes == null ? Collections.<UiNode>emptyList() : nodes;
        this.texts = texts == null ? Collections.<String>emptyList() : texts;
        this.entities = entities == null ? Collections.<ScreenEntity>emptyList() : entities;
        this.error = error;
        this.needsVl = needsVl;
        this.redacted = redacted;
    }

    /**
     * 由 dump 原文构建（纯函数，单测入口）。
     *
     * @param xml        uiautomator dump 文件内容（null = dump 失败）
     * @param ownPackage 自家包名（其节点被丢弃）
     * @param dumpsysOut dumpsys activity 的相关输出（解析前台 App），可为 null
     */
    public static ScreenContext fromDump(String xml, String ownPackage, String dumpsysOut,
                                         long nowMs, TimeZone tz, long costMs) {
        String[] fg = UiDumpParser.parseResumedActivity(dumpsysOut);
        String pkg = fg != null ? fg[0] : null;
        String act = fg != null ? fg[1] : null;
        if (xml == null || xml.trim().length() == 0) {
            return new ScreenContext(pkg, act, SRC_NONE, nowMs, costMs, null, null, null,
                    "uiautomator dump 无输出（可能超时/被拒）", true, 0);
        }
        UiDumpParser.Result r = UiDumpParser.parse(xml, ownPackage);
        if (pkg == null && r.rootPackage != null && !r.rootPackage.equals(ownPackage)) pkg = r.rootPackage;
        if (r.rootPackage != null && r.rootPackage.equals(ownPackage) && r.nodes.isEmpty()) {
            return new ScreenContext(pkg, act, SRC_NONE, nowMs, costMs, null, null, null,
                    "dump 到的是自家面板（面板抢了焦点）", true, r.redacted);
        }
        List<String> texts = new ArrayList<String>();
        for (UiNode n : r.nodes) {
            String l = n.label().trim();
            if (l.length() > 0 && !texts.contains(l)) texts.add(l);
        }
        List<ScreenEntity> ents = EntityExtractor.extractFromNodes(r.nodes, nowMs, tz);
        boolean usable = r.textNodeCount() >= MIN_TEXT_NODES;
        return new ScreenContext(pkg, act, usable ? SRC_UIAUTOMATOR : SRC_NONE, nowMs, costMs, r.nodes, texts, ents,
                usable ? null : "文字节点过少（" + r.textNodeCount() + "），疑似 WebView/自绘/安全界面", !usable, r.redacted);
    }

    /**
     * ★ r2/sysux P2：由 AssistStructure 展平后的节点构建（纯函数；节点已按 UiDumpParser 同规则去掉密码框 / 打码）。
     * 文字节点少于 {@link #MIN_TEXT_NODES} 时 source=none + needsVl（调用方回退 uiautomator / 截图）。
     */
    public static ScreenContext fromAssistNodes(String pkg, String activity, List<UiNode> nodes, int redacted,
                                                long nowMs, TimeZone tz, long costMs) {
        List<String> texts = new ArrayList<String>();
        int textNodes = 0;
        if (nodes != null) {
            for (UiNode n : nodes) {
                String l = n.label().trim();
                if (l.length() == 0) continue;
                textNodes++;
                if (!texts.contains(l)) texts.add(l);
            }
        }
        boolean usable = textNodes >= MIN_TEXT_NODES;
        List<ScreenEntity> ents = nodes == null ? null : EntityExtractor.extractFromNodes(nodes, nowMs, tz);
        return new ScreenContext(pkg, activity, usable ? SRC_ASSIST : SRC_NONE, nowMs, costMs, nodes, texts, ents,
                usable ? null : "AssistStructure 文字节点过少（" + textNodes + "）", !usable, redacted);
    }

    /** 由 VL 返回的文字构建（按行切分后抽实体）。 */
    public static ScreenContext fromVlText(ScreenContext base, String vlText, long nowMs, TimeZone tz, long costMs) {
        List<String> lines = new ArrayList<String>();
        if (vlText != null) {
            for (String l : vlText.split("[\\r\\n]+")) {
                String t = l.replaceAll("^\\s*(?:[-*•·]|\\d+[.、)])\\s*", "").trim();
                if (t.length() > 0) lines.add(t);
            }
        }
        List<ScreenEntity> ents = EntityExtractor.extractFromTexts(lines, nowMs, tz);
        // VL 抽到的实体可靠性低一档
        List<ScreenEntity> lowered = new ArrayList<ScreenEntity>();
        for (ScreenEntity e : ents) {
            lowered.add(new ScreenEntity(e.type, e.value, e.raw, e.confidence * 0.9, e.order, e.subLabel,
                    e.beginMs, e.endMs, e.allDay, e.screenY, e.contextLine));
        }
        return new ScreenContext(base != null ? base.pkg : null, base != null ? base.activity : null,
                lines.isEmpty() ? SRC_NONE : SRC_VL, nowMs, costMs, null, lines, lowered,
                lines.isEmpty() ? "VL 未返回文字" : null, false, base != null ? base.redacted : 0);
    }

    public boolean isFresh(long nowMs, long ttlMs) {
        return nowMs - capturedAtMs >= 0 && nowMs - capturedAtMs <= ttlMs;
    }

    public boolean hasEntities() {
        return !entities.isEmpty();
    }

    public List<ScreenEntity> entitiesOf(ScreenEntity.Type t) {
        List<ScreenEntity> r = new ArrayList<ScreenEntity>();
        for (ScreenEntity e : entities) if (e.type == t) r.add(e);
        return r;
    }

    /** 给 AgentLoop 工具 screen.context 的紧凑观测（不含原始文字树，控制 token）。 */
    public JSONObject toCompactJson(int maxEntities, int maxTexts) {
        JSONObject o = new JSONObject();
        try {
            o.put("available", !SRC_NONE.equals(source) || hasEntities());
            if (pkg != null) o.put("app", pkg);
            if (activity != null) o.put("activity", activity);
            o.put("source", source);
            JSONArray arr = new JSONArray();
            for (int i = 0; i < entities.size() && i < maxEntities; i++) {
                arr.put(entities.get(i).toJson(i + 1));
            }
            o.put("entities", arr);
            if (maxTexts > 0 && !texts.isEmpty()) {
                JSONArray ta = new JSONArray();
                int budget = 600;
                for (int i = 0; i < texts.size() && ta.length() < maxTexts && budget > 0; i++) {
                    String t = texts.get(i);
                    if (t.length() > 80) t = t.substring(0, 80) + "…";
                    ta.put(t);
                    budget -= t.length();
                }
                o.put("texts", ta);
            }
            if (redacted > 0) o.put("redacted", redacted);
            if (error != null) o.put("note", error);
        } catch (Exception ignored) {
        }
        return o;
    }

    /** 自动注入给模型的一段中文提示（指代命中时附在用户话后面）。 */
    public String promptHint(int maxEntities) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n\n[屏幕上下文（唤醒瞬间采集，用户说的“这个/这里/屏幕上”指的就是这些）]");
        if (pkg != null) sb.append("\n前台应用：").append(pkg);
        if (entities.isEmpty()) {
            sb.append("\n未识别到电话/地址/时间/链接/单号等实体。");
        } else {
            for (int i = 0; i < entities.size() && i < maxEntities; i++) {
                ScreenEntity e = entities.get(i);
                sb.append("\n").append(i + 1).append(". ").append(e.kindLabel()).append("：").append(e.value);
                if (e.type == ScreenEntity.Type.DATETIME && e.raw != null && !e.raw.equals(e.value)) sb.append("（").append(e.raw).append("）");
                if (e.subLabel != null && e.type != ScreenEntity.Type.CODE) sb.append("（").append(e.subLabel).append("）");
            }
        }
        sb.append("\n如需更多屏幕文字可调用 screen__context 工具；若用户所指不唯一，请先列出候选让用户选择。");
        return sb.toString();
    }

    @Override
    public String toString() {
        return "ScreenContext{" + source + " app=" + pkg + " texts=" + texts.size() + " entities=" + entities + (error != null ? " err=" + error : "") + "}";
    }
}
