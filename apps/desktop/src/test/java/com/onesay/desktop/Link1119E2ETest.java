package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Test;

/**
 * [overnight-1119] 双端连通四条链路的端到端测试（真 TLS 局域网连接 + 模拟手机）：
 * (a) 手机交任务给电脑、结果推回手机 / 手机能查；(b) 电脑让手机做事（按目标操作，含「手机版本太旧」）；
 * (c) 文件双向互传（分块、校验、乱序 / 坏校验、进度、取消、落盘位置）；(d) 快捷指令双端互通（两边互相看到、互相运行）。
 */
public class Link1119E2ETest {

    @After public void reset() {
        PcGuard.LOCAL.set(Boolean.FALSE);
        PcGuard.TASK.set("");
        SafeExec.REMOTE.set(Boolean.FALSE);
        SafeExec.reset(() -> false, null);
        Progress.CANCEL.set(false);
    }

    /** 电脑端执行器：快捷指令列文件夹、运行回成功（真机是 PowerShell）。 */
    static final class Acts implements Actions {
        final Path dir;
        final List<String> ran = new CopyOnWriteArrayList<>();

        Acts(Path dir) { this.dir = dir; }

        @Override public List<String> capabilities() { return List.of("ping", "shortcut_list", "shortcut_run"); }

        @Override public Result run(String cmd, Map<String, Object> a) throws Exception {
            switch (cmd) {
                case "ping": return Result.ok("在线");
                case "shortcut_list": {
                    List<Object> l = new ArrayList<>();
                    try (var s = Files.list(dir)) { s.forEach(p -> l.add(p.getFileName().toString().replaceAll("\\.ps1$", ""))); }
                    return Result.ok("快捷指令", DesktopActions.map("shortcuts", l));
                }
                case "shortcut_run": ran.add(String.valueOf(a.get("name"))); return Result.ok("正在执行快捷指令");
                default: return Result.fail("不支持的指令：" + cmd);
            }
        }
    }

    static final class Pc implements AutoCloseable {
        final Config cfg;
        final Agent agent;
        final Chat chat;
        final FileXfer xfer = new FileXfer();
        final Acts acts;
        final StringBuilder log = new StringBuilder();

        Pc() throws Exception {
            cfg = LanTest.pc(LanTest.freePortPair());
            cfg.workspaceDir = Files.createTempDirectory("e2ews").toFile().getCanonicalPath();
            Path sc = Files.createTempDirectory("e2esc");
            Files.writeString(sc.resolve("关灯.ps1"), "Write-Output off");
            cfg.scriptsDir = sc.toString();
            acts = new Acts(sc);
            agent = new Agent(cfg, acts, s -> { synchronized (log) { log.append(s).append('\n'); } });
            Store st = new Store(Files.createTempDirectory("e2e").resolve("state.json"));
            chat = new Chat(cfg, st, acts, List::of, x -> { });
            chat.workspace = cfg.workspaceDir;
            chat.pcExec = agent::runLocal;
            // 和 App.setupLinks1119 一样接线
            chat.peerExec = from -> (cmd, args) -> agent.runAsPeer(from, cmd, args);
            chat.xfer = xfer;
            chat.phones = agent::peers;
            chat.phone = (cmd, args) -> call(agent.phoneTarget(""), cmd, args);
            agent.local.put("pc_task", a -> chat.startPhoneTask(String.valueOf(a.getOrDefault("goal", "")), String.valueOf(a.get("_from"))));
            agent.local.put("pc_task_status", a -> chat.phoneTaskStatus(String.valueOf(a.get("_from"))));
            agent.local.put("file_put", a -> {
                Map<String, Object> r = xfer.receive(cfg.workspace().resolve(FileXfer.DIR_FROM_PHONE), a, String.valueOf(a.get("_from")));
                return new Actions.Result(Boolean.TRUE.equals(r.get("ok")), String.valueOf(r.get("text")), r);
            });
            chat.onPhoneTaskEnd = (from, card) -> {
                Map<String, Object> n = new TreeMap<>();
                n.put("title", "电脑任务" + Outcomes.zh(String.valueOf(card.get("state"))));
                n.put("text", Chat.phoneTaskLine(card));
                call(from, "notify", n);
            };
            agent.start();
            Thread.sleep(250);
        }

