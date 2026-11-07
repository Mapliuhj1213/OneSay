package com.onesay.assistant.v2.router;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.v2.executor.ExecutionResult;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * D3（2026-10-22）：只读工具跳过第二轮大模型 —— 本机数值类只读工具的结果直接套模板出答复。
 *
 * <h3>为什么</h3>
 * <p>AgentLoop 里「电量还剩多少」这类句子要两轮模型：第 1 轮决定调 {@code battery.level}，
 * 第 2 轮把 {@code level: 85} 改写成一句话（R14 实测第 2 轮约 1.3 s）。读数本身是确定的，
 * 第 2 轮只是换个说法，省掉它不丢信息。
 *
 * <h3>只对这些情况生效（任何一条不满足都退回原流程，行为与开关关闭时一致）</h3>
 * <ol>
 *   <li>开关打开（SharedPreferences {@code bixby_agent / readonly_direct}，默认开）；</li>
 *   <li>是任务的<b>第一批</b>工具调用（之前没有任何步骤），且没有注入屏幕上下文；</li>
 *   <li>这一批里<b>每一个</b>调用都在 {@link #WHITELIST} 里、都真实执行成功（没被拦、没要确认、没跳过）；</li>
 *   <li>用户原话是纯粹的「问读数」，不带条件 / 动作 / 评价（{@link #queryAllows}）——
 *       「电量低于 20 就开省电」第 2 轮要做决定，不能跳；</li>
 *   <li>每个结果都能解析出合法数值（{@link #render}）；解析不出来就让模型去说。</li>
 * </ol>
 *
 * <h3>白名单（本机、只读、数值类）</h3>
 * 电量、四种音量（媒体 / 铃声 / 通知 / 闹钟）、屏幕亮度、息屏时长、字体缩放。
 * 开关类（省电模式开没开）、列表类（闹钟、邮件）、联网类都<b>不在</b>名单里。
 */
public final class ReadOnlyAnswer {

    private ReadOnlyAnswer() {}

    public static final String PREFS = "bixby_agent";
    public static final String K_ENABLED = "readonly_direct";

    /** sign → 中文名（同时是白名单）。顺序即文档顺序。 */
    public static final Map<String, String> WHITELIST;
    static {
        Map<String, String> m = new LinkedHashMap<String, String>();
        m.put("battery.level", "电量");
        m.put("volume.media.get", "媒体音量");
        m.put("volume.ring.get", "铃声音量");
        m.put("volume.notif.get", "通知音量");
        m.put("volume.alarm.state", "闹钟音量");
        m.put("settings.brightness.get", "屏幕亮度");
        m.put("settings.screentimeout.get", "息屏时间");
        m.put("settings.font.get", "字体缩放");
        WHITELIST = Collections.unmodifiableMap(m);
    }

    public static boolean isWhitelisted(String sign) {
        return sign != null && WHITELIST.containsKey(sign);
    }

    // ------------------------------------------------------------------ 开关

    /** JVM 单测 / 无 Context 时用的值；null = 默认（开）。 */
    private static volatile Boolean sOverride = null;

    /** 仅测试用：强制开 / 关；传 null 恢复默认。 */
    public static void setOverrideForTest(Boolean v) { sOverride = v; }

    public static boolean isEnabled(Context ctx) {
        Boolean o = sOverride;
        if (o != null) return o.booleanValue();
        if (ctx == null) return true;
        try {
            return prefs(ctx).getBoolean(K_ENABLED, true);
        } catch (Throwable t) {
            return true;
        }
    }

    public static void setEnabled(Context ctx, boolean on) {
        if (ctx == null) return;
        prefs(ctx).edit().putBoolean(K_ENABLED, on).apply();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------------ 原话判定

    /**
     * 带这些词的句子第 2 轮可能要做判断 / 动作 / 解释，一律不跳（宁可多一轮也不答错）。
     * 「调到多少了」这种误伤只是退回原流程，不影响正确性。
     */
    private static final String[] DENY_CUES = {
            "如果", "要是", "假如", "的话", "就", "然后", "再", "并且", "并", "同时", "顺便", "接着",
            "帮我", "给我调", "调", "设", "改", "打开", "关", "开启", "开到", "静音", "提醒", "通知我",
            "低于", "高于", "超过", "不到", "少于", "多于", "以下", "以上",
            "够", "比", "为什么", "怎么", "咋", "建议", "应该", "该不该", "能不能", "可以吗",
            "合适", "正常", "太", "会不会", "多久能", "还能用", "充满", "耗电",
    };

    /** 用户原话是不是纯粹「问读数」。空句不算。 */
    public static boolean queryAllows(String query) {
        if (query == null) return false;
        String q = query.trim();
        if (q.length() == 0) return false;
        for (String c : DENY_CUES) {
            if (q.contains(c)) {
                if (allowedCompound(q, c)) continue;   // 「电量百分比」「关于亮度」不算
                return false;
            }
        }
        return true;
    }

    /** 含线索字但没有动作 / 比较含义的固定词：去掉它们后线索还在才算命中。 */
    private static final String[] HARMLESS = { "关于", "百分比" };

    private static boolean allowedCompound(String q, String cue) {
        String stripped = q;
        for (String h : HARMLESS) stripped = stripped.replace(h, "");
        return !stripped.contains(cue);
    }

    // ------------------------------------------------------------------ 模板

    private static final Pattern P_LEVEL = Pattern.compile("level:\\s*(\\d{1,3})");
    private static final Pattern P_VOLUME = Pattern.compile(
            "volume is\\s*(\\d{1,3})(?:\\s*in range\\s*\\[(\\d{1,3})\\.\\.(\\d{1,3})\\])?");
    private static final Pattern P_INT = Pattern.compile("^\\s*(\\d{1,10})\\s*$");
    private static final Pattern P_FLOAT = Pattern.compile("^\\s*(\\d{1,2}(?:\\.\\d{1,3})?)\\s*$");

    /** 亮度满格（Android 8 / Note 8 screen_brightness 0..255）。 */
    static final int BRIGHTNESS_MAX = 255;

    /**
     * 把一个白名单工具的执行结果套成一句话；不在名单、没成功、解析不出合法数值都返回 null。
     * 只读原始输出（{@link ExecutionResult#detail}）；detail 为空时退到 userMessage 里再找。
     */
    public static String render(String sign, ExecutionResult r) {
        if (!isWhitelisted(sign) || r == null || !r.ok) return null;
        String raw = r.detail != null && r.detail.trim().length() > 0 ? r.detail : r.userMessage;
        return renderRaw(sign, raw);
    }

    static String renderRaw(String sign, String raw) {
        if (!isWhitelisted(sign) || raw == null) return null;
        String out = raw.trim();
        if (out.length() == 0) return null;
        try {
            if ("battery.level".equals(sign)) {
                Matcher m = P_LEVEL.matcher(out);
                if (!m.find()) return null;
                int v = Integer.parseInt(m.group(1));
                if (v < 0 || v > 100) return null;
                return "当前电量 " + v + "%。";
            }
            if (sign.startsWith("volume.")) {
                Matcher m = P_VOLUME.matcher(out);
                if (!m.find()) return null;
                int v = Integer.parseInt(m.group(1));
                if (v < 0 || v > 100) return null;
                StringBuilder sb = new StringBuilder("当前").append(WHITELIST.get(sign)).append("是 ").append(v);
                if (m.group(3) != null) {
                    int max = Integer.parseInt(m.group(3));
                    if (max > 0 && v <= max) sb.append("（最大 ").append(max).append("）");
                }
                if (v == 0) sb.append("，已静音");
                return sb.append("。").toString();
            }
            if ("settings.brightness.get".equals(sign)) {
                Matcher m = P_INT.matcher(out);
                if (!m.matches()) return null;
                long v = Long.parseLong(m.group(1));
                if (v < 0 || v > BRIGHTNESS_MAX) return null;
                long pct = Math.round(v * 100.0 / BRIGHTNESS_MAX);
                return "当前屏幕亮度 " + v + "（满格 " + BRIGHTNESS_MAX + "，约 " + pct + "%）。";
            }
            if ("settings.screentimeout.get".equals(sign)) {
                Matcher m = P_INT.matcher(out);
                if (!m.matches()) return null;
                long ms = Long.parseLong(m.group(1));
                // 低于 1 秒或超过 1 小时（三星「永不」= Integer.MAX_VALUE）交给模型解释
                if (ms < 1000 || ms > 3600000L) return null;
                long sec = ms / 1000;
                String d;
                if (sec < 60) d = sec + " 秒";
                else if (sec % 60 == 0) d = (sec / 60) + " 分钟";
                else d = (sec / 60) + " 分 " + (sec % 60) + " 秒";
                return "屏幕 " + d + "无操作后自动关闭。";
            }
            if ("settings.font.get".equals(sign)) {
                Matcher m = P_FLOAT.matcher(out);
                if (!m.matches()) return null;
                double v = Double.parseDouble(m.group(1));
                if (v <= 0 || v > 5) return null;
                return "当前字体缩放 " + m.group(1) + " 倍（标准为 1.0）。";
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return null;
    }
}
