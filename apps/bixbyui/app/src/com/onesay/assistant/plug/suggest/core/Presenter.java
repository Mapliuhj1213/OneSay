/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

/** 展示端口（宿主：灵动岛 suggest 卡）。 */
public interface Presenter {
    /** 主线程外调用也安全；返回 false = 宿主当前不能展示（岛忙 / 面板开着 / 非岛模式），引擎记 SUPPRESSED。 */
    boolean show(Suggestion s);

    /** 就地改副标题（连拍计数）。实现时补的方法，对应 IslandHost.suggestUpdate。 */
    void update(String suggestionId, String subtitle);

    /** 撤卡（截图被删了）。 */
    void withdraw(String suggestionId);

    void setListener(Listener l);

    interface Listener {
        /** 按钮被点（actionId = SAction.id）。 */
        void onAction(String suggestionId, String actionId);

        void onTimeout(String suggestionId);

        /** 上滑收起 = 忽略。 */
        void onSwipedAway(String suggestionId);
    }

    Presenter NONE = new Presenter() {
        @Override public boolean show(Suggestion s) { return false; }
        @Override public void update(String id, String subtitle) { }
        @Override public void withdraw(String id) { }
        @Override public void setListener(Listener l) { }
    };
}
