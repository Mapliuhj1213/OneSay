package com.onesay.assistant.v2.ui.console;

import com.onesay.assistant.v2.entry.BuiltinEntries;
import com.onesay.assistant.v2.selfset.SelfCatalog;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.Assert.*;

/**
 * [settings-diag-1124] 设置页的静态核对（不连设备）：唤起入口都在自身设置清单里、入口说明行不露代号、
 * 同一项不在两页各放一份、按钮字数不超、日志行不提别家名字。
 */
public class SettingsPage1124Test {

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    static final String CONSOLE = "app/src/com/onesay/assistant/v2/ui/console/";

    @Test public void everyEntryIsInSelfCatalog() throws Exception {
        int n = 0;
        for (Field f : BuiltinEntries.class.getDeclaredFields()) {
            if (!Modifier.isStatic(f.getModifiers()) || f.getType() != String.class) continue;
            f.setAccessible(true);
            String v = (String) f.get(null);
            if (v == null || !v.startsWith("entry.")) continue;
            n++;
            assertNotNull("唤起入口 " + v + " 不在自身设置清单里（聊天里问不到、唤起方式页也写不出还差哪步）", SelfCatalog.get(v));
        }
        assertTrue(n >= 6);
    }

    @Test public void entrySubLinesArePlain() {
        List<SelfCatalog.Need> miss = new ArrayList<SelfCatalog.Need>();
        miss.add(SelfCatalog.need(SelfCatalog.N_MIC));
        miss.add(SelfCatalog.need(SelfCatalog.N_ARM64));
        String[] outs = {
                EntryRows.sub("entry.wake_word", "喊名字叫出 OneSay", true, miss, true),
                EntryRows.sub("entry.wake_word", "喊名字叫出 OneSay", true, miss.subList(0, 1), false),
                EntryRows.sub("entry.wake_word", "喊名字叫出 OneSay", false, miss.subList(0, 1), false),
                EntryRows.sub("entry.assistant", "长按电源键", true, new ArrayList<SelfCatalog.Need>(), false),
        };
        for (String s : outs) {
            for (String bad : new String[]{"MIC", "ARM64", "API", "Android", "perm.", "dev.", "entry.", "null"}) assertFalse(s, s.contains(bad));
        }
        assertTrue(outs[0], outs[0].contains("这台手机用不了"));
        assertTrue(outs[1], outs[1].contains("还差一步"));
        assertTrue(outs[2], outs[2].contains("打开前还要"));
    }

    @Test public void noDuplicateAcrossPages() throws Exception {
        String about = src(CONSOLE + "AboutPage.java");
        assertFalse("关于页不再放浅色外观", about.contains("\"浅色外观\""));
        assertFalse("关于页不再放设备能力入口", about.contains("\"设备能力与功能\""));
        String design = src(CONSOLE + "DesignPages.java");
        assertEquals("「唤起方式」入口只有一个", 1, count(design, "k.linkRow(gv, \"唤起方式\""));
        assertEquals("「设备能力与执行后端」入口只有一个", 1, count(design, "\"设备能力与执行后端\""));
        assertFalse("高级页不再另放「设为默认助理」", design.contains("AssistRole.ROW_TITLE"));
        String dev = src(CONSOLE + "DeviceCapsPage.java");
        assertFalse("不再列系统版本行", Pattern.compile("\"Android (8|11|14)").matcher(dev).find());
        assertFalse("不再列 arm64 行", dev.contains("\"arm64"));
    }

    @Test public void actionButtonsAreShort() throws Exception {
        Pattern p = Pattern.compile("k\\.actionRow\\(\\s*\\w+,\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\",\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
        for (String f : Arrays.asList("DeviceCapsPage.java", "LogRows.java", "DesignPages.java", "StatusPage.java")) {
            Matcher m = p.matcher(src(CONSOLE + f));
            while (m.find()) assertTrue(f + " 按钮「" + m.group(3) + "」太长，会挤掉标题", m.group(3).length() <= 4);
        }
    }

    @Test public void logRowsTextIsPlain() throws Exception {
        String s = LogRows.EXPORT_SUB + LogRows.EXPORT_TITLE + LogRows.CLEAR_TITLE + src(CONSOLE + "LogRows.java");
        for (String bad : new String[]{"微信", "QQ", "logcat", "adb", "Bixby", "三星"}) assertFalse("日志行出现「" + bad + "」", s.contains(bad));
        assertTrue("清空日志先确认", src(CONSOLE + "LogRows.java").contains("k.confirm("));
        assertTrue("新代码不用裸 Thread", !src(CONSOLE + "LogRows.java").contains("new Thread("));
    }

    private static int count(String s, String sub) {
        int n = 0;
        for (int i = s.indexOf(sub); i >= 0; i = s.indexOf(sub, i + sub.length())) n++;
        return n;
    }
}
