/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.api.Clock;

/** 场景拿到的只读宿主状态 + 本场景私有存储（命名空间 = sceneId）。 */
public interface SceneContext {
    Clock clock();

    HostState host();

    SceneConfig config();

    String load(String key);

    boolean save(String key, String val);
}
