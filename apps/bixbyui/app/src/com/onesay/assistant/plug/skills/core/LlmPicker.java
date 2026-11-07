/*
 * OneSay 自写（skill-pack，2026-11-02），非上游移植。core 包只依赖 java.*（模型走 plug.api.ModelPort）。
 */
package com.onesay.assistant.plug.skills.core;

import com.onesay.assistant.plug.api.ModelPort;

import java.util.List;
import java.util.Locale;

/**
 * LLM 兜底挑技能（<b>可选，默认关</b>：会多一次模型调用、多几百毫秒到一秒延迟）。只在规则一个都没命中、也没有保持的技能时由宿主调用。
 * 给模型的是技能目录（id + 一句说明），要它只回一个 id 或 none；回答不在目录里一律当 none。
 */
public final class LlmPicker {
    private LlmPicker() {
    }

    public static final String SYSTEM = "你是技能路由器。下面是技能目录（每行 id：说明）。看用户这句话，"
            + "如果明显需要其中某个技能的做法，只回这个 id；都不需要就只回 none。不要解释。";

    public static String catalog(List<Skill> skills, String path) {
        StringBuilder b = new StringBuilder();
        for (Skill s : skills) {
            if (!s.forPath(path)) continue;
            b.append(s.id).append("：").append(s.description.length() > 0 ? s.description : s.name).append('\n');
        }
        return b.toString();
    }

    /** @return 选中的技能，没有 / 失败 / 超时返回 null。 */
    public static Skill pick(ModelPort model, SkillIndex index, String query, String path, int timeoutMs) {
        if (model == null || index == null || query == null || query.trim().length() == 0) return null;
        String cat = catalog(index.skills(), path);
        if (cat.length() == 0) return null;
        String out;
        try {
            out = model.complete(SYSTEM + "\n\n" + cat, query, 16, timeoutMs);
        } catch (RuntimeException e) {
            return null;
        }
        return parse(index, out, path);
    }

    static Skill parse(SkillIndex index, String out, String path) {
        if (out == null) return null;
        String t = out.trim().toLowerCase(Locale.ROOT);
        int sp = 0;
        while (sp < t.length() && (Character.isLetterOrDigit(t.charAt(sp)) || t.charAt(sp) == '_')) sp++;
        String id = t.substring(0, sp);
        if (id.length() == 0 || "none".equals(id)) return null;
        Skill s = index.byId(id);
        return s != null && s.forPath(path) ? s : null;
    }
}
