/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core.scenes.shot;

import com.onesay.assistant.plug.api.PlugLog;
import com.onesay.assistant.plug.suggest.core.HostState;
import com.onesay.assistant.plug.suggest.core.Outcome;
import com.onesay.assistant.plug.suggest.core.RatePolicy;
import com.onesay.assistant.plug.suggest.core.SAction;
import com.onesay.assistant.plug.suggest.core.Scene;
import com.onesay.assistant.plug.suggest.core.SceneContext;
import com.onesay.assistant.plug.suggest.core.Signal;
import com.onesay.assistant.plug.suggest.core.SuggestDefaults;
import com.onesay.assistant.plug.suggest.core.Suggestion;

import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * 场景 1：截图 →「要不要分析这张截图？」（§6）。Judge → BurstMerger → Suggestion（分析 / 忽略 / 不再提示，6 s，priority 1）。
 * 不点就不上传；点「分析」由宿主 ScreenshotAnalyzer 处理。
 */
public final class ScreenshotScene implements Scene {
    public static final String ID = "screenshot";
    public static final String ACT_ANALYZE = "analyze";
    public static final String TITLE_ONE = "要不要分析这张截图？";
    public static final String TITLE_MANY = "要不要分析这些截图？";
    /**
     * 每天 ≤15 次；用户主动行为 → 夜间 / 克制档 / 全局配额不拦。不设场景间隔（原 20 s：卡 6 s 收起后 8 s 再截一张被 SKIP(interval)，
     * 真机 00:07:34 / 00:07:42 两次截图正是这个间隔）——连拍由 BurstMerger 合并进在屏的卡、同文件由去重拦。
     */
    public static final RatePolicy POLICY = new RatePolicy(0L, 15, SuggestDefaults.DEDUP_WINDOW_MS, true, true);

    private final OwnWrites mOwn;
    private final PlugLog mLog;
    private final BurstMerger mMerger = new BurstMerger();
    private final LinkedHashMap<String, Long> mRecent = new LinkedHashMap<String, Long>();
    private volatile long mStartWall;

    public ScreenshotScene(OwnWrites own, PlugLog log) {
        mOwn = own == null ? new OwnWrites() : own;
        mLog = log == null ? PlugLog.NONE : log;
    }

    /** 插件 start 时调（新鲜度规则：mtime ≥ 插件启动）。 */
    public void setStartWall(long wall) {
        mStartWall = wall;
    }

    public OwnWrites ownWrites() {
        return mOwn;
    }

    @Override public String id() { return ID; }
    @Override public String title() { return "截图"; }
    @Override public int privacyClass() { return PRIVACY_LOCAL; }
    @Override public boolean defaultOn() { return SuggestDefaults.defaultSceneOn(privacyClass(), false); }
    @Override public boolean accepts(String t) { return Signal.SCREENSHOT.equals(t); }

    @Override public Suggestion evaluate(Signal s, SceneContext ctx) {
        ScreenshotCandidate c = ScreenshotCandidate.from(s);
        HostState h = ctx.host();
        long nowWall = ctx.clock().wall(), nowEl = ctx.clock().elapsed();
        ScreenshotJudge.JudgeEnv env = new ScreenshotJudge.JudgeEnv();
        env.screenW = h == null ? 0 : h.screenWidthPx();
        env.screenH = h == null ? 0 : h.screenHeightPx();
        env.pluginStartWall = mStartWall;
        env.nowWall = nowWall;
        env.nowElapsed = nowEl;
        env.recentKeys = recentKeys(nowWall);
        env.own = mOwn;
        String fg = s.get(ScreenshotCandidate.K_FG);
        if (fg == null && h != null) fg = h.foregroundPkg();
        env.fgPkg = fg;
        env.privatePkgs = ctx.config().privatePkgs();
        env.locked = h != null && h.keyguardLocked();
        ScreenshotJudge.Verdict v = ScreenshotJudge.judge(c, env);
        String dims = c.width + "x" + c.height + " screen=" + env.screenW + "x" + env.screenH;
        if (!v.show) {
            mLog.i("suggest/screenshot: SKIP(" + v.reason + ") src=" + c.source + " size=" + dims);
            return null;
        }
        mLog.i("suggest/screenshot: CAND src=" + c.source + " size=" + dims + (v.reason != null ? " " + v.reason : ""));
        synchronized (mRecent) {
            mRecent.put(v.dedupKey, nowWall);
        }
        BurstMerger.MergeResult m = mMerger.add(c.path, c.seenAtElapsed > 0 ? c.seenAtElapsed : nowEl);
        String label = fg == null ? null : s.get(ScreenshotCandidate.K_FG_LABEL);
        String sub1 = label == null || label.length() == 0 ? "刚刚" : label + " · 刚刚";
        String sub = m.count > 1 ? m.count + " 张截图" : sub1;
        StringBuilder paths = new StringBuilder();
        for (String p : m.paths) paths.append(paths.length() > 0 ? "\n" : "").append(p);
        String latest = m.latest() == null ? c.path : m.latest();
        return Suggestion.builder(ID).title(m.count > 1 ? TITLE_MANY : TITLE_ONE).subtitle(sub).icon("screenshot").thumb(latest)
                .action(SAction.run(ACT_ANALYZE, "分析")).action(SAction.dismiss()).action(SAction.mute())
                .ttl(SuggestDefaults.CARD_TTL_MS).priority(1).dedup(v.dedupKey).group("burst:" + m.burstId).watch(latest)
                .put("path", latest).put("paths", paths.toString()).put("count", String.valueOf(m.count))
                .put("src", String.valueOf(c.source)).put("sub1", sub1).build();
    }

    @Override public void onOutcome(Suggestion s, Outcome o, SceneContext ctx) {
    }

    private Set<String> recentKeys(long now) {
        synchronized (mRecent) {
            Iterator<Map.Entry<String, Long>> it = mRecent.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<String, Long> e = it.next();
                if (now - e.getValue() > SuggestDefaults.DEDUP_WINDOW_MS) it.remove();
            }
            while (mRecent.size() > 200) {
                it = mRecent.entrySet().iterator();
                it.next();
                it.remove();
            }
            return new HashSet<String>(mRecent.keySet());
        }
    }
}
