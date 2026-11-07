package com.onesay.assistant.v2.roles;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.s2.HabitPolicy;
import com.onesay.assistant.v2.s2.InMemoryMemoryStore;
import com.onesay.assistant.v2.s2.S2Capabilities;
import com.onesay.assistant.v2.s2.S2Engine;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.TimeZone;

/**
 * [roles] S2 引擎接上角色层（有设备快照）之后的整条判定：单装静默 / ≥2 选择芯片 → 选一次即显式默认 / 未安装 → 安装提示 /
 * 语音管理句 / 剪贴板芯片跟随角色默认 / 旧习惯数据零迁移 / 只加数据即可新增角色。
 */
public class RolesEngineTest {

    static final S2Engine.Flags F = new S2Engine.Flags(true, true, false);
    static final String DIDI = "com.sdu.didi.psnger", AMAP = "com.autonavi.minimap", MT = "com.sankuai.meituan",
            MTWM = "com.sankuai.meituan.takeoutnew", BAIDU = "com.baidu.BaiduMap";

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

    @Test public void singleInstalledIsSilent() throws Exception {
        dev.install(DIDI);
        S2Engine.Result r = en.handle("打车", now, F);
        assertEquals(S2Engine.Type.HABIT_APPLY, r.type);
        assertEquals("app.launch", r.sign);
        assertEquals(DIDI, r.args.getString("pkg"));
        assertEquals("taxi", r.args.getString("role"));
    }

    @Test public void chooserThenExplicitDefault() throws Exception {
        dev.install(DIDI, AMAP);
        S2Engine.Result r = en.handle("打车", now, F);
        assertEquals(S2Engine.Type.ROLE_CHOOSER, r.type);
        assertTrue(r.isLocalReply());
        JSONObject ui = r.ui;
        assertEquals("chooser", ui.getString("kind"));
        assertEquals("apply", ui.getString("mode"));
        JSONArray opts = ui.getJSONArray("options");
        assertTrue(opts.length() >= 2 && opts.length() <= RoleResolver.MAX_CHOICES);
        assertTrue(opts.getJSONObject(0).getBoolean("installed"));
        assertTrue(opts.getJSONObject(1).getBoolean("installed"));
        // 点「滴滴」芯片 = 写显式默认（「总是用这个」），再执行
        String pick = opts.getJSONObject(0).getString("id");
        assertTrue(en.execute(S2Capabilities.SIGN_HABIT_SET, new JSONObject().put("group", "taxi").put("app_id", pick), now).ok);
        S2Engine.Result run = en.applyChoice("taxi", pick, null);
        assertEquals("app.launch", run.sign);
        // 之后不再弹选择
        S2Engine.Result again = en.handle("打车", now, F);
        assertEquals(S2Engine.Type.HABIT_APPLY, again.type);
        assertEquals(run.args.getString("pkg"), again.args.getString("pkg"));
        assertTrue(HabitPolicy.resolve(st, "taxi").explicit());
        // 带目的地的选择：默认高德 → S1 打车深链；导航 → geo 指定包名
        S2Engine.Result dest = en.applyChoice("taxi", "amap", "机场");
        assertEquals("s1.taxi.to", dest.sign);
    }

