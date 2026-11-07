/*
 * [mika.skilllearn 插件 · 只留接口] 部分移植自 Mika（接口形状来自 Mika core/knowledge/AppKnowledgeLearning.kt，
 * Apache-2.0，Copyright 2026 wangzan101，见 assets/LICENSE-APACHE-2.0.txt 与 NOTICE；实现未移植）。core 包只依赖 java.*。
 */
package com.onesay.assistant.plug.skill.core;

import java.util.Collections;
import java.util.List;

/**
 * 技能自学：任务做完后，从这次的操作记录里提炼「下次可以直接用的做法」（某 App 的入口、可靠的深链、界面坑）。
 * <b>只留接口，默认不启用</b>：{@link #NONE} 什么都不学；宿主不会在插件关着时调用它。
 */
public interface SkillLearner {

    /** 一次任务的记录摘要（脱敏后的）。 */
    final class Episode {
        public final String goal, app, outcome;
        public final List<String> steps;

        public Episode(String goal, String app, String outcome, List<String> steps) {
            this.goal = goal;
            this.app = app;
            this.outcome = outcome;
            this.steps = steps == null ? Collections.<String>emptyList() : steps;
        }
    }

    /** 一条学到的东西（待用户确认后才进知识包）。 */
    final class Lesson {
        public final String app, text;

        public Lesson(String app, String text) {
            this.app = app;
            this.text = text;
        }
    }

    /** 从一次任务里提炼（可能为空）。实现不得直接写知识包，只返回候选。 */
    List<Lesson> learn(Episode e);

    SkillLearner NONE = new SkillLearner() {
        @Override public List<Lesson> learn(Episode e) {
            return Collections.emptyList();
        }
    };
}
