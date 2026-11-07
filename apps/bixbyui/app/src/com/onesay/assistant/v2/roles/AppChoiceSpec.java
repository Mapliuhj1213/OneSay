package com.onesay.assistant.v2.roles;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [r2/bike-chooser] 「应用选择」的结构化描述：要用哪一类 App 做事（共享单车、外卖…）、候选 App、应用商店搜索词。
 * 纯 Java，JVM 可测。
 *
 * <p>两个来源，最后都变成同一份 {@code role_ui}（kind=chooser）交给 {@link RoleHooks} 画 2×2 应用网格：
 * <ul>
 *   <li><b>本地角色层</b>（assets/app_roles.json，比如「我想扫共享单车」→ bike 角色）：确定性，不调大模型；
 *       搜索词取角色的 {@code store_query}，没写就用角色名。</li>
 *   <li><b>大模型</b>：在<b>同一次</b>输出里一并给出（不额外再调一次）。两种写法都认：
 *     <ul>
 *       <li>编译通道 JSON：{@code {"sign":"ask.reply","args":{"text":"…","app_choice":{…}}}}；</li>
 *       <li>自由问答 / Agent 循环的自然语言终稿末尾附一段 {@code <app_choice>{…}</app_choice>}（上屏前剥掉）。</li>
 *     </ul>
 *     字段：{@code category}（软件类别，如「共享单车」）、{@code store_query}（应用商店搜索词）、
 *     {@code apps}（[{label, pkg}]，最多取 8 个，网格只显示 4 个）。</li>
 * </ul>
 * 大模型没给 store_query → 回退到 category → 再回退到用户原话里的关键词；三者都做长度 / 字符清洗（{@link #sanitizeQuery}）。
 */
public final class AppChoiceSpec {

    /** 网格最多显示几个（每行 2 个 × 2 行，用户 2026-10-30 定）。 */
    public static final int GRID_MAX = 4;
    /** 解析时最多保留几个候选（多的直接丢；网格再按已安装优先截到 4 个）。 */
    public static final int RAW_MAX = 8;
    /** 商店搜索词最长几个字符（按 code point 计）。 */
    public static final int QUERY_MAX = 12;
    /** 应用名最长几个字符（再长 UI 也会省略，这里只防模型塞一段话进来）。 */
    public static final int LABEL_MAX = 16;

    /** 自然语言终稿里的结构化尾巴。非贪婪 + DOTALL，只认第一段。 */
    static final Pattern MARKER = Pattern.compile("<app_choice>\\s*(\\{.*?\\})\\s*</app_choice>", Pattern.DOTALL);
    /** 没闭合 / 写坏的尾巴也要从上屏文字里剥掉（不能把半截 JSON 念给用户）。 */
    static final Pattern MARKER_OPEN = Pattern.compile("<app_choice>.*$", Pattern.DOTALL);

    /**
     * 商店搜索词允许的字符：文字（含汉字，\p{L}）、数字、空格、· - + 。其余（引号、尖括号、URL 符号、控制字符、emoji）一律去掉。
     * ★ 只用 Android（ICU 正则）也认的写法：真机上 {@code \p{IsHan}} 会在类初始化时抛 PatternSyntaxException，
     *   连带 RoleCatalog 解析失败、整个角色层变成 0 个角色（2026-10-30 真机发现）。
     */
    private static final Pattern QUERY_BAD = Pattern.compile("[^\\p{L}\\p{N} \u00B7\\-+]");
    /** 从用户原话里提关键词时去掉的口头成分。 */
    private static final Pattern QUERY_FILLER = Pattern.compile(
            "^(?:请|麻烦|帮我|给我|我要|我想|我想要|我需要|想|要|你帮我|能不能|可以)+"
                    + "|(?:一下|吧|呗|啊|呀|了|的软件|的app|的应用|软件|应用|app|APP|App)$"
                    + "|^(?:打开|启动|用|下载|安装|找个|找一个|找|扫一扫|扫个|扫码|扫)");

    public final String category;
    public final String storeQuery;
    public final List<Option> options;

    public static final class Option {
        public final String label;
        public final String pkg;
        public final String id;

        public Option(String id, String label, String pkg) {
            this.id = id;
            this.label = label;
            this.pkg = pkg;
        }

        @Override public String toString() {
            return label + "(" + pkg + ")";
        }
    }

    AppChoiceSpec(String category, String storeQuery, List<Option> options) {
        this.category = category;
        this.storeQuery = storeQuery;
        this.options = Collections.unmodifiableList(options);
    }

    // ------------------------------------------------------------------ 终稿里的尾巴

    /** 剥尾巴的结果：上屏文字 + 解析出来的 JSON（没有或坏了为 null）。 */
    public static final class Extracted {
        public final String text;
        public final JSONObject json;

        Extracted(String text, JSONObject json) {
            this.text = text;
            this.json = json;
        }
    }

    /** 从大模型的自然语言终稿里剥出 {@code <app_choice>{…}</app_choice>}。text 为 null 时原样返回。 */
    public static Extracted extract(String text) {
        if (text == null) return new Extracted(null, null);
        Matcher m = MARKER.matcher(text);
        JSONObject json = null;
        String clean = text;
        if (m.find()) {
            try {
                json = new JSONObject(m.group(1));
            } catch (Exception ignored) {
                json = null;
            }
            clean = text.substring(0, m.start()) + text.substring(m.end());
        }
        clean = MARKER_OPEN.matcher(clean).replaceAll("");
        return new Extracted(clean.trim(), json);
    }

    /** 把已解析的尾巴以规范形式接回文末（让它穿过只传字符串的流式回调，到 Invocation 构造处再剥成 args.app_choice）。 */
    public static String reattach(String text, JSONObject json) {
        if (json == null) return text;
        return (text == null ? "" : text) + "\n<app_choice>" + json.toString() + "</app_choice>";
    }

    /** 上屏前剥掉尾巴（没有尾巴 = 原样）。 */
    public static String stripForDisplay(String text) {
        if (text == null || text.indexOf("<app_choice>") < 0) return text;
        return extract(text).text;
    }

    // ------------------------------------------------------------------ 解析

    /**
     * @param o     大模型给的 app_choice 对象（可空）
     * @param query 用户原话（store_query / category 都没给时从这里提关键词）
     * @return 至少有 1 个合法候选才返回；否则 null（不弹网格，照常只显示文字）
     */
    public static AppChoiceSpec parse(JSONObject o, String query) {
        if (o == null) return null;
        List<Option> opts = new ArrayList<Option>();
        JSONArray apps = o.optJSONArray("apps");
        if (apps != null) {
            for (int i = 0; i < apps.length() && opts.size() < RAW_MAX; i++) {
                JSONObject a = apps.optJSONObject(i);
                if (a == null) continue;
                String pkg = a.optString("pkg", "").trim();
                if (!RoleCatalog.PKG.matcher(pkg).matches() || pkg.length() > 120) continue;
                boolean dup = false;
                for (Option x : opts) if (x.pkg.equals(pkg)) dup = true;
                if (dup) continue;
                String label = cleanLabel(a.optString("label", ""));
                if (label == null) label = pkg;
                opts.add(new Option("llm" + opts.size(), label, pkg));
            }
        }
        if (opts.isEmpty()) return null;
        String category = sanitizeQuery(o.optString("category", ""));
        String sq = resolveQuery(o.optString("store_query", ""), category, query);
        return new AppChoiceSpec(category, sq, opts);
    }

    /** 搜索词回退链：模型给的 store_query → 类别名 → 用户原话关键词 → null（不显示「查看更多」）。 */
    public static String resolveQuery(String storeQuery, String category, String userQuery) {
        String s = sanitizeQuery(storeQuery);
        if (s != null) return s;
        s = sanitizeQuery(category);
        if (s != null) return s;
        return keywordsOf(userQuery);
    }

    /** 用户原话 → 关键词（「我想扫共享单车」→「共享单车」；「帮我找个剪视频的软件」→「剪视频」）。 */
    public static String keywordsOf(String userQuery) {
        if (userQuery == null) return null;
        String r = userQuery.trim().replaceAll("[，。！？,.!?、；;：:\\s]+$", "");
        String prev;
        int guard = 0;
        do {
            prev = r;
            r = QUERY_FILLER.matcher(r).replaceAll("").trim();
            r = r.replaceAll("^(?:个|一个|一款|款)", "").replaceAll("的$", "").trim();
        } while (!r.equals(prev) && ++guard < 8);
        return sanitizeQuery(r);
    }

    /** 清洗搜索词：去掉不允许的字符、压空白、截到 {@value #QUERY_MAX} 个字符；结果为空返回 null。 */
    public static String sanitizeQuery(String s) {
        if (s == null) return null;
        String r = QUERY_BAD.matcher(s).replaceAll(" ");
        r = r.replaceAll("\\s+", " ").trim();
        if (r.length() == 0) return null;
        if (r.codePointCount(0, r.length()) > QUERY_MAX) {
            r = r.substring(0, r.offsetByCodePoints(0, QUERY_MAX)).trim();
        }
        // 全是符号（· - +）也不要
        if (r.replaceAll("[ ·\\-+]", "").length() == 0) return null;
        return r;
    }

    static String cleanLabel(String s) {
        if (s == null) return null;
        String r = s.replaceAll("[\\u0000-\\u001F\\u007F<>\"`]", "").replaceAll("\\s+", " ").trim();
        if (r.length() == 0) return null;
        if (r.codePointCount(0, r.length()) > LABEL_MAX) r = r.substring(0, r.offsetByCodePoints(0, LABEL_MAX));
        return r;
    }

    // ------------------------------------------------------------------ 变成 role_ui

    /**
     * 变成与本地角色层同一形状的 role_ui（kind=chooser，source=llm），已安装状态由调用方按设备填。
     * 不写默认（LLM 给的候选不属于任何角色）。
     */
    public JSONObject toRoleUi(DeviceSnapshot dev) {
        JSONObject ui = new JSONObject();
        try {
            ui.put("kind", "chooser");
            ui.put("source", "llm");
            ui.put("role", "");
            ui.put("role_label", category == null ? "" : category);
            ui.put("mode", "open");
            if (storeQuery != null) ui.put("store_query", storeQuery);
            JSONArray arr = new JSONArray();
            for (Option op : options) {
                JSONObject o = new JSONObject();
                o.put("id", op.id);
                o.put("label", op.label);
                o.put("pkg", op.pkg);
                o.put("installed", dev != null && dev.isInstalled(op.pkg));
                arr.put(o);
            }
            ui.put("options", arr);
        } catch (Exception ignored) {
        }
        return ui;
    }

    @Override public String toString() {
        return "AppChoice{" + category + " q=" + storeQuery + " " + options + "}";
    }

    // ------------------------------------------------------------------ 提示词

    /**
     * 追加到三个通道（编译 / 自由问答 / Agent 循环）系统提示词末尾的一段规则。放在模板渲染之后追加，
     * 用户热改模板（files/prompts）也不会把它丢掉。
     */
    public static final String PROMPT_RULE =
            "\n【应用选择（可选的结构化尾巴）】\n"
                    + "当用户想用**某一类第三方 App** 做一件事（例如「扫共享单车」「剪个视频」「坐地铁刷码」），而你没有直接执行"
                    + "（没有合适工具、App 没装、或有好几个 App 都能做），在回答**末尾**另起一行附上：\n"
                    + "<app_choice>{\"category\":\"软件类别名\",\"store_query\":\"应用商店搜索词\",\"apps\":[{\"label\":\"App 名\",\"pkg\":\"包名\"}]}</app_choice>\n"
                    + "· category / store_query 写这类软件的通用类别名（2~8 个字，如「共享单车」），不要写品牌、句子、引号或网址；\n"
                    + "· apps 最多 4 个，按国内常用程度排序，pkg 必须是你确定的真实安卓包名，不确定就不要写那一个；\n"
                    + "· 尾巴只写一次，系统会把它画成可点的应用格子（已安装→打开，未安装→下载）并从文字里去掉，正文里不要再提这段 JSON；\n"
                    + "· 用户**点名了某个具体 App**（如「打开小红书」「用闲鱼搜公路车」）而它没装（工具返回 NOT_FOUND_APP）时也要附：category 写它的类别（如「社区种草」「二手交易」），apps 第一个放它本身，再放 1~3 个同类；\n"
                    + "· 纯知识问答、闲聊、已经替用户打开了 App 的情况，不要附这段。\n"
                    + "· 用 JSON 输出（编译通道）时，改为放进 args.app_choice 字段，内容相同。\n";

    static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }
}
