package com.onesay.assistant.v2.imgsave;

import java.io.File;

/** 测试辅助：把包内的命令构造暴露给 sec 包的 CI 白名单测试。 */
public final class SecIntImgSaveAccess {
    private SecIntImgSaveAccess() {}
    public static String[] captureSteps(File raw, File xml) { return ImageSaveController.captureSteps(raw, xml); }
    public static java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> rootCopySteps(File tmp, File dst) {
        return ImageSaveController.rootCopySteps(tmp, dst);
    }
    public static String cacheListCommand(String pkg) { return CacheMatcher.listCommand(pkg); }
}
