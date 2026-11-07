/*
 * OneSay 自写（skill-pack，2026-11-02），非上游移植。core 包只依赖 java.*。
 */
package com.onesay.assistant.plug.skills.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 一个技能 = 一份 {@code skill_<id>.md}：头部（触发条件 / 预算 / 依赖 / 冲突）+ 正文（步骤、坑、确认规则）。不可变。
 *
 * <pre>
 * ---
 * id: msg_send                      # [a-z][a-z0-9_]*，文件名必须是 skill_&lt;id&gt;.md
 * name: 发微信 / QQ 消息
 * description: 一句话说明（LLM 兜底挑技能时看这个）
 * priority: 60                      # 同分时高者先；0–100
 * budget: 600                       # 正文进提示词的字数上限（含标题行）
 * paths: [agent]                    # agent = AgentLoop；ask = 自由问答
 * triggers:
 *   keywords: [发微信, 微信发]       # 每个命中 +3（≥4 字 +4），合计封顶 8
 *   regex: ["(给|跟).{1,8}(发|说)"]  # 每条命中 +5，合计封顶 8
 *   intents: [map]                  # 候选工具前几名的 domain / category，每个 +2，封顶 4
 *   packages: [com.tencent.mm]      # 前台包名命中 +3
 *   screen: [发送, 按住 说话]        # 唤醒时屏幕上的文字，每个 +2，封顶 4
 *   examples: [给妈妈发微信说我到了]  # 字二元组余弦 ≥ 0.45 时 +round(cos*6)（轻量模糊匹配，ASR 错字容忍）
 *   exclude: [朋友圈]                # 命中任一 = 0 分
 * requires:
 *   tools: [phone.operate, gui.unfold]   # 任一在本轮工具表里才加载（工具名必须真实存在，单测守）
 *   perms: [无障碍]                  # 只作说明
 *   root: false                      # true = 宿主报告无 root 时不加载
 * conflicts: [sms_send]               # 同时命中时只留分高的
 * status: active                      # draft = 草稿，永不加载
 * ---
 * </pre>
 */
public final class Skill {
    public static final int DEFAULT_BUDGET = 600, DEFAULT_PRIORITY = 50;
    public static final Pattern ID = Pattern.compile("[a-z][a-z0-9_]*");

    public final String id, name, description, body, source, status;
    public final int priority, budget;
    public final List<String> paths, keywords, intents, packages, screen, examples, exclude, tools, perms, conflicts;
    public final List<Pattern> regex;
    public final boolean root;
    /** 解析时的告警（不致命：单条正则坏了、未知键等）。 */
    public final List<String> warnings;

    private Skill(Builder b) {
        id = b.id;
        name = b.name;
        description = b.description;
        body = b.body;
        source = b.source;
        status = b.status;
        priority = b.priority;
        budget = b.budget;
        paths = ro(b.paths);
        keywords = ro(lower(b.keywords));
        intents = ro(lower(b.intents));
        packages = ro(b.packages);
        screen = ro(b.screen);
        examples = ro(b.examples);
        exclude = ro(lower(b.exclude));
        tools = ro(b.tools);
        perms = ro(b.perms);
        conflicts = ro(b.conflicts);
        regex = Collections.unmodifiableList(new ArrayList<Pattern>(b.regex));
        root = b.root;
        warnings = ro(b.warnings);
    }

    public boolean forPath(String path) {
        return paths.isEmpty() ? "agent".equals(path) : paths.contains(path);
    }

    public boolean isDraft() {
        return "draft".equalsIgnoreCase(status);
    }

    /** 解析失败的原因（致命：缺 id / 坏头部）。 */
    public static final class ParseError extends Exception {
        public ParseError(String m) {
            super(m);
        }
    }

    static final String[] KNOWN_TOP = {"id", "name", "description", "priority", "budget", "paths", "triggers", "requires", "conflicts", "status", "source"};
    static final String[] KNOWN_TRIG = {"keywords", "regex", "intents", "packages", "screen", "examples", "exclude"};
    static final String[] KNOWN_REQ = {"tools", "perms", "root"};