    @Test public void noneInstalledAndUninstalledDefault() throws Exception {
        S2Engine.Result r = en.handle("打车", now, F);
        assertEquals(S2Engine.Type.ROLE_CHOOSER, r.type);
        assertEquals("install", r.ui.getString("mode"));
        assertFalse(r.ui.getJSONArray("options").getJSONObject(0).getBoolean("installed"));
        // 选滴滴（没装）→ 默认保留 + 安装提示
        assertTrue(en.execute(S2Capabilities.SIGN_HABIT_SET, new JSONObject().put("group", "taxi").put("app_id", "didi"), now).ok);
        S2Engine.Result c = en.applyChoice("taxi", "didi", null);
        assertEquals(S2Engine.Type.ROLE_INSTALL, c.type);
        assertEquals(DIDI, c.ui.getString("pkg"));
        S2Engine.Result again = en.handle("打车", now, F);
        assertEquals(S2Engine.Type.ROLE_INSTALL, again.type);
        assertTrue(again.reply.contains("默认仍是它"));
        // 装好之后直接生效（每次重新查安装状态）
        dev.install(DIDI);
        assertEquals("app.launch", en.handle("打车", now, F).sign);
        // 点名没装的 App → 安装提示，不改默认
        S2Engine.Result o = en.handle("用花小猪打车", now, F);
        assertEquals(S2Engine.Type.ROLE_INSTALL, o.type);
        assertEquals("com.huaxiaozhu.rider", o.ui.getString("pkg"));
        assertEquals("didi", st.explicitDefault("taxi"));
    }

    @Test public void meituanLaunchesByPackageNotLabel() throws Exception {
        dev.install(MT, MTWM, AMAP);
        S2Engine.Result r = en.handle("用美团打车", now, F);
        assertEquals("app.launch", r.sign);
        assertEquals("美团主 App，不是美团外卖", MT, r.args.getString("pkg"));
        S2Engine.Result t = en.handle("用美团外卖点外卖", now, F);
        assertEquals(MTWM, t.args.getString("pkg"));
        // 执行回来按包名学习
        assertEquals("taxi=meituan", en.recordExecution("用美团打车", "app.launch", r.args, true, now));
    }

    @Test public void voiceManagement() throws Exception {
        dev.install(DIDI, AMAP, BAIDU);
        S2Engine.Result q = en.handle("打车用哪个", now, F);
        assertEquals(S2Engine.Type.HABIT_QUERY, q.type);
        assertTrue(q.reply, q.reply.contains("滴滴出行") && q.reply.contains("高德地图"));
        assertEquals("set", q.ui.getString("mode"));

        S2Engine.Result set = en.handle("以后导航用百度", now, F);
        assertEquals(S2Engine.Type.HABIT_SET, set.type);
        assertEquals("nav", set.args.getString("group"));
        assertEquals("baidumap", set.args.getString("app_id"));
        assertTrue(en.execute(set.sign, set.args, now).ok);
        S2Engine.Result nav = en.handle("导航去公司", now, F);
        assertEquals(S2Capabilities.SIGN_NAV_APP, nav.sign);
        assertEquals(BAIDU, nav.args.getString("pkg"));

        S2Engine.Result list = en.handle("默认应用有哪些", now, F);
        assertEquals(S2Engine.Type.ROLE_LIST, list.type);
        assertTrue(list.reply, list.reply.contains("导航：百度地图（你设置的）"));
        assertTrue(list.reply, list.reply.contains("打车：未设置（本机 2 个可选"));
        assertEquals(S2Engine.Type.ROLE_LIST, en.handle("看看我的默认应用", now, F).type);

        S2Engine.Result reset = en.handle("重置导航默认", now, F);
        assertEquals(S2Engine.Type.HABIT_RESET, reset.type);
        assertEquals("nav", reset.args.getString("groups"));
        assertTrue(en.execute(reset.sign, reset.args, now + 1).ok);
        assertNull(st.explicitDefault("nav"));
        assertEquals(S2Engine.Type.HABIT_RESET_NOOP, en.handle("重置所有默认应用", now, F).type);
        en.execute(S2Capabilities.SIGN_HABIT_SET, new JSONObject().put("group", "music").put("app_id", "netease"), now);
        S2Engine.Result all = en.handle("重置所有默认应用", now, F);
        assertEquals(S2Engine.Type.HABIT_RESET, all.type);
        assertEquals("music", all.args.getString("groups"));
    }

