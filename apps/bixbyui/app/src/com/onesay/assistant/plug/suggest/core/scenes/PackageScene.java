/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes;

import com.onesay.assistant.plug.suggest.core.RatePolicy;
import com.onesay.assistant.plug.suggest.core.SAction;
import com.onesay.assistant.plug.suggest.core.SceneContext;
import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.SuggestDefaults;
import com.onesay.assistant.plug.suggest.core.Suggestion;

/** 场景 8：新装应用 → 打开。排除更新（replacing）、没有桌面入口的包、OneSay 自己。每个包只提示一次。 */
public final class PackageScene extends BaseScene {
    public static final String ID = "newapp";
    public static final String ACT_OPEN = "open";
    public static final RatePolicy POLICY = new RatePolicy(0L, 0, 365L * 24L * 3600000L, true);

    public PackageScene() {
        super(ID, "新装应用", PRIVACY_LOCAL, false, Signal.PKG_ADDED);
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        String pkg = s.get("pkg");
        if (pkg == null || pkg.length() == 0) return null;
        if (s.getBool("replacing") || !s.getBool("launchable")) return null;
        if (pkg.startsWith("com.onesay.assistant")) return null;
        String label = s.get("label", pkg);
        return Suggestion.builder(ID).title("已安装 " + label).subtitle("要现在打开吗？").icon("app:" + pkg)
                .action(SAction.run(ACT_OPEN, "打开")).action(SAction.dismiss()).action(SAction.mute())
                .ttl(SuggestDefaults.CARD_TTL_MS).priority(1).dedup("pkg:" + pkg).put("pkg", pkg).build();
    }
}
