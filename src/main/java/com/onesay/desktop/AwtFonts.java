package com.onesay.desktop;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;

/**
 * [fix-1114 实测 P1-1] 托盘菜单中文变方块的根因与修法。
 *
 * <p><b>根因</b>：OneSay 启动 Java 时带 {@code -Dfile.encoding=UTF-8}（OneSay.exe 进程内托管和以前的 javaw 命令行都带）。JDK 17 的
 * {@code sun.awt.FontConfiguration.setEncoding()} 用 {@code Charset.defaultCharset()}（= file.encoding = UTF-8）去 fontconfig 里找
 * 逻辑字体的组成序列 {@code sequence.*.UTF-8.<语言>.<地区>}。Windows 版 fontconfig 只给 UTF-8 写了 {@code .hi} 和 {@code .ja}，
 * 中文系统（zh_CN）找不到，退回 {@code sequence.allfonts=alphabetic/default,dingbats,symbol}——只有 Arial，没有宋体。
 * 托盘菜单是 AWT 原生菜单（{@code PopupMenu}），按这个序列画字，所以中文全是方块；Swing / WebView2 走别的路，不受影响。
 * 不是进程内托管引起的：系统 ANSI 码页（GBK）在两种启动方式下一样，差别只在 file.encoding。
 *
 * <p><b>修法</b>：不改 file.encoding（程序别处依赖 UTF-8）。AWT 初始化之前，把运行时自带的 {@code lib/fontconfig.properties.src}
 * 复制一份，补上 UTF-8 下的中 / 日 / 韩序列（中文用 chinese-ms936 = 宋体等），写到 OneSay 数据目录，再用 JDK 支持的
 * {@code sun.awt.fontconfig} 系统属性指过去。只在 Windows、默认字符集是 UTF-8、没人设过这个属性时做；任何一步不成就什么都不做。
 */
final class AwtFonts {
    private AwtFonts() { }

    static final String MARK = "# [onesay fix-1114] CJK sequences for UTF-8 (tray menu Chinese text)"; // properties 文件是 ISO-8859-1，标记只用 ASCII
    static final String EXTRA = String.join("\n",
            MARK,
            "sequence.allfonts.UTF-8.zh.CN=alphabetic,chinese-ms936,dingbats,symbol,chinese-ms936-extb",
            "sequence.allfonts.UTF-8.zh.SG=alphabetic,chinese-ms936,dingbats,symbol,chinese-ms936-extb",
            "sequence.allfonts.UTF-8.zh=alphabetic,chinese-ms936,dingbats,symbol,chinese-ms936-extb",
            "sequence.allfonts.UTF-8.zh.TW=alphabetic,chinese-ms950,dingbats,symbol,chinese-ms950-extb",
            "sequence.allfonts.UTF-8.zh.HK=alphabetic,chinese-ms950,chinese-hkscs,dingbats,symbol,chinese-ms950-extb",
            "sequence.allfonts.UTF-8.zh.MO=alphabetic,chinese-ms950,chinese-hkscs,dingbats,symbol,chinese-ms950-extb",
            "sequence.allfonts.UTF-8.ko=alphabetic,korean,dingbats,symbol",
            // 别的语言的系统：菜单字也是中文（OneSay 的界面是中文），宋体排在 Arial 后面兜底
            "sequence.allfonts.UTF-8=alphabetic/default,chinese-ms936,dingbats,symbol,chinese-ms936-extb",
            "");
    static final String OUT_NAME = "fontconfig-utf8.properties";

    /** 给 fontconfig 原文补上 UTF-8 序列；已经补过的原样返回。原文里已有的同名键不重复写（以原文为准）。 */
    static String patch(String src) {
        if (src == null) return null;
        if (src.contains(MARK)) return src;
        StringBuilder b = new StringBuilder(src);
        if (!src.endsWith("\n")) b.append('\n');
        b.append('\n');
        for (String line : EXTRA.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0 && !line.startsWith("#") && src.contains("\n" + line.substring(0, eq + 1))) continue;
            b.append(line).append('\n');
        }
        return b.toString();
    }

    /** Main 一开始调用（任何 AWT 之前）。返回写好的文件；没做返回 null。 */
    static Path prepare() {
        try {
            return prepare(System.getProperty("os.name", ""), Charset.defaultCharset(), System.getProperty("sun.awt.fontconfig"),
                    Paths.get(System.getProperty("java.home", ""), "lib", "fontconfig.properties.src"), Config.defaultDir(), true);
        } catch (Throwable t) {
            return null; // 字体只影响托盘菜单的显示，出错就照旧启动
        }
    }

    /** 可测试的版本：apply = 真的设系统属性。 */
    static Path prepare(String os, Charset cs, String already, Path src, Path dir, boolean apply) throws Exception {
        if (os == null || !os.toLowerCase(Locale.ROOT).startsWith("windows")) return null;
        if (cs == null || !StandardCharsets.UTF_8.equals(cs)) return null; // 不是 UTF-8 时 JDK 自带的序列本来就对
        if (already != null && !already.isEmpty()) return null; // 有人自己指定过，不动
        if (src == null || dir == null || !Files.isRegularFile(src)) return null;
        String text = new String(Files.readAllBytes(src), StandardCharsets.ISO_8859_1); // properties 文件按 ISO-8859-1 读写，原样搬
        String out = patch(text);
        Files.createDirectories(dir);
        Path f = dir.resolve(OUT_NAME);
        byte[] want = out.getBytes(StandardCharsets.ISO_8859_1);
        if (!Files.isRegularFile(f) || !java.util.Arrays.equals(Files.readAllBytes(f), want)) {
            Path tmp = Files.createTempFile(dir, "fontconfig", ".tmp");
            Files.write(tmp, want);
            Files.move(tmp, f, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
        if (apply) System.setProperty("sun.awt.fontconfig", f.toAbsolutePath().toString());
        return f;
    }
}
