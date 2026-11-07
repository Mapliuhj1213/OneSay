/*
 * Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101,
 * licensed under the Apache License, Version 2.0 (see assets/LICENSE-APACHE-2.0.txt and NOTICE).
 * Modified by OneSay: translated from Kotlin (core/uistate/Accuracy.kt) to Java 7; suspend settle replaced by
 * a Runnable, Relocation sealed class replaced by an int result. Upstream: ebb0e44.
 */
package com.onesay.assistant.plug.screen.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** 元素找回（relocate / stationary）与输入读回核对（typeAndVerify）。纯函数。 */
public final class Accuracy {
    private Accuracy() {
    }

    /** 找回时允许的最大中心偏移：屏幕短边的这个比例。 */
    public static final double RELOCATE_MAX_SHIFT = 0.15;
    /** 「还在原地」的偏移上限：屏幕短边的这个比例。 */
    public static final double SAME_SPOT_SHIFT = 0.02;

    /** relocate 结果：≥0 = 新列表下标；其余见常量。 */
    public static final int MOVED = -1, AMBIGUOUS = -2, GONE = -3;

    /**
     * 编号过期后在 after 里找 previous 的唯一对应者。类型、名字、资源 ID 相同且新旧都只有这一个；上下文相同；
     * 中心挪动不超过短边 15%。页面整体没动时放宽：上下文变了但元素原地不动（不在列表里）照样算；名字只差数字和空白
     * （「跳过 3」→「跳过 2」）或旧名字是轮换的提示语，同 rid 同类型原地唯一，也算。
     */
    public static int relocate(ElementLocator previous, List<ElementLocator> before, List<ElementLocator> after, int shortSide) {
        if (previous.name.trim().length() == 0 && blank(previous.resourceId)) return AMBIGUOUS;
        double spot = shortSide * SAME_SPOT_SHIFT;
        List<Integer> cand = new ArrayList<Integer>();
        for (int i = 0; i < after.size(); i++) if (after.get(i).sameKey(previous)) cand.add(i);
        if (cand.isEmpty()) {
            if (blank(previous.resourceId) || !stationary(before, after, spot)) return GONE;
            int found = -1, n = 0;
            for (int i = 0; i < after.size(); i++) {
                ElementLocator e = after.get(i);
                if (e.role.equals(previous.role) && ElementLocator.eq(e.resourceId, previous.resourceId) && shift(e, previous) <= spot) {
                    found = i;
                    n++;
                }
            }
            if (n != 1) return GONE;
            boolean alike = previous.hint || stripDigits(after.get(found).name).equals(stripDigits(previous.name));
            return alike ? found : GONE;
        }
        int sameBefore = 0;
        for (ElementLocator b : before) if (b.sameKey(previous)) sameBefore++;
        if (cand.size() > 1 || sameBefore > 1) return AMBIGUOUS;
        int index = cand.get(0);
        ElementLocator found = after.get(index);
        double moved = shift(found, previous);
        if (moved > shortSide * RELOCATE_MAX_SHIFT) return MOVED;
        if (!ElementLocator.eq(found.context, previous.context) && !(moved <= spot && !previous.inList && stationary(before, after, spot))) return MOVED;
        return index;
    }

    /** 页面整体没动：前后都唯一出现的有名元素至少 2 个，其中八成以上还在原地。 */
    public static boolean stationary(List<ElementLocator> before, List<ElementLocator> after, double spot) {
        Map<String, List<ElementLocator>> a = group(after), b = group(before);
        int shared = 0, inPlace = 0;
        for (Map.Entry<String, List<ElementLocator>> en : b.entrySet()) {
            List<ElementLocator> al = a.get(en.getKey());
            if (al == null || al.size() != 1 || en.getValue().size() != 1) continue;
            shared++;
            if (shift(en.getValue().get(0), al.get(0)) <= spot) inPlace++;
        }
        return shared >= 2 && inPlace >= shared * 0.8;
    }

    private static Map<String, List<ElementLocator>> group(List<ElementLocator> ls) {
        Map<String, List<ElementLocator>> m = new HashMap<String, List<ElementLocator>>();
        for (ElementLocator e : ls) {
            if (e.name.trim().length() == 0) continue;
            String k = e.role + "\u0000" + e.name + "\u0000" + (e.resourceId == null ? "" : e.resourceId);
            List<ElementLocator> l = m.get(k);
            if (l == null) m.put(k, l = new ArrayList<ElementLocator>());
            l.add(e);
        }
        return m;
    }

    static double shift(ElementLocator a, ElementLocator b) {
        return Math.hypot(a.centerX - b.centerX, a.centerY - b.centerY);
    }

    static String stripDigits(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!Character.isDigit(c) && !Character.isWhitespace(c)) b.append(c);
        }
        return b.toString();
    }

    static boolean blank(String s) {
        return s == null || s.trim().length() == 0;
    }

    // ------------------------------------------------------------------ 输入读回

    public enum Verification { MATCH, MISMATCH, UNKNOWN }

    /** 输入框读写。read 返回用户可见的实际文字（显示提示语时为空串），读不出为 null。 */
    public interface TextField {
        String read();

        boolean write(String text);
    }

    /** 输入后界面冒出含所输文字的新文字（联想词等）。 */
    public interface Reacted {
        boolean reacted();
    }

    static String norm(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) if (!Character.isWhitespace(s.charAt(i))) b.append(s.charAt(i));
        return b.toString();
    }

    /**
     * 写入并读回核对，不一致重试，最多 attempts 次；settle 在每次写入后等输入框更新。写入前就是目标时先清空，
     * 清不掉返回 UNKNOWN。每次读回都和写入前一样、至少写成过一次、而 reacted 说界面冒出了含所输文字的新文字时返回 UNKNOWN。
     * secret（密码框）只写一次、不读回。
     */
    public static Verification typeAndVerify(TextField field, String text, int attempts, Runnable settle, Reacted reacted, boolean secret) {
        if (secret) {
            field.write(text);
            return Verification.UNKNOWN;
        }
        String want = norm(text);
        String baseline = field.read();
        if (baseline == null) {
            field.write(text);
            return Verification.UNKNOWN;
        }
        if (want.length() > 0 && norm(baseline).equals(want)) {
            field.write("");
            if (settle != null) settle.run();
            baseline = field.read();
            if (baseline == null) return Verification.UNKNOWN;
            if (norm(baseline).equals(want)) {
                field.write(text);
                return Verification.UNKNOWN;
            }
        }
        boolean stuck = true, wrote = false;
        for (int i = 0; i < attempts; i++) {
            if (!field.write(text)) continue;
            wrote = true;
            if (settle != null) settle.run();
            String actual = field.read();
            if (actual == null) return Verification.UNKNOWN;
            if (norm(actual).equals(want)) return Verification.MATCH;
            if (!actual.equals(baseline)) stuck = false;
        }
        return wrote && stuck && reacted != null && reacted.reacted() ? Verification.UNKNOWN : Verification.MISMATCH;
    }
}