        Actions.Result call(String to, String cmd, Map<String, Object> a) {
            try {
                return agent.callPeer(to, cmd, a, 2000, PhoneCmds.timeoutMs(cmd)).get(PhoneCmds.timeoutMs(cmd) + 4000, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                return Actions.Result.fail("手机没有回应");
            }
        }

        @Override public void close() { agent.stop(); }
    }

    /** 模拟手机：验签，按指令名交给 handler，回签名结果。 */
    interface Handler { Actions.Result on(String cmd, Map<String, Object> args); }

    static LinkV2Test.Phone phone(Config ph, List<String> seen, Handler h) {
        Protocol.ReplayGuard g = new Protocol.ReplayGuard();
        return (self, f) -> {
            @SuppressWarnings("unchecked") Map<String, Object> body = (Map<String, Object>) f.get("body");
            String id = String.valueOf(f.get("id"));
            Protocol.Check c = Protocol.verify(ph.pairKey, id, String.valueOf(f.get("from")), ph.deviceId, body, System.currentTimeMillis(), g);
            String cmd = String.valueOf(body.get("cmd"));
            @SuppressWarnings("unchecked") Map<String, Object> args = body.get("args") instanceof Map ? (Map<String, Object>) body.get("args") : Map.of();
            seen.add(cmd);
            Actions.Result r = c != Protocol.Check.OK ? Actions.Result.fail("拒绝：" + c) : h.on(cmd, args);
            String rid = Protocol.newId();
            self.send(Protocol.envelope("pc-lan", rid, Protocol.result(ph.pairKey, rid, ph.deviceId, "pc-lan", id, r.ok, r.text, r.data == null ? new TreeMap<>() : r.data, System.currentTimeMillis())));
        };
    }

    static Llm.Turn call(String id, String name, String argsJson) { return new Llm.Turn(null, List.of(new Llm.ToolCall(id, name, argsJson)), Llm.msg("assistant", "")); }

    static Llm.Turn say(String t) { return new Llm.Turn(t, List.of(), Llm.msg("assistant", t)); }

    static Map<String, Object> waitFor(java.util.function.Supplier<Map<String, Object>> s, long ms) throws Exception {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { Map<String, Object> m = s.get(); if (m != null) return m; Thread.sleep(50); }
        return null;
    }

    // ================================================================== (a) 手机 → 电脑：交任务，结果回传

