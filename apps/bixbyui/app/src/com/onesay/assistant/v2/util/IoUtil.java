package com.onesay.assistant.v2.util;

import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;

/**
 * 文件 / 流读写公共工具（收敛 search/asr/executor 里各写一份的 readAll/readFile）。
 *
 * <p>约定：
 * <ul>
 *   <li>编码一律 UTF-8；</li>
 *   <li>{@code read*} 读完会关闭传入的流；</li>
 *   <li>带 {@code OrNull/Quietly} 后缀或返回 boolean 的方法不抛异常（失败返回 null/false），
 *       其余方法把 IO 异常原样抛给调用方（调用方要区分失败原因时用它）。</li>
 * </ul>
 * 纯 Java，无 android 依赖，可在 JVM 单测中直接使用。
 */
public final class IoUtil {

    public static final String UTF8 = "UTF-8";
    private static final int BUF = 8192;

    private IoUtil() {}

    /** 读完整个流为字节数组并关闭流。{@code in == null} 返回空数组。 */
    public static byte[] readAllBytes(InputStream in) throws java.io.IOException {
        if (in == null) return new byte[0];
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[BUF];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return bos.toByteArray();
        } finally {
            closeQuietly(in);
        }
    }

    /** 读完整个流为 UTF-8 字符串并关闭流（抛 IO 异常）。{@code in == null} 返回 ""。 */
    public static String readAll(InputStream in) throws java.io.IOException {
        return new String(readAllBytes(in), UTF8);
    }

    /** 同 {@link #readAll}，但吞掉异常：失败返回 ""（与旧版 DashScope/SearchHttp 的 readAll 语义一致）。 */
    public static String readAllQuietly(InputStream in) {
        try {
            return readAll(in);
        } catch (Throwable t) {
            closeQuietly(in);
            return "";
        }
    }

    /** 读文件为 UTF-8 字符串；文件不存在或读失败返回 null。 */
    public static String readFileOrNull(File f) {
        if (f == null || !f.isFile()) return null;
        try {
            return readAll(new FileInputStream(f));
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 原子写文件：先写同目录 {@code .tmp}，再 rename 覆盖。避免进程中途被杀留下半截 JSON。
     * @return 是否成功
     */
    public static boolean writeFileAtomic(File f, String text) {
        if (f == null) return false;
        File tmp = new File(f.getPath() + ".tmp");
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(tmp, false);
            fos.write((text == null ? "" : text).getBytes(UTF8));
            fos.flush();
            try { fos.getFD().sync(); } catch (Throwable ignored) {}
            fos.close();
            fos = null;
            if (tmp.renameTo(f)) return true;
            // 个别文件系统 rename 不能覆盖：退回直接写
            return writeFile(f, text);
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(fos);
            if (tmp.exists()) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
        }
    }

    /** 直接覆盖写文件（非原子）。@return 是否成功 */
    public static boolean writeFile(File f, String text) {
        if (f == null) return false;
        FileOutputStream fos = null;
        try {
            fos = new FileOutputStream(f, false);
            fos.write((text == null ? "" : text).getBytes(UTF8));
            fos.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            closeQuietly(fos);
        }
    }

    public static void closeQuietly(Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (Throwable ignored) {
        }
    }
}
