package com.onesay.assistant.v2.asr;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * ASR 热词上下文（识别环节的先验，替代已被删除的文本层同音纠错表）。
 *
 * <h3>为什么纠错要放在这里，而不是识别完再改文本</h3>
 *
 * 用户实测「打开浏览器搜索<b>必应</b>」被听成「…搜索<b>毕竟</b>」。
 * 旧做法是在文本层拿一张枚举表把「毕竟」替换回「必应」—— 用户明确否掉了这条路：
 * 声学错法无穷无尽，枚举追不上；而且改完的字幕已经不是 ASR 的真实输出，没法排查。
 *
 * <p>正确的位置是**识别时**：把"这次可能出现哪些专有名词"作为上下文一起发给
 * qwen3-asr-flash，让声学模型在"bìyìng"这个音上把候选从「毕竟」调到「必应」。
 * 模型看到的是先验，输出的是它自己的判断 —— 一步到位，链路更短，也留得下原始痕迹。
 *
 * <h3>热词表的两部分</h3>
 * <ul>
 *   <li><b>静态</b>：搜索引擎、高频应用品牌、系统控制词 —— 覆盖"用户会说什么"；</li>
 *   <li><b>动态</b>：本机已安装应用名 —— 覆盖"这台手机上有什么"，
 *       用户新装一个 App 不用改代码就能被识别对。</li>
 * </ul>
 *
 * <h3>为什么动态部分取「已安装应用」而不是「当前前台应用」</h3>
 *
 * 前台应用在 Android 8 上普通应用拿不到（{@code getRunningAppProcesses} 只返回自己），
 * 要拿就得走 root shell（本工程走文件 IPC 守护，最坏情况会阻塞 1.5 秒）——
 * 在识别链路前面加这个延迟不划算。而用户真正会说出口的是**应用名**
 * （"打开微信"），跟它此刻在不在前台没关系。所以取全量应用名的收益更大、代价为零。
 *
 * <p>热词不是越多越好：官方文档明确"热词加得越多，误触发越多"。
 * 因此这里做了双重限流（{@link #MAX_APP_LABELS} 限条数、{@link #MAX_CHARS} 限总长），
 * 静态词优先保全，动态词按需填充。
 */
public final class AsrHotwords {

    private static final String TAG = "AsrHotwords";

    /**
     * 热词上下文固定开场白（全表以此开头）。
     *
     * <p>真机实测出现过云端把这段**提示词原文**
     * 当成"识别结果"回吐（送出 100ms 近空音频 → 模型拿上下文幻觉出一整段文本），
     * 上层无从分辨就挂到了面板上。所以这一段同时是构建前缀与**幻觉检测前缀** ——
     * 转写结果以它开头即判定为幻觉，直接丢弃（见 {@code DashScopeAsrChannel}）。
     * 值为 {@code public} 是为了让 ASR 通道不必重复字面量、避免两处漂移。
     */
    public static final String HOTWORD_PROMPT_PREFIX =
            "语音识别热词表，以下专有名词请优先按原词识别：";

    /** 静态热词：搜索引擎 / 高频品牌 / 系统控制词 / 高频查询对象 */
    private static final String[] STATIC_WORDS = {
            // 搜索引擎（用户点名的引擎必须能被听对，否则路由不到对应能力）
            "必应", "bing", "百度", "谷歌", "google", "搜狗", "夸克", "360搜索",
            // 高频应用品牌
            "微信", "支付宝", "淘宝", "京东", "拼多多", "抖音", "快手", "小红书",
            "哔哩哔哩", "微博", "知乎", "高德地图", "百度地图", "腾讯视频", "爱奇艺",
            "优酷", "网易云音乐", "QQ音乐", "喜马拉雅", "钉钉", "飞书", "美团",
            "饿了么", "携程", "12306", "墨迹天气", "相机", "相册", "计算器",
            "日历", "时钟", "闹钟", "备忘录", "设置", "文件管理", "应用商店",
            // 系统控制对象
            "WiFi", "蓝牙", "飞行模式", "热点", "个人热点", "移动数据", "数据流量",
            "定位", "GPS", "NFC", "亮度", "音量", "静音", "免打扰", "勿扰模式",
            "手电筒", "截屏", "截图", "录屏", "息屏", "自动旋转", "护眼模式",
            "深色模式", "省电模式", "超级省电",
            // 高频查询对象（这些词被听错，LLM 也救不回来）
            "比特币", "以太坊", "油价", "汇率", "股票", "基金", "天气", "限行",
            "航班", "高铁", "快递"
    };

    /** ★ fix/leak-guard：静态热词表副本（提示词回吐守卫按它判「中段词表回吐」）。 */
    public static String[] staticWords() {
        return STATIC_WORDS.clone();
    }

    /** 动态应用名最多收录几条 */
    private static final int MAX_APP_LABELS = 20;
    /** 整段上下文的最大字符数（热词过多会反噬识别率） */
    private static final int MAX_CHARS = 460;
    /** 缓存有效期：应用装/卸不会秒级发生，2 分钟足够 */
    private static final long CACHE_TTL_MS = 120_000L;

    private static volatile String sCache;
    private static volatile long sCacheAt;

    private AsrHotwords() {}

    /**
     * 构建本次识别要带上的热词上下文。
     *
     * @return 可直接放进 messages 的文本；没有任何热词时返回 null（调用方据此跳过）
     */
    public static String build(Context ctx) {
        long now = SystemClock.elapsedRealtime();
        String cached = sCache;
        if (cached != null && (now - sCacheAt) < CACHE_TTL_MS) {
            return cached.length() == 0 ? null : cached;
        }
        String v = compose(ctx);
        sCache = v;
        sCacheAt = now;
        return (v == null || v.length() == 0) ? null : v;
    }

    /** 应用增删后主动作废（由外部在收到 PACKAGE_* 广播时调用） */
    /**
     * ★ R14（diag C8）：<b>不阻塞调用方</b>的取法。有缓存（哪怕过期）就立刻返回缓存，过期的话在后台线程重建；
     * 只有进程里从没建过才同步建一次（服务启动时 {@link #prewarm} 已经在后台建好，正常走不到这一步）。
     * 10-15 真机：窗到点路径在<b>主线程</b>上 build() 了 331 ms（PackageManager 枚举）。
     */
    public static String get(final Context ctx) {
        String cached = sCache;
        if (cached == null) return build(ctx);
        long now = SystemClock.elapsedRealtime();
        if ((now - sCacheAt) >= CACHE_TTL_MS && sRefreshing.compareAndSet(false, true)) {
            final Context app = ctx.getApplicationContext();
            com.onesay.assistant.v2.util.Threads.start("hotwords-refresh", new Runnable() {
                @Override public void run() {
                    try {
                        String v = compose(app);
                        sCache = v;
                        sCacheAt = SystemClock.elapsedRealtime();
                    } finally {
                        sRefreshing.set(false);
                    }
                }
            });
        }
        return cached.length() == 0 ? null : cached;
    }

    /** ★ R14：服务启动时在后台线程预建（调用方负责线程）。 */
    public static void prewarm(Context ctx) {
        build(ctx);
    }

    private static final java.util.concurrent.atomic.AtomicBoolean sRefreshing = new java.util.concurrent.atomic.AtomicBoolean(false);

    public static void invalidate() {
        sCache = null;
        sCacheAt = 0L;
    }

    /**
     * ★ device-1102：上下文只给<b>词表</b>（空格分隔），不再带「语音识别热词表，以下专有名词请优先按原词识别：」这句前缀。
     * qwen3-asr-flash / -realtime 没有 vocabulary 热词参数（只支持 system 上下文 / corpus.text，见 DashScope 文档），
     * 而一句像说明文字的长前缀 + 「、」连写的长串，正是近空音频时最容易被模型当成转写原样吐出来的形态。
     * 上限 {@link #MAX_CHARS_V2} 字（比原 460 短），静态词优先。HOTWORD_PROMPT_PREFIX 常量保留，只作旧回吐的识别判据。
     */
    public static String composeText(java.util.Collection<String> words) {
        StringBuilder sb = new StringBuilder();
        if (words != null) {
            for (String w : words) {
                if (w == null || w.length() == 0) continue;
                if (sb.length() + w.length() + 1 > MAX_CHARS_V2) break;
                if (sb.length() > 0) sb.append(' ');
                sb.append(w);
            }
        }
        return sb.toString();
    }

    /**
     * ★ int/1102：静态词 + 已装应用名（同线上的筛选：去分隔符、2–10 字、去重、跳过纯 ASCII、最多 {@link #MAX_APP_LABELS} 个）。
     * 纯函数：线上 {@link #compose} 与 JVM 审计 harness 共用。
     */
    public static Set<String> wordsFor(java.util.Collection<String> appLabels) {
        Set<String> words = new LinkedHashSet<String>();
        for (String w : STATIC_WORDS) {
            if (w != null && w.length() > 0) words.add(w);
        }
        int added = 0;
        if (appLabels != null) {
            for (String raw : appLabels) {
                if (added >= MAX_APP_LABELS) break;
                if (raw == null) continue;
                // 应用名里不该有分隔符，去掉免得污染热词表
                String label = raw.trim().replaceAll("[\\s\\r\\n、,，]", "");
                if (label.length() < 2 || label.length() > 10) continue;
                if (words.contains(label)) continue;
                // 纯 ASCII 的应用名（多为英文 SDK 工具）价值低，占额度，跳过
                if (label.matches("[A-Za-z0-9.]+")) continue;
                words.add(label);
                added++;
            }
        }
        return words;
    }

    /** ★ device-1102：词表上限（字）。 */
    public static final int MAX_CHARS_V2 = 300;

    private static String compose(Context ctx) {
        List<String> labels = new java.util.ArrayList<String>();
        try {
            PackageManager pm = ctx.getPackageManager();
            List<ApplicationInfo> apps = pm.getInstalledApplications(0);
            for (ApplicationInfo ai : apps) labels.add(String.valueOf(pm.getApplicationLabel(ai)));
        } catch (Throwable t) {
            Log.w(TAG, "读取已安装应用失败（热词退化为静态表）: " + t.getMessage());
        }
        Set<String> words = wordsFor(labels);
        int added = words.size() - STATIC_WORDS.length;
        String out = composeText(words);
        // ★ fix/leak-guard：把本次真实发出去的词表（含动态应用名）交给回吐守卫，中段词表回吐也认得出
        try { com.onesay.assistant.plug.leakguard.core.PromptLeakGuard.global().addHotwordCorpus(out); } catch (Throwable ignore) {}
        Log.i(TAG, "热词上下文已构建: " + words.size() + " 词 / " + out.length() + " 字"
                + "（其中动态应用名 " + added + " 个）");
        return out;
    }
}
