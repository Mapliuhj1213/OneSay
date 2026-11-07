/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

import java.util.Set;

/** 插件配置（每次现读，不缓存）。 */
public interface SceneConfig {
    boolean masterOn();

    boolean sceneOn(String sceneId, boolean def);

    void setSceneOn(String sceneId, boolean on);

    /** 私密 App 名单（含内置默认）。 */
    Set<String> privatePkgs();

    /** 0 正常 1 克制（全局配额减半）2 只要高优先级（priority≥2）。 */
    int quietLevel();
}
