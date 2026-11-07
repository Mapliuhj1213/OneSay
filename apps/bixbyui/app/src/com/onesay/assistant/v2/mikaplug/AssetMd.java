package com.onesay.assistant.v2.mikaplug;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ★ skill-pack：assets 根目录平铺 markdown 的共用加载器（App 知识 {@code kb_*.md} 与技能 {@code skill_*.md} 共用）。
 * assets 打包是平铺的（build_linux.sh {@code cp app/assets/*}），不建子目录，所以按文件名前缀区分种类。
 * JVM 单测没有 Context 时从 {@code -Dbixby.root}/app/assets 读。按文件名排序，读坏的文件跳过（不抛异常）。
 */
public final class AssetMd {
    private AssetMd() {
    }

    /** @return 文件名 → 内容（UTF-8），按文件名排序。 */
    public static Map<String, String> load(Context ctx, String prefix) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        try {
            if (ctx != null) {
                String[] names = ctx.getAssets().list("");
                if (names != null) {
                    Arrays.sort(names);
                    for (String n : names) {
                        if (!n.startsWith(prefix) || !n.endsWith(".md")) continue;
                        try {
                            out.put(n, read(ctx.getAssets().open(n)));
                        } catch (Throwable ignored) {
                        }
                    }
                }
                return out;
            }
        } catch (Throwable ignored) {
        }
        File dir = new File(System.getProperty("bixby.root", "."), "app/assets");
        File[] fs = dir.listFiles();
        if (fs == null) return out;
        Arrays.sort(fs);
        for (File f : fs) {
            if (!f.getName().startsWith(prefix) || !f.getName().endsWith(".md")) continue;
            try {
                out.put(f.getName(), read(new FileInputStream(f)));
            } catch (Throwable ignored) {
            }
        }
        return out;
    }

    static String read(InputStream in) throws java.io.IOException {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }
}
