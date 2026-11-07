/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

/** 信号源（宿主实现：MediaStore / inotify / 剪贴板 / 手电筒 / 耳机 / 安装广播 …）。 */
public interface SignalSource {
    String type();

    /** start 时调。 */
    void attach(Sink sink);

    /** stop 时调，必须撤干净（注销监听、停线程）。 */
    void detach();

    interface Sink {
        void emit(Signal s);
    }
}
