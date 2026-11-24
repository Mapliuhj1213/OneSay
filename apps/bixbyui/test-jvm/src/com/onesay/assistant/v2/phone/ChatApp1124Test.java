package com.onesay.assistant.v2.phone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import com.onesay.assistant.v2.executor.IntentExecutor;
import com.onesay.assistant.v2.kernel.KernelWiring;

import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [chat-1124] 真机（魅族）：「打开美团拼好饭找到最近一张订单点击再来一单地址照旧，在付款前停下。」实际进的是另一个购物 App。
 * 打开核对只看「前台是不是要打开的那个包」，要打开的包本身认错了拦不住。这里复现并钉住：
 * 名字 → 包名先精确、模糊有门槛；要打开的 App 和用户点名的对不上就不打开；打开后被带到别的 App / 做着做着漂走都会被发现。
 */
public class ChatApp1124Test {

    static final String ORIGINAL = "打开美团拼好饭找到最近一张订单点击再来一单地址照旧，在付款前停下。";
    static final String MT = "com.sankuai.meituan", TB = "com.taobao.taobao", MTWM = "com.sankuai.meituan.takeoutnew";

    static Map<String, String> labels() {
        Map<String, String> m = new HashMap<String, String>();
        m.put("美团", MT);
        m.put("淘宝", TB);
        m.put("美团外卖", MTWM);
        m.put("设置", "com.android.settings");
        m.put("时钟", "com.android.deskclock");
        return m;
    }

    static void plain(String s) {
        assertFalse(s, s.contains("啥") || s.contains("咋") || s.contains("呗"));
    }

    // ------------------------------------------------------------------ 认出用户点名的 App

    @Test public void namedFromUtterance() {
        List<String> n = AppNameMatch.named(ORIGINAL, labels().keySet());
        assertEquals(Arrays.asList("美团"), n);
        assertEquals("长名字优先、不重叠", Arrays.asList("美团外卖"), AppNameMatch.named("帮我打开美团外卖点个饭", labels().keySet()));
        assertEquals(Arrays.asList("淘宝", "美团"), AppNameMatch.named("在淘宝看完再去美团", labels().keySet()));
        assertTrue("没点名 App = 不核对", AppNameMatch.named("帮我把亮度调高一点", labels().keySet()).isEmpty());
    }

    @Test public void wrongAppIsRefusedBeforeOpening() {
        List<String> n = AppNameMatch.named(ORIGINAL, labels().keySet());
        String why = AppNameMatch.check(n, TB, "淘宝", "com.onesay.assistant");
        assertNotNull("要开的是另一个 App：不开", why);
        assertTrue(why, why.contains("「美团」") && why.contains("对不上"));
        plain(why);
        assertNull("对的那个照常开", AppNameMatch.check(n, MT, "美团", "com.onesay.assistant"));
        assertNull("同一家的外卖 App 名字里含「美团」，算对得上", AppNameMatch.check(n, MTWM, "美团外卖", "com.onesay.assistant"));
        assertNull("系统设置之类顺路要进的界面不拦", AppNameMatch.check(n, "com.android.settings", "设置", "com.onesay.assistant"));
        assertNull("没点名不拦", AppNameMatch.check(java.util.Collections.<String>emptyList(), TB, "淘宝", null));
    }

    @Test public void matchesByKnownPkgWhenLabelUnknown() {
        assertFalse("名字读不到：常用名表说美团不是这个包", AppNameMatch.matches("美团", null, TB));
        assertTrue(AppNameMatch.matches("美团", null, MT));
        assertFalse(AppNameMatch.matches("美团", "淘宝", TB));
        assertTrue("说法里带了 App 名", AppNameMatch.matches("美团拼好饭", "美团", MT));
        assertTrue("核对不了不误拦", AppNameMatch.matches("某个没收录的应用", null, "com.x.y"));
    }

    // ------------------------------------------------------------------ 名字 → 包名：精确 / 常用名优先，模糊有门槛

    @Test public void resolveLabelExactFirstFuzzyWithThreshold() {
        Map<String, String> l = labels();
        assertEquals(MT, AppNameMatch.resolveLabel("美团", l));
        assertEquals(MT, AppNameMatch.resolveLabel("美团App", l));
        assertEquals(MTWM, AppNameMatch.resolveLabel("美团外卖", l));
        assertNull("一个字不模糊匹配", AppNameMatch.resolveLabel("团", l));
        assertNull("说法比名字长：不去猜", AppNameMatch.resolveLabel("美团拼好饭", l));
        Map<String, String> l2 = new HashMap<String, String>();
        l2.put("网易云音乐", "com.netease.cloudmusic");
        l2.put("网易云音乐极速版", "com.netease.cloudmusic.lite");
        assertEquals("多个都含：取最贴近的", "com.netease.cloudmusic", AppNameMatch.resolveLabel("网易云", l2));
        Map<String, String> l3 = new HashMap<String, String>();
        l3.put("淘宝特价版商家后台", "com.x.shop");
        assertNull("只占一小截名字：不算", AppNameMatch.resolveLabel("淘宝", l3));
    }

