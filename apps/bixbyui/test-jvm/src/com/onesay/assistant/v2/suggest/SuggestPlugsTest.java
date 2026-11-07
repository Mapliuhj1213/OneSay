package com.onesay.assistant.v2.suggest;

import com.onesay.assistant.plug.api.PlugRegistry;
import com.onesay.assistant.plug.api.PlugSpec;
import com.onesay.assistant.plug.api.PlugSwitches;
import com.onesay.assistant.plug.suggest.core.SuggestDefaults;
import com.onesay.assistant.plug.suggest.core.scenes.shot.PrivateApps;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

/** ★ feat/suggest：宿主侧开关 —— debug / release 出厂默认、--es plug 命令拆分给 mika / suggest 两族、解析。 */
public class SuggestPlugsTest {

    /** 只认出厂默认 + 显式覆盖的开关桩。 */
    static final class Sw implements PlugSwitches {
        final Map<String, Boolean> o = new HashMap<String, Boolean>();
        @Override public boolean on(String id, boolean def) {
            Boolean b = o.get(id);
            return b == null ? def : b.booleanValue();
        }
    }

    static final Set<String> LOCAL_REAL = new HashSet<String>(Arrays.asList(
            "screenshot", "link", "clip", "pickup", "headset", "torch", "newapp"));

    @Test public void masterDefaultOnInDebugAndRelease() {
        // debug / release 总开关都默认开
        PlugRegistry rel = SuggestPlugs.build(false, new Sw()), dbg = SuggestPlugs.build(true, new Sw());
        assertTrue("release 总开关默认开", rel.spec(SuggestPlugs.MASTER).defaultOn);
        assertTrue("debug 总开关默认开", dbg.spec(SuggestPlugs.MASTER).defaultOn);
        assertTrue(rel.on(SuggestPlugs.MASTER));
        assertTrue(dbg.on(SuggestPlugs.MASTER));
        assertTrue(SuggestPlugs.MASTER_DEFAULT);
        assertTrue(new com.onesay.assistant.v2.kernel.plugins.SuggestPlugin().defaultOn());
        assertTrue(rel.problems().isEmpty());
        assertTrue(dbg.problems().isEmpty());
    }

    @Test public void scenesDefaultOnlyLocalNonSkeletonAndDependOnMaster() {
        PlugRegistry rel = SuggestPlugs.build(false, new Sw()), dbg = SuggestPlugs.build(true, new Sw());
        int scenes = 0;
        for (PlugSpec s : dbg.specs()) {
            if (s.id.equals(SuggestPlugs.MASTER)) continue;
            scenes++;
            assertTrue(s.id.startsWith(SuggestPlugs.SCENE_PREFIX));
            String scene = s.id.substring(SuggestPlugs.SCENE_PREFIX.length());
            assertEquals("D5 仅本机场景（非骨架）默认开：" + scene, LOCAL_REAL.contains(scene), s.defaultOn);
            assertEquals("debug / release 场景默认一致：" + scene, s.defaultOn, rel.spec(s.id).defaultOn);
            assertEquals(Arrays.asList(SuggestPlugs.MASTER), s.deps);
            assertEquals("debug 下按默认：" + scene, s.defaultOn, dbg.on(s.id));
            assertEquals("release 同样按默认（总开关默认开）：" + scene, s.defaultOn, rel.on(s.id));
        }
        assertEquals("7 个真实场景 + 8 个骨架", 15, scenes);
    }

