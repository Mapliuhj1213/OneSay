package com.onesay.assistant.v2.router;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fix-1118（OneSay 真机 c27 / c28）：收工答复不能说没做成的事。
 * <ul>
 *   <li>「在设置里帮我点开「关于手机」」：只调了 route.open 打开设置页，屏幕上一步没点，模型却答「已打开关于手机」。
 *       用户点名要<b>点</b>某个东西，而本任务没有一步屏幕操作成功执行过 → 把答复换成如实说明；</li>
 *   <li>识屏失败（vision.* 没成功、也没有别的读屏成功）而答复没提 → 在开头说明识屏没成功，下面的内容不是看屏幕得出的。</li>
 * </ul>
 * 纯函数，JVM 可测；只收紧答复，不改执行。
 */
public final class UiClaimCheck {
    private UiClaimCheck() {}

    /** 一步的最小视图（AgentLoop.Step 的 tool / ok / gated）。 */
    public interface StepView {
        String tool();

        boolean ok();

        boolean gated();
    }

    static final Pattern CLICK_GOAL = Pattern.compile("(?:点开|点击|点一下|点进去?|点进|戳开|按一下|按下)\\s*[「『“\"]?([^」』”\"，。,.!！?？]{1,16}?)[」』”\"]?(?:按钮|选项|那一项|这一项|那个|这个)?(?:[，。,.!！?？]|$)");
    private static final Pattern DONE_WORDS = Pattern.compile("已(?:经)?(?:为你|帮你|替你)?(?:点开|点击|打开|进入|切到|跳到)|(?:点开|打开|进入)了|成功|搞定|好了");
    private static final Pattern FAIL_WORDS = Pattern.compile("没能|没有成功|未能|失败|做不到|没法|无法|不能|读不到|看不到|没看到|没找到|还没");

    /** 屏幕上真动手的工具（点 / 输 / 滑 / 多步代操作）。 */
    static boolean isUiActionTool(String t) {
        if (t == null) return false;
        String s = t.replace("__", ".");
        return s.startsWith("phone.operate") || s.startsWith("ui.tap") || s.startsWith("ui.click") || s.startsWith("ui.type")
                || s.startsWith("ui.swipe") || s.startsWith("ui.scroll") || s.startsWith("mika.") || s.startsWith("gui.tap")
                || s.startsWith("gui.click") || s.startsWith("gui.act") || s.startsWith("a11y.click") || s.startsWith("screen.tap");
    }

    static boolean isScreenReadTool(String t) {
        if (t == null) return false;
        String s = t.replace("__", ".");
        return s.startsWith("vision.") || s.startsWith("ui.screenshot") || s.startsWith("screen.read") || s.startsWith("ui.dump")
                || s.startsWith("a11y.read") || s.startsWith("gui.read");
    }

    /** 用户要点的目标；不是「点开 X」类句子 = null。 */
    public static String clickTarget(String query) {
        if (query == null) return null;
        Matcher m = CLICK_GOAL.matcher(query.trim());
        if (!m.find()) return null;
        String x = m.group(1).trim();
        return x.length() == 0 ? null : x;
    }

    /**
     * @return 需要改写时的新答复；不需要 = 原答复。
     */
    public static <S extends StepView> String verify(String query, String answer, List<S> steps) {
        if (answer == null) return null;
        String fin = answer;
        boolean uiOk = false, readOk = false, readFailed = false;
        if (steps != null) {
            for (S s : steps) {
                if (s == null) continue;
                if (isUiActionTool(s.tool()) && s.ok() && !s.gated()) uiOk = true;
                if (isScreenReadTool(s.tool())) {
                    if (s.ok() && !s.gated()) readOk = true;
                    else readFailed = true;
                }
            }
        }
        String target = clickTarget(query);
        if (target != null && !uiOk && DONE_WORDS.matcher(fin).find() && !FAIL_WORDS.matcher(fin).find()) {
            return "我还没在屏幕上点开「" + target + "」：这次只做到了打开对应的页面，没有一步真正点到它，也没核实它已经打开。"
                    + "你可以在当前页面直接点「" + target + "」，或者再说一次让我按屏幕操作。";
        }
        if (readFailed && !readOk && !FAIL_WORDS.matcher(fin).find()) {
            return "识屏没成功（这次读不到屏幕画面），下面不是看着屏幕说的：\n" + fin;
        }
        return fin;
    }
}
