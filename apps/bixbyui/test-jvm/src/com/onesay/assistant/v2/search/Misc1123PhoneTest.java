package com.onesay.assistant.v2.search;

import com.onesay.assistant.v2.pclink.PcForward;
import com.onesay.assistant.v2.util.TextUtil;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * [fix-1123-misc] 手机端：③ Parallel 可选搜索源（假服务端测，不用任何真实 Key）；② 通知文字按字符截、不切半个字；④ VYRSE 配对提示说清是哪台电脑。
 */
public class Misc1123PhoneTest {

    private HttpServer server;
    private final List<String> bodies = new ArrayList<String>();
    private final List<String> keys = new ArrayList<String>();
    private volatile int code = 200;
    private volatile String reply = "{}";

    @After public void stop() {
        if (server != null) server.stop(0);
    }

    private String start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/search", new HttpHandler() {
            @Override public void handle(HttpExchange ex) {
                try {
                    InputStream in = ex.getRequestBody();
                    ByteArrayOutputStream bo = new ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                    synchronized (bodies) {
                        bodies.add(new String(bo.toByteArray(), StandardCharsets.UTF_8));
                        keys.add(String.valueOf(ex.getRequestHeaders().getFirst("x-api-key")));
                    }
                    byte[] out = reply.getBytes(StandardCharsets.UTF_8);
                    ex.getResponseHeaders().add("Content-Type", "application/json");
                    ex.sendResponseHeaders(code, out.length);
                    OutputStream os = ex.getResponseBody();
                    os.write(out);
                    os.close();
                } catch (Exception ignored) {
                }
            }
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/search";
    }

    static ParallelSearchProvider.KeySource key(final String k) {
        return new ParallelSearchProvider.KeySource() {
            @Override public String key() { return k; }
        };
    }

    static final String FAKE_KEY = "fake-test-key-not-real";

    @Test public void noKeyNoRequest() throws Exception {
        String url = start();
        ParallelSearchProvider p = new ParallelSearchProvider(key(""), url);
        assertFalse(p.isReady());
        assertTrue(p.search("今天天气", 5).isEmpty());
        assertFalse(new ParallelSearchProvider(key(null), url).isReady());
        assertFalse(new ParallelSearchProvider(key("   "), url).isReady());
        assertTrue("没填 Key 一次都不请求", bodies.isEmpty());
    }

    @Test public void searchAgainstFakeServer() throws Exception {
        String url = start();
        reply = "{\"search_id\":\"s1\",\"results\":["
                + "{\"url\":\"https://a.example/1\",\"title\":\"标题一\",\"publish_date\":null,\"excerpts\":[\"第一段\",\"第二段\"]},"
                + "{\"url\":\"https://a.example/2\",\"title\":\"\",\"excerpts\":[]},"
                + "{\"url\":\"https://a.example/3\",\"title\":\"标题三\",\"excerpts\":[\"x\"]}],\"warnings\":null}";
        ParallelSearchProvider p = new ParallelSearchProvider(key(FAKE_KEY), url);
        assertTrue(p.isReady());
        List<SearchResult> r = p.search("  比特币价格  ", 5);
        assertEquals(2, r.size());
        assertEquals("标题一", r.get(0).title);
        assertEquals("https://a.example/1", r.get(0).url);
        assertEquals("第一段 第二段", r.get(0).snippet);
        assertEquals("parallel", r.get(0).provider);
        assertFalse(r.get(0).keyless());
        assertFalse(p.lastSearchFailed());
        assertEquals(1, bodies.size());
        assertEquals("Key 走 x-api-key 头", FAKE_KEY, keys.get(0));
        JSONObject b = new JSONObject(bodies.get(0));
        assertEquals("比特币价格", b.getJSONArray("search_queries").getString(0));
        assertTrue(b.getString("objective").contains("比特币价格"));
        assertEquals("fast", b.getString("mode"));
        assertEquals(5, b.getJSONObject("advanced_settings").getInt("max_results"));
        assertFalse("max_results 不放顶层（官方会 422）", b.has("max_results"));
    }

    @Test public void failuresBecomeEmpty() throws Exception {
        String url = start();
        ParallelSearchProvider p = new ParallelSearchProvider(key(FAKE_KEY), url);
        code = 401;
        reply = "{\"error\":\"bad key\"}";
        assertTrue(p.search("q", 5).isEmpty());
        assertTrue(p.lastSearchFailed());
        code = 429;
        assertTrue("限流也收敛成空", p.search("q", 5).isEmpty());
        code = 200;
        reply = "不是 JSON";
        assertTrue(p.search("q", 5).isEmpty());
        server.stop(0);
        server = null;
        assertTrue("连不上也不抛", p.search("q", 5).isEmpty());
        assertTrue(p.lastSearchFailed());
    }

    static final class Fake implements SearchProvider {
        final String n;
        final boolean ready;
        Fake(String n, boolean ready) { this.n = n; this.ready = ready; }
        @Override public String name() { return n; }
        @Override public boolean isReady() { return ready; }
        @Override public List<SearchResult> search(String q, int m) { return new ArrayList<SearchResult>(); }
    }

    static String names(List<SearchProvider> l) {
        StringBuilder sb = new StringBuilder();
        for (SearchProvider p : l) sb.append(sb.length() == 0 ? "" : ",").append(p.name());
        return sb.toString();
    }

    @Test public void chainOnlyWithKeyAndNotDefault() {
        Fake t = new Fake("tavily", true), b = new Fake("bocha", true), pOn = new Fake("parallel", true), pOff = new Fake("parallel", false), bing = new Fake("bing-html", true);
        Fake tOff = new Fake("tavily", false), bOff = new Fake("bocha", false);
        assertEquals("没填 Parallel Key：和原来一样", "tavily,bocha,bing-html", names(WebSearchClient.chainFor("auto", t, b, pOff, bing)));
        assertEquals("什么 Key 都没填：只有免 Key 网页", "bing-html", names(WebSearchClient.chainFor("auto", tOff, bOff, pOff, bing)));
        assertEquals("填了 Key、自动：排在已有的后面，不抢默认", "tavily,bocha,parallel,bing-html", names(WebSearchClient.chainFor("auto", t, b, pOn, bing)));
        assertEquals("只填了 Parallel：它 + 网页兜底", "parallel,bing-html", names(WebSearchClient.chainFor("auto", tOff, bOff, pOn, bing)));
        assertEquals("点名 Parallel", "parallel,tavily,bocha,bing-html", names(WebSearchClient.chainFor(SearchConfig.PROVIDER_PARALLEL, t, b, pOn, bing)));
        assertEquals("点名 Parallel 但没填 Key：照旧", "tavily,bocha,bing-html", names(WebSearchClient.chainFor(SearchConfig.PROVIDER_PARALLEL, t, b, pOff, bing)));
        assertEquals("tavily,bocha,parallel,bing-html", names(WebSearchClient.chainFor(SearchConfig.PROVIDER_TAVILY, t, b, pOn, bing)));
        assertEquals("bocha,tavily,parallel,bing-html", names(WebSearchClient.chainFor(SearchConfig.PROVIDER_BOCHA, t, b, pOn, bing)));
        assertEquals("点名只用网页", "bing-html", names(WebSearchClient.chainFor(SearchConfig.PROVIDER_BING, t, b, pOn, bing)));
    }

    @Test public void settingsNoticeIsPlainAndHonest() throws Exception {
        String n = ParallelSearchProvider.NOTICE_ZH;
        assertTrue(n.contains("填了 Key 才会用"));
        assertTrue(n.contains("不会变成默认"));
        assertTrue(n.contains("限流"));
        assertTrue(n.contains("个人"));
        assertTrue(n.contains("训练"));
        for (String bad : new String[] {"啥", "咋", "呗", "Bixby", "三星", "DeX", "小布"}) assertFalse(bad, n.contains(bad));
        String ui = new String(Files.readAllBytes(Paths.get("app/src/com/onesay/assistant/v2/ui/KeysActivity.java")), StandardCharsets.UTF_8);
        assertTrue("和已有搜索源一样的卡片名", ui.contains("\"联网搜索 · Parallel\""));
        assertTrue(ui.contains("ParallelSearchProvider.NOTICE_ZH"));
        assertTrue(ui.contains("S_PARALLEL.equals(s)) return new SearchConfig(this).getParallelKey()"));
        String cfg = new String(Files.readAllBytes(Paths.get("app/src/com/onesay/assistant/v2/search/SearchConfig.java")), StandardCharsets.UTF_8);
        assertTrue("Key 加密存", cfg.contains("SecretPrefs.put(e, K_PARALLEL_KEY"));
        assertTrue("默认首选没变", cfg.contains("sp().getString(K_PROVIDER, PROVIDER_AUTO)"));
        JSONObject probe = new JSONObject(ParallelSearchProvider.probeBody());
        assertEquals(1, probe.getJSONArray("search_queries").length());
    }

    // ------------------------------------------------------------------ ② 按字符截

    static void noHalf(String c) {
        for (int i = 0; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (Character.isHighSurrogate(ch)) assertTrue(c, i + 1 < c.length() && Character.isLowSurrogate(c.charAt(i + 1)));
            if (Character.isLowSurrogate(ch)) assertTrue(c, i > 0 && Character.isHighSurrogate(c.charAt(i - 1)));
        }
    }

    @Test public void clipByCharacter() {
        String s = "做完了😀😀𠀀结果";
        for (int n = 0; n <= s.length(); n++) {
            noHalf(TextUtil.truncate(s, n, "…"));
            noHalf(TextUtil.clipChars(s, n, "…"));
        }
        assertEquals("做完了😀…", TextUtil.clipChars(s, 4, "…"));
        assertEquals("做完了…", TextUtil.truncate(s, 4, "…"));
        assertEquals("做完了😀…", TextUtil.truncate(s, 5, "…"));
        assertEquals(s, TextUtil.clipChars(s, 8, "…"));
        assertEquals("", TextUtil.clipChars(null, 3, "…"));
        assertEquals("abc...", TextUtil.truncate("abcdef", 3));
        // 手机发给电脑的通知参数：按字符截、末尾加省略号
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 499; i++) b.append('雨');
        b.append("😀😀");
        Map<String, Object> a = PcForward.args("x", new com.onesay.assistant.v2.notice.IslandNotice(
                com.onesay.assistant.v2.notice.IslandNotice.Source.WEATHER, "k", null, "标题", b.toString()));
        String t = String.valueOf(a.get("text"));
        noHalf(t);
        assertTrue(t, t.endsWith("…"));
    }

    @Test public void phoneShowsPcNotificationCutAtWholeChar() throws Exception {
        String src = new String(Files.readAllBytes(Paths.get("app/src/com/onesay/assistant/v2/pclink/PcRxAndroid.java")), StandardCharsets.UTF_8);
        assertTrue(src.contains("TextUtil.truncate(PcRxPolicy.str(a, \"text\"), PcRxPolicy.NOTIFY_TEXT_SHOW, \"…\")"));
        assertTrue("小于系统硬截的 1024", com.onesay.assistant.v2.pclink.PcRxPolicy.NOTIFY_TEXT_SHOW + 1 < 1024);
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 999; i++) b.append('字');
        b.append("😀😀😀");
        String shown = TextUtil.truncate(b.toString(), com.onesay.assistant.v2.pclink.PcRxPolicy.NOTIFY_TEXT_SHOW, "…");
        noHalf(shown);
        assertTrue(shown.length() <= 1001);
        assertTrue(shown.endsWith("字…"));
    }

    // ------------------------------------------------------------------ ④ 配对提示

    @Test public void vyrsePairHintsSayWhichPc() throws Exception {
        String[] files = {"app/src/com/onesay/assistant/plug/vyrse/core/VyrsePairing.java",
                "app/src/com/onesay/assistant/v2/vyrse/VyrseLinkService.java", "app/src/com/onesay/assistant/v2/vyrse/VyrseUi.java"};
        for (String f : files) {
            String s = new String(Files.readAllBytes(Paths.get(f)), StandardCharsets.UTF_8);
            assertFalse(f + " 不再笼统说「在电脑上重新点」", s.contains("在电脑上重新点「Pair a phone」"));
        }
        String ui = new String(Files.readAllBytes(Paths.get(files[2])), StandardCharsets.UTF_8);
        assertTrue(ui.contains("在 VYRSE 电脑端重新点配对按钮「Pair a phone」"));
    }
}
