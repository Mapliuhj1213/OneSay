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

/** 【骨架，默认关】#10 智能建日程：来自行程短信，写日历前必须走确认卡。 规则写好了，宿主未接信号 / 动作；即使被打开，引擎也只记日志不出卡（SceneRegistry skeleton）。 */
public final class CalendarScene extends BaseScene {
    public static final String ID = "calendar_add";

    public CalendarScene() {
        super(ID, "智能建日程", PRIVACY_SYSTEM, true, Signal.SMS);
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        String[] p = TripSmsScene.parse(s.get("text"));
        if (p == null) return null;
        return Suggestion.builder(ID).title("把 " + p[1] + " 加到日历？").action(SAction.run("add", "加到日历"))
                .action(SAction.dismiss()).action(SAction.mute()).priority(1).dedup("cal:" + p[1] + p[2]).build();
    }
}
