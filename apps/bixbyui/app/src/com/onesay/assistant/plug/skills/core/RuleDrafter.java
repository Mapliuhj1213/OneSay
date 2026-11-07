/*
 * OneSay 自写（skill-pack，2026-11-02），非上游移植。core 包只依赖 java.*。
 */
package com.onesay.assistant.plug.skills.core;

import com.onesay.assistant.plug.skill.core.SkillLearner;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 纯规则的草稿生成：只记工具顺序与参数<b>键名</b>（宿主传进来的 steps 形如 {@code app.launch(app)}，不带参数值），
 * 目标文本把 4 位以上数字串、邮箱、链接打码，防止把隐私写进文件。只沉淀 outcome = done 且 ≥ 2 步的任务。
 */
public final class RuleDrafter implements SkillDrafter {
    static final Pattern DIGITS = Pattern.compile("\\d{4,}");
    static final Pattern MAIL = Pattern.compile("[\\w.+-]+@[\\w-]+(\\.[\\w-]+)+");
    static final Pattern URL = Pattern.compile("https?://\\S+");

    @Override public Draft draft(SkillLearner.Episode e, long nowMs) {
        if (e == null || !"done".equals(e.outcome) || e.steps.size() < 2) return null;
        String goal = redact(e.goal);
        Set<String> tools = new LinkedHashSet<String>();
        for (String s : e.steps) {
            String t = s == null ? "" : s;
            int p = t.indexOf('(');
            if (p > 0) t = t.substring(0, p);
            t = t.trim();
            if (Skill.ID.matcher(t.replace(".", "_")).matches()) tools.add(t);
        }
        String id = "draft_" + Long.toString(Math.abs(nowMs), 36);
        StringBuilder b = new StringBuilder();
        b.append("---\n");
        b.append("id: ").append(id).append('\n');
        b.append("name: 草稿：").append(clip(goal.replace(":", " "), 20)).append('\n');
        b.append("description: 自动沉淀的草稿，未审核\n");
        b.append("status: draft\n");
        b.append("priority: 10\n");
        b.append("budget: 400\n");
        b.append("triggers:\n");
        b.append("  examples: [\"").append(goal.replace("\"", "'").replace(",", "，")).append("\"]\n");
        b.append("requires:\n");
        b.append("  tools: [");
        int n = 0;
        for (String t : tools) b.append(n++ > 0 ? ", " : "").append(t);
        b.append("]\n");
        b.append("---\n");
        b.append("## 这次成功的做法（自动记录，需人工改写成通用步骤）\n");
        int i = 1;
        for (String s : e.steps) b.append(i++).append(". ").append(redact(s)).append('\n');
        if (e.app != null && e.app.length() > 0) b.append("\n涉及 App：").append(redact(e.app)).append('\n');
        b.append("\n## 待人工补\n- 触发关键词 / 正则\n- 坑与确认规则\n");
        return new Draft(id, b.toString());
    }

    public static String redact(String s) {
        if (s == null) return "";
        String t = URL.matcher(s).replaceAll("〔链接〕");
        t = MAIL.matcher(t).replaceAll("〔邮箱〕");
        t = DIGITS.matcher(t).replaceAll("〔数字〕");
        return t.replace("\n", " ").trim();
    }

    static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
