package com.onesay.assistant.v2.util;

import android.content.Context;

import java.io.File;

/**
 * 截图 / 界面树等<b>中间产物</b>改放 App 私有目录 {@code /data/data/com.onesay.assistant/cache/scratch/<用途>/}。
 *
 * <p>以前放 {@code /sdcard/Android/data/com.onesay.assistant/cache/}：Android 8 上任何有存储权限的 App 都能读到那张截图
 * （里面可能是聊天、验证码）。私有目录只有本 App（和 root）能读。
 *
 * <p>坑（2026-10-11 真机核实）：root 守护（magisk 上下文）在私有目录里新建的文件 SELinux 级别是 {@code s0}，
 * 而 App 是 {@code s0:c512,c768} —— App 能读但删不掉 / 写不了。所以 root 写完必须 {@link #fixupCmd}：
 * {@code chown} 成 App 的 uid、{@code chmod 600}、{@code restorecon}（按 seapp_contexts 补上 App 的类别）。
 * 真机上 restorecon 后为 {@code app_data_file:s0:c512,c768}、属主 u0_a222。
 */
public final class PrivateScratch {

    private PrivateScratch() {}

    /** 私有中间产物目录（不存在就建）。拿不到内部缓存目录时返回 null，调用方退回旧的外部缓存目录。 */
    public static File dir(Context ctx, String purpose) {
        try {
            File base = ctx.getCacheDir();
            if (base == null) return null;
            File d = new File(new File(base, "scratch"), purpose);
            if (!d.isDirectory() && !d.mkdirs()) return null;
            return d;
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * root 写完文件后的修正命令（纯函数）：属主改成目录属主（= App uid）、只给属主读写、恢复 App 的 SELinux 类别。
     * 每一步失败都不影响后续（{@code 2>/dev/null; } 串联），整体 {@code true} 结尾。
     *
     * @param path    root 写出的文件
     * @param ownerOf 用它的属主作为新属主（传私有目录本身）
     */
    public static String fixupCmd(String path, String ownerOf) {
        String p = quote(path);
        return "chown $(stat -c %u:%g " + quote(ownerOf) + ") " + p + " 2>/dev/null; "
                + "chmod 600 " + p + " 2>/dev/null; restorecon " + p + " >/dev/null 2>&1; true";
    }

    /**
     * 2026-10-25 exec 守护 v4：<b>App 先建好目标文件</b>（空、600、属主与 SELinux 标签都是 App 自己的），root 只往已有文件里写。
     * {@code screencap} / {@code uiautomator dump} / {@code cp} 对已存在的目标都是 {@code O_TRUNC} 原地重写、不换 inode，
     * 写完属主与标签（含 MCS 类别）仍是 App 的 —— 不再需要 root 端 chown / restorecon（v4 白名单里没有这两个程序）。
     * 同一思路已在城市库复制上实测过（HomeCityPolicy）。返回 false = 建不出来（调用方照旧发命令，再看 {@link #needsFixup}）。
     */
    public static boolean prepare(File f) {
        if (f == null) return false;
        try {
            java.io.FileOutputStream o = new java.io.FileOutputStream(f, false);
            o.close();
            f.setReadable(false, false);
            f.setWritable(false, false);
            f.setReadable(true, true);
            f.setWritable(true, true);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * root 写完后 App 自己读不了（说明 root 没有原地写，而是换成了 root 的新文件）→ 需要退回 {@link #fixupCmd}。
     * 这条兜底是复合脚本：exec 守护 v4 下只有 root 开了 allow_raw 才会执行（见 INTEGRATION.md「上机待验」）。
     */
    public static boolean needsFixup(File f) {
        return f != null && f.exists() && !f.canRead();
    }

    /** 单引号包裹（路径来自 App 自己的目录，不含单引号；仍按 shell 规则转义以防万一）。 */
    public static String quote(String s) {
        return "'" + (s == null ? "" : s.replace("'", "'\\''")) + "'";
    }

    /** 是否在私有目录里（给日志 / 测试用）。 */
    public static boolean isPrivatePath(String path) {
        return path != null && path.startsWith("/data/") && path.contains("/cache/scratch/");
    }
}