    @Test public void intentExecutorFuzzyHasThresholdAndIsDeterministic() {
        Map<String, String> l = new HashMap<String, String>();
        Map<String, String> c = new HashMap<String, String>();
        l.put("美团外卖", MTWM);
        c.put(MTWM, MTWM + "/.Main");
        l.put("美团优选商家版", "com.x.youxuan");
        c.put("com.x.youxuan", "com.x.youxuan/.Main");
        for (int i = 0; i < 5; i++) assertEquals("取名字最短的那个，不看 HashMap 顺序", MTWM + "/.Main", IntentExecutor.resolveIn("美团", l, c, null));
        assertNull("一个字不模糊", IntentExecutor.resolveIn("团", l, c, null));
        Map<String, String> l2 = new HashMap<String, String>();
        Map<String, String> c2 = new HashMap<String, String>();
        l2.put("淘宝特价版商家后台", "com.x.shop");
        c2.put("com.x.shop", "com.x.shop/.Main");
        assertNull("只占一小截：不算", IntentExecutor.resolveIn("淘宝", l2, c2, null));
    }

    // ------------------------------------------------------------------ PhoneAgent：开局 / 中途 launch 都过核对

    static AppNameMatch.Guard guard() {
        return new AppNameMatch.Guard(ORIGINAL, labels(), "com.onesay.assistant");
    }

    static FakeScreens.Phone phone() {
        FakeScreens.Phone p = new FakeScreens.Phone();
        p.add("home", FakeScreens.screen("com.android.launcher", ".Home").text("桌面"));
        p.add("tb", FakeScreens.screen(TB, ".Main").text("首页").button("我的淘宝"));
        p.add("mt", FakeScreens.screen(MT, ".Main").text("首页").button("我的").button("拼好饭"));
        p.on("*", "launch:" + TB, "tb");
        p.taps.put("*|launch:" + TB, "tb");
        p.taps.put("*|launch:" + MT, "mt");
        return p;
    }

    @Test public void repro_beforeFixWrongPkgWasOpened() {
        FakeScreens.Phone p = phone();
        FakeScreens.Script s = new FakeScreens.Script("{\"steps\":[{\"do\":\"launch\",\"app\":\"" + TB + "\"}]}", "{\"done\":\"找不到订单\"}");
        PhoneAgent a = new PhoneAgent(p, s, new FakeScreens.Host());   // 没有核对器 = 改前
        a.run(ORIGINAL, null);
        assertTrue("改前：认错的包照开不误", p.log.contains("launch " + TB));
    }

    @Test public void midTaskLaunchOfWrongAppIsRefused() {
        FakeScreens.Phone p = phone();
        FakeScreens.Script s = new FakeScreens.Script("{\"steps\":[{\"do\":\"launch\",\"app\":\"" + TB + "\"}]}", "{\"done\":\"好了\"}");
        PhoneAgent a = new PhoneAgent(p, s, new FakeScreens.Host());
        a.appGuard = guard();
        PhoneAgent.Result r = a.run(ORIGINAL, null);
        assertFalse("对不上就不打开", p.log.contains("launch " + TB));
        assertTrue(r.trace.toString(), r.trace.contains("launch-mismatch " + TB));
        assertTrue("下一轮模型看得到原因", s.users.get(1).contains("对不上"));
    }

    @Test public void startLabelResolvedToRightPkgAndRiskGateSeesPkg() {
        FakeScreens.Phone p = phone();
        FakeScreens.Script s = new FakeScreens.Script("{\"done\":\"到了\"}");
        PhoneAgent a = new PhoneAgent(p, s, new FakeScreens.Host());
        a.appGuard = guard();
        a.run(ORIGINAL, "美团");
        assertTrue(p.log.toString(), p.log.contains("launch " + MT));
    }