    @Test public void phoneHandsTaskToPcAndGetsResultBack() throws Exception {
        try (Pc pc = new Pc()) {
            Config ph = LinkV2Test.phoneCfg(pc.cfg, "phone-a");
            List<String> seen = new CopyOnWriteArrayList<>();
            List<Map<String, Object>> notes = new CopyOnWriteArrayList<>();
            LinkV2Test.Conn held = new LinkV2Test.Conn(ph, true, phone(ph, seen, (cmd, a) -> { if (cmd.equals("notify")) notes.add(a); return Actions.Result.ok("好"); }));
            assertTrue(held.up);
            AtomicInteger n = new AtomicInteger();
            pc.chat.brain = (m, t) -> n.incrementAndGet() == 1
                    ? call("1", "write_file", Json.stringify(Map.of("description", "写一份笔记", "path", "notes/from-phone.txt", "text", "手机交来的任务")))
                    : say("【完成】\n已经在电脑工作区写好 notes/from-phone.txt。");
            Map<String, Object> r = held.request(ph, "pc_task", new TreeMap<>(Map.of("goal", "在电脑上写一份笔记")));
            assertEquals(String.valueOf(r), Boolean.TRUE, r.get("ok"));
            assertTrue(String.valueOf(r.get("text")).contains("电脑收到了"));
            // 结果推回手机
            Map<String, Object> note = waitFor(() -> notes.isEmpty() ? null : notes.get(0), 8000);
            assertNotNull("结果推回了手机", note);
            assertEquals("电脑任务完成", note.get("title"));
            assertTrue(String.valueOf(note.get("text")).contains("写好"));
            assertTrue("文件真写了", Files.exists(pc.cfg.workspace().resolve("notes/from-phone.txt")));
            // 手机也能查
            Map<String, Object> st = held.request(ph, "pc_task_status", new TreeMap<>());
            assertEquals(Boolean.TRUE, st.get("ok"));
            @SuppressWarnings("unchecked") Map<String, Object> d = (Map<String, Object>) st.get("data");
            assertEquals("done", d.get("state"));
            assertEquals(1L, ((Number) d.get("steps")).longValue());
            // 任务卡标了来自哪台手机
            Map<String, Object> card = null;
            for (Map<String, Object> m : pc.chat.store().list("chat")) if ("task".equals(m.get("via"))) card = m;
            assertEquals("phone-a", card.get("origin"));
            assertTrue(String.valueOf(card.get("title")).startsWith("来自手机："));
            held.lc.stop();
        }
    }

    @Test public void phoneTaskStepsUseThePhonesPermissionsNotLocalOnes() throws Exception {
        try (Pc pc = new Pc()) {
            Config ph = LinkV2Test.phoneCfg(pc.cfg, "phone-a2");
            List<Map<String, Object>> notes = new CopyOnWriteArrayList<>();
            LinkV2Test.Conn held = new LinkV2Test.Conn(ph, true, phone(ph, new CopyOnWriteArrayList<>(), (cmd, a) -> { if (cmd.equals("notify")) notes.add(a); return Actions.Result.ok("好"); }));
            AtomicInteger n = new AtomicInteger();
            List<String> results = new CopyOnWriteArrayList<>();
            pc.chat.brain = (m, t) -> {
                if (!m.isEmpty() && "tool".equals(m.get(m.size() - 1).get("role"))) results.add(String.valueOf(m.get(m.size() - 1).get("content")));
                int k = n.incrementAndGet();
                if (k == 1) return call("1", "android_ui", "{\"description\":\"点模拟器\",\"action\":\"tap_text\",\"text\":\"允许\"}");
                if (k == 2) return call("2", "ask_choice", Json.stringify(Map.of("description", "选", "question", "哪个？", "options", List.of(Map.of("title", "a")))));
                return say("【需要你操作】候选：a、b，请在手机上回话选。");
            };
            held.request(ph, "pc_task", new TreeMap<>(Map.of("goal", "装个东西")));
            assertNotNull(waitFor(() -> notes.isEmpty() ? null : notes.get(0), 8000));
            assertTrue("安卓界面点按不做：" + results, results.get(0).contains("不能替你点安卓界面"));
            assertTrue("选择卡不弹在电脑上", results.get(1).contains("手机看不到"));
            assertEquals("电脑任务需要你操作", notes.get(0).get("title"));
            // 没配大模型：明说
            Chat bare = new Chat(pc.cfg, new Store(Files.createTempDirectory("e2e").resolve("s.json")), pc.acts, List::of, x -> { });
            bare.peerExec = pc.chat.peerExec;
            Actions.Result r = bare.startPhoneTask("做点事", "phone-a2");
            assertFalse(r.ok);
            assertTrue(r.text.contains("没配大模型"));
            held.lc.stop();
        }
    }

    @Test public void notAllowedPhoneCannotRunSteps() throws Exception {
        try (Pc pc = new Pc()) {
            Actions.Result r = pc.agent.runAsPeer("", "file_write", new TreeMap<>(Map.of("path", "a.txt", "text", "x")));
            assertFalse(r.ok);
            assertTrue(r.text.contains("没有被允许"));
            // 按手机来的指令判定（远程链）：工作区里写文件照常放行，日志记成「手机任务执行」
            Actions.Result ok = pc.agent.runAsPeer("phone-x", "file_write", new TreeMap<>(Map.of("path", "a.txt", "text", "x")));
            assertTrue(ok.text, ok.ok);
            assertTrue(pc.log.toString().contains("手机任务执行 file_write（来自 phone-x）"));
        }
    }

