package com.onesay.assistant.v2.kernel;

import com.onesay.assistant.plug.api.PlugRegistry;
import com.onesay.assistant.plug.api.PlugSpec;
import com.onesay.assistant.plug.api.PlugSwitches;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/** ★ int/1102：--es plug 统一入口的分派（三族合一）+ 短名重名检查。 */
public class PlugCommandTest {

    private static PlugRegistry reg(String... ids) {
        PlugRegistry r = new PlugRegistry(PlugSwitches.DEFAULTS);
        for (String id : ids) r.register(new PlugSpec(id, 1, id, id, true, null, null));
        return r;
    }

    @Test public void realRegistriesHaveNoShortNameCollisions() {
        assertEquals("各插件族短名 / 完整 id 不得撞名", "[]", PlugCommand.collisions(PlugCommandHost.families()).toString());
    }

    @Test public void routesByPrefixAndShortName() {
        PlugCommand.Routed r = PlugCommand.route("guard=off,richout.diagram=off,suggest.clip=on,skillpack=on,diagram=default,list",
                PlugCommandHost.families());
        assertEquals("[]", r.errors.toString());
        assertEquals("mika.guard=off,skillpack=on", r.part("mika"));
        assertEquals("suggest.clip=on", r.part("suggest"));
        assertEquals("richout.diagram=off,richout.diagram=default", r.part("richout"));
    }

    @Test public void allOffGoesToEveryFamilyAndAllOnRejected() {
        PlugCommand.Routed r = PlugCommand.route("all=off", PlugCommandHost.families());
        assertEquals("all=off", r.part("mika"));
        assertEquals("all=off", r.part("suggest"));
        assertTrue(r.part("richout").contains("richout=off"));
        assertTrue(r.part("richout").contains("richout.reader=off"));
        assertEquals("all=off", PlugCommandHost.mikaSuggestPart(r));   // 去重：MikaPlugs 内部再分给 suggest
        PlugCommand.Routed on = PlugCommand.route("all=on", PlugCommandHost.families());
        assertEquals(1, on.errors.size());
        assertEquals("", on.part("mika") + on.part("richout"));
    }

    @Test public void shortNameCollisionIsRejectedNotGuessed() {
        List<PlugCommand.Family> f = new ArrayList<PlugCommand.Family>();
        f.add(new PlugCommand.Family("mika", reg("mika.reader", "mika.guard"), false));
        f.add(new PlugCommand.Family("richout", reg("richout", "richout.reader"), true));
        assertTrue(PlugCommand.collisions(f).toString().contains("reader"));
        PlugCommand.Routed r = PlugCommand.route("reader=off,guard=off", f);
        assertEquals(1, r.errors.size());
        assertTrue(r.errors.get(0).startsWith("重名：「reader」"));
        assertEquals("mika.guard=off", r.part("mika"));
        assertEquals("", r.part("richout"));
        // 写全名就没歧义
        PlugCommand.Routed full = PlugCommand.route("richout.reader=off", f);
        assertEquals("[]", full.errors.toString());
        assertEquals("richout.reader=off", full.part("richout"));
    }

    @Test public void unknownAndMalformedReported() {
        PlugCommand.Routed r = PlugCommand.route("nosuch=on,guard", PlugCommandHost.families());
        assertEquals(2, r.errors.size());
        // 带族前缀的未知 id 交给该族自己报「看不懂」，不在分派层吞掉
        PlugCommand.Routed m = PlugCommand.route("mika.nosuch=on", PlugCommandHost.families());
        assertEquals("[]", m.errors.toString());
        assertEquals("mika.nosuch=on", m.part("mika"));
    }
}
