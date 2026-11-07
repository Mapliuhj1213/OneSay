package com.onesay.assistant.v2.conn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 测试用 ConnHttp：按处理器回包，记录每个请求（不触网）。 */
public class FakeHttp implements ConnHttp {

    public interface Handler {
        Resp handle(Req req, String body);
    }

    public final List<Req> requests = new ArrayList<Req>();
    public final List<String> bodies = new ArrayList<String>();
    public Handler handler;
    /** stream() 时若非空，按行喂给 sink（SSE 传输测试用另一个子类）。 */

    public FakeHttp(Handler h) {
        handler = h;
    }

    public static String bodyOf(Req r) {
        try {
            return r.body == null ? "" : new String(r.body, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    public static Resp json(int code, String body, String... kv) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("content-type", "application/json");
        for (int i = 0; i + 1 < kv.length; i += 2) h.put(kv[i].toLowerCase(), kv[i + 1]);
        return new Resp(code, h, body, null);
    }

    public static Resp sse(String body, String... kv) {
        Map<String, String> h = new LinkedHashMap<String, String>();
        h.put("content-type", "text/event-stream");
        for (int i = 0; i + 1 < kv.length; i += 2) h.put(kv[i].toLowerCase(), kv[i + 1]);
        return new Resp(200, h, body, null);
    }

    @Override
    public synchronized Resp send(Req req) {
        String b = bodyOf(req);
        requests.add(req);
        bodies.add(b);
        return handler.handle(req, b);
    }

    @Override
    public Resp stream(Req req, LineSink sink) {
        Resp r = send(req);
        if (r.error != null || r.code >= 400) return r;
        for (String line : r.body.split("\n", -1)) {
            if (!sink.onLine(line)) break;
        }
        return new Resp(r.code, r.headers, "", null);
    }

    public int count(String method) {
        int n = 0;
        for (String b : bodies) {
            try {
                if (method.equals(new org.json.JSONObject(b).optString("method"))) n++;
            } catch (Exception ignored) {
            }
        }
        return n;
    }
}
