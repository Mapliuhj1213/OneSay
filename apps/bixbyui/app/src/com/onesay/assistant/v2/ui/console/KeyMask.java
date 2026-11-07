package com.onesay.assistant.v2.ui.console;

/**
 * [console R12] API Key 打码显示（D8）：控制台<b>永远不把解密后的密钥 setText 到任何控件</b>，只显示这里给的摘要。
 * 纯 Java，JVM 可测。
 */
public final class KeyMask {

    /** 短于这个长度只显示「已配置」（太短的密钥露出末 4 位就等于露了大半）。 */
    public static final int MIN_LEN_FOR_TAIL = 12;

    private KeyMask() {}

    /** 「未配置」/「已配置」/「已配置 · sk-…3f2a」。 */
    public static String mask(String key) {
        String k = key == null ? "" : key.trim();
        if (k.length() == 0) return "未配置";
        if (k.length() < MIN_LEN_FOR_TAIL) return "已配置";
        String prefix = "";
        int dash = k.indexOf('-');
        if (dash > 0 && dash <= 4) prefix = k.substring(0, dash + 1);
        return "已配置 · " + prefix + "…" + k.substring(k.length() - 4);
    }

    /** 编辑框里输入的内容是否要保存（空 = 不改；只有空白也算空）。 */
    public static boolean shouldSave(String typed) {
        return typed != null && typed.trim().length() > 0;
    }

    /** OneSay 开源版：密钥对话框加 FLAG_SECURE（不能截屏、不进最近任务截图）。 */
    public static android.app.AlertDialog secure(android.app.AlertDialog d) {
        try {
            if (d != null && d.getWindow() != null) d.getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE);
        } catch (Throwable ignored) {
        }
        return d;
    }

    /** OneSay 开源版：保存后，剪贴板里正好是这把 Key 就清掉。 */
    public static void clearClipIf(android.content.Context ctx, String saved) {
        try {
            if (saved == null || saved.trim().length() == 0) return;
            android.content.ClipboardManager cm = (android.content.ClipboardManager) ctx.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            if (cm == null || !cm.hasPrimaryClip()) return;
            android.content.ClipData cd = cm.getPrimaryClip();
            if (cd == null || cd.getItemCount() == 0) return;
            CharSequence t = cd.getItemAt(0).coerceToText(ctx);
            if (t != null && t.toString().trim().equals(saved.trim())) cm.setPrimaryClip(android.content.ClipData.newPlainText("", ""));
        } catch (Throwable ignored) {
        }
    }
}
