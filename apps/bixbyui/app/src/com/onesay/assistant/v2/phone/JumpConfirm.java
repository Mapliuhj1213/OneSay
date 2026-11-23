package com.onesay.assistant.v2.phone;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [fix-1123-dexec B39] 打开 App 时系统 / ROM 弹的「是否打开 XX」跳转确认框（纯逻辑，JVM 可测）。
 *
 * <p>真机：有些 App 拉起别的 App 时弹框问「是否允许打开」，代操作卡在这儿。规则（用户定）：
 * 先读屏认出这个框；要打开的就是用户点名要开的 App → 点「打开 / 允许」；别的一律停下问用户；读不到（安全窗口）再看图；
 * 看图也用不了就请用户手动点一下。</p>
 */
public final class JumpConfirm {

    private JumpConfirm() {}

    /** 认出的跳转确认框。 */
    public static final class Hit {
        /** 框里说要打开的 App 名（认不出 = ""）。 */
        public String target = "";
        /** 「打开 / 允许」按钮在 els 里的下标。 */
        public int allowIdx = -1;
        /** 「取消 / 拒绝」按钮在 els 里的下标（没有 = -1）。 */
        public int cancelIdx = -1;
        public String prompt = "";
    }

    static final Pattern ASK = Pattern.compile("是否(?:允许)?.{0,12}打开|想要?打开|要打开.{0,16}吗|允许.{0,16}打开|即将(?:离开.{0,16})?打开|将要打开|跳转到?.{0,16}(?:应用|App|APP)|打开.{0,12}(?:应用|App|APP)[吗？?]");
    static final Pattern ALLOW = Pattern.compile("^(?:打开|允许|允许打开|立即打开|前往|继续|确定|好的|去打开|同意)$");
    static final Pattern CANCEL = Pattern.compile("^(?:取消|拒绝|不允许|禁止|以后再说|暂不|不打开|关闭)$");
    static final Pattern QUOTED = Pattern.compile("[“\"「『《]([^”\"」』》]{1,20})[”\"」』》]");
    static final Pattern AFTER_OPEN = Pattern.compile("打开\\s*([\\u4e00-\\u9fa5A-Za-z0-9]{1,12}?)(?:吗|应用|App|APP|？|\\?|$)");

    /** 在一屏的元素文字里认跳转确认框；不是 = null。labels 与元素一一对应。 */
    public static Hit detect(List<String> labels) {
        if (labels == null || labels.isEmpty() || labels.size() > 40) return null;   // 跳转框是个小框，元素多的整页不是
        String prompt = null;
        int allow = -1, cancel = -1;
        for (int i = 0; i < labels.size(); i++) {
            String t = labels.get(i) == null ? "" : labels.get(i).trim();
            if (t.length() == 0) continue;
            if (allow < 0 && ALLOW.matcher(t).matches()) allow = i;
            else if (cancel < 0 && CANCEL.matcher(t).matches()) cancel = i;
            else if (prompt == null && t.length() <= 60 && ASK.matcher(t).find()) prompt = t;
        }
        if (prompt == null || allow < 0) return null;
        Hit h = new Hit();
        h.prompt = prompt;
        h.allowIdx = allow;
        h.cancelIdx = cancel;
        h.target = targetName(prompt);
        return h;
    }

    /** 框里要打开的 App 名：有引号取最后一个引号里的（「X」想打开「Y」→ Y）；没有就取「打开」后面那个词。 */
    public static String targetName(String prompt) {
        if (prompt == null) return "";
        Matcher m = QUOTED.matcher(prompt);
        String last = null;
        while (m.find()) last = m.group(1);
        if (last != null) return last.trim();
        Matcher a = AFTER_OPEN.matcher(prompt);
        String got = null;
        while (a.find()) got = a.group(1);
        return got == null ? "" : got.trim();
    }

    /** 要打开的是不是用户点名要开的：框里的名字出现在用户的目标里（或目标 App 名里）。认不出名字 = 不算。 */
    public static boolean isUserTarget(String target, String goal, String targetLabel) {
        if (target == null || target.length() == 0) return false;
        if (goal != null && goal.contains(target)) return true;
        return targetLabel != null && targetLabel.length() > 0 && (targetLabel.contains(target) || target.contains(targetLabel));
    }

    /** 问用户的确认卡正文。 */
    public static String askDetail(String target) {
        return "屏幕上弹出来问要不要打开" + (target == null || target.length() == 0 ? "另一个应用" : "「" + target + "」") + "，这不是你让我开的。要我点「打开」吗？";
    }

    /** 读不到框（看图也用不了）时请用户手动点。 */
    public static final String MANUAL = "屏幕上弹了个「是否打开」的框，我读不到它，麻烦你手动点一下，点完再点灵动岛上的「继续」";
}
