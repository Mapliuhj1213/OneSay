package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.roles.KnownApps;

import org.json.JSONObject;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ audit-J 2（Gemini 真机 UNINST-01/02）：「打开抖音」「在小红书搜穿搭」——点名的常见 App 没装时，
 * AgentLoop 只调两次 probe.apps 就缩进灵动岛，从来不下发 app.launch，安装卡（KnownApps + RoleHooks.onExecFailed）永远不出。
 *
 * <p>现在 UnifiedRouter 进 AgentLoop 之前本地拦截：核心句是「打开 / 启动 / 开一下 X」或「在 / 用 / 去 X(上|里) 搜 / 搜索 / 找 / 查 Y」，
 * X 经 {@link KnownApps#pkgOf} 认得且本机没装（包名没装、标签也解析不到）→ 直接下发 app.launch {app, pkg}（不带 role），
 * 执行器返回 NOT_FOUND_APP、面板出「未安装 X，点此安装」卡，回一句大白话；点安装登记 InstallWait，续办句 = 原话。
 * X 已装 / 不认得 → null（照旧）。纯函数，JVM 可测。
 */
public final class MissingAppIntercept {

    private MissingAppIntercept() {}

    /** args 里给执行器的大白话（NOT_FOUND_APP 时代替「抱歉，手机上没有找到应用…」）。 */
    public static final String ARG_REPLY = "missing_reply";
    /** args 里的续办句（点安装 → InstallWait 登记，装好后用它接着办）。 */
    public static final String ARG_RESUME = "resume_query";

    static final Pattern OPEN = Pattern.compile(
            "^(?:请|麻烦你?|帮我|给我)?(?:帮我)?(?:打开|启动|开一下|开开|打开一下|启动一下)(?:一下)?(.{1,12}?)(?:app|应用|软件)?(?:吧|呗|啊)?$");
    static final Pattern SEARCH = Pattern.compile(
            "^(?:请|麻烦你?|帮我|给我)?(?:帮我)?(?:在|用|去|到)(.{1,10}?)(?:上面|里面|上|里)?(?:搜一下|搜索一下|搜搜|搜索|搜|找一下|找找|找|查一下|查查|查)(?:一下)?(.{1,30})$");

    /** 已装判断（Android 侧：RoleHooks.snapshot 包名 + IntentExecutor 标签解析）。 */
    public interface Installed {
        boolean pkgInstalled(String pkg);
        boolean labelResolves(String appName);
    }

    /** 解析结果：app 名、包名、要搜的词（打开类为 null）。 */
    public static final class Match {
        public final String app, pkg, term;
        Match(String app, String pkg, String term) { this.app = app; this.pkg = pkg; this.term = term; }
    }

    static String norm(String q) {
        if (q == null) return "";
        return q.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[\\s，,。.！!？?~～…「」\"'“”]", "")
                .replaceAll("^(?:嗨|嘿|你好)+", "");
    }

    /** 只做句式 + KnownApps 识别（不看是否已装）。 */
    public static Match parse(String query) {
        String q = norm(query);
        if (q.length() < 3 || q.length() > 40) return null;
        Matcher m = SEARCH.matcher(q);
        if (m.matches()) {
            String app = m.group(1);
            String pkg = KnownApps.pkgOf(app);
            String term = m.group(2).replaceAll("^(?:一下|下)", "").replaceAll("(?:吧|呗|啊)$", "");
            if (pkg != null && term.length() > 0) return new Match(app, pkg, term);
        }
        m = OPEN.matcher(q);
        if (m.matches()) {
            String app = m.group(1);
            String pkg = KnownApps.pkgOf(app);
            if (pkg != null) return new Match(app, pkg, null);
        }
        return null;
    }

    /** 大白话回执。 */
    public static String reply(Match m) {
        if (m.term != null) {
            return "手机上还没装" + m.app + "，点下面的安装卡去商店装，装好我接着帮你在" + m.app + "搜「" + m.term + "」。";
        }
        return "手机上还没装" + m.app + "，点下面的安装卡去商店装，装好我接着帮你办。";
    }

    /** 认得且没装 → app.launch 调用；否则 null（照旧进 AgentLoop / L0）。 */
    public static Invocation plan(String query, Installed inst) {
        Match m = parse(query);
        if (m == null || inst == null) return null;
        try {
            if (inst.pkgInstalled(m.pkg) || inst.labelResolves(m.app)) return null;
        } catch (Throwable t) {
            return null;
        }
        try {
            JSONObject a = new JSONObject();
            a.put("app", m.app);
            a.put("pkg", m.pkg);
            a.put(ARG_REPLY, reply(m));
            a.put(ARG_RESUME, query.trim());
            return new Invocation("app.launch", a, 1.0, Invocation.RouteSource.L0_RULE, query);
        } catch (Exception e) {
            return null;
        }
    }
}
