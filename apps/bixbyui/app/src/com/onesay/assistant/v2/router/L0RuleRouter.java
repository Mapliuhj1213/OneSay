package com.onesay.assistant.v2.router;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.IntentExecutor;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * L0 离线规则路由器（毫秒级本地分发，零网络依赖）。
 *
 * 核心特性：
 * 1. 自然语言规范化：标点抹除、同音容错与口语前缀剥离（reduceSpoken）；
 * 2. 槽位提取：阿拉伯/中文数字自动解析与应用名提取；
 * 3. 贪婪模板防抢占（BUG-003）：针对 app.launch，必须能真实解析出本地应用才命中；
 * 4. 择优路由：最长字面量优先，确保高优先级专有动作优先于宽泛模板。
 */
public class L0RuleRouter implements IRouter, IOfflineProbe {

    private static final String TAG = "L0RuleRouter";

    /**
     * ★ 缺陷修复（2026-10-02）：模板编译规格缓存。
     *
     * 背景：Vosk 每秒推 5~10 次 partial，每次都要遍历全部能力的全部说法模板
     * （200+ 条），原先每条都执行一次 String.replaceAll 归一化 + Pattern.compile。
     * 这在中低端机上表现为高频 art concurrent mark sweep GC 与转写卡顿。
     * 模板集合是编译期常量、规模有限，因此做进程级一次性缓存即可。
     */
    private static final java.util.concurrent.ConcurrentHashMap<String, Pattern> REGEX_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, Pattern>();
    private static final java.util.concurrent.ConcurrentHashMap<String, String> NORM_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, String>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Integer> LITLEN_CACHE =
            new java.util.concurrent.ConcurrentHashMap<String, Integer>();
    private static final int CACHE_MAX = 2048;

    private static String cachedNormalize(String raw) {
        String v = NORM_CACHE.get(raw);
        if (v != null) return v;
        v = normalize(raw);
        if (NORM_CACHE.size() >= CACHE_MAX) NORM_CACHE.clear();
        NORM_CACHE.put(raw, v);
        return v;
    }

    private static Pattern cachedPattern(String regex) {
        Pattern p = REGEX_CACHE.get(regex);
        if (p != null) return p;
        p = Pattern.compile(regex);
        if (REGEX_CACHE.size() >= CACHE_MAX) REGEX_CACHE.clear();
        REGEX_CACHE.put(regex, p);
        return p;
    }

    private static int cachedLiteralLength(String tNorm) {
        Integer v = LITLEN_CACHE.get(tNorm);
        if (v != null) return v.intValue();
        int len = computeLiteralLength(tNorm);
        if (LITLEN_CACHE.size() >= CACHE_MAX) LITLEN_CACHE.clear();
        LITLEN_CACHE.put(tNorm, Integer.valueOf(len));
        return len;
    }

    private final Context mContext;
    private final IntentExecutor mIntentExecutor;
    private final CapabilityRegistry mRegistry;

    public L0RuleRouter(Context context) {
        this.mContext = context.getApplicationContext();
        this.mIntentExecutor = new IntentExecutor(context);
        this.mRegistry = CapabilityRegistry.getInstance();
    }

    /**
     * （真机报告 M-4）：JVM 单测用 —— 指定能力表、不依赖 Context。
     * 没有 IntentExecutor 时 {@code app.launch} 的 {app} 槽位一律解析失败（不抢占），其余模板行为与线上一致。
     */
    public static L0RuleRouter forRegistry(CapabilityRegistry registry) {
        return new L0RuleRouter(registry);
    }

    private L0RuleRouter(CapabilityRegistry registry) {
        this.mContext = null;
        this.mIntentExecutor = null;
        this.mRegistry = registry;
    }

    private static class MatchCandidate implements Comparable<MatchCandidate> {
        final Capability capability;
        final JSONObject args;
        final int literalScore;

        MatchCandidate(Capability cap, JSONObject args, int score) {
            this.capability = cap;
            this.args = args;
            this.literalScore = score;
        }

