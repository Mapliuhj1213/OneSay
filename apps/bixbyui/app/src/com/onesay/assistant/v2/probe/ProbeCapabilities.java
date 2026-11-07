package com.onesay.assistant.v2.probe;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 只读探针能力注册 新增。
 *
 * <p>三条 {@code probe.*} 能力通过 {@link CapabilityRegistry#registerProbe} 注册：
 * <b>可被 describe() 解析、可被 AgentLoop 执行，但不出现在普通召回清单里</b>
 * （Top-K / L0 / 关键词倒排都看不到它们）——避免探针污染正常的意图匹配，
 * 它们只在 AgentLoop 的"探索族"里被显式注入。
 *
 * <p>sideEffect=NONE、risk=0：纯只读、不改任何设备状态；真正的行动（启动界面等）
 * 依然走原有能力的 ToolGate。
 */
public final class ProbeCapabilities {

    private ProbeCapabilities() {}

    public static int registerAll(CapabilityRegistry reg) {
        if (reg == null) return 0;
        int n = 0;

        reg.registerProbe(new Capability.Builder(ProbeEngine.SIGN_APPS,
                "列出手机已安装的应用（可带关键词过滤）。"
                        + "当需要确认某个应用是否安装、或拿不准它的准确名称/包名时使用；"
                        + "返回 label 与包名，之后可直接用 app.launch 精确启动。")
                .domain("probe")
                .category("probe")
                .sideEffect(SideEffect.NONE)
                .risk(0, false)
                .executor("probe")
                .schema(new ParamsSchema().addString("keyword", false, ""))
                .reply("已查询应用列表")
                .build());
        n++;

        reg.registerProbe(new Capability.Builder(ProbeEngine.SIGN_APP_INTENTS,
                "挖掘指定应用暴露的可调用接口（入参为包名，goal 填用户目标原话用于排序）。"
                        + "当用户目标涉及某个应用、而候选工具里没有它时使用：会挖出标准界面、带 host/path 的深链、"
                        + "私有动作、带中文名的界面和数据提供者，按目标相关度排序后自动注册为新工具并返回工具名，下一步可直接调用。"
                        + "注意：只声明 scheme 的链接（如 qqmail://，任何 host 都一样）只能拉起应用主界面，不代表收件箱/写信。")
                .domain("probe")
                .category("probe")
                .sideEffect(SideEffect.NONE)
                .risk(0, false)
                .executor("probe")
                .schema(new ParamsSchema().addString("package", true, "").addString("goal", false, ""))
                .reply("已挖掘应用接口")
                .build());
        n++;

        reg.registerProbe(new Capability.Builder(ProbeEngine.SIGN_SHELL_READ,
                "执行一条【只读】系统查询命令（白名单受限：仅 pm/dumpsys/getprop 类只读查询，"
                        + "禁止任何拼接/重定向/写操作）。仅当前两个探针不足以获取所需信息时兜底使用。")
                .domain("probe")
                .category("probe")
                .sideEffect(SideEffect.NONE)
                .risk(0, false)
                .executor("probe")
                .schema(new ParamsSchema().addString("command", true, ""))
                .reply("已执行只读查询")
                .build());
        n++;

        return n;
    }
}