    // ================================================================== (b) 电脑 → 手机：按目标操作

    @Test public void pcHandsGoalToPhoneAndExplainsOldPhone() throws Exception {
        try (Pc pc = new Pc()) {
            Config ph = LinkV2Test.phoneCfg(pc.cfg, "phone-b");
            List<String> seen = new CopyOnWriteArrayList<>();
            boolean[] old = {false};
            LinkV2Test.Conn held = new LinkV2Test.Conn(ph, true, phone(ph, seen, (cmd, a) -> {
                if (cmd.equals("operate")) return old[0] ? Actions.Result.fail("手机不支持「operate」") : Actions.Result.ok("已交给手机上的 OneSay 去做", new TreeMap<>(Map.of("goal", a.get("goal"))));
                if (cmd.equals("status")) return Actions.Result.ok("ok", new TreeMap<>(Map.of("battery", 66L)));
                if (cmd.equals("caps")) return Actions.Result.ok("ok", new TreeMap<>(Map.of("caps", List.of("ping", "caps", "status", "operate"), "known", List.of("status", "operate"))));
                return Actions.Result.ok("好");
            }));
            Actions.Result r = pc.chat.phoneTool("phone_operate", Map.of("goal", "打开设置看看安卓版本"));
            assertTrue(r.text, r.ok);
            assertTrue(seen.contains("operate"));
            Actions.Result st = pc.chat.phoneTool("phone_status", Map.of());
            assertTrue(st.text.contains("按目标操作手机"));
            assertFalse("开着就不提示", st.text.contains("开关没开"));
            old[0] = true;
            Actions.Result r2 = pc.chat.phoneTool("phone_operate", Map.of("goal", "打开设置"));
            assertFalse(r2.ok);
            assertTrue(r2.text, r2.text.contains("版本太旧"));
            held.lc.stop();
        }
    }

    // ================================================================== (c) 文件互传

    static byte[] rnd(int n) { byte[] b = new byte[n]; new Random(7).nextBytes(b); return b; }

    static String sha(byte[] b) throws Exception { return FileXfer.hex(MessageDigest.getInstance("SHA-256").digest(b)); }

    @Test public void phoneSendsFileToPcInChunks() throws Exception {
        try (Pc pc = new Pc()) {
            Config ph = LinkV2Test.phoneCfg(pc.cfg, "phone-c");
            LinkV2Test.Conn conn = new LinkV2Test.Conn(ph, false, null);
            byte[] data = rnd(FileXfer.CHUNK * 2 + 12345);
            int total = 3;
            for (int seq = 0; seq < total; seq++) {
                Map<String, Object> a = new TreeMap<>();
                a.put("xfer", "ph1234");
                a.put("name", "照片.jpg");
                a.put("size", (long) data.length);
                a.put("seq", (long) seq);
                a.put("total", (long) total);
                int from = seq * FileXfer.CHUNK, to = Math.min(data.length, from + FileXfer.CHUNK);
                a.put("b64", Base64.getEncoder().encodeToString(java.util.Arrays.copyOfRange(data, from, to)));
                if (seq == total - 1) a.put("sha256", sha(data));
                Map<String, Object> r = conn.request(ph, "file_put", a);
                assertEquals(String.valueOf(r), Boolean.TRUE, r.get("ok"));
                if (seq == 1) {
                    // 同一块重发：幂等
                    Map<String, Object> again = conn.request(ph, "file_put", a);
                    assertEquals(Boolean.TRUE, again.get("ok"));
                }
            }
            Path got = pc.cfg.workspace().resolve(FileXfer.DIR_FROM_PHONE).resolve("照片.jpg");
            assertTrue("落在工作区「来自手机」", Files.exists(got));
            assertArrayEquals(data, Files.readAllBytes(got));
            @SuppressWarnings("unchecked") Map<String, Object> v = (Map<String, Object>) pc.xfer.view().get(0);
            assertEquals("done", v.get("status"));
            assertEquals(100L, v.get("percent"));
            conn.lc.stop();
        }
    }

