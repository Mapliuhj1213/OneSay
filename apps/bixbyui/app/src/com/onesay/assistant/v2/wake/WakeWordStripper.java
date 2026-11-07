package com.onesay.assistant.v2.wake;

import android.util.Log;

import com.onesay.assistant.v2.router.IOfflineProbe;
import com.onesay.assistant.v2.router.PinyinUtil;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 唤醒词剥离与一句式直说（One-Shot）切分引擎。
 *
 * 核心功能：
 * 1. 严格剥离语音识别文本中夹带的唤醒词前缀与同音误读变体；
 * 2. 移植并升级 v1 的 splitWakeCommand 双向滑动窗口切分算法；
 * 3. 一句式直说：剥离前缀唤醒段与尾部噪音（OneSay 默认唤醒词表为空，见 WAKE_WORDS）。
 */
public class WakeWordStripper {

    private static final String TAG = "WakeWordStripper";
    private static final int DIRECT_MAX_EXTRA = 3; // 允许的后缀最大残留杂音字数

    private static final List<String> WAKE_WORDS = new ArrayList<String>();

    static {
        // OneSay：不内置任何唤醒词，也不剥离任何厂商唤醒词前缀（原 嗨比西比 / Bixby 同音表已删除）。
        // 列表留作扩展点：以后若接入用户自定义唤醒词的文字形式，可在这里登记。

        // 按长度降序排列，优先长匹配
        Collections.sort(WAKE_WORDS, new Comparator<String>() {
            @Override
            public int compare(String o1, String o2) {
                return Integer.valueOf(o2.length()).compareTo(o1.length());
            }
        });
    }

    /**
     * 首字是否疑似唤醒词（O(1) 预过滤，避免对非唤醒普通句子全量计算）。
     */
    public static boolean mightStartWithWake(String t) {
        if (t == null || t.length() == 0) return false;
        return false;   // OneSay：没有内置唤醒词，不做首字预判
    }

    /**
     * ★ 缺陷修复（2026-10-02）：2 字及以内的短唤醒词（如「比西」）极易与日常汉语词汇碰撞。
     * 它们只允许**全字精确匹配**，禁止 startsWith / contains / 拼音 contains 等模糊判定。
     * （2026-10-04：「小溪」已按用户要求从唤醒词表下线。）
     */
    private static boolean isShortWakeWord(String w) {
        return w != null && w.length() <= 2;
    }

    /**
     * 判断文本片段是否为唤醒词（字面量匹配）。
     */
    public static boolean isWakeText(String text) {
        if (text == null || text.length() == 0) return false;
        String s = text.toLowerCase().replaceAll("\\s+", "");
        for (String w : WAKE_WORDS) {
            if (s.equals(w)) return true;
            if (!isShortWakeWord(w) && s.startsWith(w)) return true;
        }
        return false;
    }

    /**
     * 判断文本片段是否为唤醒词（拼音容错匹配）。
     */
    public static boolean isWakeTextPy(String text) {
        if (text == null || text.length() == 0) return false;
        String py = PinyinUtil.toPinyin(text);
        if (py.length() < 4) return false;
        // OneSay：不内置任何唤醒词的拼音容错表（原 Bixby 同音表已删除）
        return false;
    }

    /**
     * 一口气说「嗨比西比，打开蓝牙」时，抽出里面的指令。
     *
     * 算法思想（继承并完善 v1 生产经验）：
     * 1. 在 1..8 字符前缀试探唤醒切点；
     * 2. 在切点之后试探各种后缀长度，使用 L0RuleRouter 验证是否命中能力；
     * 3. 约束：excess (后缀长度 - 指令字面量) >= 0 且 <= 3；
     * 4. 择优选取残留噪音最少、字面量最具体的合法指令！
     *
     * @param normText 已经归一化的转写文本
     * @param wakeJustHappened 最近 3 秒内是否刚刚发生过唤醒
     * @param probe 离线可执行性探针（严禁传联网的 L2；生产环境传 UnifiedRouter）
     * @return 抽出的指令字符串，若无则返回 null
     */
    public static String splitWakeCommand(String normText, boolean wakeJustHappened, IOfflineProbe probe) {
        if (normText == null || probe == null) return null;
        int len = normText.length();
        if (len < 4 || len > 32) return null;

        if (!wakeJustHappened && !mightStartWithWake(normText)) {
            return null;
        }

        int maxI = Math.min(8, len - 2);
        String best = null;
        int bestExcess = Integer.MAX_VALUE;
        int bestLit = 0;

        for (int i = 1; i <= maxI; i++) {
            String pre = normText.substring(0, i);
            if (!(wakeJustHappened || isWakeText(pre) || isWakeTextPy(pre))) {
                continue;
            }

            for (int j = i + 2; j <= len; j++) {
                String post = normText.substring(i, j);
                // ★ 缺陷修复（2026-10-02）：原先硬编码只探测 L0RuleRouter，
                //   导致「嗨比西比，现在几点」这类 Ask 问答一句式永远切分失败。
                //   现在改为探测完整离线级联（Ask + L0 + L1），但**绝不触网**。
                if (!probe.canHandle(post)) continue;

                int lit = probe.literalLength(post);
                if (lit < 2) continue;

                int excess = post.length() - lit;
                if (excess < 0 || excess > DIRECT_MAX_EXTRA) continue;

                if (lit > bestLit || (lit == bestLit && excess < bestExcess)) {
                    bestExcess = excess;
                    bestLit = lit;
                    best = post;
                }
            }
        }

        if (best != null) {
            Log.i(TAG, "[WAKE+CMD] 成功切分出指令=\"" + best + "\" (字面量=" + bestLit + ", 残留=" + bestExcess + ") 原文=\"" + normText + "\"");
        }
        return best;
    }

