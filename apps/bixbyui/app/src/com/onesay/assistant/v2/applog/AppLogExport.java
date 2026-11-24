package com.onesay.assistant.v2.applog;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * [settings-diag-1124] 「导出日志」打包（纯 Java，JVM 可测）：一个 zip，里面是
 * <ul>
 *   <li>{@code OneSay日志.txt}：开头一段「这台手机 / 这个版本 / 设置清单」，后面是应用内日志（从旧到新）；</li>
 *   <li>{@code 本应用系统日志.txt}（有才放）：只抓 OneSay 自己进程的系统日志。</li>
 * </ul>
 * 每一段内容进 zip 前都再过一次 {@link AppLogSanitizer}：头部信息、logcat 都不例外。
 * 导出文件放在私有目录 {@code files/applog/export/}，每次导出先删掉上一次的。
 */
public final class AppLogExport {

    private AppLogExport() {}

    public static final String LOG_ENTRY = "OneSay日志.txt";
    public static final String LOGCAT_ENTRY = "本应用系统日志.txt";

    /** 打包结果。 */
    public static final class Result {
        public final File file;
        public final int lines;
        public final boolean withLogcat;

        Result(File file, int lines, boolean withLogcat) {
            this.file = file;
            this.lines = lines;
            this.withLogcat = withLogcat;
        }
    }

    public static String fileName(long now) {
        return "OneSay-log-" + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.ROOT).format(new Date(now)) + ".zip";
    }

    /**
     * @param exportDir 导出目录（会先清掉里面旧的 zip）
     * @param header    头部信息（多行；每行再脱敏）
     * @param logcat    本应用 logcat（可空）
     */
    public static Result build(File exportDir, String header, String logcat, long now) throws IOException {
        if (!exportDir.isDirectory() && !exportDir.mkdirs()) throw new IOException("建不了导出目录");
        File[] old = exportDir.listFiles();
        if (old != null) for (File f : old) if (f.getName().endsWith(".zip")) f.delete();
        String body = AppLog.readAll();
        int lines = 0;
        for (int i = 0; i < body.length(); i++) if (body.charAt(i) == '\n') lines++;
        StringBuilder main = new StringBuilder();
        main.append(cleanBlock(header == null ? "" : header)).append('\n');
        main.append("==== 应用内日志（").append(lines).append(" 行，从旧到新）====\n");
        main.append(body.length() == 0 ? "（还没有日志）\n" : body);
        File out = new File(exportDir, fileName(now));
        ZipOutputStream zos = new ZipOutputStream(new FileOutputStream(out));
        boolean withCat = false;
        try {
            put(zos, LOG_ENTRY, main.toString());
            if (logcat != null && logcat.trim().length() > 0) {
                put(zos, LOGCAT_ENTRY, cleanBlock(logcat));
                withCat = true;
            }
        } finally {
            zos.close();
        }
        return new Result(out, lines, withCat);
    }

    /** 多行文本逐行脱敏（单行规则：密钥、网址路径、个人信息）。 */
    public static String cleanBlock(String text) {
        if (text == null) return "";
        StringBuilder b = new StringBuilder(text.length());
        int start = 0;
        while (start <= text.length()) {
            int nl = text.indexOf('\n', start);
            String line = nl < 0 ? text.substring(start) : text.substring(start, nl);
            if (line.length() > 0 || nl >= 0) b.append(AppLogSanitizer.clean(line)).append('\n');
            if (nl < 0) break;
            start = nl + 1;
        }
        return b.toString();
    }

    private static void put(ZipOutputStream zos, String name, String text) throws IOException {
        zos.putNextEntry(new ZipEntry(name));
        zos.write(text.getBytes("UTF-8"));
        zos.closeEntry();
    }

    /**
     * 从整段 logcat 里只留 OneSay 自己的行：threadtime 格式的 pid 在我们的 pid 集合里，或这一行写着我们的包名
     * （系统进程报「com.onesay.assistant 崩了 / 被杀」那种）。
     */
    public static String filterOwn(String logcat, List<Integer> pids, String pkg) {
        if (logcat == null) return "";
        List<String> keep = new ArrayList<String>();
        for (String line : logcat.split("\n")) {
            if (line.length() == 0) continue;
            Integer pid = pidOf(line);
            boolean mine = (pid != null && pids != null && pids.contains(pid)) || (pkg != null && pkg.length() > 0 && line.indexOf(pkg) >= 0);
            if (mine) keep.add(line);
        }
        StringBuilder b = new StringBuilder();
        for (String s : keep) b.append(s).append('\n');
        return b.toString();
    }

    /** threadtime：「11-24 10:50:01.123  1234  1250 I Tag: msg」→ 1234。认不出来返回 null。 */
    static Integer pidOf(String line) {
        String[] p = line.trim().split("\\s+", 5);
        if (p.length < 4) return null;
        try {
            return Integer.valueOf(p[2]);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
