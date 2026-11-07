package com.onesay.assistant.v2.roles;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** [roles] 裁决顺序：点名 > 显式默认 > 习惯推断 > 无快照=不接管 > 只装一个静默 > ≥2 选择芯片 > 都没装 = 安装选择。 */
public class RoleResolverTest {

    static final String DIDI = "com.sdu.didi.psnger", AMAP = "com.autonavi.minimap", MT = "com.sankuai.meituan";
    final RoleCatalog cat = FakeSnapshot.assetCatalog();
    final RoleCatalog.Role taxi = cat.role("taxi");

    List<RoleCandidate> cands(FakeSnapshot s) {
        return CandidateDeriver.derive(taxi, s);
    }

    static final List<String> NONE = Collections.emptyList();

    @Test public void order() {
        FakeSnapshot s = new FakeSnapshot().install(DIDI, AMAP);
        List<RoleCandidate> c = cands(s);
        RoleCandidate amap = CandidateDeriver.find(c, "amap");
        List<String> threeDidi = Arrays.asList("didi", "didi", "didi");
        // 1. 点名压过一切
        assertEquals(RoleResolver.Kind.OVERRIDE, RoleResolver.resolve(c, amap, "didi", threeDidi, true).kind);
        // 2. 显式压过推断
        RoleResolver.Resolution r = RoleResolver.resolve(c, null, "amap", threeDidi, true);
        assertEquals(RoleResolver.Kind.EXPLICIT, r.kind);
        assertEquals("amap", r.target.id);
        // 3. 推断（保留 S2 原规则 3/10）
        r = RoleResolver.resolve(c, null, null, threeDidi, true);
        assertEquals(RoleResolver.Kind.INFERRED, r.kind);
        assertEquals("didi", r.target.id);
        assertEquals(RoleResolver.Kind.CHOOSER, RoleResolver.resolve(c, null, null, Arrays.asList("didi", "didi"), true).kind);
        // 4. 无快照：原流程（不接管）
        assertEquals(RoleResolver.Kind.NONE, RoleResolver.resolve(CandidateDeriver.derive(taxi, null), null, null, NONE, false).kind);
    }

    @Test public void singleChooserNoneInstalled() {
        RoleResolver.Resolution one = RoleResolver.resolve(cands(new FakeSnapshot().install(DIDI)), null, null, NONE, true);
        assertEquals(RoleResolver.Kind.SINGLE, one.kind);
        assertEquals(DIDI, one.target.pkg);

        RoleResolver.Resolution ch = RoleResolver.resolve(cands(new FakeSnapshot().install(MT, AMAP)), null, null, NONE, true);
        assertEquals(RoleResolver.Kind.CHOOSER, ch.kind);
        assertEquals(RoleResolver.MAX_CHOICES, ch.choices.size());
        assertTrue("已安装排前面", ch.choices.get(0).installed && ch.choices.get(1).installed);
        assertFalse(ch.choices.get(2).installed);
        assertEquals("种子顺序：高德在美团前", "amap", ch.choices.get(0).id);

        RoleResolver.Resolution none = RoleResolver.resolve(cands(new FakeSnapshot()), null, null, NONE, true);
        assertEquals(RoleResolver.Kind.NONE_INSTALLED, none.kind);
        assertEquals("didi", none.choices.get(0).id);
    }

    @Test public void notInstalledTargets() {
        FakeSnapshot s = new FakeSnapshot().install(AMAP, MT);
        List<RoleCandidate> c = cands(s);
        RoleCandidate didi = CandidateDeriver.find(c, "didi");
        assertEquals("点名没装 → 安装提示", RoleResolver.Kind.INSTALL, RoleResolver.resolve(c, didi, null, NONE, true).kind);
        RoleResolver.Resolution ex = RoleResolver.resolve(c, null, "didi", NONE, true);
        assertEquals("显式默认没装 → 安装提示（默认保留）", RoleResolver.Kind.INSTALL, ex.kind);
        assertTrue(ex.def.explicit());
        // 装上之后同一份数据直接生效（每次重新推导）
        s.install(DIDI);
        assertEquals(RoleResolver.Kind.EXPLICIT, RoleResolver.resolve(cands(s), null, "didi", NONE, true).kind);
        // 推断出的 App 被卸载 → 不提示安装，退到下一步
        s.uninstall(DIDI);
        assertEquals(RoleResolver.Kind.CHOOSER, RoleResolver.resolve(cands(s), null, null, Arrays.asList("didi", "didi", "didi"), true).kind);
    }
}
