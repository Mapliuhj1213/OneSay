package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.executor.ExecutionGate;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.backend.BackendOutcome;
import com.onesay.assistant.v2.executor.backend.ExecRequest;
import com.onesay.assistant.v2.executor.backend.SysActionBackend;
import com.onesay.assistant.v2.router.longctx.ArchiveClear;
import com.onesay.assistant.v2.router.longctx.LongCtx;
import com.onesay.assistant.v2.router.longctx.TurnArchive;
import com.onesay.assistant.v2.s2.InMemoryMemoryStore;
import com.onesay.assistant.v2.s2.S2Capabilities;
import com.onesay.assistant.v2.s2.S2Engine;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

/**
 * ★ 2026-11-23 用户定：聊天存档（每轮原话 + 完整答复）是单纯的聊天记录，和长期记忆分开。
 * 清空只删存档不删记忆、确认框点取消不删、30 天边界（启动 / 写入时清）、500 条上限照旧、语音入口走红色确认卡。
 */
public class ArchiveClearTest {

    static final long DAY = 24L * 60 * 60 * 1000;
    final long[] wall = {1795000000000L};
    final long[] mono = {1000L};
    File dir;

    @Before public void setUp() throws Exception {
        LongCtx.setEnabled(true);
        dir = Files.createTempDirectory("arcclr").toFile();
    }

    @After public void tearDown() {
        LongCtx.setEnabled(true);
        ConversationMemory.getInstance().archive().setFile(null);
        ConversationMemory.getInstance().archive().setWallClock(null);
    }

    TurnArchive.WallClock wc() {
        return new TurnArchive.WallClock() {
            @Override public long now() {
                return wall[0];
            }
        };
    }

    ConversationMemory mem() {
        ConversationMemory m = ConversationMemory.newForTest(new ConversationMemory.Clock() {
            @Override public long now() {
                return mono[0];
            }
        });
        m.archive().setWallClock(wc());
        return m;
    }

    void turn(ConversationMemory m, String q, String a) {
        mono[0] += 1000;
        m.beginTurn(q);
        m.record(q, a);
    }

    static S2Engine.Flags MEM = new S2Engine.Flags(true, true, false);

    // ---------------------------------------------------------------- 清空只删存档，不删长期记忆
    @Test public void wipeOnlyArchiveNotMemory() throws Exception {
        InMemoryMemoryStore st = new InMemoryMemoryStore();
        S2Engine en = new S2Engine(st, st, TimeZone.getTimeZone("Asia/Shanghai"));
        long now = 1795000000000L;
        S2Engine.Result w = en.handle("记一下车停在 B2-17", now, MEM);
        assertEquals(S2Capabilities.SIGN_NOTE_ADD, w.sign);
        assertTrue(en.execute(w.sign, w.args, now).ok);
        int notesBefore = st.count(null);
        assertTrue("长期记忆里有一条", notesBefore >= 1);

        ConversationMemory m = mem();
        File f = new File(dir, "conv_archive.jsonl");
        m.setArchiveFile(f);
        turn(m, "我的航班是 CA4502，早上八点二十起飞", "好的，记下了");
        turn(m, "天空为什么是蓝色的", "瑞利散射");
        assertEquals(2, m.archive().size());
        assertTrue(f.exists());
        int facts = m.facts().size(), ledger = m.ledger().size(), turns = m.size();

        String said = ArchiveClear.run(m.archive());
        assertEquals("存档删光", 0, m.archive().size());
        assertFalse("落盘文件删掉", f.exists());
        assertTrue(said, said.contains("清空了") && said.contains("长期记忆没动"));
        assertEquals("长期记忆一条没少", notesBefore, st.count(null));
        S2Engine.Result r = en.handle("我车停哪了", now + 60000, MEM);
        assertNotNull(r);
        assertTrue("长期记忆照样能答：" + r.reply, r.reply != null && r.reply.contains("B2-17"));
        assertEquals("本段会话的事实不动", facts, m.facts().size());
        assertEquals("账本不动", ledger, m.ledger().size());
        assertEquals("逐字历史不动", turns, m.size());
        assertTrue("再清一次：本来就是空的", ArchiveClear.run(m.archive()).contains("本来就是空的"));
        // 清空后照常继续写
        turn(m, "那晚霞为什么是红的", "傍晚光走的路长");
        assertEquals(1, m.archive().size());
        assertTrue(f.exists());

        // 语音说「清空聊天存档」不能被记忆库的「清空记忆」接走
        S2Engine.Result s2 = en.handle("清空聊天存档", now, MEM);
        assertTrue("不走记忆库清空：" + s2, s2 == null || !S2Capabilities.SIGN_CLEAR.equals(s2.sign));
        assertEquals(notesBefore, st.count(null));
    }

