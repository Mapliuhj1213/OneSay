package com.onesay.clock.content;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** 最小 HTTP：GET / POST JSON，统一超时与 UA。 */
public final class Http {
    private Http() { }

    static final String UA = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120 Mobile Safari/537.36";

    public static String get(String url, int timeoutMs) throws Exception { return req(url, null, timeoutMs); }

    public static String postJson(String url, String json, int timeoutMs) throws Exception { return req(url, json, timeoutMs); }

    static String req(String url, String json, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept-Encoding", "identity");
            if (json != null) {
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] b = json.getBytes("UTF-8");
                c.setFixedLengthStreamingMode(b.length);
                OutputStream o = c.getOutputStream();
                try { o.write(b); } finally { o.close(); }
            }
            int code = c.getResponseCode();
            if (code < 200 || code >= 300) throw new Exception("HTTP " + code);
            return new String(readAll(c.getInputStream()), "UTF-8");
        } finally {
            c.disconnect();
        }
    }

    public static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return b.toByteArray();
        } finally { in.close(); }
    }
}