    /**
     * 剥离文本开头的唤醒词。
     *
     * @param query 原始识别文本
     * @return 剥离唤醒词后的真实意图指令文本
     */
    public static String strip(String query) {
        if (query == null) return "";
        String s = query.trim();

        // 1. 字面量前缀剥离
        for (String w : WAKE_WORDS) {
            if (s.startsWith(w)) {
                s = s.substring(w.length()).trim();
                break;
            }
            String spaceless = s.replaceAll("\\s+", "");
            if (spaceless.startsWith(w)) {
                int charsFound = 0;
                int cutIdx = 0;
                for (int i = 0; i < s.length(); i++) {
                    if (!Character.isWhitespace(s.charAt(i))) {
                        charsFound++;
                    }
                    if (charsFound == w.length()) {
                        cutIdx = i + 1;
                        break;
                    }
                }
                s = s.substring(cutIdx).trim();
                break;
            }
        }

        // 2. 拼音容错剥离前缀（针对海必须别、拍品需比等未被字面完全覆盖的开头发音）
        if (s.length() >= 4) {
            for (int i = 2; i <= Math.min(6, s.length()); i++) {
                String sub = s.substring(0, i);
                if (isWakeTextPy(sub)) {
                    s = s.substring(i).trim();
                    break;
                }
            }
        }

        // 3. 剥离扬声器应答音残留（前缀「我在打开蓝牙」→「打开蓝牙」；后缀「讲个笑话。我在。」→「讲个笑话。」）
        s = stripAckEcho(s);

        // 4. 剔除紧随其后的逗号、标点或助词
        s = s.replaceAll("^[，,。！!？?\\s]+", "");
        return s;
    }

    /** 扬声器自身应答短语黑名单，杜绝将扬声器声音当成用户指令 */
    private static final String[] ACK_PHRASES = {
            "我在请讲", "在呢请讲", "我在听请讲", "我在听", "在听",
            "我在呢", "我在", "在的", "在呢", "请讲", "我是", "哎", "诶", "嗯"
    };

    /**
     * 判断文本是否纯粹属于扬声器自身应答音回读（如"我在"、"在的"、"在呢"等）。
     */
    public static boolean isAckSoundUtterance(String text) {
        if (text == null) return false;
        String s = text.replaceAll("[\\s，。？！、：；“”‘’\"'`]", "");
        if (s.length() == 0) return true;
        for (String ack : ACK_PHRASES) {
            if (s.equals(ack)) return true;
        }
        // ★ r2/voice-templates：整句等于自己的模板短句（「好了，看屏幕」「出错了，看屏幕」…）= 扬声器回读
        if (com.onesay.assistant.v2.tts.VoiceTemplate.isTemplateEcho(s)) return true;
        // ★ R14.1：原版应答语音的开头单字「在」（流式中间结果先出「在」再出「在的」）—— 只做整句相等，不进前缀剥离表
        return s.equals("在");
    }

    /**
     * 剥离文本开头的应答音前缀（防用户紧随应答音发音导致指令夹带"我在..."前缀）。
     */
    public static String stripAckPrefix(String s) {
        if (s == null) return "";
        for (String ack : ACK_PHRASES) {
            if (s.startsWith(ack) && s.length() > ack.length()) {
                return s.substring(ack.length()).trim();
            }
        }
        return s;
    }

    /**
     * ★ 10-23：结尾的应答语。原版应答语音（「我在」/「在的」）和抢话重叠时，转写会是「讲个笑话。我在。」——
     * 应答语被转在用户的话后面。只收应答语音本身会说的几种（不收「嗯/哎/我是/请讲」这类会出现在正常句尾的词）。
     */
    private static final String[] ACK_SUFFIX_PHRASES = {
            "我在听请讲", "我在请讲", "在呢请讲", "我在听", "我在呢", "我在", "在的", "在呢"
    };

    private static boolean isAckBoundary(char c) {
        return Character.isWhitespace(c) || "，,。.！!？?、；;：:".indexOf(c) >= 0;
    }

    /**
     * 剥离文本结尾的应答语（「讲个笑话。我在。」→「讲个笑话。」）。
     * 要求应答语前面是标点或空白（句子边界），以免把「跟妈妈说我在」这类正常内容的结尾吃掉；
     * 剥完前面若没有实际内容（整句只有应答语，如「我在。」），原样返回，交给 isAckSoundUtterance 按 R14 规则当空 turn。
     */
    public static String stripAckSuffix(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0 && isAckBoundary(s.charAt(end - 1))) end--;
        String body = s.substring(0, end);
        for (String ack : ACK_SUFFIX_PHRASES) {
            if (!body.endsWith(ack)) continue;
            int cut = body.length() - ack.length();
            if (cut == 0 || !isAckBoundary(body.charAt(cut - 1))) return s;
            String head = body.substring(0, cut);
            int k = head.length();
            while (k > 0 && isAckBoundary(head.charAt(k - 1))) k--;
            if (k == 0) return s;   // 前面只有标点：整句其实只有应答语 → 保持 R14 行为
            return head.trim();
        }
        return s;
    }

    /** R14 应答语规则（10-23 起）：先剥开头的应答语，再剥结尾的。整句只有应答语时与 R14 相同（原样返回）。 */
    public static String stripAckEcho(String s) {
        return stripAckSuffix(stripAckPrefix(s));
    }

    public static boolean containsWakeWord(String text) {
        if (text == null) return false;
        String s = text.toLowerCase().replaceAll("\\s+", "");
        for (String w : WAKE_WORDS) {
            if (s.equals(w)) return true;
            // ★ 修复：短词不参与 contains 模糊判定
            if (!isShortWakeWord(w) && s.contains(w)) return true;
        }
        return isWakeTextPy(text);
    }
}
