package com.onesay.assistant.v2.fix1123c;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.exec.ExecState;
import com.onesay.assistant.v2.fastscreen.NavKeys;
import com.onesay.assistant.v2.fastscreen.NavTouchParser;
import com.onesay.assistant.v2.fastscreen.NavWatch;
import com.onesay.assistant.v2.fastscreen.ReadBack;
import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.llm.ProviderHttp;
import com.onesay.assistant.v2.llm.ProviderKind;
import com.onesay.assistant.v2.llm.ProviderModels;
import com.onesay.assistant.v2.llm.WireRequest;
import com.onesay.assistant.v2.ui.console.FormChoices;
import com.onesay.assistant.v2.ui.console.PcEntry;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;

/**
 * [fix-1123-c] 线程 C 手机侧：执行中返回键紧急接管、fast-screen 遗留缺陷（观察线程泄漏 / 输入后读回旧提示 / 确认卡标题写成「定时任务」）、
 * 六处只让手填的设置改成选择、首页连接电脑入口。
 */
public class Fix1123cPhoneTest {

    static final File SRC = new File("app/src/com/onesay/assistant/v2");

    static String src(String rel) throws IOException {
        return new String(Files.readAllBytes(new File(SRC, rel).toPath()), StandardCharsets.UTF_8);
    }

    // ================================================================== 返回键紧急接管

    @Test public void shieldNavHitBackHomeRecents() {
        int[] r = NavKeys.compute(1080, 2220, 126, 0); // 默认：最近任务 | 主页 | 返回（返回在右）
        assertEquals("按了返回键", NavKeys.hit(r, 900, 2157)); // 真机探针记下的返回键落点
        assertEquals("按了主页键", NavKeys.hit(r, 540, 2157));
        assertEquals("按了多任务键", NavKeys.hit(r, 100, 2157));
        assertNull("导航栏以上 = 普通误触", NavKeys.hit(r, 540, 2000));
        assertNull(NavKeys.hit(r, 899, 2093));
        int[] l = NavKeys.compute(1080, 2220, 126, 1); // 返回在左
        assertEquals("按了返回键", NavKeys.hit(l, 100, 2157));
        assertNull("没有导航栏（全面屏手势）", NavKeys.hit(new int[8], 900, 2157));
        assertNull(NavKeys.hit(null, 900, 2157));
    }

    @Test public void takeoverOnlyWhileRunning() {
        ExecState s = new ExecState();
        s.start();
        assertTrue(s.navKey("按了返回键"));
        assertFalse("已经接手了：不叠加", s.navKey("按了返回键"));
        assertEquals(1, s.navTakeovers);
        String t = ExecState.navTakeoverToast("按了返回键");
        assertTrue(t, t.startsWith("我先停下，交给你来操作。"));
        assertTrue(t, t.contains("再按一次返回就是返回"));
        assertTrue(t, t.contains("「继续」"));
        assertTrue(ExecState.navTakeoverToast("按了主页键").contains("回桌面"));
        for (String w : new String[] {"啥", "咋", "呗", "Bixby", "三星"}) assertFalse(w, t.contains(w));
    }

    @Test public void shieldNoLongerSwallowsNavKeys() throws Exception {
        String sh = src("exec/ShieldOverlay.java"), ec = src("exec/ExecModeController.java");
        assertTrue(sh.contains("NavKeys.hit(mNav"));
        assertTrue("按到导航键先接管、不当误触", sh.indexOf("l.onNavKey(nav)") < sh.indexOf("mEnv.onStray(e.getRawX(), e.getRawY());\n        }"));
        assertTrue(ec.contains("mShield.setNavKeys(com.onesay.assistant.v2.fastscreen.NavKeys.rects(mApp)"));
        assertTrue(ec.contains("navTakeover(which, \"护罩\")"));
    }

