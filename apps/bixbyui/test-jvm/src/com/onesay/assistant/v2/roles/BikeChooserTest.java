package com.onesay.assistant.v2.roles;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.s2.InMemoryMemoryStore;
import com.onesay.assistant.v2.s2.S2Capabilities;
import com.onesay.assistant.v2.s2.S2Engine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.TimeZone;

/**
 * [r2/bike-chooser] 「我想扫共享单车」等说法 → 本地角色 bike → 应用选择网格（已装→打开，未装→下载），不经大模型。
 * 也覆盖：只装一个也弹（always_choose）、2×2 上限与已安装优先、商店搜索词、支付宝扫码深链、其它角色不受影响。
 */
public class BikeChooserTest {

    static final S2Engine.Flags F = new S2Engine.Flags(true, true, false);
    static final String HELLO = "com.jingyao.easybike", MT = "com.sankuai.meituan", ALIPAY = "com.eg.android.AlipayGphone",
            WX = "com.tencent.mm", DIDI = "com.sdu.didi.psnger";

    InMemoryMemoryStore st;
    S2Engine en;
    FakeSnapshot dev;
    long now = 1791400000000L;

    @Before public void setUp() {
        st = new InMemoryMemoryStore();
        en = new S2Engine(st, st, TimeZone.getTimeZone("Asia/Shanghai"));
        en.setRoles(FakeSnapshot.assetCatalog());
        dev = new FakeSnapshot();
        en.setDevice(dev);
    }

    private S2Engine.Result ask(String q) {
        return en.handle(q, now, F);
    }

    @Test public void catalogHasBikeRole() {
        RoleCatalog.Role g = FakeSnapshot.assetCatalog().role("bike");
        assertNotNull(g);
        assertEquals("共享单车", g.storeQuery);
        assertTrue(g.alwaysChoose);
        assertEquals("alipays://platformapi/startapp?saId=10000007", g.seed("alipay").openUri);
        assertNull(g.seed("hellobike").openUri);
    }

    @Test public void userSentenceNothingInstalledShowsDownloadGrid() throws Exception {
        S2Engine.Result r = ask("我想扫共享单车");
        assertNotNull("不能落到大模型文字回答", r);
        assertEquals(S2Engine.Type.ROLE_CHOOSER, r.type);
        assertTrue(r.isLocalReply());
        assertEquals("chooser", r.ui.getString("kind"));
        assertEquals("bike", r.ui.getString("role"));
        assertEquals("install", r.ui.getString("mode"));
        assertEquals("共享单车", r.ui.getString("store_query"));
        assertTrue(r.reply, r.reply.contains("共享单车类"));
        JSONArray opts = r.ui.getJSONArray("options");
        assertEquals(4, opts.length());
        assertEquals(HELLO, opts.getJSONObject(0).getString("pkg"));
        assertEquals(MT, opts.getJSONObject(1).getString("pkg"));
        AppGridModel m = AppGridModel.from(r.ui);
        assertEquals(4, m.cells.size());
        assertEquals(2, m.rows());
        for (AppGridModel.Cell c : m.cells) assertEquals(AppGridModel.Action.DOWNLOAD, c.action);
        assertEquals("共享单车", m.storeQuery);
        assertEquals(RoleResolver.ChoiceAction.INSTALL_ONLY, en.choiceAction("bike", "hellobike"));
    }

    @Test public void manyPhrasingsHitBike() {
        String[] qs = {"扫共享单车", "我想扫共享单车", "我要扫共享单车", "帮我扫个单车", "扫一辆共享单车", "骑共享单车", "我想骑单车",
                "共享单车", "扫码骑车", "找个共享单车", "扫个共享电单车", "我想扫共享单车。"};
        for (String q : qs) {
            S2Engine.Result r = ask(q);
            assertNotNull(q, r);
            assertEquals(q, S2Engine.Type.ROLE_CHOOSER, r.type);
            assertEquals(q, "bike", r.ui.optString("role"));
        }
    }

