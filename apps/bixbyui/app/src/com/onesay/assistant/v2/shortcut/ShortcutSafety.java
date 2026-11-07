package com.onesay.assistant.v2.shortcut;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.InterfaceKind;
import com.onesay.assistant.v2.capability.SideEffect;
import com.onesay.assistant.v2.conn.Redact;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.Iterator;
import java.util.regex.Pattern;

/**
 * [shortcut] 快捷指令的安全规则（纯 Java）。
 *
 * <ol>
 *   <li><b>发消息、花钱、删除类每次都弹确认卡</b>（{@link #alwaysConfirm}）：判据复用无人值守的红卡类
 *       {@code S3Safety.isRedClass}（对外发送 / 付款 / 打电话 / 删除 / 卸载 / 清空 / 远程 MCP 工具 / DESTRUCTIVE / ROOT），
 *       外加删除类接口（{@code InterfaceKind.DELETE}）。门禁因「始终允许」放行了也照样弹卡 —— 快捷指令不能成为绕过确认卡的捷径；</li>
 *   <li><b>污点</b>（{@link #taintSource}）：把「问模型」或读隐私数据的输出用 {@code {{prev}}} 喂给下一步时，下一步按 tainted 过闸
 *       （与 AgentLoop 读过不可信内容后的待遇一致）；</li>
 *   <li><b>存步骤时不存密钥</b>（{@link #sanitize}）：参数名像口令 / 令牌，或值里有 {@link Redact} 能认出的密钥形状 →
 *       这个参数改成「每次运行时问」，不写进计划 App。</li>
 * </ol>
 */
public final class ShortcutSafety {

    private ShortcutSafety() {}

    public static boolean alwaysConfirm(Capability cap) {
        if (cap == null) return true;
        SideEffect e = cap.sideEffect;
        if (e == SideEffect.PUBLIC_COMMS || e == SideEffect.DESTRUCTIVE || e == SideEffect.ROOT) return true;
        if (com.onesay.assistant.v2.s3.S3Safety.isRedClass(cap.sign, e)) return true;
        return InterfaceKind.of(cap) == InterfaceKind.Kind.DELETE;
    }

    public static boolean taintSource(Capability cap) {
        if (cap == null) return false;
        return ShortcutActions.LLM_ASK.equals(cap.sign) || cap.sideEffect == SideEffect.PRIVATE_DATA_READ;
    }

    static final Pattern SECRET_KEY = Pattern.compile("(?i).*(password|passwd|pwd|token|secret|api[_-]?key|apikey|auth|cookie|session|otp|验证码|口令|密码).*");

    /** 存步骤前清理参数：密钥类参数 → JSON null（每次问），并把名字加进 ask。返回清理后的新对象。 */
    public static JSONObject sanitize(JSONObject args, java.util.List<String> askOut) {
        JSONObject out = new JSONObject();
        if (args == null) return out;
        Iterator<?> it = args.keys();
        while (it.hasNext()) {
            String k = String.valueOf(it.next());
            Object v = args.opt(k);
            boolean secret = SECRET_KEY.matcher(k).matches();
            if (!secret && v instanceof String) {
                String s = (String) v;
                secret = !Redact.s(s, Integer.MAX_VALUE).equals(s.replace('\n', ' ').replace('\r', ' '));
            }
            try {
                if (secret) {
                    out.put(k, JSONObject.NULL);
                    if (askOut != null && !askOut.contains(k)) askOut.add(k);
                } else {
                    out.put(k, v);
                }
            } catch (JSONException ignored) {
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ 定时触发（无人值守）

    /**
     * 一条快捷指令到点运行要的权限（给定时任务的权限卡 / 授权指纹，{@code JobsEngine.CommandPerms}）：
     * 每一步按「能力签名 + 副作用等级」归类，与 S3Runner 运行时 {@code PermissionGuard.check} 同一判据。
     */
    public static java.util.Set<com.onesay.assistant.v2.jobs.Perm> perms(ShortcutModel.Shortcut sc, ShortcutActions.Lookup lookup) {
        java.util.Set<com.onesay.assistant.v2.jobs.Perm> s = java.util.EnumSet.noneOf(com.onesay.assistant.v2.jobs.Perm.class);
        if (sc == null) return s;
        for (ShortcutModel.Step st : sc.steps) {
            Capability cap = lookup == null ? null : lookup.lookup(st.tool);
            com.onesay.assistant.v2.jobs.Perm p = com.onesay.assistant.v2.jobs.PermissionAnalyzer.forTool(st.tool, cap == null ? null : cap.sideEffect);
            if (p != null) s.add(p);
        }
        return s;
    }

    /**
     * 无人值守回调（S3Runner）把「没授权 / 岛上没允许」报成 EXEC_FAILED；快捷指令要按拒绝处理（停下，不转正常模式），
     * 这里按它的 detail 换成 GATE_DENIED / USER_REJECTED。其余结果原样返回。
     */
    public static com.onesay.assistant.v2.executor.ExecutionResult unattendedVerdict(com.onesay.assistant.v2.executor.ExecutionResult r) {
        if (r == null || r.ok || r.detail == null) return r;
        com.onesay.assistant.v2.executor.ErrorCode c = null;
        if ("jobs ungranted".equals(r.detail)) c = com.onesay.assistant.v2.executor.ErrorCode.GATE_DENIED;
        else if ("jobs per-run confirm".equals(r.detail) || "s3 unattended red".equals(r.detail)) c = com.onesay.assistant.v2.executor.ErrorCode.USER_REJECTED;
        if (c == null) return r;
        return com.onesay.assistant.v2.executor.ExecutionResult.failed(c, r.userMessage, r.detail, r.capability, r.args);
    }
}
