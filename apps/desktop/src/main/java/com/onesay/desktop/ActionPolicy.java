package com.onesay.desktop;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * [app-whitelist-1116] 统一分层策略（纯 Java，手机个人版 / 手机开源版 / 电脑端三份除 package 行外逐字相同）。
 *
 * <p>各端原来的门禁（手机 ExecutionGate / SensitiveGuard / RiskLabel，电脑 LinkPolicy.decide / PcGuard）先算出<b>原结论</b>
 * （{@link Facts#base}），这里再按固定顺序过五层，<b>只有第 4 层能放松</b>，其余只会加严：
 * <ol>
 *   <li><b>L1 硬拒</b>：原结论是拒绝 / 只生成脚本（付款最后一下、自授权 / 自批准、不提权、「从不询问」档、可疑命令硬拒、密钥类…）→ 原样返回。</li>
 *   <li><b>L2 支付 / 隐私外发必确认</b>：这一步是付款，或要把隐私资料（身份证、银行卡、住址、真名、手机号、邮箱，文字或图片；
 *       认不出的图片也算）发送 / 粘贴 / 上传 / 填写出去 → 确认，不能「以后不用问」。「从不询问」档下拒绝。</li>
 *   <li><b>L3 读过隐私后外发确认</b>（strands-agents/box 策略引用历史的思路）：本次任务（电脑端 = 本次会话窗口）读过隐私资料，这一步是外发 → 确认，不能记住。</li>
 *   <li><b>L3e 读过外部内容后外发</b>（2026-11-16 用户定「保持」）：本次任务读过网页 / 短信 / 通知 / 下载内容等外部文字（{@link Facts#readExternal}），这一步是外发 → 确认，不能记住（防被里面的文字指使）。</li>
 *   <li><b>L3b 认不准</b>：写法可疑 / 守卫认不准（{@link Facts#suspect}）→ 确认，不能记住（用户规则：认不准的弹确认）。</li>
 *   <li><b>L3u 卸载</b>（2026-11-16 用户定）：卸载白名单里的应用 / 程序（{@link Kind#UNINSTALL}）→ 确认，不能记住；「从不询问」档下拒绝。</li>
 *   <li><b>L4 白名单放行</b>：目标应用在白名单里、原结论是「要确认」→ 放行并写审计。本次任务白名单放行次数超过 {@link Facts#passLimit} 后不再放行（次数上限）。</li>
 *   <li><b>L5 默认</b>：原结论（放行 / 确认）照旧。</li>
 * </ol>
 * L2 / L3 / L3e / L3u / L4 只对白名单应用起作用：不在白名单的动作，结论和改前完全一样（L1 / L3b / L5 本来就是各端原有规则）。
 * 白名单读坏了 / 读不出（{@link Facts#whitelistBroken}）→ 当不在白名单。
 */
public final class ActionPolicy {

    private ActionPolicy() {}

    public enum Verdict { ALLOW, CONFIRM, DENY, SCRIPT }

    /** 这条结论是哪一层定的（审计日志 / 测试用）。 */
    public enum Layer { HARD, PAY_PRIVACY, READ_PRIVACY, READ_EXTERNAL, SUSPECT, UNINSTALL, WHITELIST, DEFAULT }

    /** 动作是哪一类（只区分策略需要的几种；其余都是 OTHER）。 */
    public enum Kind {
        /** 付款 / 下单 / 转账（含进收银台后的每一步、确认金额）。 */
        PAY,
        /** 外发：发送 / 分享 / 上传 / 粘贴 / 往输入框填写 / 提交 / 写剪贴板 / 联网命令。 */
        OUTBOUND,
        /** 其他有副作用的：删除、登录、改设置、打开、关掉… */
        OTHER,
        /** 只读：读屏、查询、列表。 */
        READ,
        /** 卸载应用 / 程序（白名单也要确认，2026-11-16 用户定）。 */
        UNINSTALL
    }

    public static final class Facts {
        /** 原来门禁的结论（必填）。 */
        public Verdict base = Verdict.CONFIRM;
        /** 原结论的原因（原样透传）。 */
        public String baseReason = "";
        public Kind kind = Kind.OTHER;
        /** 这一步要发出去的内容里认出的隐私类别（{@link PrivacyScan#text} / {@link PrivacyScan#image}）。 */
        public Set<String> outPrivacy = new LinkedHashSet<String>();
        /** 本次任务 / 会话已经读到过的隐私类别。 */
        public Set<String> readPrivacy = new LinkedHashSet<String>();
        /** 本次任务 / 会话读过外部内容（网页、短信、邮件、通知、下载的文件…）：之后的外发白名单也不放行。 */
        public boolean readExternal;
        /** 写法可疑 / 守卫认不准的原因（非空 = 可疑）。 */
        public String suspect = "";
        /** 目标应用（android 包名 / windows exe 名），平台。 */
        public String platform = "", appId = "";
        public boolean whitelisted, whitelistBroken;
        /** 审批策略是「从不询问」（需要确认的一律拒绝）。 */
        public boolean neverAsk;
        /** 本次任务已经按白名单放行了几次；上限。 */
        public int passesUsed, passLimit = DEFAULT_PASS_LIMIT;
    }

    /** 一次任务里白名单最多放行多少步（之后照原规则问）。 */
    public static final int DEFAULT_PASS_LIMIT = 40;

    public static final class Decision {
        public final Verdict verdict;
        public final Layer layer;
        /** 给人看的一句话（确认卡正文）；不含隐私原文，只含类别。 */
        public final String text;
        /** 这张确认卡能不能给「以后不用问」（L2 / L3 / L3e / L3b / L3u 永远不能）。 */
        public final boolean rememberable;
        /** 按白名单放行的（调用方要写审计、计数）。 */
        public final boolean whitelistPass;

        Decision(Verdict v, Layer l, String text, boolean rememberable, boolean whitelistPass) {
            this.verdict = v;
            this.layer = l;
            this.text = text == null ? "" : text;
            this.rememberable = rememberable;
            this.whitelistPass = whitelistPass;
        }

        @Override public String toString() { return verdict + "/" + layer + (whitelistPass ? "/wl" : ""); }
    }

    public static Decision decide(Facts f) {
        if (f == null) f = new Facts();
        Verdict base = f.base == null ? Verdict.CONFIRM : f.base;
        // L1 硬拒：任何东西都放不松
        if (base == Verdict.DENY || base == Verdict.SCRIPT) return new Decision(base, Layer.HARD, f.baseReason, false, false);
        boolean wl = f.whitelisted && !f.whitelistBroken && f.appId != null && f.appId.length() > 0;
        // L2 / L3 只在白名单应用上起作用：不在白名单的，结论和改前完全一样（原来要确认的照旧、能不能「以后不用问」也照旧）
        boolean inPlay = wl;
        // L2 支付 / 隐私外发
        if (inPlay && f.kind == Kind.PAY) return ask(f, Layer.PAY_PRIVACY, "这一步和付款有关，要你确认（白名单也不放行）");
        boolean out = f.kind == Kind.OUTBOUND;
        if (inPlay && out && f.outPrivacy != null && !f.outPrivacy.isEmpty())
            return ask(f, Layer.PAY_PRIVACY, "这一步会把隐私资料（" + PrivacyScan.zh(f.outPrivacy) + "）发出去，要你确认（白名单也不放行）");
        // L3 读过隐私后外发
        if (inPlay && out && f.readPrivacy != null && !f.readPrivacy.isEmpty())
            return ask(f, Layer.READ_PRIVACY, "本次任务读到过隐私资料（" + PrivacyScan.zh(f.readPrivacy) + "），这一步要往外发，先问你");
        // L3e 读过外部内容后外发（防提示注入）
        if (inPlay && out && f.readExternal)
            return ask(f, Layer.READ_EXTERNAL, "本次任务读过网页 / 短信 / 通知等外部内容，这一步要往外发，先问你（防被里面的文字指使）");
        // L3b 认不准
        if (f.suspect != null && f.suspect.trim().length() > 0)
            return ask(f, Layer.SUSPECT, f.suspect.trim() + "，守卫认不准，先问你（这类不能记住）");
        // L3u 卸载白名单里的应用
        if (inPlay && f.kind == Kind.UNINSTALL) return ask(f, Layer.UNINSTALL, "要卸载白名单里的应用，先问你（白名单也不放行卸载）");
        // L4 白名单
        if (base == Verdict.CONFIRM && wl) {
            if (f.passesUsed < f.passLimit) return new Decision(Verdict.ALLOW, Layer.WHITELIST, "白名单应用，不用确认", false, true);
            return new Decision(Verdict.CONFIRM, Layer.WHITELIST, "白名单本次已放行 " + f.passLimit + " 步，后面的照常问你" + tail(f.baseReason), false, false);
        }
        // L5 默认
        return new Decision(base, Layer.DEFAULT, f.baseReason, base == Verdict.CONFIRM, false);
    }

    private static Decision ask(Facts f, Layer l, String text) {
        if (f.neverAsk) return new Decision(Verdict.DENY, l, "审批策略是「从不询问」，需要确认的事直接拒绝：" + text, false, false);
        return new Decision(Verdict.CONFIRM, l, text, false, false);
    }

    private static String tail(String r) {
        return r == null || r.length() == 0 ? "" : "：" + r;
    }

    /**
     * 审计日志一行（两端同一格式）：时间、端、平台、应用 id、层、结论、动作摘要。动作摘要只能是调用方给的<b>动作类型</b>
     * （如 tap / type / sys_exec），不放内容；最后再过一遍 {@link PrivacyScan#scrub}，邮箱 / 号码 / 长数字一律打码。
     */
    public static String auditLine(long at, String side, Facts f, Decision d, String action) {
        StringBuilder b = new StringBuilder();
        b.append(at).append('\t').append(side == null ? "" : side).append('\t').append(f == null ? "" : f.platform).append(':')
                .append(f == null ? "" : AppWhitelist.clip(f.appId == null ? "" : f.appId, 100)).append('\t')
                .append(d == null ? "" : d.layer).append('\t').append(d == null ? "" : d.verdict).append('\t')
                .append(action == null ? "" : AppWhitelist.clip(action, 40));
        if (f != null && f.outPrivacy != null && !f.outPrivacy.isEmpty()) b.append("\tout=").append(join(f.outPrivacy));
        if (f != null && f.readPrivacy != null && !f.readPrivacy.isEmpty()) b.append("\tread=").append(join(f.readPrivacy));
        if (f != null && f.readExternal) b.append("\text=1");
        return PrivacyScan.scrub(b.toString().replace('\n', ' ').replace('\r', ' '));
    }

    static String join(Set<String> s) {
        StringBuilder b = new StringBuilder();
        for (String x : s) {
            if (b.length() > 0) b.append(',');
            b.append(x);
        }
        return b.toString();
    }
}
