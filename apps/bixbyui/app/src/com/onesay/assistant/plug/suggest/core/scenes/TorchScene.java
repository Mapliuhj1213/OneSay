/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes;

import com.onesay.assistant.plug.suggest.core.HostState;
import com.onesay.assistant.plug.suggest.core.RatePolicy;
import com.onesay.assistant.plug.suggest.core.SAction;
import com.onesay.assistant.plug.suggest.core.SceneContext;
import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.SuggestDefaults;
import com.onesay.assistant.plug.suggest.core.Suggestion;

import java.util.Locale;

/**
 * 场景 7：手电筒忘关。宿主 TorchSource：开满 {@link #ON_MIN_MS} 发 {@code event=tick}（之后每 2 分钟一次），
 * 亮屏发 {@code screen_on}、解锁发 {@code user_present}，关掉发 {@code event=off} + {@code withdraw=torch}（引擎撤卡 / 取消推迟）。
 *
 * <p>2026-11-04 用户真机反馈：原「开满 3 min 且亮屏」太晚 → <b>亮屏状态下开满 30 s 就提醒</b>；
 * 锁屏 / 息屏期间不弹（计时照走），一旦亮屏（已解锁）且手电仍开、已满 30 s → 立刻提醒；关了手电就撤卡。
 * 前台就是相机 / 手电筒类 App 时的 tick 不弹（用户正在那个 App 里自己开着）；亮屏 / 解锁那一刻照弹。
 * priority 2（夜里也提醒：手电筒常在夜里用）。
 */
public final class TorchScene extends BaseScene {
    public static final String ID = "torch";
    public static final String ACT_OFF = "off";
    public static final long ON_MIN_MS = 30000L;
    public static final String EV_TICK = "tick", EV_SCREEN_ON = "screen_on", EV_USER_PRESENT = "user_present", EV_OFF = "off";
    public static final RatePolicy POLICY = RatePolicy.interval(10L * 60000L);

    public TorchScene() {
        super(ID, "手电筒忘关", PRIVACY_LOCAL, false, Signal.TORCH);
    }

    public static boolean cameraLike(String pkg) {
        if (pkg == null) return false;
        String p = pkg.toLowerCase(Locale.ROOT);
        return p.contains("camera") || p.contains("torch") || p.contains("flashlight");
    }

    /** 「已经开了 …」：不到 1 分钟按秒说。 */
    public static String duration(long ms) {
        if (ms < 60000L) return Math.max(1L, ms / 1000L) + " 秒";
        return (ms / 60000L) + " 分钟";
    }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        if (!s.getBool("on")) return null;
        long since = s.getLong("on_since", -1);
        if (since < 0 || s.atElapsed - since < ON_MIN_MS) return null;
        HostState h = ctx.host();
        if (h != null && (!h.screenOn() || h.keyguardLocked())) return null;   // 锁屏 / 息屏不弹：等亮屏 / 解锁事件再来
        String ev = s.get("event", "");
        if (EV_OFF.equals(ev)) return null;
        if (EV_TICK.equals(ev)) {
            String fg = s.get("fg");
            if (fg == null && h != null) fg = h.foregroundPkg();
            if (cameraLike(fg)) return null;
        } else if (!EV_SCREEN_ON.equals(ev) && !EV_USER_PRESENT.equals(ev)) {
            return null;
        }
        return Suggestion.builder(ID).title("手电筒还开着").subtitle("已经开了 " + duration(s.atElapsed - since)).icon("torch")
                .action(SAction.run(ACT_OFF, "关闭手电筒")).action(SAction.dismiss()).action(SAction.mute())
                .ttl(SuggestDefaults.CARD_TTL_MS).priority(2).put("camera_id", s.get("camera_id")).build();
    }
}
