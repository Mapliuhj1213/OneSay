package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [conn] 连接器注册表（纯逻辑，可单测）：注册 / 查找 / 导出 AgentLoop 工具 / 签名 → 能力 / 执行。
 *
 * <h3>门禁分工</h3>
 * <ul>
 *   <li>READ 工具：AgentLoop 先过 ExecutionGate（被污染时也要确认），放行后经 AGENT_EXECUTE → {@link #executeRead}；</li>
 *   <li>有副作用的工具：{@link #executeRead} 拒绝（返回 null 让 AgentLoop 走宿主 GatedCallback）→ UnifiedExecutor 校验并<b>消费</b>
 *       确认令牌 → {@link ConnBackend} → {@link #executeGated}。任何不经令牌的路径都到不了远端写接口。</li>
 * </ul>
 */
public final class ConnectorRegistry {

    /** 每轮最多交给模型多少个连接器工具（防止把上下文撑爆）。 */
    public static final int MAX_AGENT_TOOLS = 24;

    private final Map<String, Connector> mConnectors = new LinkedHashMap<String, Connector>();

    public synchronized void register(Connector c) {
        if (c != null) mConnectors.put(c.id(), c);
    }

    public synchronized void unregister(String id) {
        mConnectors.remove(id);
    }

    public synchronized Connector get(String id) {
        return mConnectors.get(id);
    }

    public synchronized List<Connector> all() {
        return new ArrayList<Connector>(mConnectors.values());
    }

    /** 交给 AgentLoop 的工具（exposeToAgent && isReady），按注册顺序，最多 {@link #MAX_AGENT_TOOLS} 个。 */
    public List<ConnTool> agentTools() {
        List<ConnTool> out = new ArrayList<ConnTool>();
        for (Connector c : all()) {
            if (!c.exposeToAgent() || !c.isReady()) continue;
            for (ConnTool t : c.tools()) {
                if (out.size() >= MAX_AGENT_TOOLS) return out;
                out.add(t);
            }
        }
        return out;
    }

    /** 按签名找工具（只在 agentTools 范围里找：白名单外 / 未就绪的一律找不到）。 */
    public ConnTool find(String sign) {
        if (sign == null || !sign.startsWith(ConnTool.PREFIX)) return null;
        for (ConnTool t : agentTools()) if (t.sign.equals(sign)) return t;
        return null;
    }

    /** 按签名找工具，不限 exposeToAgent（给 A 线 / 监控任务等 App 内调用方用，仍受白名单与就绪约束）。 */
    public ConnTool findAny(String sign) {
        if (sign == null || !sign.startsWith(ConnTool.PREFIX)) return null;
        for (Connector c : all()) {
            if (!c.isReady()) continue;
            for (ConnTool t : c.tools()) if (t.sign.equals(sign)) return t;
        }
        return null;
    }

    public Capability describe(String sign) {
        ConnTool t = find(sign);
        return t == null ? null : ConnCapabilities.of(t);
    }

    /** 往 AgentLoop 候选工具里追加（已存在的同名工具跳过）。@return 追加了几个 */
    public int appendTools(JSONArray tools) {
        if (tools == null) return 0;
        java.util.Set<String> have = new java.util.HashSet<String>();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            JSONObject fn = t != null ? t.optJSONObject("function") : null;
            if (fn != null) have.add(fn.optString("name"));
        }
        int n = 0;
        for (ConnTool t : agentTools()) {
            String wire = CapabilityRegistry.signToToolName(t.sign);
            if (have.contains(wire)) continue;
            JSONObject j = ConnCapabilities.toolJson(t);
            if (j == null) continue;
            tools.put(j);
            have.add(wire);
            n++;
        }
        return n;
    }

    /**
     * App 内执行只读工具（门禁已放行）。不是本注册表的签名、或者工具有副作用 → 返回 null（交回宿主走令牌路径）。
     */
    public ExecutionResult executeRead(Capability cap, JSONObject args) {
        if (cap == null) return null;
        ConnTool t = find(cap.sign);
        if (t == null || t.effect.hasSideEffect()) return null;
        Connector c = get(t.connectorId);
        if (c == null) return null;
        return ConnCapabilities.toResult(c.call(t.name, args), cap, args);
    }

    /** 令牌已由 UnifiedExecutor 消费之后执行（{@link ConnBackend} 调用）。READ 工具也可走这里。 */
    public ExecutionResult executeGated(Capability cap, JSONObject args) {
        ConnTool t = cap == null ? null : findAny(cap.sign);
        if (t == null) return ExecutionResult.failed(ErrorCode.UNKNOWN_CAPABILITY, "这个连接器工具不存在或未启用",
                "no conn tool " + (cap != null ? cap.sign : "null"), cap, args);
        Connector c = get(t.connectorId);
        if (c == null) return ExecutionResult.failed(ErrorCode.UNKNOWN_CAPABILITY, "连接器不存在", t.connectorId, cap, args);
        return ConnCapabilities.toResult(c.call(t.name, args), cap, args);
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        for (Connector c : all()) {
            if (sb.length() > 0) sb.append(" | ");
            sb.append(c.status());
        }
        return sb.length() == 0 ? "no connectors" : sb.toString();
    }
}
