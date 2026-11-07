/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

/** 建议卡上的一个按钮。RUN 交给宿主 ActionRunner；DISMISS = 忽略；MUTE_SCENE = 不再提示（只关这个场景，可撤销）。 */
public final class SAction {
    public static final int RUN = 0, DISMISS = 1, MUTE_SCENE = 2;

    public final String id;
    public final String label;
    public final int kind;

    public SAction(String id, String label, int kind) {
        this.id = id;
        this.label = label;
        this.kind = kind;
    }

    public static SAction run(String id, String label) {
        return new SAction(id, label, RUN);
    }

    public static SAction dismiss() {
        return new SAction("dismiss", SuggestDefaults.LABEL_DISMISS, DISMISS);
    }

    public static SAction mute() {
        return new SAction("mute", SuggestDefaults.LABEL_MUTE, MUTE_SCENE);
    }

    @Override public String toString() {
        return id + "/" + kind;
    }
}
