package com.onesay.assistant.v2.quicknote;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.kernel.FeatureHook;
import com.onesay.assistant.v2.kernel.FeatureKernel;
import com.onesay.assistant.v2.kernel.FeaturePlugin;
import com.onesay.assistant.v2.kernel.plugins.QuickNotePlugin;
import com.onesay.assistant.v2.s2.InMemoryMemoryStore;

import com.onesay.assistant.v2.s2.MemoryEntry;
import com.onesay.assistant.v2.vision.screenref.EntityExtractor;
import com.onesay.assistant.v2.wake.KeyTrigger;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;

/** [quicknote] 2026-10-11 记一记 + K L2 按键手势（纯 Java 部分 + 接线的源码断言）。 */
public class QuickNoteTest {

    private static final TimeZone CN = TimeZone.getTimeZone("Asia/Shanghai");
    private static final long NOW = 1791600000000L;   // 2026-10-10 前后，任意固定值

    private static QuickNoteClassifier.Note cls(String pkg, String... lines) {
        List<String> l = Arrays.asList(lines);
        return QuickNoteClassifier.classify(pkg, l, EntityExtractor.extractFromTexts(l, NOW, CN));
    }

    private static String src(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("../" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    // ---------------------------------------------------------------- KeyTrigger（守护 trigger 内容）

    @Test public void keyTriggerParse() {
        assertEquals(KeyTrigger.Kind.PRESS, KeyTrigger.parse("1791600000").kind);      // v5/v6：秒数
        assertEquals(KeyTrigger.Kind.PRESS, KeyTrigger.parse("").kind);
        assertEquals(KeyTrigger.Kind.PRESS, KeyTrigger.parse(null).kind);
        assertEquals(KeyTrigger.Kind.PRESS, KeyTrigger.parse("garbage x").kind);
        KeyTrigger p = KeyTrigger.parse("press 7 123456\n");
        assertEquals(KeyTrigger.Kind.PRESS, p.kind);
        assertEquals(7, p.seq);
        KeyTrigger l = KeyTrigger.parse("long 7 812");
        assertEquals(KeyTrigger.Kind.LONG, l.kind);
        assertEquals(7, l.seq);
        assertEquals(812L, l.ms);
        assertEquals(KeyTrigger.Kind.LONG, KeyTrigger.parse("long").kind);
        assertEquals(KeyTrigger.Kind.LONG, KeyTrigger.parse("long x y").kind);   // 坏数字不抛
    }

    @Test public void keyTriggerDebounce() {
        assertTrue("短按 800 ms 内去抖", KeyTrigger.debounced(KeyTrigger.Kind.PRESS, 1500, 1000));
        assertFalse(KeyTrigger.debounced(KeyTrigger.Kind.PRESS, 1900, 1000));
        assertFalse("长按约 800 ms 后到达，不能被去抖吃掉", KeyTrigger.debounced(KeyTrigger.Kind.LONG, 1500, 1000));
    }

    // ---------------------------------------------------------------- 语音句式

    @Test public void intents() {
        assertEquals(QuickNoteIntent.Kind.RUN, QuickNoteIntent.of("记一记"));
        assertEquals(QuickNoteIntent.Kind.RUN, QuickNoteIntent.of("把这个记一记。"));
        assertEquals(QuickNoteIntent.Kind.RUN, QuickNoteIntent.of("帮我记一记这页"));
        assertEquals(QuickNoteIntent.Kind.RECALL, QuickNoteIntent.of("刚才记一记的是什么"));
        assertEquals(QuickNoteIntent.Kind.LIST, QuickNoteIntent.of("记一记列表"));
        assertEquals(QuickNoteIntent.Kind.DELETE_LAST, QuickNoteIntent.of("删掉刚才的记一记"));
        assertEquals(QuickNoteIntent.Kind.NONE, QuickNoteIntent.of("记一下车停在 B2-17"));   // S2 的句子不抢
        assertEquals(QuickNoteIntent.Kind.NONE, QuickNoteIntent.of("打开蓝牙"));
        assertEquals(QuickNoteIntent.Kind.NONE, QuickNoteIntent.of(null));
        assertEquals(QuickNoteIntent.Kind.NONE, QuickNoteIntent.of("记一记明天给张三打电话然后发微信"));   // 不认的长句不接管
    }

    // ---------------------------------------------------------------- 本地分类

    @Test public void classifyPickupAndRefusals() {
        QuickNoteClassifier.Note n = cls("com.android.mms", "菜鸟驿站", "取件码 5-2-1234", "请在今天 18:00 前取件");
        assertEquals(QuickNoteClassifier.Category.PICKUP, n.category);
        assertTrue(n.title, n.title.contains("5-2-1234"));
        assertEquals(QuickNoteClassifier.Category.REFUSED, cls("com.android.mms", "【某银行】验证码 384912，5 分钟内有效，请勿告诉他人").category);
        assertEquals(QuickNoteClassifier.Category.REFUSED, cls("x", "请输入密码").category);
        QuickNoteClassifier.Note empty = QuickNoteClassifier.classify("x", Collections.<String>emptyList(), null);
        assertFalse(empty.saved());
        assertEquals("屏幕上没有可记的文字", empty.reason);
    }

    @Test public void classifyOtherKinds() {
        assertEquals(QuickNoteClassifier.Category.CHAT, cls("com.tencent.mm", "张三", "晚上一起吃饭吗", "好的老地方见").category);
        QuickNoteClassifier.Note o = cls("com.sec.android.app.launcher", "设置", "相机", "图库");
        assertEquals(QuickNoteClassifier.Category.OTHER, o.category);
        assertTrue(o.saved());
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 30; i++) sb.append("这是一段很长的正文内容");
        QuickNoteClassifier.Note a = cls("com.android.chrome", "标题：秋天的第一场雨", sb.toString(), sb.toString(), "第四段", "第五段");
        assertEquals(QuickNoteClassifier.Category.ARTICLE, a.category);
        assertTrue("摘要截断 ≤ 160+1", a.summary.length() <= QuickNoteClassifier.MAX_SUMMARY + 1);
    }

    @Test public void meaningfulDropsStatusBarNoise() {
        List<String> m = QuickNoteClassifier.meaningful(Arrays.asList("22:10", "100%", "a", null, "正文", "正文"));
        assertEquals(Collections.singletonList("正文"), m);
    }

    // ---------------------------------------------------------------- 存储（S2 记忆库）

    @Test public void bookSaveRecallListDelete() {
        // ★ R10：记一记搬到独立库（QuickNoteStore）；删除改软删（最近删除）
        InMemoryQuickNoteStore store = new InMemoryQuickNoteStore();
        QuickNoteBook b = new QuickNoteBook(store, CN);
        assertTrue(b.recallReply(NOW).startsWith("你还没用过记一记"));
        assertEquals("记一记里没有可删的。", b.deleteLastReply());
        b.save(cls("com.android.mms", "菜鸟驿站", "取件码 5-2-1234"), NOW - 120000L);
        b.save(cls("com.sec.android.app.launcher", "设置", "相机"), NOW - 10000L);
        assertEquals(-1, b.save(cls("x", "请输入密码"), NOW));
        assertEquals(2, b.list().size());
        assertTrue(b.recallReply(NOW), b.recallReply(NOW).startsWith("刚刚记一记的："));
        String list = b.listReply(NOW, 5);
        assertTrue(list, list.startsWith("记一记共 2 条") && list.contains("2 分钟前"));
        assertTrue(b.deleteLastReply().startsWith("已删掉最近一条记一记"));
        assertEquals(1, b.list().size());
        assertEquals("软删进最近删除", 1, b.deletedList().size());
        assertTrue(b.latest().summary.contains("5-2-1234"));
        assertEquals("取件码", b.latest().category);
        assertEquals("5-2-1234", b.latest().codeValue);
        b.deleteById(b.latest().id);
        assertEquals(0, b.list().size());
        assertEquals(2, b.deletedList().size());
        assertEquals("30 天内不清", 0, b.purge(System.currentTimeMillis() + 29L * 86400000L).size());
        assertEquals("超过 30 天彻底删", 2, b.purge(System.currentTimeMillis() + 31L * 86400000L).size());
        assertEquals(QuickNoteBook.RELATION, "quicknote");
    }

    // ---------------------------------------------------------------- 插件 / 内核

    @Test public void pluginDefaultOnAndGated() {
        FeaturePlugin p = FeatureKernel.get().find("quicknote");
        assertNotNull(p);
        assertTrue(p instanceof QuickNotePlugin);
        assertTrue("2026-10-11 晚起默认开（用户要长按记一记真正可用）", p.defaultOn());
        assertTrue(QuickNotePlugin.SWITCH.def);
        assertTrue("没设过 → 默认开", com.onesay.assistant.v2.kernel.FeatureSwitch.decide(null, false, QuickNotePlugin.SWITCH.def));
        assertFalse("kill-switch quicknote.off 仍然优先", com.onesay.assistant.v2.kernel.FeatureSwitch.decide(null, true, QuickNotePlugin.SWITCH.def));
        assertArrayEquals(new String[]{"quicknote.off"}, p.killFiles());
        assertTrue(p.kernelGated());
        assertFalse("无 Context → 关", p.enabled(null));
        assertEquals("quicknote", p.acceptanceModule());
        assertEquals("quicknote", p.debugExtra());
        assertEquals("bixby_quicknote", p.prefsName());
        assertTrue(p.wantsKeyGesture(null, "long"));
        assertFalse(p.wantsKeyGesture(null, "double"));
        assertTrue("「把这个记一记」绕过识屏 DEIXIS", p.bypassScreenRef(null, "把这个记一记"));
        assertFalse(p.bypassScreenRef(null, "复制这个号码"));
        assertTrue(p.claims(null, "记一记"));
        assertFalse(p.claims(null, "记一下车停在 B2-17"));
        // 没有 Context（JVM）→ 关：内核不分发手势、不要求建标记
        assertFalse(FeatureKernel.get().wantsKeyGesture(null, "long"));
        assertFalse(FeatureKernel.get().onKeyGesture(null, "long"));
        assertTrue(FeatureKernel.get().orderIds(FeatureHook.KEY_GESTURE).contains("quicknote"));
    }

    @Test public void pluginAfterMigratedOnes() {
        List<String> t = FeatureKernel.get().orderIds(FeatureHook.TAKEOVER);
        assertTrue(t.toString(), t.indexOf("quicknote") > t.indexOf("s2") && t.indexOf("quicknote") < t.indexOf("hello"));
    }

    // ---------------------------------------------------------------- 接线（源码）

    @Test public void wiring() throws Exception {
        String w = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        assertTrue(w.contains("KeyTrigger.parse(readSmall(triggerFile))"));
        assertTrue("长按先撤销唤醒窗", w.indexOf("cancelCaptureFromUi();", w.indexOf("private void onLongGesture(boolean silent)")) > 0);
        assertTrue(w.contains("syncGestureFlag(this)"));
        String a = src("app/src/com/onesay/assistant/WebViewUiActivity.java");
        assertTrue("冷启动 -e gesture long 不唤醒", a.indexOf("\"long\".equals(intent.getStringExtra(\"gesture\"))") < a.indexOf("WakeService.requestWake(WakeService.SRC_KEY_ACTIVITY"));
        String d = src("app/src/com/onesay/assistant/v2/test/PluginDebugDrivers.java");
        assertTrue(d.contains("QuickNoteTestDriver.handle"));
        String h = src("app/src/com/onesay/assistant/v2/quicknote/QuickNoteHooks.java");
        // ★ R10：缩略图（D7，默认关）/ 视觉兜底（D8）都在敏感拒收之后，且不在 Hooks 里直接截图
        assertFalse("Hooks 不直接截图", h.contains("screencap"));
        assertTrue("缩略图在存库（= 已过敏感检查）之后", h.indexOf("QuickNoteThumbs.capture") > h.indexOf("if (!n.saved())"));
        String v7 = src("tools/kdaemon/hijack_daemon.v7.sh");
        assertTrue("守护 v7 认 App 的手势标记", v7.contains("bixby.gesture_v2"));
    }

    // ---------------------------------------------------------------- 2026-10-11 晚：「我刚记的那个是什么」

    @Test public void genericRecentRecallAnswersLatestQuickNote() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        com.onesay.assistant.v2.s2.S2Engine en = new com.onesay.assistant.v2.s2.S2Engine(store, store, CN);
        MemoryEntry car = new MemoryEntry();
        car.subject = "车";
        car.relation = MemoryEntry.REL_PLACE;
        car.value = "B2-17";
        car.content = "车停在 B2-17";
        car.createdAt = NOW - 3600000L;
        store.insert(car);
        // ★ R10：记一记在独立库，S2 通过 ExtraNotes 视图看得到（D10）
        final QuickNoteBook qb = new QuickNoteBook(new InMemoryQuickNoteStore(), CN);
        qb.save(cls("com.android.mms", "菜鸟驿站", "取件码 5-2-1234"), NOW - 60000L);
        en.setExtraNotes(new com.onesay.assistant.v2.s2.S2Engine.ExtraNotes() {
            @Override public List<MemoryEntry> notes() { return qb.s2View(); }
            @Override public int clear() { return qb.store().clearAll(); }
        });
        com.onesay.assistant.v2.s2.S2Engine.Flags on = new com.onesay.assistant.v2.s2.S2Engine.Flags(true, true, true, false);
        for (String q : new String[]{"我刚记的那个是什么", "刚才记的是什么", "我刚记的是啥", "我刚才记了什么", "刚刚记的那条是什么"}) {
            com.onesay.assistant.v2.s2.S2Engine.Result r = en.handle(q, NOW, on);
            assertNotNull(q, r);
            assertEquals(q, com.onesay.assistant.v2.s2.S2Engine.Type.RECALL, r.type);
            assertTrue(q + " → " + r.reply, r.reply.contains("5-2-1234"));
        }
        // 真机：库里有假时钟写的「未来」条目时，不让它压过刚记的
        MemoryEntry future = new MemoryEntry();
        future.subject = "钥匙";
        future.content = "钥匙在鞋柜上";
        future.createdAt = NOW + 12 * 60000L;
        store.insert(future);
        com.onesay.assistant.v2.s2.S2Engine.Result rf = en.handle("我刚记的那个是什么", NOW, on);
        assertTrue(rf.reply, rf.reply.contains("5-2-1234"));
        // 带主体的问句照旧按主体找（不被「最新一条」抢走）
        com.onesay.assistant.v2.s2.S2Engine.Result carQ = en.handle("上次记的停车位置", NOW, on);
        assertTrue(carQ.reply, carQ.reply.contains("B2-17"));
    }

