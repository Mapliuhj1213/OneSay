/*
 * OneSay 自写（skill-pack，2026-11-02），非上游移植。core 包只依赖 java.*。
 */
package com.onesay.assistant.plug.skills.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 技能索引：逐份 {@link #add} 文件，坏文件不抛异常（记进 {@link #problems()}，其他技能照常可用）；
 * 重复 id 只留第一份；{@code status: draft} 的草稿永不进索引。
 */
public final class SkillIndex {
    private final Map<String, Skill> byId = new LinkedHashMap<String, Skill>();
    private final List<String> problems = new ArrayList<String>();

    /** @return 加进去的技能；坏文件 / 草稿 / 重复返回 null。 */
    public Skill add(String fileName, String markdown) {
        Skill s;
        try {
            s = Skill.parse(fileName, markdown);
        } catch (Skill.ParseError e) {
            problems.add(e.getMessage());
            return null;
        } catch (RuntimeException e) {
            problems.add(fileName + "：解析异常 " + e);
            return null;
        }
        if (s.isDraft()) {
            problems.add(fileName + "：草稿（status: draft），不加载");
            return null;
        }
        if (byId.containsKey(s.id)) {
            problems.add(fileName + "：id 重复「" + s.id + "」，忽略");
            return null;
        }
        String want = "skill_" + s.id + ".md";
        if (fileName != null && fileName.length() > 0 && !fileName.endsWith(want))
            problems.add(fileName + "：文件名应为 " + want);
        for (String w : s.warnings) problems.add(fileName + "：" + w);
        byId.put(s.id, s);
        return s;
    }

    public List<Skill> skills() {
        return Collections.unmodifiableList(new ArrayList<Skill>(byId.values()));
    }

    public Skill byId(String id) {
        return byId.get(id);
    }

    public int size() {
        return byId.size();
    }

    public List<String> problems() {
        return Collections.unmodifiableList(problems);
    }
}
