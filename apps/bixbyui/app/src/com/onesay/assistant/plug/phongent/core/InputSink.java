/*
 * [phongent 插件 · 宿主端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，
 * 宿主（OneSay 或别的 App）实现这些窄接口把执行端接进来。见 PHONGENT-PORT.md §插件规范。
 */
package com.onesay.assistant.plug.phongent.core;

/** 接管输入（宿主用 root 注入 / 无障碍等实现）。坐标是真实屏幕像素。 */
public interface InputSink {
    int KEY_HOME = 3;
    int KEY_BACK = 4;

    boolean tap(int x, int y);

    boolean swipe(int x1, int y1, int x2, int y2, int durationMs);

    boolean key(int keyCode);
}
