package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.capability.SideEffect;

/**
 * [conn] 远程工具的副作用等级（由<b>本机白名单</b>决定，不信远端自报）。
 *
 * <table>
 *   <tr><th>等级</th><th>映射 SideEffect</th><th>risk / confirm</th><th>说明</th></tr>
 *   <tr><td>READ</td><td>PRIVATE_DATA_READ</td><td>0 / 否</td><td>只读；结果是不可信外部内容 → 本任务后续按 tainted 过闸</td></tr>
 *   <tr><td>DRAFT</td><td>PERSISTENT_WRITE</td><td>1 / 否</td><td><b>仅内置连接器</b>（邮件草稿的建 / 改 / 删，只动本 App 建的草稿，对外不可见）：
 *       不弹卡，真正对外的 sendDraft 才弹；任务被污染时 ToolGate 仍会要求确认。远程 MCP 白名单写 draft 一律按 WRITE。</td></tr>
 *   <tr><td>WRITE</td><td>PERSISTENT_WRITE</td><td>2 / 是</td><td>建 / 改日程、改待办…：黄卡</td></tr>
 *   <tr><td>COMMS</td><td>PUBLIC_COMMS</td><td>2 / 是</td><td>对外发送（发邮件 / 发消息）：黄卡，不给「始终允许」</td></tr>
 *   <tr><td>DESTRUCTIVE</td><td>DESTRUCTIVE</td><td>3 / 是</td><td>删除类：黄卡（被污染时升级红卡）</td></tr>
 * </table>
 * 「有副作用的工具一律走黄卡确认」：除 READ / DRAFT（内置草稿）外 confirm 恒为 true；ExecutionGate 对 tainted 任务会再升级成红卡。
 */
public enum ToolEffect {
    READ, DRAFT, WRITE, COMMS, DESTRUCTIVE;

    public SideEffect sideEffect() {
        switch (this) {
            case READ: return SideEffect.PRIVATE_DATA_READ;
            case DRAFT:
            case WRITE: return SideEffect.PERSISTENT_WRITE;
            case COMMS: return SideEffect.PUBLIC_COMMS;
            default: return SideEffect.DESTRUCTIVE;
        }
    }

    public int risk() {
        switch (this) {
            case READ: return 0;
            case DRAFT: return 1;
            case DESTRUCTIVE: return 3;
            default: return 2;
        }
    }

    public boolean confirm() {
        return this != READ && this != DRAFT;
    }

    public boolean hasSideEffect() {
        return this != READ;
    }

    /** 宽松解析（白名单 JSON 里写的值）；不认识的返回 null（= 不放行）。 */
    public static ToolEffect parse(String s) {
        if (s == null) return null;
        String v = s.trim().toUpperCase(java.util.Locale.ROOT);
        if ("READ".equals(v) || "R".equals(v) || "READONLY".equals(v)) return READ;
        if ("WRITE".equals(v) || "W".equals(v) || "DRAFT".equals(v)) return WRITE;   // 远程白名单不能声明免确认的 DRAFT
        if ("COMMS".equals(v) || "SEND".equals(v)) return COMMS;
        if ("DESTRUCTIVE".equals(v) || "DELETE".equals(v)) return DESTRUCTIVE;
        return null;
    }

    /** 两者取更严的一个。 */
    public static ToolEffect stricter(ToolEffect a, ToolEffect b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.ordinal() >= b.ordinal() ? a : b;
    }
}
