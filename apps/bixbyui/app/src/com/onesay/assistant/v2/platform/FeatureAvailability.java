package com.onesay.assistant.v2.platform;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 「这个功能在这台设备上能不能开」的裁决（纯函数，JVM 可测；refactor/oss 2026-11-06）。
 *
 * <p>规则（保证在 Note 8 上不误关任何现有功能）：
 * <ol>
 *   <li>{@link FeatureSpec.Status#NOT_BUILT} → {@link State#NOT_BUILT}（永远置灰，写明缺什么、替代是什么）；</li>
 *   <li>任一<b>补不上</b>的能力明确为 {@link Tri#NO}（root、三星、系统版本…）→ {@link State#UNAVAILABLE}，置灰；</li>
 *   <li>只缺<b>用户能自己授权</b>的能力（{@link DeviceCap#grantable()}）→ {@link State#NEEDS_GRANT}，开关可用、提示去授权；</li>
 *   <li>有能力是 {@link Tri#UNKNOWN}（还没探到）→ {@link State#UNKNOWN}，开关<b>可用</b>（不确定不等于没有）；</li>
 *   <li>否则 {@link State#AVAILABLE}。</li>
 * </ol>
 * 裁决只影响展示，<b>从不改写</b>功能开关本身的值。
 */
public final class FeatureAvailability {

    public enum State { AVAILABLE, NEEDS_GRANT, UNKNOWN, UNAVAILABLE, NOT_BUILT }

    public final State state;
    /** 缺的能力（按声明顺序）。 */
    public final List<DeviceCap> missing;
    /** 没探到的能力。 */
    public final List<DeviceCap> unknown;

    private FeatureAvailability(State state, List<DeviceCap> missing, List<DeviceCap> unknown) {
        this.state = state;
        this.missing = Collections.unmodifiableList(missing);
        this.unknown = Collections.unmodifiableList(unknown);
    }

    /** 开关能不能拨（UNAVAILABLE / NOT_BUILT 置灰，其余可拨）。 */
    public boolean switchEnabled() {
        return state != State.UNAVAILABLE && state != State.NOT_BUILT;
    }

    /** 给人看的原因：「需要 root · 仅三星」；可用时为 null。 */
    public String reason() {
        if (state == State.AVAILABLE) return null;
        StringBuilder sb = new StringBuilder();
        if (state == State.NOT_BUILT) sb.append("未实现");
        for (DeviceCap c : missing) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(c.missingReason);
        }
        if (state == State.UNKNOWN) {
            for (DeviceCap c : unknown) {
                if (sb.length() > 0) sb.append(" · ");
                sb.append(c.title).append("未确认");
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    /** 裁决。profile 为 null（还没探过）时，所需能力全部视为 UNKNOWN。 */
    public static FeatureAvailability evaluate(FeatureSpec spec, DeviceProfile profile) {
        List<DeviceCap> hardMissing = new ArrayList<DeviceCap>();
        List<DeviceCap> grantMissing = new ArrayList<DeviceCap>();
        List<DeviceCap> unknown = new ArrayList<DeviceCap>();
        for (DeviceCap c : spec.requires) {
            Tri t = profile == null ? Tri.UNKNOWN : profile.get(c);
            if (t == Tri.NO) {
                if (c.grantable()) grantMissing.add(c);
                else hardMissing.add(c);
            } else if (t == Tri.UNKNOWN) {
                unknown.add(c);
            }
        }
        List<DeviceCap> missing = new ArrayList<DeviceCap>(hardMissing);
        missing.addAll(grantMissing);
        if (spec.status == FeatureSpec.Status.NOT_BUILT) {
            // 未实现的项：列出全部所需能力里缺的；都不缺也照样置灰
            return new FeatureAvailability(State.NOT_BUILT, missing, unknown);
        }
        if (!hardMissing.isEmpty()) return new FeatureAvailability(State.UNAVAILABLE, missing, unknown);
        if (!grantMissing.isEmpty()) return new FeatureAvailability(State.NEEDS_GRANT, missing, unknown);
        if (!unknown.isEmpty()) return new FeatureAvailability(State.UNKNOWN, missing, unknown);
        return new FeatureAvailability(State.AVAILABLE, missing, unknown);
    }
}
