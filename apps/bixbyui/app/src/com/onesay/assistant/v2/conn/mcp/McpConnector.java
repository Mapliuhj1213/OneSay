package com.onesay.assistant.v2.conn.mcp;

import com.onesay.assistant.v2.conn.ConnHttp;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.ConnTool;
import com.onesay.assistant.v2.conn.Connector;
import com.onesay.assistant.v2.conn.Redact;
import com.onesay.assistant.v2.conn.SecretStore;
import com.onesay.assistant.v2.conn.ToolEffect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [conn/mcp] 一个远程 MCP 服务器 = 一个连接器。
 *
 * <ul>
 *   <li>工具清单只在 {@link #refreshTools()}（控制台「刷新」/ 调试命令 / 首次调用）时触网拉取，结果缓存（可持久化，见 {@link CacheSink}）；
 *       AgentLoop 每轮只读缓存，不会因为列工具而卡住；</li>
 *   <li>{@link #tools()} = 缓存 ∩ 白名单（{@link com.onesay.assistant.v2.conn.ToolAllowlist}），白名单外的远程工具模型看不到也调不了；</li>
 *   <li>令牌从 SecretStore 现取，只放进请求头，不进日志、不进缓存。</li>
 * </ul>
 */
public final class McpConnector implements Connector {

    public interface CacheSink {
        void save(String connectorId, String toolsJson);
    }

    private final McpServerConfig mCfg;
    private final SecretStore mSecrets;
    private final ConnHttp mHttp;
    private final String mAppVersion;
    private final CacheSink mSink;
    private volatile List<McpTool> mCache = Collections.emptyList();
    private volatile long mCacheAt;
    private volatile McpClient mClient;
    private volatile String mLastError = "";

    public McpConnector(McpServerConfig cfg, SecretStore secrets, ConnHttp http, String appVersion, CacheSink sink) {
        mCfg = cfg;
        mSecrets = secrets;
        mHttp = http;
        mAppVersion = appVersion;
        mSink = sink;
    }

    public McpServerConfig config() { return mCfg; }

    @Override public String id() { return mCfg.id; }
    @Override public String title() { return mCfg.title; }
    @Override public boolean exposeToAgent() { return mCfg.enabled; }

    @Override
    public boolean isReady() {
        return mCfg.enabled && mCfg.validate() == null;
    }

    /** 从持久化的缓存恢复（App 重启后不用先触网也能把工具交给模型）。 */
    public void loadCache(String json, long at) {
        if (json == null || json.length() == 0) return;
        try {
            JSONArray arr = new JSONArray(json);
            List<McpTool> l = new ArrayList<McpTool>();
            for (int i = 0; i < arr.length(); i++) {
                McpTool t = McpTool.fromJson(arr.optJSONObject(i));
                if (t != null) l.add(t);
            }
            mCache = Collections.unmodifiableList(l);
            mCacheAt = at;
        } catch (Exception ignored) {
        }
    }

    public List<McpTool> rawTools() { return mCache; }

    @Override
    public List<ConnTool> tools() {
        List<ConnTool> out = new ArrayList<ConnTool>();
        if (!isReady()) return out;
        for (McpTool t : mCache) {
            ToolEffect e = mCfg.allow.effectFor(t.name, t.annotations, t.inputSchema);   // ★ LOGIC-5 结构识别
            if (e == null) continue;
            out.add(new ConnTool(mCfg.id, t.name, "[" + mCfg.title + "] " + t.description, t.inputSchema, e));
        }
        return out;
    }

    Map<String, String> authHeaders() {
        Map<String, String> h = new LinkedHashMap<String, String>();
        String tok = mSecrets == null ? "" : mSecrets.get(mCfg.secretKey());
        if (tok != null && tok.length() > 0) {
            String scheme = mCfg.authScheme == null ? "" : mCfg.authScheme.trim();
            h.put(mCfg.authHeader, scheme.length() > 0 ? scheme + " " + tok : tok);
        }
        return h;
    }

    /** 建连（auto：先 Streamable HTTP，HTTP 400/404/405 再退旧版 SSE）。 */
    synchronized McpClient client() throws McpException {
        McpClient c = mClient;
        if (c != null && c.initialized()) return c;
        Map<String, String> auth = authHeaders();
        if (McpServerConfig.T_SSE.equals(mCfg.transport)) {
            c = new McpClient(new SseTransport(mHttp, mCfg.url, auth), mAppVersion);
            c.initialize();
        } else {
            c = new McpClient(new StreamableHttpTransport(mHttp, mCfg.url, auth), mAppVersion);
            try {
                c.initialize();
            } catch (McpException e) {
                boolean legacy = McpServerConfig.T_AUTO.equals(mCfg.transport)
                        && (e.httpStatus == 400 || e.httpStatus == 404 || e.httpStatus == 405);
                if (!legacy) throw e;
                c = new McpClient(new SseTransport(mHttp, mCfg.url, auth), mAppVersion);
                c.initialize();
            }
        }
        mClient = c;
        return c;
    }

    public String transportKind() {
        McpClient c = mClient;
        return c == null ? "-" : c.transport().kind();
    }

    @Override
    public ConnResult refreshTools() {
        if (!isReady()) return ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "MCP 服务器「" + mCfg.title + "」未启用或配置不合法");
        try {
            List<McpTool> l = client().listTools();
            mCache = Collections.unmodifiableList(new ArrayList<McpTool>(l));
            mCacheAt = System.currentTimeMillis();
            if (mSink != null) {
                JSONArray arr = new JSONArray();
                for (McpTool t : l) arr.put(t.toJson());
                mSink.save(mCfg.id, arr.toString());
            }
            mLastError = "";
            return ConnResult.ok("远端 " + l.size() + " 个工具，白名单放行 " + tools().size() + " 个");
        } catch (McpException e) {
            drop();
            mLastError = e.code + " " + e.getMessage();
            return ConnResult.fail(e.code, e.getMessage());
        }
    }

    @Override
    public ConnResult call(String toolName, JSONObject args) {
        if (!isReady()) return ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "MCP 服务器未启用");
        McpTool t = find(toolName);
        ToolEffect e = t == null ? mCfg.allow.effectFor(toolName, null) : mCfg.allow.effectFor(t.name, t.annotations, t.inputSchema);
        if (e == null) return ConnResult.fail(ConnResult.Code.DENIED, "工具 " + toolName + " 不在白名单里");
        try {
            McpClient.ToolOutput out = client().callTool(toolName, args);
            mLastError = "";
            if (out.isError) return ConnResult.fail(ConnResult.Code.REMOTE_ERROR, "远端工具报错：" + out.text);
            return ConnResult.ok(out.text);
        } catch (McpException ex) {
            if (ex.code == ConnResult.Code.UNREACHABLE || ex.code == ConnResult.Code.TIMEOUT
                    || ex.code == ConnResult.Code.TLS_ERROR || ex.code == ConnResult.Code.AUTH_REVOKED) drop();
            mLastError = ex.code + " " + ex.getMessage();
            return ConnResult.fail(ex.code, ex.getMessage());
        }
    }

    private McpTool find(String name) {
        for (McpTool t : mCache) if (t.name.equals(name)) return t;
        return null;
    }

    private synchronized void drop() {
        McpClient c = mClient;
        mClient = null;
        if (c != null) {
            try { c.close(); } catch (Throwable ignored) { }
        }
    }

    @Override
    public String status() {
        String tok = mSecrets == null ? "" : mSecrets.get(mCfg.secretKey());
        return "mcp[" + mCfg.id + "] enabled=" + mCfg.enabled + " transport=" + mCfg.transport + "/" + transportKind()
                + " token=" + (tok.length() > 0 ? "set" : "none") + " remote=" + mCache.size() + " allowed=" + tools().size()
                + (mLastError.length() > 0 ? " last_error=" + Redact.s(mLastError, 80) : "");
    }
}
