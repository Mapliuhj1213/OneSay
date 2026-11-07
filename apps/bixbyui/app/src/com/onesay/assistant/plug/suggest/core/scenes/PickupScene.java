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

/**
 * 场景 4：取件码 / 取餐码。S1 现有链路（短信 / 记一记 → PICKUP 常驻岛）维持现状，这里只把「上岛了一张码」接入总频控（external，计数不展示）。
 * 场景关掉 = 不计数，取件码照常上岛（行为不回退）。
 */
public final class PickupScene extends BaseScene {
    public static final String ID = "pickup";
    public static final RatePolicy POLICY = new RatePolicy(0L, 0, SuggestDefaults.DEDUP_WINDOW_MS, false);

    public PickupScene() {
        super(ID, "取件码", PRIVACY_LOCAL, false, Signal.PICKUP);
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        String h = s.get("code_hash");
        if (h == null) return null;
        return Suggestion.builder(ID).title("取件码已上岛").external(true).priority(3).dedup("pickup:" + h)
                .put("kind", s.get("kind", "")).build();
    }
}
