package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.suggest.core.scenes.ClipScene;
import com.onesay.assistant.plug.suggest.core.scenes.HeadsetScene;
import com.onesay.assistant.plug.suggest.core.scenes.LinkScene;
import com.onesay.assistant.plug.suggest.core.scenes.LinkTable;
import com.onesay.assistant.plug.suggest.core.scenes.MeetingScene;
import com.onesay.assistant.plug.suggest.core.scenes.MissedCallScene;
import com.onesay.assistant.plug.suggest.core.scenes.MusicApps;
import com.onesay.assistant.plug.suggest.core.scenes.PackageScene;
import com.onesay.assistant.plug.suggest.core.scenes.SummaryScene;
import com.onesay.assistant.plug.suggest.core.scenes.TorchScene;
import com.onesay.assistant.plug.suggest.core.scenes.TranslateScene;
import com.onesay.assistant.plug.suggest.core.scenes.TripSmsScene;
import com.onesay.assistant.plug.suggest.core.scenes.LowBatteryScene;
import com.onesay.assistant.plug.suggest.core.scenes.DepartNavScene;
import com.onesay.assistant.plug.suggest.core.scenes.shot.OwnWrites;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 场景 2/3/6/7/8 的规则与骨架场景的解析；D5 默认开关。 */
public class SuggestScenesTest {

    final SuggestFakes.FakeClock clock = new SuggestFakes.FakeClock();
    final SuggestFakes.FakeHost host = new SuggestFakes.FakeHost();
    final SuggestFakes.FakeConfig cfg = new SuggestFakes.FakeConfig();

    SceneContext ctx() {
        return new SceneContext() {
            @Override public com.onesay.assistant.plug.api.Clock clock() { return clock; }
            @Override public HostState host() { return host; }
            @Override public SceneConfig config() { return cfg; }
            @Override public String load(String key) { return null; }
            @Override public boolean save(String key, String val) { return true; }
        };
    }

    Signal sig(String type, String... kv) {
        return Signal.of(type, clock.el, clock.wall, kv);
    }

    @Test public void linkTable() {
        assertEquals("taobao", LinkTable.match("【淘宝】xx https://m.tb.cn/h.5abcDE 点击链接直接打开").entry.key);
        assertEquals("tmall 也归淘宝", "taobao", LinkTable.match("https://detail.tmall.com/item.htm?id=1").entry.key);
        LinkTable.Match tok = LinkTable.match("￥AbC12dEf34￥ 复制这条信息，打开手机淘宝");
        assertTrue(tok.token);
        assertNull(tok.url);
        assertNull("没有淘宝字样的口令不认", LinkTable.match("￥AbC12dEf34￥ 你好"));
        assertEquals("jd", LinkTable.match("https://u.jd.com/abcd").entry.key);
        assertEquals("pdd", LinkTable.match("https://mobile.yangkeduo.com/goods.html?goods_id=1").entry.key);
        assertEquals("douyin", LinkTable.match("7.43 复制打开抖音 v.douyin.com/iRNBho6/ 看看").entry.key);
        assertEquals("https://v.douyin.com/iRNBho6/", LinkTable.match("复制打开抖音 v.douyin.com/iRNBho6/ 看看").url);
        assertEquals("kuaishou", LinkTable.match("https://v.kuaishou.com/abc").entry.key);
        assertEquals("xhs", LinkTable.match("http://xhslink.com/a/bcd").entry.key);
        assertEquals("baidupan", LinkTable.match("链接: https://pan.baidu.com/s/1abc 提取码: x1y2").entry.key);
        assertNull("网盘首页不算分享链接", LinkTable.match("https://pan.baidu.com/disk/home"));
        assertEquals("quark", LinkTable.match("https://pan.quark.cn/s/abc").entry.key);
        assertNull(LinkTable.match("https://www.example.com/a"));
        assertNull(LinkTable.match(null));
        assertTrue(LinkTable.allPackages().contains("com.baidu.netdisk"));
    }

