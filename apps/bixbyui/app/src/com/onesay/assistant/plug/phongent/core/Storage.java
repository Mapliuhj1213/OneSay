/*
 * [phongent 插件 · 宿主端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，
 * 宿主（OneSay 或别的 App）实现这些窄接口把执行端接进来。见 PHONGENT-PORT.md §插件规范。
 */
package com.onesay.assistant.plug.phongent.core;

import java.util.Map;

/** 键值持久化。put / remove 返回 true = 已落盘（SharedPreferences.commit 语义）；值是文本（core 存 JSON）。 */
public interface Storage {
    Map<String, String> all();

    boolean put(String key, String value);

    boolean remove(String key);
}
