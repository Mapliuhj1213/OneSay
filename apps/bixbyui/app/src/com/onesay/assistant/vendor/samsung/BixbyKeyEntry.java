package com.onesay.assistant.vendor.samsung;

import com.onesay.assistant.v2.entry.BuiltinEntries;
import com.onesay.plugin.api.entry.EntryTrigger;

/**
 * 厂商入口：三星 Bixby 实体键（默认关）。
 *
 * <p>实现方式：root 按键守护（{@code tools/kdaemon/hijack_daemon.v9.sh}）在 {@code /dev/input} 上截获 Bixby 键，
 * 把手势写进 App 私有目录的 {@code bixby.trigger}；WakeService 的文件观察器只在本入口打开且可用时才启动。
 * 需要：三星带 Bixby 键的机型 + root + 已部署按键守护。其他手机上这一行永远置灰。
 */
public final class BixbyKeyEntry extends BuiltinEntries.Simple {

    public static final String ID = "vendor.samsung.bixby_key";

    public BixbyKeyEntry() {
        super(ID, "三星 Bixby 键（厂商插件）",
                "短按唤起、长按记一记、双击；需要三星带 Bixby 键的机型、root 和按键守护（tools/kdaemon）",
                EntryTrigger.Kind.LISTENER, false, 24, new String[]{"BIXBY_KEY", "ROOT", "KEY_DAEMON"}, null);
    }
}
