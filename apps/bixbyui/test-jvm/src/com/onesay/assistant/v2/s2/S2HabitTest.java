package com.onesay.assistant.v2.s2;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.TimeZone;

/** [s2] #12 习惯默认值（2026-10-08 起候选与目录来自角色层 assets/app_roles.json；本类不接设备快照 = 旧行为回归）：阈值 / 打平 / 显式优先 / 单句覆盖 / 重置 / 从执行学习 / S1 剪贴板芯片改道。 */
public class S2HabitTest {

    static final S2Engine.Flags F = new S2Engine.Flags(true, true, false);
    InMemoryMemoryStore st;
    S2Engine en;
    long now = 1791400000000L;

    @Before public void setUp() {
        st = new InMemoryMemoryStore();
        en = new S2Engine(st, st, TimeZone.getTimeZone("Asia/Shanghai"));
    }

    void use(String q, String sign, String key, String val) throws Exception {
        assertNotNull(q, en.recordExecution(q, sign, new JSONObject().put(key, val), true, now++));
    }

    @Test public void policyThresholdTieExplicit() {
        assertNull(HabitPolicy.decide(null, Collections.<String>emptyList()));
        assertNull("2 次不够", HabitPolicy.decide(null, Arrays.asList("didi", "didi")));
        assertEquals("didi", HabitPolicy.decide(null, Arrays.asList("didi", "didi", "didi")).appId);
        assertNull("打平不默认", HabitPolicy.decide(null, Arrays.asList("didi", "amap", "didi", "amap", "didi", "amap")));
        assertEquals("didi", HabitPolicy.decide(null, Arrays.asList("didi", "amap", "didi", "didi")).appId);
        // 窗口只看最近 10 次
        assertNull(HabitPolicy.decide(null, Arrays.asList("amap", "amap", "x", "y", "z", "w", "v", "u", "t", "s", "didi", "didi", "didi")));
        HabitPolicy.Default d = HabitPolicy.decide("amap", Arrays.asList("didi", "didi", "didi"));
        assertEquals("显式偏好压过推断", "amap", d.appId);
        assertTrue(d.explicit());
    }

    @Test public void inferredAfterThreeUsesThenDefaultWithoutAsking() throws Exception {
        assertNull("无默认时不接管（原流程照旧）", en.handle("打车", now, F));
        use("打车", "app.launch", "app", "滴滴出行");
        use("打个车", "app.launch", "app", "滴滴");
        assertNull(en.handle("打车", now, F));
        use("打开滴滴", "app.launch", "app", "滴滴");   // 滴滴只属于打车 → 计入
        S2Engine.Result r = en.handle("打车", now, F);
        assertEquals(S2Engine.Type.HABIT_APPLY, r.type);
        assertEquals("app.launch", r.sign);
        assertEquals("滴滴", r.args.getString("app"));
        assertEquals("app.launch", en.handle("帮我叫辆车", now, F).sign);
        // 高德没说意图：既能打车也能导航 → 不计入
        assertNull(en.recordExecution("打开高德", "app.launch", new JSONObject().put("app", "高德地图"), true, now));
        // 失败的执行不计
        assertNull(en.recordExecution("打车", "app.launch", new JSONObject().put("app", "滴滴"), false, now));
        // 不在目录里的 App 不计
        assertNull(en.recordExecution("打车", "app.launch", new JSONObject().put("app", "微信"), true, now));
    }

    @Test public void perUtteranceOverrideAndDestination() throws Exception {
        for (int i = 0; i < 3; i++) use("打车", "app.launch", "app", "滴滴");
        S2Engine.Result r = en.handle("用高德打车", now, F);
        assertEquals("app.launch", r.sign);
        assertEquals("高德地图", r.args.getString("app"));
        assertEquals("美团", en.handle("打车用美团", now, F).args.getString("app"));
        // 高德 + 目的地 → S1 的打车深链
        S2Engine.Result d = en.handle("用高德打车去机场", now, F);
        assertEquals("s1.taxi.to", d.sign);
        assertEquals("机场", d.args.getString("address"));
        // 默认滴滴 + 目的地 → 打开滴滴（深链未核实，目的地需在 App 里确认）
        assertEquals("app.launch", en.handle("打车去机场", now, F).sign);
        // 覆盖不改默认
        assertEquals("didi", HabitPolicy.resolve(st, "taxi").appId);
        // 带别的内容 / 问句 / 指代：不接管
        assertNull(en.handle("打车要多少钱", now, F));
        assertNull(en.handle("怎么打车", now, F));
        assertNull(en.handle("打车去这里", now, F));
    }