    @Test public void startPkgMismatchNotOpenedAndNoted() {
        FakeScreens.Phone p = phone();
        FakeScreens.Script s = new FakeScreens.Script("{\"done\":\"到了\"}");
        PhoneAgent a = new PhoneAgent(p, s, new FakeScreens.Host());
        a.appGuard = guard();
        PhoneAgent.Result r = a.run(ORIGINAL, TB);
        assertFalse(p.log.contains("launch " + TB));
        assertTrue(r.trace.contains("launch-mismatch " + TB));
        assertTrue(s.users.get(0).contains("对不上"));
    }

    @Test public void unknownLabelNotOpenedSaysNotFound() {
        FakeScreens.Phone p = phone();
        FakeScreens.Script s = new FakeScreens.Script("{\"steps\":[{\"do\":\"launch\",\"app\":\"拼好饭\"}]}", "{\"done\":\"好了\"}");
        PhoneAgent a = new PhoneAgent(p, s, new FakeScreens.Host());
        a.appGuard = guard();
        a.run(ORIGINAL, null);
        for (String l : p.log) assertFalse(l, l.startsWith("launch"));
        assertTrue(s.users.get(1), s.users.get(1).contains("没找到叫「拼好饭」的应用"));
    }

    @Test public void driftToOtherAppIsFlaggedToModel() {
        FakeScreens.Phone p = phone();
        p.cur = "tb";   // 开屏广告把人带走了 / 本来前台就是别的 App
        FakeScreens.Script s = new FakeScreens.Script("{\"done\":\"好了\"}");
        PhoneAgent a = new PhoneAgent(p, s, new FakeScreens.Host());
        a.appGuard = guard();
        PhoneAgent.Result r = a.run(ORIGINAL, null);
        assertTrue(r.trace.contains("app-drift " + TB));
        String u = s.users.get(0);
        assertTrue(u, u.contains("不是用户说的「美团」") && u.contains(MT));
        plain(u.substring(u.indexOf("注意：")));
        // 前台就是美团：不提醒
        FakeScreens.Phone p2 = phone();
        p2.cur = "mt";
        FakeScreens.Script s2 = new FakeScreens.Script("{\"done\":\"好了\"}");
        PhoneAgent a2 = new PhoneAgent(p2, s2, new FakeScreens.Host());
        a2.appGuard = guard();
        a2.run(ORIGINAL, null);
        assertFalse(s2.users.get(0).contains("注意：现在前台是"));
    }

    // ------------------------------------------------------------------ 打开以后被带到别的 App（开屏广告）

    static LaunchCheck.Probe seq(final String... fgs) {
        return new LaunchCheck.Probe() {
            int i;
            long t;

            @Override public String foreground() {
                return fgs[Math.min(i++, fgs.length - 1)];
            }

            @Override public long now() {
                return t;
            }

            @Override public void sleep(long ms) {
                t += ms;
            }

            @Override public boolean relaunch() {
                return false;
            }
        };
    }

    @Test public void jumpedAwayAfterOpening() {
        assertEquals(TB, LaunchCheck.jumpedAway(MT, "com.onesay.assistant", seq(MT, MT, TB)));
        assertNull("一直在目标上", LaunchCheck.jumpedAway(MT, "com.onesay.assistant", seq(MT)));
        assertNull("系统弹框不算被带走", LaunchCheck.jumpedAway(MT, "com.onesay.assistant", seq(MT, "com.android.systemui", MT)));
        assertNull("读不到不算", LaunchCheck.jumpedAway(MT, "com.onesay.assistant", seq((String) null)));
        String n = LaunchCheck.jumpNote("美团", "淘宝");
        assertTrue(n, n.contains("「美团」") && n.contains("「淘宝」"));
        plain(n);
    }

    // ------------------------------------------------------------------ 接线

    @Test public void wiring() {
        String ph = KernelWiring.src("app/src/com/onesay/assistant/v2/phone/PhoneHooks.java");
        assertTrue(ph.contains("agent.appGuard = g;"));
        String pp = KernelWiring.src("app/src/com/onesay/assistant/v2/kernel/plugins/PhonePlugin.java");
        assertTrue("交下去的是用户原话", pp.contains("TaskContinuation.strip(ses.query)") && pp.contains("runBlocking(ctx, goal, app, false, true, null, userSaid)"));
        String fd = KernelWiring.src("app/src/com/onesay/assistant/v2/phone/FastScreenDriver.java");
        assertTrue("到前台后再看有没有被带走", fd.contains("return stayed(pkg);"));
        String ue = KernelWiring.src("app/src/com/onesay/assistant/v2/executor/UnifiedExecutor.java");
        assertTrue("模型填的 pkg 和名字对不上不信 pkg", ue.contains("!pkgAgreesWithName(appName, hintPkg)"));
    }
}
