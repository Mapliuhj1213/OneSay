package com.onesay.assistant.v2.net;

import java.net.URI;
import java.util.Locale;

/**
 * 用户自填服务地址的校验（纯 Java）。只收加密地址：{@code https://} 或 {@code wss://}（按调用方要求），
 * 必须有主机名，不允许用户信息（{@code user@host}）和片段；查询串按调用方决定是否允许。
 */
public final class UserEndpoint {
    private UserEndpoint() {}

    /**
     * @param in          用户输入
     * @param scheme      "https" 或 "wss"
     * @param allowQuery  是否允许 {@code ?a=b}
     * @return 规范化后的地址（去首尾空白）；不合法返回 null
     */
    public static String normalize(String in, String scheme, boolean allowQuery) {
        if (in == null) return null;
        String s = in.trim();
        if (s.length() == 0 || s.length() > 512) return null;
        try {
            URI u = new URI(s);
            if (u.getScheme() == null || !u.getScheme().toLowerCase(Locale.ROOT).equals(scheme)) return null;
            if (u.getHost() == null || u.getHost().length() == 0) return null;
            if (u.getRawUserInfo() != null || u.getRawFragment() != null) return null;
            if (!allowQuery && u.getRawQuery() != null) return null;
        } catch (Exception e) {
            return null;
        }
        return s;
    }

    /** 模型名 / 语言代码这类短标识：字母数字和 . _ - ，1~64 位。 */
    public static boolean validId(String s) {
        return s != null && s.matches("[A-Za-z0-9][A-Za-z0-9._\\-]{0,63}");
    }

    /** 在地址后追加查询参数（已有 ? 用 &）。 */
    public static String withParam(String base, String key, String value) {
        if (value == null || value.length() == 0) return base;
        return base + (base.indexOf('?') >= 0 ? "&" : "?") + key + "=" + value;
    }
}
