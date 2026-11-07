/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.api.Clock;
import com.onesay.assistant.plug.api.PlugLog;
import com.onesay.assistant.plug.api.Storage;
import com.onesay.assistant.plug.suggest.core.scenes.CalendarScene;
import com.onesay.assistant.plug.suggest.core.scenes.ClipScene;
import com.onesay.assistant.plug.suggest.core.scenes.DepartNavScene;
import com.onesay.assistant.plug.suggest.core.scenes.HeadsetScene;
import com.onesay.assistant.plug.suggest.core.scenes.LinkScene;
import com.onesay.assistant.plug.suggest.core.scenes.LowBatteryScene;
import com.onesay.assistant.plug.suggest.core.scenes.MeetingScene;
import com.onesay.assistant.plug.suggest.core.scenes.MissedCallScene;
import com.onesay.assistant.plug.suggest.core.scenes.PackageScene;
import com.onesay.assistant.plug.suggest.core.scenes.PickupScene;
import com.onesay.assistant.plug.suggest.core.scenes.SummaryScene;
import com.onesay.assistant.plug.suggest.core.scenes.TorchScene;
import com.onesay.assistant.plug.suggest.core.scenes.TranslateScene;
import com.onesay.assistant.plug.suggest.core.scenes.TripSmsScene;
import com.onesay.assistant.plug.suggest.core.scenes.shot.OwnWrites;
import com.onesay.assistant.plug.suggest.core.scenes.shot.ScreenshotScene;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TimeZone;
import java.util.concurrent.Executor;

/**
 * 插件外壳（服务型，PLUGIN-SPEC 第 9 条）：构造零副作用；{@link #start} / {@link #stop} 可重入；线程由宿主给（executor「suggest-engine」）。
 * 出厂默认由宿主按变体决定（debug 开、release 关），core 自己的 {@link #DEFAULT_ON} = false。
 */
public final class SuggestPlug {
    public static final String ID = "suggest";
    public static final int VERSION = 1;
    public static final String TITLE = "主动建议";
    public static final String SUMMARY = "情境触发的一个小卡片：截图要不要分析、复制链接 / 单号直达、耳机 → 音乐、手电筒忘关、新装应用（对标 Flyme Aicy 建议）";
    public static final boolean DEFAULT_ON = false;
    public static final String[] DEPS = new String[0];
    public static final String[] PORTED = new String[0];

    /** 宿主装配给 start 的全部端口。 */
    public static final class Deps {
        public SceneConfig config;
        public HostState host;
        public Presenter presenter;
        public ActionRunner runner;
        public Clock clock;
        public Ports.Sched sched;
        public Executor exec;
        public Storage storage;
        public PlugLog log;
        public Ports.FileProbe files;
        public TimeZone tz;
        public List<SignalSource> sources = new ArrayList<SignalSource>();
    }

    private final OwnWrites mOwn = new OwnWrites();
    private SuggestEngine mEngine;
    private List<SignalSource> mSources = Collections.emptyList();

    public SuggestPlug() {
        // 零副作用：不起线程、不注册任何监听
    }

    /** 默认场景表（登记顺序 = 同优先级仲裁先后：链接先于剪贴板）。 */
    public static SceneRegistry defaultScenes(OwnWrites own, PlugLog log) {
        SceneRegistry r = new SceneRegistry();
        r.register(new ScreenshotScene(own, log), ScreenshotScene.POLICY);
        r.register(new LinkScene(), LinkScene.POLICY);
        r.register(new ClipScene(), ClipScene.POLICY);
        r.register(new PickupScene(), PickupScene.POLICY);
        r.register(new HeadsetScene(), HeadsetScene.POLICY);
        r.register(new TorchScene(), TorchScene.POLICY);
        r.register(new PackageScene(), PackageScene.POLICY);
        // 规则骨架：默认关，宿主未接信号 / 动作
        r.register(new TripSmsScene(), RatePolicy.DEFAULT, true);
        r.register(new CalendarScene(), RatePolicy.DEFAULT, true);
        r.register(new TranslateScene(), RatePolicy.interval(5L * 60000L), true);
        r.register(new SummaryScene(), RatePolicy.interval(5L * 60000L), true);
        r.register(new MissedCallScene(), RatePolicy.DEFAULT, true);
        r.register(new MeetingScene(), RatePolicy.DEFAULT, true);
        r.register(new DepartNavScene(), RatePolicy.DEFAULT, true);
        r.register(new LowBatteryScene(), RatePolicy.DEFAULT, true);
        return r;
    }

    public synchronized boolean running() {
        return mEngine != null;
    }

    public synchronized SuggestEngine engine() {
        return mEngine;
    }

    public OwnWrites ownWrites() {
        return mOwn;
    }

    /** 已在跑 → 原样返回 false。 */
    public synchronized boolean start(Deps d) {
        if (mEngine != null || d == null) return false;
        PlugLog log = d.log == null ? PlugLog.NONE : d.log;
        Clock clock = d.clock == null ? Clock.SYSTEM : d.clock;
        SceneRegistry reg = defaultScenes(mOwn, log);
        Scene shot = reg.scene(ScreenshotScene.ID);
        if (shot instanceof ScreenshotScene) ((ScreenshotScene) shot).setStartWall(clock.wall());
        SuggestEngine e = new SuggestEngine(reg, d.config, d.host, d.presenter, d.runner, clock, d.sched, d.exec, d.storage, log, d.files, d.tz);
        if (d.presenter != null) d.presenter.setListener(e);
        mEngine = e;
        List<SignalSource> attached = new ArrayList<SignalSource>();
        for (SignalSource s : d.sources) {
            if (s == null) continue;
            try {
                s.attach(e);
                attached.add(s);
            } catch (Throwable t) {
                log.w("suggest: source " + s.type() + " attach failed " + t.getClass().getSimpleName());
            }
        }
        mSources = attached;
        log.i("suggest: start sources=" + attached.size());
        return true;
    }

    public synchronized void stop() {
        if (mEngine == null) return;
        for (SignalSource s : mSources) {
            try {
                s.detach();
            } catch (Throwable ignored) {
            }
        }
        mSources = Collections.emptyList();
        mEngine.stop();
        mEngine = null;
    }
}
