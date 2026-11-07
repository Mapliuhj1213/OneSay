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

/** 【骨架，默认关】#22 电量 <15% 未充电且导航 / 打车在前台 → 省电模式（改系统设置，走确认）。 规则写好了，宿主未接信号 / 动作；即使被打开，引擎也只记日志不出卡（SceneRegistry skeleton）。 */
public final class LowBatteryScene extends BaseScene {
    public static final String ID = "low_battery";

    public LowBatteryScene() {
        super(ID, "低电量出门", PRIVACY_SYSTEM, true, Signal.BATTERY);
    }

    /** 电量 &lt; 15%、没在充电、前台是导航 / 打车类。 */
    public static boolean qualifies(int level, boolean charging, boolean navForeground) {
        return level >= 0 && level < 15 && !charging && navForeground;
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        if (!qualifies((int) s.getLong("level", -1), s.getBool("charging"), s.getBool("nav_fg"))) return null;
        return Suggestion.builder(ID).title("电量不多了").action(SAction.run("saver", "开省电模式")).action(SAction.dismiss())
                .action(SAction.mute()).priority(2).build();
    }
}