    /** 从文件内容解析。头部坏到读不出 id / 正文为空 → 抛 {@link ParseError}；次要问题进 warnings。 */
    @SuppressWarnings("unchecked")
    public static Skill parse(String fileName, String markdown) throws ParseError {
        MiniYaml.Result y = MiniYaml.parseDocument(markdown);
        if (!y.hasFrontMatter) throw new ParseError(fileName + "：" + (y.errors.isEmpty() ? "缺少头部" : y.errors.get(0)));
        if (!y.errors.isEmpty()) throw new ParseError(fileName + "：头部有错 " + y.errors);
        Builder b = new Builder();
        b.source = fileName;
        b.id = str(y.map.get("id"));
        if (!ID.matcher(b.id).matches()) throw new ParseError(fileName + "：id 不合法「" + b.id + "」");
        b.name = str(y.map.get("name"));
        if (b.name.length() == 0) b.name = b.id;
        b.description = str(y.map.get("description"));
        b.status = str(y.map.get("status"));
        b.priority = intOr(y.map.get("priority"), DEFAULT_PRIORITY, "priority", b.warnings);
        b.budget = intOr(y.map.get("budget"), DEFAULT_BUDGET, "budget", b.warnings);
        if (b.budget < 80) {
            b.warnings.add("budget 太小，按 80");
            b.budget = 80;
        }
        b.paths = list(y.map.get("paths"));
        b.conflicts = list(y.map.get("conflicts"));
        b.body = y.body;
        if (b.body.length() == 0) throw new ParseError(fileName + "：正文为空");
        for (String k : y.map.keySet()) if (!in(k, KNOWN_TOP)) b.warnings.add("未知键：" + k);
        Object tr = y.map.get("triggers");
        if (tr instanceof Map) {
            Map<String, Object> t = (Map<String, Object>) tr;
            for (String k : t.keySet()) if (!in(k, KNOWN_TRIG)) b.warnings.add("未知触发键：" + k);
            b.keywords = list(t.get("keywords"));
            b.intents = list(t.get("intents"));
            b.packages = list(t.get("packages"));
            b.screen = list(t.get("screen"));
            b.examples = list(t.get("examples"));
            b.exclude = list(t.get("exclude"));
            for (String rx : list(t.get("regex"))) {
                try {
                    b.regex.add(Pattern.compile(rx, Pattern.CASE_INSENSITIVE));
                } catch (PatternSyntaxException e) {
                    b.warnings.add("正则坏了，已跳过：" + rx);
                }
            }
        } else if (tr != null) b.warnings.add("triggers 不是映射");
        Object rq = y.map.get("requires");
        if (rq instanceof Map) {
            Map<String, Object> q = (Map<String, Object>) rq;
            for (String k : q.keySet()) if (!in(k, KNOWN_REQ)) b.warnings.add("未知 requires 键：" + k);
            b.tools = list(q.get("tools"));
            b.perms = list(q.get("perms"));
            b.root = "true".equalsIgnoreCase(str(q.get("root")));
        }
        if (b.keywords.isEmpty() && b.regex.isEmpty() && b.examples.isEmpty() && b.packages.isEmpty() && b.screen.isEmpty())
            b.warnings.add("没有任何触发条件，永远不会被规则命中");
        return new Skill(b);
    }

    /** 测试 / 草稿用的构造器。 */
    public static final class Builder {
        String id = "", name = "", description = "", body = "", source = "", status = "";
        int priority = DEFAULT_PRIORITY, budget = DEFAULT_BUDGET;
        List<String> paths = new ArrayList<String>(), keywords = new ArrayList<String>(), intents = new ArrayList<String>(),
                packages = new ArrayList<String>(), screen = new ArrayList<String>(), examples = new ArrayList<String>(),
                exclude = new ArrayList<String>(), tools = new ArrayList<String>(), perms = new ArrayList<String>(),
                conflicts = new ArrayList<String>(), warnings = new ArrayList<String>();
        List<Pattern> regex = new ArrayList<Pattern>();
        boolean root;
    }

    static boolean in(String k, String[] arr) {
        for (String a : arr) if (a.equals(k)) return true;
        return false;
    }

    static int intOr(Object o, int def, String key, List<String> warn) {
        String s = str(o);
        if (s.length() == 0) return def;
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            warn.add(key + " 不是整数：" + s);
            return def;
        }
    }

    static String str(Object o) {
        return o instanceof String ? ((String) o).trim() : "";
    }

    @SuppressWarnings("unchecked")
    static List<String> list(Object o) {
        List<String> l = new ArrayList<String>();
        if (o instanceof List) {
            for (Object x : (List<Object>) o) if (x instanceof String && ((String) x).trim().length() > 0) l.add(((String) x).trim());
        } else if (o instanceof String && ((String) o).trim().length() > 0) l.add(((String) o).trim());
        return l;
    }

    static List<String> lower(List<String> l) {
        List<String> o = new ArrayList<String>();
        for (String s : l) o.add(s.toLowerCase(Locale.ROOT));
        return o;
    }

    static List<String> ro(List<String> l) {
        return Collections.unmodifiableList(new ArrayList<String>(l));
    }

    @Override public String toString() {
        return id;
    }
}
