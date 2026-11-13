package com.onesay.assistant.v2.phone;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * ★ borrow-1112：动作风险标签（纯 Java，JVM 可测）。
 *
 * <ol>
 *   <li><b>模型自报</b>：模型在每个动作里带 {@code "risk"}（none / money / security / delete / send / modify），
 *       说明它自己觉得这一步属于哪类风险；</li>
 *   <li><b>本地规则只能加严</b>：{@link SensitiveGuard} 等本地关键词规则判出敏感 → 不管模型写什么都要确认；模型写了
 *       money / security / delete / send 而本地没判出 → 也要确认（模型能把门槛抬高，不能放低）。
 *       {@code modify} 只记录，不单独触发确认（改设置这类动作本地规则已覆盖需要拦的那部分）；</li>
 *   <li><b>外部内容「污染」</b>：本轮任务读过网页、通知、短信等外部文字（{@link #tainted}）之后，任何发送 / 分享 / 提交类
 *       动作一律弹确认卡（防提示注入），即使之前「记住」过、模型标了 none 也一样；</li>
 *   <li>与「能批就不拦」一致：三条硬拒（付款最后一下、机器人不能自己批自己 / 语音不能批准、电脑不提权）在这之前判、批了也不行，
 *       风险标签只管「要不要问」，不碰硬拒。<b>不做</b>「用户点名的单个删除免确认」这类放宽。</li>
 * </ol>
 *
 * <p>思路借鉴 OpenPhoneUse（https://github.com/joungminsung/OpenPhoneUse，MIT）：模型在同一次调用里自标风险、关键词规则只加严、
 * 读过外部文字后发送一律确认、设完读不回的动作回报「已请求」；代码是自己写的，没有复制其源码。
 */
public final class RiskLabel {

    private RiskLabel() {}

    public enum Risk {
        NONE("none", ""), MONEY("money", "付款 / 花钱"), SECURITY("security", "登录 / 授权 / 安全设置"), DELETE("delete", "删除"),
        SEND("send", "发送 / 分享 / 提交"), MODIFY("modify", "修改设置");

        public final String id, zh;

        Risk(String id, String zh) {
            this.id = id;
            this.zh = zh;
        }

        /** 模型写的值 → 枚举（中英文都认；不认识 / 空 = NONE）。 */
        public static Risk of(String s) {
            if (s == null) return NONE;
            String t = s.trim().toLowerCase(Locale.ROOT);
            if (t.length() == 0) return NONE;
            for (Risk r : values()) if (r.id.equals(t)) return r;
            if (t.contains("pay") || t.contains("钱") || t.contains("付") || t.equals("financial")) return MONEY;
            if (t.contains("secur") || t.contains("login") || t.contains("安全") || t.contains("登录") || t.contains("auth")) return SECURITY;
            if (t.contains("del") || t.contains("删")) return DELETE;
            if (t.contains("send") || t.contains("share") || t.contains("submit") || t.contains("发") || t.contains("分享") || t.contains("提交")) return SEND;
            if (t.contains("mod") || t.contains("改") || t.contains("write")) return MODIFY;
            return NONE;
        }

        /** 模型自报这一类就要确认（modify 只记录）。 */
        public boolean asksUser() {
            return this == MONEY || this == SECURITY || this == DELETE || this == SEND;
        }
    }

    /**
     * 合并：本地规则结论（null = 本地没判出）+ 模型自报 → 最终要给用户看的确认原因（null = 不用确认）。
     * 本地判出的永远保留（模型标 none 不能放松）；本地没判出、模型标了要确认的类 → 用模型的类加严。
     */
    public static String merge(String localReason, Risk model) {
        if (localReason != null) return localReason;
        if (model != null && model.asksUser()) return "这一步你标的风险是" + model.zh + "，先确认一下";
        return null;
    }

    // ------------------------------------------------------------------ 外部内容污染

    /** 发送 / 分享 / 提交类按钮（比 SensitiveGuard.SEND 宽：污染时宁可多问）。 */
    static final Pattern SEND_LIKE = Pattern.compile(
            "发送|发 送|发出|分享|转发|提交|发布|发表|回复|评论|上传|确认发送|立即发送|告诉朋友|share|send|submit|post\\b|reply|publish|upload|forward",
            Pattern.CASE_INSENSITIVE);
    static final Pattern RID_SEND_LIKE = Pattern.compile("send|share|submit|post|reply|publish|upload|forward|comment", Pattern.CASE_INSENSITIVE);
    static final Pattern SEARCHY = Pattern.compile("搜索|查找|search|query|find", Pattern.CASE_INSENSITIVE);

    /**
     * 污染时这一步是不是「发送 / 分享 / 提交」：点 / 长按发送类按钮，或往非搜索框里写字并回车提交。
     * @return null = 不是；否则给用户看的原因
     */
    public static String taintedSendLike(PhonePlan.Step s, UiTree screen) {
        if (s == null) return null;
        UiTree.El e = s.id >= 0 && screen != null ? screen.find(s.id) : null;
        if ("tap".equals(s.op) || "long".equals(s.op)) {
            if (e == null) {
                String t = s.target == null ? "" : s.target;
                return t.length() > 0 && SEND_LIKE.matcher(t).find() ? reason(t) : null;
            }
            String name = SensitiveGuard.title(e.label);
            if (name.length() > 0 && name.replace(" ", "").length() <= 16 && SEND_LIKE.matcher(name).find()) return reason(name);
            String rid = UiTree.shortRid(e.rid);
            if (rid.length() > 0 && RID_SEND_LIKE.matcher(rid).find() && !SEARCHY.matcher(rid).find()) return reason(name.length() > 0 ? name : rid);
            return null;
        }
        if ("type".equals(s.op) && s.enter) {
            String where = e == null ? "" : (e.label + " " + e.rid);
            if (SEARCHY.matcher(where).find()) return null;   // 搜索框回车 = 查东西，不是对外发
            return "读过外部内容后，写完直接回车提交也要你确认";
        }
        return null;
    }

    static String reason(String name) {
        return "这一步会发送 / 分享 / 提交（" + UiTree.clip(name, 12) + "）。本次任务读过网页、通知或短信等外部内容，为防被里面的文字指使，发出去前先问你";
    }

    /** 读到这些前台应用的界面 = 读到了外部文字（浏览器、短信、邮件、通知栏）。 */
    static final String[] EXTERNAL_PKGS = {
            "com.android.chrome", "com.sec.android.app.sbrowser", "org.mozilla.firefox", "com.android.browser", "com.UCMobile",
            "com.tencent.mtt", "com.quark.browser", "com.huawei.browser", "com.heytap.browser", "com.vivo.browser", "com.mi.globalbrowser",
            "com.microsoft.emmx", "com.baidu.searchbox", "com.android.mms", "com.samsung.android.messaging",
            "com.google.android.apps.messaging", "com.android.email", "com.samsung.android.email.provider", "com.tencent.androidqqmail",
            "com.netease.mail", "com.google.android.gm", "com.android.systemui"};

    public static boolean externalTextPkg(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        for (String p : EXTERNAL_PKGS) if (p.equals(pkg)) return true;
        return false;
    }

    // ------------------------------------------------------------------ 本轮任务污染标记（AgentLoop → phone.operate 同一线程传下去）

    private static final ThreadLocal<Boolean> TAINT = new ThreadLocal<Boolean>();

    /** AgentLoop 执行本地工具前设上本任务是否已污染，执行完 {@link #clearTaskTaint}。 */
    public static void setTaskTaint(boolean tainted) {
        TAINT.set(tainted ? Boolean.TRUE : Boolean.FALSE);
    }

    public static void clearTaskTaint() {
        TAINT.remove();
    }

    public static boolean taskTainted() {
        return Boolean.TRUE.equals(TAINT.get());
    }

    // ------------------------------------------------------------------ AgentLoop 工具名：对外发送类

    /** 工具名像对外发送 / 分享 / 提交（sms.send、mail.reply、xx.share、conn.x.send_email…）；纯回答（ask.reply / chat.reply）不算。 */
    public static boolean outboundSign(String sign) {
        if (sign == null) return false;
        String s = sign.toLowerCase(Locale.ROOT);
        if (s.equals("ask.reply") || s.equals("chat.reply") || s.equals("clarify")) return false;
        return Pattern.compile("(^|[._])(send|share|reply|forward|post|publish|submit|send_?draft|send_?email|send_?message)([._]|$)").matcher(s).find();
    }

    // ------------------------------------------------------------------ 诚实回报：读不回的动作说「已请求」

    /**
     * 动作发出去了但没法读回确认（如系统闹钟接口设完读不到闹钟列表）：把「已设 / 已设置 / 已设定 / 已开」改成「已请求」，
     * 并补一句去哪里看。已经是「已请求」的原样返回。
     */
    public static String requested(String okText, String whereToCheck) {
        String t = okText == null ? "" : okText.trim();
        if (t.startsWith("已请求")) return t;
        String body = t.replaceFirst("^(已经|已)(设置|设定|设好|设|开启|开|打开|添加|加)", "");
        if (body.equals(t)) body = t;
        String verb = t.startsWith("已开") || t.startsWith("已经开") ? "开" : t.startsWith("已添加") || t.startsWith("已加") ? "加" : "设";
        String out = "已请求" + verb + body;
        if (whereToCheck != null && whereToCheck.length() > 0) out += "（" + whereToCheck + "）";
        return out;
    }
}
