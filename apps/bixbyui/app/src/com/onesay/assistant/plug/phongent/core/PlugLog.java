/*
 * [phongent 插件 · 宿主端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，
 * 宿主（OneSay 或别的 App）实现这些窄接口把执行端接进来。见 PHONGENT-PORT.md §插件规范。
 */
package com.onesay.assistant.plug.phongent.core;

/** 日志。core 只写不含正文 / token / 第三方原文的短句。 */
public interface PlugLog {
    void i(String msg);

    void w(String msg);

    PlugLog NONE = new PlugLog() {
        @Override public void i(String msg) {
        }

        @Override public void w(String msg) {
        }
    };
}
