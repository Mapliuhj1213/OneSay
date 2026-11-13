package com.onesay.assistant.v2.net;

import com.onesay.assistant.v2.timeline.TimelineNotes;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

/** ★ console-align 2026-11-12：主模型接口地址 / 模型名可改（默认 DeepSeek；https，debug 包也认 http）；LlmFallback 照常。 */
public class MainModelEndpointTest {

    private static final String OK = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"备用答的\"}}]}";
    private static final String OK_MAIN = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"主模型答的\"}}]}";
    private HttpServer server;
    private volatile int mainStatus = 503;
    private final List<String> mainBodies = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> backupBodies = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> notes = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> trace = Collections.synchronizedList(new ArrayList<String>());
    private final TimelineNotes.Listener listener = new TimelineNotes.Listener() {
        @Override public void onNote(String text) {
            notes.add(text);
        }
    };

    @Before public void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(Executors.newCachedThreadPool());
        server.createContext("/v1/chat/completions", new HttpHandler() {
            @Override public void handle(HttpExchange ex) {
                reply(ex, mainBodies, mainStatus, mainStatus == 200 ? OK_MAIN : "{\"error\":\"x\"}");
            }
        });
        server.createContext("/backup", new HttpHandler() {
            @Override public void handle(HttpExchange ex) {
                reply(ex, backupBodies, 200, OK);
            }
        });
        server.start();
        TimelineNotes.add(listener);
        LlmClient.setTrace(new LlmClient.Trace() {
            @Override public void line(String s) {
                trace.add(s);
            }
        });
    }

    static void reply(HttpExchange ex, List<String> sink, int code, String body) {
        try {
            sink.add(new String(ex.getRequestBody().readAllBytes(), "UTF-8"));
            byte[] bb = body.getBytes("UTF-8");
            ex.sendResponseHeaders(code, bb.length);
            OutputStream os = ex.getResponseBody();
            os.write(bb);
            os.close();
        } catch (Exception ignored) {
        }
    }

    @After public void down() {
        MainModelEndpoint.setForProcess(null, false);
        LlmModels.setChat(null);
        LlmFallback.setSource(null);
        TimelineNotes.remove(listener);
        LlmClient.setTrace(null);
        server.stop(0);
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    // ------------------------------------------------------------------ 校验

    @Test public void emptyMeansDefaultDeepSeek() {
        MainModelEndpoint.Check c = MainModelEndpoint.check("", false);
        assertTrue(c.ok);
        assertEquals("https://api.deepseek.com", c.base);
        assertTrue(MainModelEndpoint.check(null, false).ok);
        assertTrue(MainModelEndpoint.check("   ", true).ok);
        MainModelEndpoint.setForProcess(null, false);
        assertTrue(MainModelEndpoint.isDefault());
        assertEquals("默认与改前完全一样", LlmClient.DEEPSEEK_URL, MainModelEndpoint.chatUrl());
        assertEquals("默认（DeepSeek）", MainModelEndpoint.summary());
    }

    @Test public void httpsAcceptedAndNormalized() {
        assertEquals("https://llm.example.org/v1", MainModelEndpoint.check(" https://llm.example.org/v1/ ", false).base);
        assertEquals("误填整条地址也认", "https://llm.example.org/v1", MainModelEndpoint.check("https://llm.example.org/v1/chat/completions", false).base);
        assertEquals("https://llm.example.org/v1", MainModelEndpoint.check("https://llm.example.org/v1/models", false).base);
        assertEquals("scheme 统一小写", "https://Llm.Example.org:8443/v1", MainModelEndpoint.check("HTTPS://Llm.Example.org:8443/v1", false).base);
    }

    @Test public void httpOnlyInDebugBuild() {
        MainModelEndpoint.Check rel = MainModelEndpoint.check("http://192.168.1.20:8080/v1", false);
        assertFalse("release 包不认 http", rel.ok);
        assertEquals("地址要以 https:// 开头", rel.error);
        MainModelEndpoint.Check dbg = MainModelEndpoint.check("http://192.168.1.20:8080/v1", true);
        assertTrue("debug 包认 http（本地 / 代理测 5xx）", dbg.ok);
        assertEquals("http://192.168.1.20:8080/v1", dbg.base);
        assertEquals("地址要以 https:// 或 http:// 开头", MainModelEndpoint.check("ftp://x.org", true).error);
        // release 包里存着的 http 值读进来回落默认
        MainModelEndpoint.setForProcess("http://192.168.1.20:8080/v1", false);
        assertTrue(MainModelEndpoint.isDefault());
        MainModelEndpoint.setForProcess("http://192.168.1.20:8080/v1", true);
        assertFalse(MainModelEndpoint.isDefault());
        assertTrue(MainModelEndpoint.summary(), MainModelEndpoint.summary().contains("192.168.1.20") && MainModelEndpoint.summary().contains("仅调试包"));
    }

    @Test public void rejectsBadShapes() {
        assertFalse(MainModelEndpoint.check("api.deepseek.com", false).ok);
        assertEquals("地址里不能带用户名或密码", MainModelEndpoint.check("https://user:pw@llm.example.org/v1", false).error);
        assertEquals("地址里不能带 ? 或 # 后面的参数", MainModelEndpoint.check("https://llm.example.org/v1?key=sk-1", false).error);
        assertEquals("地址里不能带 ? 或 # 后面的参数", MainModelEndpoint.check("https://llm.example.org/v1#x", false).error);
        assertEquals("地址里不能有空格", MainModelEndpoint.check("https://llm.example .org/v1", false).error);
        assertEquals("地址里没有主机名", MainModelEndpoint.check("https:///v1", false).error);
        assertFalse(MainModelEndpoint.check("https://" + new String(new char[400]).replace('\0', 'a') + ".org", false).ok);
        assertFalse(MainModelEndpoint.check("javascript:alert(1)", true).ok);
    }

    @Test public void modelNameCheck() {
        assertNull("空 = 默认", MainModelEndpoint.checkModel(""));
        assertNull(MainModelEndpoint.checkModel("deepseek-v4-pro"));
        assertNull(MainModelEndpoint.checkModel("Qwen/Qwen2.5-7B-Instruct"));
        assertNull(MainModelEndpoint.checkModel("gpt-4o-mini:2024@x+y"));
        assertNotNull(MainModelEndpoint.checkModel("bad model"));
        assertNotNull(MainModelEndpoint.checkModel("a;rm -rf"));
        assertNotNull(MainModelEndpoint.checkModel("-dash-first"));
        // LlmModels：DeepSeek 式名字仍小写；别家的大小写 / 斜杠原样；非法照旧回缺省
        assertEquals("deepseek-v4-pro", LlmModels.setChat("DeepSeek-V4-Pro"));
        assertEquals("Qwen/Qwen2.5-7B-Instruct", LlmModels.setChat("Qwen/Qwen2.5-7B-Instruct"));
        assertEquals("Qwen/Qwen2.5-7B-Instruct", LlmModels.resolve(LlmModels.DEEPSEEK_DEFAULT));
        assertEquals(LlmModels.DEEPSEEK_DEFAULT, LlmModels.setChat("bad model;rm"));
        assertEquals(LlmModels.DEEPSEEK_DEFAULT, LlmModels.setChat(""));
    }

    @Test public void logLineHasHostOnly() {
        MainModelEndpoint.setForProcess("https://llm.example.org/secret-path/v1", false);
        String l = MainModelEndpoint.logLine();
        assertTrue(l, l.contains("host=llm.example.org"));
        assertFalse("不记路径", l.contains("secret-path"));
        assertEquals("llm.example.org", MainModelEndpoint.host(MainModelEndpoint.chatUrl()));
    }

    // ------------------------------------------------------------------ 请求时现取 + 备用照常

    static final String BODY = "{\"model\":\"deepseek-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}";

    LlmClient mainClient() {
        return new LlmClient(LlmClient.DEEPSEEK_URL, new LlmClient.KeyProvider() {
            @Override public String apiKey() {
                return "sk-main";
            }
        }, 2000, 2000).withTag("agent");
    }

    @Test public void clientFollowsSettingAtRequestTime() {
        LlmClient c = mainClient();
        assertEquals(LlmClient.DEEPSEEK_URL, c.url());
        MainModelEndpoint.setForProcess(base() + "/v1", true);
        assertEquals("同一个客户端，改了马上生效", base() + "/v1/chat/completions", c.url());
        mainStatus = 200;
        LlmResponse r = c.post(BODY);
        assertTrue(r.error, r.ok());
        assertEquals("主模型答的", r.content());
        assertEquals(1, mainBodies.size());
        MainModelEndpoint.setForProcess("", true);
        assertEquals("留空回默认", LlmClient.DEEPSEEK_URL, c.url());
        // 别的地址（百炼 / 备用）不受影响
        assertEquals(LlmClient.DASHSCOPE_COMPAT_URL, new LlmClient(LlmClient.DASHSCOPE_COMPAT_URL, null, 1, 1).url());
    }

    /** 周日清单「备用模型」：主地址指向返回 5xx 的地址 → 切到备用，时间轴记一步；日志只有主机名、没有 Key。 */
    @Test public void mainPointedAt5xxSwitchesToBackupAndNotesTimeline() throws Exception {
        MainModelEndpoint.setForProcess(base() + "/v1", true);
        LlmFallback.setSource(new LlmFallback.Source() {
            @Override public LlmFallback.Backup current() {
                return new LlmFallback.Backup(base() + "/backup", "qwen-plus", "测试备用", new LlmClient.KeyProvider() {
                    @Override public String apiKey() {
                        return "sk-backup";
                    }
                });
            }
        });
        mainStatus = 502;
        LlmResponse r = mainClient().post(BODY);
        assertTrue(r.ok());
        assertEquals("备用答的", r.content());
        assertEquals("主地址确实被请求过", 1, mainBodies.size());
        assertEquals(1, backupBodies.size());
        assertEquals("qwen-plus", new JSONObject(backupBodies.get(0)).getString("model"));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0), notes.get(0).startsWith(LlmFallback.NOTE) && notes.get(0).contains("502"));
        String t = trace.toString();
        assertTrue(t, t.contains("切到备用模型") && t.contains("host=127.0.0.1"));
        assertFalse("日志不含 Key", t.contains("sk-main") || t.contains("sk-backup"));
    }

    @Test public void mainPointedAt5xxWithoutBackupFailsAsBefore() {
        MainModelEndpoint.setForProcess(base() + "/v1", true);
        mainStatus = 503;
        LlmResponse r = mainClient().post(BODY);
        assertFalse(r.ok());
        assertEquals(503, r.httpCode);
        assertTrue(notes.isEmpty());
    }
}
