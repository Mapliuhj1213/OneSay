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

/** 【骨架，默认关】#9 智慧出行：12306 / 航班 / 酒店短信模板解析 → 岛上倒计时。 规则写好了，宿主未接信号 / 动作；即使被打开，引擎也只记日志不出卡（SceneRegistry skeleton）。 */
public final class TripSmsScene extends BaseScene {
    public static final String ID = "trip_sms";

    public TripSmsScene() {
        super(ID, "行程短信", PRIVACY_PERSONAL, true, Signal.SMS);
    }

    static final Pattern TRAIN = Pattern.compile("([GDCZTK]\\d{1,4})次.{0,12}?(\\d{1,2}车\\d{1,3}[A-F]?号)?.{0,12}?([\\u4e00-\\u9fa5]{1,8}站)?(\\d{1,2}:\\d{2})开");
    static final Pattern FLIGHT = Pattern.compile("(?<![A-Z0-9])([A-Z0-9]{2}\\d{3,4})(?![0-9]).{0,30}?(\\d{1,2}:\\d{2})");

    /** 只认 106 开头服务号 / 12306 / 航旅类来源（宿主给 from）。解析不了就不弹，不交给模型猜。 */
    public static String[] parse(String body) {
        if (body == null) return null;
        java.util.regex.Matcher m = TRAIN.matcher(body);
        if (m.find()) return new String[]{"train", m.group(1), m.group(4)};
        if (body.contains("航班") || body.contains("起飞")) {
            m = FLIGHT.matcher(body);
            if (m.find()) return new String[]{"flight", m.group(1), m.group(2)};
        }
        return null;
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        String[] p = parse(s.get("text"));
        if (p == null) return null;
        return Suggestion.builder(ID).title(("train".equals(p[0]) ? "火车 " : "航班 ") + p[1] + " " + p[2])
                .action(SAction.run("pin", "放到岛上倒计时")).action(SAction.dismiss()).action(SAction.mute()).priority(1)
                .dedup("trip:" + p[1] + p[2]).build();
    }
}
