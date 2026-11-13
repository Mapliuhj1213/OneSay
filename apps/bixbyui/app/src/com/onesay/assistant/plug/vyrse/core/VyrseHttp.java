/*
 * Copyright 2026 OneSay
 * Portions derived from VYRSE (https://github.com/felixthosharon/vyrse, reference commit 41c5651),
 * Copyright (c) 2026 VYRSE contributors, MIT License (full text: THIRD_PARTY_NOTICES.md).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); see LICENSE.
 *
 * 改动声明（OneSay，2026-11-08）：VYRSE 手机端 VyrseLinkService.java / PhoneActions.java / PhoneControl.java
 * 与电脑端 desktop/src/main/phone-bridge.ts 的协议常量、动作语义、结果格式按原样对齐，代码为 Java 7 重写；
 * 只实现动作白名单，完整操控（tap / type …）不实现。
 */
package com.onesay.assistant.plug.vyrse.core;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * [vyrse 兼容 core] 窄 HTTP 接口（测试里换假的）。{@link LanSocket} 是裸 Socket 的明文 HTTP/1.1 实现，只连家庭网络地址。
 * 实现不得把 token 写进日志。
 */
public interface VyrseHttp {

    Response send(String method, String url, String token, String jsonBody, int readTimeoutMs) throws IOException;

    final class Response {
        public final int code;
        public final String body;

        public Response(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
        }
    }

    /**
     * 局域网明文 HTTP/1.1 客户端（裸 Socket）。不用 HttpURLConnection：本 App 全局 usesCleartextTraffic=false，
     * 这里只对配对时校验过的家庭网络地址开明文，不放开全局策略。每次请求一条连接（Connection: close），
     * 支持 Content-Length / chunked / 读到 EOF 三种响应体；响应限 1 MB；不跟随重定向。
     */
    final class LanSocket implements VyrseHttp {
        @Override public Response send(String method, String url, String token, String jsonBody, int readTimeoutMs) throws IOException {
            if (!url.startsWith("http://")) throw new IOException("http only");
            String rest = url.substring(7);
            int slash = rest.indexOf('/');
            String hostPort = slash < 0 ? rest : rest.substring(0, slash);
            String path = slash < 0 ? "/" : rest.substring(slash);
            String host = hostPort;
            int port = 80;
            int c = hostPort.lastIndexOf(':');
            if (c >= 0) {
                host = hostPort.substring(0, c);
                port = Integer.parseInt(hostPort.substring(c + 1));
            }
            if (!VyrseProtocol.isPrivateIpv4(host)) throw new IOException("home-network addresses only");
            if (path.indexOf('\r') >= 0 || path.indexOf('\n') >= 0 || (token != null && (token.indexOf('\r') >= 0 || token.indexOf('\n') >= 0))) {
                throw new IOException("bad request");
            }
            byte[] body = jsonBody == null ? null : jsonBody.getBytes("UTF-8");
            StringBuilder h = new StringBuilder();
            h.append(method).append(' ').append(path).append(" HTTP/1.1\r\n");
            h.append("Host: ").append(hostPort).append("\r\n");
            h.append("Connection: close\r\n");
            h.append("Accept: application/json\r\n");
            h.append("User-Agent: OneSay-vyrse-compat/1\r\n");
            if (token != null) h.append(VyrseProtocol.HEADER_TOKEN).append(": ").append(token).append("\r\n");
            if (body != null) {
                h.append("Content-Type: application/json\r\n");
                h.append("Content-Length: ").append(body.length).append("\r\n");
            }
            h.append("\r\n");
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(host, port), VyrseProtocol.CONNECT_TIMEOUT_MS);
                s.setSoTimeout(readTimeoutMs);
                OutputStream out = new BufferedOutputStream(s.getOutputStream());
                out.write(h.toString().getBytes("ISO-8859-1"));
                if (body != null) out.write(body);
                out.flush();
                return readResponse(new BufferedInputStream(s.getInputStream()));
            } finally {
                try {
                    s.close();
                } catch (IOException ignored) {
                }
            }
        }

        static Response readResponse(InputStream in) throws IOException {
            String status = line(in);
            if (status == null || !status.startsWith("HTTP/1.")) throw new IOException("not http");
            String[] parts = status.split(" ", 3);
            if (parts.length < 2) throw new IOException("bad status");
            int code;
            try {
                code = Integer.parseInt(parts[1].trim());
            } catch (NumberFormatException e) {
                throw new IOException("bad status");
            }
            long len = -1;
            boolean chunked = false;
            int headers = 0;
            String l;
            while ((l = line(in)) != null && l.length() > 0) {
                if (++headers > 100) throw new IOException("too many headers");
                int colon = l.indexOf(':');
                if (colon <= 0) continue;
                String k = l.substring(0, colon).trim().toLowerCase(java.util.Locale.ROOT);
                String v = l.substring(colon + 1).trim();
                if ("content-length".equals(k)) {
                    try {
                        len = Long.parseLong(v);
                    } catch (NumberFormatException e) {
                        throw new IOException("bad length");
                    }
                } else if ("transfer-encoding".equals(k) && v.toLowerCase(java.util.Locale.ROOT).contains("chunked")) {
                    chunked = true;
                }
            }
            if (l == null) throw new IOException("eof in headers");
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            if (chunked) {
                while (true) {
                    String sz = line(in);
                    if (sz == null) throw new IOException("eof in chunk");
                    int semi = sz.indexOf(';');
                    if (semi >= 0) sz = sz.substring(0, semi);
                    int n;
                    try {
                        n = Integer.parseInt(sz.trim(), 16);
                    } catch (NumberFormatException e) {
                        throw new IOException("bad chunk");
                    }
                    if (n < 0) throw new IOException("bad chunk");
                    if (n == 0) break;
                    copy(in, buf, n);
                    line(in);
                }
            } else if (len >= 0) {
                if (len > VyrseProtocol.MAX_RESPONSE_BYTES) throw new IOException("response too large");
                copy(in, buf, (int) len);
            } else {
                byte[] chunk = new byte[4096];
                int r;
                while ((r = in.read(chunk)) > 0) {
                    buf.write(chunk, 0, r);
                    if (buf.size() > VyrseProtocol.MAX_RESPONSE_BYTES) throw new IOException("response too large");
                }
            }
            return new Response(code, buf.toString("UTF-8"));
        }

        static void copy(InputStream in, ByteArrayOutputStream buf, int n) throws IOException {
            if (buf.size() + (long) n > VyrseProtocol.MAX_RESPONSE_BYTES) throw new IOException("response too large");
            byte[] b = new byte[Math.min(n, 8192)];
            int left = n;
            while (left > 0) {
                int r = in.read(b, 0, Math.min(left, b.length));
                if (r < 0) throw new IOException("eof in body");
                buf.write(b, 0, r);
                left -= r;
            }
        }

        /** 读一行（去掉 CRLF）；EOF 且没读到字节 = null。行长限 8 KB。 */
        static String line(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            boolean any = false;
            while ((c = in.read()) >= 0) {
                any = true;
                if (c == '\n') break;
                if (c != '\r') b.write(c);
                if (b.size() > 8192) throw new IOException("line too long");
            }
            if (!any) return null;
            return b.toString("ISO-8859-1");
        }
    }
}