    @Test public void linkSceneNeedsInstalledApp() {
        LinkScene s = new LinkScene();
        assertNull("没装不弹", s.evaluate(sig(Signal.CLIP, "text", "https://u.jd.com/abc", "installed", "com.taobao.taobao"), ctx()));
        Suggestion g = s.evaluate(sig(Signal.CLIP, "text", "https://u.jd.com/abc", "installed", "com.taobao.taobao,com.jingdong.app.mall"), ctx());
        assertEquals("在京东打开", g.actions.get(0).label);
        assertEquals("com.jingdong.app.mall", g.get("pkg"));
        assertEquals("https://u.jd.com/abc", g.get("url"));
        assertEquals(2, g.priority);
    }

    @Test public void clipSceneChips() {
        ClipScene s = new ClipScene();
        assertNull(s.evaluate(sig(Signal.CLIP, "text", "hello"), ctx()));
        Suggestion three = s.evaluate(sig(Signal.CLIP, "labels", "导航\n打车\n拨打", "title", "复制了地址：深圳", "token", "t", "hash", "h"), ctx());
        assertEquals("芯片照旧 3 个、不挤掉", Arrays.asList("导航", "打车", "拨打"), three.labels());
        assertEquals("复制了地址：深圳", three.title);
        Suggestion one = s.evaluate(sig(Signal.CLIP, "labels", "查快递", "title", "复制了快递单号", "token", "t", "hash", "h"), ctx());
        assertEquals(Arrays.asList("查快递", "不再提示"), one.labels());
        assertEquals(2, ClipScene.chipIndex("chip2"));
        assertEquals(-1, ClipScene.chipIndex("mute"));
        assertEquals(60000L, ClipScene.POLICY.dedupWindowMs);
    }

    @Test public void headsetPicksMostUsed() {
        HeadsetScene s = new HeadsetScene();
        String cands = MusicApps.encode(Arrays.asList(new MusicApps.Usage("com.tencent.qqmusic", "QQ音乐", 1000),
                new MusicApps.Usage("com.netease.cloudmusic", "网易云音乐", 9000), new MusicApps.Usage("cmccwm.mobilemusic", "咪咕音乐", 0)));
        Suggestion g = s.evaluate(sig(Signal.HEADSET, "state", "on", "candidates", cands), ctx());
        assertEquals("不写死：用得最多的是网易云", "com.netease.cloudmusic", g.get("pkg"));
        assertEquals("第二个按钮给第二常用", "QQ音乐", g.actions.get(1).label);
        assertNull("没有使用数据不弹", s.evaluate(sig(Signal.HEADSET, "state", "on", "candidates", "cmccwm.mobilemusic|咪咕|0"), ctx()));
        assertNull("拔出不弹", s.evaluate(sig(Signal.HEADSET, "state", "off", "candidates", cands), ctx()));
        host.fg = "com.tencent.qqmusic";
        assertNull("已在听歌不弹", s.evaluate(sig(Signal.HEADSET, "state", "on", "candidates", cands), ctx()));
        assertEquals(30L * 60000L, HeadsetScene.POLICY.minIntervalMs);
    }