    static byte[] cat(List<byte[]> l) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        for (byte[] b : l) o.write(b, 0, b.length);
        return o.toByteArray();
    }

    /** 一次按下：TRACKING_ID、X、Y、BTN_TOUCH、SYN；抬起：TRACKING_ID -1、BTN_TOUCH 0、SYN。 */
    static byte[] tap(int size, int x, int y) {
        List<byte[]> l = new ArrayList<byte[]>();
        l.add(NavTouchParser.event(size, 3, 0x39, 7));
        l.add(NavTouchParser.event(size, 3, 0x35, x));
        l.add(NavTouchParser.event(size, 3, 0x36, y));
        l.add(NavTouchParser.event(size, 1, 0x14a, 1));
        l.add(NavTouchParser.event(size, 0, 0, 0));
        l.add(NavTouchParser.event(size, 3, 0x39, -1));
        l.add(NavTouchParser.event(size, 1, 0x14a, 0));
        l.add(NavTouchParser.event(size, 0, 0, 0));
        return cat(l);
    }

    @Test public void parser64And32Bit() {
        for (int size : new int[] {NavTouchParser.EV64, NavTouchParser.EV32}) {
            final List<int[]> downs = new ArrayList<int[]>();
            NavTouchParser p = new NavTouchParser(size, 4095, 4095, 1080, 2220);
            byte[] b = tap(size, 3413, 3980);
            p.feed(b, b.length, new NavTouchParser.Sink() {
                @Override public void onDown(int sx, int sy) { downs.add(new int[] {sx, sy}); }
            });
            assertEquals("size " + size, 1, downs.size());
            assertEquals(900, downs.get(0)[0], 2);
            assertEquals(2157, downs.get(0)[1], 2);
        }
    }

    @Test public void parserFixesWrongGuessedSize() {
        // 以前固定按 24 字节切：32 位进程拿到的 16 字节事件全错位，一次按下都认不出（真机 backs 一直是 0）
        final int[] n = {0};
        NavTouchParser p = new NavTouchParser(NavTouchParser.EV64, 4095, 4095, 1080, 2220);
        byte[] b = tap(NavTouchParser.EV32, 3413, 3980); // 8 条 × 16 = 128，不能被 24 整除
        p.feed(b, b.length, new NavTouchParser.Sink() {
            @Override public void onDown(int sx, int sy) { n[0]++; }
        });
        assertEquals(NavTouchParser.EV32, p.eventSize());
        assertEquals(1, n[0]);
        // 按事件一条条喂（evdev 一次 read 可能只给一条）也一样
        final int[] m = {0};
        NavTouchParser q = new NavTouchParser(NavTouchParser.EV32, 4095, 4095, 1080, 2220);
        byte[] c = tap(NavTouchParser.EV32, 100, 100);
        for (int i = 0; i < c.length; i += 16) q.feed(Arrays.copyOfRange(c, i, i + 16), 16, new NavTouchParser.Sink() {
            @Override public void onDown(int sx, int sy) { m[0]++; }
        });
        assertEquals(1, m[0]);
        assertEquals(NavTouchParser.EV64, NavTouchParser.sizeFor(true));
        assertEquals(NavTouchParser.EV32, NavTouchParser.sizeFor(false));
    }

    // ================================================================== 观察线程泄漏

    @Test public void navWatchDoesNotLeakThreads() throws Exception {
        final NavWatch w = new NavWatch();
        final List<PipedOutputStream> outs = new ArrayList<PipedOutputStream>();
        NavWatch.Opener op = new NavWatch.Opener() {
            @Override public InputStream open() throws IOException {
                PipedInputStream in = new PipedInputStream(4096);
                synchronized (outs) { outs.add(new PipedOutputStream(in)); }
                return in; // 没人写：读的那一下一直卡着（和 /dev/input 一样）
            }
        };
        NavTouchParser.Sink sink = new NavTouchParser.Sink() {
            @Override public void onDown(int sx, int sy) { }
        };
        for (int i = 0; i < 8; i++) { // 8 个任务，每个开一次观察
            w.start(op, new NavTouchParser(24, 0, 0, 0, 0), sink, null);
            waitLive(w, 1);
        }
        assertEquals("任何时候只有一个观察线程（以前 6→8 个句柄）", 1, w.live.get());
        w.stop();
        waitLive(w, 0);
        assertEquals(0, w.live.get());
        assertFalse(w.on());
    }

    @Test public void navWatchStopsBlockedChannelRead() throws Exception {
        // 和助手一样走可中断通道：卡在读上的线程，stop 一下就退出（不靠下一次触摸）
        final NavWatch w = new NavWatch();
        final List<java.nio.channels.Pipe> pipes = new ArrayList<java.nio.channels.Pipe>();
        NavWatch.Opener op = new NavWatch.Opener() {
            @Override public InputStream open() throws IOException {
                java.nio.channels.Pipe p = java.nio.channels.Pipe.open();
                synchronized (pipes) { pipes.add(p); }
                return java.nio.channels.Channels.newInputStream(p.source());
            }
        };
        for (int i = 0; i < 5; i++) {
            w.start(op, new NavTouchParser(24, 0, 0, 0, 0), new NavTouchParser.Sink() {
                @Override public void onDown(int sx, int sy) { }
            }, null);
            waitLive(w, 1);
            Thread.sleep(20); // 让它真的卡进 read
        }
        assertEquals(1, w.live.get());
        w.stop();
        waitLive(w, 0);
        assertEquals("卡在读上的也退出了", 0, w.live.get());
    }

    @Test public void navWatchCountsTouches() throws Exception {
        final NavWatch w = new NavWatch();
        final AtomicReference<PipedOutputStream> out = new AtomicReference<PipedOutputStream>();
        final List<int[]> downs = new ArrayList<int[]>();
        w.start(new NavWatch.Opener() {
            @Override public InputStream open() throws IOException {
                PipedInputStream in = new PipedInputStream(4096);
                out.set(new PipedOutputStream(in));
                return in;
            }
        }, new NavTouchParser(24, 4095, 4095, 1080, 2220), new NavTouchParser.Sink() {
            @Override public void onDown(int sx, int sy) { synchronized (downs) { downs.add(new int[] {sx, sy}); } }
        }, null);
        while (out.get() == null) Thread.sleep(5);
        out.get().write(tap(24, 3413, 3980));
        out.get().flush();
        for (int i = 0; i < 200 && downs.isEmpty(); i++) Thread.sleep(5);
        assertEquals(1, downs.size());
        w.stop();
        waitLive(w, 0);
    }

    static void waitLive(NavWatch w, int n) throws InterruptedException {
        for (int i = 0; i < 400 && w.live.get() != n; i++) Thread.sleep(5);
    }

    @Test public void helperUsesTokenWatch() throws Exception {
        String h = src("fastscreen/HelperMain.java");
        assertFalse("全局 sWatchOn 已删", h.contains("sWatchOn"));
        assertFalse(h.contains("static void readTouches("));
        assertTrue(h.contains("sNav.start("));
        assertTrue(h.contains("NavTouchParser.sizeFor(is64())"));
    }

    // ================================================================== 输入后读回旧提示

    @Test public void readBackPrefersNewFocusedField() {
        assertEquals("set:关于手机", ReadBack.pick("关于手机", "关于手机", null));
        // 真机：旧节点读回「  搜索」，新搜索页的输入框里已经是「关于手机」
        assertEquals("set:关于手机", ReadBack.pick("关于手机", "  搜索", "关于手机"));
        // 两个都没读到：用新取的，并且标明没读到（不冒充成功）
        assertEquals("set:（没读到刚输入的字）", ReadBack.pick("关于手机", "  搜索", "").replace("set:", "set:"));
        assertEquals("set:  搜索（没读到刚输入的字）", ReadBack.pick("关于手机", "  搜索", null));
        assertFalse(ReadBack.has(null, "x"));
    }

    @Test public void readBackWiredInBothBackends() throws Exception {
        assertTrue(src("fastscreen/HelperMain.java").contains("ReadBack.pick(text, old, fresh)"));
        assertTrue(src("a11y/A11yBackend.java").contains("ReadBack.pick(text, old, fresh)"));
    }

    // ================================================================== 确认卡标题

    @Test public void confirmHeaderNotScheduledInExecMode() throws Exception {
        assertEquals("正在替你操作 · 等你确认", IslandModel.confirmHeader(true, true));
        assertEquals("定时任务 · 等你确认", IslandModel.confirmHeader(true, false));
        assertEquals("等你确认", IslandModel.confirmHeader(false, false));
        assertFalse(src("island/IslandView.java").contains("bg ? \"定时任务 · 等你确认\" : \"等你确认\""));
    }

    // ================================================================== 六处表单 → 选择

    @Test public void minuteSteps() {
        assertEquals(Arrays.asList(0, 5, 10, 15, 30, 60, 90, 120), FormChoices.minuteSteps(0, 120, 10));
        assertEquals("邮件 1–60：从 1 起", Arrays.asList(1, 5, 10, 15, 30, 60), FormChoices.minuteSteps(1, 60, 5));
        assertTrue("以前手填的 7 分钟也列出来", FormChoices.minuteSteps(0, 120, 7).contains(7));
        assertFalse("范围外不列", FormChoices.minuteSteps(1, 60, 90).contains(90));
        assertEquals("准时提醒（不提前）", FormChoices.minuteLabel(0));
        assertEquals("提前 15 分钟", FormChoices.minuteLabel(15));
        assertEquals("提前 1 小时", FormChoices.minuteLabel(60));
    }

    @Test public void quietTimes() {
        assertArrayEquals(new int[] {23, 0}, FormChoices.hm("23:00", "07:00"));
        assertArrayEquals(new int[] {7, 30}, FormChoices.hm("7：30", "07:00"));
        assertArrayEquals("坏值用默认", new int[] {23, 0}, FormChoices.hm("25:99", "23:00"));
        assertEquals("07:05", FormChoices.formatHm(7, 5));
        assertEquals("23:00 到 第二天 07:00", FormChoices.quietSpan("23:00", "07:00"));
        assertEquals("13:00 到 14:00", FormChoices.quietSpan("13:00", "14:00"));
    }

    @Test public void appListSortFilterJoin() {
        List<FormChoices.App> all = new ArrayList<FormChoices.App>(Arrays.asList(
                new FormChoices.App("微信", "com.tencent.mm"), new FormChoices.App("QQ", "com.tencent.mobileqq"),
                new FormChoices.App("支付宝", "com.eg.android.AlipayGphone"), new FormChoices.App("微信", "com.tencent.mm"),
                new FormChoices.App("", "com.example.noname")));
        Set<String> checked = new LinkedHashSet<String>(Arrays.asList("com.eg.android.AlipayGphone"));
        List<FormChoices.App> s = FormChoices.sortApps(all, checked);
        assertEquals("去重", 4, s.size());
        assertEquals("勾着的在前", "com.eg.android.AlipayGphone", s.get(0).pkg);
        assertEquals("没名字用包名", "com.example.noname", s.get(1).label);
        assertEquals(1, FormChoices.filter(s, "qq").size());
        assertEquals("按包名也能搜", 1, FormChoices.filter(s, "tencent.mm").size());
        assertEquals(4, FormChoices.filter(s, " ").size());
        Set<String> p = new LinkedHashSet<String>(Arrays.asList("com.tencent.mm", "com.tencent.mobileqq"));
        assertEquals("com.tencent.mm,com.tencent.mobileqq", FormChoices.joinPkgs(p));
        assertEquals("2 个：微信、QQ", FormChoices.pickedSummary(p, s));
        assertEquals("没有", FormChoices.pickedSummary(new LinkedHashSet<String>(), s));
    }

    @Test public void keywordTags() {
        List<String> t = FormChoices.addTags(null, "快递，取件码、 快递");
        assertEquals(Arrays.asList("快递", "取件码"), t);
        t = FormChoices.addTags(t, "外卖");
        t = FormChoices.removeTag(t, "快递");
        assertEquals(Arrays.asList("取件码", "外卖"), t);
        List<String> many = new ArrayList<String>();
        for (int i = 0; i < 30; i++) many = FormChoices.addTags(many, "词" + i);
        assertEquals(FormChoices.MAX_TAGS, many.size());
    }

    @Test public void modelChoices() {
        List<String> c = FormChoices.modelChoices("my-model", "deepseek-flash", Arrays.asList("deepseek-flash", "deepseek-pro"));
        assertEquals(Arrays.asList("my-model", "deepseek-flash", "deepseek-pro", FormChoices.CUSTOM), c);
        assertEquals("自定义放最后", FormChoices.CUSTOM, c.get(c.size() - 1));
        assertEquals("my-model（在用）", FormChoices.labels(c, "my-model")[0]);
        List<String> t = FormChoices.modelChoices("cosyvoice-v3.5-flash", "cosyvoice-v3.5-flash", Arrays.asList(FormChoices.TTS_MODELS));
        assertEquals("cosyvoice-v3.5-flash", t.get(0));
        assertEquals(FormChoices.TTS_MODELS.length + 1, t.size());
        assertEquals(Arrays.asList(FormChoices.CUSTOM), FormChoices.modelChoices("", "", null));
    }

    @Test public void eventTargets() {
        List<String> c = FormChoices.targetChoices(Arrays.asList("\"Home\"", "Office", "Home", "<unknown ssid>", " "));
        assertEquals(Arrays.asList(FormChoices.ANY, "Home", "Office", FormChoices.MANUAL), c);
        assertEquals("", FormChoices.targetOf(FormChoices.ANY));
        assertNull(FormChoices.targetOf(FormChoices.MANUAL));
        assertEquals("Home", FormChoices.targetOf("Home"));
    }

    @Test public void providerModelsFetched() {
        final List<String> urls = new ArrayList<String>();
        ProviderHttp.Transport t = new ProviderHttp.Transport() {
            @Override public ProviderHttp.Response exec(WireRequest req, String key, int c, int r) {
                urls.add(req.url);
                return new ProviderHttp.Response(200, "{\"data\":[{\"id\":\"deepseek-pro\"},{\"id\":\"deepseek-flash\"},{\"id\":\"bad model\"}]}");
            }
        };
        List<String> got = ProviderModels.list(ProviderKind.OPENAI_COMPAT, "https://api.deepseek.com", "k", t);
        assertEquals(Arrays.asList("deepseek-flash", "deepseek-pro"), got);
        assertEquals("https://api.deepseek.com/models", urls.get(0));
        assertTrue("没 Key 不发请求", ProviderModels.list(ProviderKind.OPENAI_COMPAT, "https://api.deepseek.com", "", t).isEmpty());
        assertEquals(1, urls.size());
        assertTrue("只收 https", ProviderModels.list(ProviderKind.OPENAI_COMPAT, "http://x.com", "k", t).isEmpty());
        ProviderHttp.Transport bad = new ProviderHttp.Transport() {
            @Override public ProviderHttp.Response exec(WireRequest req, String key, int c, int r) { return new ProviderHttp.Response(401, "{}"); }
        };
        assertTrue(ProviderModels.list(ProviderKind.OPENAI_COMPAT, "https://api.deepseek.com", "k", bad).isEmpty());
    }

    @Test public void sixPlacesNoLongerTypeIn() throws Exception {
        String notice = src("kernel/plugins/NoticePlugin.java");
        String wl = notice.substring(notice.indexOf("static void editWhitelist"), notice.indexOf("@Override public String debugStatus"));
        assertFalse("白名单不再手打包名", wl.contains("new EditText"));
        assertTrue(wl.contains("Pickers.apps("));
        assertTrue("提前分钟用档位", wl.contains("Pickers.minutes("));
        String cb = src("kernel/plugins/CallbackPlugin.java");
        String et = cb.substring(cb.indexOf("static void editTime"));
        assertFalse(et.substring(0, et.indexOf("@Override")).contains("new EditText"));
        assertTrue(et.contains("Pickers.time("));
        String ev = src("events/EventConsole.java");
        assertFalse("Wi‑Fi / 蓝牙不再手打", ev.contains("Wi‑Fi 名称 或 蓝牙设备名 / MAC（空 = 任意）"));
        assertFalse(ev.contains("白名单 App 包名，逗号分隔"));
        assertFalse(ev.contains("白名单关键词，逗号分隔"));
        assertTrue(ev.contains("EventPickData.wifiNames(act)"));
        assertTrue(ev.contains("EventPickData.bluetoothNames(act)"));
        assertTrue(ev.contains("Pickers.apps(act, \"哪些 App 的通知\""));
        assertTrue(ev.contains("＋ 加关键词"));
        String keys = src("ui/KeysActivity.java");
        assertTrue("音色：列表为主，手动填进高级", keys.contains("{\"从音色列表里选\", \"不用音色（清除）\", \"高级：手动填写音色 ID\"}"));
        String tts = keys.substring(keys.indexOf("void editTtsModel()"), keys.indexOf("void editBase("));
        assertFalse(tts.contains("new EditText"));
        assertTrue(tts.contains("FormChoices.TTS_MODELS"));
        String llm = src("ui/LlmProvidersActivity.java");
        String em = llm.substring(llm.indexOf("private void editModel("), llm.indexOf("interface Saver"));
        assertTrue(em.contains("ProviderModels.list("));
        assertTrue(em.contains("FormChoices.modelChoices("));
    }

    // ================================================================== 首页连接电脑

    @Test public void homePcEntry() throws Exception {
        assertEquals(PcEntry.KILLED, PcEntry.state(true, true, true, false));
        assertEquals(PcEntry.OFF, PcEntry.state(false, false, false, false));
        assertEquals(PcEntry.UNPAIRED, PcEntry.state(true, false, false, false));
        assertEquals(PcEntry.PAIRED, PcEntry.state(true, false, true, false));
        assertEquals(PcEntry.KEY_LOST, PcEntry.state(true, false, true, true));
        String home = src("ui/console/HomePage.java");
        assertTrue(home.contains("k.group(col, \"连接电脑\")"));
        assertTrue("没配对时首页直接扫码", home.contains("PcScanActivity.class"));
        assertTrue("没配对时首页直接粘贴", home.contains("PcLinkUi.pasteDialog(act"));
        assertTrue("首页这一组在「计划」前面", home.indexOf("\"连接电脑\"") < home.indexOf("k.group(col, \"计划\")"));
    }

    @Test public void visibleTextRules() throws Exception {
        String[] files = {"ui/console/FormChoices.java", "ui/console/Pickers.java", "events/EventConsole.java", "events/EventPickData.java",
                "ui/console/HomePage.java", "exec/ExecState.java", "fastscreen/ReadBack.java"};
        for (String f : files) {
            String s = src(f);
            for (String w : new String[] {"啥", "咋", "呗", "Bixby", "三星", "DeX", "小布"}) assertFalse(f + " 有「" + w + "」", s.contains(w));
        }
    }
}
