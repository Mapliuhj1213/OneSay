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

/** 【骨架，默认关】#21 有地址的日程，提前 = 预估路程 + 15 分钟 → 导航 / 打车。 规则写好了，宿主未接信号 / 动作；即使被打开，引擎也只记日志不出卡（SceneRegistry skeleton）。 */
public final class DepartNavScene extends BaseScene {
    public static final String ID = "depart_nav";

    public DepartNavScene() {
        super(ID, "日程前出发", PRIVACY_PERSONAL, true, Signal.CALENDAR);
    }

    /** 有地址，且离「应出发时刻」（开始 − 路程 − 15 分钟）不到 0 分钟。 */
    public static boolean timeToLeave(boolean hasAddress, long minutesToLeave) {
        return hasAddress && minutesToLeave <= 0;
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        if (!timeToLeave(s.getBool("has_address"), s.getLong("min_to_leave", 999))) return null;
        return Suggestion.builder(ID).title("该出发了").action(SAction.run("nav", "导航")).action(SAction.run("taxi", "打车"))
                .action(SAction.mute()).priority(2).dedup("depart:" + s.get("event_hash", "")).build();
    }
}
