package com.onesay.assistant.plug.leakguard.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ★ fix/leak-guard（2026-11-02）：提示词回吐守卫（纯 Java，无 Android 依赖，可插拔 + 开关）。
 *
 * <h3>要拦的东西</h3>
 * <ol>
 *   <li><b>ASR 热词上下文回吐</b>（项目「老毛病」）：qwen3-asr 在近空 / 只有提示音的音频上，把热词上下文
 *       「语音识别热词表，以下专有名词请优先按原词识别：必应、bing、百度…」当识别结果吐回来。
 *       可能带前缀，也可能只吐中段词表「微信、支付宝、淘宝、京东…」，也可能没有分隔符连成一串。</li>
 *   <li><b>LLM 回吐系统提示词</b>：回答 / 朗读里出现系统提示词原文片段。</li>
 * </ol>
 *
 * <h3>三种口径</h3>
 * <ul>
 *   <li>{@link #check}（输入侧：识别最终结果 / 分段入账 / 指令入口）：热词前缀、热词表连串（≥{@link #RUN_MIN} 个热词以
 *       「、」或逗号相连，容 1 个不认识的夹词）、热词占比极高的长串、系统提示词指纹。</li>
 *   <li>{@link #checkPartial}（流式中间结果上屏）：同上但阈值更低（连串 ≥{@link #RUN_MIN_PARTIAL}、前缀前
 *       {@link #PREFIX_HEAD_PARTIAL} 字就算），误判的代价只是这一帧不上屏，最终结果照常执行。</li>
 *   <li>{@link #checkOutput}（回答卡片 / TTS / AgentLoop、L2 最终回答）：热词前缀、系统提示词指纹、
 *       按热词表<b>原顺序</b>连出 ≥{@link #RUN_MIN_ORDERED} 个热词（正常回答列举 App 不会恰好按表序）。
 *       不用「任意 6 个热词相连」——「微信、微博、抖音、快手、小红书、知乎」这种正常回答会被误杀。</li>
 * </ul>
 *
 * <h3>系统提示词指纹</h3>
 * 每段提示词按行切，去掉 HTML 注释、占位符行（{{…}}）、含数字的行（时间块等动态内容）、引号里的示例；
 * 归一化（去空白标点、转小写）后：≥{@link #FP_STRONG} 字的行取开头 / 中间 / 结尾各一段 {@link #FP_STRONG} 字作「强指纹」
 * （命中 1 个即判），{@link #FP_MIN}–{@link #FP_STRONG}−1 字的行整行作「弱指纹」（命中 ≥2 个不同的才判）。
 *
 * <p>开关：{@link #setEnabled}（宿主按 prefs / kill 文件设，默认开）。关掉后三个口径恒返回 {@link Verdict#PASS}。
 */
public final class PromptLeakGuard {

    /** 输入侧热词连串阈值（任务定：≥6）。 */
    public static final int RUN_MIN = 6;
    /** 中间结果上屏阈值。 */
    public static final int RUN_MIN_PARTIAL = 4;
    /** 输出侧：按热词表原顺序相连的个数。 */
    public static final int RUN_MIN_ORDERED = 8;
    /** 热词前缀在中间结果里出现前几个字就算（「语音识别热词」）。 */
    public static final int PREFIX_HEAD_PARTIAL = 6;
    /** 热词前缀在最终结果里出现前几个字就算（「语音识别热词表」）。 */
    public static final int PREFIX_HEAD = 7;
    /** 指纹最短字数（任务定 ≥12）。 */
    public static final int FP_MIN = 12;
    /** 强指纹长度。 */
    public static final int FP_STRONG = 20;
    /** 占比判据：归一化后至少这么长。 */
    public static final int DENSE_MIN_LEN = 14;
    /** 占比判据：热词覆盖的字数比例。 */
    public static final double DENSE_RATIO = 0.85;
    /** 占比判据：至少命中几个不同热词。 */
    public static final int DENSE_MIN_WORDS = 5;
    /** 指纹总数上限（防止误注册超大文本拖慢判定）。 */
    public static final int FP_CAP = 3000;

    public enum Reason {
        NONE, HOTWORD_PREFIX, HOTWORD_RUN, HOTWORD_DENSE, HOTWORD_ORDERED, SYSTEM_PROMPT;

        public String tag() {
            switch (this) {
                case HOTWORD_PREFIX: return "hotword_prefix";
                case HOTWORD_RUN: return "hotword_run";
                case HOTWORD_DENSE: return "hotword_dense";
                case HOTWORD_ORDERED: return "hotword_ordered";
                case SYSTEM_PROMPT: return "system_prompt";
                default: return "none";
            }
        }
    }

    /** 判定结果。leak=false 时 reason=NONE。detail 只放计数 / 指纹来源，不放全文。 */
    public static final class Verdict {
        public static final Verdict PASS = new Verdict(Reason.NONE, "");
        public final Reason reason;
        public final String detail;

        Verdict(Reason r, String d) {
            reason = r;
            detail = d == null ? "" : d;
        }

        public boolean leak() { return reason != Reason.NONE; }

        /** reason 标签 + 细节，日志用。 */
        public String describe() {
            return detail.length() == 0 ? reason.tag() : reason.tag() + "(" + detail + ")";
        }

        @Override public String toString() { return describe(); }
    }

    // ------------------------------------------------------------------ 全局实例 / 开关

    private static final PromptLeakGuard GLOBAL = new PromptLeakGuard();

    /** 进程内共享的那一个（宿主注册热词 / 提示词都往这里）。 */
    public static PromptLeakGuard global() { return GLOBAL; }

    private volatile boolean mEnabled = true;

    public void setEnabled(boolean on) { mEnabled = on; }

    public boolean isEnabled() { return mEnabled; }

    // ------------------------------------------------------------------ 数据

    private volatile String mPrefix = "";
    private volatile String mPrefixNorm = "";
    /** 热词（小写）→ 在表中的序号（先注册的序号小；只记第一次）。 */
    private final Map<String, Integer> mHotwords = new HashMap<String, Integer>();
    private int mMaxHotwordLen = 0;
    /** 归一化热词（去标点小写），给占比判据用。 */
    private final Set<String> mHotNorm = new HashSet<String>();
    private int mMaxHotNormLen = 0;

    private final List<String> mStrongFp = new ArrayList<String>();
    private final Map<String, String> mFpSource = new HashMap<String, String>();
    private final List<String> mWeakFp = new ArrayList<String>();
    private final Set<String> mFpSeen = new HashSet<String>();
    private final Set<String> mPromptSources = new LinkedHashSet<String>();
    private final Set<Integer> mPromptHashes = new HashSet<Integer>();

    public PromptLeakGuard() {}

    /** 热词上下文的固定开场白（AsrHotwords.HOTWORD_PROMPT_PREFIX）。 */
    public synchronized PromptLeakGuard setHotwordPrefix(String prefix) {
        mPrefix = prefix == null ? "" : prefix.trim();
        mPrefixNorm = norm(mPrefix);
        return this;
    }

    /**
     * 追加热词（按热词表顺序给）。重复的词保留第一次的序号。宿主每次重建热词表（含动态应用名）都可以再调一次。
     * 也接受一整段热词上下文（带前缀、以「、」分隔），会自己切开。
     */
    public synchronized PromptLeakGuard addHotwords(Collection<String> words) {
        if (words == null) return this;
        for (String w : words) addHotwordLocked(w);
        return this;
    }

    public synchronized PromptLeakGuard addHotwordCorpus(String corpus) {
        if (corpus == null) return this;
        String c = corpus.trim();
        if (mPrefix.length() > 0 && c.startsWith(mPrefix)) c = c.substring(mPrefix.length());
        else {
            int colon = c.indexOf('：');
            if (colon >= 0 && colon < 40) c = c.substring(colon + 1);   // 前缀还没注册时按冒号切
        }
        for (String w : c.split(SEP_RE)) addHotwordLocked(w);
        return this;
    }

    private void addHotwordLocked(String w) {
        if (w == null) return;
        String k = w.trim().toLowerCase(Locale.ROOT);
        if (k.length() == 0) return;
        if (!mHotwords.containsKey(k)) {
            mHotwords.put(k, mHotwords.size());
            if (k.length() > mMaxHotwordLen) mMaxHotwordLen = k.length();
        }
        String n = norm(k);
        if (n.length() >= 2 && mHotNorm.add(n) && n.length() > mMaxHotNormLen) mMaxHotNormLen = n.length();
    }

    public synchronized int hotwordCount() { return mHotwords.size(); }

    public synchronized int strongFingerprintCount() { return mStrongFp.size(); }

    public synchronized int weakFingerprintCount() { return mWeakFp.size(); }

    /** 已注册过的提示词来源名（日志 / 调试用）。 */
    public synchronized List<String> promptSources() { return new ArrayList<String>(mPromptSources); }

    /**
     * 注册一段系统提示词（运行时取）。同一段文本重复注册是空操作（按 hash + 长度去重）。
     *
     * @param source 来源名（如 "agent_prompt.md"、"L2.ask.output"），命中时写进 detail
     * @return 本次新增的指纹数
     */
    public synchronized int registerSystemPrompt(String source, String text) {
        if (text == null || text.length() == 0) return 0;
        int h = text.hashCode() * 31 + text.length();
        if (!mPromptHashes.add(h)) return 0;
        String src = source == null ? "?" : source;
        mPromptSources.add(src);
        int added = 0;
        for (String line : extractLines(text)) {
            String n = norm(line);
            if (n.length() < FP_MIN) continue;
            if (n.length() >= FP_STRONG) {
                added += addStrongLocked(n.substring(0, FP_STRONG), src);
                if (n.length() >= FP_STRONG * 2) {
                    int mid = (n.length() - FP_STRONG) / 2;
                    added += addStrongLocked(n.substring(mid, mid + FP_STRONG), src);
                }
                if (n.length() > FP_STRONG) added += addStrongLocked(n.substring(n.length() - FP_STRONG), src);
            } else {
                if (mStrongFp.size() + mWeakFp.size() >= FP_CAP) break;
                if (mFpSeen.add(n)) {
                    mWeakFp.add(n);
                    mFpSource.put(n, src);
                    added++;
                }
            }
        }
        return added;
    }

    private int addStrongLocked(String fp, String src) {
        if (mStrongFp.size() + mWeakFp.size() >= FP_CAP) return 0;
        if (!mFpSeen.add(fp)) return 0;
        mStrongFp.add(fp);
        mFpSource.put(fp, src);
        return 1;
    }

    /**
     * 提示词 → 可做指纹的行：去 HTML 注释、占位符行、含数字的行、引号里的内容（示例话术）、
     * Markdown 行首符号。纯 ASCII 行（JSON 示例 / 工具签名）不要。
     */
    static List<String> extractLines(String text) {
        String t = text.replaceAll("(?s)<!--.*?-->", "\n");
        List<String> out = new ArrayList<String>();
        for (String raw0 : t.split("\\r?\\n")) {
          String raw1 = raw0.trim();
          if (raw1.startsWith("{") || raw1.startsWith("[{") || raw1.startsWith("\"")) continue;
          // 行首编号 / 列表符（「1. 」「4b. 」「①」「- 」「· 」）不算内容，先去掉（否则整行因含数字被丢）
          raw1 = raw1.replaceFirst("^([0-9]+[a-z]?[.、)）]|[\u2460-\u2473]|[-·*•]+)\\s*", "");
          // 引号里多是用户话术示例（「打开微信」），正常回答可能原样说出来 —— 先挖掉再按句切
          raw1 = raw1.replaceAll("「[^」]*」", "|").replaceAll("“[^”]*”", "|")
                  .replaceAll("\"[^\"]*\"", "|").replaceAll("『[^』]*』", "|");
          // 按句切（单行长提示词也能用；含数字 / 占位符的只丢那一句）
          for (String raw : raw1.split("[。；！？]")) {
            String line = raw.trim();
            if (line.length() == 0) continue;
            if (line.contains("{{") || line.contains("}}")) continue;
            if (line.matches(".*[0-9０-９].*")) continue;
            // 「例如 / 比如 / 示例」之后是举例，截掉
            int ex = indexOfAny(line, "例如", "比如", "示例", "举例", "如：", "e.g.");
            if (ex >= 0) line = line.substring(0, ex);
            if (!hasCjk(line)) continue;
            // 挖掉引号后被切成几段的，各段分别作候选
            for (String seg : line.split("\\|")) {
                if (seg.trim().length() > 0) out.add(seg);
            }
          }
        }
        return out;
    }

    private static int indexOfAny(String s, String... keys) {
        int best = -1;
        for (String k : keys) {
            int i = s.indexOf(k);
            if (i >= 0 && (best < 0 || i < best)) best = i;
        }
        return best;
    }

    private static boolean hasCjk(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x4E00 && c <= 0x9FFF) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ 判定

    /** 输入侧（识别最终结果 / 分段 / 指令入口）。 */
    public Verdict check(String text) {
        if (!mEnabled || text == null) return Verdict.PASS;
        String t = text.trim();
        if (t.length() == 0) return Verdict.PASS;
        String n = norm(t);
        Verdict v = prefix(n, PREFIX_HEAD);
        if (v.leak()) return v;
        v = hotwordRun(t, RUN_MIN);
        if (v.leak()) return v;
        v = dense(n);
        if (v.leak()) return v;
        return systemPrompt(n);
    }

    /** 流式中间结果上屏（阈值更低，误判只是这一帧不上屏）。 */
    public Verdict checkPartial(String text) {
        if (!mEnabled || text == null) return Verdict.PASS;
        String t = text.trim();
        if (t.length() == 0) return Verdict.PASS;
        String n = norm(t);
        Verdict v = prefix(n, PREFIX_HEAD_PARTIAL);
        if (v.leak()) return v;
        v = hotwordRun(t, RUN_MIN_PARTIAL);
        if (v.leak()) return v;
        v = dense(n);
        if (v.leak()) return v;
        return systemPrompt(n);
    }

    /** 输出侧（回答卡片 / TTS / AgentLoop、L2 最终回答）。 */
    public Verdict checkOutput(String text) {
        if (!mEnabled || text == null) return Verdict.PASS;
        String t = text.trim();
        if (t.length() == 0) return Verdict.PASS;
        String n = norm(t);
        Verdict v = prefix(n, PREFIX_HEAD);
        if (v.leak()) return v;
        v = orderedRun(t);
        if (v.leak()) return v;
        return systemPrompt(n);
    }

    /** 便捷：输入侧判定，命中返回原因标签，否则 null。 */
    public String isLeak(String text) {
        Verdict v = check(text);
        return v.leak() ? v.describe() : null;
    }

    // ---- 热词前缀：归一化后出现前缀的前 head 字，或出现前缀后半句（「专有名词请优先按原词识别」）
    private Verdict prefix(String n, int head) {
        String p = mPrefixNorm;
        if (p.length() == 0 || n.length() == 0) return Verdict.PASS;
        int h = Math.min(head, p.length());
        if (n.contains(p.substring(0, h))) return new Verdict(Reason.HOTWORD_PREFIX, "head");
        if (p.length() >= FP_MIN) {
            String tail = p.substring(p.length() - FP_MIN);
            if (n.contains(tail)) return new Verdict(Reason.HOTWORD_PREFIX, "tail");
        }
        return Verdict.PASS;
    }

    private static final String SEP_RE = "[、，,;；/|｜\\s]+";

    private List<String> tokens(String t) {
        String s = t;
        // 前缀若在里面，切掉前缀那段（前缀已经单独判过；这里给「前缀被截断」的情况兜底）
        String[] parts = s.split(SEP_RE);
        List<String> out = new ArrayList<String>(parts.length);
        for (String p : parts) {
            String q = p.replaceAll("^[：:。．.！!？?\"“”「」]+|[：:。．.！!？?\"“”「」]+$", "")
                    .trim().toLowerCase(Locale.ROOT);
            if (q.length() > 0) out.add(q);
        }
        return out;
    }

    // ---- 热词连串：最长的「热词 (分隔) 热词 …」，容 1 个不认识的夹词（≤10 字，不计数）
    private Verdict hotwordRun(String t, int min) {
        Map<String, Integer> hw;
        synchronized (this) {
            if (mHotwords.isEmpty()) return Verdict.PASS;
            hw = mHotwords;
        }
        List<String> toks = tokens(t);
        int best = 0, run = 0, gap = 0;
        synchronized (this) {
            for (String k : toks) {
                if (hw.containsKey(k)) {
                    run++;
                    gap = 0;
                    if (run > best) best = run;
                } else if (run > 0 && gap == 0 && k.length() <= 10) {
                    gap = 1;   // 夹一个不认识的词（动态应用名漏注册 / 识别错一个字）
                } else {
                    run = 0;
                    gap = 0;
                }
            }
        }
        return best >= min ? new Verdict(Reason.HOTWORD_RUN, "run=" + best) : Verdict.PASS;
    }

    // ---- 输出侧：按热词表原顺序相连（序号递增、相邻差 ≤3）
    private Verdict orderedRun(String t) {
        List<String> toks = tokens(t);
        int best = 0, run = 0, last = -100;
        synchronized (this) {
            if (mHotwords.isEmpty()) return Verdict.PASS;
            for (String k : toks) {
                Integer idx = mHotwords.get(k);
                if (idx == null) {
                    run = 0;
                    last = -100;
                    continue;
                }
                if (run > 0 && idx > last && idx - last <= 3) run++;
                else run = 1;
                last = idx;
                if (run > best) best = run;
            }
        }
        return best >= RUN_MIN_ORDERED ? new Verdict(Reason.HOTWORD_ORDERED, "run=" + best) : Verdict.PASS;
    }

    // ---- 占比：归一化串里贪心最长匹配热词，覆盖率 ≥85% 且 ≥5 个不同热词且够长
    private Verdict dense(String n) {
        if (n.length() < DENSE_MIN_LEN) return Verdict.PASS;
        synchronized (this) {
            if (mHotNorm.isEmpty()) return Verdict.PASS;
            int covered = 0;
            Set<String> distinct = new HashSet<String>();
            int i = 0;
            while (i < n.length()) {
                int hit = 0;
                int maxL = Math.min(mMaxHotNormLen, n.length() - i);
                for (int L = maxL; L >= 2; L--) {
                    String sub = n.substring(i, i + L);
                    if (mHotNorm.contains(sub)) {
                        hit = L;
                        distinct.add(sub);
                        break;
                    }
                }
                if (hit > 0) {
                    covered += hit;
                    i += hit;
                } else {
                    i++;
                }
            }
            if (distinct.size() >= DENSE_MIN_WORDS && covered >= n.length() * DENSE_RATIO) {
                return new Verdict(Reason.HOTWORD_DENSE, "words=" + distinct.size() + ",cover=" + (covered * 100 / n.length()) + "%");
            }
        }
        return Verdict.PASS;
    }

    // ---- 系统提示词指纹
    private Verdict systemPrompt(String n) {
        if (n.length() < FP_MIN) return Verdict.PASS;
        synchronized (this) {
            for (String fp : mStrongFp) {
                if (n.contains(fp)) return new Verdict(Reason.SYSTEM_PROMPT, "strong@" + mFpSource.get(fp));
            }
            String firstSrc = null;
            int weak = 0;
            for (String fp : mWeakFp) {
                if (n.contains(fp)) {
                    weak++;
                    if (firstSrc == null) firstSrc = mFpSource.get(fp);
                    if (weak >= 2) return new Verdict(Reason.SYSTEM_PROMPT, "weak2@" + firstSrc);
                }
            }
        }
        return Verdict.PASS;
    }

    // ------------------------------------------------------------------ 工具

    /** 归一化：去空白、标点、Markdown 符号，转小写。 */
    public static String norm(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isLetterOrDigit(c)) sb.append(Character.toLowerCase(c));
        }
        return sb.toString();
    }

    /** 日志用：前 20 字（换行压成空格）。不打全文。 */
    public static String head20(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').replace('\r', ' ').trim();
        return t.length() <= 20 ? t : t.substring(0, 20) + "…";
    }

    /** 测试用：清空（不动开关）。 */
    public synchronized void resetForTest() {
        mPrefix = "";
        mPrefixNorm = "";
        mHotwords.clear();
        mHotNorm.clear();
        mMaxHotwordLen = 0;
        mMaxHotNormLen = 0;
        mStrongFp.clear();
        mWeakFp.clear();
        mFpSeen.clear();
        mFpSource.clear();
        mPromptSources.clear();
        mPromptHashes.clear();
        mEnabled = true;
    }

    /** 只读视图（测试 / 调试）。 */
    public synchronized List<String> strongFingerprints() { return Collections.unmodifiableList(new ArrayList<String>(mStrongFp)); }
}
