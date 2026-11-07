package com.onesay.assistant.v2.phone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ r2/fast-screen：模型一次输出（纯 Java，JVM 可测）。一次调用可以排 1–{@link #MAX_STEPS} 步（思路借鉴 Mika do_steps，Java 重写）：
 *
 * <pre>
 * {"steps":[
 *    {"do":"tap","id":12,"expect":"WLAN"},
 *    {"do":"type","id":3,"text":"北京天气","enter":true,"wait_stable":true},
 *    {"do":"scroll","dir":"down","id":5,"expect":"关于手机","timeout_s":6},
 *    {"do":"back","expect_gone":"弹窗标题"},
 *    {"do":"launch","app":"com.android.settings","expect_app":"com.android.settings"},
 *    {"do":"long","id":7}, {"do":"key","key":"enter"}, {"do":"wait","expect":"加载完成","timeout_s":8}
 * ], "note":"为什么这样排"}
 * {"collect":{"until":"关于手机","max_pages":8}}       逐屏下滑收集长列表（去重），直到看到 until 或到底
 * {"done":"给用户的结果"}      {"ask":"需要用户决定 / 确认的事"}      {"shot":true}（下一轮附视觉描述）
 * </pre>
 */
public final class PhonePlan {

    public static final int MAX_STEPS = 20;

    public enum Kind { STEPS, COLLECT, DONE, ASK, SHOT, INVALID, /** ★ mika-plug（mika.dosteps）：end_not_done。 */ NOT_DONE }

    public static final class Step {
        public String op = "";
        public int id = -1;
        public String text = "";
        public boolean enter;
        public int dir = 1;
        public String app = "";
        public String key = "";
        public String expect = "";
        public String expectGone = "";
        public String expectApp = "";
        public boolean waitStable;
        public int timeoutMs = 5000;
        public int holdMs = 800;
        /** ★ mika-plug（mika.dosteps）：0–999 坐标（-1 = 没给）与坐标点的说明。 */
        public int x = -1, y = -1;
        public String target = "";

        public boolean hasCondition() {
            return expect.length() > 0 || expectGone.length() > 0 || expectApp.length() > 0;
        }

        /** 重复动作检测用的键。 */
        public String sig() {
            return op + ":" + id + ":" + text + ":" + dir + ":" + app + ":" + key + (x >= 0 ? ":" + x / 50 + "," + y / 50 : "");
        }

        @Override public String toString() {
            StringBuilder b = new StringBuilder(op);
            if (id >= 0) b.append(" [").append(id).append(']');
            if (x >= 0) b.append(" (").append(x).append(',').append(y).append(target.length() > 0 ? " " + target : "").append(')');
            if (text.length() > 0) b.append(" 「").append(text).append('」');
            if (op.equals("scroll")) b.append(dir > 0 ? " 下" : " 上");
            if (app.length() > 0) b.append(' ').append(app);
            if (key.length() > 0) b.append(' ').append(key);
            return b.toString();
        }
    }

    public Kind kind = Kind.INVALID;
    public final List<Step> steps = new ArrayList<Step>();
    public String answer = "";
    public String note = "";
    public String collectUntil = "";
    public int collectPages = 8;
    public String error = "";

    static final String[] OPS = {"tap", "long", "type", "scroll", "back", "home", "launch", "key", "wait", "swipe"};

    public static PhonePlan parse(String content) {
        return parse(content, false);
    }

    /** Mika 工具名 → 本地动作。 */
    static String mikaOp(String tool) {
        String t = tool == null ? "" : tool.trim().toLowerCase();
        if (t.startsWith("phone_")) t = t.substring(6);
        if (t.equals("type_text")) return "type";
        if (t.equals("launch_app") || t.equals("open_app")) return "launch";
        if (t.equals("press_key")) return "key";
        if (t.equals("long_press")) return "long";
        return t;
    }

    /**
     * ★ mika-plug：mika = true 时额外接受 Mika 写法（mika.dosteps）—— ref "@eN"、0–999 坐标 x/y + target、
     * {"do_steps":{"steps":[{"tool","args",expect…}]}} / {"tool":"do_steps","args":{…}}、单个 {"tool":…,"args":…}、
     * scroll 的 direction、end_not_done。mika = false 与改前逐字一致。
     */
    public static PhonePlan parse(String content, boolean mika) {
        PhonePlan p = new PhonePlan();
        if (content == null) {
            p.error = "模型没有输出";
            return p;
        }
        String s = content.trim();
        int a = s.indexOf('{'), b = s.lastIndexOf('}');
        if (a < 0 || b <= a) {
            p.error = "输出不是 JSON";
            return p;
        }
        try {
            JSONObject o = new JSONObject(s.substring(a, b + 1));
            p.note = o.optString("note", o.optString("why", ""));
            if (mika) {
                if (o.has("end_not_done")) {
                    p.kind = Kind.NOT_DONE;
                    Object v = o.opt("end_not_done");
                    p.answer = v instanceof JSONObject ? ((JSONObject) v).optString("message", "") : String.valueOf(v);
                    return p;
                }
                if ("end_not_done".equals(o.optString("tool"))) {
                    p.kind = Kind.NOT_DONE;
                    JSONObject ar = o.optJSONObject("args");
                    p.answer = ar == null ? "" : ar.optString("message", "");
                    return p;
                }
                JSONObject ds = o.optJSONObject("do_steps");
                if (ds == null && "do_steps".equals(o.optString("tool"))) ds = o.optJSONObject("args");
                if (ds != null && ds.optJSONArray("steps") != null) o.put("steps", ds.optJSONArray("steps"));
                else if (o.has("tool") && !o.has("steps")) {
                    JSONArray one = new JSONArray();
                    one.put(o);
                    o.put("steps", one);
                }
            }
            if (o.has("done")) {
                p.kind = Kind.DONE;
                p.answer = o.optString("done", "");
                return p;
            }
            if (o.has("ask")) {
                p.kind = Kind.ASK;
                p.answer = o.optString("ask", "");
                return p;
            }
            if (o.has("collect")) {
                JSONObject c = o.optJSONObject("collect");
                p.kind = Kind.COLLECT;
                if (c != null) {
                    p.collectUntil = c.optString("until", "");
                    p.collectPages = Math.max(1, Math.min(20, c.optInt("max_pages", 8)));
                }
                return p;
            }
            if (o.optBoolean("shot", false)) {
                p.kind = Kind.SHOT;
                return p;
            }
            JSONArray arr = o.optJSONArray("steps");
            if (arr == null && o.has("do")) {   // 容忍单步写法
                arr = new JSONArray();
                arr.put(o);
            }
            if (arr == null || arr.length() == 0) {
                p.error = "没有 steps / done / ask";
                return p;
            }
            for (int i = 0; i < arr.length() && i < MAX_STEPS; i++) {
                JSONObject x = arr.optJSONObject(i);
                if (x == null) continue;
                Step st = new Step();
                JSONObject args = mika ? x.optJSONObject("args") : null;
                if (args != null) {   // Mika：{"tool":"tap","args":{…},"expect":…} → 参数并进同一层
                    java.util.Iterator<String> it = args.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        if (!x.has(k)) x.put(k, args.get(k));
                    }
                }
                st.op = x.optString("do", x.optString("action", "")).trim().toLowerCase();
                if (mika && st.op.length() == 0 && x.has("tool")) st.op = mikaOp(x.optString("tool"));
                if (st.op.equals("click")) st.op = "tap";
                if (st.op.equals("input")) st.op = "type";
                if (st.op.equals("open")) st.op = "launch";
                if (st.op.equals("long_press")) st.op = "long";
                boolean known = false;
                for (String k : OPS) known |= k.equals(st.op);
                if (!known) {
                    p.error = "第 " + (i + 1) + " 步不认识的动作：" + st.op;
                    return p;
                }
                st.id = x.optInt("id", -1);
                if (mika) {
                    if (st.id < 0) {
                        Object rf = x.opt("ref");
                        if (rf == null) rf = x.opt("id");
                        if (rf != null) st.id = com.onesay.assistant.plug.screen.core.UiElement.refNo(String.valueOf(rf));
                    }
                    if (x.has("x") && x.has("y")) {
                        st.x = Math.max(0, Math.min(999, x.optInt("x", -1)));
                        st.y = Math.max(0, Math.min(999, x.optInt("y", -1)));
                    }
                    st.target = x.optString("target", "");
                    if (x.has("direction")) x.put("dir", x.optString("direction"));
                    if (x.has("package") && !x.has("app")) x.put("app", x.optString("package"));
                    if (x.has("pressEnter")) x.put("enter", x.optBoolean("pressEnter"));
                    if (x.has("expect_app") == false && x.has("expectApp")) x.put("expect_app", x.optString("expectApp"));
                }
                st.text = x.optString("text", "");
                st.enter = x.optBoolean("enter", x.optBoolean("submit", false));
                String d = x.optString("dir", "down").toLowerCase();
                st.dir = d.startsWith("up") || d.equals("-1") || d.equals("left") ? -1 : 1;
                st.app = x.optString("app", x.optString("pkg", ""));
                st.key = x.optString("key", "");
                st.expect = x.optString("expect", "");
                st.expectGone = x.optString("expect_gone", "");
                st.expectApp = x.optString("expect_app", "");
                st.waitStable = x.optBoolean("wait_stable", false);
                st.timeoutMs = (int) Math.max(500, Math.min(20000, x.optDouble("timeout_s", 5) * 1000));
                st.holdMs = Math.max(300, Math.min(3000, x.optInt("hold_ms", 800)));
                p.steps.add(st);
            }
            p.kind = p.steps.isEmpty() ? Kind.INVALID : Kind.STEPS;
            if (p.steps.isEmpty()) p.error = "steps 为空";
            return p;
        } catch (Throwable t) {
            p.error = "JSON 解析失败";
            return p;
        }
    }

    /** 对照当前界面检查一步的参数；合法返回 null。 */
    public static String validate(Step s, UiTree screen) {
        if (s.op.equals("tap") || s.op.equals("long") || s.op.equals("type")) {
            if (s.id < 0 && s.x >= 0 && s.y >= 0 && !s.op.equals("type")) {   // ★ mika-plug：0–999 坐标点
                return s.target.length() == 0 ? "坐标点要写 target（点的是什么）" : null;
            }
            if (s.id < 0 && !s.op.equals("type")) return "缺少元素编号 id";
            if (s.id >= 0) {
                UiTree.El e = screen == null ? null : screen.find(s.id);
                if (e == null) return "编号 [" + s.id + "] 不在当前界面";
                if (s.op.equals("type") && e.password) return "不替用户填密码";
                if (s.op.equals("type") && !e.edit) return "[" + s.id + "] 不是输入框";
            }
            if (s.op.equals("type") && s.text.length() == 0) return "输入内容为空";
        }
        if (s.op.equals("launch") && s.app.length() == 0) return "launch 缺少 app 包名";
        if (s.op.equals("key") && !(s.key.equals("enter") || s.key.equals("back") || s.key.equals("search") || s.key.equals("delete")))
            return "key 只支持 enter / back / search / delete";
        return null;
    }
}