    @Test public void explicitSetQueryReset() throws Exception {
        S2Engine.Result s = en.handle("以后打车都用滴滴", now, F);
        assertEquals(S2Engine.Type.HABIT_SET, s.type);
        assertTrue(en.execute(s.sign, s.args, now).ok);
        assertEquals("app.launch", en.handle("打车", now, F).sign);
        // 显式压过推断
        for (int i = 0; i < 5; i++) use("打车", "app.launch", "app", "高德地图");
        assertEquals("滴滴", en.handle("打车", now, F).args.getString("app"));
        S2Engine.Result q = en.handle("打车用哪个", now, F);
        assertEquals(S2Engine.Type.HABIT_QUERY, q.type);
        assertTrue(q.reply.contains("滴滴出行（你设置的）"));
        // 重置
        S2Engine.Result rs = en.handle("别默认用滴滴了", now, F);
        assertEquals(S2Engine.Type.HABIT_RESET, rs.type);
        assertEquals("taxi", rs.args.getString("groups"));
        assertTrue(en.execute(rs.sign, rs.args, now++).ok);
        assertNull("重置后旧记录不再计入", en.handle("打车", now, F));
        assertEquals(S2Engine.Type.HABIT_RESET_NOOP, en.handle("别默认用百度地图了", now, F).type);
        // 其它说法
        assertEquals(S2Engine.Type.HABIT_SET, en.handle("导航默认用百度地图", now, F).type);
        assertEquals(S2Engine.Type.HABIT_RESET, en.handle("打车不要默认了", now, F).type);
        assertEquals(S2Engine.Type.HABIT_SET, en.handle("记住以后点外卖都用饿了么", now, F).type);
    }

    @Test public void navDefaultUsesGeoWithPackage() throws Exception {
        S2Engine.Result s = en.handle("以后导航都用百度地图", now, F);
        en.execute(s.sign, s.args, now);
        S2Engine.Result r = en.handle("导航去公司", now, F);
        assertEquals(S2Capabilities.SIGN_NAV_APP, r.sign);
        assertEquals("公司", r.args.getString("address"));
        assertEquals("com.baidu.BaiduMap", r.args.getString("pkg"));
        assertEquals(S2Capabilities.SIGN_NAV_APP, en.handle("打开导航去公司", now, F).sign);
        assertEquals("app.launch", en.handle("导航", now, F).sign);
        assertNull("识屏指代留给识屏", en.handle("导航去这里", now, F));
        // 执行回来也算一次导航使用
        assertEquals("nav=baidumap", en.recordExecution("导航去公司", S2Capabilities.SIGN_NAV_APP, r.args, true, now));
    }

    @Test public void musicAndTakeoutBareOnly() throws Exception {
        for (int i = 0; i < 3; i++) use("我想听歌", "app.launch", "app", "网易云音乐");
        assertEquals("网易云音乐", en.handle("听歌", now, F).args.getString("app"));
        assertEquals("app.launch", en.handle("我想听歌", now, F).sign);
        assertNull("有具体歌名不接管", en.handle("放首周杰伦的歌", now, F));
        assertNull("媒体键类不接管", en.handle("播放音乐", now, F));
        for (int i = 0; i < 3; i++) use("点外卖", "app.launch", "app", "饿了么");
        assertEquals("饿了么", en.handle("我要点外卖", now, F).args.getString("app"));
    }

    @Test public void s1ChipOverride() throws Exception {
        assertNull("无默认：芯片保持原动作", en.chipOverride("TAXI", "广州塔"));
        for (int i = 0; i < 3; i++) use("打车", "s1.taxi.to", "address", "x");   // 芯片打车 = 用了高德
        assertEquals("amap", HabitPolicy.resolve(st, "taxi").appId);
        assertNull("默认高德 = 原深链不变", en.chipOverride("TAXI", "广州塔"));
        S2Engine.Result s = en.handle("以后打车都用滴滴", now, F);
        en.execute(s.sign, s.args, now);
        S2Engine.Result c = en.chipOverride("TAXI", "广州塔");
        assertEquals("app.launch", c.sign);
        assertEquals("滴滴", c.args.getString("app"));
        S2Engine.Result n0 = en.chipOverride("NAV", "广州塔");
        assertNull(n0);
        S2Engine.Result s2 = en.handle("以后导航都用高德", now, F);
        en.execute(s2.sign, s2.args, now);
        S2Engine.Result n = en.chipOverride("NAV", "广州塔");
        assertEquals(S2Capabilities.SIGN_NAV_APP, n.sign);
        assertEquals("com.autonavi.minimap", n.args.getString("pkg"));
    }

    @Test public void habitFlagOffNoTakeover() {
        S2Engine.Flags off = new S2Engine.Flags(true, false, false);
        assertNull(en.handle("以后打车都用滴滴", now, off));
        assertNull(en.handle("用高德打车", now, off));
        assertNull(en.handle("打车用哪个", now, off));
    }

    @Test public void isBare() {
        com.onesay.assistant.v2.roles.RoleCatalog.Role taxi = com.onesay.assistant.v2.roles.RoleCatalog.active().role("taxi");
        com.onesay.assistant.v2.roles.RoleCatalog.Role nav = com.onesay.assistant.v2.roles.RoleCatalog.active().role("nav");
        assertTrue(S2Engine.isBare("打车", taxi, null));
        assertTrue(S2Engine.isBare("帮我叫辆车", taxi, null));
        assertTrue(S2Engine.isBare("用滴滴打车", taxi, null));
        assertTrue(S2Engine.isBare("打车去机场", taxi, "机场"));
        assertFalse(S2Engine.isBare("打车28块", taxi, null));
        assertEquals("机场", taxi.destination("打车去机场"));
        assertEquals("公司", nav.destination("用百度地图导航到公司"));
        assertEquals("公司", taxi.destination("去公司打车"));
        assertEquals("机场", taxi.destination("用滴滴打车去机场"));
    }
}
