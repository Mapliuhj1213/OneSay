/*
 * Copyright 2026 wangzan101 (Mika) and mylea (phongent, https://github.com/mylea1/phongent)
 * Modifications Copyright 2026 OneSay
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * 改动声明（OneSay，2026-11-02）：本文件改写自 phongent
 * app/src/main/java/com/wangzan101/mika/remote/RemoteBridge.kt 里的请求解析 / 应答部分（Kotlin → Java 7）。
 * 改动：拆成独立的纯 Java 类（JVM 可测）；行长、头数、正文上限沿用上游口径，另加 URL 长度上限与 query 解析；
 * 应答行改为标准原因短语（上游统一写 "Response"）。
 */
package com.onesay.assistant.plug.phongent.core;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URLDecoder;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * [phongent] 极简 HTTP/1.1（只够 phongent 主控用）：一个连接一个请求、Connection: close、不支持分块正文。
 * 纯 Java，JVM 可测。坏请求一律抛 {@link IOException}，调用方直接关连接、不回包、不记正文。
 */
public final class PgHttp {

    public static final int MAX_LINE = 8192;
    public static final int MAX_HEADERS = 40;
    /** 普通 JSON 正文上限（上游 16 KiB；任务正文 ≤ 8000 字，UTF-8 最多 24 000 字节，放宽到 32 KiB）。 */
    public static final int MAX_BODY = 32 * 1024;

    private PgHttp() {}

    public static final class Request {
        public final String method;
        /** 原始请求目标（含 query）。 */
        public final String target;
        /** 去掉 query 的路径。 */
        public final String path;
        public final Map<String, String> query;
        /** 头名一律小写。 */
        public final Map<String, String> headers;
        public final byte[] body;

        Request(String method, String target, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.target = target;
            int q = target.indexOf('?');
            this.path = q < 0 ? target : target.substring(0, q);
            this.query = parseQuery(q < 0 ? "" : target.substring(q + 1));
            this.headers = headers;
            this.body = body;
        }

        public String header(String name) {
            String v = headers.get(name.toLowerCase(Locale.ROOT));
            return v == null ? "" : v;
        }

        public JSONObject json() throws IOException {
            if (body == null || body.length == 0) throw new IOException("empty_body");
            try {
                return new JSONObject(new String(body, "UTF-8"));
            } catch (org.json.JSONException e) {
                throw new IOException("bad_json");
            }
        }
    }

    static Map<String, String> parseQuery(String q) {
        Map<String, String> m = new HashMap<String, String>();
        if (q == null || q.length() == 0) return m;
        for (String part : q.split("&")) {
            if (part.length() == 0) continue;
            int e = part.indexOf('=');
            String k = e < 0 ? part : part.substring(0, e);
            String v = e < 0 ? "" : part.substring(e + 1);
            try {
                m.put(URLDecoder.decode(k, "UTF-8"), URLDecoder.decode(v, "UTF-8"));
            } catch (Exception ignored) {
            }
        }
        return m;
    }

    static String line(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        while (true) {
            int c = in.read();
            if (c < 0) throw new IOException("eof");
            if (c == '\n') break;
            if (b.size() >= MAX_LINE) throw new IOException("line_too_long");
            if (c != '\r') b.write(c);
        }
        return b.toString("UTF-8");
    }

    /** 读一个请求。bodyLimit 由调用方按路径给（{@link #MAX_BODY}）。 */
    public static Request read(InputStream in, int bodyLimit) throws IOException {
        String first = line(in);
        String[] rq = first.split(" ");
        if (rq.length != 3 || !rq[2].startsWith("HTTP/1.")) throw new IOException("bad_request_line");
        String method = rq[0];
        if (!"GET".equals(method) && !"POST".equals(method)) throw new IOException("bad_method");
        String target = rq[1];
        if (target.length() == 0 || target.charAt(0) != '/' || target.length() > 2048) throw new IOException("bad_target");
        Map<String, String> headers = new HashMap<String, String>();
        int n = 0;
        while (true) {
            String l = line(in);
            if (l.length() == 0) break;
            if (++n >= MAX_HEADERS) throw new IOException("too_many_headers");
            int c = l.indexOf(':');
            if (c <= 0) throw new IOException("bad_header");
            headers.put(l.substring(0, c).trim().toLowerCase(Locale.ROOT), l.substring(c + 1).trim());
        }
        byte[] body = new byte[0];
        if ("POST".equals(method)) {
            if (headers.containsKey("transfer-encoding")) throw new IOException("chunked_unsupported");
            String cl = headers.get("content-length");
            int len;
            try {
                len = cl == null ? 0 : Integer.parseInt(cl.trim());
            } catch (NumberFormatException e) {
                throw new IOException("bad_length");
            }
            if (len < 0 || len > bodyLimit) throw new IOException("body_too_large");
            body = new byte[len];
            int got = 0;
            while (got < len) {
                int r = in.read(body, got, len - got);
                if (r <= 0) throw new IOException("short_body");
                got += r;
            }
        }
        return new Request(method, target, headers, body);
    }

    static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 400: return "Bad Request";
            case 401: return "Unauthorized";
            case 403: return "Forbidden";
            case 404: return "Not Found";
            case 409: return "Conflict";
            case 422: return "Unprocessable Entity";
            case 429: return "Too Many Requests";
            case 501: return "Not Implemented";
            case 503: return "Service Unavailable";
            default: return "Response";
        }
    }

    public static void writeJson(OutputStream out, int code, JSONObject value) throws IOException {
        byte[] data = value.toString().getBytes("UTF-8");
        String head = "HTTP/1.1 " + code + " " + reason(code) + "\r\nContent-Type: application/json; charset=utf-8\r\n"
                + "Cache-Control: no-store\r\nConnection: close\r\nContent-Length: " + data.length + "\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(data);
        out.flush();
    }

    /** 流式应答头（事件流 / 视频流），之后调用方自己写正文直到关连接。 */
    public static void writeStreamHead(OutputStream out, String contentType) throws IOException {
        String head = "HTTP/1.1 200 OK\r\nContent-Type: " + contentType + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.flush();
    }

    public static JSONObject err(String code) {
        JSONObject o = new JSONObject();
        try {
            o.put("error", code == null ? "error" : code);
        } catch (Exception ignored) {
        }
        return o;
    }
}