        @Override
        public int compareTo(MatchCandidate other) {
            // 1. 字面量越长越具体，优先级越高
            int byScore = Integer.valueOf(other.literalScore).compareTo(this.literalScore);
            if (byScore != 0) return byScore;

            // 字面量打平时，**专用能力优先于 app.launch**。
            //
            //   问题实录：`打开浏览器` 同时命中两条，且字面量分数**都是 5**：
            //     - browser.open.default 的 say「打开浏览器」→ 字面量 5
            //     - app.launch 的 say「打开{app}」       → 字面量 2 + 槽位 3 = 5
            //   打平后排序不稳定，实测被 app.launch 抢走 →
            //   用户拿到的是"靠品类别名猜一个包去点图标"，而不是
            //   "读默认浏览器 + 用它的接口打开界面"（专用能力的正解）。
            //
            //   app.launch 是**最宽泛的贪婪模板**（几乎能吃下任何"打开X"），
            //   仅当没有任何更具体的同名能力命中时才该由它兜底。
            //   所以平手时让它排到最后 —— 这是通用规则，不是给浏览器开小灶。
            boolean thisIsLaunch = "app.launch".equals(capability.sign);
            boolean otherIsLaunch = "app.launch".equals(other.capability.sign);
            if (thisIsLaunch != otherIsLaunch) {
                return thisIsLaunch ? 1 : -1;
            }
            return 0;
        }
    }

    @Override
    public Invocation route(String query) {
        if (query == null || query.trim().length() == 0) return null;

        String raw = query.trim();
        // [kernel] 插件 L0 直达规则（顺序 = L0 优先级）：存图「保存图片 / 存这张图 / 全部保存…」→ image.save（bixby_imgsave），
        //   摘要「总结一下 / 这篇讲了什么 / 太长不看…」→ screen.summary（bixby_summary）；都不注册进能力表
        com.onesay.assistant.v2.kernel.FeatureKernel.Hit direct = com.onesay.assistant.v2.kernel.FeatureKernel.get().l0(mContext, raw);
        if (direct != null) {
            String line = direct.plugin.l0Log(direct.inv);
            if (line != null) Log.i(TAG, line);
            return direct.inv;
        }
        List<String> variants = generateSpokenVariants(raw);

        List<MatchCandidate> candidates = new ArrayList<MatchCandidate>();

        for (String v : variants) {
            for (Capability cap : mRegistry.listCapabilities()) {
                for (String sayTemplate : cap.sayTemplates) {
                    MatchCandidate cand = matchTemplate(cap, sayTemplate, v);
                    if (cand != null) {
                        candidates.add(cand);
                    }
                }
            }
        }

        if (candidates.isEmpty()) {
            return null;
        }

        Collections.sort(candidates);
        MatchCandidate best = candidates.get(0);

        Log.i(TAG, "L0 命中能力: " + best.capability.sign + " (字面量权重=" + best.literalScore + ", 参数=" + best.args + ")");
        return new Invocation(best.capability.sign, best.args, 1.0, Invocation.RouteSource.L0_RULE, query);
    }

