package com.onesay.assistant.v2.kernel;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/** 测试助手：presence 测试用它检查「宿主 → 内核 → 插件 → *Hooks」这条接线（2026-10-09 插件内核迁移）。 */
public final class KernelWiring {

    private KernelWiring() {}

    public static String src(String rel) {
        try {
            String root = System.getProperty("bixby.root", ".");
            File f = new File(root, rel);
            if (!f.exists()) f = new File(rel);
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    /** 插件源码（kernel/plugins/&lt;name&gt;.java）。 */
    public static String plugin(String name) {
        return src("app/src/com/onesay/assistant/v2/kernel/plugins/" + name + ".java");
    }

    public static final String K = "com.onesay.assistant.v2.kernel.FeatureKernel.get()";

    /** 宿主文件里是否调用了内核的某个挂钩（如 ".takeover(mContext, query, mL2)"）。 */
    public static boolean hostCalls(String hostRel, String call) {
        return src(hostRel).contains(K + call);
    }

    /** 插件 id 在某挂钩顺序里的位置（-1 = 不在）。 */
    public static int pos(FeatureHook hook, String id) {
        List<String> ids = FeatureKernel.get().orderIds(hook);
        return ids.indexOf(id);
    }

    /** a 在 b 之前（都在列表里）。 */
    public static boolean before(FeatureHook hook, String a, String b) {
        int pa = pos(hook, a), pb = pos(hook, b);
        return pa >= 0 && pb >= 0 && pa < pb;
    }
}
