package com.onesay.assistant.v2.roles;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.s2.InMemoryMemoryStore;
import com.onesay.assistant.v2.s2.S2Engine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/**
 * [roles] R5（用户决定 2026-10-08）：选择芯片固定补足 4 个——已安装在前，不足按 app_roles.json 种子顺序（热门度）用未安装的补位；
 * 点已安装 = 显式默认；点未安装 = 只走安装链、不设默认（没默认时下次照样问）；只装 1 个仍静默；综合 App（general）不从「打开 X」学习。
 */
public class RolesFillToFourTest {

    static final S2Engine.Flags F = new S2Engine.Flags(true, true, false);
    static final String DIDI = "com.sdu.didi.psnger", AMAP = "com.autonavi.minimap", BAIDU = "com.baidu.BaiduMap",
            MTWM = "com.sankuai.meituan.takeoutnew", ELEME = "me.ele", JD = "com.jingdong.app.mall",
            GMAP = "com.google.android.apps.maps";
    static final List<String> NONE = Collections.emptyList();

    final RoleCatalog cat = FakeSnapshot.assetCatalog();
    InMemoryMemoryStore st;
    S2Engine en;
    FakeSnapshot dev;
    long now = 1791400000000L;

    @Before public void setUp() {
        st = new InMemoryMemoryStore();
        en = new S2Engine(st, st, TimeZone.getTimeZone("Asia/Shanghai"));
        en.setRoles(cat);
        dev = new FakeSnapshot();
        en.setDevice(dev);
    }

    @Test public void everyShippedRoleHasAtLeastFourSeeds() {
        for (RoleCatalog.Role r : cat.roles()) {
            assertTrue(r.id + " 种子不足 4 个，补不满", r.seeds.size() >= RoleResolver.MAX_CHOICES);
        }
    }

    @Test public void chooserAlwaysFillsToFourInstalledFirstThenSeedOrder() throws Exception {
        dev.install(BAIDU, AMAP);
        S2Engine.Result r = en.handle("导航", now, F);
        assertEquals(S2Engine.Type.ROLE_CHOOSER, r.type);
        JSONArray o = r.ui.getJSONArray("options");
        assertEquals(4, o.length());
        assertEquals("amap", o.getJSONObject(0).getString("id"));
        assertEquals("baidumap", o.getJSONObject(1).getString("id"));
        assertTrue(o.getJSONObject(0).getBoolean("installed") && o.getJSONObject(1).getBoolean("installed"));
        assertEquals("qqmap", o.getJSONObject(2).getString("id"));
        assertEquals("googlemap", o.getJSONObject(3).getString("id"));
        assertFalse(o.getJSONObject(2).getBoolean("installed") || o.getJSONObject(3).getBoolean("installed"));
        assertTrue(r.reply, r.reply.contains("点未安装的带你去安装"));

        dev.install(MTWM, ELEME);
        JSONArray t = en.handle("点外卖", now, F).ui.getJSONArray("options");
        assertEquals(4, t.length());
        assertEquals("meituan", t.getJSONObject(2).getString("id"));
        assertEquals("jd", t.getJSONObject(3).getString("id"));
    }

    @Test public void derivedNonSeedCountsAsInstalledAndPrecedesFill() {
        RoleCatalog.Role nav = cat.role("nav");
        FakeSnapshot s = new FakeSnapshot().install(BAIDU, "com.sec.maps").handles("geo:0,0?q=test", "com.sec.maps", "三星地图");
        RoleResolver.Resolution res = RoleResolver.resolve(CandidateDeriver.derive(nav, s), null, null, NONE, true);
        assertEquals(RoleResolver.Kind.CHOOSER, res.kind);
        assertEquals(4, res.choices.size());
        assertEquals("baidumap", res.choices.get(0).id);
        assertEquals("com.sec.maps", res.choices.get(1).pkg);
        assertTrue(res.choices.get(1).installed);
        assertEquals("amap", res.choices.get(2).id);
        assertEquals("qqmap", res.choices.get(3).id);
    }

    @Test public void moreThanFourInstalledShowsFirstFourInstalled() {
        RoleCatalog.Role taxi = cat.role("taxi");
        FakeSnapshot s = new FakeSnapshot().install(DIDI, AMAP, "com.sankuai.meituan", "com.huaxiaozhu.rider", "cn.caocaokeji.user");
        RoleResolver.Resolution res = RoleResolver.resolve(CandidateDeriver.derive(taxi, s), null, null, NONE, true);
        assertEquals(4, res.choices.size());
        for (RoleCandidate c : res.choices) assertTrue(c.installed);
    }