    /** 生成口语剥离变体列表（剥离前缀语气词、中置助词、句末语气词与近音纠错） */
    public static List<String> generateSpokenVariants(String input) {
        List<String> list = new ArrayList<String>();
        String norm = normalize(input);
        if (norm.length() == 0) return list;

        list.add(norm);

        // 移除文本层同音纠错（用户定档）。
        //   原先这里会把「发开」「烂牙」「歪发」这类**声学错读**按一张枚举表替换成规范词。
        //   问题在于：这张表是**治标**——vosk 对同一个词的错法近乎无穷，枚举永远追不上；
        //   而且它污染了溯源（用户看到的字幕不是 ASR 原始输出）。
        //   现在的做法是让 ASR 自己识别对：识别时带上**热词上下文**
        //   （见 {@code com.onesay.assistant.v2.asr.AsrHotwords}），把先验交给声学模型，
        //   文本层从此原样透传，不再做任何替换。

        // 1. 剥离常见前缀连接词（"请帮我"、"给我"、"麻烦把"、"帮我把"、"把"、"请"、"快点"）
        String[] prefixes = {
                // 问句式请求「能不能把亮度调高」「可以大声一点吗」也剥掉
                "能不能帮我把", "能不能帮我", "能不能把", "能不能", "可不可以把", "可不可以", "可以帮我把", "可以把", "可以", "能帮我把", "能帮我",
                "再帮我把", "再把", "再",
                "请帮我把", "请帮我", "麻烦帮我把", "麻烦帮我", "麻烦把", "帮我把", "帮我",
                "给我把", "给我", "请把", "麻烦", "请", "把", "去把", "去", "快把", "快点把", "快点", "赶紧把", "赶紧"
        };
        for (String p : prefixes) {
            if (norm.startsWith(p) && norm.length() > p.length()) {
                String sub = norm.substring(p.length());
                if (!list.contains(sub)) list.add(sub);
            }
        }

        // 2. 剥离中置口语助词（例如 "开一下wifi" -> "开wifi", "打开一下无线" -> "打开无线"）
        List<String> current = new ArrayList<String>(list);
        for (String item : current) {
            if (item.contains("一下")) {
                String stripped = item.replace("一下", "");
                if (stripped.length() >= 2 && !list.contains(stripped)) list.add(stripped);
            }
            if (item.contains("一个")) {
                String stripped = item.replace("一个", "");
                if (stripped.length() >= 2 && !list.contains(stripped)) list.add(stripped);
            }
        }

        // 2b. 剥离"单独量词「个」"与"结构助词「的」"（补）
        //   例：「设个七点的闹钟」→「设七点闹钟」、「定一个七点的闹钟」→「定七点闹钟」。
        //   原先只剥"一个"，遇到"个"单独出现（设个/定个）或带"的"（七点的）就失配，
        //   落到 LLM 还被答成"我设法设闹钟"。这是对所有能力通用的口语覆盖补强，零副作用。
        List<String> current2 = new ArrayList<String>(list);
        for (String item : current2) {
            if (item.contains("个")) {
                String stripped = item.replace("个", "");
                if (stripped.length() >= 2 && !list.contains(stripped)) list.add(stripped);
            }
            if (item.contains("的")) {
                String stripped = item.replace("的", "");
                if (stripped.length() >= 2 && !list.contains(stripped)) list.add(stripped);
            }
        }

        // 3. 剥离常见句末语气词（"一下", "下", "好了", "吧", "啊", "啦", "呀", "呢"）
        String[] suffixes = { "一下", "一个", "下", "好了", "吧", "啊", "啦", "呀", "呢", "吗", "行吗", "好吗", "可以吗" };
        List<String> snapshot = new ArrayList<String>(list);
        for (String item : snapshot) {
            for (String s : suffixes) {
                if (item.endsWith(s) && item.length() > s.length() + 1) {
                    String sub = item.substring(0, item.length() - s.length());
                    if (!list.contains(sub)) list.add(sub);
                }
            }
        }

        return list;
    }

