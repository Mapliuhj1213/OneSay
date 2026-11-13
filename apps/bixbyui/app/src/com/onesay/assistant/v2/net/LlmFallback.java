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

        /** 地址是 https（本机回环地址只给测试用）、模型名和 Key 都有。 */
        public boolean usable() {
            String k = key == null ? null : key.apiKey();
            return (url.startsWith("https://") || url.startsWith("http://127.0.0.1:")) && model.length() > 0 && k != null && k.trim().length() > 0;
        }
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
