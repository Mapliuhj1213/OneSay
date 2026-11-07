package com.onesay.assistant.v2.prompt;

import android.content.Context;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;

/**
 * ★ arch/tool-tiers（A 路）：提示词资源文件读取（assets/agent_prompt.md）。
 *
 * <p>读取顺序：内存缓存 → {@link #init} / 调用方给的 Context 的 assets → JVM 下 {@code -Dbixby.root}/app/assets（与 RoleCatalog 同口径）。
 * 都读不到返回 null（调用方记日志并用最小兜底提示词，不抛异常）。
 */
public final class PromptSource {

    private static final String TAG = "PromptSource";

    public static final String AGENT_PROMPT_ASSET = "agent_prompt.md";

    private static volatile Context sApp;
    private static volatile String sCached;
    private static volatile String sOverride;   // 单测注入

    private PromptSource() {}

    /** 服务启动时调用一次（PhonePlugin.onServiceCreate）。 */
    public static void init(Context ctx) {
        if (ctx != null) sApp = ctx.getApplicationContext() != null ? ctx.getApplicationContext() : ctx;
    }

    /** 单测：直接给文本（null = 恢复正常读取）。 */
    public static void setOverrideForTest(String text) {
        sOverride = text;
    }

    public static String agentPrompt(Context ctx) {
        String o = sOverride;
        if (o != null) return o;
        String c = sCached;
        if (c != null) return c;
        synchronized (PromptSource.class) {
            if (sCached != null) return sCached;
            Context use = ctx != null ? ctx : sApp;
            String text = null;
            if (use != null) {
                InputStream in = null;
                try {
                    in = use.getAssets().open(AGENT_PROMPT_ASSET);
                    text = readAll(in);
                } catch (Throwable t) {
                    Log.w(TAG, "assets 读取失败: " + t.getMessage());
                } finally {
                    close(in);
                }
            }
            if (text == null) {
                String root = System.getProperty("bixby.root");
                File f = new File(root != null ? root : ".", "app/assets/" + AGENT_PROMPT_ASSET);
                if (f.isFile()) {
                    InputStream in = null;
                    try {
                        in = new FileInputStream(f);
                        text = readAll(in);
                    } catch (Throwable t) {
                        Log.w(TAG, "文件读取失败: " + t.getMessage());
                    } finally {
                        close(in);
                    }
                }
            }
            if (text != null) sCached = text;
            return text;
        }
    }

    static String readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return new String(bos.toByteArray(), "UTF-8");
    }

    private static void close(InputStream in) {
        if (in == null) return;
        try { in.close(); } catch (Throwable ignored) {}
    }
}
