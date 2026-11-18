package com.onesay.assistant.v2.router;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ plan-1118（真机 Meizu 21 Pro g01）：「打开设置，往下滑到显示与亮度」AgentLoop 第 1 轮 route.open 打开设置，
 * 第 2 轮就收工（结局 done），phone.operate 一次没调 —— 用户看到的是「打开了 App 但什么都没做」。
 * <p>
 * 确定性判定（不靠提示词）：用户原话里除了「打开 X」还有 App 内的动作（滑 / 点 / 输入 / 搜索 / 找 / 算…），
 * 而本任务只有「打开」类步骤成功、屏幕操作一步都没试过 → 不许收工：
 * <ul>
 *   <li>{@link #withRemaining}：打开类工具的观测值里写明剩下的子目标与下一步（phone.operate）；</li>
 *   <li>{@link #finishBlock}：模型想收工时打回一次，内部提示接着调 phone.operate；</li>
 *   <li>{@link #honestTail}：打回后仍不做 → 答复补一句如实说明，结局记 not_done（partial）。</li>
 * </ul>
 * 纯函数，JVM 可测。
 */
public final class OpenThenOperate {
    private OpenThenOperate() {}

    /** 开头的「打开 X」。 */
    private static final Pattern OPEN_HEAD = Pattern.compile("^(?:请|麻烦)?(?:你)?(?:帮我|替我|给我)?(?:先)?(?:打开|启动|开一下|打开一下|进入|进到|去)\\s*");

    /** App 内动作（成词的说法；避开「几点」「一点」这类含「点」「算」的普通词）。 */
    static final Pattern IN_APP_ACT = Pattern.compile(
            "往下滑|往上滑|向下滑|向上滑|往下翻|往上翻|下滑|上滑|滑到|滑动|划到|滚到|滚动|翻到|翻页|拉到底|拉到|"
                    + "一个个点|一个一个点|逐个点|依次点|挨个点|点一下|点击|点开|点进|点按|按一下|长按|"
                    + "输入|打字|填写|填入|搜索|搜一下|查找|找到|找一下|"
                    + "计算|算一下|算出|算(?=\\s*[0-9零一二两三四五六七八九十（(])|"
                    + "选择|选中|勾选|切换到|切到|操作界面|替我操作|帮我操作|代我操作|模拟点击");

    /** 子目标前的连接词。 */
    private static final Pattern LEAD = Pattern.compile("^[，,、；;。\\s]*(?:然后|接着|之后|再|并且|并|后|帮我|替我|给我|在里面|在里头|里面|里)*[，,、\\s]*");

    private static final Pattern SEP = Pattern.compile("[，,、；;。]|然后|接着|之后|并且");

    /**
     * 仅打开页面 / App 的一步。不含具体设置页深链 settings.page.*、route.open 带 page 的设置页（那本身就到了目标页），
     * 也不含 route.open 的搜索 / 导航 / 下单等意图（接口已经替用户办了）。
     */
    public static boolean isOpenOnlyStep(String sign, org.json.JSONObject args) {
        if (sign == null) return false;
        String s = sign.replace("__", ".");
        if (s.equals("app.launch") || s.equals("app.open") || s.equals("settings.open")) return true;
        if (!s.equals("route.open")) return false;
        String intent = args == null ? "" : args.optString("intent", "").trim();
        String page = args == null ? "" : args.optString("page", "").trim();
        return (intent.isEmpty() || intent.equals("settings.open")) && page.isEmpty();
    }

    /**
     * 用户原话里「打开」之后还要在 App 里做的事；没有 = null。
     * 「打开设置，往下滑到显示与亮度」→「往下滑到显示与亮度」；「打开计算器算 12×34」→「算 12×34」；
     * 「打开蓝牙」「打开设置」→ null。不以「打开」开头但点名界面动作（「在设置里往下滑」）→ 整句。
     */
    public static String remainingGoal(String query) {
        if (query == null) return null;
        String q = query.trim();
        if (q.isEmpty()) return null;
        Matcher h = OPEN_HEAD.matcher(q);
        if (!h.find() || h.end() == 0 || !q.substring(0, h.end()).matches(".*(打开|启动|开一下|进入|进到|去).*")) {
            // 不以「打开」开头：只认明确的界面动作说法（「在设置里往下滑到…」），免得把「搜一下周杰伦」这类接口能办完的算进来
            return com.onesay.assistant.v2.agenttools.GuiFold.explicitUi(q) ? q : null;
        }
        String body = q.substring(h.end());
        String rest = null;
        Matcher sep = SEP.matcher(body);
        int sepAt = sep.find() && sep.start() >= 1 && sep.start() <= 16 ? sep.start() : -1;
        // 从第 2 个字起找第一个动作词（「计算器算 12×34」的「计算」在 App 名里，跳过）；比分隔符早就从动作词切
        int lim = Math.min(body.length(), sepAt >= 0 ? sepAt : 16);
        for (int i = 2; i < lim; i++) {
            Matcher m = IN_APP_ACT.matcher(body);
            m.region(i, body.length());
            if (m.lookingAt()) {
                rest = body.substring(i);
                break;
            }
        }
        if (rest == null && sepAt >= 0) rest = body.substring(sep.end());
        if (rest == null) return null;
        rest = LEAD.matcher(rest).replaceFirst("").trim();
        if (rest.isEmpty() || !IN_APP_ACT.matcher(rest).find()) return null;
        return rest.length() > 60 ? rest.substring(0, 60) : rest;
    }

    /** 一步的最小视图。 */
    public interface StepView {
        String tool();

        org.json.JSONObject args();

        boolean ok();
    }

    /**
     * 只「打开」成功、屏幕操作一步没试：返回剩下的子目标；否则 null。
     * phone.operate 试过（不论成败）、或任何界面动作工具试过 → null（那是别的问题，不在这里拦）。
     */
    public static <S extends StepView> String pendingGoal(String query, List<S> steps) {
        String goal = remainingGoal(query);
        if (goal == null || steps == null) return null;
        boolean opened = false;
        for (S s : steps) {
            if (s == null) continue;
            if (UiClaimCheck.isUiActionTool(s.tool())) return null;
            if (isOpenOnlyStep(s.tool(), s.args()) && s.ok()) opened = true;
        }
        return opened ? goal : null;
    }

    /** 打开类工具成功后的观测值：写明剩下的子目标与下一步。不是打开类 / 没有剩余 = 原样。 */
    public static String withRemaining(String observation, String sign, org.json.JSONObject args, boolean ok, String query) {
        if (!ok || !isOpenOnlyStep(sign, args) || observation == null) return observation;
        String goal = remainingGoal(query);
        if (goal == null) return observation;
        try {
            org.json.JSONObject o = new org.json.JSONObject(observation);
            o.put("remaining_goal", goal);
            o.put("next", "页面只是打开了，用户还要「" + goal + "」没做。下一步调用 phone.operate（goal 写「" + goal + "」）在已打开的页面上完成，做完再收工。");
            return o.toString();
        } catch (Throwable t) {
            return observation;
        }
    }

    /** 收工打回时的内部提示。 */
    public static String finishNudge(String goal, String query) {
        return "（系统内部提示，不要向用户复述）任务还没做完：目前只打开了页面，用户原话里的「" + goal + "」一步都没在屏幕上做。"
                + "现在调用 phone.operate（工具表里只有 gui.unfold 就先调它展开），goal 写「" + goal + "」，在已打开的页面上完成；做不到再如实说明卡在哪。\n用户原话：" + query;
    }

    /** 打回后仍没做：答复补一句如实说明（答复已说没做成就不补）。 */
    public static String honestTail(String answer, String goal) {
        String a = answer == null ? "" : answer.trim();
        if (a.contains(goal) && a.matches("(?s).*(没能|没有|未能|还没|没做|失败|做不到|无法).*")) return a;
        String tail = "（只打开了页面，「" + goal + "」还没在屏幕上完成。）";
        return a.isEmpty() ? tail : a + "\n" + tail;
    }
}
