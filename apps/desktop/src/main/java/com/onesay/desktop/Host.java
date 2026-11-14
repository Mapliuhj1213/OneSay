package com.onesay.desktop;

/**
 * OneSay.exe 进程内托管（-Donesay.host=1）时，启动器在本进程里跑 Java，并把下面几个 native 方法注册进来：
 * 主窗口（WebView2，属于 OneSay.exe）和带 AppUserModelID 的快捷方式交给启动器做。
 * 不是托管（直接 java -jar、旧启动器、测试）或注册失败时，各方法返回 false，调用方照旧走原来的做法。
 */
final class Host {
    /** 任务栏分组 / 固定图标用的 AppUserModelID；与 launcher/Program.cs 的 AppId、安装程序写的必须相同。 */
    static final String AUMID = "OneSay.Desktop";

    private Host() { }

    static boolean present() {
        return "1".equals(System.getProperty("onesay.host"));
    }

    /** 在 OneSay.exe 自己的窗口里打开界面；返回 false 表示没开（不是托管 / 没有 WebView2 运行时），调用方自己开。 */
    static boolean open(String url) {
        if (!present()) return false;
        try {
            return open0(url, Config.defaultDir().toString());
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** 界面服务起来了：告诉启动器地址（--tray 启动后再双击 OneSay.exe 时用来叫出窗口）。 */
    static void ready(String url) {
        if (!present()) return;
        try {
            ready0(url, Config.defaultDir().toString());
        } catch (UnsatisfiedLinkError ignored) { }
    }

    /** 建（覆盖）指向 exe 的快捷方式，带 AppUserModelID。返回 false 表示没建，调用方自己建。 */
    static boolean shortcut(String lnk, String exe) {
        if (!present()) return false;
        try {
            return shortcut0(lnk, exe);
        } catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** 当前用户的桌面目录（托管时由启动器给；取不到返回 null）。 */
    static String desktopDir() {
        if (!present()) return null;
        try {
            return desktopDir0();
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    private static native boolean open0(String url, String dataDir);
    private static native void ready0(String url, String dataDir);
    private static native boolean shortcut0(String lnk, String exe);
    private static native String desktopDir0();
}
