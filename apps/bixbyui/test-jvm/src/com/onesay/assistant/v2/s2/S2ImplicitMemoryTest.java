package com.onesay.assistant.v2.s2;

import static org.junit.Assert.*;

import org.junit.Before;
import org.junit.Test;

import java.util.Calendar;
import java.util.TimeZone;

/**
 * [s2] round3 #2：免前缀记忆（{@link ImplicitNote} + S2Engine 接线）回归集。
 * 正例必须记下（NOTE_ADD，回执「记下了：…」），负例（指令 / 问句 / 知识陈述 / 诊断句）必须放行；
 * 敏感陈述拒存；回忆不带「我/上次」也能答；新增前缀动词；开关关闭回到 round3 之前；撤销 = 删掉刚记的那条。
 */
public class S2ImplicitMemoryTest {

    static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");
    static final S2Engine.Flags ON = new S2Engine.Flags(true, true, false, true);
    static final S2Engine.Flags IMPLICIT_OFF = new S2Engine.Flags(true, true, false, false);

    static long t(int day, int h, int m) {
        Calendar c = Calendar.getInstance(TZ);
        c.clear();
        c.set(2026, 9, day, h, m, 0);
        return c.getTimeInMillis();
    }

    /** 用户点名的正例 + 扩展。 */
    static final String[][] POSITIVE = {
        // 原句, 期望主体, 期望值
        {"我车停在B2-17", "车", "B2-17"},
        {"车停B2-17了", "车", "B2-17"},
        {"护照放在书房第二个抽屉", "护照", "书房第二个抽屉"},
        {"我把钥匙放鞋柜上了", "钥匙", "鞋柜上"},
        {"停在B2-17", "车", "B2-17"},
        {"车位是B2-17", "车位", "B2-17"},
        {"我的车牌是粤B12345", "车牌", "粤B12345"},
        {"钥匙在鞋柜上", "钥匙", "鞋柜上"},
        {"门禁卡在包里", "门禁卡", "包里"},
        {"充电宝塞抽屉里了", "充电宝", "抽屉里"},
        {"钥匙放在茶几上", "钥匙", "茶几上"},
        {"存折放在抽屉", "存折", "抽屉"},
    };

    /** 用户点名的负例 + 扩展：不能被记下。 */
    static final String[] NEGATIVE = {
        "北京在华北", "把音量调到5", "打开高德", "导航去B2", "我在家",
        "我车停哪了", "把文件放到桌面", "放首歌", "停一下", "锁屏",
        "手机放兜里老是自己亮", "下午在会议室开会", "wifi在哪设置", "北京是中国的首都",
        "把车停到车库然后导航回家", "我把亮度调到一半", "帮我把音乐放大声点", "这个放在哪里",
    };

    InMemoryMemoryStore st;
    S2Engine en;

    @Before public void setUp() {
        st = new InMemoryMemoryStore();
        en = new S2Engine(st, st, TZ);
    }

    S2Engine.Exec exec(S2Engine.Result r, long now) {
        S2Engine.Exec x = en.execute(r.sign, r.args, now);
        assertTrue(x.message, x.ok);
        return x;
    }

    @Test public void positivesAreStored() {
        for (String[] c : POSITIVE) {
            setUp();
            S2Engine.Result r = en.handle(c[0], t(8, 20, 0), ON);
            assertNotNull(c[0], r);
            assertEquals(c[0], S2Engine.Type.NOTE_ADD, r.type);
            assertEquals(c[0], c[1], r.args.optString("subject"));
            assertEquals(c[0], c[2], r.args.optString("value"));
            assertEquals(c[0], "1", r.args.optString("implicit"));
            S2Engine.Exec x = exec(r, t(8, 20, 0));
            assertTrue(c[0] + " → " + x.message, x.message.startsWith("记下了："));
            assertTrue("执行回执带新条目 id（撤销芯片用）", x.output.matches("id=\\d+"));
        }
    }

    @Test public void negativesAreNotHijacked() {
        for (String q : NEGATIVE) {
            S2Engine.Result r = en.handle(q, t(8, 20, 0), ON);
            boolean stored = r != null && (r.type == S2Engine.Type.NOTE_ADD || r.type == S2Engine.Type.NOTE_REFUSED);
            assertFalse(q + " 被记成了笔记: " + r, stored);
            assertNull(q, ImplicitNote.detect(q));
        }
    }

    @Test public void sensitiveStatementsAreRefusedWithReason() {
        for (String q : new String[]{"wifi密码是12345678", "我家wifi密码是abcdefgh", "我的银行卡号是6222020200112233445"}) {
            S2Engine.Result r = en.handle(q, t(8, 20, 0), ON);
            assertNotNull(q, r);
            assertEquals(q, S2Engine.Type.NOTE_REFUSED, r.type);
            assertTrue(r.reply, r.reply.contains("不记"));
            assertEquals("拒存不落库", 0, st.count(MemoryEntry.KIND_NOTE));
        }
    }

