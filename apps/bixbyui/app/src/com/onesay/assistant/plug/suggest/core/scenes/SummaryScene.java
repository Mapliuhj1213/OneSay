/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes;

import com.onesay.assistant.plug.suggest.core.SAction;
import com.onesay.assistant.plug.suggest.core.SceneContext;
import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.Suggestion;

import java.util.regex.Pattern;

/** 【骨架，默认关】#7 复制长文 → 总结（点了才发模型）。 规则写好了，宿主未接信号 / 动作；即使被打开，引擎也只记日志不出卡（SceneRegistry skeleton）。 */
public final class SummaryScene extends BaseScene {
    public static final String ID = "summarize";

    public SummaryScene() {
        super(ID, "复制长文 → 总结", PRIVACY_LOCAL, true, Signal.CLIP);
    }

    /** 300–6000 字且不像代码。 */
    public static boolean longText(long len, boolean codeLike) {
        return len >= 300 && len <= 6000 && !codeLike;
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        if (!longText(s.getLong("len", 0), s.getBool("code_like"))) return null;
        return Suggestion.builder(ID).title("复制了一段长文").action(SAction.run("summarize", "总结")).action(SAction.dismiss())
                .action(SAction.mute()).priority(1).build();
    }
}
