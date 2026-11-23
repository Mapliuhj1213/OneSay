package com.onesay.assistant.v2.a11ylabel;

import com.onesay.assistant.v2.phone.UiTree;

/**
 * ★ a11y-labels：快捷指令 / 定时步骤「点屏幕上的按钮」的判定（纯 Java，JVM 全测；读屏和点按在 {@link LabelTapHooks}）。
 * 顺序：参数解析 → 按标签找（旧坐标格式：找那里的可点控件，绝不盲点）→ 付款 / 发送类交给本人点 → 可以点。
 */
public final class LabelTap {

    private LabelTap() {
    }

    public static final class Plan {
        public final UiTree.El target;
        public final String error;
        /** 付款 / 发送 / 删除 / 登录这类：找到了也不替他点。 */
        public final boolean userMustTap;
        /** 旧坐标格式命中后建议改存的新格式（日志 / 结果里提示，不自动改存）。 */
        public final LabelSelector migrated;
        public final int candidates;

        Plan(UiTree.El target, String error, boolean userMustTap, LabelSelector migrated, int candidates) {
            this.target = target;
            this.error = error;
            this.userMustTap = userMustTap;
            this.migrated = migrated;
            this.candidates = candidates;
        }

        public boolean ok() {
            return target != null && error == null && !userMustTap;
        }
    }

    /** labeled = 刚读、已贴标签的树。 */
    public static Plan decide(org.json.JSONObject args, UiTree labeled, ButtonNumbers.Guard guard) {
        LabelSelector sel = LabelSelector.fromArgs(args);
        if (sel == null) return new Plan(null, "没说要点哪个按钮（target 为空）", false, null, 0);
        LabelSelector.Match m = sel.find(labeled);
        if (!m.ok()) return new Plan(null, m.error, false, null, 0);
        String must = guard == null ? null : guard.userMustTap(m.el, labeled);
        if (must != null) return new Plan(m.el, "找到了「" + ButtonNumbers.clip(m.el.label) + "」，但" + must + "，这一下要你自己点", true, m.migrated, m.candidates);
        return new Plan(m.el, null, false, m.migrated, m.candidates);
    }
}
