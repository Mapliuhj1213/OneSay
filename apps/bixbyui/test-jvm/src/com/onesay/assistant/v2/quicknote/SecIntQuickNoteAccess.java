package com.onesay.assistant.v2.quicknote;

/** 测试辅助：把包内的命令构造暴露给 sec 包的 CI 白名单测试。 */
public final class SecIntQuickNoteAccess {
    private SecIntQuickNoteAccess() {}
    public static String captureCmd(String p) { return QuickNoteThumbs.captureCmd(p); }
}
