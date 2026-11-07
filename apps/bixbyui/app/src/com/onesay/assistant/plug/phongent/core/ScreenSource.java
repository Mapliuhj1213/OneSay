/*
 * [phongent 插件 · 宿主端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，
 * 宿主（OneSay 或别的 App）实现这些窄接口把执行端接进来。见 PHONGENT-PORT.md §插件规范。
 */
package com.onesay.assistant.plug.phongent.core;

import java.io.IOException;

/** 屏幕画面来源（宿主负责截屏 + H.264 编码，平台相关）。 */
public interface ScreenSource {

    /** 真实屏幕像素 [宽, 高]（输入坐标换算用）。 */
    int[] screenSize();

    /** 编码画面尺寸 [宽, 高]（同意后立刻报给主控：iOS 要 width/height &gt; 0 才去连 /video）。 */
    int[] streamSize();

    /**
     * 持续往 sink 推 H.264 访问单元（Annex B，参数集可单独一包），直到 {@link PacketSink#open()} 为 false 或出错。
     * 第一包前先调 {@link PacketSink#size(int, int)} 报编码尺寸。
     */
    void stream(PacketSink sink) throws IOException;

    interface PacketSink {
        boolean open();

        void size(int width, int height);

        void packet(byte[] accessUnit) throws IOException;
    }
}