    @Test public void receiverRejectsGapsAndBadChecksum() throws Exception {
        FileXfer x = new FileXfer();
        Path dir = Files.createTempDirectory("rx");
        byte[] data = rnd(1000);
        Map<String, Object> a = new TreeMap<>(Map.of("xfer", "bad1", "name", "a.bin", "size", 1000L, "seq", 1L, "total", 2L, "b64", Base64.getEncoder().encodeToString(data)));
        assertEquals("缺了开头", Boolean.FALSE, x.receive(dir, a, "p").get("ok"));
        a.put("seq", 0L);
        a.put("total", 1L);
        a.put("sha256", "00");
        Map<String, Object> r = x.receive(dir, a, "p");
        assertEquals(Boolean.FALSE, r.get("ok"));
        assertTrue(String.valueOf(r.get("text")).contains("校验不对"));
        try (var s = Files.list(dir)) { assertEquals("坏的不留", 0, s.count()); }
        // 重名：加 (2)
        Files.write(dir.resolve("b.bin"), new byte[] {1});
        Map<String, Object> ok = x.receive(dir, new TreeMap<>(Map.of("xfer", "good1", "name", "../b.bin", "size", 1000L, "seq", 0L, "total", 1L, "b64", Base64.getEncoder().encodeToString(data), "sha256", sha(data))), "p");
        assertEquals(Boolean.TRUE, ok.get("ok"));
        assertTrue("不能跳出目录、重名加编号", String.valueOf(ok.get("path")).endsWith("b (2).bin"));
        assertEquals(Boolean.FALSE, x.receive(dir, new TreeMap<>(Map.of("xfer", "big1", "name", "x", "size", FileXfer.MAX_SIZE + 1, "seq", 0L, "total", 1L, "b64", "")), "p").get("ok"));
    }

    @Test public void pcSendsFileToPhoneWithProgressAndCancel() throws Exception {
        try (Pc pc = new Pc()) {
            Config ph = LinkV2Test.phoneCfg(pc.cfg, "phone-c2");
            FileXfer phoneSide = new FileXfer();
            Path phoneDir = Files.createTempDirectory("phone-dl");
            LinkV2Test.Conn held = new LinkV2Test.Conn(ph, true, phone(ph, new CopyOnWriteArrayList<>(), (cmd, a) -> {
                if (!cmd.equals("file_put")) return Actions.Result.ok("好");
                Map<String, Object> r = phoneSide.receive(phoneDir, a, "pc");
                return new Actions.Result(Boolean.TRUE.equals(r.get("ok")), String.valueOf(r.get("text")), r);
            }));
            byte[] data = rnd(FileXfer.CHUNK * 3 + 77);
            Path f = Files.createTempDirectory("pcf").resolve("安装包.apk");
            Files.write(f, data);
            List<Map<String, Object>> prog = new CopyOnWriteArrayList<>();
            Progress.SINK.set(prog::add);
            Actions.Result r;
            try {
                r = pc.chat.phoneTool("phone_send_file", Map.of("path", f.toString()));
            } finally {
                Progress.SINK.remove();
            }
            assertTrue(r.text, r.ok);
            assertTrue(r.text.contains("已发到手机"));
            assertArrayEquals(data, Files.readAllBytes(phoneDir.resolve("安装包.apk")));
            assertTrue("有进度", prog.size() >= 2);
            assertEquals(100L, prog.get(prog.size() - 1).get("percent"));
            assertTrue(String.valueOf(prog.get(prog.size() - 1).get("text")).contains("100%"));
            // 取消
            Progress.CANCEL.set(false);
            AtomicInteger chunks = new AtomicInteger();
            Actions.Result c = pc.xfer.send(f.toFile(), "phone-c2", (cmd, a) -> { if (chunks.incrementAndGet() == 2) Progress.CANCEL.set(true); return pc.call("phone-c2", cmd, a); });
            assertFalse(c.ok);
            assertTrue(c.text.contains("已取消"));
            // 手机不在：如实说
            held.lc.stop();
            Thread.sleep(300);
            Actions.Result off = pc.xfer.send(f.toFile(), "phone-c2", (cmd, a) -> pc.call("phone-c2", cmd, a));
            assertFalse(off.ok);
            assertTrue(off.text, off.text.contains("失败"));
        }
    }

