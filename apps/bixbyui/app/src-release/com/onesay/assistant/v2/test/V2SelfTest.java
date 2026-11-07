package com.onesay.assistant.v2.test;

import android.content.Context;

/**
 * Release 构建用的 V2SelfTest 占位（P1-12）。
 *
 * <p>真正的设备自测（app/src/com/onesay/assistant/v2/test/V2SelfTest.java，2500+ 行）与 adb 测试驱动
 * TestRunReceiver 只在 {@code DEBUG=1 bash build_linux.sh} 时编译进 APK。
 * Release 包里 MainActivity 的「运行自测」按钮仍然存在，点击后只给出提示，不执行任何测试动作
 * （真自测会清空 L1 缓存、注入测试条目、实际执行能力 —— 这些都不该出现在日常使用的包里）。
 *
 * <p>API 必须与 debug 版保持一致（目前只被 MainActivity 调用：run + SelfTestCallback）。
 */
public final class V2SelfTest {

    public interface SelfTestCallback {
        void onProgress(String log);
        void onComplete(String summary);
    }

    /** ★ R12：release 包没有自测 → 控制台开发者页不画「运行全量回归自测」，也用它判断构建类型。 */
    public static final boolean AVAILABLE = false;

    private V2SelfTest() {}

    public static void run(final Context ctx, final SelfTestCallback cb) {
        if (cb == null) return;
        cb.onProgress("当前是 Release 构建：设备自测（V2SelfTest）与 adb 测试驱动（TestRunReceiver）未打包。");
        cb.onProgress("如需自测，请用 DEBUG=1 bash build_linux.sh 构建 debug 包后重新安装。");
        cb.onComplete("未执行（release 构建不含自测代码）");
    }
}
