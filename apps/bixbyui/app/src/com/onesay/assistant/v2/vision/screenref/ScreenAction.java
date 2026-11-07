package com.onesay.assistant.v2.vision.screenref;

import org.json.JSONObject;

/**
 * [screenref] 一个可执行的"屏幕实体动作"：芯片、指代消解结果都落成它，再交给面板走正常门禁/执行链路。纯 Java。
 */
public final class ScreenAction {
    public final DeixisResolver.Action action;
    /** 能力签名：既有能力（phone.call / browser.search）或 screenref 目录里的能力（screen.*）。 */
    public final String sign;
    public final JSONObject args;
    /** 芯片文案，如「导航到 深圳市南山区…」。 */
    public final String label;
    public final ScreenEntity entity;

    public ScreenAction(DeixisResolver.Action action, String sign, JSONObject args, String label, ScreenEntity entity) {
        this.action = action;
        this.sign = sign;
        this.args = args;
        this.label = label;
        this.entity = entity;
    }

    /** 复制不经 root/Intent，由 App 进程内 ClipboardManager 完成。 */
    public boolean isLocalCopy() {
        return ScreenRefCapabilities.SIGN_COPY.equals(sign);
    }

    @Override
    public String toString() {
        return label + " -> " + sign + " " + args;
    }
}
