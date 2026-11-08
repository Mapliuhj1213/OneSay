/*
 * Copyright 2026 OneSay
 * Portions derived from VYRSE (https://github.com/felixthosharon/vyrse, reference commit 41c5651),
 * Copyright (c) 2026 VYRSE contributors, MIT License (full text: THIRD_PARTY_NOTICES.md).
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); see LICENSE.
 *
 * 改动声明（OneSay，2026-11-08）：VYRSE 手机端 VyrseLinkService.java / PhoneActions.java / PhoneControl.java
 * 与电脑端 desktop/src/main/phone-bridge.ts 的协议常量、动作语义、结果格式按原样对齐，代码为 Java 7 重写；
 * 只实现动作白名单，完整操控（tap / type …）不实现。
 */
package com.onesay.assistant.plug.vyrse.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * [vyrse 兼容 core] 永远不替电脑操作的 App：支付、银行、钱包、密码管理、应用安装器、本 App 自己。
 * 按包名（VYRSE PhoneControl.BLOCKED 原表 + 国内常见支付 / 银行 / 密码管理包名）<b>加</b>按名字关键词兜底
 * （包名列不全时，名字里带「银行 / 支付 / 钱包 / 密码 / Pay / Bank / Wallet…」的也挡）。挡错只会少开一个 App。
 * 这些 App 的通知也不交给电脑（里面常有余额、验证码）。
 */
public final class AppGuard {

    private AppGuard() {}

    static final Set<String> BLOCKED = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            // —— VYRSE PhoneControl.BLOCKED（MIT）原表 ——
            "com.google.android.apps.nbu.paisa.user", "com.phonepe.app", "net.one97.paytm", "in.org.npci.upiapp",
            "in.amazon.mShop.android.shopping", "com.mobikwik_new", "com.freecharge.android", "com.whatsapp.w4b.payments",
            "com.sbi.lotusintouch", "com.sbi.SBIFreedomPlus", "com.csam.icici.bank.imobile", "com.snapwork.hdfc",
            "com.axis.mobile", "com.msf.kbank.mobile", "com.bankofbaroda.mconnect", "com.google.android.apps.walletnfcrel",
            "com.paypal.android.p2pmobile", "com.x8bit.bitwarden", "com.lastpass.lpandroid", "com.agilebits.onepassword",
            "com.google.android.packageinstaller", "com.android.packageinstaller", "com.android.vending",
            // —— 国内支付 / 银行 / 金融 ——
            "com.eg.android.AlipayGphone", "com.unionpay", "com.jd.jrapp", "com.icbc", "com.chinamworld.main",
            "com.android.bankabc", "com.chinamworld.bocmbci", "cmb.pb", "com.cmbchina.ccd.pluto.cmbActivity", "com.bankcomm.Bankcomm",
            "com.ecitic.bank.mobile", "com.cebbank.mobile.cemb", "com.yitong.mbank.psbc", "cn.com.spdb.mobilebank.per",
            "com.cib.cibmb", "com.pingan.paces.ccms", "cn.com.cmbc.newmbank", "com.hxb.mobile.client", "com.webank.wemoney",
            "com.tencent.wetype.pay", "com.alipay.mobile.android.tinyapp", "com.sankuai.meituan.pay", "com.jdjr.paymentcode",
            "com.ss.android.caijing.stock", "com.huawei.wallet", "com.mipay.wallet", "com.coloros.wallet", "com.vivo.wallet",
            "com.samsung.android.spay", "com.samsung.android.samsungpay.gear",
            // —— 密码管理 ——
            "keepass2android.keepass2android", "com.kunzisoft.keepass.free", "com.kunzisoft.keepass.libre",
            "com.dashlane", "com.callpod.android_apps.keeper", "proton.android.pass", "com.enpass.app",
            // —— 安装器 / 应用商店 ——
            "com.google.android.packageinstaller", "com.miui.packageinstaller", "com.samsung.android.packageinstaller",
            "com.huawei.appmarket", "com.xiaomi.market", "com.heytap.market", "com.bbk.appstore", "com.sec.android.app.samsungapps"
    )));

    /** 名字兜底：银行 / 支付 / 钱包 / 理财 / 证券 / 密码 / 安装器。 */
    static final Pattern NAME = Pattern.compile(
            "银行|支付|钱包|付款|收款|理财|证券|信用卡|云闪付|密码|保险箱|安装器|应用商店|应用市场"
                    + "|\\bbank|\\bpay\\b|pay$|\\bwallet|password|\\bvault\\b|keepass|authenticator|\\binstaller\\b",
            Pattern.CASE_INSENSITIVE);

    /** 包名前缀兜底（各家银行常见前缀）。 */
    static final String[] PREFIXES = {"com.bank", "cn.com.bank", "com.icbc", "com.ccb", "com.boc", "com.abchina", "com.cmbchina"};

    public static boolean blocked(String pkg, String label, String selfPkg) {
        if (pkg == null || pkg.length() == 0) return true;
        if (pkg.equals(selfPkg)) return true;
        if (BLOCKED.contains(pkg)) return true;
        String lp = pkg.toLowerCase(Locale.ROOT);
        for (String p : PREFIXES) if (lp.startsWith(p)) return true;
        if (lp.contains("bank") || lp.contains("wallet") || lp.contains("password") || lp.contains("packageinstaller")) return true;
        return label != null && NAME.matcher(label).find();
    }
}
