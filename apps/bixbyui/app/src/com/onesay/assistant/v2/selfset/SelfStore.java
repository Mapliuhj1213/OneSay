package com.onesay.assistant.v2.selfset;

import com.onesay.assistant.v2.platform.Tri;

/**
 * [settings-diag-1124] 设置清单的读写口（宿主实现；Android 侧是 {@code SelfSettingsAndroid}，单测用假的）。
 * 写一律走各功能原来的 setter（带它原来的副作用），不直接改 prefs。
 */
public interface SelfStore {

    /** 当前值：开关 → Boolean；多选 → 取值字符串；权限项 → Boolean（开没开）。读不到 → null。 */
    Object read(String itemId);

    /** 写（只会对 TOGGLE / CHOICE 调）。@return 写进去没出错（是否生效看之后的回读） */
    boolean write(String itemId, Object value);

    /** 前提满足没有。 */
    Tri need(String needId);

    /** 这一项被关停文件停用了（停用时开关改不动）。 */
    boolean killed(String itemId);
}
