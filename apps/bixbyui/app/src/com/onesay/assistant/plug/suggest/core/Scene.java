/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

/** 一个场景（每个场景一个类）。evaluate 无副作用；状态只能放进 SceneContext 的私有存储或场景自己的内存。 */
public interface Scene {
    /** 0 = 纯本机；1 = 读个人数据（短信 / 通话 / 日历 / 通知）；2 = 会改系统设置或写日历。 */
    int PRIVACY_LOCAL = 0, PRIVACY_PERSONAL = 1, PRIVACY_SYSTEM = 2;

    /** [a-z][a-z0-9_]* */
    String id();

    /** 控制台显示。 */
    String title();

    /** D5：插件开启后这个场景默认开不开（=privacyClass 0 且不是骨架）。 */
    boolean defaultOn();

    int privacyClass();

    boolean accepts(String signalType);

    /** 无副作用；返回 null = 不建议。 */
    Suggestion evaluate(Signal s, SceneContext ctx);

    /** 结果回流（学习 / 退避）。 */
    void onOutcome(Suggestion s, Outcome o, SceneContext ctx);
}
