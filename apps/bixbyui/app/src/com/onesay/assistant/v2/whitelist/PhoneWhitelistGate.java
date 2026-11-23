package com.onesay.assistant.v2.whitelist;

import com.onesay.assistant.v2.phone.PhonePlan;
import com.onesay.assistant.v2.phone.RiskLabel;
import com.onesay.assistant.v2.phone.UiTree;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [app-whitelist-1116] 手机端把原有门禁的结论接到 {@link ActionPolicy}（与电脑同一套分层）：
 * <ul>
 *   <li>{@link Task}：一次手机操作任务（phone.operate）的状态 —— 读到过的隐私类别、输入过的隐私类别、白名单放行了几步；</li>
 *   <li>{@link #step}：每一步执行前，SensitiveGuard / RiskLabel / Mika 算出的确认原因（null = 原本不问）→ 最终原因（null = 不问）；</li>
 *   <li>{@link #capability}：AgentLoop 工具调用里带 package 参数的（打开 App 这类），ExecutionGate 原本要确认时能不能按白名单放行。</li>
 * </ul>
 * 只对前台 / 目标 App 在白名单里的起作用；不在白名单的，结论和改前完全一样。付款最后一下（userMustTap）、锁屏拒、ROOT 污点拒这些硬拒在调用方之前就判了，这里碰不到。
 */
public final class PhoneWhitelistGate {

    private PhoneWhitelistGate() {}

    /** 一次任务的状态。 */
    public static final class Task {
        public final Set<String> read = new LinkedHashSet<String>(), typed = new LinkedHashSet<String>();
        public int passes;
        public int limit = ActionPolicy.DEFAULT_PASS_LIMIT;
        /** 本次任务读过外部内容（PhoneAgent.tainted：浏览器 / 短信 / 邮件 / 通知栏，或 AgentLoop 传下来）。之后的外发白名单也不放行（ActionPolicy L3e）。 */
        public boolean external;
    }

    /** 付款类原因（SensitiveGuard「付款 / 下单」、模型自报 money、Mika「页面上有金额」）。 */
    static final Pattern PAY_WORDS = Pattern.compile("付款|支付|花钱|金额|下单|转账|充值|收银|购买");
    /** 密钥类（密码 / 验证码框、获取验证码、身份验证）：用户规则「密钥类不放行」，白名单放不松。 */
    static final Pattern SECRET_WORDS = Pattern.compile("密码|验证码|身份验证");
    /** 读过外部内容（网页 / 短信 / 通知）后的发送：防提示注入，白名单放不松。 */
    static final Pattern TAINT_WORDS = Pattern.compile("读过外部内容|读过网页");
    /** 卸载类（2026-11-16 用户定：卸载白名单里的应用必须确认）。 */
    static final Pattern UNINSTALL_WORDS = Pattern.compile("卸载|uninstall", Pattern.CASE_INSENSITIVE);
    /** 界面上像附件 / 图片预览的控件（发送时认不出内容 = 当隐私）。 */
    static final Pattern ATTACH = Pattern.compile("图片|照片|相册|截图|附件|文件|image|photo|picture|pic|thumb|attach|gallery", Pattern.CASE_INSENSITIVE);

    /** 读到一屏：把屏上的隐私类别记进任务（只记类别）。 */
    public static void noteScreen(Task t, UiTree screen) {
        if (t == null || screen == null) return;
        StringBuilder b = new StringBuilder();
        for (UiTree.El e : screen.els) {
            if (e.password) continue;
            if (b.length() > 20000) break;
            b.append(e.label).append('\n');
        }
        t.read.addAll(PrivacyScan.text(b.toString()));
        t.read.remove(PrivacyScan.PLACEHOLDER);
    }

    /** 判一步的结论（不改任何状态）。 */
    public static ActionPolicy.Decision decide(WhitelistState st, Task t, PhonePlan.Step s, UiTree cur, String sens) {
        ActionPolicy.Facts f = facts(st, t, s, cur, sens);
        return f == null ? null : ActionPolicy.decide(f);
    }

    static ActionPolicy.Facts facts(WhitelistState st, Task t, PhonePlan.Step s, UiTree cur, String sens) {
        if (st == null || t == null || s == null || cur == null) return null;
        String pkg = cur.pkg == null ? "" : cur.pkg;
        if (pkg.length() == 0 || !st.contains(AppWhitelist.ANDROID, pkg)) return null;
        ActionPolicy.Facts f = new ActionPolicy.Facts();
        f.platform = AppWhitelist.ANDROID;
        f.appId = pkg;
        f.whitelisted = true;
        f.whitelistBroken = st.broken();
        f.base = sens == null ? ActionPolicy.Verdict.ALLOW : ActionPolicy.Verdict.CONFIRM;
        f.baseReason = sens == null ? "" : sens;
        f.passesUsed = t.passes;
        f.passLimit = t.limit;
        String r = sens == null ? "" : sens;
        boolean wait = "wait".equals(s.op) || s.isWaitUntil() || "back".equals(s.op) || "scroll".equals(s.op) || "swipe".equals(s.op);
        boolean sendLike = RiskLabel.taintedSendLike(s, cur) != null;
        boolean typing = "type".equals(s.op);
        UiTree.El tgt = s.id >= 0 ? cur.find(s.id) : null;
        String tgtText = (s.target == null ? "" : s.target) + " " + (tgt == null ? "" : tgt.label + " " + tgt.rid);
        boolean taintSend = TAINT_WORDS.matcher(r).find();
        // 只有发送 / 分享 / 提交 / 回车提交算「外发」（和不在白名单时的原规则同一口径）；读过外部内容后单纯往框里打字不加问
        f.readExternal = taintSend || (t.external && sendLike);
        if (PAY_WORDS.matcher(r).find() || s.risk == RiskLabel.Risk.MONEY) f.kind = ActionPolicy.Kind.PAY;
        else if (!wait && !typing && UNINSTALL_WORDS.matcher(r + " " + tgtText).find()) f.kind = ActionPolicy.Kind.UNINSTALL;
        else if (typing || sendLike || taintSend) f.kind = ActionPolicy.Kind.OUTBOUND;
        else if (wait) f.kind = ActionPolicy.Kind.READ;
        else f.kind = ActionPolicy.Kind.OTHER;
        if (SECRET_WORDS.matcher(r).find()) f.suspect = "这一步要填密码 / 验证码或做身份验证（密钥类）";
        // 读过外部内容后的发送：走 ActionPolicy L3e（readExternal），白名单放不松（2026-11-16 用户定「保持」）
        if (typing) f.outPrivacy = PrivacyScan.text(s.text);
        else if (sendLike) {
            Set<String> out = new LinkedHashSet<String>(t.typed);
            boolean img = false;
            for (UiTree.El e : cur.els) {
                if (e.edit && !e.password) out.addAll(PrivacyScan.text(e.label));
                if (!img && !e.edit && ATTACH.matcher(e.rid + " " + e.label).find() && e.cls != null && e.cls.toLowerCase().contains("image")) img = true;
            }
            out.remove(PrivacyScan.DOC_WORD);
            if (img) out.addAll(PrivacyScan.image(null));
            f.outPrivacy = out;
        }
        f.readPrivacy = new LinkedHashSet<String>(t.read);
        return f;
    }

    /**
     * 判一步并落账：返回最终要给用户看的确认原因（null = 不用问）。白名单放行写审计、计次；白名单应用里因支付 / 隐私 / 读过隐私 / 认不准 / 次数上限
     * 没放行的也写审计。不在白名单：原样返回 sens。
     */
    public static String step(WhitelistState st, Task t, PhonePlan.Step s, UiTree cur, String sens) {
        ActionPolicy.Facts f = facts(st, t, s, cur, sens);
        if (f == null) {
            if (t != null && s != null && "type".equals(s.op)) t.typed.addAll(PrivacyScan.text(s.text));
            return sens;
        }
        ActionPolicy.Decision d = ActionPolicy.decide(f);
        if ("type".equals(s.op)) t.typed.addAll(PrivacyScan.text(s.text));
        if (d.layer == ActionPolicy.Layer.DEFAULT || d.layer == ActionPolicy.Layer.HARD) return sens;
        st.audit(f, d, s.op);
        if (d.whitelistPass) {
            t.passes++;
            return null;
        }
        if (d.verdict == ActionPolicy.Verdict.ALLOW) return null;
        return sens == null ? d.text : sens + "；" + d.text;
    }

    /**
     * ExecutionGate：原本要确认（REQUIRE_CONFIRM）的工具调用，目标 App（参数 package / pkg）在白名单里、不是对外发送 / ROOT / 破坏性 / 红卡 /
     * 通信类 / 远程工具、参数来源可信（没污点）、参数里没有隐私 → 放行。其余一律 false（照原规则确认）。
     */
    public static boolean capability(WhitelistState st, String sign, String sideEffect, String pkg, String argsText, boolean tainted, boolean excluded) {
        if (st == null || pkg == null || pkg.length() == 0 || tainted || excluded) return false;
        if ("ROOT".equals(sideEffect) || "DESTRUCTIVE".equals(sideEffect) || "PUBLIC_COMMS".equals(sideEffect)) return false;
        if (!st.contains(AppWhitelist.ANDROID, pkg)) return false;
        boolean uninstall = sign != null && UNINSTALL_WORDS.matcher(sign).find();
        ActionPolicy.Facts f = new ActionPolicy.Facts();
        f.platform = AppWhitelist.ANDROID;
        f.appId = pkg;
        f.whitelisted = true;
        f.base = ActionPolicy.Verdict.CONFIRM;
        f.kind = ActionPolicy.Kind.OTHER;
        f.outPrivacy = PrivacyScan.text(argsText);
        if (!f.outPrivacy.isEmpty()) f.kind = ActionPolicy.Kind.OUTBOUND;
        if (uninstall) f.kind = ActionPolicy.Kind.UNINSTALL; // 卸载白名单 App：照样确认（本来也是红卡）
        f.passLimit = Integer.MAX_VALUE;
        ActionPolicy.Decision d = ActionPolicy.decide(f);
        if (d.layer != ActionPolicy.Layer.DEFAULT) st.audit(f, d, sign);
        return d.whitelistPass;
    }
}
