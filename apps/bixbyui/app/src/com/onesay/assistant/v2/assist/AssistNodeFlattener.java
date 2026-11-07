package com.onesay.assistant.v2.assist;

import com.onesay.assistant.v2.vision.screenref.UiDumpParser;
import com.onesay.assistant.v2.vision.screenref.UiNode;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ r2/sysux P2：AssistStructure → 识屏管线的 {@link UiNode} 列表（纯 Java，JVM 可测）。
 *
 * <p>Android 壳（{@link OneSaySession}）遍历 WindowNode / ViewNode，把每个节点抄成 {@link Raw}（绝对坐标已算好）交给这里；
 * 这里套用和 uiautomator dump 完全相同的隐私规则（{@link UiDumpParser}）：密码框 / 像支付码的 id 整个丢弃，银行卡号 / 身份证号打码，
 * 自家包的节点丢弃（面板本来就不在 AssistStructure 里，防御）。不可见、零面积的节点丢弃。</p>
 */
public final class AssistNodeFlattener {

    private AssistNodeFlattener() {}

    /** 一个 ViewNode 的必要属性（壳层填）。 */
    public static final class Raw {
        public final String text, desc, idEntry, className, pkg;
        public final boolean password, clickable, visible;
        public final int left, top, right, bottom;

        public Raw(String text, String desc, String idEntry, String className, String pkg,
                   boolean password, boolean clickable, boolean visible, int left, int top, int right, int bottom) {
            this.text = text;
            this.desc = desc;
            this.idEntry = idEntry;
            this.className = className;
            this.pkg = pkg;
            this.password = password;
            this.clickable = clickable;
            this.visible = visible;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }
    }

    public static final class Result {
        public final List<UiNode> nodes;
        public final int redacted;
        public final int dropped;

        Result(List<UiNode> nodes, int redacted, int dropped) {
            this.nodes = nodes;
            this.redacted = redacted;
            this.dropped = dropped;
        }
    }

    /** InputType 里的密码变体（TYPE_TEXT_VARIATION_PASSWORD / WEB_PASSWORD / VISIBLE_PASSWORD / NUMBER_PASSWORD）。 */
    public static boolean isPasswordInputType(int inputType) {
        int cls = inputType & 0x0000000f;          // TYPE_MASK_CLASS
        int var = inputType & 0x00000ff0;          // TYPE_MASK_VARIATION
        if (cls == 0x00000001) {                   // TYPE_CLASS_TEXT
            return var == 0x00000080 || var == 0x000000e0 || var == 0x00000090;
        }
        if (cls == 0x00000002) return var == 0x00000010;   // TYPE_CLASS_NUMBER + NUMBER_PASSWORD
        return false;
    }

    public static Result flatten(List<Raw> raws, String ownPackage) {
        List<UiNode> out = new ArrayList<UiNode>();
        int redacted = 0, dropped = 0;
        if (raws == null) return new Result(out, 0, 0);
        for (Raw r : raws) {
            if (r == null) continue;
            if (!r.visible || r.right <= r.left || r.bottom <= r.top) { dropped++; continue; }
            if (ownPackage != null && ownPackage.equals(r.pkg)) { dropped++; continue; }
            if (r.password || UiDumpParser.isSecretId(r.idEntry)) { redacted++; continue; }
            String t = r.text == null ? "" : r.text;
            String d = r.desc == null ? "" : r.desc;
            if (t.length() == 0 && d.length() == 0 && !r.clickable) { dropped++; continue; }   // 纯布局容器
            String t2 = UiDumpParser.maskSecrets(t);
            String d2 = UiDumpParser.maskSecrets(d);
            if (!t2.equals(t) || !d2.equals(d)) redacted++;
            out.add(new UiNode(t2, d2, r.idEntry, r.className, r.pkg, r.clickable, false, r.left, r.top, r.right, r.bottom));
        }
        return new Result(out, redacted, dropped);
    }
}
