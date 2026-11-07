package com.onesay.assistant.v2.roles;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.List;

/** [roles] 候选推导：已安装种子 + 标准 intent 解析（含非种子）+ 挖掘接口（证据 / 关键词候选）；未安装种子排最后。 */
public class CandidateDeriverTest {

    final RoleCatalog cat = FakeSnapshot.assetCatalog();

    @Test public void navDerivesFromGeoIntent() {
        FakeSnapshot s = new FakeSnapshot().install("com.baidu.BaiduMap", "com.here.app.maps")
                .handles("geo:0,0?q=test", "com.baidu.BaiduMap", "百度地图")
                .handles("geo:0,0?q=test", "com.here.app.maps", "HERE WeGo");
        List<RoleCandidate> c = CandidateDeriver.derive(cat.role("nav"), s);
        assertEquals("baidumap", c.get(0).id);
        assertTrue(c.get(0).sources.contains(RoleCandidate.SRC_INTENT));
        RoleCandidate g = c.get(1);
        assertEquals("非种子：id = 包名，标签取系统标签（R5 起谷歌地图是种子，换 HERE 作例子）", "com.here.app.maps", g.id);
        assertEquals("HERE WeGo", g.label);
        assertFalse(g.isSeed());
        assertEquals("角色级 dest_sign 也给非种子", "s2.nav.app", g.destSign);
        assertFalse("未安装种子排最后", c.get(c.size() - 1).installed);
        assertEquals(2, CandidateDeriver.installed(c).size());
        assertSame(g, CandidateDeriver.named("用herewego导航", c));
    }

    @Test public void musicCategoryProbeAndMinedEvidence() {
        FakeSnapshot s = new FakeSnapshot().install("com.sec.android.app.music", "com.netease.cloudmusic")
                .handles("android.intent.category.APP_MUSIC", "com.sec.android.app.music", "三星音乐");
        s.mined.add(new DeviceSnapshot.MinedIface("com.netease.cloudmusic", "netease.player", "播放 歌曲"));
        List<RoleCandidate> c = CandidateDeriver.derive(cat.role("music"), s);
        assertEquals("netease", c.get(0).id);
        assertEquals(1, c.get(0).minedCount);
        assertEquals("com.sec.android.app.music", c.get(1).id);
        assertEquals(2, CandidateDeriver.installed(c).size());
    }

    @Test public void taxiHasNoProbeOnlyInstalledSeeds() {
        FakeSnapshot s = new FakeSnapshot().install("com.sankuai.meituan", "com.sankuai.meituan.takeoutnew");
        List<RoleCandidate> c = CandidateDeriver.derive(cat.role("taxi"), s);
        assertEquals(1, CandidateDeriver.installed(c).size());
        assertEquals("com.sankuai.meituan", c.get(0).pkg);
        assertEquals("「美团打车」点名到美团主 App，不是外卖", "meituan", CandidateDeriver.named("用美团打车", c).id);
    }

    @Test public void noSnapshotMeansSeedsOnly() {
        List<RoleCandidate> c = CandidateDeriver.derive(cat.role("taxi"), null);
        assertEquals(cat.role("taxi").seeds.size(), c.size());
        for (RoleCandidate x : c) assertTrue(x.installed);
    }
}