    // ---------------------------------------------------------------- 设置按钮：确认框，取消不删
    @Test public void settingsConfirmCancelKeeps() {
        ConversationMemory m = mem();
        turn(m, "天空为什么是蓝色的", "瑞利散射");
        turn(m, "那晚霞呢", "光走的路长");
        final String[] asked = new String[3];
        final String[] toast = {null};
        ArchiveClear.Done d = new ArchiveClear.Done() {
            @Override public void done(String text) {
                toast[0] = text;
            }
        };
        // 点取消：确认框弹了，但 onYes 没调
        ArchiveClear.request(new ArchiveClear.Asker() {
            @Override public void ask(String title, String body, String ok, Runnable onYes) {
                asked[0] = title;
                asked[1] = body;
                asked[2] = ok;
            }
        }, m.archive(), d);
        assertEquals("先弹确认框", ArchiveClear.CONFIRM_TITLE, asked[0]);
        assertTrue("说清只删聊天记录、记忆不动", asked[1].contains("聊天记录") && asked[1].contains("长期记忆不动"));
        assertEquals("取消 → 一条没删", 2, m.archive().size());
        assertNull("取消 → 不弹结果", toast[0]);
        // 点确认
        ArchiveClear.request(new ArchiveClear.Asker() {
            @Override public void ask(String title, String body, String ok, Runnable onYes) {
                onYes.run();
            }
        }, m.archive(), d);
        assertEquals(0, m.archive().size());
        assertNotNull(toast[0]);
        // 用户可见文字：大白话，不出现啥 / 咋 / 呗
        for (String s : new String[]{ArchiveClear.ROW_TITLE, ArchiveClear.ROW_SUB, ArchiveClear.CONFIRM_TITLE, ArchiveClear.CONFIRM_BODY, toast[0],
                ArchiveClear.run(m.archive())}) {
            assertFalse(s, s.matches(".*[啥咋呗].*"));
        }
    }

    // ---------------------------------------------------------------- 语音：快路径命中 → 红色确认卡 → 确认后才删
    @Test public void voiceGoesThroughRedConfirm() throws Exception {
        String[] yes = {"清空聊天存档", "清空对话存档", "删掉聊天存档", "把聊天存档清空", "帮我清空聊天存档", "清空聊天存档。", "聊天存档全删了"};
        for (String q : yes) {
            FastIntents.Hit h = FastIntents.match(q);
            assertNotNull(q, h);
            assertEquals(q, ArchiveClear.SIGN, h.sign);
        }
        String[] no = {"清空聊天记录", "清空微信聊天记录", "清空记忆", "什么是聊天存档", "聊天存档在哪"};
        for (String q : no) {
            FastIntents.Hit h = FastIntents.match(q);
            assertTrue(q + " → " + (h == null ? null : h.sign), h == null || !ArchiveClear.SIGN.equals(h.sign));
        }
        Capability cap = CapabilityRegistry.getInstance().describe(ArchiveClear.SIGN);
        assertNotNull(cap);
        assertEquals("custom", cap.executorType);
        ExecutionGate.Decision d = ExecutionGate.evaluate(cap, new JSONObject(), false);
        assertTrue("要确认", d.needsConfirm());
        assertEquals("红卡", ConsentPolicy.TIER_RED, d.tier);

        // 确认之前：存档原样（门禁拦着，执行层根本没被调）
        TurnArchive ar = ConversationMemory.getInstance().archive();
        ar.setWallClock(wc());
        ar.wipe();
        ar.add("v1", 1, "天空为什么是蓝色的", "瑞利散射", false, 1);
        assertEquals(1, ar.size());
        // 确认之后：执行层删
        SysActionBackend b = new SysActionBackend();
        ExecRequest req = new ExecRequest(cap, new JSONObject(), null, null, null, null, null, null);
        assertTrue(b.accepts(cap, req));
        BackendOutcome o = b.run(req);
        ExecutionResult r = o.terminal;
        assertTrue(r.ok);
        assertTrue(r.userMessage, r.userMessage.contains("长期记忆没动"));
        assertEquals(0, ar.size());
    }

