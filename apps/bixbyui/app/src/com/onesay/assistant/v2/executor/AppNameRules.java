package com.onesay.assistant.v2.executor;

import java.util.regex.Pattern;

/**
 * [executor] ★ R11 打开应用的名字规则（纯 Java，JVM 测）：自家别名、去掉「设置」后缀。
 * 输入都是 {@link IntentExecutor#normalize} 之后的串（小写、去空格标点）。
 */
public final class AppNameRules {

    private AppNameRules() {}

    /** 自家控制台的叫法。 */
    static final Pattern SELF = Pattern.compile("^(onesay|onesay助手|onesay控制台|语音助手|助手|小助手|控制台|助手控制台|助手设置)$");
    static final Pattern SETTINGS_SUFFIX = Pattern.compile("^(.+?)(的)?(设置|设定|settings|setting|设置页|设置界面|设置页面)$");

    public static boolean isSelfAlias(String key) {
        return key != null && SELF.matcher(key).matches();
    }

    /** 「bixby设置」→「bixby」；「微信的设置」→「微信」；不是「X设置」或 X 为空 → null。 */
    public static String stripSettingsSuffix(String key) {
        if (key == null) return null;
        java.util.regex.Matcher m = SETTINGS_SUFFIX.matcher(key);
        if (!m.matches()) return null;
        String r = m.group(1);
        return r == null || r.length() == 0 ? null : r;
    }
}
