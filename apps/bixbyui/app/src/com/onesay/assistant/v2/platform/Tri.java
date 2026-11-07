package com.onesay.assistant.v2.platform;

/**
 * 三态探测结果：有 / 没有 / 不确定（探不到、还没探）。
 *
 * <p>「不确定」<b>绝不</b>当成「没有」去置灰功能——只有明确探到 {@link #NO} 才置灰（保证在 Note 8 上现有功能不被误关）。
 */
public enum Tri {
    YES, NO, UNKNOWN;

    /** Boolean → Tri（null = UNKNOWN）。 */
    public static Tri of(Boolean b) {
        return b == null ? UNKNOWN : (b.booleanValue() ? YES : NO);
    }

    /** boolean → Tri。 */
    public static Tri of(boolean b) {
        return b ? YES : NO;
    }
}
