package com.onesay.assistant.v2.conn;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** [conn] {@link ConnHttp} 的 HttpURLConnection 实现（真机用）。不记录任何头 / 体。 */
public final class UrlConnHttp implements ConnHttp {

    /** 响应体上限（防止异常服务器把内存吃光）。 */
    static final int MAX_BODY = 4 * 1024 * 1024;

    @Override
    public Resp send(Req req) {
        HttpURLConnection c = null;
        try {
            c = open(req);
            int code = c.getResponseCode();
            Map<String, String> h = headers(c);
            InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
            String body = in == null ? "" : readAll(in);
            return new Resp(code, h, body, null);
        } catch (Throwable t) {
            return Resp.fail(t);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    @Override
    public Resp stream(Req req, LineSink sink) {
        HttpURLConnection c = null;
        try {
            c = open(req);
            int code = c.getResponseCode();
            Map<String, String> h = headers(c);
            if (code >= 400) {
                InputStream es = c.getErrorStream();
                return new Resp(code, h, es == null ? "" : readAll(es), null);
            }
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            String line;
            while ((line = r.readLine()) != null) {
                if (!sink.onLine(line)) break;
            }
            return new Resp(code, h, "", null);
        } catch (Throwable t) {
            return Resp.fail(t);
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static HttpURLConnection open(Req req) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(req.url).openConnection();
        String m = req.method == null ? "GET" : req.method.toUpperCase(Locale.ROOT);
        if ("PATCH".equals(m)) {
            // HttpURLConnection 不认 PATCH：Google API 支持 X-HTTP-Method-Override
            c.setRequestMethod("POST");
            c.setRequestProperty("X-HTTP-Method-Override", "PATCH");
        } else {
            c.setRequestMethod(m);
        }
        c.setConnectTimeout(req.connectMs);
        c.setReadTimeout(req.readMs);
        c.setInstanceFollowRedirects(false);   // 带令牌的请求不跟随跳转（防止把 Authorization 带到别的主机）
        c.setUseCaches(false);
        for (Map.Entry<String, String> e : req.headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        // 先单独建连（TCP + TLS 握手）：这一步失败 = 请求肯定没发出，包一层标记让 ConnErrors 归 UNREACHABLE
        try {
            c.connect();
        } catch (java.io.IOException e) {
            throw new ConnectPhaseException(e);
        }
        if (req.body != null) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(req.body.length);
            OutputStream os = c.getOutputStream();
            try {
                os.write(req.body);
            } finally {
                os.close();
            }
        } else if ("POST".equals(m) || "PUT".equals(m)) {
            c.setDoOutput(true);
            c.setFixedLengthStreamingMode(0);
            c.getOutputStream().close();
        }
        return c;
    }

    private static Map<String, String> headers(HttpURLConnection c) {
        Map<String, String> out = new LinkedHashMap<String, String>();
        Map<String, List<String>> h = c.getHeaderFields();
        if (h == null) return out;
        for (Map.Entry<String, List<String>> e : h.entrySet()) {
            if (e.getKey() == null || e.getValue() == null || e.getValue().isEmpty()) continue;
            out.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue().get(0));
        }
        return out;
    }

    private static String readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                bo.write(buf, 0, n);
                if (bo.size() > MAX_BODY) break;
            }
            return bo.toString("UTF-8");
        } finally {
            in.close();
        }
    }
}
