package com.onesay.desktop;

import org.junit.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/** 0.4：本机数据、对话上下文、记忆同步、通知、界面服务的防护。 */
public class AppTest {
    static Path tmp() throws Exception { return Files.createTempDirectory("onesay-app"); }

    @Test public void storePersistsAndMergesMemoryNewestWins() throws Exception {
        Path d = tmp();
        Store s = new Store(d.resolve("state.json"));
        s.add("todos", Map.of("title", "交电费", "done", false), 0);
        s.setMemory("A", 100);
        assertEquals("A", s.mergeMemory("old", 50).get("text"));
        assertEquals("B", s.mergeMemory("B", 200).get("text"));
        Store again = new Store(d.resolve("state.json"));
        assertEquals(1, again.list("todos").size());
        assertEquals("B", again.memory().get("text"));
    }

    @Test public void storeCapsList() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        for (int i = 0; i < 5; i++) s.add("notes", Map.of("title", "n" + i), 3);
        assertEquals(3, s.list("notes").size());
        assertEquals("n2", s.list("notes").get(0).get("title"));
    }

    static Chat chat(Store s, Config c) {
        return new Chat(c, s, new Main.TestActions(), List::of, x -> { });
    }

    @Test public void contextSkipsCommandsAndTruncates() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        Config c = new Config(tmp().resolve("c.properties"));
        s.add("chat", Map.of("role", "user", "text", "x".repeat(Chat.CONTEXT_CHARS - 10)), 0);
        s.add("chat", Map.of("role", "assistant", "text", "old"), 0);
        s.add("chat", Map.of("role", "user", "text", "锁屏"), 0);
        s.add("chat", Map.of("role", "assistant", "text", "锁屏", "via", "cmd"), 0);
        s.add("chat", Map.of("role", "user", "text", "你好啊朋友们大家好"), 0);
        List<Map<String, Object>> ctx = chat(s, c).context();
        assertEquals("system", ctx.get(0).get("role"));
        StringBuilder all = new StringBuilder();
        for (Map<String, Object> m : ctx.subList(1, ctx.size())) all.append(m.get("content")).append('|');
        assertFalse("指令不进上下文", all.toString().contains("锁屏"));
        assertTrue(all.toString().endsWith("你好啊朋友们大家好|"));
        assertFalse("超出字数的旧消息被截掉", all.toString().contains("xxxx"));
    }

    @Test public void memoryIsInSystemPrompt() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        s.setMemory("- 喜欢简短回答", 1);
        assertTrue(chat(s, new Config(tmp().resolve("c.properties"))).systemPrompt().contains("喜欢简短回答"));
    }

    @Test public void commandsRunWithoutLlmKey() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        Config c = new Config(tmp().resolve("c.properties"));
        Map<String, Object> r = chat(s, c).send("今天吃什么", "text");
        assertTrue(String.valueOf(r.get("text")).contains("大模型 Key"));
        assertEquals(2, s.list("chat").size());
    }

    @Test public void notifyUnknownKindFallsBackToApp() throws Exception {
        Store s = new Store(tmp().resolve("state.json"));
        Notify n = new Notify(s, x -> { });
        assertEquals("app", n.post("weird", "", "hi", "p").get("kind"));
        assertEquals("降水", n.post("rain", "", "", "p").get("title"));
    }

    @Test public void uiServerNeedsTokenAndLocalHost() throws Exception {
        UiServer ui = new UiServer();
        ui.on("echo", b -> Map.of("ok", true));
        assertTrue(ui.start(0));
        try {
            HttpClient h = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
            String base = "http://127.0.0.1:" + ui.port();
            HttpResponse<String> page = h.send(HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, page.statusCode());
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("const TOKEN='([^']+)'").matcher(page.body());
            assertTrue(m.find());
            assertFalse(m.group(1).startsWith("__"));
            assertEquals(401, h.send(HttpRequest.newBuilder(URI.create(base + "/api/echo")).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(200, h.send(HttpRequest.newBuilder(URI.create(base + "/api/echo")).header("X-OneSay-Token", m.group(1)).build(), HttpResponse.BodyHandlers.ofString()).statusCode());
        } finally {
            ui.stop();
        }
    }
}
