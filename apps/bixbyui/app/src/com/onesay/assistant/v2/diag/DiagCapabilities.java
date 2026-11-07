package com.onesay.assistant.v2.diag;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;

/**
 * [diag] 诊断修复的能力对象（<b>不进注册表</b>，BuiltinCapabilities 快照不变；AgentLoop 也看不到它们）。
 *
 * <table>
 *   <tr><th>签名</th><th>副作用 / 风险</th><th>门禁</th></tr>
 *   <tr><td>{@code diag.fix.<修复id>}</td><td>DEVICE_CONTROL / 2 + confirm</td><td>黄卡确认（卡上是「改前 → 改后」）；只接受 args.fix = 表内 id</td></tr>
 *   <tr><td>{@code diag.undo}</td><td>DEVICE_CONTROL / 1</td><td>放行（恢复用户 10 分钟内刚确认过的改动；值来自撤销记录，同样过白名单）</td></tr>
 * </table>
 * 只读诊断本身不是能力调用（路由层直接读、直接回答），无需确认。
 * 打开设置页的芯片直接用现有 {@code settings.page.*} 能力。
 */
public final class DiagCapabilities {

    private DiagCapabilities() {}

    public static final String EXECUTOR = "diag";
    public static final String PREFIX_FIX = "diag.fix.";
    public static final String SIGN_UNDO = "diag.undo";

    /** 一个修复芯片对应的能力（summary 带改前 → 改后，确认卡直接显示它）。 */
    public static Capability fix(DiagKnowledge.Fix f, String summary) {
        if (f == null || !f.writes()) return null;
        return new Capability.Builder(PREFIX_FIX + f.id, summary != null ? summary : f.label)
                .domain("diag")
                .category("settings")
                .verbs("设置")
                .entities("setting")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(2, true)
                .executor(EXECUTOR)
                .reply(f.label + " 已完成")
                .build();
    }

    public static Capability undo(String summary) {
        return new Capability.Builder(SIGN_UNDO, summary != null ? summary : "撤销刚才的设置改动")
                .domain("diag")
                .category("settings")
                .verbs("设置")
                .entities("setting")
                .sideEffect(SideEffect.DEVICE_CONTROL)
                .risk(1, false)
                .executor(EXECUTOR)
                .reply("已撤销")
                .build();
    }

    /** 由签名还原能力（面板 dispatch / 执行层用；未知签名返回 null）。 */
    public static Capability describe(String sign) {
        if (sign == null) return null;
        if (SIGN_UNDO.equals(sign)) return undo(null);
        if (sign.startsWith(PREFIX_FIX)) {
            DiagKnowledge.Fix f = DiagKnowledge.fix(sign.substring(PREFIX_FIX.length()));
            return f != null ? fix(f, null) : null;
        }
        return null;
    }

    public static boolean isLocal(Capability cap) {
        return cap != null && EXECUTOR.equals(cap.executorType)
                && cap.sign != null && (cap.sign.startsWith(PREFIX_FIX) || SIGN_UNDO.equals(cap.sign));
    }

    /** 修复芯片的参数：只有表内 id（门禁令牌按它做摘要）。 */
    public static JSONObject fixArgs(String fixId) {
        JSONObject a = new JSONObject();
        try {
            a.put("fix", fixId);
        } catch (Exception ignored) {
        }
        return a;
    }

    public static JSONObject undoArgs(String recordId) {
        JSONObject a = new JSONObject();
        try {
            if (recordId != null) a.put("undo", recordId);
        } catch (Exception ignored) {
        }
        return a;
    }

    /**
     * 执行层守卫：签名里的修复 id 与 args.fix 必须一致且在表内（防止参数被换成别的修复）。
     * @return 修复 id；不合法返回 null
     */
    public static String fixIdOf(Capability cap, JSONObject args) {
        if (cap == null || cap.sign == null || !cap.sign.startsWith(PREFIX_FIX)) return null;
        String id = cap.sign.substring(PREFIX_FIX.length());
        String a = args != null ? args.optString("fix", "") : "";
        if (!id.equals(a)) return null;
        DiagKnowledge.Fix f = DiagKnowledge.fix(id);
        return f != null && f.writes() ? id : null;
    }
}
