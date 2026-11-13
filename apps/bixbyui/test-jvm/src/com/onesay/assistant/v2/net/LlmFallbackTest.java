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

/** ★ borrow-1112 ④：主模型超时 / 5xx / 429 → 已配置的备用模型；没配置照旧报错；时间轴记「已切换到备用模型」。 */
public class LlmFallbackTest {

    private static final String OK = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"备用答的\"}}]}";
    private HttpServer server;
    private volatile int primaryStatus = 503;
    private final List<String> primaryBodies = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> backupBodies = Collections.synchronizedList(new ArrayList<String>());
    private final List<String> backupAuth = Collections.synchronizedList(new ArrayList<String>());
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
        server.createContext("/primary", new HttpHandler() {
            @Override public void handle(HttpExchange ex) {
                try {
                    primaryBodies.add(new String(ex.getRequestBody().readAllBytes(), "UTF-8"));
                    byte[] bb = (primaryStatus == 200 ? OK : "{\"error\":\"x\"}").getBytes("UTF-8");
                    ex.sendResponseHeaders(primaryStatus, bb.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(bb);
                    os.close();
                } catch (Exception ignored) {
                }
            }
        });
        server.createContext("/backup", new HttpHandler() {
            @Override public void handle(HttpExchange ex) {
                try {
                    backupBodies.add(new String(ex.getRequestBody().readAllBytes(), "UTF-8"));
                    backupAuth.add(ex.getRequestHeaders().getFirst("Authorization"));
                    byte[] bb = OK.getBytes("UTF-8");
                    ex.sendResponseHeaders(200, bb.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(bb);
                    os.close();
                } catch (Exception ignored) {
                }
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

    @After public void down() {
        LlmFallback.setSource(null);
        TimelineNotes.remove(listener);
        LlmClient.setTrace(null);
        server.stop(0);
    }

    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    LlmClient primary() {
        return new LlmClient(base() + "/primary", new LlmClient.KeyProvider() {
            @Override public String apiKey() {
                return "sk-primary";
            }
        }, 2000, 2000).withTag("agent");
    }

    void configureBackup(final String model) {
        LlmFallback.setSource(new LlmFallback.Source() {
            @Override public LlmFallback.Backup current() {
                return new LlmFallback.Backup(base() + "/backup", model, "测试备用", new LlmClient.KeyProvider() {
                    @Override public String apiKey() {
                        return "sk-backup";
                    }
                });
            }
        });
    }

    static final String BODY = "{\"model\":\"deepseek-flash\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}],\"thinking\":{\"type\":\"disabled\"}}";

    @Test public void fiveHundredSwitchesToBackupAndNotesTimeline() throws Exception {
        configureBackup("qwen-plus");
        LlmResponse r = primary().post(BODY);
        assertTrue(r.ok());
        assertEquals("备用答的", r.content());
        assertEquals(1, backupBodies.size());
        JSONObject sent = new JSONObject(backupBodies.get(0));
        assertEquals("qwen-plus", sent.getString("model"));
        assertFalse("别家不认 thinking 字段", sent.has("thinking"));
        assertEquals("Bearer sk-backup", backupAuth.get(0));
        assertEquals(1, notes.size());
        assertTrue(notes.get(0), notes.get(0).startsWith(LlmFallback.NOTE));
        String t = trace.toString();
        assertTrue(t, t.contains("切到备用模型"));
        assertFalse("日志不含 Key", t.contains("sk-backup") || t.contains("sk-primary"));
    }

    @Test public void rateLimitSwitches() {
        primaryStatus = 429;
        configureBackup("qwen-plus");
        assertTrue(primary().post(BODY).ok());
        assertEquals(1, backupBodies.size());
    }

    @Test public void noBackupConfiguredFailsAsBefore() {
        LlmResponse r = primary().post(BODY);
        assertFalse(r.ok());
        assertEquals(503, r.httpCode);
        assertEquals(0, backupBodies.size());
        assertTrue(notes.isEmpty());
    }

    @Test public void authErrorsAndSuccessDoNotSwitch() {
        configureBackup("qwen-plus");
        primaryStatus = 401;
        assertFalse(primary().post(BODY).ok());
        primaryStatus = 400;
        assertFalse(primary().post(BODY).ok());
        primaryStatus = 200;
        assertTrue(primary().post(BODY).ok());
        assertEquals(0, backupBodies.size());
    }

    @Test public void incompleteBackupIsIgnored() {
        LlmFallback.setSource(new LlmFallback.Source() {
            @Override public LlmFallback.Backup current() {
                return new LlmFallback.Backup(base() + "/backup", "qwen-plus", "x", new LlmClient.KeyProvider() {
                    @Override public String apiKey() {
                        return "";
                    }
                });
            }
        });
        assertFalse(primary().post(BODY).ok());
        assertEquals(0, backupBodies.size());
    }

    @Test public void classification() {
        assertTrue(LlmFallback.shouldSwitch(LlmResponse.failure(-1, null, "首字节超时")));
        assertTrue(LlmFallback.shouldSwitch(LlmResponse.failure(-1, null, "总时长到(60000ms)")));
        assertTrue(LlmFallback.shouldSwitch(LlmResponse.failure(502, "", "HTTP 502")));
        assertFalse(LlmFallback.shouldSwitch(LlmResponse.failure(-1, null, "连接异常(UnknownHostException: x)")));
        assertFalse(LlmFallback.shouldSwitch(LlmResponse.failure(402, "", "HTTP 402")));
        assertFalse(LlmFallback.shouldSwitch(null));
        assertEquals("主模型限流", LlmFallback.why(LlmResponse.failure(429, "", "HTTP 429")));
    }
}
