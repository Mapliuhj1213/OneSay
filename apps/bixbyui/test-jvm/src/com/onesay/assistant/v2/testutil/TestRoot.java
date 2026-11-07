package com.onesay.assistant.v2.testutil;

import java.io.File;

/**
 * 按相对路径找源文件 / fixtures 的测试的起点（refactor/oss 2026-11-06）。
 *
 * <p>原来这些测试从测试类所在目录（默认 test-jvm/build/test）往上回溯；编译输出放到别处（BIXBY_TEST_BUILD=/tmp/…）时就找不到了。
 * 现在优先用 run_unit_tests.sh 传入的 {@code -Dbixby.root}：返回 {@code <bixby.root>/test-jvm/build/test}
 * 这个<b>路径</b>（不要求存在），原有的「逐级 getParentFile 回溯」逻辑不用改就能找到 test-jvm/fixtures 与仓库根的 app/。
 * 没传 bixby.root（或它不像仓库根）时回退到测试类所在位置，行为与原来一致。
 */
public final class TestRoot {

    private TestRoot() {}

    public static File start(Class<?> testClass) throws Exception {
        String br = System.getProperty("bixby.root");
        if (br != null && br.length() > 0) {
            File root = new File(br).getAbsoluteFile();
            if (new File(root, "app/AndroidManifest.xml").exists()) return new File(root, "test-jvm/build/test");
        }
        return new File(testClass.getProtectionDomain().getCodeSource().getLocation().toURI());
    }
}
