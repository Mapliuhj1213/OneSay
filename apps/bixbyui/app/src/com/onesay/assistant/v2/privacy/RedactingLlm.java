package com.onesay.assistant.v2.privacy;

import com.onesay.assistant.v2.router.AgentLoop;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ★ borrow-1112：AgentLoop 的大模型调用外面包一层本地脱敏（纯 Java + org.json，JVM 可测）。
 *
 * <ul>
 *   <li>发出去：messages 深拷贝一份，每条的 content（字符串或 [{text}] 数组）和 assistant 的 tool_calls 参数过 {@link Redactor#redact}；
 *       原数组不改（AgentLoop 自己的历史里仍是真实值）；</li>
 *   <li>收回来：模型返回的 content 和每个 tool_call 的 arguments 过 {@link Redactor#restore}——要输入 / 拨号 / 发短信时用的是真实值；</li>
 *   <li>任务内有效：AgentLoop 一次 run 用同一个 messages 数组（按对象认，不按内容），换了数组（新任务 / research 子任务）就换一个新的 Redactor；
 *       最多同时记 {@link #KEEP} 个任务，最老的清掉（映射只在内存）；</li>
 *   <li>开关关（{@link Gate#on} = false）= 原样直通。</li>
 * </ul>
 */
public final class RedactingLlm implements AgentLoop.Llm {

    public interface Gate {
        boolean on();
    }

    private final AgentLoop.Llm mInner;
    private final Gate mGate;
    /** 同时可能有几个任务在跑（前台 + 定时任务）：按 messages 数组本身（同一对象）各记一个，最多留 {@link #KEEP} 个。 */
    private final java.util.LinkedList<Object[]> mSessions = new java.util.LinkedList<Object[]>();
    static final int KEEP = 4;
    private Redactor mRedactor;

    public RedactingLlm(AgentLoop.Llm inner, Gate gate) {
        mInner = inner;
        mGate = gate;
    }

    @Override public boolean hasApiKey() {
        return mInner.hasApiKey();
    }

    @Override public JSONObject call(JSONArray messages, JSONArray tools, int maxTokens) {
        boolean on;
        try {
            on = mGate == null || mGate.on();
        } catch (Throwable t) {
            on = true;
        }
        if (!on || messages == null) return mInner.call(messages, tools, maxTokens);
        Redactor r;
        synchronized (this) {
            r = null;
            for (Object[] e : mSessions) if (e[0] == messages) r = (Redactor) e[1];
            if (r == null) {
                r = new Redactor();
                mSessions.addFirst(new Object[]{messages, r});
                while (mSessions.size() > KEEP) {
                    Object[] old = mSessions.removeLast();
                    ((Redactor) old[1]).clear();
                }
            }
            mRedactor = r;
        }
        JSONArray out = redactMessages(messages, r);
        JSONObject msg = mInner.call(out, tools, maxTokens);
        return restoreMessage(msg, r);
    }

    /** 当前任务的 Redactor（测试用）。 */
    public synchronized Redactor current() {
        return mRedactor;
    }

    public static JSONArray redactMessages(JSONArray messages, Redactor r) {
        JSONArray out = new JSONArray();
        for (int i = 0; i < messages.length(); i++) {
            Object o = messages.opt(i);
            if (!(o instanceof JSONObject)) {
                out.put(o);
                continue;
            }
            try {
                JSONObject m = new JSONObject(((JSONObject) o).toString());
                Object c = m.opt("content");
                if (c instanceof String) m.put("content", r.redact((String) c));
                else if (c instanceof JSONArray) {
                    JSONArray arr = (JSONArray) c;
                    for (int k = 0; k < arr.length(); k++) {
                        JSONObject part = arr.optJSONObject(k);
                        if (part != null && part.opt("text") instanceof String) part.put("text", r.redact(part.optString("text")));
                    }
                }
                JSONArray tc = m.optJSONArray("tool_calls");
                if (tc != null) {
                    for (int k = 0; k < tc.length(); k++) {
                        JSONObject fn = tc.optJSONObject(k) == null ? null : tc.optJSONObject(k).optJSONObject("function");
                        if (fn != null && fn.opt("arguments") instanceof String) fn.put("arguments", r.redact(fn.optString("arguments")));
                    }
                }
                out.put(m);
            } catch (Exception e) {
                out.put(o);
            }
        }
        return out;
    }

    public static JSONObject restoreMessage(JSONObject msg, Redactor r) {
        if (msg == null || r == null || r.size() == 0) return msg;
        try {
            Object c = msg.opt("content");
            if (c instanceof String) msg.put("content", r.restore((String) c));
            JSONArray tc = msg.optJSONArray("tool_calls");
            if (tc != null) {
                for (int k = 0; k < tc.length(); k++) {
                    JSONObject fn = tc.optJSONObject(k) == null ? null : tc.optJSONObject(k).optJSONObject("function");
                    if (fn != null && fn.opt("arguments") instanceof String) fn.put("arguments", restoreJsonString(fn.optString("arguments"), r));
                }
            }
        } catch (Exception ignored) {
        }
        return msg;
    }

    /** arguments 是 JSON 字符串：真实值里可能有引号以外的字符，直接文本替换即可（值只含数字 / 字母 / @ . + - 空格）。 */
    static String restoreJsonString(String args, Redactor r) {
        return r.restore(args);
    }
}
