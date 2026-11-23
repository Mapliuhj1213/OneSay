package com.onesay.assistant.v2.ui.console;

/** [fix-1123-c] 首页「连接电脑」一组显示哪种（纯 Java，JVM 可测）。 */
public final class PcEntry {
    private PcEntry() {}

    public static final String KILLED = "killed", OFF = "off", UNPAIRED = "unpaired", KEY_LOST = "key_lost", PAIRED = "paired";

    public static String state(boolean on, boolean killed, boolean paired, boolean keyLost) {
        if (killed) return KILLED;
        if (!on) return OFF;
        if (!paired) return UNPAIRED;
        return keyLost ? KEY_LOST : PAIRED;
    }
}
