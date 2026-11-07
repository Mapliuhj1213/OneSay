package com.onesay.assistant.v2.router;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class CapabilityRetriever {   // 审计 R-1：原名 TriageEngine —— 它只做能力 Top-K 召回，不做分诊（分诊是 TriageDecider）
    
    private static class ScoredCap {
        Capability cap;
        int score;
        ScoredCap(Capability cap, int score) {
            this.cap = cap;
            this.score = score;
        }
    }
    
    public static List<Capability> getTopK(String query, int k) {
        List<Capability> all = CapabilityRegistry.getInstance().listCapabilities();
        if (query == null || query.trim().length() == 0) return all;
        
        List<ScoredCap> scored = new ArrayList<ScoredCap>();
        for (Capability cap : all) {
            int score = scoreCapability(cap, query);
            scored.add(new ScoredCap(cap, score));
        }
        
        Collections.sort(scored, new Comparator<ScoredCap>() {
            @Override
            public int compare(ScoredCap c1, ScoredCap c2) {
                return Integer.compare(c2.score, c1.score);
            }
        });
        
        List<Capability> top = new ArrayList<Capability>();
        for (int i = 0; i < Math.min(k, scored.size()); i++) {
            if (scored.get(i).score > 0 || i < 3) { // 至少有些分数或者是兜底的前几个
                top.add(scored.get(i).cap);
            }
        }
        return top;
    }
    
    /**
     * ★ arch/tool-tiers（A 路）：全量打分（不截断），给 {@link com.onesay.assistant.v2.agenttools.ToolTiers#rank} 按
     * 「相关度段 → 档位 → 分数」排序。分数口径与 {@link #getTopK} 完全相同。
     */
    /** ★ fix/triage-fast：快路径认出的系统动作在 AgentLoop 候选里的分数（≥ ToolTiers.STRONG，进强相关段）。 */
    public static final int PINNED_SCORE = 60;

    public static List<com.onesay.assistant.v2.agenttools.ToolTiers.Scored> scoreAll(String query) {
        List<Capability> all = CapabilityRegistry.getInstance().listCapabilities();
        List<com.onesay.assistant.v2.agenttools.ToolTiers.Scored> out = new ArrayList<com.onesay.assistant.v2.agenttools.ToolTiers.Scored>(all.size());
        boolean empty = query == null || query.trim().length() == 0;
        // ★ fix/triage-fast：整句 / 子句能被快路径认出的系统动作（回桌面、清空后台、截屏、媒体键…）钉进强相关段，
        //   多步任务里每一步都先看到直达工具，不会因字面打分低被截掉而退到点界面
        java.util.Set<String> pinned = empty ? java.util.Collections.<String>emptySet() : CompoundSplitter.fastSigns(query);
        for (int i = 0; i < all.size(); i++) {
            Capability cap = all.get(i);
            int sc = empty ? 0 : scoreCapability(cap, query);
            if (pinned.contains(cap.sign)) sc = Math.max(sc, PINNED_SCORE);
            out.add(new com.onesay.assistant.v2.agenttools.ToolTiers.Scored(cap, sc, i));
        }
        return out;
    }

    /**
     * ★ arch/tool-tiers（A 路）：AgentLoop 开局候选 —— 按档位 + 相关度排序再截断（替代字面 Top-K），同时开本任务的工具层会话。
     * 空句子与 {@link #getTopK} 一样返回全量。
     */
    public static List<Capability> getTopKTiered(String query, int k) {
        if (query == null || query.trim().length() == 0) {
            com.onesay.assistant.v2.agenttools.AgentToolset.begin(query);
            return CapabilityRegistry.getInstance().listCapabilities();
        }
        // ★ fix/shop-research（R2）：购物 / 比价句不配亮度 / 音量 / 开关这类无关工具（「买鼠标」曾召回亮度三件套）
        return dropIrrelevant(com.onesay.assistant.v2.agenttools.AgentToolset.candidates(query, scoreAll(query), k), query);
    }

    /** ★ fix/shop-research：购物句去掉无关工具（句子本身点名了亮度 / 音量等就不去）。非购物句原样返回。 */
    public static List<Capability> dropIrrelevant(List<Capability> caps, String query) {
        if (caps == null || ResearchGate.kindOf(query) != ResearchGate.Kind.SHOP) return caps;
        if (query.matches(".*(?:亮度|音量|声音|闹钟|手电|蓝牙|wifi|WiFi|WLAN|飞行模式|省电).*")) return caps;
        List<Capability> out = new ArrayList<Capability>(caps.size());
        for (Capability c : caps) if (c != null && !ResearchGate.irrelevantForShop(c.sign)) out.add(c);
        return out;
    }

    private static int scoreCapability(Capability cap, String query) {
        int score = 0;
        String q = query.toLowerCase();
        
        // Verbs
        if (cap.verbs != null) {
            for (String v : cap.verbs) {
                if (q.contains(v.toLowerCase())) score += 10;
            }
        }
        
        // Entities
        if (cap.entities != null) {
            for (String e : cap.entities) {
                if (q.contains(e.toLowerCase())) score += 5;
            }
        }
        
        // Domain
        if (cap.domain != null && q.contains(cap.domain.toLowerCase())) score += 2;
        
        // Summary 
        if (cap.summary != null) {
            for (int i=0; i<cap.summary.length()-1; i++) {
                if (q.contains(cap.summary.substring(i, i+2))) score += 3;
            }
        }
        
        // Examples
        if (cap.examples != null) {
            for (String ex : cap.examples) {
                if (q.contains(ex) || ex.contains(q)) score += 8;
            }
        }
        
        // //
        //   BUG：`cap.sign.split("\\\\.")` —— 源码里的 "\\\\." 经 Java 转义后是 `\\.`，
        //   在正则里表示"一个反斜杠 + 任意字符"，**根本不按点切分**。
        //   于是 parts 永远是整串 ["store.search.samsung"]，q.contains(整串) 恒 false，
        //   **sign 这一路打分从未生效过**。改成 split("\\.") 后才真正按点切。
        //
        //   泛化：光修 split 还不够 —— 用户说「把微信装上」时，
        //   句中没有"安装/下载"这两个 verbs，store.* 依然拿不到 verbs 分，
        //   会被摘要二元组蹭出来的零散分挤掉 Top-K，LLM 看不到它 → 答"我无法安装"。
        //   这是**和 IntentClassifier 同一类病**（精确词表追不上口语搭配），
        //   解法也一致：用**动作语素类别**把同族能力强提上来。
        //   句子是 INSTALL → 所有"能装应用"的能力（verbs 含下载/安装/卸载）加权。
        String[] parts = cap.sign.split("\\.");
        for (String p : parts) {
            if (q.contains(p.toLowerCase())) score += 5;
        }

        // ★ 语素类别 → 能力 verbs 的语义对齐（机制化召回）
        ActionLexicon.Intent actIntent = ActionLexicon.classify(
                L0RuleRouter.normalizeUtterance(query));
        if (actIntent != ActionLexicon.Intent.NONE && cap.verbs != null) {
            boolean verbAligned = false;
            for (String v : cap.verbs) {
                if (verbMatchesIntent(v, actIntent)) { verbAligned = true; break; }
            }
            if (verbAligned) score += 25;   // 足够大，保证压过摘要蹭分挤进 Top-K
        }

        // ★ 状态同义召回（2026-10-04 补，机制化）
        //
        //   缺陷（实测定位）：用户说「让手机安静点」时，volume.ring.mute 进不了 Top-12。
        //   原因：上面 Summary 那一路是**二元组蹭分** ——
        //     q "让手机安静点" 的二元组 {让手,手机,机安,安静,静点}
        //     cap summary "静音电话来电铃声" 的二元组 {静音,音电,电话,话来,来电,电铃,铃声}
        //     **零重合** → summary 贡献 0 分。
        //   而 settings.brightness.* 的 "设置屏幕亮度" 含"设置"→蹭到分→挤掉 volume 族。
        //   结果：LLM 拿到的清单里只有亮度/息屏，**没有静音**，
        //   于是它追问"你要调低亮度还是关自动旋转"—— 方向完全错了。
        //
        //   修法不是往 summary 加同义二元组（那还是穷举），而是**复用**
        //   ActionLexicon 已定义的状态同义词根：既然"安静"已被认定为"静音"的同义，
        //   就给含"静音"的能力加权。两处共用一份定义，改一处即同步。
        if (ActionLexicon.stateSynonymMatches(q, cap.summary)) score += 30;

        // 精确短语强命中（AgentLoop 实测暴露的结构性漏召）
        //
        //   事故：用户说「先看一下现在几点了，如果还没到晚上，就顺便把屏幕亮度调到最低」，
        //   Top-40 候选里**没有 time.now** —— 40 个名额被 brightness/connectivity/volume 族占满，
        //   模型看到清单后老实回答「我这边没有查询系统时间的工具」。
        //   模型没撒谎，是我们没把工具给它。
        //
        //   为什么会被挤掉：time.now 的 verbs 被误写成"设置/打开"（复制粘贴错误），
        //   导致 QUERY_INFO 语素对齐拿不到分；而句子里的"看"又给一堆 UI 能力加了分。
        //   但 time.now 明明登记了最精确的口语模板 say("现在几点","几点了","当前时间"…)，
        //   **这一路此前完全不参与打分**。
        //
        //   修法：把 say 模板当作**精确实体信号**给高权重。
        //   要求词长 ≥3 字（"几点""时间"这类泛词会给一片能力加分，反而制造噪音），
        //   且**命中即 40 分**——足以压过 verbs/摘要蹭分，把该能力稳定送进候选。
        if (cap.sayTemplates != null) {
            for (String t : cap.sayTemplates) {
                if (t == null) continue;
                String tt = t.toLowerCase();
                if (tt.length() >= 3 && q.contains(tt)) {
                    score += 40;
                    break;
                }
            }
        }

        return score;
    }

    /**
     * 判断一个能力 verbs 词条是否与句子的动作类别**语义对齐**。
     *
     * <p>这是"语素 → 能力"的桥：句子用的是口语语素（装/弄/搞/搜/找），
     * 能力注册用的是规范动词（安装/下载/搜索），两者靠**类别**而非字面对上。
     *
     * <p>注意这里**不是**在扩词表：{@code v.contains("装")} 会同时覆盖
     * "安装/卸载/下载安装"以及未来任何含"装"的规范动词；而"装上/装个"这些
     * **口语搭配**根本不在这里出现 —— 它们已被 ActionLexicon 归一成 INSTALL 类别了。
     */
    private static boolean verbMatchesIntent(String verb, ActionLexicon.Intent intent) {
        if (verb == null) return false;
        switch (intent) {
            case INSTALL:
                return verb.contains("装") || verb.contains("下载") || verb.contains("卸");
            case SEARCH:
                return verb.contains("搜") || verb.contains("查") || verb.contains("找");
            case LAUNCH:
                return verb.contains("打开") || verb.contains("启动") || verb.contains("进");
            case CREATE:
                // CREATE 的外延不只是"写笔记"，还包括
                //   **在本机按期生成一条事件**（定闹钟 / 叫醒 / 提醒）。
                //   事故：「40分钟后叫醒我」即使被判成 CREATE，clock.alarm.set 的
                //   verbs（设置/定/叫）在这条桥上一个都不匹配 → 拿不到 +25 →
                //   被摘要蹭分挤掉，模型依然看不到闹钟接口。桥接必须与语素表同源。
                return verb.contains("新建") || verb.contains("创建") || verb.contains("记")
                        || verb.contains("写") || verb.contains("保存")
                        || verb.contains("定") || verb.contains("叫") || verb.contains("提醒");
            case CONFIGURE:
                return verb.contains("设") || verb.contains("调") || verb.contains("切换")
                        || verb.contains("改");
            case QUERY_INFO:
                return verb.contains("查") || verb.contains("看") || verb.contains("获取");
            default:
                return false;
        }
    }
}
