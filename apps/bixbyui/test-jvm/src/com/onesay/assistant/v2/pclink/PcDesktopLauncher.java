package com.onesay.assistant.v2.pclink;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * [pclink] 互通测试用：在子进程里起电脑端 {@code app --tray}，但跳过 {@code App.launch} 开头的「已经开着一个就只叫出窗口」。
 *
 * <p>电脑端 0.4.2 的 {@code App.launch} 先 ping 固定界面端口 127.0.0.1:47830，有别的 OneSay 实例在答（同一台机器上
 * 另一个互通测试、或手动起的测试电脑端）就直接 return，进程马上退出——互通测试的 @BeforeClass 因此报
 * 「电脑端进程提前退出」，整类 8 例一个都没跑（oss-device-1108：42 例 1 失败就是这个）。这里照抄 {@code launch} 的其余步骤：
 * {@code Config.load} → {@code App.autoSetup} → {@code new App(cfg).start()} → join；界面端口被占时 {@code App.start} 自己退到随机端口。
 *
 * <p>用法：{@code java -cp <电脑端 jar>:<测试 classes> com.onesay.assistant.v2.pclink.PcDesktopLauncher <配置文件>}
 */
public final class PcDesktopLauncher {
    public static void main(String[] a) throws Exception {
        Path cfgFile = Paths.get(a[0]);
        Class<?> cfgC = Class.forName("com.onesay.desktop.Config");
        Class<?> appC = Class.forName("com.onesay.desktop.App");
        Object cfg = cfgC.getMethod("load", Path.class).invoke(null, cfgFile);
        Method setup = appC.getDeclaredMethod("autoSetup", cfgC);
        setup.setAccessible(true);
        setup.invoke(null, cfg);
        Constructor<?> ctor = appC.getDeclaredConstructor(cfgC);
        ctor.setAccessible(true);
        Object app = ctor.newInstance(cfg);
        Method start = appC.getDeclaredMethod("start");
        start.setAccessible(true);
        start.invoke(app);
        System.out.println("[PcDesktopLauncher] started");
        Thread.currentThread().join();
    }
}
