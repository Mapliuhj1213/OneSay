package com.onesay.assistant.v2.router;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ★ 11-04 A（用户：「订酒店」某一步一直死循环）：AgentLoop 原地打转检测（纯函数，按已做的步骤整体重算，JVM 可测）。
 *
 * <p>机上日志（logcat-hotel-20261104-0216）：问过一次时间预算后，probe.apps ×4（携程 / 美团 / 飞猪 / 同程都没装）→ 探针预算拒绝 →
 * web.search / web.read 在 trip.com（404）、携程（要登录）、klook（反爬页）之间来回 9 轮，72 s 没拿到一条房价，用户手动停。
 * 原有 TaskBudget 只认「同一调用 + 观测不变」，换个站点 / 换个搜索词就不算重复，所以一直转。
 *
 * <p>停的条件（任一）：
 * <ol>
 *   <li>同一动作 + 同一参数连续 ≥ {@link #SAME_LIMIT} 次；</li>
 *   <li>连续 ≥ {@link #NOPROG_LIMIT} 步没有进展（搜索、失败、碰墙都算没进展；读到 ≥300 字的正文 / 设备动作成功 / 用户回答才算进展；探针中性）；</li>
 *   <li>碰墙（要登录 / 反爬校验 / 验证码 / 404 / 403）累计 ≥ {@link #WALL_LIMIT}（预订 / 下单类 {@link #WALL_LIMIT_BOOKING}）。</li>
 * </ol>
 * 停下后由 {@link #message} 如实说卡在哪、给可选下一步；绝不下单 / 付款。
 */
public final class LoopGuard {

    public static final int SAME_LIMIT = 3;
    public static final int NOPROG_LIMIT = 6;
    public static final int WALL_LIMIT = 3;
    public static final int WALL_LIMIT_BOOKING = 2;

    private LoopGuard() {}

    /** 停下的原因。 */
    public static final class Stop {
        public final String kind;      // same / no_progress / walls
        public final String where;     // 卡在哪（人话）
        public final int count;

        Stop(String kind, String where, int count) {
            this.kind = kind;
            this.where = where;
            this.count = count;
        }

        @Override public String toString() {
            return kind + "×" + count + "@" + where;
        }
    }

    /** 一步的最小视图（AgentLoop.Step 与测试共用）。 */
    public static final class S {
        final String tool;
        final String args;
        final String obs;
        final boolean ok;

        public S(String tool, String args, String obs, boolean ok) {
            this.tool = tool == null ? "" : tool;
            this.args = args == null ? "" : args;
            this.obs = obs == null ? "" : obs;
            this.ok = ok;
        }
    }

    static boolean booking(String query) {
        return query != null && query.matches("(?s).*(订|预订|预定|预约|下单|买票|抢票|开房|入住).*");
    }

    /** 碰墙：要登录 / 反爬校验 / 验证码 / 404 / 403。 */
    public static boolean wall(String obs) {
        if (obs == null) return false;
        String o = obs;
        return o.contains("要登录") || o.contains("登录才") || o.contains("需要登录") || o.contains("反爬") || o.contains("校验页")
                || o.contains("验证码") || o.contains("滑块") || o.contains("HTTP 404") || o.contains("HTTP 403") || o.contains("http 404")
                || o.contains("http 403") || o.contains("没渲染出内容") || o.contains("\"blocked\":true");
    }

    static boolean neutral(String tool) {
        return tool.startsWith("probe.") || tool.equals("task.todo") || tool.equals("gui.unfold");
    }

    static boolean progress(S s) {
        if (!s.ok || wall(s.obs)) return false;
        if (s.tool.equals("ask_user")) return true;
        if (s.tool.equals("web.search")) return false;   // 搜索本身不算进展：只有读到东西才算
        if (s.tool.equals("web.read") || s.tool.equals("research.spawn")) return s.obs.length() >= 300;
        return true;   // 设备动作 / 其他工具成功
    }

    public static Stop check(List<S> steps, String query) {
        if (steps == null || steps.isEmpty()) return null;
        // ① 同一动作 + 同一参数连续
        int run = 1;
        for (int i = steps.size() - 1; i > 0; i--) {
            S a = steps.get(i), b = steps.get(i - 1);
            if (a.tool.equals(b.tool) && a.args.equals(b.args)) run++;
            else break;
        }
        S last = steps.get(steps.size() - 1);
        if (run >= SAME_LIMIT && !neutral(last.tool)) return new Stop("same", describe(last), run);
        // ③ 碰墙累计
        int walls = 0;
        Set<String> wallWhere = new LinkedHashSet<String>();
        for (S s : steps) {
            if (s.tool.startsWith("web.") && wall(s.obs)) {
                walls++;
                wallWhere.add(host(s.args));
            }
        }
        int wl = booking(query) ? WALL_LIMIT_BOOKING : WALL_LIMIT;
        if (walls >= wl) return new Stop("walls", join(wallWhere), walls);
        // ② 连续没进展
        int np = 0;
        List<String> where = new ArrayList<String>();
        for (int i = steps.size() - 1; i >= 0; i--) {
            S s = steps.get(i);
            if (neutral(s.tool)) continue;
            if (progress(s)) break;
            np++;
            if (where.size() < 3) where.add(0, describe(s));
        }
        if (np >= NOPROG_LIMIT) return new Stop("no_progress", join(where), np);
        return null;
    }

    static String describe(S s) {
        if (s.tool.equals("web.read")) return "读取 " + host(s.args);
        if (s.tool.equals("web.search")) return "搜索";
        if (s.tool.equals("phone.operate")) return "在手机上操作";
        return s.tool;
    }

    static String host(String args) {
        if (args == null) return "网页";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://(?:www\\.|m\\.)?([^/\"\\\\]+)").matcher(args.replace("\\/", "/"));
        if (m.find()) return com.onesay.assistant.v2.island.IslandStepText.siteName("https://" + m.group(1));
        return "网页";
    }

    static String join(Iterable<String> it) {
        StringBuilder sb = new StringBuilder();
        for (String s : it) {
            if (s == null || s.length() == 0) continue;
            if (sb.length() > 0) sb.append("、");
            sb.append(s);
        }
        return sb.length() == 0 ? "同一步" : sb.toString();
    }

    /** 停下后给用户的话：卡在哪 + 可选下一步；绝不说已下单 / 已付款。 */
    public static String message(Stop st, String query) {
        StringBuilder sb = new StringBuilder();
        if ("walls".equals(st.kind)) {
            sb.append("我先停下了：").append(st.where).append(" 的网页要登录或被拦（碰到 ").append(st.count).append(" 次），在网页上拿不到结果。");
        } else if ("same".equals(st.kind)) {
            sb.append("我先停下了：同一步（").append(st.where).append("）连续做了 ").append(st.count).append(" 次都没变化。");
        } else {
            sb.append("我先停下了：连续 ").append(st.count).append(" 步没有新进展（卡在 ").append(st.where).append("）。");
        }
        sb.append("没有下单，也没有付款。\n你可以：\n");
        if (booking(query)) {
            sb.append("1. 打开订房 / 购物 App，你登录后我接着帮你筛；\n");
            sb.append("2. 告诉我预算和位置，我先按公开信息列几家候选；\n");
        } else {
            sb.append("1. 换个说法或缩小范围，我重新查；\n");
            sb.append("2. 告诉我想看的网站，你登录后我接着读；\n");
        }
        sb.append("3. 说「算了」就不继续了。");
        return sb.toString();
    }

    /** AgentLoop.Step → S。 */
    public static List<S> view(List<AgentLoop.Step> steps) {
        List<S> l = new ArrayList<S>();
        if (steps == null) return l;
        for (AgentLoop.Step st : steps) {
            if (st == null) continue;
            l.add(new S(st.tool, st.args == null ? "" : st.args.toString().toLowerCase(Locale.ROOT), st.observation, st.ok));
        }
        return l;
    }
}
