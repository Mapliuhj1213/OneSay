package com.onesay.assistant.v2.agenttools;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ arch/tool-tiers（A 路）：GUI 档（phone.operate）折叠 / 展开的判定 —— 纯函数，JVM 可测。
 *
 * <p>默认<b>折叠</b>：工具表里只给一个入口 {@code gui.unfold}（短说明），phone.operate 全文不常驻。
 * 下面任一条成立才<b>展开</b>（把 phone.operate 放进工具表）：
 * <ol>
 *   <li>{@link Reason#EXPLICIT_UI} 用户明确要点界面（「帮我点一下…」「往下滑」「替我操作」）；</li>
 *   <li>{@link Reason#TRIAGE_GUI} 分诊判为 GUI：办事类请求 + 点名在某个第三方 App 里做（「在小红书发条笔记」），
 *       且那个 App 不是设置 / 闹钟 / 地图 / 浏览器 / 商店 / 邮箱这类已有接口或深链的系统范围；</li>
 *   <li>{@link Reason#NO_UPPER_TIER} 前三档都不适用：候选里没有任何一个接口 / 查信息 / 深链工具达到强相关；</li>
 *   <li>运行中：{@link Reason#UPPER_TIERS_FAILED} 前三档试过失败（深链失败 1 次或其它失败累计 2 次），
 *       或 {@link Reason#ENTRY_CALLED} 模型调了入口 gui.unfold。</li>
 * </ol>
 */
public final class GuiFold {

    private GuiFold() {}

    public enum Reason {
        /** 折叠（默认）。 */
        FOLDED(false),
        EXPLICIT_UI(true),
        TRIAGE_GUI(true),
        NO_UPPER_TIER(true),
        UPPER_TIERS_FAILED(true),
        ENTRY_CALLED(true);

        public final boolean expanded;

        Reason(boolean expanded) {
            this.expanded = expanded;
        }
    }

    /** 用户明确要求在界面上操作。避开「几点」「一点」这类含「点」的普通词：只认成词的动作说法。 */
    static final Pattern EXPLICIT_UI = Pattern.compile(
            "点一下|点击|点开|点进|帮我点|替我点|给我点|点那个|点这个|点按|按一下|长按|"
                    + "往下滑|往上滑|向下滑|向上滑|下拉到|滑到|划到|翻到底|滑动|"
                    + "界面上|在界面|操作界面|点界面|屏幕上点|"
                    + "替我操作|帮我操作|代我操作|模拟点击|手动操作|一步一步");

    /**
     * 点名在某个 App 里做事：「在 / 去 / 用 / 进 / 打开 X 里 / 上 / 中」或「在 / 去 / 用 X + 动作」（「在小红书发…」「用微信给…」「去淘宝买…」）。
     * group(2) = App 名。
     */
    static final Pattern APP_SCOPED = Pattern.compile(
            "(?<![现实存正所好自不])(在|去|用|进入?|打开)\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,8}?)\\s*"
                    + "(里面|里|上面|上|中|内|app|APP|App|应用|给|发|买|搜|看|点赞|评论|下单|签到|领|关注|收藏|刷|抢|订|约|打卡|转账|付)");

    /** 不是 App 的地点 / 时间词（「在家里」「在公司」）。 */
    static final Pattern NOT_APP = Pattern.compile("^(我|你|他|她|给|家|公司|学校|单位|这|那|哪|今天|明天|晚上|早上|路|车)");

    /** 已有接口 / 深链的系统范围：这些 App 里的事不算「分诊判为 GUI」。 */
    static final Pattern SYSTEM_SCOPE = Pattern.compile(
            "设置|系统|手机|本机|闹钟|时钟|计时|日历|浏览器|地图|高德|百度地图|导航|商店|应用市场|Play|play|"
                    + "邮箱|邮件|相机|手电|通知栏|控制中心|快捷|屏幕|界面|网上|网页|网站");

    /** 办事类分诊标签（{@code TriageDecider.Label} 的名字）；null = 调用方没给分诊（JVM / 直测），按办事处理。 */
    static boolean isDoLabel(String triageLabel) {
        if (triageLabel == null) return true;
        return "DO_ACT".equals(triageLabel) || "DO_READ".equals(triageLabel) || "MIXED".equals(triageLabel);
    }

    public static boolean explicitUi(String query) {
        return query != null && EXPLICIT_UI.matcher(query).find();
    }

    /** 点名的第三方 App（不在系统范围里）；没有返回 null。 */
    public static String thirdPartyAppScope(String query) {
        if (query == null) return null;
        Matcher m = APP_SCOPED.matcher(query);
        while (m.find()) {
            String app = m.group(2);
            if (app == null || app.length() == 0) continue;
            if (SYSTEM_SCOPE.matcher(app).find() || NOT_APP.matcher(app).find()) continue;
            return app;
        }
        return null;
    }

    /**
     * ★ int/1102 常识：发微信 / QQ 消息没有接口（不能退化成短信），直接 GUI（phone.operate 在微信里操作，发送前确认卡）。
     * 「给老王发微信说我到了」「微信告诉老王…」「用qq给张三发个消息」。
     */
    static final Pattern IM_SEND = Pattern.compile(
            "(给|跟|和)[^，,。]{1,8}?(发|回)(个|条|一条)?(微信|qq|QQ)|(发|回)(个|条|一条)?(微信|qq|QQ)(给|跟)|(微信|qq|QQ)(上)?(告诉|通知|跟|给|发)");

    public static boolean imSend(String query) {
        return query != null && IM_SEND.matcher(query).find();
    }

    /** 分诊判为 GUI：办事类 + 点名在第三方 App 里做；或发微信 / QQ 消息。 */
    public static boolean triageGui(String query, String triageLabel) {
        return isDoLabel(triageLabel) && (thirdPartyAppScope(query) != null || imSend(query));
    }

    /**
     * 开局判定。
     *
     * @param query            用户原话
     * @param triageLabel      分诊标签名（可空）
     * @param upperTierStrong  候选里有没有强相关的前三档工具（接口 / 查信息 / 深链；不算常驻的探针与兜底链）
     */
    public static Reason initial(String query, String triageLabel, boolean upperTierStrong) {
        if (explicitUi(query)) return Reason.EXPLICIT_UI;
        if (triageGui(query, triageLabel)) return Reason.TRIAGE_GUI;
        if (!upperTierStrong) return Reason.NO_UPPER_TIER;
        return Reason.FOLDED;
    }

    /** 深链失败一次记 2 分，其它前三档失败记 1 分；满 {@link #FAIL_SCORE_TO_UNFOLD} 分自动展开。 */
    public static final int FAIL_SCORE_TO_UNFOLD = 2;

    public static int failWeight(ToolTier tier) {
        if (tier == null || tier == ToolTier.GUI) return 0;
        return tier == ToolTier.DEEPLINK ? 2 : 1;
    }
}
