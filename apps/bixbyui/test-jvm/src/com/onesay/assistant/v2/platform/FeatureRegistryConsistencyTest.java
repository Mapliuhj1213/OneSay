package com.onesay.assistant.v2.platform;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.kernel.FeatureSwitch;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

/**
 * refactor/oss：功能注册表里有开关的项必须和真实 Settings 类读写同一个 prefs 值（prefs 名、key、默认值、关停文件），
 * 并且能力页拨开关必须走原设置页的 setter（DeviceCapsToggles 里每项都有 case）。
 */
public class FeatureRegistryConsistencyTest {

    /** 真实 Settings 类的绑定（从生产类常量 / FeatureSwitch 实例读，不手抄字符串）。 */
    static Map<String, FeatureSwitch> realBindings() {
        Map<String, FeatureSwitch> m = new LinkedHashMap<String, FeatureSwitch>();
        m.put("screen.ref", new FeatureSwitch(com.onesay.assistant.v2.vision.screenref.ScreenRefSettings.PREF,
                com.onesay.assistant.v2.vision.screenref.ScreenRefSettings.KEY_ENABLED, true,
                com.onesay.assistant.v2.vision.screenref.ScreenRefSettings.KILL_FILE_NAME));
        m.put("quicknote", com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin.SWITCH);
        m.put("imgsave", new FeatureSwitch(com.onesay.assistant.v2.imgsave.ImageSaveSettings.PREF,
                com.onesay.assistant.v2.imgsave.ImageSaveSettings.KEY_ENABLED, true, com.onesay.assistant.v2.imgsave.ImageSaveSettings.KILL));
        m.put("island", new FeatureSwitch(com.onesay.assistant.v2.island.IslandSettings.PREF,
                com.onesay.assistant.v2.island.IslandSettings.KEY_ENABLED, true, com.onesay.assistant.v2.island.IslandSettings.KILL));
        m.put("notice", com.onesay.assistant.v2.notice.NoticeSettings.SWITCH);
        m.put("errand.browser", com.onesay.assistant.v2.errand.ErrandSettings.BROWSER);
        return m;
    }

    static String src(String rel) throws Exception {
        return new String(Files.readAllBytes(new File(System.getProperty("bixby.root", "."), rel).toPath()), "UTF-8");
    }

    @Test public void everySwitchMatchesRealSettingsClass() {
        Map<String, FeatureSwitch> real = realBindings();
        Set<String> seen = new HashSet<String>();
        for (FeatureSpec f : FeatureRegistry.all()) {
            if (!f.hasSwitch()) continue;
            FeatureSwitch r = real.get(f.id);
            assertNotNull("有开关的功能必须在本测试登记真实 Settings 绑定：" + f.id, r);
            assertEquals(f.id + " prefs", r.prefs, f.prefs);
            assertEquals(f.id + " key", r.key, f.key);
            assertEquals(f.id + " 默认值", r.def, f.def);
            assertEquals(f.id + " 关停文件", Arrays.asList(r.killFiles), f.killFiles);
            seen.add(f.id);
        }
        assertEquals("登记的真实绑定都应在注册表里出现", real.keySet(), seen);
    }

    /** 没有 FeatureSwitch 实例的三个类：默认值写在 getBoolean(…, true) 里，从源码确认没被改。 */
    @Test public void sourceDefaultsUnchanged() throws Exception {
        assertTrue(src("app/src/com/onesay/assistant/v2/vision/screenref/ScreenRefSettings.java").contains("getBoolean(KEY_ENABLED, true)"));
        assertTrue(src("app/src/com/onesay/assistant/v2/imgsave/ImageSaveSettings.java").contains("getBoolean(KEY_ENABLED, true)"));
        assertTrue(src("app/src/com/onesay/assistant/v2/island/IslandSettings.java").contains("getBoolean(KEY_ENABLED, true)"));
    }

    @Test public void capsPageTogglesReuseOriginalSetters() throws Exception {
        String t = src("app/src/com/onesay/assistant/v2/ui/console/DeviceCapsToggles.java");
        for (FeatureSpec f : FeatureRegistry.all()) {
            if (!f.hasSwitch()) continue;
            assertTrue("DeviceCapsToggles 缺 case：" + f.id, t.contains("\"" + f.id + "\".equals(id)"));
        }
        // 原设置页的即时副作用都要调到
        assertTrue(t.contains("S1Hooks.refreshIslandMode(\"console\")"));
        assertTrue(t.contains("WakeService.syncGestureFlag(act)"));
        assertTrue(t.contains("NoticePlugin.refresh(act)"));
        String page = src("app/src/com/onesay/assistant/v2/ui/console/DeviceCapsPage.java");
        assertTrue(page.contains("DeviceCapsToggles.apply("));
        assertFalse("能力页不应直接写 prefs", page.contains("putBoolean("));
    }

    @Test public void idsUniqueGroupsKnown() {
        Set<String> ids = new HashSet<String>();
        List<String> groups = Arrays.asList(FeatureRegistry.GROUPS);
        for (FeatureSpec f : FeatureRegistry.all()) {
            assertTrue("重复 id " + f.id, ids.add(f.id));
            assertTrue("未知分组 " + f.group, groups.contains(f.group));
            assertNotNull(f.title);
            if (f.status == FeatureSpec.Status.NOT_BUILT) {
                assertEquals(FeatureRegistry.G_NOT_BUILT, f.group);
                assertFalse("未实现的项不应有开关", f.hasSwitch());
            }
        }
        // [ci-1114] 开源版删了「厂商专有」分组 6 项、新增通用替代项（wake.key / wake.cue / diag.settings / city.home / store.default / errand.browser），共 21 项
        assertEquals(21, FeatureRegistry.all().size());
        assertSame(FeatureRegistry.get("island"), FeatureRegistry.get("island"));
        assertNull(FeatureRegistry.get("nope"));
        int n = 0;
        for (List<FeatureSpec> l : FeatureRegistry.byGroup().values()) n += l.size();
        assertEquals(FeatureRegistry.all().size(), n);
    }

    @Test public void sixNotBuiltItemsRegistered() {
        int n = 0;
        for (FeatureSpec f : FeatureRegistry.all()) {
            if (f.status != FeatureSpec.Status.NOT_BUILT) continue;
            n++;
            assertFalse("未实现项要写所需能力：" + f.id, f.requires.isEmpty());
            assertTrue("未实现项要写替代：" + f.id, f.alternative != null && f.alternative.length() > 0);
        }
        // [ci-1114] exec.shizuku（priv-llm 插件）与 wake.other_key（无障碍按键唤醒 wake.key）已实现，未实现项 6 → 4
        assertEquals(4, n);
    }

    @Test public void wakeEntryOrderIdsExist() {
        for (String id : WakeEntry.Builtin.ORDER) assertNotNull("唤醒入口 id 不在注册表：" + id, FeatureRegistry.get(id));
    }
}
