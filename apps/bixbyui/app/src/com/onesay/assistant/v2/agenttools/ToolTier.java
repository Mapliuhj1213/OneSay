package com.onesay.assistant.v2.agenttools;

/**
 * ★ arch/tool-tiers（A 路）：AgentLoop 工具的四档分层。数字越小越优先。
 *
 * <pre>
 *   API(0)      接口 / 系统工具：直接读写系统状态、闹钟、邮件连接器、只读探针 …（一步到位、结果可核对）
 *   INFO(1)     查信息：搜索、读网页、查记忆、读屏幕文字（B 路在加 web.search / web.read，这里先留档位）
 *   DEEPLINK(2) 深链：am start / intent 打开到某个页面（app.launch、设置页、商店详情、浏览器搜索页 …）
 *   GUI(3)      点界面：phone.operate（以及它折叠时的入口 gui.unfold）
 * </pre>
 *
 * 规则写进提示词：「接口 > 查信息 > 深链 > 点界面」（assets/agent_prompt.md 的 tiers 段）。
 * 设计参考 Mika（wangzan101/mika，Apache-2.0，Copyright 2026 wangzan101）core/tool/ToolGroups.kt 的「GUI 组折叠在入口后面」，
 * 代码为本仓库重写，未复制原文。
 */
public enum ToolTier {
    API(0, "接口"),
    INFO(1, "查信息"),
    DEEPLINK(2, "深链"),
    GUI(3, "点界面");

    public final int rank;
    public final String label;

    ToolTier(int rank, String label) {
        this.rank = rank;
        this.label = label;
    }

    /** 前三档（接口 / 查信息 / 深链）。 */
    public boolean isUpper() {
        return this != GUI;
    }
}