    @Test public void clipboardChipFollowsRoleDefault() throws Exception {
        assertNull("无默认、什么都没装：芯片保持原动作", en.chipOverride("TAXI", "广州塔", "s1.taxi.to"));
        dev.install(DIDI);
        S2Engine.Result c = en.chipOverride("TAXI", "广州塔", "s1.taxi.to");
        assertEquals("只装了滴滴 → 打开滴滴", DIDI, c.args.getString("pkg"));
        dev.install(AMAP);
        assertNull("装了两个且没默认：芯片不弹选择，保持原动作", en.chipOverride("TAXI", "广州塔", "s1.taxi.to"));
        en.execute(S2Capabilities.SIGN_HABIT_SET, new JSONObject().put("group", "taxi").put("app_id", "amap"), now);
        assertNull("默认高德 = 原深链", en.chipOverride("TAXI", "广州塔", "s1.taxi.to"));
        en.execute(S2Capabilities.SIGN_HABIT_SET, new JSONObject().put("group", "nav").put("app_id", "amap"), now);
        S2Engine.Result n = en.chipOverride("NAV", "广州塔", "nav.to");
        assertEquals(S2Capabilities.SIGN_NAV_APP, n.sign);
        assertEquals(AMAP, n.args.getString("pkg"));
    }

    /** 旧 S2 习惯数据（种子 id 与旧 HabitCatalog 同值）不用迁移即可继续生效；旧 11 个 App 全部仍在目录里。 */
    @Test public void legacyHabitDataStillWorks() throws Exception {
        String[][] legacy = {
                {"taxi", "didi", "com.sdu.didi.psnger"}, {"taxi", "amap", "com.autonavi.minimap"}, {"taxi", "meituan", "com.sankuai.meituan"},
                {"nav", "amap", "com.autonavi.minimap"}, {"nav", "baidumap", "com.baidu.BaiduMap"}, {"nav", "qqmap", "com.tencent.map"},
                {"takeout", "meituanwm", "com.sankuai.meituan.takeoutnew"}, {"takeout", "eleme", "me.ele"}, {"takeout", "meituan", "com.sankuai.meituan"},
                {"music", "netease", "com.netease.cloudmusic"}, {"music", "qqmusic", "com.tencent.qqmusic"}, {"music", "kugou", "com.kugou.android"},
                {"music", "kuwo", "cn.kuwo.player"}};
        RoleCatalog cat = FakeSnapshot.assetCatalog();
        for (String[] l : legacy) {
            RoleCatalog.Seed s = cat.role(l[0]).seed(l[1]);
            assertNotNull(l[0] + "/" + l[1], s);
            assertEquals(l[2], s.pkg);
        }
        // 旧库里的数据：3 次滴滴（推断）+ 显式「导航用百度地图」+ 外卖重置时刻
        for (int i = 0; i < 3; i++) st.recordUse("taxi", "didi", now - 1000 + i);
        st.setExplicitDefault("nav", "baidumap");
        dev.install(DIDI, AMAP, BAIDU);
        assertEquals(DIDI, en.handle("打车", now, F).args.getString("pkg"));
        assertEquals(BAIDU, en.handle("导航去公司", now, F).args.getString("pkg"));
        // 新写法：推导出的非种子 App 以包名存
        dev.handles("geo:0,0?q=test", "com.here.app.maps", "HERE WeGo").install("com.here.app.maps");
        en.execute(S2Capabilities.SIGN_HABIT_SET, new JSONObject().put("group", "nav").put("app_id", "com.here.app.maps"), now);
        assertEquals("com.here.app.maps", st.explicitDefault("nav"));
        assertEquals("com.here.app.maps", en.handle("导航去公司", now, F).args.getString("pkg"));
        // R5：谷歌地图从「推导出的非种子」变成种子；旧库里以包名存的显式默认照样生效（find 按 id 或包名）
        dev.handles("geo:0,0?q=test", "com.google.android.apps.maps", "Google 地图").install("com.google.android.apps.maps");
        st.setExplicitDefault("nav", "com.google.android.apps.maps");
        assertEquals("com.google.android.apps.maps", en.handle("导航去公司", now, F).args.getString("pkg"));
    }