    @Test public void longPressFixesFromDevice() throws Exception {
        QuickNoteClassifier.Note r = QuickNoteClassifier.Note.refused("锁屏");
        assertFalse(r.saved());
        assertEquals("锁屏", r.reason);
        String hooks = src("app/src/com/onesay/assistant/v2/quicknote/QuickNoteHooks.java");
        int kg = hooks.indexOf("if (keyguardLocked(app))");
        int cap = hooks.indexOf("ScreenContextCapture.captureSync(app)");
        assertTrue("锁屏判断在读屏之前", kg > 0 && cap > kg);
        assertTrue("灵动岛回执", hooks.contains("island(\"quicknote\")"));
        String ws = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        int a = ws.indexOf("private void onLongGesture(boolean silent");   // BUG-A1 起多一个 seq 参数
        String body = ws.substring(a, ws.indexOf("dispatchKeyGesture(getApplicationContext(), \"long\"", a));
        assertTrue("长按撤掉按下时挂的到期回调（否则 12 s 后「没听清」）", body.contains("removeCallbacks(mWindowExpiredRunnable)"));
        assertTrue(body.contains("mGotCommand = true"));
        String plugin = src("app/src/com/onesay/assistant/v2/kernel/plugins/QuickNotePlugin.java");
        assertTrue("控制台开关改完同步手势标记", plugin.contains("WakeService.syncGestureFlag(act)"));
    }
}