    @Test public void onChoicePureRule() {
        RoleCatalog.Role taxi = cat.role("taxi");
        List<RoleCandidate> c = CandidateDeriver.derive(taxi, new FakeSnapshot().install(DIDI));
        assertEquals(RoleResolver.ChoiceAction.SET_DEFAULT, RoleResolver.onChoice(CandidateDeriver.find(c, "didi"), true));
        assertEquals(RoleResolver.ChoiceAction.INSTALL_ONLY, RoleResolver.onChoice(CandidateDeriver.find(c, "amap"), true));
        assertEquals(RoleResolver.ChoiceAction.IGNORE, RoleResolver.onChoice(null, true));
        // 无快照（设备现状未知）= 旧行为：视为已安装
        assertEquals(RoleResolver.ChoiceAction.SET_DEFAULT,
                RoleResolver.onChoice(CandidateDeriver.find(CandidateDeriver.derive(taxi, null), "amap"), false));
    }

    @Test public void tapUninstalledOpensInstallOnlyAndKeepsAsking() throws Exception {
        dev.install(DIDI, AMAP);
        JSONArray o = en.handle("打车", now, F).ui.getJSONArray("options");
        assertEquals(4, o.length());
        JSONObject missing = o.getJSONObject(2);
        assertFalse(missing.getBoolean("installed"));
        assertEquals(RoleResolver.ChoiceAction.INSTALL_ONLY, en.choiceAction("taxi", missing.getString("id")));
        S2Engine.Result c = en.applyChoice("taxi", missing.getString("id"), null);
        assertEquals(S2Engine.Type.ROLE_INSTALL, c.type);
        assertFalse("点未安装芯片不设默认，也不能说默认仍是它", c.reply.contains("默认仍是它"));
        assertEquals(missing.getString("pkg"), c.ui.getString("pkg"));
        assertNull(st.explicitDefault("taxi"));
        assertEquals("没默认 → 下次照样问", S2Engine.Type.ROLE_CHOOSER, en.handle("打车", now, F).type);
        // 期间装好了 → 点击时重新推导，按已安装处理
        dev.install(missing.getString("pkg"));
        assertEquals(RoleResolver.ChoiceAction.SET_DEFAULT, en.choiceAction("taxi", missing.getString("id")));
        assertEquals(RoleResolver.ChoiceAction.SET_DEFAULT, en.choiceAction("taxi", "didi"));
    }

    @Test public void noneInstalledShowsFourAllInstallOnly() throws Exception {
        S2Engine.Result r = en.handle("导航", now, F);
        assertEquals(S2Engine.Type.ROLE_CHOOSER, r.type);
        assertEquals("install", r.ui.getString("mode"));
        JSONArray o = r.ui.getJSONArray("options");
        assertEquals(4, o.length());
        for (int i = 0; i < o.length(); i++) {
            assertFalse(o.getJSONObject(i).getBoolean("installed"));
            assertEquals(RoleResolver.ChoiceAction.INSTALL_ONLY, en.choiceAction("nav", o.getJSONObject(i).getString("id")));
        }
        assertTrue(r.reply, r.reply.contains("不会设成默认"));
        assertNull(st.explicitDefault("nav"));
    }

    @Test public void singleInstalledStillSilent() throws Exception {
        dev.install(BAIDU);
        S2Engine.Result r = en.handle("导航", now, F);
        assertEquals("只装 1 个：保持静默直接用（R5 不改）", S2Engine.Type.HABIT_APPLY, r.type);
        assertEquals(BAIDU, r.args.getString("pkg"));
    }

    @Test public void generalSeedNotLearnedFromBareLaunch() throws Exception {
        dev.install(JD, MTWM);
        JSONObject a = new JSONObject().put("app", "京东").put("pkg", JD);
        assertNull("「打开京东」不算一次外卖", en.recordExecution("打开京东", "app.launch", a, true, now));
        assertEquals("说了角色才算", "takeout=jd", en.recordExecution("用京东点外卖", "app.launch", a, true, now));
        // 非综合 App 的唯一归属学习不变
        JSONObject g = new JSONObject().put("app", "谷歌地图").put("pkg", GMAP);
        dev.install(GMAP);
        assertEquals("nav=googlemap", en.recordExecution("打开谷歌地图", "app.launch", g, true, now));
    }
}