    // ---------------------------------------------------------------- 30 天边界
    @Test public void thirtyDayBoundaryOnWriteAndStartup() throws Exception {
        File f = new File(dir, "conv_archive.jsonl");
        TurnArchive a = new TurnArchive();
        a.setWallClock(wc());
        a.setFile(f);
        long t0 = wall[0];
        a.add("c1", 1, "第一条", "答一", false, 1);                       // t0
        wall[0] = t0 + 1000;
        a.add("c1", 2, "第二条", "答二", false, 2);                       // t0 + 1s
        // 正好 30 天：不删（早于「现在 − 30 天」才删）
        wall[0] = t0 + TurnArchive.MAX_AGE_MS;
        a.add("c1", 3, "第三条", "答三", false, 3);
        assertEquals("刚好 30 天的还在", 3, a.size());
        assertEquals(3, lines(f).size());
        // 再过 1 毫秒：第一条超了 → 写入时清掉（内存 + 文件），第二条还差 999ms
        wall[0] = t0 + TurnArchive.MAX_AGE_MS + 1;
        a.add("c1", 4, "第四条", "答四", false, 4);
        assertEquals(3, a.size());
        assertNull("内存里第一条没了", a.get("c1", 1));
        assertNotNull(a.get("c1", 2));
        List<String> ls = lines(f);
        assertEquals(3, ls.size());
        assertFalse("文件里第一条没了", String.join("\n", ls).contains("第一条"));
        assertTrue(String.join("\n", ls).contains("第二条"));

        // 启动时清：换一个进程（新对象）、时间过去 40 天 → 文件里 ≤ 30 天的才留
        wall[0] = t0 + 40 * DAY;
        a.add("c1", 5, "第五条", "答五", false, 5);   // 写入时也清：30 天线 = t0+10天，第二条（t0+1s）过线，第三、四条（t0+30天）还在
        assertEquals(3, a.size());
        assertNull(a.get("c1", 2));
        assertEquals(3, lines(f).size());
        assertFalse(String.join("\n", lines(f)).contains("第二条"));
        // 手写一份旧文件：一条 31 天前、一条 29 天前、一条没有时间的旧格式 → 启动后留后两条
        Files.write(f.toPath(), ("{\"chain\":\"x\",\"id\":1,\"at\":1,\"t\":" + (wall[0] - 31 * DAY) + ",\"q\":\"老的\",\"a\":\"老\"}\n"
                + "{\"chain\":\"x\",\"id\":2,\"at\":2,\"t\":" + (wall[0] - 29 * DAY) + ",\"q\":\"近的\",\"a\":\"近\"}\n"
                + "{\"chain\":\"x\",\"id\":3,\"at\":3,\"q\":\"没时间\",\"a\":\"旧格式\"}\n"
                + "这行是坏的\n").getBytes("UTF-8"));
        TurnArchive b = new TurnArchive();
        b.setWallClock(wc());
        b.setFile(f);
        String disk = String.join("\n", lines(f));
        assertFalse("启动时 31 天前的删掉", disk.contains("老的"));
        assertTrue("29 天的留着", disk.contains("近的"));
        assertTrue("没时间的旧格式先留着", disk.contains("没时间"));
        assertFalse("坏行清掉", disk.contains("这行是坏的"));
        assertEquals(2, b.fileLines());
        // 没有要删的，启动时不重写文件
        long mod = f.lastModified();
        byte[] before = Files.readAllBytes(f.toPath());
        TurnArchive c2 = new TurnArchive();
        c2.setWallClock(wc());
        c2.setFile(f);
        assertArrayEquals(before, Files.readAllBytes(f.toPath()));
        assertEquals(mod, f.lastModified());
        // 写入时：29 天那条在 1 天多后过线 → 下一次写入清掉
        wall[0] += 1 * DAY + 1;
        c2.add("y", 1, "新的", "新", false, 9);
        disk = String.join("\n", lines(f));
        assertFalse("过了 30 天线，写入时清掉", disk.contains("近的"));
        assertTrue(disk.contains("没时间") && disk.contains("新的"));
    }

    // ---------------------------------------------------------------- 500 条上限照旧
    @Test public void capStillHolds() throws Exception {
        File f = new File(dir, "conv_archive.jsonl");
        TurnArchive a = new TurnArchive();
        a.setWallClock(wc());
        a.setFile(f);
        int n = TurnArchive.MAX_ENTRIES * 2 + 37;
        for (int i = 0; i < n; i++) {
            wall[0] += 1000;
            a.add("c", i + 1, "问题" + i, "答" + i, false, i);
        }
        assertEquals("内存最多 500 条", TurnArchive.MAX_ENTRIES, a.size());
        assertNotNull("留的是最新的", a.get("c", n));
        assertNull(a.get("c", n - TurnArchive.MAX_ENTRIES));
        int ls = lines(f).size();
        assertTrue("落盘也有上限：" + ls, ls <= TurnArchive.MAX_ENTRIES * 2);
        // 重启：启动清理把文件压到最近 500 条
        TurnArchive b = new TurnArchive();
        b.setWallClock(wc());
        b.setFile(f);
        List<String> after = lines(f);
        assertEquals("重启后文件最多 500 条", TurnArchive.MAX_ENTRIES, after.size());
        assertTrue("留的是最新的", after.get(after.size() - 1).contains("问题" + (n - 1)));
        // 上一个进程的条目不会因为新进程重写而丢（旧做法按内存重写会丢）
        for (int i = 0; i < 10; i++) {
            wall[0] += 1000;
            b.add("d", i + 1, "新进程" + i, "答", false, i);
        }
        assertTrue(String.join("\n", lines(f)).contains("问题" + (n - 1)));
    }

    static List<String> lines(File f) throws Exception {
        return Files.readAllLines(f.toPath(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
