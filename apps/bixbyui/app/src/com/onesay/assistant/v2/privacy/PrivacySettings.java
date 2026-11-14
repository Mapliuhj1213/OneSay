package com.onesay.assistant.v2.privacy;

import com.onesay.assistant.v2.kernel.FeatureSwitch;

/**
 * ★ borrow-1112：隐私相关开关。
 * <ul>
 *   <li>{@link #REDACT}：发给云端模型前本地把手机号 / 身份证号 / 银行卡号 / 邮箱 / 验证码换成占位符（默认开，个人版、开源版都开；
 *       kill 文件 privacy_redact.off）。</li>
 * </ul>
 */
public final class PrivacySettings {

    private PrivacySettings() {}

    public static final String PREFS = "bixby_privacy";
    public static final boolean REDACT_DEFAULT = true;
    public static final FeatureSwitch REDACT = new FeatureSwitch(PREFS, "redact_before_cloud", REDACT_DEFAULT, "privacy_redact.off");
}
