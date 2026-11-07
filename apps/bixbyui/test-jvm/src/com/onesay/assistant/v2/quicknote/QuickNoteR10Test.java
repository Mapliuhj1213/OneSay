package com.onesay.assistant.v2.quicknote;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.island.IslandModel;
import com.onesay.assistant.v2.s1.PickupCardStore;
import com.onesay.assistant.v2.s2.InMemoryMemoryStore;
import com.onesay.assistant.v2.s2.MemoryEntry;
import com.onesay.assistant.v2.s2.S2Engine;
import com.onesay.assistant.v2.vision.screenref.EntityExtractor;
import com.onesay.assistant.v2.wake.KeyTrigger;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TimeZone;

/** [quicknote] R10 记一记 × 小布：守护 v8 协议、分类 v2、独立库、迁移、按内容回忆、动作、岛上结果卡 / 码胶囊、S2 联动。 */
public class QuickNoteR10Test {

    private static final TimeZone CN = TimeZone.getTimeZone("Asia/Shanghai");
    private static final long NOW = 1791600000000L;
    private static final long H = 3600L * 1000L;

    private static QuickNoteClassifier.Note cls(String pkg, String... lines) {
        List<String> l = Arrays.asList(lines);
        return QuickNoteClassifier.classify(pkg, l, EntityExtractor.extractFromTexts(l, NOW, CN));
    }

