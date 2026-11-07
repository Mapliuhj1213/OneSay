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

/** 【骨架，默认关】#6 复制外文 → 翻译（点了才发模型）。 规则写好了，宿主未接信号 / 动作；即使被打开，引擎也只记日志不出卡（SceneRegistry skeleton）。 */
public final class TranslateScene extends BaseScene {
    public static final String ID = "translate";

    public TranslateScene() {
        super(ID, "复制外文 → 翻译", PRIVACY_LOCAL, true, Signal.CLIP);
    }

    /** 非 CJK 字母占比 &gt; 60% 且 ≥ 8 个词。 */
    public static boolean foreign(String t) {
        if (t == null) return false;
        int letters = 0, cjk = 0;
        for (int i = 0; i < t.length(); i++) {
            char c = t.charAt(i);
            if (c >= 0x4e00 && c <= 0x9fa5) cjk++;
            else if (Character.isLetter(c)) letters++;
        }
        if (letters + cjk == 0) return false;
        return letters * 100 / (letters + cjk) > 60 && t.trim().split("\\s+").length >= 8;
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        if (!foreign(s.get("text"))) return null;
        return Suggestion.builder(ID).title("复制了外文").action(SAction.run("translate", "翻译")).action(SAction.dismiss())
                .action(SAction.mute()).priority(1).build();
    }
}
