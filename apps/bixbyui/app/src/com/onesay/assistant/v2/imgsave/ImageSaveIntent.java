package com.onesay.assistant.v2.imgsave;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * [imgsave] 「保存图片」意图（L0 规则 / 分诊 / 面板挂钩共用这一个判定）+ 多图时的选择口令解析。纯 Java，JVM 可测。
 *
 * <h3>触发（保守：只认「存/保存/下载」+ 明确的图片宾语）</h3>
 * 保存图片 / 存这张图 / 把图存下来 / 保存这张图片 / 存一下图片 / 全部保存 / 保存第二张图 / 把这几张照片都存下来 …
 * <h3>不触发</h3>
 * 方法/原因/位置问句（图片怎么保存、图片保存在哪里、为什么保存不了）、别的宾语（保存联系人、保存文件、保存网页、保存视频）、
 * 截图/截屏（那是「截一张屏」，不是存屏上的图）、只说「存一下」（S2 的记一下前缀）。
 * <h3>选择口令（只在多图编号芯片待选时生效）</h3>
 * 第二张 / 第2张 / 2号 / 二 / 最后一张 / 第一张和第三张 / 1和3 / 全部 / 都要 / 取消。
 */
public final class ImageSaveIntent {

    private ImageSaveIntent() {}

    /** 触发命中。 */
    public static final class Hit {
        /** 「全部保存 / 都存下来」。 */
        public final boolean all;
        /** 句里点了第几张（1 起），0 = 没点。 */
        public final int ordinal;

        Hit(boolean all, int ordinal) {
            this.all = all;
            this.ordinal = ordinal;
        }

        @Override public String toString() {
            return all ? "ALL" : (ordinal > 0 ? "#" + ordinal : "ONE_OR_ASK");
        }
    }

    private static final String VERB = "(?:保存|存|下载)";
    private static final String IMG = "(?:图片|图|照片|相片|原图|大图|高清图|壁纸)";
    private static final String DEM = "(?:这|那)?(?:一)?(?:张|几张|些|一些|两张|几幅|幅|个)?";
    private static final String ORD = "(?:第(?:[一二两三四五六七八九十]|[1-9])(?:张|幅|个)?)";
    private static final String TAIL = "(?:下来|起来|到相册|到手机|到本地|到图库|进相册|一下)?(?:吧|啊|呀|哈|呗|了)?";
    private static final String HEAD = "^(?:请|麻烦|帮我|给我|你|能不能|可以|可不可以|快|赶紧)*(?:帮我|给我)?";

    /** 动词在前：「保存图片」「存这张图」「存一下图片」「保存第二张图」「下载这张图片」。 */
    private static final Pattern P_VERB_FIRST = Pattern.compile(
            HEAD + VERB + "(?:一下|下)?(?:全部|所有|全部的|所有的)?(?:" + DEM + "|" + ORD + ")" + IMG + TAIL + "$");
    /** 宾语在前：「把图存下来」「把这张照片存下来」「这张图存一下」「图片都保存了吧」「把第二张图存下来」。 */
    private static final Pattern P_OBJ_FIRST = Pattern.compile(
            HEAD + "(?:把|将)?(?:全部|所有)?(?:" + DEM + "|" + ORD + ")" + IMG + "(?:都|全部|全|全都)?" + VERB + "(?:一下)?" + TAIL + "$");
    /** 全部：「全部保存」「都保存」「保存全部」「保存所有图片」「全存下来」「都存下来」。 */
    private static final Pattern P_ALL = Pattern.compile(
            HEAD + "(?:(?:全部|全都|都|全)(?:给我)?(?:保存|存)(?:下来|起来)?(?:吧)?|(?:保存|存)(?:一下)?(?:全部|所有)(?:的)?" + IMG + "?" + TAIL + ")$");
    /** 序数在前的短句：「第二张存一下」「保存第二张」。 */
    private static final Pattern P_ORD_SHORT = Pattern.compile(
            HEAD + "(?:" + VERB + "(?:一下)?" + ORD + "|(?:把)?" + ORD + VERB + "(?:一下)?" + TAIL + ")$");

    private static final Pattern QUESTION = Pattern.compile(
            "怎么|怎样|如何|为什么|为啥|咋|在哪|哪里|哪儿|什么|能保存吗|可以保存吗|吗$|呢$|不了|失败|不能|没法|办法|教程|方法");
    private static final Pattern ORD_IN = Pattern.compile("第([一二两三四五六七八九十]|[1-9])");
    private static final Pattern ALL_IN = Pattern.compile("全部|所有|全都|都(?:保存|存)|全存");

