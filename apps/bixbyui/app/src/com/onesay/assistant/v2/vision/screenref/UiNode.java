package com.onesay.assistant.v2.vision.screenref;

/**
 * [screenref] uiautomator dump 里的一个节点（只保留识屏需要的属性）。纯 Java。
 */
public final class UiNode {
    public final String text;
    public final String contentDesc;
    public final String resourceId;
    public final String className;
    public final String pkg;
    public final boolean clickable;
    public final boolean password;
    /** bounds：left, top, right, bottom；解析失败为 0。 */
    public final int left, top, right, bottom;

    public UiNode(String text, String contentDesc, String resourceId, String className, String pkg,
                  boolean clickable, boolean password, int left, int top, int right, int bottom) {
        this.text = text == null ? "" : text;
        this.contentDesc = contentDesc == null ? "" : contentDesc;
        this.resourceId = resourceId == null ? "" : resourceId;
        this.className = className == null ? "" : className;
        this.pkg = pkg == null ? "" : pkg;
        this.clickable = clickable;
        this.password = password;
        this.left = left;
        this.top = top;
        this.right = right;
        this.bottom = bottom;
    }

    public int centerY() {
        return (top + bottom) / 2;
    }

    /** 节点"可读文字"：优先 text，退而用 content-desc。 */
    public String label() {
        if (text.length() > 0) return text;
        return contentDesc;
    }

    @Override
    public String toString() {
        return "UiNode{" + label() + " id=" + resourceId + " [" + left + "," + top + "][" + right + "," + bottom + "]"
                + (clickable ? " clickable" : "") + "}";
    }
}