    @Test public void userSwitchWrittenToSuggestKeyAndOldUsersFollowNewDefault() {
        // 老用户：prefs 里没有 suggest 键 → 按新默认开；用户关过（写 false）→ 照旧关，场景跟着视同关；再开 → 场景按 D5 回来
        Sw sw = new Sw();
        PlugRegistry r = SuggestPlugs.build(false, sw);
        assertTrue("没这个键 = 新默认开", r.on(SuggestPlugs.MASTER));
        assertTrue(r.on(SuggestPlugs.SCENE_PREFIX + "screenshot"));
        assertFalse("骨架仍关", r.on(SuggestPlugs.SCENE_PREFIX + "low_battery"));
        sw.o.put("suggest", Boolean.FALSE);   // 控制台总开关写的就是 bixby_plug 的 suggest 键（与 --es plug suggest=off 同一个键）
        assertFalse(r.on(SuggestPlugs.MASTER));
        assertFalse("总开关关 → 场景视同关", r.on(SuggestPlugs.SCENE_PREFIX + "screenshot"));
        assertTrue("场景自身开关不被改写", r.selfOn(SuggestPlugs.SCENE_PREFIX + "screenshot"));
        sw.o.put("suggest", Boolean.TRUE);
        assertTrue(r.on(SuggestPlugs.SCENE_PREFIX + "screenshot"));
        assertEquals("suggest", SuggestPlugs.MASTER);
    }

    @Test public void defaultsDecisions() {
        assertEquals("D1 卡片 6 s", 6000L, SuggestDefaults.CARD_TTL_MS);
        assertFalse("D2 不再提示不确认", SuggestDefaults.MUTE_CONFIRM);
        assertEquals("D2 3 s 撤销", 3000L, SuggestDefaults.UNDO_TTL_MS);
        assertFalse("D3 微信不进私密名单", PrivateApps.defaults().contains(PrivateApps.WECHAT));
        assertTrue("D4 先结构化结果", SuggestDefaults.ANALYZE_AUTO_STRUCTURED);
        assertFalse("D4 不自动执行", SuggestDefaults.AUTO_EXECUTE);
        assertTrue(SuggestPlugs.privatePkgs(null).contains("com.eg.android.AlipayGphone"));
    }

    @Test public void splitRoutesBySuggestPrefix() {
        assertArrayEquals(new String[]{"mika.screen=on", "suggest=off"}, SuggestPlugs.splitCommand("mika.screen=on,suggest=off"));
        assertArrayEquals(new String[]{"", "suggest.screenshot=off,suggest.clip=on"},
                SuggestPlugs.splitCommand(" suggest.screenshot=off , suggest.clip=on "));
        assertArrayEquals("list / all=… 两族都给", new String[]{"list", "list"}, SuggestPlugs.splitCommand("list"));
        assertArrayEquals(new String[]{"all=default", "all=default"}, SuggestPlugs.splitCommand("all=default"));
        assertArrayEquals("不像 suggest 的都留给 mika（由它报看不懂）", new String[]{"suggestx=on,screenshot=off", ""},
                SuggestPlugs.splitCommand("suggestx=on,screenshot=off"));
        assertArrayEquals(new String[]{"", ""}, SuggestPlugs.splitCommand(null));
        assertArrayEquals(new String[]{"", ""}, SuggestPlugs.splitCommand(" , ,"));
    }

    @Test public void parseNormalizesValuesAndRejectsUnknown() {
        PlugRegistry r = SuggestPlugs.build(true, new Sw());
        List<String[]> c = SuggestPlugs.parseCommand(r, "suggest=OFF,suggest.torch=1,suggest.clip=reset,list");
        assertEquals(3, c.size());
        assertArrayEquals(new String[]{"suggest", "off"}, c.get(0));
        assertArrayEquals(new String[]{"suggest.torch", "on"}, c.get(1));
        assertArrayEquals(new String[]{"suggest.clip", "default"}, c.get(2));

        assertEquals("?", SuggestPlugs.parseCommand(r, "suggest.nope=on").get(0)[1]);
        assertEquals("?", SuggestPlugs.parseCommand(r, "suggest.torch=maybe").get(0)[1]);
        assertEquals("?", SuggestPlugs.parseCommand(r, "suggest.torch").get(0)[1]);
        assertEquals("all=on 被拒（与 mika 同口径）", "?", SuggestPlugs.parseCommand(r, "all=on").get(0)[1]);
        List<String[]> all = SuggestPlugs.parseCommand(r, "all=default");
        assertEquals(r.specs().size(), all.size());
        for (String[] a : all) assertEquals("default", a[1]);
        assertTrue(SuggestPlugs.parseCommand(r, null).isEmpty());
    }
}
