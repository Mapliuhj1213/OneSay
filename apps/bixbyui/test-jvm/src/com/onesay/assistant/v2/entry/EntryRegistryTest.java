package com.onesay.assistant.v2.entry;

import com.onesay.assistant.vendor.samsung.BixbyKeyEntry;
import com.onesay.plugin.api.entry.EntryEnv;
import com.onesay.plugin.api.entry.EntryRegistry;
import com.onesay.plugin.api.entry.EntryTrigger;
import com.onesay.plugin.api.entry.SummonHost;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 唤起入口插件点：注册、默认开关、可用性、触发闸门、启停与 Manifest 声明。 */
public class EntryRegistryTest {

    static final class Env implements EntryEnv {
        int sdk = 28;
        final Map<String, EntryTrigger.Tri> caps = new HashMap<String, EntryTrigger.Tri>();
        public Object androidContext() { return null; }
        public int sdkInt() { return sdk; }
        public EntryTrigger.Tri cap(String n) { EntryTrigger.Tri t = caps.get(n); return t == null ? EntryTrigger.Tri.UNKNOWN : t; }
    }

    final List<String> summoned = new ArrayList<String>();
    final List<String> switched = new ArrayList<String>();
    Env env;

    @Before public void setUp() {
        EntryRegistry.reset();
        env = new Env();
        EntryRegistry.setEnv(env);
        EntryRegistry.setHost(new SummonHost() {
            public void summon(String source, EntryTrigger.Mode mode) { summoned.add(source + "/" + mode); }
        });
        BuiltinEntries.registerAll(new BuiltinEntries.ComponentSwitch() {
            public void setComponentEnabled(String id, boolean on) { switched.add(id + "=" + on); }
        });
        EntryRegistry.register(new BixbyKeyEntry());
    }

    @After public void tearDown() { EntryRegistry.reset(); }

    @Test public void builtinsRegisteredInOrder() {
        List<String> ids = new ArrayList<String>();
        for (EntryTrigger t : EntryRegistry.all()) ids.add(t.id());
        assertEquals(java.util.Arrays.asList(BuiltinEntries.ASSISTANT, BuiltinEntries.QS_TILE, BuiltinEntries.WIDGET,
                BuiltinEntries.SHORTCUT, BuiltinEntries.WAKE_WORD, BuiltinEntries.INTENT, BixbyKeyEntry.ID), ids);
    }

    @Test public void defaults_micListenersAndPublicIntentOff() {
        assertTrue(EntryRegistry.isEnabled(BuiltinEntries.ASSISTANT));
        assertTrue(EntryRegistry.isEnabled(BuiltinEntries.QS_TILE));
        assertTrue(EntryRegistry.isEnabled(BuiltinEntries.WIDGET));
        assertTrue(EntryRegistry.isEnabled(BuiltinEntries.SHORTCUT));
        assertFalse("唤醒词常驻开麦，默认关", EntryRegistry.isEnabled(BuiltinEntries.WAKE_WORD));
        assertFalse("任何 App 都能发的公开 Intent 默认关", EntryRegistry.isEnabled(BuiltinEntries.INTENT));
        assertFalse("厂商按键劫持默认关", EntryRegistry.isEnabled(BixbyKeyEntry.ID));
    }

    @Test public void fire_dropsDisabledAndUnknown() {
        assertFalse(EntryRegistry.fire(BuiltinEntries.INTENT, EntryTrigger.Mode.VOICE));
        assertFalse(EntryRegistry.fire("entry.nope", EntryTrigger.Mode.VOICE));
        assertTrue(summoned.isEmpty());
        EntryRegistry.setEnabled(BuiltinEntries.INTENT, true);
        assertTrue(EntryRegistry.fire(BuiltinEntries.INTENT, EntryTrigger.Mode.TEXT));
        assertTrue(EntryRegistry.fire(BuiltinEntries.QS_TILE, null));
        assertEquals(java.util.Arrays.asList("entry.intent/TEXT", "entry.qs_tile/VOICE"), summoned);
    }

    @Test public void fire_withoutHostIsDropped() {
        EntryRegistry.setHost(null);
        assertFalse(EntryRegistry.fire(BuiltinEntries.QS_TILE, EntryTrigger.Mode.VOICE));
    }

    @Test public void availability_bySdkAndCaps() {
        env.sdk = 24;
        assertEquals(EntryTrigger.Tri.NO, EntryRegistry.availability(BuiltinEntries.SHORTCUT));   // 需要 25
        assertEquals(EntryTrigger.Tri.YES, EntryRegistry.availability(BuiltinEntries.QS_TILE));
        assertNotNull(EntryRegistry.get(BuiltinEntries.SHORTCUT).unavailableReason(env));
        // 唤醒词：MIC + ARM64
        assertEquals(EntryTrigger.Tri.UNKNOWN, EntryRegistry.availability(BuiltinEntries.WAKE_WORD));
        env.caps.put("MIC", EntryTrigger.Tri.YES); env.caps.put("ARM64", EntryTrigger.Tri.YES);
        assertEquals(EntryTrigger.Tri.YES, EntryRegistry.availability(BuiltinEntries.WAKE_WORD));
        env.caps.put("MIC", EntryTrigger.Tri.NO);
        assertEquals(EntryTrigger.Tri.NO, EntryRegistry.availability(BuiltinEntries.WAKE_WORD));
        assertTrue(EntryRegistry.get(BuiltinEntries.WAKE_WORD).unavailableReason(env).contains("MIC"));
    }

