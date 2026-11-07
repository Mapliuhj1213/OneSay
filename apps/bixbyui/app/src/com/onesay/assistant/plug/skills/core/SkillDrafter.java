/*
 * OneSay 自写（skill-pack，2026-11-02），非上游移植。core 包只依赖 java.*。
 */
package com.onesay.assistant.plug.skills.core;

import com.onesay.assistant.plug.skill.core.SkillLearner;

/**
 * 技能沉淀（预留，<b>默认关、不自动启用</b>）：一次成功的任务 → 一份草稿技能 markdown（{@code status: draft}）。
 * 草稿只交给宿主落盘到 debug 目录给人看；{@link SkillIndex} 见到 {@code status: draft} 一律拒收，所以草稿就算被拷进 assets 也不会生效，
 * 要人工改写、去掉 draft 后才算技能。输入复用 {@link SkillLearner.Episode}。规则实现见 {@link RuleDrafter}。
 */
public interface SkillDrafter {

    final class Draft {
        public final String id, fileName, markdown;

        public Draft(String id, String markdown) {
            this.id = id;
            this.fileName = "skill_" + id + ".md";
            this.markdown = markdown;
        }
    }

    /** 不值得沉淀（失败 / 只有一步）返回 null。 */
    Draft draft(SkillLearner.Episode e, long nowMs);

    SkillDrafter NONE = new SkillDrafter() {
        @Override public Draft draft(SkillLearner.Episode e, long nowMs) {
            return null;
        }
    };
}
