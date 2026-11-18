package com.onesay.desktop;

import static org.junit.Assert.*;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

/**
 * [phone-tools-1118] 对话里的手机工具（走已配对的手机联动）；后面几批（截屏 / 看图、安卓界面、按任务分段的上下文）的测试也放这里。
 */
public class DevTools1118Test {

    static Llm.Turn call(String id, String name, String argsJson) {
        return new Llm.Turn(null, List.of(new Llm.ToolCall(id, name, argsJson)), Llm.msg("assistant", ""));
    }

    static Llm.Turn say(String text) { return new Llm.Turn(text, List.of(), Llm.msg("assistant", text)); }

    static Chat chat() throws Exception {
        Store s = new Store(Files.createTempDirectory("dt").resolve("state.json"));
        Config c = new Config(Files.createTempFile("dt", ".properties"));
        return new Chat(c, s, new Main.TestActions(), List::of, x -> { });
    }

    // ------------------------------------------------------------------ 手机工具

    @Test public void phoneToolsAreRegisteredAndMapped() {
        assertTrue(ChatAgent.toolNames().containsAll(List.of("phone_status", "phone_open_app", "phone_operate")));
        assertEquals("phone_operate", ChatAgent.map(new Llm.ToolCall("1", "phone_operate", "{\"description\":\"x\",\"goal\":\"打开设置看系统版本\"}"))[0]);
        assertNull("目标为空不收", ChatAgent.map(new Llm.ToolCall("1", "phone_operate", "{\"goal\":\" \"}")));
        assertEquals("phone_open_app", ChatAgent.map(new Llm.ToolCall("1", "phone_open_app", "{\"name\":\"微信\"}"))[0]);
        assertEquals("phone_status", ChatAgent.map(new Llm.ToolCall("1", "phone_status", "{}"))[0]);
        assertTrue(ChatAgent.badArgs(new Llm.ToolCall("1", "phone_operate", "{}")).contains("goal"));
        assertTrue(ChatAgent.badArgs(new Llm.ToolCall("1", "nope", "{}")).contains("phone_operate"));
        assertNull(PhoneCmds.check("operate", Map.of("goal", "打开设置")));
        assertNotNull(PhoneCmds.check("operate", Map.of()));
        assertEquals(LinkPolicy.Cat.APP_OPERATE, LinkPolicy.phoneCat("operate"));
    }

    @Test public void promptSaysItCanControlPhonesAndListsConnectedOnes() throws Exception {
        Chat ch = chat();
        ch.pcExec = (c, a) -> Actions.Result.ok("ok");
        ch.phones = () -> new TreeMap<>(Map.of("meizu21", "局域网·加密"));
        String p = ch.systemPrompt();
        assertTrue(p.contains("不要说你没法控制手机"));
        assertTrue(p.contains("meizu21（局域网·加密）"));
        ch.phones = TreeMap::new;
        p = ch.systemPrompt();
        assertTrue(p.contains("现在没有手机连着这台电脑"));
        assertTrue("没连着就写怎么连", p.contains("扫码配对"));
    }

    @Test public void phoneToolsGoOverTheLinkAndExplainHowToConnect() throws Exception {
        Chat ch = chat();
        List<String> sent = new ArrayList<>();
        ch.phone = (cmd, a) -> {
            sent.add(cmd + a);
            if (cmd.equals("status")) return Actions.Result.ok("ok", new TreeMap<>(Map.of("battery", 80L, "screen_on", true)));
            if (cmd.equals("caps")) return Actions.Result.ok("ok", new TreeMap<>(Map.of("caps", List.of("ping", "status", "open_app"))));
            return Actions.Result.ok("已交给手机上的 OneSay 去做");
        };
        // 没连着：说怎么连，不往手机发
        ch.phones = TreeMap::new;
        Actions.Result r = ch.phoneTool("phone_operate", Map.of("goal", "打开设置"));
        assertFalse(r.ok);
        assertTrue(r.text.contains("扫码配对"));
        assertTrue(sent.isEmpty());
        // 连着
        ch.phones = () -> new TreeMap<>(Map.of("p1", "局域网"));
        r = ch.phoneTool("phone_status", Map.of());
        assertTrue(r.ok);
        assertTrue(r.text.contains("电量 80%"));
        assertTrue("没开按目标操作要说", r.text.contains("按目标操作手机"));
        r = ch.phoneTool("phone_operate", Map.of("goal", "打开设置查系统版本"));
        assertTrue(r.ok);
        assertTrue(sent.get(sent.size() - 1).startsWith("operate{goal=打开设置查系统版本}"));
        // 只读预设：手机动作不做，状态照查
        ch.phoneTool("phone_status", Map.of());
        Config c = new Config(Files.createTempFile("dt", ".properties"));
        c.chatPreset = "read_only";
        Chat ro = new Chat(c, new Store(Files.createTempDirectory("dt").resolve("state.json")), new Main.TestActions(), List::of, x -> { });
        ro.phone = ch.phone;
        ro.phones = ch.phones;
        assertFalse(ro.phoneTool("phone_open_app", Map.of("name", "微信")).ok);
        assertTrue(ro.phoneTool("phone_status", Map.of()).ok);
        // 手机不在线：原因 + 怎么连
        ch.phone = (cmd, a) -> Actions.Result.fail("手机没有回应");
        r = ch.phoneTool("phone_open_app", Map.of("name", "微信"));
        assertTrue(r.text.contains("手机没有回应") && r.text.contains("接受电脑指令"));
    }

    @Test public void phoneOperateRunsInsideATaskWithoutGoingThroughPcExec() throws Exception {
        Chat ch = chat();
        AtomicInteger pc = new AtomicInteger();
        ch.pcExec = (c, a) -> { pc.incrementAndGet(); return Actions.Result.ok("ok"); };
        ch.phones = () -> new TreeMap<>(Map.of("p1", "局域网"));
        List<String> sent = new ArrayList<>();
        ch.phone = (cmd, a) -> { sent.add(cmd); return Actions.Result.ok("已交给手机上的 OneSay 去做"); };
        AtomicInteger n = new AtomicInteger();
        ch.brain = (m, t) -> n.incrementAndGet() == 1 ? call("1", "phone_operate", "{\"description\":\"让手机打开设置\",\"goal\":\"打开设置\"}") : say("交给手机了");
        Map<String, Object> card = ch.send("帮我把手机上最近一次系统更新的说明找出来，告诉我改了什么", "text");
        assertEquals("done", card.get("state"));
        assertEquals(List.of("operate"), sent);
        assertEquals("手机工具不走电脑执行链", 0, pc.get());
    }
}
