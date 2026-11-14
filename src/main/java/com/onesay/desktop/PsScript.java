package com.onesay.desktop;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * 把内置的 PowerShell 脚本写到磁盘上。
 *
 * Windows PowerShell 5.1 用 -File 跑脚本时，文件没有 BOM 就按系统 ANSI 代码页读（中文 Windows 是 GBK），
 * 不按 UTF-8。脚本里的中文 UTF-8 字节被当成 GBK 拆开，会吞掉紧跟在后面的引号，
 * 报 ParserError「字符串缺少终止符」（2026-11-14 hotkey.ps1 在用户电脑上就是这样）。
 * 所以写盘时一律加 UTF-8 BOM：5.1 和 7.x 都按 UTF-8 读。资源本身有没有 BOM 都行。
 */
final class PsScript {
    static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private PsScript() { }

    /** 读内置资源，去掉可能已有的 BOM，返回脚本正文字节（UTF-8）。 */
    static byte[] resource(String res) throws Exception {
        try (InputStream in = PsScript.class.getResourceAsStream(res)) {
            if (in == null) throw new IllegalStateException("缺少资源 " + res);
            return stripBom(in.readAllBytes());
        }
    }

    /** 正文前加 UTF-8 BOM（已有就不重复加）。 */
    static byte[] withBom(byte[] body) {
        byte[] b = stripBom(body);
        byte[] out = new byte[BOM.length + b.length];
        System.arraycopy(BOM, 0, out, 0, BOM.length);
        System.arraycopy(b, 0, out, BOM.length, b.length);
        return out;
    }

    static byte[] stripBom(byte[] b) {
        if (b.length >= 3 && b[0] == BOM[0] && b[1] == BOM[1] && b[2] == BOM[2]) {
            byte[] r = new byte[b.length - 3];
            System.arraycopy(b, 3, r, 0, r.length);
            return r;
        }
        return b;
    }

    /** 把资源脚本写到 to（带 BOM，覆盖已有文件）。 */
    static void write(String res, Path to) throws Exception {
        Files.write(to, withBom(resource(res)), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
    }

    /** 只用 UTF-8 解码时才对的文本：PowerShell 5.1 实际看到的内容（有 BOM → UTF-8，没有 → 系统 ANSI，这里按 GBK）。 */
    static String asPowerShell51Reads(byte[] file) {
        if (file.length >= 3 && file[0] == BOM[0] && file[1] == BOM[1] && file[2] == BOM[2])
            return new String(file, 3, file.length - 3, StandardCharsets.UTF_8);
        return windowsAnsi936(file);
    }

    /**
     * 照 Windows 代码页 936 的解法：0x81–0xFE 开头的字节总是和下一个字节拼成一个字，拼不成合法字就成「?」——
     * 下一个字节哪怕是引号也一起被吞掉。Java 自带的 GBK 解码不吞，所以单写。
     */
    static String windowsAnsi936(byte[] b) {
        java.nio.charset.Charset gbk = java.nio.charset.Charset.forName("GBK");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < b.length; i++) {
            int x = b[i] & 0xFF;
            if (x < 0x80) { sb.append((char) x); continue; }
            if (x >= 0x81 && x <= 0xFE && i + 1 < b.length) {
                String two = new String(b, i, 2, gbk);
                sb.append(two.length() == 1 && two.charAt(0) != '\uFFFD' ? two : "?");
                i++;
                continue;
            }
            sb.append('?');
        }
        return sb.toString();
    }
}
