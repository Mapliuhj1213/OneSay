package com.onesay.plugin.exec.shizuku;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.Arrays;
import java.util.Collection;

import static org.junit.Assert.*;

/**
 * [已知缺陷 P2] Shizuku 白名单的模拟按键「电源键除外，避免误锁屏 / 关机菜单」只按字符串比较 "26" / "223"，
 * 而 {@code [0-9]{1,3}} 放行任何 1–3 位数字：input keyevent 026（Android 按整数解析 = 26 电源键）被放行，
 * 长按 026 = 关机菜单。另外 KEYCODE_SOFT_SLEEP（276）同样会锁屏，没在禁用表里。插件默认关。
 * 期望：按解析后的键码判断（26 / 223 / 276 及其前导零写法都拒）。
 */
@RunWith(Parameterized.class)
public class AdvBugShizukuKeyTest {

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> data() {
        return Arrays.asList(new Object[][]{{"input keyevent 026"}, {"input keyevent --longpress 026"}, {"input keyevent --longpress 276"},
                {"input keyevent KEYCODE_SOFT_SLEEP"}, {"input keyevent 276"}});
    }

    private final String cmd;

    public AdvBugShizukuKeyTest(String cmd) {
        this.cmd = cmd;
    }

    @Test public void blocked() {
        assertFalse("[" + cmd + "] 电源 / 睡眠键被放行", new ShizukuWhitelist("com.onesay.assistant").check(cmd).allowed);
    }
}
