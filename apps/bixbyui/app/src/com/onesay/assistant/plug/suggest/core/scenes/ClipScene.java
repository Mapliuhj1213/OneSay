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
 * 场景 3：剪贴板快递单号 / 地址 / 电话 / 链接（收编 s1 ClipboardWatcher + ClipChipPlanner）。
 * 规则核心仍是宿主的 ClipChipPlanner（≤300 字、防回环、60 s 去重、敏感过滤、实体抽取原样复用），宿主把裁决结果放进信号：
 * {@code labels}（芯片文字，换行分隔，≤3）、{@code title}（「复制了地址：…」）、{@code token}（宿主找回芯片对象的键）、{@code hash}（去重）。
 * 这里只负责出卡：芯片照旧最多 3 个，有空位时补「不再提示」；6 s 自动收；点芯片由宿主走原 ExecutionGate 链路。
 */
public final class ClipScene extends BaseScene {
    public static final String ID = "clip";
    public static final String ACT_PREFIX = "chip";
    /** 沿用剪贴板 60 s 去重；不参与全局配额（priority 2）。 */
    public static final RatePolicy POLICY = new RatePolicy(0L, 0, 60000L, true);

    public ClipScene() {
        super(ID, "复制单号 / 地址", PRIVACY_LOCAL, false, Signal.CLIP);
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        String labels = s.get("labels");
        String token = s.get("token");
        if (labels == null || labels.length() == 0 || token == null) return null;
        Suggestion.Builder b = Suggestion.builder(ID).title(s.get("title", "复制了内容")).icon("clip").ttl(SuggestDefaults.CARD_TTL_MS)
                .priority(2).dedup("clip:" + s.get("hash", token)).put("token", token);
        int n = 0;
        for (String l : labels.split("\n")) {
            if (l.length() == 0 || n >= SuggestDefaults.MAX_ACTIONS) continue;
            b.action(SAction.run(ACT_PREFIX + n, l));
            n++;
        }
        if (n == 0) return null;
        if (n < SuggestDefaults.MAX_ACTIONS) b.action(SAction.mute());
        return b.build();
    }

    /** 「chip2」→ 2；不是芯片返回 -1。 */
    public static int chipIndex(String actionId) {
        if (actionId == null || !actionId.startsWith(ACT_PREFIX)) return -1;
        try {
            return Integer.parseInt(actionId.substring(ACT_PREFIX.length()));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