    /** 新增角色只改数据：测试往 JSON 里加一个「看视频」角色（带标准 intent 探针），不动任何代码。 */
    @Test public void addRoleByDataOnly() throws Exception {
        String json = FakeSnapshot.assetJson();
        JSONObject root = new JSONObject(json);
        root.getJSONArray("roles").put(new JSONObject("{\"id\":\"video\",\"label\":\"看视频\",\"triggers\":[\"看视频\",\"刷视频\"],"
                + "\"probe\":{\"action\":\"android.intent.action.VIEW\",\"data\":\"https://v.example/x.mp4\"},"
                + "\"mined_keywords\":[\"短视频\"],"
                + "\"seeds\":[{\"id\":\"bili\",\"pkg\":\"tv.danmaku.bili\",\"label\":\"哔哩哔哩\",\"aliases\":[\"b站\",\"哔哩哔哩\"]},"
                + "{\"id\":\"douyin\",\"pkg\":\"com.ss.android.ugc.aweme\",\"label\":\"抖音\",\"aliases\":[\"抖音\"]}]}"));
        RoleCatalog cat = RoleCatalog.parse(root.toString(), "test");
        assertNotNull(cat.role("video"));
        en.setRoles(cat);
        dev.install("tv.danmaku.bili", "com.kuaishou.nebula");
        dev.mined.add(new DeviceSnapshot.MinedIface("com.kuaishou.nebula", "kuaishou.feed", "快手 短视频 首页"));
        dev.labels.put("com.kuaishou.nebula", "快手极速版");
        S2Engine.Result r = en.handle("刷视频", now, F);
        assertEquals("哔哩哔哩 + 挖掘接口推导出的快手 → 选择", S2Engine.Type.ROLE_CHOOSER, r.type);
        assertEquals("com.kuaishou.nebula", r.ui.getJSONArray("options").getJSONObject(1).getString("pkg"));
        S2Engine.Result s = en.handle("以后看视频都用b站", now, F);
        assertEquals(S2Engine.Type.HABIT_SET, s.type);
        assertTrue(en.execute(s.sign, s.args, now).ok);
        assertEquals("tv.danmaku.bili", en.handle("看视频", now, F).args.getString("pkg"));
        assertEquals("用抖音（没装）→ 安装提示", S2Engine.Type.ROLE_INSTALL, en.handle("用抖音刷视频", now, F).type);
        assertTrue(en.handle("默认应用有哪些", now, F).reply.contains("看视频：哔哩哔哩（你设置的）"));
        // 坏数据不拖垮：解析失败 = 空目录
        assertTrue(RoleCatalog.parseOrEmpty("{oops", "bad").roles().isEmpty());
    }

    @Test public void minedHandoffSignOnlyWhenPresent() throws Exception {
        RoleCatalog cat = RoleCatalog.parse("{\"roles\":[{\"id\":\"mail\",\"label\":\"写邮件\",\"triggers\":[\"写邮件\"],"
                + "\"probe\":{\"action\":\"android.intent.action.SENDTO\",\"data\":\"mailto:\"},"
                + "\"seeds\":[{\"id\":\"qqmail\",\"pkg\":\"com.tencent.androidqqmail\",\"label\":\"QQ邮箱\",\"handoff_sign\":\"qqmail.compose\"}]}]}", "t");
        en.setRoles(cat);
        dev.install("com.tencent.androidqqmail");
        assertEquals("注册表里没有挖掘能力 → app.launch", "app.launch", en.handle("写邮件", now, F).sign);
        dev.caps.add("qqmail.compose");
        assertEquals("有 → 用挖掘深链", "qqmail.compose", en.handle("写邮件", now, F).sign);
    }
}
