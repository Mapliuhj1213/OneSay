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

/** 【骨架，默认关】#19 同一号码 30 分钟内未接 ≥2 次 → 回拨（黄卡）/ 代回短信（确认卡）。 规则写好了，宿主未接信号 / 动作；即使被打开，引擎也只记日志不出卡（SceneRegistry skeleton）。 */
public final class MissedCallScene extends BaseScene {
    public static final String ID = "missed_call";

    public MissedCallScene() {
        super(ID, "未接来电 ≥2 次", PRIVACY_PERSONAL, true, Signal.MISSED_CALL);
    }

    /** 同一号码 30 分钟内未接 ≥ 2 次。 */
    public static boolean qualifies(long countIn30Min) {
        return countIn30Min >= 2;
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        if (!qualifies(s.getLong("count30", 0))) return null;
        return Suggestion.builder(ID).title(s.get("name", "未知号码") + " 打了 " + s.get("count30") + " 次")
                .action(SAction.run("call", "回拨")).action(SAction.run("sms", "稍后回电")).action(SAction.mute()).priority(2)
                .dedup("missed:" + s.get("num_hash", "")).build();
    }
}
