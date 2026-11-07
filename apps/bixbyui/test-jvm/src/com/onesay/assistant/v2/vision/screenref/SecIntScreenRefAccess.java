package com.onesay.assistant.v2.vision.screenref;

/** 测试辅助：把包内的命令构造暴露给 sec 包的 CI 白名单测试。 */
public final class SecIntScreenRefAccess {
    private SecIntScreenRefAccess() {}
    public static String activityCmd() { return ScreenContextCapture.ACTIVITY_CMD; }
    public static String dumpCmd(String p) { return ScreenContextCapture.dumpCmd(p); }
    public static String dumpCmdLow(String p) { return ScreenContextCapture.dumpCmd(p, true); }
}
