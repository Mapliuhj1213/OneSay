package com.onesay.assistant.v2.phone;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * ★ smart-replay：成功路径回放（纯 Java，JVM 可测；存储读写普通文件，路径由调用方给）。
 *
 * <p>任务成功完成后，按「规范化指令 + 起始 App」记下这次走过的步骤（动作类型 + 目标节点的稳定特征 + 动作前的回放指纹）；
 * 再收到同一条指令时先不问模型，逐步「读稳定画面 → 比回放指纹 → 按特征重新找节点 → 执行」，任何一步对不上就停下，
 * 把当前画面和已完成的步骤交还模型接着做（降级）。同一路径连续失败 {@link #MAX_FAILS} 次作废。
 *
 * <p>安全：回放的每一步仍走 {@link PhoneAgent} 原来的执行口（PhonePlan.validate → SensitiveGuard / 插件 RiskGate → 宿主确认），
 * 录制时判过敏感的步骤回放时也强制确认；含付款 / 转账类步骤、或经过支付 / 银行 / 钱包 / 密码 / 商店类 App 的任务不录也不放；
 * 输入内容像验证码 / 密码 / 个人信息的不存原文，回放到那一步交还模型。
 *
 * <p>思路参考 codesfly/phoneDriver（Apache-2.0，https://github.com/codesfly/phoneDriver ，replay_engine.py：
 * 成功任务按名字缓存轨迹 + 每步页面指纹、指纹不符即降级回模型）。代码按思路自己写，没有复制其源码；
 * 与它不同的是：指纹只看结构与资源 id（对时间、角标、列表内容宽容），节点按特征重新定位（不存坐标），并加了安全与失败作废。
 */
public final class SmartReplay {

    private SmartReplay() {
    }

    // ------------------------------------------------------------------ 参数

    /** 最多存多少条路径（超出按最近使用淘汰最久没用的）。 */
    public static final int MAX_PATHS = 200;
    /** 同一路径连续失败几次作废。 */
    public static final int MAX_FAILS = 2;
    /** 一条路径最多几步（更长的任务不录）。 */
    public static final int MAX_STEPS = 40;
    /** 回放指纹：资源 id 结构集合的相似度（Jaccard）不低于它才算同一画面。 */
    public static final double MIN_SIMILARITY = 0.6;
    /** 重新定位：没名字的节点 / 同名的多个候选，按相对位置挑「附近」的那个，距离不超过短边的这个比例。 */
    public static final double NEAR_FRACTION = 0.08;
    /** 一屏最多记多少个结构记号。 */
    static final int MAX_TOKENS = 48;
    /** 存储文件名（应用私有 files 目录；allowBackup=false，不上传）。 */
    public static final String FILE = "phone_replay.json";
    /** 总开关默认值：开源版默认关（个人版开），用户在设置里打开。 */
    public static final boolean DEFAULT_ON = false;

    /** 开关裁决（与其他功能开关同一写法）：覆盖值优先；kill 文件存在即关；否则看设置值。 */
    public static boolean decide(Boolean override, boolean killed, boolean pref) {
        if (override != null) return override.booleanValue();
        if (killed) return false;
        return pref;
    }

    // ------------------------------------------------------------------ 键

    /** 规范化指令：全角转半角、转小写、去掉空白和标点（只留字母 / 数字 / 汉字）。 */
    public static String normalizeGoal(String goal) {
        if (goal == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < goal.length(); i++) {
            char c = goal.charAt(i);
            if (c == '\u3000') c = ' ';
            else if (c >= '\uFF01' && c <= '\uFF5E') c = (char) (c - 0xFEE0);
            if (Character.isLetterOrDigit(c)) b.append(Character.toLowerCase(c));
        }
        return b.toString();
    }

    /** 路径键 = sha256(规范化指令 @ 起始包名)。存的是哈希，文件里不出现指令原文。 */
    public static String key(String goal, String startPkg) {
        return sha256(normalizeGoal(goal) + "@" + (startPkg == null ? "" : startPkg.trim()));
    }

    // ------------------------------------------------------------------ 不录不放

    /** 指令本身像付款 / 转账（SensitiveGuard 的付款词 + 资金类词）。 */
    static final Pattern GOAL_MONEY = Pattern.compile("转账|汇款|红包|付钱|提现|还款|收款码|付款码|扫码付|transfer|remit", Pattern.CASE_INSENSITIVE);

    public static boolean moneyGoal(String goal) {
        if (goal == null) return false;
        return GOAL_MONEY.matcher(goal).find() || SensitiveGuard.PAY.matcher(goal).find();
    }

    /** 敏感判定给的原因是不是付款类（SensitiveGuard「付款 / 下单」、插件 RiskGate 按金额问的付款）。 */
    public static boolean moneyReason(String sens) {
        if (sens == null) return false;
        return sens.contains("付款") || sens.contains("支付") || sens.contains("转账") || sens.contains("下单") || sens.contains("金额");
    }

    /** 支付 / 银行 / 钱包 / 密码 / 应用商店 / 安装器类 App（与 LinkPolicy 同一份判定）。 */
    public static boolean blockedPkg(String pkg) {
        if (pkg == null || pkg.length() == 0) return false;
        try {
            return com.onesay.assistant.v2.pclink.LinkPolicy.blockedApp(pkg, "");
        } catch (Throwable t) {
            return true;   // 判不了就当不能碰
        }
    }

    /** 这条指令能不能录 / 放：null = 可以；否则是原因。 */
    public static String skipReason(String goal, String startPkg) {
        if (moneyGoal(goal)) return "指令含付款 / 转账";
        if (blockedPkg(startPkg)) return "支付 / 银行 / 钱包 / 密码 / 商店类 App";
        return null;
    }

    // ------------------------------------------------------------------ 隐私

    /** 像个人数据的文字：手机号、邮箱、身份证、连续 4 位以上数字（验证码 / 卡号 / 证件号）。 */
    static final Pattern PII_TEXT = Pattern.compile("1[3-9]\\d{9}|[\\w.+-]+@[\\w-]+\\.[\\w.]+|\\d{17}[\\dxX]|\\d{4,}");
    /** 输入框本身是验证码 / 密码 / 个人信息栏（资源 id 或提示文字）。 */
    static final Pattern PII_RID = Pattern.compile("(^|_)(phone|mobile|tel|email|mail|address|addr|realname|nickname|name|username|idcard|card|cardno|account)(_|$)",
            Pattern.CASE_INSENSITIVE);
    static final Pattern PII_LABEL = Pattern.compile("手机|电话|邮箱|地址|姓名|名字|身份证|证件|银行卡|卡号|账号|帐号|\\b(phone|e-?mail|address|name|account)\\b",
            Pattern.CASE_INSENSITIVE);

    public static boolean piiLike(String s) {
        return s != null && s.length() > 0 && PII_TEXT.matcher(s).find();
    }

    /** 这段输入要不要「不存原文、回放到这一步交还模型」。field 可为 null（当前焦点不明）。 */
    public static boolean secretInput(String text, UiTree.El field) {
        if (piiLike(text)) return true;
        if (field == null) return false;
        if (field.password) return true;
        String f = field.rid + " " + field.label;
        return SensitiveGuard.SECRET_FIELD.matcher(f).find() || PII_RID.matcher(SensitiveGuard.splitRid(field.rid)).find()
                || PII_LABEL.matcher(field.label).find();
    }

    // ------------------------------------------------------------------ 回放指纹

    /** 弹窗常见按钮（录制时不在、回放时冒出来 = 有弹窗）。 */
    static final String[] POPUP_WORDS = {"允许", "拒绝", "始终允许", "仅在使用中允许", "仅限这一次", "本次运行允许", "禁止", "确定", "取消", "关闭",
            "跳过", "我知道了", "知道了", "以后再说", "下次再说", "暂不", "暂不更新", "立即更新", "去更新", "同意", "不同意", "好的",
            "OK", "Cancel", "Allow", "Deny", "Close", "Skip", "Not now", "Later", "Got it"};
    /** 对话框常见资源 id（录制时不在、回放时出现 = 有弹窗）。 */
    static final Pattern POPUP_RID = Pattern.compile("^(alertTitle|parentPanel|button1|button2|button3|permission_\\w+|grant_dialog)$|dialog|popup|alert", Pattern.CASE_INSENSITIVE);

    /** 一屏的回放指纹（见 {@link UiTree#replayTokens}）：前台包名 + 页面 + 结构记号集合 + 录制时屏上已有的弹窗词。 */
    public static final class Print {
        public String pkg = "", act = "";
        public final Set<String> tokens = new HashSet<String>();
        public final Set<String> popupWords = new HashSet<String>();

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("pkg", pkg);
            o.put("act", act);
            JSONArray t = new JSONArray();
            for (String s : new java.util.TreeSet<String>(tokens)) t.put(s);
            o.put("t", t);
            JSONArray w = new JSONArray();
            for (String s : new java.util.TreeSet<String>(popupWords)) w.put(s);
            o.put("w", w);
            return o;
        }

        static Print of(JSONObject o) {
            Print p = new Print();
            if (o == null) return p;
            p.pkg = o.optString("pkg", "");
            p.act = o.optString("act", "");
            JSONArray t = o.optJSONArray("t");
            if (t != null) for (int i = 0; i < t.length(); i++) p.tokens.add(t.optString(i));
            JSONArray w = o.optJSONArray("w");
            if (w != null) for (int i = 0; i < w.length(); i++) p.popupWords.add(w.optString(i));
            return p;
        }
    }

    public static Print print(UiTree t) {
        Print p = new Print();
        if (t == null) return p;
        p.pkg = t.pkg == null ? "" : t.pkg;
        p.act = t.activity == null ? "" : t.activity;
        p.tokens.addAll(t.replayTokens(MAX_TOKENS));
        for (UiTree.El e : t.els) if (isPopupWord(e)) p.popupWords.add(e.label);
        return p;
    }

    static boolean isPopupWord(UiTree.El e) {
        if (!e.click || e.label.length() == 0) return false;
        for (String w : POPUP_WORDS) if (w.equalsIgnoreCase(e.label)) return true;
        return false;
    }

    public static double similarity(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) return 1.0;
        int inter = 0;
        for (String s : a) if (b.contains(s)) inter++;
        int union = a.size() + b.size() - inter;
        return union == 0 ? 1.0 : (double) inter / union;
    }

    /** 回放前看有没有弹窗：录制时不在的弹窗按钮 / 对话框资源 id 出现了。null = 没有。 */
    public static String popup(Print rec, UiTree live) {
        if (rec == null || live == null) return null;
        for (UiTree.El e : live.els) {
            if (isPopupWord(e) && !rec.popupWords.contains(e.label)) return "冒出了「" + e.label + "」弹窗";
            if (e.rid.length() > 0 && POPUP_RID.matcher(e.rid).find() && !rec.tokens.contains(UiTree.replayToken(e)))
                return "冒出了对话框（#" + e.rid + "）";
        }
        return null;
    }

    /** 回放指纹比对：null = 是同一画面；否则是不符的原因。 */
    public static String mismatch(Print rec, UiTree live) {
        if (rec == null) return "没有录制时的指纹";
        if (live == null) return "读不到界面";
        if (!rec.pkg.equals(live.pkg)) return "前台 App 不同（录制时 " + rec.pkg + "，现在 " + live.pkg + "）";
        if (rec.act.length() > 0 && live.activity.length() > 0 && !rec.act.equals(live.activity))
            return "页面不同（录制时 " + UiTree.shortAct(rec.act) + "，现在 " + UiTree.shortAct(live.activity) + "）";
        Set<String> now = new HashSet<String>(live.replayTokens(MAX_TOKENS));
        double sim = similarity(rec.tokens, now);
        if (sim < MIN_SIMILARITY) return "界面结构对不上（相似度 " + Math.round(sim * 100) + "%）";
        return null;
    }

    // ------------------------------------------------------------------ 目标节点特征

    /** 目标节点的稳定特征：资源 id、类名、名字（像个人数据时只存哈希）、种类、相对位置（千分比，只作辅助）。不存编号与绝对坐标。 */
    public static final class Target {
        public String rid = "", cls = "", label = "", title = "";
        /** label / title 存的是哈希（原文像个人数据）。 */
        public boolean hashed;
        public boolean edit, scroll, check, click;
        public int rx, ry;

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("rid", rid);
            o.put("cls", cls);
            o.put("label", label);
            o.put("title", title);
            o.put("hashed", hashed);
            o.put("k", (edit ? "e" : "") + (scroll ? "s" : "") + (check ? "c" : "") + (click ? "k" : ""));
            o.put("rx", rx);
            o.put("ry", ry);
            return o;
        }

        static Target of(JSONObject o) {
            if (o == null) return null;
            Target t = new Target();
            t.rid = o.optString("rid", "");
            t.cls = o.optString("cls", "");
            t.label = o.optString("label", "");
            t.title = o.optString("title", "");
            t.hashed = o.optBoolean("hashed", false);
            String k = o.optString("k", "");
            t.edit = k.contains("e");
            t.scroll = k.contains("s");
            t.check = k.contains("c");
            t.click = k.contains("k");
            t.rx = o.optInt("rx", 500);
            t.ry = o.optInt("ry", 500);
            return t;
        }

        @Override public String toString() {
            return (hashed ? "（个人信息，已隐去）" : label.length() > 0 ? "「" + PhoneAgent.clip(label, 16) + "」" : "")
                    + (rid.length() > 0 ? " #" + rid : "") + (rid.length() == 0 && label.length() == 0 ? cls + " @" + rx + "‰," + ry + "‰" : "");
        }
    }

    public static Target target(UiTree.El e, UiTree t) {
        Target g = new Target();
        g.rid = e.rid;
        g.cls = e.cls;
        String title = SensitiveGuard.title(e.label);
        if (piiLike(e.label)) {
            g.hashed = true;
            g.label = sha256(e.label);
            g.title = sha256(title);
        } else {
            g.label = e.label;
            g.title = title;
        }
        g.edit = e.edit;
        g.scroll = e.scroll;
        g.check = e.check;
        g.click = e.click;
        int w = t == null ? 1080 : Math.max(1, t.screenW), h = t == null ? 2220 : Math.max(1, t.screenH);
        g.rx = (int) Math.max(0, Math.min(1000, (long) e.cx() * 1000 / w));
        g.ry = (int) Math.max(0, Math.min(1000, (long) e.cy() * 1000 / h));
        return g;
    }

    /** 重新定位结果：el 非 null = 找到唯一一个；否则 error 是原因（ambiguous = 有歧义）。 */
    public static final class Found {
        public UiTree.El el;
        public String error;
        public boolean ambiguous;
    }

    /** 数字串换成 #（时间、角标、计数变化不影响匹配）。 */
    static String digitless(String s) {
        return s == null ? "" : s.replaceAll("\\d+", "#");
    }

    static boolean labelEq(Target g, String label, boolean loose) {
        if (g.hashed) return sha256(label).equals(g.label) || sha256(SensitiveGuard.title(label)).equals(g.title);
        if (label.equals(g.label)) return true;
        if (!loose) return false;
        if (digitless(label).equals(digitless(g.label))) return true;
        String t = SensitiveGuard.title(label);   // 「标题 · 摘要」：摘要会变（最后一条消息、状态），标题不变
        return g.title.length() > 0 && (t.equals(g.title) || digitless(t).equals(digitless(g.title)));
    }

    public static Found locate(Target g, UiTree live) {
        Found f = new Found();
        if (g == null || live == null) {
            f.error = "读不到界面";
            return f;
        }
        List<UiTree.El> c = new ArrayList<UiTree.El>();
        for (UiTree.El e : live.els) {
            if (!e.cls.equals(g.cls) || e.edit != g.edit || e.scroll != g.scroll || e.check != g.check) continue;
            if (g.rid.length() > 0 && !g.rid.equals(e.rid)) continue;
            c.add(e);
        }
        if (g.label.length() > 0) {
            List<UiTree.El> exact = new ArrayList<UiTree.El>(), loose = new ArrayList<UiTree.El>();
            for (UiTree.El e : c) {
                if (labelEq(g, e.label, false)) exact.add(e);
                else if (labelEq(g, e.label, true)) loose.add(e);
            }
            c = exact.isEmpty() ? loose : exact;
        } else if (g.rid.length() == 0) {
            c = near(c, g, live);   // 没名字也没 id：只认原位置附近的
        }
        if (c.isEmpty()) {
            f.error = "找不到要操作的节点 " + g;
            return f;
        }
        if (c.size() > 1) {
            List<UiTree.El> n = near(c, g, live);
            if (n.size() != 1) {
                f.ambiguous = true;
                f.error = "要操作的节点 " + g + " 有 " + c.size() + " 个，分不清是哪一个";
                return f;
            }
            c = n;
        }
        f.el = c.get(0);
        return f;
    }

    static List<UiTree.El> near(List<UiTree.El> c, Target g, UiTree live) {
        int w = Math.max(1, live.screenW), h = Math.max(1, live.screenH);
        double lim = Math.min(w, h) * NEAR_FRACTION;
        double gx = g.rx * (double) w / 1000, gy = g.ry * (double) h / 1000;
        List<UiTree.El> out = new ArrayList<UiTree.El>();
        for (UiTree.El e : c) if (Math.hypot(e.cx() - gx, e.cy() - gy) <= lim) out.add(e);
        return out;
    }

    // ------------------------------------------------------------------ 路径

    /** 记下的一步。 */
    public static final class RStep {
        public String op = "";
        public Target target;
        public String text = "";
        /** 输入内容没存（验证码 / 密码 / 个人信息）：回放到这一步交还模型。 */
        public boolean secret;
        public boolean enter;
        public int dir = 1;
        public String app = "", key = "", expect = "", expectGone = "", expectApp = "";
        public boolean waitStable;
        public int timeoutMs = 5000, holdMs = 800;
        /** 动作前的回放指纹。 */
        public Print before;
        /** 录制时判为敏感的原因（回放时即使现判不敏感也照样确认）；null = 不敏感。 */
        public String sens;

        /** 还原成一步执行指令（id = 重新定位到的编号，没有目标时 -1）。 */
        public PhonePlan.Step toStep(int id) {
            PhonePlan.Step s = new PhonePlan.Step();
            s.op = op;
            s.id = id;
            s.text = text;
            s.enter = enter;
            s.dir = dir;
            s.app = app;
            s.key = key;
            s.expect = expect;
            s.expectGone = expectGone;
            s.expectApp = expectApp;
            s.waitStable = waitStable;
            s.timeoutMs = timeoutMs;
            s.holdMs = holdMs;
            return s;
        }

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("op", op);
            if (target != null) o.put("target", target.json());
            o.put("text", text);
            o.put("secret", secret);
            o.put("enter", enter);
            o.put("dir", dir);
            o.put("app", app);
            o.put("key", key);
            o.put("expect", expect);
            o.put("expectGone", expectGone);
            o.put("expectApp", expectApp);
            o.put("waitStable", waitStable);
            o.put("timeoutMs", timeoutMs);
            o.put("holdMs", holdMs);
            if (before != null) o.put("before", before.json());
            if (sens != null) o.put("sens", sens);
            return o;
        }

        static RStep of(JSONObject o) {
            RStep s = new RStep();
            s.op = o.optString("op", "");
            s.target = Target.of(o.optJSONObject("target"));
            s.text = o.optString("text", "");
            s.secret = o.optBoolean("secret", false);
            s.enter = o.optBoolean("enter", false);
            s.dir = o.optInt("dir", 1);
            s.app = o.optString("app", "");
            s.key = o.optString("key", "");
            s.expect = o.optString("expect", "");
            s.expectGone = o.optString("expectGone", "");
            s.expectApp = o.optString("expectApp", "");
            s.waitStable = o.optBoolean("waitStable", false);
            s.timeoutMs = o.optInt("timeoutMs", 5000);
            s.holdMs = o.optInt("holdMs", 800);
            s.before = o.has("before") ? Print.of(o.optJSONObject("before")) : null;
            s.sens = o.has("sens") ? o.optString("sens") : null;
            return s;
        }

        @Override public String toString() {
            StringBuilder b = new StringBuilder(op);
            if (target != null) b.append(' ').append(target);
            if (secret) b.append(" （输入内容没保存）");
            else if (text.length() > 0) b.append(" 「").append(PhoneAgent.clip(text, 16)).append('」');
            if (app.length() > 0) b.append(' ').append(app);
            if (key.length() > 0) b.append(' ').append(key);
            return b.toString();
        }
    }

    /** 一条成功路径。 */
    public static final class Path {
        public String key = "";
        public String pkg = "";
        public final List<RStep> steps = new ArrayList<RStep>();
        /** 连续失败次数（成功回放清零）。 */
        public int fails;
        public int hits;
        public long created, lastUsed;

        JSONObject json() throws Exception {
            JSONObject o = new JSONObject();
            o.put("key", key);
            o.put("pkg", pkg);
            o.put("fails", fails);
            o.put("hits", hits);
            o.put("created", created);
            o.put("lastUsed", lastUsed);
            JSONArray a = new JSONArray();
            for (RStep s : steps) a.put(s.json());
            o.put("steps", a);
            return o;
        }

        static Path of(JSONObject o) {
            Path p = new Path();
            p.key = o.optString("key", "");
            p.pkg = o.optString("pkg", "");
            p.fails = o.optInt("fails", 0);
            p.hits = o.optInt("hits", 0);
            p.created = o.optLong("created", 0);
            p.lastUsed = o.optLong("lastUsed", 0);
            JSONArray a = o.optJSONArray("steps");
            if (a != null) for (int i = 0; i < a.length(); i++) {
                JSONObject x = a.optJSONObject(i);
                if (x != null) p.steps.add(RStep.of(x));
            }
            return p;
        }
    }

    // ------------------------------------------------------------------ 录制

    /** 动作结果文字是不是失败（{@link PhoneAgent#exec} 的说法）。 */
    public static boolean failedEffect(String effect) {
        if (effect == null) return true;
        return effect.contains("失败") || effect.startsWith("打不开") || effect.equals("不支持") || effect.startsWith("输入后读回");
    }

    /** 一次任务的录制器：执行层每做成一步就记一步；任何不能回放 / 不该记的情况打上 taint，收尾时不存。 */
    public static final class Recorder {
        public final String key, pkg;
        final List<RStep> steps = new ArrayList<RStep>();
        /** 不存的原因（null = 可以存）。 */
        public String taint;

        public Recorder(String key, String pkg) {
            this.key = key;
            this.pkg = pkg == null ? "" : pkg;
        }

        public void taint(String why) {
            if (taint == null) taint = why;
        }

        public int size() {
            return steps.size();
        }

        /** 敏感判定命中（不管最后执行没有）：付款类 → 整个任务不录。 */
        public void onSensitive(String sens) {
            if (moneyReason(sens)) taint("含付款 / 转账类步骤");
        }

        /** 每读到一屏：经过支付 / 银行 / 钱包类 App → 不录。 */
        public void onScreen(UiTree t) {
            if (t != null && blockedPkg(t.pkg)) taint("经过支付 / 银行 / 钱包 / 密码 / 商店类 App");
        }

        /** 一步执行完：before = 动作前那屏，effect / wait = 执行层的说法，sens = 这一步的敏感原因（null = 不敏感）。 */
        public void onExecuted(PhonePlan.Step s, UiTree before, String effect, String wait, String sens) {
            if (taint != null) return;
            if (failedEffect(effect)) return;   // 没做成的步骤不记（模型随后会换个做法）
            if ((s.op.equals("tap") || s.op.equals("long")) && s.id < 0) {
                taint("按坐标点的步骤没法按特征回放");
                return;
            }
            if (s.op.equals("launch") && blockedPkg(s.app)) {
                taint("打开了支付 / 银行 / 钱包 / 密码 / 商店类 App");
                return;
            }
            if (moneyReason(sens)) {
                taint("含付款 / 转账类步骤");
                return;
            }
            if (steps.size() >= MAX_STEPS) {
                taint("步骤超过 " + MAX_STEPS + " 步");
                return;
            }
            RStep r = new RStep();
            r.op = s.op;
            UiTree.El e = s.id >= 0 && before != null ? before.find(s.id) : null;
            if (s.id >= 0 && e == null) {
                taint("找不到第 " + (steps.size() + 1) + " 步的目标节点");
                return;
            }
            if (e != null) r.target = target(e, before);
            if (s.op.equals("type")) {
                UiTree.El field = e != null ? e : focusedEdit(before);
                if (secretInput(s.text, field)) {
                    r.secret = true;
                    r.text = "";
                } else r.text = s.text;
            }
            r.enter = s.enter;
            r.dir = s.dir;
            r.app = s.app;
            r.key = s.key;
            boolean condFailed = wait != null && wait.startsWith("没等到");
            if (!condFailed && !piiLike(s.expect + s.expectGone)) {   // 条件没等到 / 条件里有个人信息：只留动作，不留条件
                r.expect = s.expect;
                r.expectGone = s.expectGone;
                r.expectApp = s.expectApp;
                r.waitStable = s.waitStable;
                r.timeoutMs = s.timeoutMs;
            }
            r.holdMs = s.holdMs;
            r.before = print(before);
            r.sens = sens == null ? null : piiLike(sens) ? "这一步要你确认（录制时判为敏感）" : sens;
            steps.add(r);
        }

        /** 任务成功收尾：可以存就给出路径，否则 null（原因在 taint）。 */
        public Path finish(long now) {
            if (taint == null && steps.isEmpty()) taint("没有可记的步骤");
            if (taint != null) return null;
            Path p = new Path();
            p.key = key;
            p.pkg = pkg;
            p.steps.addAll(steps);
            p.created = now;
            p.lastUsed = now;
            return p;
        }
    }

    static UiTree.El focusedEdit(UiTree t) {
        if (t == null) return null;
        for (UiTree.El e : t.els) if (e.edit && e.focused) return e;
        return null;
    }

    // ------------------------------------------------------------------ 存储

    public interface Now {
        long now();
    }

    /** 文件存储（应用私有目录的一个 JSON 文件）：最近使用排序、超出上限淘汰最久没用的、可一键清空。线程安全。 */
    public static final class Store {
        private final File mFile;
        private final int mMax;
        private final Now mNow;
        private final LinkedHashMap<String, Path> mMap = new LinkedHashMap<String, Path>(16, 0.75f, true);
        private boolean mLoaded;
        /** 累计统计（清空时一起清）。 */
        public int totalHits, totalFallbacks, totalVoided, totalRecorded, totalHandoffs;

        public Store(File file, int max, Now now) {
            mFile = file;
            mMax = Math.max(1, max);
            mNow = now != null ? now : new Now() {
                @Override public long now() {
                    return System.currentTimeMillis();
                }
            };
        }

        public Store(File file) {
            this(file, MAX_PATHS, null);
        }

        private void load() {
            if (mLoaded) return;
            mLoaded = true;
            if (mFile == null || !mFile.exists()) return;
            try {
                JSONObject o = new JSONObject(readAll(mFile));
                JSONObject t = o.optJSONObject("totals");
                if (t != null) {
                    totalHits = t.optInt("hit");
                    totalFallbacks = t.optInt("fallback");
                    totalVoided = t.optInt("voided");
                    totalRecorded = t.optInt("recorded");
                    totalHandoffs = t.optInt("handoff");
                }
                JSONArray a = o.optJSONArray("paths");
                if (a != null) for (int i = 0; i < a.length(); i++) {   // 文件里按最久没用 → 最近用排
                    JSONObject x = a.optJSONObject(i);
                    if (x == null) continue;
                    Path p = Path.of(x);
                    if (p.key.length() > 0 && !p.steps.isEmpty()) mMap.put(p.key, p);
                }
                evict();
            } catch (Throwable t) {   // 坏文件：当作空的，下次写入覆盖
                mMap.clear();
            }
        }

        private void save() {
            if (mFile == null) return;
            try {
                JSONObject o = new JSONObject();
                o.put("v", 1);
                JSONObject t = new JSONObject();
                t.put("hit", totalHits);
                t.put("fallback", totalFallbacks);
                t.put("voided", totalVoided);
                t.put("recorded", totalRecorded);
                t.put("handoff", totalHandoffs);
                o.put("totals", t);
                JSONArray a = new JSONArray();
                for (Path p : mMap.values()) a.put(p.json());
                o.put("paths", a);
                File dir = mFile.getParentFile();
                if (dir != null && !dir.exists()) dir.mkdirs();
                File tmp = new File(mFile.getPath() + ".tmp");
                FileOutputStream out = new FileOutputStream(tmp);
                try {
                    out.write(o.toString().getBytes("UTF-8"));
                } finally {
                    out.close();
                }
                if (!tmp.renameTo(mFile)) {
                    mFile.delete();
                    tmp.renameTo(mFile);
                }
            } catch (Throwable ignored) {
            }
        }

        private void evict() {
            Iterator<Map.Entry<String, Path>> it = mMap.entrySet().iterator();
            while (mMap.size() > mMax && it.hasNext()) {
                it.next();
                it.remove();
            }
        }

        /** 取路径（算一次使用，挪到最近）。 */
        public synchronized Path get(String key) {
            load();
            Path p = mMap.get(key);
            if (p != null) {
                p.lastUsed = mNow.now();
                save();
            }
            return p;
        }

        public synchronized boolean has(String key) {
            load();
            return mMap.containsKey(key);
        }

        public synchronized void put(Path p) {
            load();
            if (p == null || p.key.length() == 0) return;
            p.lastUsed = mNow.now();
            if (p.created == 0) p.created = p.lastUsed;
            mMap.remove(p.key);
            mMap.put(p.key, p);
            totalRecorded++;
            evict();
            save();
        }

        /** 回放走完（或按计划在不存原文的输入处交还）：连续失败清零。 */
        public synchronized void succeeded(String key, boolean full) {
            load();
            Path p = mMap.get(key);
            if (p != null) {
                p.fails = 0;
                p.hits++;
            }
            if (full) totalHits++;
            else totalHandoffs++;
            save();
        }

        /** 交还模型但不算路径的错（用户拒绝 / 接手 / 停止）。 */
        public synchronized void handedOff() {
            load();
            totalHandoffs++;
            save();
        }

        /** 回放失败一次：返回 true = 连续失败到上限，已作废（删掉）。 */
        public synchronized boolean failed(String key) {
            load();
            totalFallbacks++;
            Path p = mMap.get(key);
            boolean voided = false;
            if (p != null) {
                p.fails++;
                if (p.fails >= MAX_FAILS) {
                    mMap.remove(key);
                    totalVoided++;
                    voided = true;
                }
            }
            save();
            return voided;
        }

        public synchronized void remove(String key) {
            load();
            if (mMap.remove(key) != null) save();
        }

        /** 一键清空：路径和统计全删，文件也删。 */
        public synchronized void clear() {
            mLoaded = true;
            mMap.clear();
            totalHits = totalFallbacks = totalVoided = totalRecorded = totalHandoffs = 0;
            if (mFile != null) {
                mFile.delete();
                new File(mFile.getPath() + ".tmp").delete();
            }
        }

        public synchronized int size() {
            load();
            return mMap.size();
        }

        /** 最久没用 → 最近用的键（测试 / 诊断用）。 */
        public synchronized List<String> keys() {
            load();
            return new ArrayList<String>(mMap.keySet());
        }

        public synchronized String stats() {
            load();
            return "paths=" + mMap.size() + " totalHit=" + totalHits + " totalFallback=" + totalFallbacks + " totalVoided=" + totalVoided
                    + " totalRecorded=" + totalRecorded + " totalHandoff=" + totalHandoffs;
        }
    }

    // ------------------------------------------------------------------ 小工具

    static String readAll(File f) throws Exception {
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), "UTF-8");
        } finally {
            in.close();
        }
    }

    public static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest((s == null ? "" : s).getBytes("UTF-8"));
            StringBuilder b = new StringBuilder();
            for (byte x : d) b.append(String.format("%02x", x & 0xff));
            return b.toString();
        } catch (Exception e) {
            return Integer.toHexString(String.valueOf(s).hashCode());
        }
    }
}
