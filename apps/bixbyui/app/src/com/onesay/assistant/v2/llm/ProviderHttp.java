package com.onesay.assistant.v2.llm;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Map;

/**
 * 发 {@link WireRequest}（「测试」按钮用；对话请求走 {@code LlmClient}，规矩相同）。
 * <ul>
 *   <li>带 Key 只发 https（{@link ProviderUrls#keySafe}），不跟随重定向（3xx 直接当失败，Key 不会被带去别的主机）；</li>
 *   <li>Key 只在这里现填进请求头，日志 / 异常信息里都不出现。</li>
 * </ul>
 */
public final class ProviderHttp {
    private ProviderHttp() {}

    public static final class Response {
        public final int status;
        public final String body;

        public Response(int status, String body) {
            this.status = status;
            this.body = body == null ? "" : body;
        }
    }

    /** 可替换的传输层（JVM 单测注入录制好的回复，不联网）。 */
    public interface Transport {
        Response exec(WireRequest req, String key, int connectMs, int readMs) throws IOException;
    }

    public static final Transport URL_CONNECTION = new Transport() {
        @Override public Response exec(WireRequest req, String key, int connectMs, int readMs) throws IOException {
            if (key != null && key.length() > 0 && !ProviderUrls.keySafe(req.url)) throw new IOException("拒绝：Key 只发往 https 地址");
            HttpURLConnection c = (HttpURLConnection) new URL(req.url).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(connectMs);
                c.setReadTimeout(readMs);
                c.setRequestMethod(req.method);
                for (Map.Entry<String, String> e : req.headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
                if (key != null && key.length() > 0 && req.keyHeader != null) c.setRequestProperty(req.keyHeader, req.keyPrefix + key);
                if (req.body != null) {
                    c.setDoOutput(true);
                    OutputStream os = c.getOutputStream();
                    os.write(req.body.getBytes("UTF-8"));
                    os.close();
                }
                int code = c.getResponseCode();
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                return new Response(code, read(is, 64 * 1024));
            } finally {
                c.disconnect();
            }
        }
    };

    static String read(InputStream is, int max) throws IOException {
        if (is == null) return "";
        BufferedReader br = new BufferedReader(new InputStreamReader(is, "UTF-8"));
        try {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = br.read(buf)) > 0 && sb.length() < max) sb.append(buf, 0, n);
            return sb.toString();
        } finally {
            br.close();
        }
    }
}
