package com.onesay.assistant.v2.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 中文关键词 → sign 倒排索引（方案 A：L0 层兜住高频指令，Top-K 只兜长尾）。
 *
 * <p><b>动机（2026-10-03 能力面调研）</b>：{@code CapabilityRetriever} 的打分用的是
 * verbs/entities/examples 这些**凑数字段**（全表几乎都是"设置/setting/调整设置"），
 * 真正带信息量的中文说法 {@code Capability.sayTemplates} 零参与；且其
 * {@code split("\\\\.")} 正则写错导致 sign 从不切分。结果 Top-8 在 62 条时就崩坏
 * （{@code wifi开着吗} 掉出 Top-8、{@code app.launch} 在"打开相机"里排 41/62）。
 *
 * <p><b>做法</b>：把每条能力的 keywords（显式声明的中文关键词）+ say 模板里的
 * 实义词拆出来建 {@code Map<keyword, List<sign>>}。查询时对用户语句做
 * **关键词命中统计**，命中唯一且明确的能力直接交付；命中多个才回退 Top-K。
 * 全程毫秒级、零网络、零 token，且**不碰 {@code CapabilityRetriever} 内部**。
 *
 * <p><b>线程安全</b>：索引在 {@code registerAll} 完成后惰性构建一次，
 * 构建后只读，天然无锁。构建时机由 {@link CapabilityRegistry} 触发。
 */
public final class KeywordIndex {

    private static final String TAG = "KeywordIndex";

    /** keyword → 命中的 sign 列表（保持注册顺序） */
    private final Map<String, List<String>> mIndex = new LinkedHashMap<String, List<String>>();

    /** 已建好的能力总数（用于判断是否需要重建） */
    private int mBuiltSize = -1;

    private static volatile KeywordIndex sInstance;

    private KeywordIndex() {}

    public static KeywordIndex getInstance() {
        if (sInstance == null) {
            synchronized (KeywordIndex.class) {
                if (sInstance == null) {
                    sInstance = new KeywordIndex();
                }
            }
        }
        return sInstance;
    }

    /** 构建/增量重建索引。当能力表规模变化时自动重建。 */
    public synchronized void ensureBuilt(List<Capability> all) {
        if (all == null) return;
        if (mBuiltSize == all.size()) return;
        mIndex.clear();
        for (Capability cap : all) {
            indexCapability(cap);
        }
        mBuiltSize = all.size();
    }

    private void indexCapability(Capability cap) {
        // 1. 显式 keywords（最强信号）
        for (String kw : cap.keywords) {
            add(kw, cap.sign);
        }
        // 2. say 模板里的实义词（剥离槽位占位符与纯结构词）
        for (String say : cap.sayTemplates) {
            for (String tok : tokenizeSay(say)) {
                add(tok, cap.sign);
            }
        }
    }

    /** 把一条 say 模板切成关键词：去 {slot}，中文按 2-gram、英文按整词。 */
    private static List<String> tokenizeSay(String say) {
        if (say == null) return Collections.emptyList();
        String s = say.replaceAll("\\{[^}]*\\}", "");   // 去 {n}/{app}/{url}/{content}
        List<String> out = new ArrayList<String>();
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();

        // 先抽出英文/数字整词，中文单独累积
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean isCjk = (c >= 0x4E00 && c <= 0x9FFF);
            if (isCjk) {
                if (ascii.length() >= 2) out.add(ascii.toString());
                ascii.setLength(0);
                cjk.append(c);
            } else if (Character.isLetterOrDigit(c)) {
                if (cjk.length() > 0) { addCjkGrams(out, cjk.toString()); cjk.setLength(0); }
                ascii.append(c);
            } else {
                if (ascii.length() >= 2) out.add(ascii.toString());
                if (cjk.length() > 0) { addCjkGrams(out, cjk.toString()); cjk.setLength(0); }
                ascii.setLength(0);
            }
        }
        if (ascii.length() >= 2) out.add(ascii.toString());
        if (cjk.length() > 0) addCjkGrams(out, cjk.toString());

        // 去重（保序）
        List<String> dedup = new ArrayList<String>();
        for (String t : out) if (!dedup.contains(t)) dedup.add(t);
        return dedup;
    }

    /** 中文按 2-gram 滑窗切词（"蓝牙开着吗" → 蓝牙/牙开/开着/着吗），保证单字/双字实体可命中。 */
    private static void addCjkGrams(List<String> out, String cjk) {
        if (cjk.length() < 2) return;
        for (int i = 0; i + 1 < cjk.length(); i++) {
            out.add(cjk.substring(i, i + 2));
        }
    }

    private void add(String keyword, String sign) {
        if (keyword == null || keyword.length() < 2) return;
        String k = keyword.toLowerCase();
        List<String> list = mIndex.get(k);
        if (list == null) {
            list = new ArrayList<String>();
            mIndex.put(k, list);
        }
        if (!list.contains(sign)) list.add(sign);
    }

    /**
     * 关键词级召回：返回命中的能力 sign 及其命中词数，按命中数降序。
     *
     * <p>只有"用户语句里完整出现某个关键词"才算命中（substring 匹配），
     * 与 L0 的 say 模板精确/正则匹配互补 —— L0 管"说法完全对得上"，
     * 这里管"说法对不上但关键实体对得上"（如 {@code wifi开着吗} 的 {@code wifi}）。
     */
    public List<Hit> query(String utterance) {
        List<Hit> hits = new ArrayList<Hit>();
        if (utterance == null || utterance.trim().length() == 0) return hits;
        String q = utterance.toLowerCase();

        // sign → 命中词数（保序）
        Map<String, Integer> score = new LinkedHashMap<String, Integer>();
        Map<String, List<String>> hitWords = new HashMap<String, List<String>>();

        for (Map.Entry<String, List<String>> e : mIndex.entrySet()) {
            String kw = e.getKey();
            if (!q.contains(kw)) continue;
            for (String sign : e.getValue()) {
                Integer c = score.get(sign);
                score.put(sign, c == null ? 1 : c.intValue() + 1);
                List<String> ws = hitWords.get(sign);
                if (ws == null) {
                    ws = new ArrayList<String>();
                    hitWords.put(sign, ws);
                }
                ws.add(kw);
            }
        }

        for (Map.Entry<String, Integer> e : score.entrySet()) {
            hits.add(new Hit(e.getKey(), e.getValue().intValue(), hitWords.get(e.getKey())));
        }
        // 命中词数降序；平手保持注册顺序（稳定）
        Collections.sort(hits, new java.util.Comparator<Hit>() {
            @Override public int compare(Hit a, Hit b) {
                return Integer.valueOf(b.count).compareTo(Integer.valueOf(a.count));
            }
        });
        return hits;
    }

    /** 命中结果：能力 sign + 命中关键词数 + 具体命中的词。 */
    public static final class Hit {
        public final String sign;
        public final int count;
        public final List<String> words;
        Hit(String sign, int count, List<String> words) {
            this.sign = sign;
            this.count = count;
            this.words = words;
        }
        @Override public String toString() {
            return "Hit{sign=" + sign + ", count=" + count + ", words=" + words + "}";
        }
    }

    /** 当前已索引能力数（自测断言用）。 */
    public int builtSize() { return mBuiltSize; }
}
