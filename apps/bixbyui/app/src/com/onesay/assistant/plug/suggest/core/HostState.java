/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

/** 只读宿主状态（每次现读；取不到时给保守值）。 */
public interface HostState {
    /** 可能为 null（取不到就当未知，不放行私密判定 —— 截图场景按「未知」照常弹但不显示包名）。 */
    String foregroundPkg();

    boolean keyguardLocked();

    boolean screenOn();

    boolean inCall();

    /** 语音会话 / 面板打开 / 岛上有确认。 */
    boolean assistantBusy();

    /** 游戏 / 全屏视频。 */
    boolean fullscreenImmersive();

    boolean dndOn();

    int screenWidthPx();

    int screenHeightPx();
}