    // ================================================================== (d) 快捷指令双端互通

    @Test public void shortcutsVisibleAndRunnableBothWays() throws Exception {
        try (Pc pc = new Pc()) {
            Approvals1118Test.Card card = new Approvals1118Test.Card();
            card.answer = PcGuard.ALWAYS;
            pc.agent.guard.confirmer = card;
            Config ph = LinkV2Test.phoneCfg(pc.cfg, "phone-d");
            List<String> seen = new CopyOnWriteArrayList<>();
            List<String> phoneRan = new CopyOnWriteArrayList<>();
            LinkV2Test.Conn held = new LinkV2Test.Conn(ph, true, phone(ph, seen, (cmd, a) -> {
                if (cmd.equals("shortcut_list")) return Actions.Result.ok("手机快捷指令", new TreeMap<>(Map.of("shortcuts", List.of(Map.of("name", "回家模式", "steps", "3 步")))));
                if (cmd.equals("shortcut_run")) { phoneRan.add(String.valueOf(a.get("name"))); return Actions.Result.ok("已在手机上运行「" + a.get("name") + "」"); }
                return Actions.Result.ok("好");
            }));
            // 手机看电脑的
            Map<String, Object> l = held.request(ph, "shortcut_list", new TreeMap<>());
            assertEquals(String.valueOf(l), Boolean.TRUE, l.get("ok"));
            assertTrue(String.valueOf(l.get("data")).contains("关灯"));
            // 手机跑电脑的（第一次在电脑上点「以后直接执行」）
            Map<String, Object> run = held.request(ph, "shortcut_run", new TreeMap<>(Map.of("name", "关灯")));
            assertEquals(String.valueOf(run), Boolean.TRUE, run.get("ok"));
            assertEquals(List.of("关灯"), pc.acts.ran);
            // 电脑看手机的、跑手机的
            Actions.Result pl = pc.chat.phoneTool("phone_shortcuts", Map.of());
            assertTrue(pl.text, pl.ok);
            assertTrue(String.valueOf(pl.data).contains("回家模式"));
            Actions.Result pr = pc.chat.phoneTool("phone_run_shortcut", Map.of("name", "回家模式"));
            assertTrue(pr.text, pr.ok);
            assertEquals(List.of("回家模式"), phoneRan);
            // 跨端动作：电脑对话里一句话 → 模型先列手机快捷指令再运行
            AtomicInteger n = new AtomicInteger();
            pc.chat.pcExec = pc.agent::runLocal;
            pc.chat.brain = (m, t) -> {
                int k = n.incrementAndGet();
                if (k == 1) return call("1", "phone_shortcuts", "{\"description\":\"看手机快捷指令\"}");
                if (k == 2) return call("2", "phone_run_shortcut", "{\"description\":\"运行回家模式\",\"name\":\"回家模式\"}");
                return say("【完成】\n手机上的「回家模式」已经运行。");
            };
            Map<String, Object> tc = pc.chat.send("在手机上跑一下回家模式", "text");
            assertEquals("done", tc.get("state"));
            assertEquals(List.of("回家模式", "回家模式"), phoneRan);
            held.lc.stop();
        }
    }
}
