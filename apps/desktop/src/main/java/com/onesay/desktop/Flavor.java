package com.onesay.desktop;

import java.io.InputStream;
import java.util.Properties;

/**
 * [preview-1120] 正式版 / 预览版（Preview）。预览版和正式版能同时装在一台电脑上互不打扰：
 * 名字、窗口标题带「Preview」，数据目录、工作区、工具目录、端口、开机自启项、AppUserModelID 都分开。
 *
 * <p>怎么算预览版：jar 里有 {@code onesay/flavor.properties}（只有 {@code build-preview.sh} 打的 jar 带），
 * 或者启动时 {@code -Donesay.flavor=preview}。正式版构建（build.sh）没有这个文件，下面全是原来的值，行为不变。
 */
public final class Flavor {
    private Flavor() { }

    public static final boolean PREVIEW;
    /** 预览版的版本后缀（如 -preview.1120）；正式版空。 */
    public static final String SUFFIX;

    static {
        Properties p = new Properties();
        try (InputStream in = Flavor.class.getClassLoader().getResourceAsStream("onesay/flavor.properties")) {
            if (in != null) p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception ignored) { }
        String f = System.getProperty("onesay.flavor", p.getProperty("flavor", "release"));
        PREVIEW = "preview".equalsIgnoreCase(f.trim());
        SUFFIX = PREVIEW ? p.getProperty("suffix", "-preview") : "";
    }

    /** 给人看的名字：OneSay / OneSay Preview。 */
    public static String name() { return PREVIEW ? "OneSay Preview" : "OneSay"; }

    /** 目录名（%APPDATA%、%USERPROFILE%、%LOCALAPPDATA%、Programs 下）：OneSay / OneSay-Preview。 */
    public static String dir() { return PREVIEW ? "OneSay-Preview" : "OneSay"; }

    /** 非 Windows 的配置目录名：onesay / onesay-preview。 */
    public static String unixDir() { return PREVIEW ? "onesay-preview" : "onesay"; }

    /** 显示的版本号：正式版不变，预览版 0.5.1-preview.1120。 */
    public static String version() { return Main.VERSION + SUFFIX; }

    /** 局域网端口（UDP 发现用 +1）：正式版 47820，预览版 47920。 */
    public static int lanPort() { return PREVIEW ? 47920 : 47820; }

    /** 界面端口：正式版 47830，预览版 47930。 */
    public static int uiPort() { return PREVIEW ? 47930 : 47830; }

    /** 开机自启（HKCU\...\Run）里的值名。 */
    public static String runValue() { return PREVIEW ? "OneSay Preview" : "OneSay"; }

    /** 任务栏分组用的 AppUserModelID（和启动器、安装程序一致）。 */
    public static String aumid() { return PREVIEW ? "OneSay.Desktop.Preview" : "OneSay.Desktop"; }
}