    @Test public void recallWorksWithoutWoOrShangci() {
        exec(en.handle("车停B2-17了", t(7, 20, 0), ON), t(7, 20, 0));
        exec(en.handle("护照放在书房第二个抽屉", t(7, 20, 1), ON), t(7, 20, 1));
        S2Engine.Result r = en.handle("车停哪了", t(8, 9, 0), ON);
        assertEquals(S2Engine.Type.RECALL, r.type);
        assertTrue(r.reply, r.reply.contains("B2-17") && r.reply.contains("昨天"));
        r = en.handle("护照在哪", t(8, 9, 0), ON);
        assertEquals(S2Engine.Type.RECALL, r.type);
        assertTrue(r.reply, r.reply.contains("书房第二个抽屉"));
        // 库里没有的弱问句照旧放行（「钥匙在哪」没记过 → 不接管）
        assertNull(en.handle("钥匙在哪", t(8, 9, 0), ON));
        // 新值覆盖旧值（以最新为准，保留历史）
        exec(en.handle("我车停在B1-05", t(8, 10, 0), ON), t(8, 10, 0));
        r = en.handle("车停哪了", t(8, 11, 0), ON);
        assertTrue(r.reply, r.reply.split("\n")[0].contains("B1-05"));
        assertTrue(r.reply, r.reply.contains("更早还记过：B2-17"));
    }

    @Test public void moreExplicitVerbs() {
        String[][] cases = {
            {"别忘了护照放在书房", "护照放在书房"},
            {"帮我记住车停在B3", "车停在B3"},
            {"存一下车位B2-17", "车位B2-17"},
            {"记着雨伞在门后", "雨伞在门后"},
        };
        for (String[] c : cases) {
            S2Engine.Result r = en.handle(c[0], t(8, 20, 0), ON);
            assertNotNull(c[0], r);
            assertEquals(c[0], S2Engine.Type.NOTE_ADD, r.type);
            assertEquals(c[0], c[1], r.args.optString("content"));
            assertEquals("显式前缀不标 implicit", "", r.args.optString("implicit"));
        }
        // 显式前缀 + 屏幕指代仍让给识屏
        assertNull(en.handle("存一下这个号码", t(8, 20, 0), ON));
    }

    @Test public void switchOffRestoresOldBehaviour() {
        assertNull(en.handle("我车停在B2-17", t(8, 20, 0), IMPLICIT_OFF));
        assertNull("记忆库关 = 免前缀也关", en.handle("我车停在B2-17", t(8, 20, 0), new S2Engine.Flags(false, true, false, true)));
        assertFalse(new S2Engine.Flags(false, true, false, true).implicit);
        assertTrue("三参构造跟随 memory（旧调用方默认开）", new S2Engine.Flags(true, true, false).implicit);
        // 前缀写入不受免前缀开关影响
        assertEquals(S2Engine.Type.NOTE_ADD, en.handle("记一下车停在B2-17", t(8, 20, 0), IMPLICIT_OFF).type);
    }

    @Test public void undoDeletesTheJustSavedEntry() {
        S2Engine.Exec x = exec(en.handle("我把钥匙放鞋柜上了", t(8, 20, 0), ON), t(8, 20, 0));
        String id = x.output.substring(3);
        assertEquals(1, st.count(MemoryEntry.KIND_NOTE));
        org.json.JSONObject a = new org.json.JSONObject();
        try {
            a.put("ids", id);
            a.put("preview", "刚才记的「我把钥匙放鞋柜上了」");
        } catch (Exception e) {
            fail();
        }
        S2Engine.Exec d = en.execute(S2Capabilities.SIGN_NOTE_DELETE, a, t(8, 20, 1));
        assertTrue(d.ok && d.verified);
        assertEquals(0, st.count(MemoryEntry.KIND_NOTE));
        assertNull("撤销后回忆不到", en.handle("钥匙在哪", t(8, 20, 2), ON));
    }

    /** 宿主接线守卫：撤销芯片挂在执行成功分支；设置项 + kill-switch 存在；分诊带开关。 */
    @Test public void hostWiring() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String fss = read(root + "/app/src/com/onesay/assistant/v2/ui/FloatingSheetService.java");
        assertTrue(fss.contains(com.onesay.assistant.v2.kernel.KernelWiring.K + ".onExecuted(FloatingSheetService.this, cap, args, result)")
                && com.onesay.assistant.v2.kernel.KernelWiring.plugin("MemoryPlugin").contains("S2Hooks.onNoteAdded(svc, cap, args, r)"));
        String set = read(root + "/app/src/com/onesay/assistant/v2/s2/S2Settings.java");
        assertTrue(set.contains("KEY_IMPLICIT = \"memory.implicit\"") && set.contains("KILL_IMPLICIT = \"memory.implicit.off\""));
        String ur = read(root + "/app/src/com/onesay/assistant/v2/router/UnifiedRouter.java");
        assertTrue(com.onesay.assistant.v2.kernel.KernelWiring.plugin("MemoryPlugin").contains("S2Settings.implicitEnabled(ctx)") && ur.contains("diagOn, implicitOn")
                && ur.contains("boolean implicitOn = sw.implicit;"));   // [imgsave] 之后是 diagOn, implicitOn, imgOn)
        String hooks = read(root + "/app/src/com/onesay/assistant/v2/s2/S2Hooks.java");
        assertTrue(hooks.contains("SIGN_NOTE_DELETE") && hooks.contains("labels.add(\"撤销\")"));
    }

    static String read(String p) throws Exception {
        return new String(java.nio.file.Files.readAllBytes(new java.io.File(p).toPath()), "UTF-8");
    }
}
