package com.onesay.assistant.v2.suggest.sources;

/**
 * ★ feat/suggest：解析 toybox {@code inotifyd - DIR:wy} 的输出行（纯 Java，JVM 可测）。
 * toybox 的格式是「事件字母 \t 目录 \t 文件名」；有的版本 / busybox 是「事件 目录 文件名」（空格）或「事件 \t 目录/文件名」。
 * 格式没核实过（上机核实项），所以三种都认；认不出返回 null（宿主只记「格式未识别」，不记原行）。
 */
public final class InotifydLine {
    private InotifydLine() {
    }

    /**
     * @return {event, fullPath}；接受 n（新建）/ w（写完关闭）/ y（移入）。
     * ★ fix/shot-1104：三星 Note8 上系统截图只报 n、从不报 w，所以 n 也认——宿主收到后自己等文件大小稳定再处理。
     */
    public static String[] parse(String line) {
        if (line == null) return null;
        // 只去掉行尾换行（不 trim 掉尾部的 \t：「w\tDIR\t」是空文件名，不能被当成两段式的「目录即文件」）
        String l = line;
        while (l.length() > 0 && (l.charAt(l.length() - 1) == '\n' || l.charAt(l.length() - 1) == '\r')) l = l.substring(0, l.length() - 1);
        while (l.length() > 0 && l.charAt(0) == ' ') l = l.substring(1);
        if (l.trim().length() < 3) return null;
        String[] f = l.split("\t", -1);
        if (f.length < 2) f = l.trim().split(" +", 3);
        if (f.length < 2) return null;
        String ev = f[0].trim();
        if (ev.length() == 0) return null;
        char c = ev.charAt(0);
        if (c != 'n' && c != 'w' && c != 'y') return null;
        String path;
        if (f.length >= 3) {
            String dir = f[1].trim(), name = f[2].trim();
            if (name.length() == 0) return null;
            path = dir.endsWith("/") ? dir + name : dir + "/" + name;
        } else {
            path = f[1].trim();
            if (!path.contains("/")) return null;
        }
        if (!path.startsWith("/")) return null;
        return new String[]{String.valueOf(c), path};
    }
}