    @Test public void questionsAboutBikesAreNotIntercepted() {
        String[] qs = {"共享单车多少钱", "共享单车怎么还车", "共享单车为什么这么贵", "附近有共享单车吗", "共享单车花了3块记一下"};
        for (String q : qs) {
            S2Engine.Result r = ask(q);
            assertTrue(q + " → " + r, r == null || r.type != S2Engine.Type.ROLE_CHOOSER);
        }
    }

    @Test public void singleInstalledStillChoosesAndOpensViaDeepLink() throws Exception {
        dev.install(ALIPAY);
        S2Engine.Result r = ask("我想扫共享单车");
        assertEquals(S2Engine.Type.ROLE_CHOOSER, r.type);
        assertEquals("apply", r.ui.getString("mode"));
        JSONArray opts = r.ui.getJSONArray("options");
        assertEquals(4, opts.length());
        JSONObject first = opts.getJSONObject(0);
        assertEquals(ALIPAY, first.getString("pkg"));
        assertTrue(first.getBoolean("installed"));
        assertEquals("alipays://platformapi/startapp?saId=10000007", first.getString("open_uri"));
        AppGridModel m = AppGridModel.from(r.ui);
        assertEquals(AppGridModel.Action.OPEN, m.cells.get(0).action);
        assertEquals("alipays://platformapi/startapp?saId=10000007", m.cells.get(0).openUri);
        assertEquals(AppGridModel.Action.DOWNLOAD, m.cells.get(1).action);
        // 已安装 → 打开：深链在前，主界面兜底
        assertEquals(2, StoreLinks.openApp(ALIPAY, m.cells.get(0).openUri).size());
        // 选它 = 显式默认，交接 app.launch（RoleHooks 会先试 open_uri）
        assertEquals(RoleResolver.ChoiceAction.SET_DEFAULT, en.choiceAction("bike", "alipay"));
        assertTrue(en.execute(S2Capabilities.SIGN_HABIT_SET, new JSONObject().put("group", "bike").put("app_id", "alipay"), now).ok);
        S2Engine.Result run = en.applyChoice("bike", "alipay", null);
        assertEquals("app.launch", run.sign);
        assertEquals(ALIPAY, run.args.getString("pkg"));
        // 之后直接打开（与其它角色同一语义）
        assertEquals(S2Engine.Type.HABIT_APPLY, ask("扫共享单车").type);
    }

    @Test public void installedFirstThenSeedOrderCappedAtFour() throws Exception {
        dev.install(MT, WX);
        S2Engine.Result r = ask("扫共享单车");
        JSONArray opts = r.ui.getJSONArray("options");
        assertEquals(4, opts.length());
        assertTrue(opts.getJSONObject(0).getBoolean("installed"));
        assertTrue(opts.getJSONObject(1).getBoolean("installed"));
        assertFalse(opts.getJSONObject(2).getBoolean("installed"));
        assertEquals(HELLO, opts.getJSONObject(2).getString("pkg"));
        assertEquals(ALIPAY, opts.getJSONObject(3).getString("pkg"));
        AppGridModel m = AppGridModel.from(r.ui);
        assertEquals(AppGridModel.Action.OPEN, m.at(0, 0).action);
        assertEquals(AppGridModel.Action.OPEN, m.at(0, 1).action);
        assertEquals(AppGridModel.Action.DOWNLOAD, m.at(1, 0).action);
        assertEquals(AppGridModel.Action.DOWNLOAD, m.at(1, 1).action);
    }

    @Test public void namedAppOpensDirectly() throws Exception {
        dev.install(HELLO);
        S2Engine.Result r = ask("用哈啰扫共享单车");
        assertEquals(S2Engine.Type.HABIT_APPLY, r.type);
        assertEquals(HELLO, r.args.getString("pkg"));
    }

    @Test public void otherRolesUnchanged() throws Exception {
        S2Engine.Result taxi = ask("打车");
        assertEquals(S2Engine.Type.ROLE_CHOOSER, taxi.type);
        assertEquals("打车", taxi.ui.getString("store_query"));
        assertTrue(taxi.reply, taxi.reply.startsWith("手机上还没有能打车的 App"));
        dev.install("com.sdu.didi.psnger");
        S2Engine.Result one = ask("打车");
        assertEquals("单装静默只对 always_choose 角色关闭", S2Engine.Type.HABIT_APPLY, one.type);
    }
}
