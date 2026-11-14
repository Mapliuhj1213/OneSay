package com.onesay.assistant.v2.net;

import org.json.JSONObject;

/**
 * ★ borrow-1112：主模型出错时自动换备用模型（纯 Java + org.json，JVM 可测）。
 * <ul>
 *   <li>什么时候换：主模型<b>超时</b>（连接 / 首字节 / 总时长）、<b>5xx</b>、<b>429 限流</b>（{@link #shouldSwitch}）。
 *       401 / 402（Key 失效、余额不足）、400（请求本身不对）不换——换了也多半是同样的错，且应该让用户知道去修 Key；</li>
 *   <li>换到哪：用户在「设置 › 模型与密钥 › 备用模型」配置的那一个（{@link Source}）。<b>没配置 = 照旧报错</b>，行为与改前相同；</li>
 *   <li>换了之后：执行时间轴记一步「已切换到备用模型（名字）」（{@link com.onesay.assistant.v2.timeline.TimelineNotes}），请求日志记一行（不含 Key / 请求体）；
 *       备用也失败 → 返回主模型原来的错误（调用方照旧兜底）。备用客户端自己不会再换（不连环切）。</li>
 * </ul>
 * 思路借鉴涛声 TaoSheng（https://github.com/0xCoach/TaoSheng，MIT）的「主模型挂了按顺序换备用模型」；代码是自己写的。
 */
public final class LlmFallback {

    private LlmFallback() {}

    public static final String NOTE = "已切换到备用模型";

    /** 一个备用提供方（OpenAI 兼容的 chat/completions 端点）。 */
    public static final class Backup {
        public final String url, model, label;
        public final LlmClient.KeyProvider key;

        public Backup(String url, String model, String label, LlmClient.KeyProvider key) {
            this.url = url == null ? "" : url.trim();
            this.model = model == null ? "" : model.trim();
            this.label = label == null || label.trim().length() == 0 ? this.model : label.trim();
            this.key = key;
        }

        /** 地址合格（{@link #checkUrl}；http 只认本机回环，给测试 / 调试用）、模型名和 Key 都有。 */
        public boolean usable() {
            String k = key == null ? null : key.apiKey();
            return checkUrl(url, true) == null && model.length() > 0 && k != null && k.trim().length() > 0;
        }
    }

    /**
     * 备用模型地址校验（2026-11-14 对抗测试 P2-6：以前只看前缀，{@code http://127.0.0.1:1@evil.example/…} 的真实主机是 evil.example）。
     * 解析后判断：https；或 allowHttp 时 http 且主机<b>就是</b> 127.0.0.1 / localhost。不能带用户名密码（user:pw@）、? 查询串、# 片段、空白 / 不可见字符。
     * 合格返回 null，否则返回给人看的一句话。设置页用它拒收（http 只有 debug 包收），{@link Backup#usable} 用它兜底。
     */
    public static String checkUrl(String raw, boolean allowHttp) {
        String s = raw == null ? "" : raw.trim();
        if (s.length() == 0) return "没有地址";
        if (s.length() > 500) return "地址太长";
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch <= ' ' || ch == 0x7f || Character.isSpaceChar(ch) || Character.isWhitespace(ch) || com.onesay.assistant.v2.security.SafetyText.isInvisible(ch))
                return "地址里不能有空格或不可见字符";
        }
        java.net.URI u;
        try {
            u = new java.net.URI(s);
        } catch (Exception e) {
            return "地址格式不对";
        }
        String scheme = u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT);
        if (u.getRawUserInfo() != null || u.getRawAuthority() != null && u.getRawAuthority().indexOf('@') >= 0) return "地址里不能带用户名或密码";
        if (u.getRawQuery() != null || u.getRawFragment() != null || s.indexOf('?') >= 0 || s.indexOf('#') >= 0) return "地址里不能带 ? 或 # 后面的参数";
        String host = u.getHost() == null ? "" : u.getHost().toLowerCase(java.util.Locale.ROOT);
        if (host.length() == 0) return "地址里没有主机名";
        if (scheme.equals("https")) return null;
        if (scheme.equals("http") && allowHttp && (host.equals("127.0.0.1") || host.equals("localhost"))) return null;
        return allowHttp ? "地址要以 https:// 开头（http 只收本机 127.0.0.1 / localhost）" : "地址要以 https:// 开头";
    }

    public interface Source {
        /** 当前配置的备用；没配置 / 关了 → null。 */
        Backup current();
    }

    private static volatile Source sSource;

    public static void setSource(Source s) {
        sSource = s;
    }

    public static Backup current() {
        Source s = sSource;
        if (s == null) return null;
        try {
            Backup b = s.current();
            return b != null && b.usable() ? b : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 主模型这次的失败该不该换备用。 */
    public static boolean shouldSwitch(LlmResponse r) {
        if (r == null || r.ok()) return false;
        int c = r.httpCode;
        if (c == 429) return true;
        if (c >= 500 && c <= 599) return true;
        return c == -1 && isTimeout(r.error);
    }

    static boolean isTimeout(String err) {
        return err != null && (err.contains("超时") || err.contains("总时长") || err.contains("SocketTimeout"));
    }

    /** 失败原因的一句话（时间轴 / 日志用）。 */
    public static String why(LlmResponse r) {
        if (r == null) return "";
        if (r.httpCode == 429) return "主模型限流";
        if (r.httpCode >= 500) return "主模型服务出错（" + r.httpCode + "）";
        return "主模型超时";
    }

    /** 请求体换成备用模型：model 换掉；备用模型不是「默认开思考」的那一系就去掉 thinking 字段（别家不认）。 */
    public static String rewrite(String body, Backup b) {
        if (body == null || b == null) return body;
        try {
            JSONObject o = new JSONObject(body);
            o.put("model", b.model);
            if (!LlmModels.thinkingByDefault(b.model)) o.remove("thinking");
            return o.toString();
        } catch (Exception e) {
            return body;
        }
    }

    /** 主备是不是同一个（同地址同模型就不换了）。 */
    static boolean same(String primaryUrl, String body, Backup b) {
        if (!b.url.equals(primaryUrl)) return false;
        try {
            return b.model.equals(new JSONObject(body).optString("model", ""));
        } catch (Exception e) {
            return false;
        }
    }
}