    private MatchCandidate matchTemplate(Capability cap, String template, String utterance) {
        String tNorm = cachedNormalize(template);
        if (tNorm.length() == 0) return null;

        // 1. 无槽位精确字面匹配
        if (!tNorm.contains("{")) {
            if (tNorm.equals(utterance)) {
                return new MatchCandidate(cap, new JSONObject(), tNorm.length());
            }
            return null;
        }

        // 2. 槽位正则捕获匹配（Pattern 走缓存，杜绝高频重复编译）
        Matcher matcher = cachedPattern(buildRegex(tNorm)).matcher(utterance);

        if (!matcher.matches()) {
            return null;
        }

        JSONObject args = new JSONObject();
        int literalLen = cachedLiteralLength(tNorm);

        int slotLen = 0;
        // 提取槽位内容
        if (tNorm.contains("{app}")) {
            String app = matcher.group("app");
            if (app == null || app.trim().length() == 0) return null;

            // ★ BUG-003 防贪婪抢占：必须能解析出本地真实应用或品类别名才算命中！
            if ("app.launch".equals(cap.sign)) {
                String component = mIntentExecutor != null ? mIntentExecutor.resolveComponent(app.trim()) : null;
                if (component == null) {
                    return null; // 无法解析出应用，不准抢占！
                }
                // ★ 槽位残余检测（2026-10-03）—— 用户实测「打开浏览器搜索必应」只打开了浏览器。
                //
                //   根因：`打开{app}` 是贪婪模板，把整串吞成 app="浏览器搜索必应"；
                //   而 resolveComponent 走「品类别名」分支时只要 key.contains("浏览器") 就算成功，
                //   于是判成 AGENT 单步直接执行 —— 后半句「搜索必应」从来没进过 LLM。
                //
                //   判据：槽位里如果还**残留着别的动作词**（搜索/截图/拍照/播放…），
                //   且该动作词后面还跟着实际内容（说明它是另一件事，不是句尾的语气补充），
                //   就说明这句话被吞了，立刻放弃抢占，让分类器判 MIXED 交给 LLM 拆步骤。
                //
                //   为什么要"后面还有内容"这个附加条件：
                //   「打开相机拍照」里的"拍照"是对"相机"的补充说明，不是第二个动作，
                //   放行它继续由 L0 处理（快且准）；而「打开浏览器搜索必应」的"搜索"后面
                //   明确还有一个宾语"必应"，是实打实的第二件事，必须交出去。
                String residual = findResidualAction(app.trim());
                if (residual != null) {
                    Log.i(TAG, "app.launch 槽位残留第二动作，放弃抢占交 LLM: app=\""
                            + app.trim() + "\" 残留动作=" + residual);
                    return null;
                }
                slotLen += app.trim().length();
            }
            try { args.put("app", app.trim()); } catch (Exception ignored) {}
        }

        if (tNorm.contains("{n}")) {
            String nStr = matcher.group("n");
            // **文本型** {n} 槽位必须先于数字解析。
            //
            //   实测踩过：「新建笔记记录明天下午三点开会」→ notes.new.text，但 content 为空。
            //   根因：NumberParser.parse("明天下午三点开会") 会因为句中有汉字"三"而**返回非 null**
            //   （它逐个字符扫，见到中国数字就累加，完全不管前后还有没有别的字）。
            //   于是流程进了"数字分支"，而 notes.new.text 没有 level/seconds/hour 参数，
            //   结果**什么都没回填**，`content` 丢失 → 打开一条空白笔记。
            //   对比：「新建笔记记录开会」中无汉字数字 → parse 返回 null → 正确走文本分支填 content。
            //
            //   判据：只要本能力的 {n} 对应**字符串参数**（content/keyword/number 等），
            //   就永远按原文回填，绝不尝试数字解析。
            //   —— 数字型能力（闹钟/倒计时/亮度/音量）的 {n} 对应 level/seconds/hour，不受影响。
            boolean textSlot = cap.paramsSchema.getRules().containsKey("content")
                    || cap.paramsSchema.getRules().containsKey("keyword")
                    || cap.paramsSchema.getRules().containsKey("number");
            // **文本槽位清洗**。
            //
            //   实测踩过：「帮我下载个抖音」→ store.search.market，keyword="个抖音"。
            //   根因：`{n}` 贪婪捕获了"个抖音"，量词"个"没被剥掉就进了槽位。
            //   旧的绕法是往"剥离变体"里加一条"个"的剥离规则（单例修补，治标）；
            //   现在改用**语法定位**：从动作语素右边界取宾语（{@link ActionLexicon#objectOf}），
            //   量词/助词由语法结构决定要剥，任何"下载个X/装个X/搜个X"都自动干净。
            String nClean = nStr;
            if (textSlot) {
                String obj = ActionLexicon.objectOf(nStr);
                if (obj != null && obj.length() > 0 && obj.length() <= nStr.length()) {
                    nClean = obj;
                }
            }
            Long val = textSlot ? null : NumberParser.parse(stripSlotQuantifier(nStr));
            if (val == null) {
                // 文本型回填（闹钟/时间/关键词/便签正文）
                if (cap.paramsSchema.getRules().containsKey("content")) {
                    try { args.put("content", nClean); } catch (Exception ignored) {}
                } else if (cap.sign.startsWith("browser.search")
                        || cap.paramsSchema.getRules().containsKey("keyword")) {
                    try { args.put("keyword", nClean); } catch (Exception ignored) {}
                } else if ("phone.call".equals(cap.sign)) {
                    try { args.put("number", nClean); } catch (Exception ignored) {}
                } else if (textSlot) {
                    // 兜底：文本槽位但参数名不在上面三类，按占位符本身的名字回填
                    // （例如 store.search.* 用 keyword 已覆盖；此处防将来新增自定义文本槽）
                    return null;
                } else {
                    return null;
                }
            } else {
                if (cap.paramsSchema.getRules().containsKey("level")) {
                    // 「调亮度到50」走这里（FastIntents 只认「亮度调到N」），50 被当成 50/255 = 20%。
                    //   亮度没有用户认识的 0–255 刻度：说的数一律按百分比（与 FastIntents.BRI_SET 同口径）
                    if ("settings.brightness.set".equals(cap.sign)) val = brightnessPercentToRaw(val);
                    try { args.put("level", val); } catch (Exception ignored) {}
                } else if (cap.paramsSchema.getRules().containsKey("seconds")) {
                    try { args.put("seconds", val); } catch (Exception ignored) {}
                } else if (cap.paramsSchema.getRules().containsKey("hour")) {
                    try { args.put("hour", val); } catch (Exception ignored) {}
                }
            }
        }

        if (tNorm.contains("{m}")) {
            String mStr = matcher.group("m");
            Long mVal = NumberParser.parse(stripSlotQuantifier(mStr));
            try { args.put("minute", mVal != null ? mVal : 0L); } catch (Exception ignored) {}
        } else if (cap.paramsSchema.getRules().containsKey("minute") && !args.has("minute")) {
            try { args.put("minute", 0L); } catch (Exception ignored) {}
        }

        if (tNorm.contains("{url}")) {
            String url = matcher.group("url");
            try { args.put("url", url); } catch (Exception ignored) {}
        }

        if (tNorm.contains("{content}")) {
            String content = matcher.group("content");
            try { args.put("content", content); } catch (Exception ignored) {}
        }

        return new MatchCandidate(cap, args, literalLen + slotLen);
    }

