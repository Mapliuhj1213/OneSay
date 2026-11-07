package com.onesay.assistant.v2.s1;

/** 测试辅助：把包内的命令常量暴露给 sec 包的 CI 白名单测试。 */
public final class SecIntS1Access {
    private SecIntS1Access() {}
    public static String fullscreenCmd() { return FullscreenPolicy.CMD; }
}