    @Test public void torchRules() {
        TorchScene s = new TorchScene();
        assertEquals("2026-11-04 用户：30 s", 30000L, TorchScene.ON_MIN_MS);
        long since = clock.el;
        clock.advance(29000L);
        host.fg = "com.android.chrome";
        assertNull("不到 30 s", s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "tick"), ctx()));
        clock.advance(1500L);
        Suggestion g = s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "tick"), ctx());
        assertNotNull("亮屏开满 30 s 就提醒", g);
        assertEquals("关闭手电筒", g.actions.get(0).label);
        assertEquals("已经开了 30 秒", g.subtitle);
        assertEquals(2, g.priority);
        host.fg = null;
        assertNotNull("前台拿不到（没 usage 权限）也照提醒", s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "tick"), ctx()));
        host.fg = "com.sec.android.app.camera";
        assertNull("还在相机里", s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "tick"), ctx()));
        assertNotNull("屏幕刚亮起", s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "screen_on"), ctx()));
        assertNull("已关", s.evaluate(sig(Signal.TORCH, "on", "0", "on_since", String.valueOf(since), "event", "screen_on"), ctx()));
        assertNull("off 事件不弹", s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "off"), ctx()));
        clock.advance(5L * 60000L);
        assertEquals("已经开了 5 分钟", s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "screen_on"), ctx()).subtitle);
    }

    @Test public void torchSilentWhileScreenOffOrLocked() {
        TorchScene s = new TorchScene();
        long since = clock.el;
        clock.advance(40000L);
        host.screenOn = false;
        assertNull("息屏不弹", s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "tick"), ctx()));
        host.screenOn = true;
        host.locked = true;
        assertNull("亮屏但还在锁屏界面不弹", s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "screen_on"), ctx()));
        host.locked = false;
        assertNotNull("解锁那一刻立刻提醒", s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "user_present"), ctx()));
        assertNotNull("无锁屏的机器：亮屏那一刻立刻提醒", s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "screen_on"), ctx()));
        assertNull("未知事件不弹", s.evaluate(sig(Signal.TORCH, "on", "1", "on_since", String.valueOf(since), "event", "x"), ctx()));
    }

    @Test public void newAppRules() {
        PackageScene s = new PackageScene();
        assertNotNull(s.evaluate(sig(Signal.PKG_ADDED, "pkg", "com.example", "label", "示例", "launchable", "1"), ctx()));
        assertNull("更新不算", s.evaluate(sig(Signal.PKG_ADDED, "pkg", "com.example", "launchable", "1", "replacing", "1"), ctx()));
        assertNull("没有桌面入口", s.evaluate(sig(Signal.PKG_ADDED, "pkg", "com.example", "launchable", "0"), ctx()));
        assertNull("自己", s.evaluate(sig(Signal.PKG_ADDED, "pkg", "com.onesay.assistant.debug", "launchable", "1"), ctx()));
        assertEquals("每包只提示一次", "pkg:com.example",
                s.evaluate(sig(Signal.PKG_ADDED, "pkg", "com.example", "launchable", "1"), ctx()).dedupKey);
    }

    @Test public void skeletonRules() {
        assertEquals("G1234", TripSmsScene.parse("【铁路客服】订单E123，张三您已购11月3日G1234次3车5F号，北京南站14:00开")[1]);
        assertEquals("flight", TripSmsScene.parse("您预订的航班CA1234将于11月3日08:30起飞")[0]);
        assertNull(TripSmsScene.parse("你好"));
        assertTrue(TranslateScene.foreign("The quick brown fox jumps over the lazy dog"));
        assertFalse(TranslateScene.foreign("今天天气不错 ok"));
        assertTrue(SummaryScene.longText(800, false));
        assertFalse(SummaryScene.longText(800, true));
        assertTrue(MissedCallScene.qualifies(2));
        assertTrue(MeetingScene.hasMeeting("点击链接入会 https://meeting.tencent.com/dm/abc"));
        assertTrue(DepartNavScene.timeToLeave(true, 0));
        assertTrue(LowBatteryScene.qualifies(12, false, true));
        assertFalse(LowBatteryScene.qualifies(12, true, true));
    }

    @Test public void d5Defaults() {
        SceneRegistry r = SuggestPlug.defaultScenes(new OwnWrites(), null);
        for (String on : new String[]{"screenshot", "link", "clip", "pickup", "headset", "torch", "newapp"}) {
            assertTrue(on + " 默认开", r.defaultOn(on));
        }
        for (String off : new String[]{"trip_sms", "calendar_add", "translate", "summarize", "missed_call", "meeting", "depart_nav", "low_battery"}) {
            assertFalse(off + " 默认关", r.defaultOn(off));
            assertTrue(off + " 骨架", r.isSkeleton(off));
        }
        for (SceneRegistry.Entry e : r.entries()) {
            if (e.scene.privacyClass() > 0) assertFalse(e.scene.id() + " 读个人数据 / 改设置的默认关", r.defaultOn(e.scene.id()));
        }
        assertEquals(15, r.entries().size());
    }
}
