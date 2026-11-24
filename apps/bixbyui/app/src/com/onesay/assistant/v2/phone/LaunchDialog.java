package com.onesay.assistant.v2.phone;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * [exec-ground-1124] 打开 App 时冒出来的系统弹窗（纯 Java，JVM 可测）。
 *
 * <p>真机（魅族 21 Pro，2026-11-24 11:57:07）：打开微信后前台是 {@code android}、读到 3 个元素，记了「没打开」以后模型直接点了 [3]，
 * 点的是什么谁也不知道。思路参考 Mika AndroidScreen 打开应用后的核对（launched / chooserOf / picker：打开后看前台，
 * 是选择器就选目标、是别的系统框就交给人），这里是自己写的：
 * <ul>
 *   <li>「是否打开 XX」一类的跳转框：已有 {@link JumpConfirm} 处理（要开的就是用户点名的 App 才点「打开」）；</li>
 *   <li>「打开方式」选择器：列表里有目标 App 的名字 → 点它，再点「仅此一次」（没有「仅此一次」就不点「始终」）；</li>
 *   <li>别的系统框（权限、安全提醒、读不到内容的框）：不猜，请用户看一眼手机、处理好以后点继续。</li>
 * </ul>
 */
public final class LaunchDialog {

    private LaunchDialog() {}

    public enum Kind { NONE, CHOOSER, UNKNOWN }

    public static final class Verdict {
        public Kind kind = Kind.NONE;
        /** CHOOSER：目标 App 那一行、「仅此一次」按钮在 els 里的编号（没有 = -1）。 */
        public int targetId = -1, onceId = -1;
        /** 框里的文字（给用户看，最多几段）。 */
        public String summary = "";
    }

    /** 系统界面的包（弹窗、选择器、权限框、安全中心）。 */
    static final Pattern SYSTEM_PKG = Pattern.compile("^(android|com\\.android\\.(systemui|permissioncontroller|packageinstaller|settings\\.intelligence|intentresolver)"
            + "|com\\.google\\.android\\.(permissioncontroller|packageinstaller|intentresolver)|com\\.meizu\\.(safe|security|flyme\\..*)|com\\.flyme\\..*"
            + "|com\\.miui\\.securitycenter|com\\.lbe\\.security\\.miui|com\\.coloros\\.(safecenter|securitypermission)|com\\.oplus\\.(safecenter|securitypermission)"
            + "|com\\.vivo\\.permissionmanager|com\\.iqoo\\.secure|com\\.huawei\\.systemmanager|com\\.hihonor\\.systemmanager|com\\.samsung\\.android\\.(permissioncontroller|intentresolver))$");

    static final Pattern ONCE = Pattern.compile("^(仅此一次|只此一次|仅一次|本次|just once|once)$", Pattern.CASE_INSENSITIVE);
    static final Pattern CHOOSER_TITLE = Pattern.compile("打开方式|选择.{0,6}(应用|方式)|使用以下|用.{0,6}打开|open with|complete action", Pattern.CASE_INSENSITIVE);

    public static boolean systemPkg(String pkg) {
        return pkg != null && pkg.length() > 0 && SYSTEM_PKG.matcher(pkg).matches();
    }

    /**
     * 打开 targetPkg（用户叫它 targetLabel）以后读到这一屏：不是系统弹窗 = NONE。
     * 跳转确认框（{@link JumpConfirm}）不在这里判，调用方先交给它。
     */
    public static Verdict classify(UiTree u, String targetPkg, String targetLabel) {
        Verdict v = new Verdict();
        if (u == null || u.pkg == null || u.pkg.equals(targetPkg) || !systemPkg(u.pkg)) return v;
        List<String> texts = new ArrayList<String>();
        boolean chooser = false;
        for (UiTree.El e : u.els) {
            String l = e.label == null ? "" : e.label.trim();
            if (l.length() == 0) continue;
            if (texts.size() < 4) texts.add(l.length() > 24 ? l.substring(0, 24) + "…" : l);
            if (CHOOSER_TITLE.matcher(l).find()) chooser = true;
            if (v.onceId < 0 && ONCE.matcher(l).matches()) v.onceId = e.id;
            if (v.targetId < 0 && targetLabel != null && targetLabel.length() > 0 && (e.click || e.focusRow) && l.contains(targetLabel)) v.targetId = e.id;
        }
        v.summary = join(texts);
        v.kind = chooser && v.targetId >= 0 ? Kind.CHOOSER : Kind.UNKNOWN;
        return v;
    }

    /** 请用户处理时的说法（大白话，不带包名）。 */
    public static String askText(String targetLabel, String summary) {
        String who = targetLabel == null || targetLabel.length() == 0 ? "这个应用" : "「" + targetLabel + "」";
        return "打开" + who + "时，系统弹出了一个提示" + (summary != null && summary.length() > 0 ? "（" + summary + "）" : "（我读不到上面的字）")
                + "。我拿不准该点哪个，没有替你点。你看一下手机，处理好以后点继续，我接着做";
    }

    static String join(List<String> l) {
        StringBuilder b = new StringBuilder();
        for (String s : l) {
            if (b.length() > 0) b.append(" / ");
            b.append(s);
        }
        return b.toString();
    }
}
