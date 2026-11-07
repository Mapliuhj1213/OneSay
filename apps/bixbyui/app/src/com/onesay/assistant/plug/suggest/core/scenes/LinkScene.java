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

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * 场景 2：复制购物 / 短视频 / 网盘链接 → 在对应 App 打开（{@link LinkTable}）。信号 {@code text}（宿主已过敏感过滤、≤300 字）、
 * {@code installed}（LinkTable 目标包里已安装的，逗号分隔）。没装就不弹。与剪贴板场景同一条信号时，本场景先登记、同优先级胜出。
 */
public final class LinkScene extends BaseScene {
    public static final String ID = "link";
    public static final String ACT_OPEN = "open";
    public static final RatePolicy POLICY = new RatePolicy(0L, 0, 60000L, true);

    public LinkScene() {
        super(ID, "复制链接打开 App", PRIVACY_LOCAL, false, Signal.CLIP);
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        String text = s.get("text");
        LinkTable.Match m = LinkTable.match(text);
        if (m == null) return null;
        Set<String> installed = new HashSet<String>(Arrays.asList(s.get("installed", "").split(",")));
        if (!installed.contains(m.entry.pkg)) return null;
        return Suggestion.builder(ID).title(m.token ? "复制了" + m.entry.label + "口令" : "复制了" + m.entry.label + "链接")
                .subtitle("直接在 " + m.entry.label + " 里打开").icon("link:" + m.entry.key)
                .action(SAction.run(ACT_OPEN, "在" + m.entry.label + "打开")).action(SAction.dismiss()).action(SAction.mute())
                .ttl(SuggestDefaults.CARD_TTL_MS).priority(2).dedup("link:" + hash(text))
                .put("pkg", m.entry.pkg).put("app", m.entry.key).put("url", m.url).put("token", m.token ? "1" : "0").build();
    }
}
