package com.onesay.assistant.v2.selfset;

import com.onesay.assistant.v2.platform.Tri;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * [settings-diag-1124] 「自身设置」的核心（纯 Java，JVM 可测）：读清单 → 诊断 → 改 → 回读核对。
 *
 * <ul>
 *   <li>{@link #snapshot}：每一项的当前值、缺哪些前提、能不能在聊天里改（模型读它，相当于 OneSay 自己的控件树）；</li>
 *   <li>{@link #match}：一句话是不是在说 OneSay 自己的某项设置、想干什么（为什么用不了 / 开 / 关 / 开着没 / 整体检查）；</li>
 *   <li>{@link #diagnose} / {@link #overview}：大白话原因 + 按钮（帮我打开 / 去打开系统权限）；</li>
 *   <li>{@link #plan} + {@link #apply}：普通项直接改，敏感项要确认，系统权限只给跳转；改完回读核对、如实说。</li>
 * </ul>
 */
public final class SelfSettingsEngine {

    private SelfSettingsEngine() {}

    // ------------------------------------------------------------------ 清单

    /** 一行：一项设置此刻的样子。 */
    public static final class Row {
        public final SelfCatalog.Item item;
        public final Object value;
        public final boolean killed;
        /** 明确不满足的前提。 */
        public final List<SelfCatalog.Need> missing;
        /** 查不到的前提。 */
        public final List<SelfCatalog.Need> unknown;

        Row(SelfCatalog.Item item, Object value, boolean killed, List<SelfCatalog.Need> missing, List<SelfCatalog.Need> unknown) {
            this.item = item;
            this.value = value;
            this.killed = killed;
            this.missing = missing;
            this.unknown = unknown;
        }

        public boolean on() {
            return Boolean.TRUE.equals(value);
        }

        public boolean changeable() {
            return item.chatChangeable && !killed && item.kind != SelfCatalog.Kind.PERMISSION;
        }

        /** 缺的前提里有没有这台手机补不上的。 */
        public boolean blockedForever() {
            for (SelfCatalog.Need n : missing) if (!n.fixable) return true;
            return false;
        }
    }

    public static Row row(SelfStore store, SelfCatalog.Item it) {
        Object v;
        try {
            v = store.read(it.id);
        } catch (Throwable t) {
            v = null;
        }
        boolean killed;
        try {
            killed = store.killed(it.id);
        } catch (Throwable t) {
            killed = false;
        }
        List<SelfCatalog.Need> miss = new ArrayList<SelfCatalog.Need>(), unk = new ArrayList<SelfCatalog.Need>();
        for (String nid : it.needs) {
            SelfCatalog.Need n = SelfCatalog.need(nid);
            if (n == null) continue;
            Tri t;
            try {
                t = store.need(nid);
            } catch (Throwable e) {
                t = Tri.UNKNOWN;
            }
            if (t == Tri.NO) miss.add(n);
            else if (t != Tri.YES) unk.add(n);
        }
        return new Row(it, v, killed, miss, unk);
    }

    public static List<Row> snapshot(SelfStore store) {
        List<Row> out = new ArrayList<Row>();
        for (SelfCatalog.Item it : SelfCatalog.all()) out.add(row(store, it));
        return out;
    }

    /** 给模型读的结构化清单（不含任何 Key / 隐私内容，只有开关状态）。 */
    public static JSONObject toJson(List<Row> rows) {
        JSONObject o = new JSONObject();
        try {
            JSONArray a = new JSONArray();
            for (Row r : rows) {
                JSONObject x = new JSONObject();
                x.put("setting", r.item.id);
                x.put("title", r.item.title);
                x.put("where", r.item.where);
                x.put("kind", r.item.kind == SelfCatalog.Kind.PERMISSION ? "system_permission" : (r.item.kind == SelfCatalog.Kind.CHOICE ? "choice" : "switch"));
                x.put("value", r.item.valueText(r.value));
                if (r.item.kind == SelfCatalog.Kind.CHOICE) {
                    JSONArray ch = new JSONArray();
                    for (String l : r.item.choiceLabels) ch.put(l);
                    x.put("choices", ch);
                }
                x.put("can_change_in_chat", r.changeable());
                x.put("needs_confirm", r.item.sensitive);
                if (r.killed) x.put("disabled_by_kill_file", true);
                if (!r.missing.isEmpty()) x.put("missing", needsJson(r.missing));
                if (!r.unknown.isEmpty()) x.put("unknown", needsJson(r.unknown));
                a.put(x);
            }
            o.put("settings", a);
            o.put("note", "这是 OneSay 自己的设置。system_permission 只能给用户跳转按钮（self.settings.open），不能直接改；needs_confirm=true 的用 self.settings.set_guarded（会弹确认卡）；"
                    + "其余用 self.settings.set。改完以返回的回读结果为准如实告诉用户。");
        } catch (Exception ignored) {
        }
        return o;
    }

    private static JSONArray needsJson(List<SelfCatalog.Need> ns) throws Exception {
        JSONArray a = new JSONArray();
        for (SelfCatalog.Need n : ns) {
            JSONObject x = new JSONObject();
            x.put("title", n.title);
            x.put("how", n.how);
            x.put("user_can_fix", n.fixable);
            a.put(x);
        }
        return a;
    }

    /** 导出日志头部用的纯文本清单。 */
    public static String plainText(List<Row> rows) {
        StringBuilder b = new StringBuilder();
        for (Row r : rows) {
            b.append(r.item.title).append("：").append(r.item.valueText(r.value));
            if (r.killed) b.append("（被关停文件停用）");
            if (!r.missing.isEmpty()) b.append("，还差 ").append(titles(r.missing));
            if (!r.unknown.isEmpty()) b.append("，查不到 ").append(titles(r.unknown));
            b.append('\n');
        }
        return b.toString();
    }

    static String titles(List<SelfCatalog.Need> ns) {
        StringBuilder b = new StringBuilder();
        for (SelfCatalog.Need n : ns) {
            if (b.length() > 0) b.append("、");
            b.append(n.title);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ 认句子

    public enum Intent { WHY, ON, OFF, STATUS, SET_CHOICE, OVERVIEW }

    public static final class Match {
        public final Intent intent;
        public final SelfCatalog.Item item;
        /** SET_CHOICE 的目标值。 */
        public final String choice;

        Match(Intent intent, SelfCatalog.Item item, String choice) {
            this.intent = intent;
            this.item = item;
            this.choice = choice;
        }

        @Override public String toString() {
            return intent + (item != null ? " " + item.id : "") + (choice != null ? "=" + choice : "");
        }
    }

    static final String[] WHY_WORDS = {"为什么", "为啥", "咋回事", "怎么回事", "用不了", "不能用", "不好使", "没反应", "不管用", "不工作", "不灵",
            "失灵", "开不了", "打不开", "开不开", "叫不出", "喊不出", "是灰的", "灰着", "变灰", "没法用", "无法使用", "用不上", "不起作用",
            "怎么开", "怎么打开", "怎么用", "怎么设置", "如何打开", "如何开启", "在哪开", "在哪里开", "在哪儿开", "连不上", "不能用了", "没用了"};
    static final String[] STATUS_WORDS = {"开着吗", "开了吗", "开了没", "开没开", "是开着", "有没有开", "关着吗", "关了吗", "关了没", "是什么状态",
            "什么状态", "现在是开", "现在是关", "是开的吗", "是关的吗", "打开了吗", "打开没"};
    static final String[] OFF_WORDS = {"关掉", "关了", "关闭", "关上", "停用", "禁用"};
    static final String[] SET_ON_WORDS = {"开启", "启用", "开开", "开一下", "开起来", "打开吧", "打开一下"};
    static final String[] CHOICE_WORDS = {"改成", "调成", "设成", "设为", "换成", "切成", "切到", "调到"};
    static final String[] SELF_WORDS = {"你的设置", "你自己的设置", "onesay的设置", "onesay设置", "自己的设置", "助手的设置", "你的功能", "onesay的功能"};
    static final String[] CHECK_WORDS = {"检查", "诊断", "看看", "查查", "查一下", "有什么问题", "哪里不对", "有没有问题", "有问题", "哪些没开", "哪些没配", "没配好"};

    /** 一句话是不是在说 OneSay 自己的设置。不是 → null。 */
    public static Match match(String query) {
        if (query == null) return null;
        String q = clean(query);
        if (q.length() == 0 || q.length() > 40) return null;
        if (has(q, SELF_WORDS) && has(q, CHECK_WORDS)) return new Match(Intent.OVERVIEW, null, null);
        if (q.equals("自检") || q.equals("自检一下") || q.contains("诊断一下你自己") || q.contains("检查一下你自己")) return new Match(Intent.OVERVIEW, null, null);
        SelfCatalog.Item it = null;
        String alias = null;
        int best = 0;
        for (SelfCatalog.Item i : SelfCatalog.all()) {
            List<String> names = new ArrayList<String>(i.aliases);
            names.add(i.title);
            for (String a : names) {
                String s = SelfCatalog.squash(a);
                if (s.length() > best && q.contains(s)) {
                    best = s.length();
                    it = i;
                    alias = s;
                }
            }
        }
        if (it == null) return null;
        // 别的 App / 系统功能里的同名东西（「微信的快捷方式」「系统的快捷开关里的手电筒」）不接
        int at = q.indexOf(alias);
        String before = q.substring(0, at), after = q.substring(at + alias.length());
        if (before.endsWith("的") && !before.endsWith("你的") && !before.endsWith("onesay的") && !before.endsWith("助手的")) return null;
        if (after.startsWith("里") || after.startsWith("中的") || after.startsWith("里面")) return null;
        if (has(q, STATUS_WORDS)) return new Match(Intent.STATUS, it, null);
        if (it.kind == SelfCatalog.Kind.CHOICE) {
            for (String w : CHOICE_WORDS) {
                int k = after.indexOf(w);
                if (k < 0) continue;
                String rest = after.substring(k + w.length());
                for (int c = 0; c < it.choiceLabels.size(); c++) {
                    if (rest.startsWith(SelfCatalog.squash(it.choiceLabels.get(c)))) return new Match(Intent.SET_CHOICE, it, it.choices.get(c));
                }
            }
        }
        if (has(q, WHY_WORDS)) {
            // 名字前面还挂着别的东西（「为什么微信的快捷方式…」「为什么抖音快捷方式…」）→ 说的不是 OneSay 自己的
            String left = before;
            for (String w : WHY_WORDS) left = left.replace(w, "");
            for (String w : new String[]{"你的", "onesay的", "onesay", "我的", "这个", "那个", "现在", "怎么", "的"}) left = left.replace(w, "");
            if (left.length() > 1) return null;
            return new Match(Intent.WHY, it, null);
        }
        boolean ba = before.endsWith("把") || before.endsWith("把你的") || before.endsWith("把onesay的");
        boolean switchWord = after.startsWith("开关") || after.startsWith("功能") || after.startsWith("选项") || after.startsWith("这一项") || after.startsWith("这个设置");
        if (has(after, OFF_WORDS) && (ba || after.length() <= 4 || switchWord)) return new Match(Intent.OFF, it, null);
        if (has(before, OFF_WORDS) && before.length() <= 6) return new Match(Intent.OFF, it, null);
        if (ba && (after.contains("打开") || has(after, SET_ON_WORDS) || after.startsWith("开"))) return new Match(Intent.ON, it, null);
        if (has(before, SET_ON_WORDS) && before.length() <= 6) return new Match(Intent.ON, it, null);
        if (before.endsWith("打开") && switchWord && before.length() <= 6) return new Match(Intent.ON, it, null);
        if (before.length() == 0 && (after.equals("打开") || after.equals("开启") || after.equals("打开一下") || after.equals("开一下") || after.equals("打开吧")
                || after.equals("启用"))) return new Match(Intent.ON, it, null);
        return null;
    }

    static String clean(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c) || c == '\u3000') continue;
            if ("，。！？、,.!?；;：:~～…「」\"“”'‘’（）()".indexOf(c) >= 0) continue;
            b.append(Character.toLowerCase(c));
        }
        String t = b.toString();
        // 礼貌前缀不影响判断
        String[] pre = {"请你", "请", "麻烦你", "麻烦", "帮我", "帮忙", "给我", "你帮我", "能不能帮我", "能不能", "可以帮我"};
        boolean again = true;
        while (again) {
            again = false;
            for (String p : pre) {
                if (t.startsWith(p) && t.length() > p.length()) {
                    t = t.substring(p.length());
                    again = true;
                }
            }
        }
        return t;
    }

    static boolean has(String q, String[] words) {
        for (String w : words) if (q.contains(w)) return true;
        return false;
    }

    // ------------------------------------------------------------------ 回答

    /** 回答下面的按钮。 */
    public static final class Chip {
        public enum Kind { SET, OPEN }

        public final Kind kind;
        public final String label;
        public final String itemId;
        public final Object value;
        public final SelfCatalog.Target target;

        Chip(Kind kind, String label, String itemId, Object value, SelfCatalog.Target target) {
            this.kind = kind;
            this.label = label;
            this.itemId = itemId;
            this.value = value;
            this.target = target;
        }

        @Override public String toString() {
            return kind + ":" + label;
        }
    }

    public static final class Reply {
        public final String text;
        public final List<Chip> chips;

        Reply(String text, List<Chip> chips) {
            this.text = text;
            this.chips = chips;
        }
    }

    static final int MAX_CHIPS = 3;

    static void openChips(List<Chip> chips, List<SelfCatalog.Need> needs) {
        for (SelfCatalog.Need n : needs) {
            if (chips.size() >= MAX_CHIPS) return;
            if (!n.fixable || n.target == SelfCatalog.Target.NONE) continue;
            chips.add(new Chip(Chip.Kind.OPEN, "去打开" + n.title, null, null, n.target));
        }
    }

    static String howLines(List<SelfCatalog.Need> needs) {
        StringBuilder b = new StringBuilder();
        for (SelfCatalog.Need n : needs) b.append("\n· ").append(n.title).append("：").append(n.how);
        return b.toString();
    }

    /** 「为什么 X 用不了」「X 开着吗」。 */
    public static Reply diagnose(Row r, Row assistOn) {
        List<Chip> chips = new ArrayList<Chip>();
        SelfCatalog.Item it = r.item;
        StringBuilder t = new StringBuilder();
        if (assistOn != null && Boolean.FALSE.equals(assistOn.value) && it.kind != SelfCatalog.Kind.PERMISSION) {
            t.append("先说一句：助手现在是暂停的（设置首页顶上的总开关关着），哪个入口都叫不出我。\n");
        }
        if (it.kind == SelfCatalog.Kind.PERMISSION) {
            if (r.value == null) {
                t.append("「").append(it.title).append("」现在开没开，我查不到。");
            } else if (r.on()) {
                t.append("「").append(it.title).append("」已经开了。");
            } else {
                SelfCatalog.Need pn = it.needId == null ? null : SelfCatalog.need(it.needId);
                t.append("「").append(it.title).append("」现在没开。这是系统权限，OneSay 自己改不了，点下面的按钮跳过去")
                        .append(pn == null ? "" : "：" + pn.how).append("。");
                chips.add(new Chip(Chip.Kind.OPEN, "去打开" + it.title, null, null, it.page));
            }
            return new Reply(t.toString(), chips);
        }
        if (it.kind == SelfCatalog.Kind.CHOICE) {
            t.append("「").append(it.title).append("」现在是「").append(it.valueText(r.value)).append("」，可选：");
            for (int i = 0; i < it.choiceLabels.size(); i++) t.append(i == 0 ? "" : " / ").append(it.choiceLabels.get(i));
            t.append("。想换就说「把").append(it.title).append("改成…」。位置：").append(it.where).append("。");
            return new Reply(t.toString(), chips);
        }
        if (r.killed) {
            t.append("「").append(it.title).append("」被关停文件停用了，开关现在改不动。");
            return new Reply(t.toString(), chips);
        }
        if (r.value == null) {
            t.append("「").append(it.title).append("」现在是开是关，我读不到。位置：").append(it.where).append("。");
            chips.add(new Chip(Chip.Kind.OPEN, "去设置里看看", null, null, it.page));
            return new Reply(t.toString(), chips);
        }
        if (!r.on()) {
            t.append("「").append(it.title).append("」现在是关着的，所以用不了。");
            if (!r.missing.isEmpty()) t.append("另外还差：").append(howLines(r.missing));
            if (r.blockedForever()) {
                t.append("\n这台手机缺的条件补不上，打开了也用不了。");
            } else if (r.changeable()) {
                chips.add(new Chip(Chip.Kind.SET, "帮我打开", it.id, Boolean.TRUE, null));
                if (it.sensitive) t.append("\n这一项打开前要你确认一下。");
            } else {
                t.append("\n这一项不在聊天里改，位置：").append(it.where).append("。");
            }
            openChips(chips, r.missing);
            return new Reply(t.toString(), chips);
        }
        if (!r.missing.isEmpty()) {
            t.append("「").append(it.title).append("」开着，但还差：").append(howLines(r.missing));
            if (r.blockedForever()) t.append("\n其中有这台手机补不上的，这个入口在这台手机上用不了。");
            openChips(chips, r.missing);
            return new Reply(t.toString(), chips);
        }
        t.append("「").append(it.title).append("」开着");
        if (!it.needs.isEmpty() && r.unknown.isEmpty()) t.append("，需要的条件也都满足了");
        t.append("。");
        if (!r.unknown.isEmpty()) t.append("有几项我查不到：").append(titles(r.unknown)).append("。");
        if (it.onHint != null) t.append("\n提醒：").append(it.onHint).append("。");
        t.append("\n要是还是不行，到 设置 › 高级 › 导出日志，把日志发给开发者看看。");
        return new Reply(t.toString(), chips);
    }

    /** 「检查一下你的设置」：只列有问题的（开着但缺前提 / 系统权限没开）。 */
    public static Reply overview(List<Row> rows) {
        List<Chip> chips = new ArrayList<Chip>();
        StringBuilder t = new StringBuilder();
        int n = 0;
        List<SelfCatalog.Need> fix = new ArrayList<SelfCatalog.Need>();
        for (Row r : rows) {
            if (r.item.kind == SelfCatalog.Kind.PERMISSION) continue;
            if (r.item.id.equals("assist.on") && Boolean.FALSE.equals(r.value)) {
                t.append("\n· 助手现在是暂停的（设置首页顶上的总开关）");
                n++;
                continue;
            }
            if (!r.on() || r.missing.isEmpty()) continue;
            t.append("\n· 「").append(r.item.title).append("」开着，但还差 ").append(titles(r.missing));
            for (SelfCatalog.Need x : r.missing) if (!containsNeed(fix, x)) fix.add(x);
            n++;
        }
        if (n == 0) return new Reply("看了一遍：开着的功能需要的权限和条件都齐了，没发现问题。", chips);
        openChips(chips, fix);
        return new Reply("看了一遍，有 " + n + " 处要注意：" + t, chips);
    }

    private static boolean containsNeed(List<SelfCatalog.Need> l, SelfCatalog.Need n) {
        for (SelfCatalog.Need x : l) if (x.id.equals(n.id)) return true;
        return false;
    }

    // ------------------------------------------------------------------ 改

    public enum Plan { DIRECT, CONFIRM, ALREADY, SYSTEM_ONLY, NOT_IN_CHAT, KILLED, BLOCKED, BAD_VALUE, UNKNOWN_ITEM }

    /** 能不能改、怎么改（不写任何东西）。 */
    public static Plan plan(Row r, Object want) {
        if (r == null) return Plan.UNKNOWN_ITEM;
        SelfCatalog.Item it = r.item;
        if (it.kind == SelfCatalog.Kind.PERMISSION) return Plan.SYSTEM_ONLY;
        if (!it.chatChangeable) return Plan.NOT_IN_CHAT;
        if (r.killed) return Plan.KILLED;
        Object w = normalizeValue(it, want);
        if (w == null) return Plan.BAD_VALUE;
        if (r.value != null && String.valueOf(r.value).equals(String.valueOf(w))) return Plan.ALREADY;
        if (Boolean.TRUE.equals(w) && r.blockedForever()) return Plan.BLOCKED;
        return it.sensitive ? Plan.CONFIRM : Plan.DIRECT;
    }

    /** 模型 / 句子给的值 → 存储值（开关：true/false；多选：取值）。认不出 → null。 */
    public static Object normalizeValue(SelfCatalog.Item it, Object v) {
        if (it == null || v == null) return null;
        if (it.kind == SelfCatalog.Kind.CHOICE) {
            String s = SelfCatalog.squash(String.valueOf(v));
            for (int i = 0; i < it.choices.size(); i++) {
                if (it.choices.get(i).equals(s) || SelfCatalog.squash(it.choiceLabels.get(i)).equals(s)) return it.choices.get(i);
            }
            return null;
        }
        if (v instanceof Boolean) return v;
        String s = SelfCatalog.squash(String.valueOf(v));
        if (s.equals("true") || s.equals("on") || s.equals("开") || s.equals("打开") || s.equals("开启") || s.equals("1") || s.equals("启用")) return Boolean.TRUE;
        if (s.equals("false") || s.equals("off") || s.equals("关") || s.equals("关闭") || s.equals("关掉") || s.equals("0") || s.equals("停用")) return Boolean.FALSE;
        return null;
    }

    /** 不改的那几种情况怎么跟用户说（ALREADY / SYSTEM_ONLY / NOT_IN_CHAT / KILLED / BLOCKED / BAD_VALUE）。 */
    public static Reply explainNoChange(Plan p, Row r, Object want) {
        List<Chip> chips = new ArrayList<Chip>();
        if (r == null) return new Reply("OneSay 的设置里没有这一项。", chips);
        SelfCatalog.Item it = r.item;
        String name = "「" + it.title + "」";
        switch (p) {
            case ALREADY: {
                StringBuilder t = new StringBuilder(name + "本来就是" + it.valueText(normalizeValue(it, want)) + "的，没动它。");
                if (r.on() && !r.missing.isEmpty()) {
                    t.append("不过它还差：").append(howLines(r.missing));
                    openChips(chips, r.missing);
                }
                return new Reply(t.toString(), chips);
            }
            case SYSTEM_ONLY:
                chips.add(new Chip(Chip.Kind.OPEN, "去打开" + it.title, null, null, it.page));
                SelfCatalog.Need pn = it.needId == null ? null : SelfCatalog.need(it.needId);
                return new Reply(name + "是系统权限，OneSay 不能替你改。点下面的按钮跳过去" + (pn == null ? "" : "：" + pn.how) + "。", chips);
            case NOT_IN_CHAT:
                return new Reply(name + "不在聊天里改" + ("assist.on".equals(it.id) ? "（关了就听不到你说话了）" : "") + "。位置：" + it.where + "。", chips);
            case KILLED:
                return new Reply(name + "被关停文件停用了，现在改不动。", chips);
            case BLOCKED:
                return new Reply(name + "在这台手机上打不开：" + howLines(r.missing), chips);
            case BAD_VALUE:
                if (it.kind == SelfCatalog.Kind.CHOICE) {
                    StringBuilder t = new StringBuilder(name + "只能选：");
                    for (int i = 0; i < it.choiceLabels.size(); i++) t.append(i == 0 ? "" : " / ").append(it.choiceLabels.get(i));
                    return new Reply(t.append("。").toString(), chips);
                }
                return new Reply(name + "只能开或关。", chips);
            default:
                return new Reply("OneSay 的设置里没有这一项。", chips);
        }
    }

    /** 改完的结果。 */
    public static final class Outcome {
        public final boolean ok;
        public final boolean verified;
        public final String text;
        public final List<Chip> chips;
        public final Object before, after;

        Outcome(boolean ok, boolean verified, String text, List<Chip> chips, Object before, Object after) {
            this.ok = ok;
            this.verified = verified;
            this.text = text;
            this.chips = chips;
            this.before = before;
            this.after = after;
        }
    }

    /**
     * 写 → 回读 → 核对。门禁（敏感项的确认卡）在调用方之前已经过了；这里只管写和如实报。
     */
    public static Outcome apply(SelfStore store, String itemId, Object want) {
        SelfCatalog.Item it = SelfCatalog.find(itemId);
        List<Chip> chips = new ArrayList<Chip>();
        if (it == null) return new Outcome(false, false, "OneSay 的设置里没有「" + itemId + "」这一项。", chips, null, null);
        Row before = row(store, it);
        Plan p = plan(before, want);
        if (p != Plan.DIRECT && p != Plan.CONFIRM) {
            Reply rp = explainNoChange(p, before, want);
            return new Outcome(p == Plan.ALREADY, p == Plan.ALREADY, rp.text, rp.chips, before.value, before.value);
        }
        Object w = normalizeValue(it, want);
        boolean wrote;
        try {
            wrote = store.write(it.id, w);
        } catch (Throwable t) {
            wrote = false;
        }
        Row after = row(store, it);
        String name = "「" + it.title + "」";
        if (!wrote) return new Outcome(false, false, "没改成：写" + name + "的时候出错了，现在还是" + it.valueText(after.value) + "。", chips, before.value, after.value);
        if (after.value == null || !String.valueOf(after.value).equals(String.valueOf(w))) {
            return new Outcome(false, false, "改了，但读回来" + name + "还是「" + it.valueText(after.value) + "」，没改成。", chips, before.value, after.value);
        }
        StringBuilder t = new StringBuilder();
        if (it.kind == SelfCatalog.Kind.CHOICE) t.append("已把").append(name).append("改成「").append(it.valueText(w)).append("」");
        else t.append(Boolean.TRUE.equals(w) ? "已打开" : "已关掉").append(name);
        t.append("，读回来核对过了。");
        if (Boolean.TRUE.equals(w)) {
            if (!after.missing.isEmpty()) {
                t.append("\n不过还差一步才能用：").append(howLines(after.missing));
                openChips(chips, after.missing);
            }
            if (it.onHint != null) t.append("\n提醒：").append(it.onHint).append("。");
        }
        return new Outcome(true, true, t.toString(), chips, before.value, after.value);
    }

    /** 确认卡标题：「把「外部 App 唤起」从 关 改成 开」。 */
    public static String changeSummary(Row r, Object want) {
        if (r == null) return "改 OneSay 的一项设置";
        Object w = normalizeValue(r.item, want);
        return "把「" + r.item.title + "」从 " + r.item.valueText(r.value) + " 改成 " + r.item.valueText(w);
    }
}
