package com.onesay.assistant.v2.s3;

import com.onesay.assistant.v2.capability.SideEffect;

import java.util.regex.Pattern;

/**
 * [s3] 无人值守的安全规则（纯 Java，JVM 可测）。
 *
 * <ol>
 *   <li><b>创建时</b>：指令里含红卡动作（发短信 / 发邮件 / 回复邮件 / 支付 / 转账 / 删除 / 清空 / 卸载 / 打电话）→ 走
 *       {@link S3Capabilities#SIGN_TASK_ADD_CONFIRM}，创建环节就弹确认卡；</li>
 *   <li><b>执行时</b>：无人值守的 AGENT 任务照样走 ExecutionGate；另外，凡是红卡类能力（{@link #isRedClass}），<b>没有用户在确认卡上
 *       批准的令牌就绝不执行</b>（哪怕门禁因为「始终允许」放行了，也要先在岛上弹确认卡）；确认卡 10 分钟没人回应 → 安全中止。</li>
 * </ol>
 */
public final class S3Safety {

    private S3Safety() {}

    static final Pattern RED_WORDS = Pattern.compile(
            "发(个|条|一条)?短信|短信(给|告诉)|发(个|封|一封)?邮件|回(复)?邮件|邮件(给|告诉)|发微信|发消息给|转发|支付|付款|付钱|转账|买单|下单|"
                    + "删除|删掉|清空|清除|卸载|格式化|恢复出厂|打电话|拨打|呼叫");

    /**
     * <b>归一化后</b>（{@link com.onesay.assistant.v2.security.SafetyText#fold}：繁→简、NFKC、去零宽、同形字、小写）
     * 的红词，中英繁全收（繁体经 fold 变简体）。「every morning send email to boss」「pay the bill at 9am」「每天發郵件給老闆」
     * 「到点把报告寄出去」原来都漏。
     */
    static final Pattern RED_WORDS_NORM = Pattern.compile(
            "发(个|条|一条|送)?短信|短信(给|告诉|通知)|发(个|封|一封|送)?(电子)?邮件|回(复)?邮件|邮件(给|发给|告诉|通知)|发(个|条)?微信|发消息|"
                    + "发送|发给|寄(给|出|送)|转发|群发|推送给|"
                    + "支付|付款|付钱|转账|汇款|买单|下单|购买|充值|缴费|还款|"
                    + "删除|删掉|清空|清除|卸载|格式化|恢复出厂|重置|打电话|拨打|呼叫|致电|"
                    + "\\b(send|sends|sending|e-?mail|emails?|mail|text|sms|message|msg|reply|forward|fwd|tweet|post|publish|notify|dm|"
                    + "pay|pays|paying|payment|purchase|buy|order|checkout|transfer|wire|remit|"
                    + "delete|remove|erase|wipe|uninstall|format|reset|call|dial|phone)\\b",
            Pattern.CASE_INSENSITIVE);

    /** 指令里是否含红卡动作（先强归一化，中英繁全收）。 */
    public static boolean commandNeedsConfirm(String command) {
        if (command == null) return false;
        if (RED_WORDS.matcher(command).find()) return true;
        String f = com.onesay.assistant.v2.security.SafetyText.fold(command);
        return RED_WORDS_NORM.matcher(f).find() || RED_WORDS_NORM.matcher(com.onesay.assistant.v2.security.SafetyText.squash(command)).find();
    }

    /** 能力是否属于「无人值守绝不自动执行」的红卡类。 */
    public static boolean isRedClass(String sign, SideEffect effect) {
        if (effect == SideEffect.PUBLIC_COMMS || effect == SideEffect.DESTRUCTIVE || effect == SideEffect.ROOT) return true;
        if (sign == null) return false;
        // 与 ToolAllowlist / PermissionAnalyzer.forTool 同一套判据
        if ("ask.reply".equals(sign) || "chat.reply".equals(sign)) return false;
        if (com.onesay.assistant.v2.jobs.PermissionAnalyzer.isRemoteConnSign(sign)) return true;   // 远程 MCP 工具：无人值守一律岛上确认
        if (com.onesay.assistant.v2.security.OutboundJudge.signLooksOutbound(sign)) return true;
        String s = com.onesay.assistant.v2.security.SafetyText.fold(sign).toLowerCase(java.util.Locale.ROOT);
        return s.contains("sms") || s.contains("mail.send") || s.contains("mail.reply") || s.contains("pay") || s.contains("call")
                || s.contains("delete") || s.contains("uninstall") || s.contains("clear") || s.contains("wipe") || s.contains("send");
    }
}
