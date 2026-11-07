package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.executor.ErrorCode;
import com.onesay.assistant.v2.executor.ExecutionResult;

import org.json.JSONObject;

/**
 * [conn] 连接器工具 → 能力契约（{@link Capability}）/ 执行结果。
 * 能力<b>不进 CapabilityRegistry</b>（与 S2/S3 自带能力一样，不改道老指令、能力快照不变），由 ConnectorPlugin 的
 * DESCRIBE / AGENT_DESCRIBE 挂钩现造现给。
 */
public final class ConnCapabilities {

    private ConnCapabilities() {}

    public static final String DOMAIN = "conn";
    /** 有副作用的连接器工具走执行后端 executor="conn"（UnifiedExecutor 先校验并消费确认令牌）。 */
    public static final String EXECUTOR = "conn";

    public static Capability of(ConnTool t) {
        return new Capability.Builder(t.sign, clip(t.description.length() > 0 ? t.description : t.name, 300))
                .domain(DOMAIN).category(t.connectorId)
                .sideEffect(t.effect.sideEffect()).risk(t.effect.risk(), t.effect.confirm())
                .executor(EXECUTOR).schema(new ParamsSchema())   // 远端 schema 交给模型；本机只透传，不做二次校验
                .reply("{preview}")
                .build();
    }

    /** AgentLoop 工具定义（OpenAI function 格式；parameters 用远端 inputSchema）。 */
    public static JSONObject toolJson(ConnTool t) {
        try {
            JSONObject fn = new JSONObject();
            fn.put("name", com.onesay.assistant.v2.capability.CapabilityRegistry.signToToolName(t.sign));
            String d = t.description.length() > 0 ? t.description : t.name;
            if (t.effect.confirm()) d = d + "（有副作用：执行前会弹确认卡）";
            fn.put("description", clip(d, 500));
            JSONObject schema = t.inputSchema;
            if (schema == null || !"object".equals(schema.optString("type", "object"))) schema = ConnTool.emptySchema();
            if (!schema.has("type")) schema = new JSONObject(schema.toString()).put("type", "object");
            fn.put("parameters", schema);
            return new JSONObject().put("type", "function").put("function", fn);
        } catch (Exception e) {
            return null;
        }
    }

    public static ExecutionResult toResult(ConnResult r, Capability cap, JSONObject args) {
        if (r == null) return ExecutionResult.failed(ErrorCode.EXEC_FAILED, "连接器无返回", "null", cap, args);
        if (r.ok()) return ExecutionResult.success(cap, args, r.text, false, r.text);
        String msg = ConnErrors.human(r.code) + "：" + r.text;
        switch (r.code) {
            case NOT_CONFIGURED:
                return ExecutionResult.failed(ErrorCode.NOT_CONFIGURED, msg, r.text, "需要用户先在控制台配置该连接器；可改用本机能力", cap, args);
            case AUTH_REVOKED:
                return ExecutionResult.failed(ErrorCode.AUTH_FAILED, msg, r.text, cap, args);
            case UNREACHABLE:
                return ExecutionResult.failed(ErrorCode.NETWORK_ERROR, msg, r.text, "网络到不了该服务（可能在大陆网络环境下）；改用本机能力，不要反复重试", cap, args);
            case TIMEOUT:
                return ExecutionResult.failed(ErrorCode.TIMEOUT, msg, r.text, cap, args);
            case CANCELLED:
                return ExecutionResult.failed(ErrorCode.CANCELLED, msg, r.text, cap, args);
            case TLS_ERROR:
                return ExecutionResult.failed(ErrorCode.TLS_ERROR, msg, r.text, cap, args);
            case DENIED:
                return ExecutionResult.failed(ErrorCode.GATE_DENIED, msg, r.text, cap, args);
            case BAD_ARGS:
                return ExecutionResult.failed(ErrorCode.SCHEMA_INVALID, msg, r.text, cap, args);
            default:
                return ExecutionResult.failed(ErrorCode.EXEC_FAILED, msg, r.text, cap, args);
        }
    }

    static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }
}
