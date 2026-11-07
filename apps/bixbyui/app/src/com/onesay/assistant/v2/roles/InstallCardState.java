package com.onesay.assistant.v2.roles;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 安装卡（「未安装 X，点此安装」提示条 / 缺应用的 2×2 应用网格）在屏状态 + 「不用了」口令判定。纯 Java，JVM 可测。
 *
 * <p>卡在屏时用户打字 / 说「不用了」「算了」「不装了」= 关卡并取消这张卡对应的 InstallWait 等装登记（RolesPlugin 最先接管，回一句「好，不装了。」），
 * 不进分诊 / AgentLoop。卡不在屏时这几个词照旧走原流程。InstallPromptView / ChoiceGridView 显示、收起时更新这里（主线程写，路由线程读）。
 */
public final class InstallCardState {

    private InstallCardState() {}

    public static final String KIND_PROMPT = "prompt";
    public static final String KIND_GRID = "grid";

    /** 整句就是拒绝安装。「不用了谢谢」「算了吧」「先不装」也算；带别的事（「算了，打开微信」）不算。 */
    static final Pattern DISMISS = Pattern.compile(
            "^(?:那|那就|嗯|哦)?(?:先)?(?:不用了|不用|不要了|不要|算了|不装了|不装|不安装了|不安装|不下了|不下载了|不下载|不需要了|不需要|别装了|别装|不用装了|取消|取消吧|关掉|关了吧|没事了)(?:吧|啦|了|呀|啊)?(?:谢谢你?|多谢)?$");

    private static volatile List<String> sPromptPkgs;
    private static volatile List<String> sGridPkgs;

    public static boolean isDismissWord(String utterance) {
        if (utterance == null) return false;
        String q = utterance.trim().replaceAll("[\\s，,。.！!？?~～…]", "").replaceAll("^(?:嗨|嘿)+", "");
        return q.length() > 0 && q.length() <= 12 && DISMISS.matcher(q).matches();
    }

    public static void shown(String kind, List<String> pkgs) {
        List<String> c = pkgs == null ? Collections.<String>emptyList() : Collections.unmodifiableList(new ArrayList<String>(pkgs));
        if (KIND_GRID.equals(kind)) sGridPkgs = c;
        else sPromptPkgs = c;
    }

    public static void gone(String kind) {
        if (KIND_GRID.equals(kind)) sGridPkgs = null;
        else sPromptPkgs = null;
    }

    public static boolean showing() {
        return sPromptPkgs != null || sGridPkgs != null;
    }

    /** 在屏卡片涉及的全部包名（取消等装登记用）。 */
    public static List<String> pkgs() {
        List<String> out = new ArrayList<String>();
        List<String> a = sPromptPkgs, b = sGridPkgs;
        if (a != null) out.addAll(a);
        if (b != null) for (String p : b) if (!out.contains(p)) out.add(p);
        return out;
    }

    /** 卡在屏 + 整句是「不用了 / 算了 / 不装了」→ true（宿主关卡、取消等装、本地回一句）。 */
    public static boolean wantsDismiss(String utterance) {
        return showing() && isDismissWord(utterance);
    }

    /** 本地回执。 */
    public static final String REPLY = "好，不装了。";
}
