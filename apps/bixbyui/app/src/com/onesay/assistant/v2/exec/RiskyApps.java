package com.onesay.assistant.v2.exec;

/**
 * [fix-1123-dexec B6] 代操作要拉起的 App 是不是「高风险」（能替用户外发消息、打电话、付钱）——纯逻辑，JVM 可测。
 *
 * <p>真机：删记忆时智能体「打开应用找长期记忆」直接拉起了微信，没有任何确认。规定：代操作打开聊天 / 短信 / 电话 / 支付 / 银行类 App
 * 必须先过确认卡（用户点允许才开）。按包名精确表 + 包名关键词两层判，宁可多问。</p>
 */
public final class RiskyApps {

    private RiskyApps() {}

    /** 精确包名 → 类别（用户能看懂的大白话）。 */
    private static final String[][] EXACT = {
            {"com.tencent.mm", "聊天"},
            {"com.tencent.mobileqq", "聊天"},
            {"com.tencent.tim", "聊天"},
            {"com.tencent.wework", "聊天"},
            {"com.alibaba.android.rimet", "聊天"},
            {"com.ss.android.lark", "聊天"},
            {"org.telegram.messenger", "聊天"},
            {"com.whatsapp", "聊天"},
            {"com.android.mms", "短信"},
            {"com.google.android.apps.messaging", "短信"},
            {"com.samsung.android.messaging", "短信"},
            {"com.android.messaging", "短信"},
            {"com.meizu.mms", "短信"},
            {"com.android.dialer", "电话"},
            {"com.google.android.dialer", "电话"},
            {"com.samsung.android.dialer", "电话"},
            {"com.android.contacts", "电话"},
            {"com.android.incallui", "电话"},
            {"com.android.server.telecom", "电话"},
            {"com.eg.android.AlipayGphone", "支付"},
            {"com.unionpay", "支付"},
            {"com.jd.jrapp", "支付"},
            {"com.chinamworld.main", "银行"},
            {"com.icbc", "银行"},
            {"cmb.pb", "银行"},
            {"com.android.bankabc", "银行"},
            {"com.chinamworld.bocmbci", "银行"},
            {"com.ecitic.bank.mobile", "银行"},
            {"com.yitong.mbank.psbc", "银行"},
            {"com.bankcomm.Bankcomm", "银行"},
            {"com.cib.cibmb", "银行"},
            {"com.pingan.paces.ccms", "银行"},
            {"com.huawei.wallet", "支付"},
            {"com.mipay.wallet", "支付"},
            {"com.samsung.android.spay", "支付"},
            {"com.meizu.flyme.wallet", "支付"},
    };

    /** 包名片段（小写比对）→ 类别。 */
    private static final String[][] PARTS = {
            {"bank", "银行"}, {"wallet", "支付"}, {"alipay", "支付"}, {"pay.", "支付"}, {".pay", "支付"},
            {"mms", "短信"}, {"messaging", "短信"}, {"dialer", "电话"}, {"incall", "电话"},
    };

    /** App 名片段 → 类别（模型 launch 时偶尔写名字）。 */
    private static final String[][] LABELS = {
            {"微信", "聊天"}, {"企业微信", "聊天"}, {"钉钉", "聊天"}, {"飞书", "聊天"},
            {"短信", "短信"}, {"信息", "短信"}, {"电话", "电话"}, {"拨号", "电话"}, {"联系人", "电话"},
            {"支付宝", "支付"}, {"云闪付", "支付"}, {"钱包", "支付"}, {"银行", "银行"},
    };

    /** 高风险就返回类别（聊天 / 短信 / 电话 / 支付 / 银行），否则 null。 */
    public static String kind(String pkg) {
        if (pkg == null) return null;
        String p = pkg.trim();
        if (p.length() == 0) return null;
        for (String[] e : EXACT) if (e[0].equals(p)) return e[1];
        String lo = p.toLowerCase(java.util.Locale.ROOT);
        for (String[] e : EXACT) if (e[0].toLowerCase(java.util.Locale.ROOT).equals(lo)) return e[1];
        for (String[] e : PARTS) if (lo.contains(e[0])) return e[1];
        if (lo.equals("qq") || lo.equals("tim")) return "聊天";
        for (String[] e : LABELS) if (p.contains(e[0])) return e[1];   // 模型写的是 App 名而不是包名
        return null;
    }

    public static boolean risky(String pkg) {
        return kind(pkg) != null;
    }

    /** 确认卡标题 / 正文。label = App 名（没有就用「这个应用」）。 */
    public static String confirmTitle(String kind) {
        return "要打开" + (kind == null ? "这个" : kind) + "应用吗";
    }

    public static String confirmDetail(String label, String kind) {
        String name = label == null || label.trim().length() == 0 ? "这个应用" : "「" + label.trim() + "」";
        return "我要打开" + name + "，它能" + what(kind) + "。你点允许我才打开；不允许我就停在这儿。";
    }

    static String what(String kind) {
        if ("聊天".equals(kind)) return "替你发消息";
        if ("短信".equals(kind)) return "替你发短信";
        if ("电话".equals(kind)) return "替你打电话";
        return "动到你的钱";
    }
}