    /**
     * 槽位里可能出现的「第二个动作」词表。
     *
     * <p>只收"做一件事"的实义动词，不收"把/给/去/帮我"这类助动词 ——
     * 助动词到处都是，收了会把「帮我把浏览器打开」也误判成复合句。
     */
    private static final String[] RESIDUAL_ACTIONS = {
            "搜索", "搜一下", "搜一搜", "查一下", "查询", "检索",
            "截图", "截屏", "截个图", "录屏", "录制",
            "拍照", "照相", "拍一张", "拍个照",
            "播放", "放一首", "暂停", "停止播放",
            "发送", "发出", "发消息", "发短信", "打电话", "拨打",
            "记一下", "记录", "写下", "导航", "翻译",
            "关闭", "关掉", "打开", "开启", "设为", "调到",
            // **引擎/参数切换**类动词。
            //   实测踩过：「打开浏览器把引擎换成必应」被 app.launch 贪婪吞成
            //   app="浏览器把引擎换成必应"——旧表里没有"换成/切换/改用"，
            //   残余检测看不见，于是整句被当成"打开一个叫『浏览器把引擎换成必应』的应用"。
            //   这与「打开浏览器搜索必应」是同一类错误（动宾并列被吞），只是换了个动词。
            "换成", "换为", "切到", "切换", "改用", "换用", "改为", "改成",
            // **应用商店下载**类动词。
            //   实测踩过：「打开应用商店下载微信」被吞成 app="应用商店下载微信"，
            //   然后 app.launch 靠子串模糊匹配到了三星商店（"应用商店"），
            //   报"已为您打开 应用商店下载微信"——**假成功**，用户真正的诉求"下载微信"被丢了。
            //   收进来后残余检测放行，交给上层 LLM 拆成 [打开商店 → 搜索/下载 微信]。
            "下载", "下载安装", "安装", "更新", "装一个"
    };

    /** 残余动作词后面至少要有这么多字符，才算"这是另一件事"而不是句尾补充 */
    private static final int RESIDUAL_MIN_TAIL = 2;