    @Test public void bixbyKey_needsSamsungKeyRootDaemon_andIsOptIn() {
        EntryRegistry.setEnabled(BixbyKeyEntry.ID, true);
        env.caps.put("BIXBY_KEY", EntryTrigger.Tri.NO);
        assertFalse("非三星：开了也不能触发", EntryRegistry.canFire(BixbyKeyEntry.ID));
        env.caps.put("BIXBY_KEY", EntryTrigger.Tri.YES); env.caps.put("ROOT", EntryTrigger.Tri.YES); env.caps.put("KEY_DAEMON", EntryTrigger.Tri.YES);
        assertTrue(EntryRegistry.canFire(BixbyKeyEntry.ID));
        EntryRegistry.setEnabled(BixbyKeyEntry.ID, false);
        assertFalse(EntryRegistry.canFire(BixbyKeyEntry.ID));
    }

    @Test public void apply_startsAndStopsComponents() {
        List<String> on = EntryRegistry.apply();
        assertTrue(on.contains(BuiltinEntries.QS_TILE));
        assertFalse(on.contains(BuiltinEntries.INTENT));
        assertTrue(switched.contains("entry.qs_tile=true"));
        assertFalse("默认关的入口第一次 apply 不去碰组件（Manifest 里本来就是停用）", switched.contains("entry.intent=false"));
        switched.clear();
        EntryRegistry.setEnabled(BuiltinEntries.QS_TILE, false);
        EntryRegistry.setEnabled(BuiltinEntries.INTENT, true);
        on = EntryRegistry.apply();
        assertEquals(java.util.Arrays.asList("entry.qs_tile=false", "entry.intent=true"), switched);
        assertTrue(EntryRegistry.isRunning(BuiltinEntries.INTENT));
        assertFalse(EntryRegistry.isRunning(BuiltinEntries.QS_TILE));
    }

    @Test public void register_rejectsBadIds() {
        try {
            EntryRegistry.register(new BuiltinEntries.Simple("Bad Id", "x", "x", EntryTrigger.Kind.SYSTEM, false, 1, null, null));
            fail();
        } catch (IllegalArgumentException expected) { }
    }

    @Test public void parseMode() {
        assertEquals(EntryTrigger.Mode.TEXT, BuiltinEntries.parseMode("text"));
        assertEquals(EntryTrigger.Mode.VOICE, BuiltinEntries.parseMode(null));
        assertEquals(EntryTrigger.Mode.VOICE, BuiltinEntries.parseMode("whatever"));
    }

    private static String rd(String rel) throws Exception {
        return new String(Files.readAllBytes(Paths.get(System.getProperty("bixby.root", "."), rel)), "UTF-8");
    }

    @Test public void manifest_declaresEntriesSafely() throws Exception {
        String m = rd("app/AndroidManifest.xml");
        // 默认助理三件套
        assertTrue(m.contains("android.service.voice.VoiceInteractionService"));
        assertTrue(m.contains("com.onesay.assistant.v2.assist.OneSaySessionService"));
        assertTrue(m.contains("android.speech.RecognitionService"));
        String vi = rd("app/res/xml/onesay_voice_interaction.xml");
        assertTrue(vi.contains("sessionService") && vi.contains("recognitionService") && vi.contains("supportsAssist=\"true\""));
        // 公开 Intent：危险级权限 + 默认停用
        int i = m.indexOf("com.onesay.assistant.v2.entry.SummonActivity");
        String block = m.substring(i, m.indexOf("</activity>", i));
        assertTrue(block.contains("android:enabled=\"false\""));
        assertTrue(block.contains("android:permission=\"com.onesay.assistant.permission.SUMMON\""));
        int p = m.indexOf("android:name=\"com.onesay.assistant.permission.SUMMON\"");
        assertTrue(m.substring(p, m.indexOf("/>", p)).contains("protectionLevel=\"dangerous\""));
        // 跳板不导出
        int j = m.indexOf("com.onesay.assistant.v2.entry.EntryLaunchActivity");
        assertTrue(m.substring(j, m.indexOf("/>", j)).contains("android:exported=\"false\""));
        assertTrue(m.contains("android.permission.BIND_QUICK_SETTINGS_TILE"));
        assertTrue(m.contains("android.appwidget.provider"));
        assertTrue(m.contains("android.app.shortcuts"));
        // debug 变体把新权限 / action 也改名（否则与 release 同装时 DUPLICATE_PERMISSION）
        String b = rd("build_linux.sh");
        assertTrue(b.contains("com.onesay.assistant.debug.permission.SUMMON") && b.contains("com.onesay.assistant.debug.action.SUMMON"));
        assertTrue(rd("app/res-debug/xml/onesay_shortcuts.xml").contains("com.onesay.assistant.debug"));
    }
}
