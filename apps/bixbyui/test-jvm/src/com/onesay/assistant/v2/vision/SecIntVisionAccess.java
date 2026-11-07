package com.onesay.assistant.v2.vision;

/** 测试辅助：把包内的命令构造暴露给 sec 包的 CI 白名单测试。 */
public final class SecIntVisionAccess {
    private SecIntVisionAccess() {}
    public static String captureCmd(String p) { return ScreenVisionEngine.captureCmd(p); }
}
