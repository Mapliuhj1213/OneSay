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

/** 【骨架，默认关】#20 腾讯会议 / 飞书 / 钉钉会议链接或会议号 → 开始前 5 分钟一键入会。 规则写好了，宿主未接信号 / 动作；即使被打开，引擎也只记日志不出卡（SceneRegistry skeleton）。 */
public final class MeetingScene extends BaseScene {
    public static final String ID = "meeting";

    public MeetingScene() {
        super(ID, "会议通知 → 入会", PRIVACY_PERSONAL, true, Signal.NOTICE);
    }

    static final Pattern LINK = Pattern.compile("(?i)(meeting\\.tencent\\.com/\\S+|vc\\.feishu\\.cn/j/\\S+|meeting\\.dingtalk\\.com/\\S+|会议号[:：]?\\s*\\d{3}[ -]?\\d{3}[ -]?\\d{3,4})");

    public static boolean hasMeeting(String text) {
        return text != null && LINK.matcher(text).find();
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        if (!hasMeeting(s.get("text"))) return null;
        return Suggestion.builder(ID).title("会议快开始了").action(SAction.run("join", "一键入会")).action(SAction.dismiss())
                .action(SAction.mute()).priority(2).build();
    }
}