    /**
     * 检测 app 槽位里是否残留第二个动作。
     *
     * @return 命中的残余动作词；没有残留返回 null
     */
    private static String findResidualAction(String slot) {
        if (slot == null || slot.length() < 3) return null;
        for (String act : RESIDUAL_ACTIONS) {
            int idx = slot.indexOf(act);
            if (idx < 0) continue;
            int tailStart = idx + act.length();
            // 动作词必须在槽位内部（不是整个槽位本身就是这个动作词）
            if (tailStart >= slot.length()) continue;
            // 后面还得跟着实际内容，否则只是句尾补充（"打开相机拍照"）
            if (slot.length() - tailStart < RESIDUAL_MIN_TAIL) continue;
            return act;
        }
        return null;
    }

    /**
     * 供自测断言：模板 → 正则。
     *
     * <p>断言目标：**含未识别槽位 `{xxx}` 的模板也必须能编译**（被转义成字面量），
     * 绝不能抛 {@code PatternSyntaxException} —— 那个异常会炸掉整条 L0 路由。
     */
    public static String debugBuildRegex(String template) {
        return buildRegex(template);
    }

    private static String buildRegex(String template) {        String r = template;
        r = r.replace("{app}", "(?<app>.+?)");
        r = r.replace("{n}", "(?<n>.+?)");
        r = r.replace("{m}", "(?<m>.+?)");
        r = r.replace("{url}", "(?<url>.+?)");
        r = r.replace("{content}", "(?<content>.+?)");

        // ★修复（这是一个**会炸掉整条路由**的隐患，务必保留此防护）：
        //
        //   实测事故：新加一个邮件能力时，say 模板里写了 `写邮件草稿{subject}` ——
        //   而 L0 的槽位名只有 app/n/m/url/content 五个。`{subject}` 没被替换，
        //   直接进了 Pattern.compile → `Error in {min,max} interval near index 8`
        //   → 抛 PatternSyntaxException。
        //
        //   致命之处在于**爆炸范围**：matchTemplate 是在"遍历所有能力 × 所有 say 模板"
        //   的循环里被调用的，异常会一路冒到 route() 外面 ——
        //   于是**任何一个句子**（哪怕和邮件毫无关系）都路由失败。
        //   一条模板的手误 = 全局瘫痪。
        //
        //   所以这里做兜底：把**所有未被识别的 `{...}` 转义成字面量**，
        //   让它只是"这条模板不匹配"，而不是"整个路由挂掉"。
        // (转义的是花括号本身：`{subject}` 变成 `\{subject\}`，正则里表示字面量。)
        StringBuilder sb = new StringBuilder();
        boolean warned = false;
        for (int i = 0; i < r.length(); i++) {
            char c = r.charAt(i);
            if (c == '{' || c == '}') {
                sb.append('\\');
                warned = true;
            }
            sb.append(c);
        }
        if (warned) {
            Log.w(TAG, "[L0-TEMPLATE] 模板含未识别的槽位占位符（按字面量处理，不会匹配到用户口语）："
                    + template + "  仅支持 {app}/{n}/{m}/{url}/{content}");
        }
        return "^" + sb.toString() + "$";
    }

    private static int computeLiteralLength(String template) {
        return template.replaceAll("\\{.*?\\}", "").length();
    }

