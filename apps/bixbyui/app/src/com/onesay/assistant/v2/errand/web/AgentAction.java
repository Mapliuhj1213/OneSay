package com.onesay.assistant.v2.errand.web;

import org.json.JSONObject;

/**
 * ★ lane C3-2：浏览代理的一步动作（模型输出 JSON → 本类；纯 Java）。
 *
 * <pre>
 *   {"action":"click","id":12,"why":"进入第一条结果"}
 *   {"action":"type","id":3,"text":"USB-C 数据线","submit":true}
 *   {"action":"scroll","dir":"down"}        {"action":"next_page"}       {"action":"back"}
 *   {"action":"open","url":"https://…"}     {"action":"done","answer":"…"}
 *   {"action":"takeover","reason":"需要登录"}  {"action":"checkout","answer":"已加购 X，到了结算页"}
 * </pre>
 */
public final class AgentAction {

    public enum Type { CLICK, TYPE, SCROLL, NEXT_PAGE, BACK, OPEN, DONE, TAKEOVER, CHECKOUT, INVALID }

    public final Type type;
    public final int id;
    public final String text;
    public final boolean submit;
    public final int dir;
    public final String url;
    public final String answer;
    public final String why;

    private AgentAction(Type type, int id, String text, boolean submit, int dir, String url, String answer, String why) {
        this.type = type;
        this.id = id;
        this.text = text == null ? "" : text;
        this.submit = submit;
        this.dir = dir;
        this.url = url;
        this.answer = answer == null ? "" : answer;
        this.why = why == null ? "" : why;
    }

    public static AgentAction invalid(String why) {
        return new AgentAction(Type.INVALID, -1, null, false, 0, null, null, why);
    }

    public static AgentAction click(int id) {
        return new AgentAction(Type.CLICK, id, null, false, 0, null, null, null);
    }

    public static AgentAction type(int id, String text, boolean submit) {
        return new AgentAction(Type.TYPE, id, text, submit, 0, null, null, null);
    }

    public static AgentAction scroll(int dir) {
        return new AgentAction(Type.SCROLL, -1, null, false, dir >= 0 ? 1 : -1, null, null, null);
    }

    public static AgentAction done(String answer) {
        return new AgentAction(Type.DONE, -1, null, false, 0, null, answer, null);
    }

    public static AgentAction of(Type t) {
        return new AgentAction(t, -1, null, false, 0, null, null, null);
    }

    public static AgentAction open(String url) {
        return new AgentAction(Type.OPEN, -1, null, false, 0, url, null, null);
    }

    public static AgentAction checkout(String answer) {
        return new AgentAction(Type.CHECKOUT, -1, null, false, 0, null, answer, null);
    }

    /** 解析模型输出（容忍 ```json 包裹 / 前后废话，取第一个 {…}）。 */
    public static AgentAction parse(String content) {
        if (content == null) return invalid("模型没有输出");
        String s = content.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) return invalid("输出不是 JSON");
        try {
            JSONObject o = new JSONObject(s.substring(a, b + 1));
            String act = o.optString("action", "").trim().toLowerCase();
            String why = o.optString("why", null);
            if (act.equals("click")) return withWhy(click(o.optInt("id", -1)), why);
            if (act.equals("type") || act.equals("input")) {
                return withWhy(type(o.optInt("id", -1), o.optString("text", ""), o.optBoolean("submit", false)), why);
            }
            if (act.equals("scroll")) {
                String d = o.optString("dir", "down").toLowerCase();
                return withWhy(scroll(d.startsWith("up") || d.equals("-1") ? -1 : 1), why);
            }
            if (act.equals("next_page") || act.equals("nextpage") || act.equals("next")) return withWhy(of(Type.NEXT_PAGE), why);
            if (act.equals("back")) return withWhy(of(Type.BACK), why);
            if (act.equals("open") || act.equals("goto")) return withWhy(open(o.optString("url", "")), why);
            if (act.equals("done") || act.equals("finish") || act.equals("answer")) return withWhy(done(o.optString("answer", "")), why);
            if (act.equals("takeover") || act.equals("handoff")) {
                return new AgentAction(Type.TAKEOVER, -1, null, false, 0, null, o.optString("reason", "需要你处理"), why);
            }
            if (act.equals("checkout")) return withWhy(checkout(o.optString("answer", "")), why);
            return invalid("不认识的动作：" + act);
        } catch (Throwable t) {
            return invalid("JSON 解析失败");
        }
    }

    private static AgentAction withWhy(AgentAction x, String why) {
        return new AgentAction(x.type, x.id, x.text, x.submit, x.dir, x.url, x.answer, why);
    }

    /** 对照当前页面检查参数；不合法返回原因，合法返回 null。 */
    public String validate(PageSnapshot page) {
        switch (type) {
            case INVALID:
                return why.length() > 0 ? why : "无效动作";
            case CLICK:
            case TYPE: {
                PageSnapshot.El e = page == null ? null : page.find(id);
                if (e == null) return "编号 [" + id + "] 不在当前页面的元素列表里";
                if (type == Type.TYPE && !e.isInput()) return "[" + id + "] 不是输入框";
                if (type == Type.TYPE && e.isPassword()) return "不替用户填密码";
                if (type == Type.TYPE && text.trim().length() == 0) return "输入内容为空";
                return null;
            }
            case OPEN: {
                String u = url == null ? "" : url.trim().toLowerCase();
                if (!(u.startsWith("http://") || u.startsWith("https://"))) return "只能打开 http(s) 网址";
                return null;
            }
            case DONE:
                return answer.trim().length() == 0 ? "done 必须带 answer" : null;
            default:
                return null;
        }
    }

    /** 历史记录里的一行（给下一轮模型看）。 */
    public String describe(PageSnapshot page) {
        PageSnapshot.El e = page == null ? null : page.find(id);
        String target = e != null ? "「" + PageSnapshot.clip(e.text, 20) + "」" : "[" + id + "]";
        switch (type) {
            case CLICK: return "点击 " + target;
            case TYPE: return "在 " + target + " 输入「" + PageSnapshot.clip(text, 20) + "」" + (submit ? " 并提交" : "");
            case SCROLL: return dir > 0 ? "向下滚动" : "向上滚动";
            case NEXT_PAGE: return "翻到下一页";
            case BACK: return "返回上一页";
            case OPEN: return "打开 " + url;
            case DONE: return "完成";
            case TAKEOVER: return "请用户接手：" + answer;
            case CHECKOUT: return "到结算页停下";
            default: return "无效：" + why;
        }
    }

    @Override
    public String toString() {
        return type + (id >= 0 ? "#" + id : "") + (text.length() > 0 ? " \"" + text + "\"" : "") + (url != null ? " " + url : "");
    }
}
