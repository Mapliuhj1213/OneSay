/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes.shot;

import com.onesay.assistant.plug.suggest.core.SuggestDefaults;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 私密 App 内置默认名单（§6.3-8）：银行与支付、支付宝、云闪付、密码管理器、三星安全文件夹 / Pass / Pay。用户可在控制台增删。
 * 包名按公开渠道常见包名列出，<b>上机按 pm list packages 核实</b>（列错只会少挡，不会误挡别的 App）。
 * D3：微信默认<b>不</b>进名单（{@link SuggestDefaults#WECHAT_PRIVATE}）。宿主另把自己的包名（控制台）加进来。
 */
public final class PrivateApps {
    private PrivateApps() {
    }

    public static final String WECHAT = "com.tencent.mm";

    static final String[] BUILTIN = {
            // 支付
            "com.eg.android.AlipayGphone", "com.unionpay", "com.jd.jrapp", "com.samsung.android.spay", "com.samsung.android.samsungpass",
            // 银行
            "com.icbc", "com.chinamworld.main", "com.android.bankabc", "com.chinamworld.bocmbci", "cmb.pb", "com.bankcomm.Bankcomm",
            "com.ecitic.bank.mobile", "com.cebbank.mobile.cemb", "com.yitong.mbank.psbc", "cn.com.spdb.mobilebank.per", "com.cib.cibmb",
            "com.pingan.paces.ccms", "cn.com.cmbc.newmbank", "com.hxb.mobile.client", "com.webank.wemoney",
            // 密码管理器
            "com.x8bit.bitwarden", "com.agilebits.onepassword", "com.lastpass.lpandroid", "keepass2android.keepass2android",
            "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre",
            // 三星安全文件夹
            "com.samsung.knox.securefolder",
    };

    public static Set<String> defaults() {
        Set<String> s = new LinkedHashSet<String>(Arrays.asList(BUILTIN));
        if (SuggestDefaults.WECHAT_PRIVATE) s.add(WECHAT);
        return Collections.unmodifiableSet(s);
    }
}