    public static String normalizeUtterance(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s\\p{Punct}，。？！、：；“”‘’\"'`]", "").toLowerCase();
    }

    public static String normalizeTemplate(String s) {
        if (s == null) return "";
        // 关键防护（汲取 v1 审计经验）：保留 { } 占位符结构，只剥离空白与中文标点
        return s.replaceAll("[\\s，。？！、：；“”‘’\"'`]", "").toLowerCase();
    }

    public static String normalize(String s) {
        if (s == null) return "";
        if (s.indexOf('{') >= 0) return normalizeTemplate(s);
        return normalizeUtterance(s);
    }

    /**
     * 在 Vosk 的 N-best 多候选列表中，通过能力模板字面量与领域词先验表挑选最优候选。
     * 仅当严格优于 top-1 时才返回更优候选，平手或更差保持原样（返回 null）。
     */
    public String pickBestAlternative(List<String> alts, int minLiteral) {
        if (alts == null || alts.size() < 2) return null;
        String top1Raw = cleanAlternative(alts.get(0));
        if (top1Raw.length() < 2) return null;

        String best = null;
        int bestScore = Integer.MIN_VALUE;
        int top1Score = Integer.MIN_VALUE;

        for (int i = 0; i < alts.size(); i++) {
            String c = cleanAlternative(alts.get(i));
            int score = Integer.MIN_VALUE;
            if (c.length() >= 2) {
                Invocation inv = route(c);
                int lit = 0;
                if (inv != null) {
                    lit = getQueryLiteralLength(c);
                }
                score = 0;
                if (lit >= minLiteral) {
                    score += lit * 1000; // 命中够具体的说法
                }
                // 领域词先验打分（区分标准词与同音错字，例如“无线”vs“无限”）
                score += CommandVocab.canonicalHits(c) * 100;
                score -= i; // 平局时偏向 top-1
            }
            if (i == 0) top1Score = score;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        if (best == null) return null;
        if (bestScore < 100) return null;       // 全都不像指令，不改动
        if (bestScore <= top1Score) return null; // 没有严格更好，不改动
        return best;
    }

    private static String cleanAlternative(String alt) {
        if (alt == null) return "";
        String c = normalizeUtterance(alt);
        if (c.startsWith("我在")) c = c.substring(2);
        else if (c.startsWith("在的")) c = c.substring(2);
        else if (c.startsWith("在")) c = c.substring(1);
        return c;
    }

    /**
     * 数字槽的「量词残余」清洗（实测定稿）。
     *
     * <p>实测踩过：「设一个 7 点的闹钟」被设成 **17 点**。链路：口语变体未把
     * 「一个」剥净时，非贪婪槽位 {@code (?<n>.+?)} 捕获到 {@code "一个7"}，
     * NumberParser 把「一」和「7」当连续数字拼出 17。
     *
     * <p>语法上「一个 X / 一 X」中的「一」是**量词**（数词本体是 X），应当剥掉。
     * 两个分支各有防误伤设计：
     * <ul>
     *   <li>{@code "一个7"} → 剥「一个」（后随任意数字字符即视为量词）；
     *   <li>{@code "一7"} → 剥「一」—— 但**仅当后随 ASCII 数字**：
     *       「一七」（中文）保留，那是"17"的逐字直读写法，不能剥；
     *       "一+阿拉伯数字"只会来自量词「一个」丢「个」的变体，剥之安全。
     * </ul>
     * <p>「一点叫我」的「一」后面是「点」不是数字 ⇒ 不动（真数字 1 点）。
     */
    /** 说的数按百分比 → 0–255 原值（最少 1；超过 100 按 100）。 */
    static long brightnessPercentToRaw(long pct) {
        long p = Math.max(0, Math.min(100, pct));
        return Math.max(1, Math.round(p * 255.0 / 100.0));
    }

    static String stripSlotQuantifier(String nStr) {
        if (nStr == null || nStr.length() < 2) return nStr;
        String s = nStr;
        if (s.startsWith("一个") && s.length() > 2 && isDigitLike(s.charAt(2))) {
            s = s.substring(2);
        } else if (s.charAt(0) == '一' && s.length() >= 2 && isAsciiDigit(s.charAt(1))) {
            s = s.substring(1);
        }
        return s;
    }

    /** 任一数字字符：ASCII 0-9 或中文数字（含"两"）。 */
    private static boolean isDigitLike(char c) {
        return isAsciiDigit(c) || "〇零一二三四五六七八九两".indexOf(c) >= 0;
    }

    private static boolean isAsciiDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * 计算该 Query 在所有匹配规则中最长的字面量长度
     */
    public int getQueryLiteralLength(String query) {
        if (query == null) return 0;
        List<String> variants = generateSpokenVariants(query.trim());
        int maxLit = 0;
        for (String v : variants) {
            for (Capability cap : mRegistry.listCapabilities()) {
                for (String sayTemplate : cap.sayTemplates) {
                    MatchCandidate cand = matchTemplate(cap, sayTemplate, v);
                    if (cand != null && cand.literalScore > maxLit) {
                        maxLit = cand.literalScore;
                    }
                }
            }
        }
        return maxLit;
    }

    // ---------- IOfflineProbe：供唤醒链路（VAD / 一句式切分）做热路径探针 ----------

    @Override
    public boolean canHandle(String utterance) {
        return route(utterance) != null;
    }

    @Override
    public int literalLength(String utterance) {
        return getQueryLiteralLength(utterance);
    }
}
