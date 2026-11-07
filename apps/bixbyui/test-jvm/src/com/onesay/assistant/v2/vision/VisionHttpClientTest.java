package com.onesay.assistant.v2.vision;

import static org.junit.Assert.*;

import com.onesay.assistant.v2.net.HttpClient;

import org.junit.Test;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;

/** 识屏视觉请求改走共享 HttpClient，参数与旧实现一致。 */
public class VisionHttpClientTest {

    static Object field(Object o, String name) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    @Test public void requestMatchesLegacyConnectionSettings() throws Exception {
        byte[] body = "{\"a\":1}".getBytes("UTF-8");
        HttpClient.Request r = ScreenVisionEngine.visionRequest("https://example.invalid/v1", "sk-x", body);
        assertEquals("https://example.invalid/v1", field(r, "url"));
        assertEquals("POST", field(r, "method"));
        assertEquals(12000, field(r, "connectTimeoutMs"));
        assertEquals(25000, field(r, "readTimeoutMs"));
        @SuppressWarnings("unchecked") Map<String, String> h = (Map<String, String>) field(r, "headers");
        assertEquals("Bearer sk-x", h.get("Authorization"));
        assertEquals("application/json", h.get("Content-Type"));
        assertSame(body, field(r, "body"));
    }

    @Test public void unreachableHostReturnsErrorInsteadOfThrowing() throws Exception {
        HttpClient.Request r = ScreenVisionEngine.visionRequest("http://127.0.0.1:1/x", "k", new byte[]{'{', '}'})
                .timeouts(500, 500);
        HttpClient.Response resp = HttpClient.execute(r);
        assertEquals(-1, resp.code);
        assertNotNull(resp.error);
    }

    @Test public void noRawConnectionLeftInScreenref() throws Exception {
        String root = System.getProperty("bixby.root", ".");
        String src = new String(Files.readAllBytes(new File(root,
                "app/src/com/onesay/assistant/v2/vision/ScreenVisionEngine.java").toPath()), StandardCharsets.UTF_8);
        assertFalse(src.contains("openConnection"));
        assertFalse(src.contains("HttpURLConnection"));
    }

    /** 云端错误体摘要（真机 10-10 22:11 起 HTTP 404，旧日志只有状态码）。 */
    @Test public void cloudErrorSummary() {
        assertEquals("model_not_found The model `qwen-vl-plus` does not exist",
                ScreenVisionEngine.cloudErrorSummary("{\"error\":{\"code\":\"model_not_found\",\"message\":\"The model `qwen-vl-plus` does not exist\"}}"));
        assertEquals("InvalidApiKey Invalid API-key provided: sk-***",
                ScreenVisionEngine.cloudErrorSummary("{\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided: sk-abcdef123456\"}"));
        // 带「-」「.」的 workspace key（sk-ws-xxx.yyy）也要整串打码
        assertEquals("InvalidApiKey Invalid API-key provided: sk-***",
                ScreenVisionEngine.cloudErrorSummary("{\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided: sk-ws-H.AbCdEf_12-34\"}"));
        assertFalse(ScreenVisionEngine.cloudErrorSummary("{\"message\":\"key sk-ws-H.AbCdEf123 bad\"}").contains("AbCdEf"));
        assertEquals("", ScreenVisionEngine.cloudErrorSummary(""));
        assertEquals("", ScreenVisionEngine.cloudErrorSummary(null));
        assertEquals("<html>404</html>", ScreenVisionEngine.cloudErrorSummary("<html>404</html>"));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 50; i++) sb.append("abcdef");
        assertEquals(121, ScreenVisionEngine.cloudErrorSummary(sb.toString()).length());
    }

    /** qwen-vl-plus 已下线（404 model_not_found）→ 默认 qwen3-vl-plus，可用 vision_model 覆盖。 */
    @Test public void visionModelDefaultAndOverride() {
        assertEquals("qwen3-vl-plus", ScreenVisionEngine.pickModel(null));
        assertEquals("qwen3-vl-plus", ScreenVisionEngine.pickModel("  "));
        assertEquals("qwen3-vl-flash", ScreenVisionEngine.pickModel(" qwen3-vl-flash "));
    }
}