    /** 归一化：去空白与常见标点。 */
    public static String norm(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s，,。.！!？?、~～…“”\"'：:；;（）()]+", "");
    }

    /** 是否是存图指令；null = 不是。 */
    public static Hit match(String utterance) {
        String q = norm(utterance);
        if (q.length() < 2 || q.length() > 24) return null;
        if (QUESTION.matcher(q).find()) return null;
        if (q.contains("截图") || q.contains("截屏") || q.contains("视频")) return null;
        boolean hit = P_VERB_FIRST.matcher(q).matches() || P_OBJ_FIRST.matcher(q).matches()
                || P_ALL.matcher(q).matches() || P_ORD_SHORT.matcher(q).matches();
        if (!hit) return null;
        int ord = 0;
        Matcher m = ORD_IN.matcher(q);
        if (m.find()) ord = digit(m.group(1));
        boolean all = ord == 0 && ALL_IN.matcher(q).find();
        return new Hit(all, ord);
    }

    public static boolean isTrigger(String utterance) {
        return match(utterance) != null;
    }

    // ------------------------------------------------------------------ 选择口令

    /** 选择结果。 */
    public static final class Selection {
        public final boolean all;
        public final boolean cancel;
        /** 选中的序号（1 起，升序去重）。 */
        public final List<Integer> picks;
        /** 说了序号但超出范围（「第五张」但只有 3 张）。 */
        public final boolean outOfRange;

        Selection(boolean all, boolean cancel, List<Integer> picks, boolean outOfRange) {
            this.all = all;
            this.cancel = cancel;
            this.picks = picks;
            this.outOfRange = outOfRange;
        }

        @Override public String toString() {
            return cancel ? "CANCEL" : all ? "ALL" : outOfRange ? "OUT_OF_RANGE" : "PICK" + picks;
        }
    }

    private static final Pattern SEL_CANCEL = Pattern.compile("^(?:取消|算了|不用了?|不要了|不存了|不保存了|别存了|退出)(?:吧|啦)?$");
    private static final Pattern SEL_ALL = Pattern.compile(
            "^(?:就|那就|都|全部|全都|所有|每张|每一张)?(?:全部|都|全都|所有|每张)(?:的)?(?:都)?(?:要|存|保存|存下来|保存下来|选)?(?:吧|了)?$"
                    + "|^(?:都要|全要|全选|全部要|全部都要|全部保存|都保存|都存|全存|保存全部|存全部|全部吧)$");
    private static final Pattern SEL_TOKEN = Pattern.compile("第?([一二两三四五六七八九十]|[1-9])(?:张|幅|个|号)?");
    private static final Pattern SEL_LAST = Pattern.compile("^(?:最后|最后那|最后一)(?:张|个|幅)$");
    private static final Pattern SEL_FILLER = Pattern.compile(
            "^(?:就|那就|要|选|我要|保存|存|存一下|保存一下)|(?:吧|啦|了|就行|就好|那张|这张|图片|图|照片|的)$");

    /**
     * 解析选择口令；null = 不是选择（交回原流程）。
     * @param n 当前编号芯片个数
     */
    public static Selection parseSelection(String utterance, int n) {
        String q = norm(utterance);
        if (q.length() == 0 || q.length() > 16) return null;
        if (SEL_CANCEL.matcher(q).matches()) return new Selection(false, true, Collections.<Integer>emptyList(), false);
        if (SEL_ALL.matcher(q).matches()) return new Selection(true, false, Collections.<Integer>emptyList(), false);
        String s = q;
        for (int i = 0; i < 3; i++) {
            String t = SEL_FILLER.matcher(s).replaceAll("");
            if (t.equals(s)) break;
            s = t;
        }
        if (s.length() == 0) return null;
        if (SEL_LAST.matcher(s).matches()) {
            List<Integer> one = new ArrayList<Integer>();
            one.add(n);
            return new Selection(false, false, one, n <= 0);
        }
        // 序号列表：「第一张和第三张」「1和3」「1、3」「一三」
        String rest = s.replaceAll("和|跟|与|还有|以及|加上|、|,|跟上", "");
        List<Integer> picks = new ArrayList<Integer>();
        Matcher m = SEL_TOKEN.matcher(rest);
        int pos = 0;
        boolean out = false;
        while (m.find()) {
            if (m.start() != pos) return null;   // 中间夹了别的字 → 不是选择口令
            pos = m.end();
            int d = digit(m.group(1));
            if (d <= 0) return null;
            if (d > n) out = true;
            else if (!picks.contains(d)) picks.add(d);
        }
        if (pos != rest.length() || (picks.isEmpty() && !out)) return null;
        Collections.sort(picks);
        return new Selection(false, false, picks, out && picks.isEmpty());
    }

    static int digit(String s) {
        if (s == null || s.length() == 0) return 0;
        char c = s.charAt(0);
        if (c >= '1' && c <= '9') return c - '0';
        String cn = "一二三四五六七八九十";
        if (c == '两') return 2;
        int i = cn.indexOf(c);
        return i >= 0 ? i + 1 : 0;
    }
}
