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

import java.util.List;

/**
 * 场景 6：插耳机 / 连蓝牙耳机 → 最常用的音乐 App（UsageStats 最近 7 天时长，宿主算好放进 {@code candidates}）。
 * 没有使用数据（usage appop 没开）就不弹；前台已经是音乐 App 不弹；第二常用的给第二个按钮（「长按选别的」的替代）。
 */
public final class HeadsetScene extends BaseScene {
    public static final String ID = "headset";
    public static final String ACT_OPEN = "open", ACT_OPEN2 = "open2";
    public static final RatePolicy POLICY = RatePolicy.interval(30L * 60000L);

    public HeadsetScene() {
        super(ID, "耳机 → 音乐", PRIVACY_LOCAL, false, Signal.HEADSET);
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        if (!"on".equals(s.get("state"))) return null;
        String fg = s.get("fg");
        if (fg == null && ctx.host() != null) fg = ctx.host().foregroundPkg();
        if (MusicApps.isMusic(fg)) return null;
        List<MusicApps.Usage> r = MusicApps.rank(s.get("candidates"));
        if (r.isEmpty()) return null;
        MusicApps.Usage a = r.get(0);
        Suggestion.Builder b = Suggestion.builder(ID).title("耳机已连接").subtitle("要不要打开" + a.label + "？").icon("headset")
                .action(SAction.run(ACT_OPEN, "打开" + a.label)).ttl(SuggestDefaults.CARD_TTL_MS).priority(1)
                .put("pkg", a.pkg).put("kind", s.get("kind", ""));
        if (r.size() > 1) {
            b.action(SAction.run(ACT_OPEN2, r.get(1).label)).put("pkg2", r.get(1).pkg);
        } else {
            b.action(SAction.dismiss());
        }
        return b.action(SAction.mute()).build();
    }
}