    private static String src(String rel) throws Exception {
        File f = new File(rel);
        if (!f.exists()) f = new File("../" + rel);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static QuickNoteBook bookWith(String[]... screens) {
        QuickNoteBook b = new QuickNoteBook(new InMemoryQuickNoteStore(), CN);
        long t = NOW - screens.length * 60000L;
        for (String[] s : screens) {
            String pkg = s[0];
            String[] lines = Arrays.copyOfRange(s, 1, s.length);
            b.save(cls(pkg, lines), t);
            t += 60000L;
        }
        return b;
    }

    // ---------------------------------------------------------------- P0 守护 v8 协议

    @Test public void keyTriggerV8() {
        KeyTrigger d = KeyTrigger.parse("down 3 123456");
        assertEquals(KeyTrigger.Kind.DOWN, d.kind);
        assertEquals(3, d.seq);
        KeyTrigger p = KeyTrigger.parse("press 3 120");
        assertEquals(KeyTrigger.Kind.PRESS, p.kind);
        assertEquals(120L, p.ms);
        KeyTrigger u = KeyTrigger.parse("up 4 2300");
        assertEquals(KeyTrigger.Kind.UP, u.kind);
        assertEquals(2300L, u.ms);
        assertFalse("DOWN 不受短按去抖", KeyTrigger.debounced(KeyTrigger.Kind.DOWN, 1100, 1000));
        assertEquals("bixby.gesture_v3", KeyTrigger.GESTURE_FLAG_V3);
    }

    @Test public void wakeServiceDownIsSilent() throws Exception {
        String w = src("app/src/com/onesay/assistant/v2/wake/WakeService.java");
        int a = w.indexOf("if (kt.kind == KeyTrigger.Kind.DOWN) {");
        int b = w.indexOf("return;", a);
        assertTrue(a > 0 && b > a);
        String body = w.substring(a, b);
        assertFalse("按下只预热：不唤醒、不出声", body.contains("requestWakeInternal") || body.contains("playWakeSounds"));
        assertTrue("v3 标记跟 v2 一起建", w.contains("KeyTrigger.GESTURE_FLAG_V3"));
        assertTrue("长按震动", w.contains("vibrateTick();"));
        assertTrue("v7 兜底掐断唤醒音", w.contains("if (!silent) abortAckSound();"));
        assertTrue("按住 ≥ 2 s = 随口记", w.contains("dispatchKeyGesture(getApplicationContext(), \"hold\"")   /* BUG-A1 起带 seq */);
        assertTrue("短按延迟日志", w.contains("[KEY-LATENCY]"));
        String v8 = src("tools/kdaemon/hijack_daemon.v8.sh");
        assertTrue(v8.contains("bixby.gesture_v3") && v8.contains("echo \"down $seq $now\"") && v8.contains("echo \"press $seq $dur\""));
        assertTrue("v8 离线测试脚本在", new File("tools/kdaemon/test_hijack_v8.sh").exists() || new File("../tools/kdaemon/test_hijack_v8.sh").exists());
    }

    // ---------------------------------------------------------------- P1 分类 v2

    @Test public void classifierV2Fields() {
        QuickNoteClassifier.Note meal = cls("com.lucky.luckyclient", "瑞幸咖啡（科技园店）", "取餐码 A123", "生椰拿铁 冰");
        assertEquals("取餐码", meal.label());
        assertEquals("A123", meal.codeValue);
        assertTrue(meal.tags.contains("取餐码"));
        assertTrue(meal.body.contains("生椰拿铁"));
        QuickNoteClassifier.Note coupon = cls("x", "兑换码 XK9P2M7Q", "有效期至 10 月 31 日");
        assertEquals("券码", coupon.label());
        QuickNoteClassifier.Note order = cls("com.taobao.taobao", "订单详情", "实付 ¥128.50", "已支付");
        assertEquals(QuickNoteClassifier.Category.ORDER, order.category);
        assertEquals(12850L, order.amountFen);
        assertTrue(order.tags.contains("账单"));
        assertTrue(order.tags.contains("购物"));
        // R10 真机复核：订单页上的「订单号 2026101112345」不能被当成快递单号
        QuickNoteClassifier.Note orderNo = cls("com.sankuai.meituan", "订单详情", "实付 ¥38.50", "订单号 2026101112345", "已支付");
        assertEquals(QuickNoteClassifier.Category.ORDER, orderNo.category);
        assertEquals(3850L, orderNo.amountFen);
        QuickNoteClassifier.Note parcel = cls("com.cainiao.wireless", "菜鸟", "运单号 SF1234567890123", "派送中");
        assertEquals(QuickNoteClassifier.Category.PARCEL, parcel.category);
        QuickNoteClassifier.Note contact = cls("com.android.chrome", "王经理", "电话 13800138000", "欢迎来电咨询");
        assertEquals(QuickNoteClassifier.Category.CONTACT, contact.category);
        QuickNoteEntry ce = new QuickNoteBook(new InMemoryQuickNoteStore(), CN).toEntry(contact, NOW, "long");
        assertNotNull(ce.firstEnt("PHONE"));
        assertEquals("王经理", ce.firstEnt("NAME").value);
        QuickNoteClassifier.Note sale = cls("cn.damai", "周杰伦演唱会", "10月20日 12:00 开售", "请提前登录");
        assertEquals(QuickNoteClassifier.Category.SCHEDULE, sale.category);
        assertTrue(sale.tags.contains("开售"));
        assertTrue(sale.eventAt > 0);
        // 拒收照旧
        assertFalse(cls("x", "您的验证码 384912，5 分钟内有效").saved());
        assertFalse(cls("x", "银行卡号 6222 0212 3456 7890 123").saved());
    }

    @Test public void voiceNote() {
        List<String> one = Arrays.asList("明天上午9点交房租");
        QuickNoteClassifier.Note v = QuickNoteClassifier.classifyVoice(one.get(0), EntityExtractor.extractFromTexts(one, NOW, CN));
        assertEquals(QuickNoteClassifier.Category.VOICE, v.category);
        assertEquals("随口记", v.label());
        assertTrue(v.eventAt > 0);
        assertFalse(QuickNoteClassifier.classifyVoice("我的密码是 abc123456", null).saved());
        assertFalse(QuickNoteClassifier.classifyVoice("  ", null).saved());
    }

    // ---------------------------------------------------------------- 存储 / 迁移

    @Test public void storeSoftDeleteRestore() {
        InMemoryQuickNoteStore st = new InMemoryQuickNoteStore();
        QuickNoteBook b = new QuickNoteBook(st, CN);
        long id = b.save(cls("x", "设置", "相机"), NOW);
        assertTrue(id > 0);
        assertTrue(st.softDelete(id, NOW));
        assertFalse("不重复删", st.softDelete(id, NOW));
        assertEquals(0, st.count(false));
        assertEquals(1, st.count(true));
        assertTrue(st.restore(id));
        assertEquals(1, st.count(false));
        QuickNoteEntry e = st.get(id);
        e.notes = "补一句";
        assertTrue(st.update(e));
        assertEquals("补一句", st.get(id).notes);
        assertEquals(1, st.clearAll());
    }

    @Test public void migrationIdempotent() {
        InMemoryMemoryStore s2 = new InMemoryMemoryStore();
        MemoryEntry q = new MemoryEntry();
        q.subject = "记一记";
        q.relation = "quicknote";
        q.value = "取件码";
        q.content = "取件码 5-2-1234（菜鸟驿站）";
        q.createdAt = NOW - H;
        s2.insert(q);
        MemoryEntry car = new MemoryEntry();
        car.subject = "车";
        car.content = "车停在 B2-17";
        car.createdAt = NOW - 2 * H;
        s2.insert(car);
        InMemoryQuickNoteStore dst = new InMemoryQuickNoteStore();
        assertEquals(1, QuickNoteMigration.migrate(s2.list(MemoryEntry.KIND_NOTE), dst, CN));
        assertEquals("再跑一次不重复", 0, QuickNoteMigration.migrate(s2.list(MemoryEntry.KIND_NOTE), dst, CN));
        QuickNoteEntry m = dst.list(false).get(0);
        assertEquals("migrated", m.source);
        assertEquals("5-2-1234", m.codeValue);
        assertEquals(QuickNoteEntry.CODE_TAKEN, m.codeState);
        assertEquals("S2 原行保留", 2, s2.list(MemoryEntry.KIND_NOTE).size());
    }

    // ---------------------------------------------------------------- 按内容回忆

    @Test public void recallByContent() {
        QuickNoteBook b = bookWith(
                new String[]{"com.android.mms", "菜鸟驿站", "取件码 5-2-1234"},
                new String[]{"com.xingin.xhs", "三亚 4 天 3 晚攻略", "第一天亚龙湾，第二天蜈支洲岛，第三天天涯海角，第四天免税店。", "住宿推荐海棠湾", "交通租车最方便"},
                new String[]{"com.lucky.luckyclient", "瑞幸咖啡", "取餐码 A123"});
        b.save(QuickNoteClassifier.classifyVoice("车停在B2层C区15号", null), NOW - 1000);
        String code = QuickNoteRecall.answer(b, "我的取件码是多少", NOW, CN);
        assertNotNull(code);
        assertTrue(code, code.startsWith("取件码 5-2-1234"));
        String meal = QuickNoteRecall.answer(b, "取餐码是多少", NOW, CN);
        assertTrue(meal, meal.contains("A123"));
        String park = QuickNoteRecall.answer(b, "车停哪了", NOW, CN);
        assertTrue(park, park.contains("B2层C区15号"));
        String sanya = QuickNoteRecall.answer(b, "记一记里关于三亚的", NOW, CN);
        assertTrue(sanya, sanya.contains("三亚"));
        assertNull("搜不到返回 null（软句式留给 S2）", QuickNoteRecall.answer(b, "我记过的那个北京烤鸭", NOW, CN));
        assertTrue(QuickNoteRecall.missReply("车停哪了").contains("停车"));
        assertTrue(b.categoryReply("取件码", NOW, 5).contains("5-2-1234"));
        assertTrue(b.categoryReply("码", NOW, 5).contains("共 2 条"));
    }

    @Test public void searchFiltersAndTime() {
        QuickNoteBook b = new QuickNoteBook(new InMemoryQuickNoteStore(), CN);
        b.save(cls("x", "订单详情", "实付 ¥35.00", "已支付"), NOW - 3 * 24 * H);
        b.save(cls("x", "订单详情", "实付 ¥20.00", "已支付"), NOW - 60000L);
        QuickNoteSearch.Query q = QuickNoteSearch.parse("今天的订单", NOW, CN);
        assertEquals("订单", q.category);
        assertEquals("", q.keywords);
        assertEquals(1, QuickNoteSearch.search(b.list(), q, 5).size());
        assertEquals(2, QuickNoteSearch.search(b.list(), "我记了哪些订单", NOW, CN, 5).size());
        assertEquals("没条件不乱答", 0, QuickNoteSearch.search(b.list(), "我记的", NOW, CN, 5).size());
    }

    // ---------------------------------------------------------------- 语音句式

    @Test public void intentsR10() {
        assertEquals(QuickNoteIntent.Kind.SUIKOUJI, QuickNoteIntent.of("随口记 明早八点交房租"));
        assertEquals(QuickNoteIntent.Kind.SUIKOUJI, QuickNoteIntent.of("帮我记一下车停在 B2-17"));
        assertEquals("车停在 B2-17", QuickNoteIntent.suikoujiText("帮我记一下车停在 B2-17"));
        assertEquals("S2「记一下」不抢", QuickNoteIntent.Kind.NONE, QuickNoteIntent.of("记一下车停在 B2-17"));
        assertEquals(QuickNoteIntent.Kind.SEARCH, QuickNoteIntent.of("我的取件码是多少"));
        assertEquals(QuickNoteIntent.Kind.SEARCH, QuickNoteIntent.of("车停哪了"));
        assertEquals(QuickNoteIntent.Kind.SEARCH, QuickNoteIntent.of("我的车停在哪里"));
        assertEquals(QuickNoteIntent.Kind.CATEGORY, QuickNoteIntent.of("我记了哪些取件码"));
        assertEquals(QuickNoteIntent.Kind.CATEGORY, QuickNoteIntent.of("记一记里有哪些文章"));
        assertEquals(QuickNoteIntent.Kind.SEARCH, QuickNoteIntent.of("记一记里关于健身的"));
        assertEquals(QuickNoteIntent.Kind.SEARCH_SOFT, QuickNoteIntent.of("我记过的那个三亚攻略"));
        assertEquals(QuickNoteIntent.Kind.OPEN, QuickNoteIntent.of("打开记一记"));
        assertEquals(QuickNoteIntent.Kind.NONE, QuickNoteIntent.of("圈一下"));
        assertEquals(QuickNoteIntent.Kind.NONE, QuickNoteIntent.of("导航到停车场"));
        assertEquals(QuickNoteIntent.Kind.NONE, QuickNoteIntent.of("打开蓝牙"));
        assertEquals("S2 的「我记了什么」不抢", QuickNoteIntent.Kind.NONE, QuickNoteIntent.of("我记了什么"));
        assertTrue(QuickNoteIntent.beforeS2("车停哪了"));
        assertFalse("软句式不抢 S2", QuickNoteIntent.beforeS2("我记过的那个三亚攻略"));
        assertFalse(QuickNoteIntent.beforeS2("记一下车停在 B2-17"));
    }

    // ---------------------------------------------------------------- 动作

    @Test public void actions() {
        QuickNoteBook b = new QuickNoteBook(new InMemoryQuickNoteStore(), CN);
        QuickNoteEntry code = b.toEntry(cls("com.android.mms", "菜鸟驿站", "取件码 5-2-1234"), NOW, "long");
        List<QuickNoteActions.Action> c = QuickNoteActions.card(code, CN);
        assertTrue(c.size() <= QuickNoteActions.MAX_CARD);
        assertEquals(QuickNoteActions.COPY, c.get(0).type);
        assertEquals(QuickNoteActions.UNDO, c.get(c.size() - 1).type);
        QuickNoteEntry sale = b.toEntry(cls("cn.damai", "周杰伦演唱会", "10月20日 12:00 开售"), NOW, "long");
        List<QuickNoteActions.Action> s = QuickNoteActions.card(sale, CN);
        assertEquals(QuickNoteActions.ALARM, s.get(0).type);
        assertEquals(QuickNoteActions.CALENDAR, s.get(1).type);
        QuickNoteEntry contact = b.toEntry(cls("com.android.chrome", "王经理", "电话 13800138000"), NOW, "long");
        boolean hasContact = false;
        for (QuickNoteActions.Action a : QuickNoteActions.all(contact, CN)) {
            if (QuickNoteActions.CONTACT.equals(a.type)) hasContact = true;
            assertFalse("不放拨号", a.type.contains("call"));
        }
        assertTrue(hasContact);
    }

    // ---------------------------------------------------------------- 岛：结果卡 / 码胶囊

    @Test public void islandNoteLifecycle() {
        IslandModel m = new IslandModel();
        m.setMode(IslandModel.Mode.ISLAND);
        m.noteStarted("qn1", "记录中", NOW);
        assertEquals(IslandModel.Kind.COMPACT_NOTE, m.view().kind);
        assertEquals(IslandModel.Status.RUN, m.view().status);
        m.drainTimers();
        m.noteResult("qn1", true, "取件码 5-2-1234", "已放到灵动岛上", "记一记 · 取件码", Arrays.asList("复制取件码", "打开短信", "撤销", "多余"), NOW);
        IslandModel.Snapshot s = m.view();
        assertEquals(IslandModel.Kind.EXP_NOTE, s.kind);
        assertEquals("最多 3 个动作", 3, s.noteActions.size());
        assertEquals("记一记 · 取件码", s.noteMeta);
        List<IslandModel.Timer> ts = m.drainTimers();
        IslandModel.Timer hide = null;
        for (IslandModel.Timer t : ts) if ("noteHide".equals(t.name)) hide = t;
        assertNotNull(hide);
        assertEquals("停留 6 s", 6000L, hide.delayMs);
        List<IslandModel.Effect> e = m.noteAction(2);
        assertTrue(e.contains(IslandModel.Effect.NOTE_ACTION));
        assertEquals("qn1#2", m.lastTarget());
        assertEquals(IslandModel.Kind.HIDDEN, m.view().kind);
        // 到时收走
        m.noteResult("qn2", false, "没有记：验证码", null, null, null, NOW);
        assertEquals(IslandModel.Kind.EXP_NOTE, m.view().kind);
        IslandModel.Timer h2 = null;
        for (IslandModel.Timer t : m.drainTimers()) if ("noteHide".equals(t.name)) h2 = t;
        m.timerDue(h2.name, h2.seq, h2.target);
        assertEquals(IslandModel.Kind.HIDDEN, m.view().kind);
        // 紧凑态点按 → 打开详情
        m.noteStarted("qn3", null, NOW);
        assertTrue(m.tapMain().contains(IslandModel.Effect.OPEN_APP_NOTE));
        assertEquals("qn3", m.lastTarget());
    }

    @Test public void islandNoteWithPickupIsDual() {
        IslandModel m = new IslandModel();
        m.setMode(IslandModel.Mode.ISLAND);
        List<IslandModel.Pickup> p = new ArrayList<IslandModel.Pickup>();
        p.add(new IslandModel.Pickup("取餐码|A123", "取餐码", "A123", "瑞幸", "记一记", NOW, NOW + 3 * H));
        m.setPickups(p);
        m.noteStarted("qn1", "记录中", NOW);
        assertEquals(IslandModel.Kind.MINIMAL_DUAL, m.view().kind);
        m.noteResult("qn1", true, "取餐码 A123", null, null, Arrays.asList("复制取餐码", "撤销"), NOW);
        assertEquals("结果卡优先展开", IslandModel.Kind.EXP_NOTE, m.view().kind);
        m.noteOpen();
        assertEquals("码胶囊常驻", IslandModel.Kind.COMPACT_PICKUP, m.view().kind);
    }

    @Test public void pickupStoreFromQuickNote() {
        PickupCardStore st = new PickupCardStore();
        PickupCardStore.Card c = st.addFromQuickNote("取餐码", "A123", "瑞幸", "com.lucky.luckyclient", 42L, NOW, QuickNoteSettings.codeTtlMs("取餐码"));
        assertNotNull(c);
        assertEquals(NOW + 3 * H, c.expiresAt);
        assertNull("同码不重复上岛", st.addFromQuickNote("取餐码", "A123", null, null, 43L, NOW + 1000, H));
        PickupCardStore back = PickupCardStore.fromJson(st.toJson(), NOW + 1000);
        PickupCardStore.Card r = back.find(c.id);
        assertEquals("com.lucky.luckyclient", r.pkg);
        assertEquals(42L, r.noteId);
        assertTrue(r.fromQuickNote());
        assertTrue(back.dismissByKey("取餐码", "A123"));
        assertTrue(back.active(NOW + 2000).isEmpty());
        assertEquals(24 * H, QuickNoteSettings.codeTtlMs("券码"));
        assertEquals(12 * H, QuickNoteSettings.codeTtlMs("取件码"));
        assertTrue(QuickNoteSettings.sensitivePkg("com.eg.android.AlipayGphone"));
        assertTrue(QuickNoteSettings.sensitivePkg("cmb.pb"));
        assertFalse(QuickNoteSettings.sensitivePkg("com.xingin.xhs"));
        assertFalse("缩略图默认关", QuickNoteSettings.defaultOf(QuickNoteSettings.KEY_THUMBS));
        assertTrue("视觉兜底默认开", QuickNoteSettings.defaultOf(QuickNoteSettings.KEY_VL));
    }

    // ---------------------------------------------------------------- S2 联动（D10）

    @Test public void s2SeesQuickNotes() {
        InMemoryMemoryStore store = new InMemoryMemoryStore();
        S2Engine en = new S2Engine(store, store, CN);
        MemoryEntry old = new MemoryEntry();   // 迁移前留在 S2 的旧记一记行：有视图时不重复列
        old.subject = "记一记";
        old.relation = "quicknote";
        old.content = "旧的取件码 1-1-1111";
        old.createdAt = NOW - 5 * H;
        store.insert(old);
        final QuickNoteBook qb = new QuickNoteBook(new InMemoryQuickNoteStore(), CN);
        qb.save(cls("x", "菜鸟驿站", "取件码 5-2-1234"), NOW - 60000L);
        en.setExtraNotes(new S2Engine.ExtraNotes() {
            @Override public List<MemoryEntry> notes() { return qb.s2View(); }
            @Override public int clear() { return qb.store().clearAll(); }
        });
        S2Engine.Flags on = new S2Engine.Flags(true, true, false, false);
        S2Engine.Result list = en.handle("我记了什么", NOW, on);
        assertTrue(list.reply, list.reply.contains("5-2-1234"));
        assertFalse(list.reply, list.reply.contains("1-1-1111"));
        S2Engine.Result clear = en.handle("清空所有记忆", NOW, on);
        assertNotNull(clear);
        S2Engine.Exec x = en.execute(clear.sign, clear.args, NOW);
        assertTrue(x.ok);
        assertEquals("清空连记一记一起清", 0, qb.list().size());
    }

    @Test public void wiringR10() throws Exception {
        String hooks = src("app/src/com/onesay/assistant/v2/quicknote/QuickNoteHooks.java");
        assertTrue("锁屏不读屏", hooks.indexOf("if (keyguardLocked(app))") < hooks.indexOf("ScreenContextCapture.captureSync(app)"));
        assertTrue("视觉兜底跳过敏感 App", hooks.contains("!QuickNoteSettings.sensitivePkg(sc.pkg)"));
        assertTrue("订单记账", hooks.contains("ledgerAdd(app, e)"));
        assertTrue("码上岛", hooks.contains("ch.addCode("));
        String s2 = src("app/src/com/onesay/assistant/v2/s2/S2Hooks.java");
        assertTrue("S2 让出记一记句式", s2.contains("yieldToQuickNote(ctx, query)"));
        assertTrue("S2 看得到记一记", s2.contains("attachQuickNote("));
        String ma = src("app/src/com/onesay/assistant/v2/ui/MainActivity.java");
        assertTrue("page=quicknote 深链", ma.contains("handlePage(getIntent())") && ma.contains("onNewIntent"));
        String plugin = src("app/src/com/onesay/assistant/v2/kernel/plugins/QuickNotePlugin.java");
        assertTrue(plugin.contains("QuickNoteHooks.takeSuikouji(svc, command)"));
        String sq = src("app/src/com/onesay/assistant/v2/quicknote/SqliteQuickNoteStore.java");
        assertTrue("独立库", sq.contains("\"quicknote.db\""));
        String s2db = src("app/src/com/onesay/assistant/v2/s2/SqliteMemoryStore.java");
        // r2/soul-memory：s2_memory.db 升到 v2 只为加人设 / 长期记忆表（旧 memory / habit 表结构不动）
        assertTrue("S2 库只因 soul 加表升级", s2db.contains("private static final int DB_VERSION = 2;") && s2db.contains("if (oldV < 2) com.onesay.assistant.v2.soul.SoulSchema.create(db);"));
    }
}
