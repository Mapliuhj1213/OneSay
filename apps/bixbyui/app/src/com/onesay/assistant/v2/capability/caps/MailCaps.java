package com.onesay.assistant.v2.capability.caps;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * 内置能力 · 邮件域（IMAP 读信/草稿）。
 *
 * <p>从 {@code BuiltinCapabilities.registerMail} <b>原样机械搬出</b>（内容、顺序逐字不变，
 * 由 {@code BuiltinCapabilitiesSnapshotTest} 的全字段快照保证）。注册顺序仍由
 * {@code BuiltinCapabilities.registerAll} 决定。
 */
public final class MailCaps {

    private MailCaps() {}

    /**
     * 邮件域能力（新增，IMAP）。
     *
     * <p>为什么走 IMAP 而不是"读屏幕"：邮件有公开标准协议；
     * 而 UI 自动化在 QQ 邮箱上三条死路 —— 腾讯风控、主界面自绘拿不到稳定控件、
     * 以及 {@code adb shell input text} 打不了中文（连"新建中文草稿"都做不到）。
     * 详见 {@code executor/MailBridge}。
     */
    public static void register(CapabilityRegistry reg) {
        // 读收件箱最新邮件（只读，零风险）
        reg.register(new Capability.Builder("mail.inbox.latest", "读收件箱最新邮件（IMAP）")
                .domain("mail").category("mail")
                .verbs("读", "查看", "看看", "念")
                .entities("mail")
                .keywords("邮件", "邮箱", "收件箱", "来信", "邮件内容", "最新邮件")
                .examples("读一下我的邮件", "看看收件箱最新一封")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("custom")
                .schema(new ParamsSchema()
                        .addInteger("count", false, 1, 5, "1")
                        .addBoolean("body", false, "true")
                        .addString("folder", false, "INBOX")
                        // ★ int/1102：按类型 / 发件人 / 内容读（filter = unread | flagged）
                        .addString("filter", false, "")
                        .addString("from", false, "")
                        .addString("keyword", false, "")
                        // ★ 11-04 F/G：摘要模式（数量口径 + 最近一封 + 2–4 封值得看的，排除广告通知）；value = 「有价值的邮件」
                        .addBoolean("digest", false, "false")
                        .addBoolean("value", false, "false")
                        // ★ 11-04 午：「展开第 N 封」按 UID 直读（摘要时记下的 UID）；index = 第几封（回答里写「第 N 封」）
                        .addString("uid", false, "")
                        .addInteger("index", false, 1, 4, "1"))
                // ⚠️ say 模板里**只能用 L0 认识的槽位名**（{app}/{n}/{m}/{url}/{content}）。
                //   本轮踩过：写了 {subject} → 未替换 → 正则编译异常 → **整条 L0 路由全挂**
                //   （已在 L0RuleRouter.buildRegex 加了兜底防护，但模板仍不该乱写）。
                //   邮件这两个能力的参数由 LLM 在 AGENT 通道填，**不需要** L0 槽位。
                .say("读邮件", "读一下邮件", "看看邮件", "有新邮件吗", "收件箱最新",
                        "读一下我的邮件", "看看收件箱", "念一下邮件")
                .reply("正在读取邮件…")
                .build());

        // 新建草稿（不发送 —— 用户明确"只读 + 草稿"）
        reg.register(new Capability.Builder("mail.draft.new", "在草稿箱新建一封邮件草稿（IMAP）")
                .domain("mail").category("mail")
                .verbs("新建", "写", "保存", "存")
                .entities("mail")
                .keywords("草稿", "草稿箱", "写邮件", "新建邮件")
                .examples("写一封草稿邮件", "存个草稿")
                .sideEffect(SideEffect.DEVICE_CONTROL).risk(0, false)
                .executor("custom")
                .schema(new ParamsSchema()
                        .addString("subject", false, "")
                        .addString("body", false, "")
                        .addString("to", false, ""))
                .say("新建草稿邮件", "写一封草稿", "存个邮件草稿", "草稿邮件")
                .reply("草稿已保存到邮箱")
                .build());
    }
}
