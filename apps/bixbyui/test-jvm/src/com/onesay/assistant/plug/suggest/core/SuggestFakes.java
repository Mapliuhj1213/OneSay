package com.onesay.assistant.plug.suggest.core;

import com.onesay.assistant.plug.api.Clock;
import com.onesay.assistant.plug.api.Storage;
import com.onesay.assistant.plug.suggest.core.scenes.shot.PrivateApps;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/** suggest core 单测用的假宿主：时钟 / 调度器手动推进，展示与执行只记录。 */
public final class SuggestFakes {
    private SuggestFakes() {
    }

    /** 2026-11-02 15:00 CST（白天）。 */
    public static final long T0 = 1793602800000L;
    public static final TimeZone TZ = TimeZone.getTimeZone("Asia/Shanghai");

    public static final class FakeClock implements Clock {
        public long wall = T0, el = 1000000L;

        @Override public long elapsed() { return el; }
        @Override public long wall() { return wall; }
        @Override public void sleep(long ms) { advance(ms); }

        public void advance(long ms) {
            wall += ms;
            el += ms;
        }
    }

    /** 调度器：任务按到期时间排队，{@link #advance} 时推进时钟并执行到期任务。 */
    public static final class FakeSched implements Ports.Sched {
        final FakeClock clock;
        final List<Object[]> tasks = new ArrayList<Object[]>();   // {long due, Runnable, boolean[] cancelled}

        public FakeSched(FakeClock c) {
            clock = c;
        }

        @Override public Ports.Cancel schedule(Runnable r, long delayMs) {
            final boolean[] cancelled = {false};
            tasks.add(new Object[]{clock.el + delayMs, r, cancelled});
            return new Ports.Cancel() {
                @Override public void cancel() {
                    cancelled[0] = true;
                }
            };
        }

        public void advance(long ms) {
            long end = clock.el + ms;
            while (true) {
                Object[] next = null;
                for (Object[] t : tasks) if ((Long) t[0] <= end && (next == null || (Long) t[0] < (Long) next[0])) next = t;
                if (next == null) break;
                tasks.remove(next);
                long due = (Long) next[0];
                if (due > clock.el) clock.advance(due - clock.el);
                if (!((boolean[]) next[2])[0]) ((Runnable) next[1]).run();
            }
            if (end > clock.el) clock.advance(end - clock.el);
        }

        public int pending() {
            int n = 0;
            for (Object[] t : tasks) if (!((boolean[]) t[2])[0]) n++;
            return n;
        }
    }

    public static final class FakeHost implements HostState {
        public String fg;
        public boolean locked, screenOn = true, inCall, busy, immersive, dnd;
        public int w = 1080, h = 2220;

        @Override public String foregroundPkg() { return fg; }
        @Override public boolean keyguardLocked() { return locked; }
        @Override public boolean screenOn() { return screenOn; }
        @Override public boolean inCall() { return inCall; }
        @Override public boolean assistantBusy() { return busy; }
        @Override public boolean fullscreenImmersive() { return immersive; }
        @Override public boolean dndOn() { return dnd; }
        @Override public int screenWidthPx() { return w; }
        @Override public int screenHeightPx() { return h; }
    }

    public static final class FakeConfig implements SceneConfig {
        public boolean master = true;
        public int quiet;
        public final Map<String, Boolean> scenes = new HashMap<String, Boolean>();
        public final Set<String> privates = new HashSet<String>(PrivateApps.defaults());

        @Override public boolean masterOn() { return master; }
        @Override public boolean sceneOn(String id, boolean def) {
            Boolean b = scenes.get(id);
            return b == null ? def : b;
        }
        @Override public void setSceneOn(String id, boolean on) { scenes.put(id, on); }
        @Override public Set<String> privatePkgs() { return privates; }
        @Override public int quietLevel() { return quiet; }
    }

    public static final class FakePresenter implements Presenter {
        public boolean accept = true;
        public final List<Suggestion> shown = new ArrayList<Suggestion>();
        public final List<String> withdrawn = new ArrayList<String>();
        public final List<String> updates = new ArrayList<String>();
        public Listener listener;

        @Override public boolean show(Suggestion s) {
            if (!accept) return false;
            shown.add(s);
            return true;
        }
        @Override public void update(String id, String subtitle) { updates.add(id + "|" + subtitle); }
        @Override public void withdraw(String id) { withdrawn.add(id); }
        @Override public void setListener(Listener l) { listener = l; }

        public Suggestion last() {
            return shown.isEmpty() ? null : shown.get(shown.size() - 1);
        }
    }

    public static final class FakeRunner implements ActionRunner {
        public final List<String> runs = new ArrayList<String>();

        @Override public void run(Suggestion s, SAction a, Done done) {
            runs.add(s.sceneId + ":" + a.id);
            if (done != null) done.finished(true, null);
        }
    }

    public static final class FakeFiles implements Ports.FileProbe {
        public final Map<String, Long> files = new HashMap<String, Long>();

        @Override public boolean exists(String p) { return files.containsKey(p); }
        @Override public long size(String p) {
            Long v = files.get(p);
            return v == null ? -1 : v;
        }
    }

    public static final class MemStorage implements Storage {
        public final Map<String, String> m = new LinkedHashMap<String, String>();

        @Override public Map<String, String> all() { return new HashMap<String, String>(m); }
        @Override public boolean put(String k, String v) { m.put(k, v); return true; }
        @Override public boolean remove(String k) { m.remove(k); return true; }
    }

    /** 一套连好线的引擎（直接执行器）。 */
    public static final class Rig {
        public final FakeClock clock = new FakeClock();
        public final FakeSched sched = new FakeSched(clock);
        public final FakeHost host = new FakeHost();
        public final FakeConfig cfg = new FakeConfig();
        public final FakePresenter presenter = new FakePresenter();
        public final FakeRunner runner = new FakeRunner();
        public final FakeFiles files = new FakeFiles();
        public final MemStorage store = new MemStorage();
        public final List<String> outcomes = new ArrayList<String>();
        public final List<String> logs = new ArrayList<String>();
        public final SuggestPlug plug = new SuggestPlug();
        public SuggestEngine engine;

        public Rig start() {
            SuggestPlug.Deps d = new SuggestPlug.Deps();
            d.config = cfg;
            d.host = host;
            d.presenter = presenter;
            d.runner = runner;
            d.clock = clock;
            d.sched = sched;
            d.exec = null;   // 直接执行
            d.storage = store;
            d.files = files;
            d.tz = TZ;
            d.log = new com.onesay.assistant.plug.api.PlugLog() {
                @Override public void i(String m) { logs.add(m); }
                @Override public void w(String m) { logs.add("W " + m); }
            };
            plug.start(d);
            engine = plug.engine();
            engine.setOutcomeListener(new SuggestEngine.OutcomeListener() {
                @Override public void onOutcome(Suggestion s, Outcome o, String reason) {
                    outcomes.add(s.sceneId + ":" + o + (reason != null ? ":" + reason : ""));
                }
            });
            return this;
        }

        /** 一张刚截好的竖屏截图（A 源，已写完）。 */
        public Signal shot(String path, char src) {
            files.files.put(path, 123456L);
            return Signal.of(Signal.SCREENSHOT, clock.el, clock.wall, "path", path, "size", "123456", "mtime", String.valueOf(clock.wall),
                    "w", "1080", "h", "2220", "date_added", src == 'A' ? String.valueOf(clock.wall / 1000L) : "0", "src", String.valueOf(src),
                    "settled", "1");
        }
    }
}
