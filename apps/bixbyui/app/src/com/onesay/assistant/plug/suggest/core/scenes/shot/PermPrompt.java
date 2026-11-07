/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes.shot;

/**
 * ★ fix/shot-1104 release 内引导授权：截图分析需要存储权限、而 release 从不主动申请时，给用户一次「去授权」提示。
 * <b>只提示一次</b>（显示成功即记 shown，之后永不再弹；控制台那一行常驻，不算骚扰）。没显示成（岛忙 / 锁屏 / 息屏）不算，
 * 下次触发再试，最多尝试 {@link #MAX_TRIES} 次。纯 Java。
 */
public final class PermPrompt {
    public static final String ID = "sys:storage_perm";
    public static final String ACT_GRANT = "grant";
    public static final String ACT_LATER = "later";
    public static final String TITLE = "截图分析需要存储权限";
    public static final String SUB = "授权后截屏，岛上会问要不要分析 · 去授权";
    public static final String LABEL_GRANT = "去授权";
    public static final String LABEL_LATER = "不用了";
    public static final long TTL_MS = 10000L;
    public static final int MAX_TRIES = 5;

    private PermPrompt() {
    }

    /**
     * @param granted     已有存储权限
     * @param sceneOn     总开关 + 截图场景都开着
     * @param shown       之前已经成功显示过（prefs）
     * @param tries       已经尝试过几次（没显示成的也算）
     * @param screenOn    亮屏
     * @param locked      锁屏中
     */
    public static boolean shouldTry(boolean granted, boolean sceneOn, boolean shown, int tries, boolean screenOn, boolean locked) {
        if (granted || !sceneOn || shown) return false;
        if (tries >= MAX_TRIES) return false;
        return screenOn && !locked;
    }
}
