package com.onesay.plugin.api.entry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 入口注册表（纯 Java，进程内单例）。
 *
 * <ul>
 *   <li>宿主启动时 {@link #setHost} + {@link #setStore}，再 {@link #register} 内置入口与插件入口；</li>
 *   <li>开关状态存在宿主提供的 {@link Store} 里；用户没动过的入口取 {@link EntryTrigger#defaultEnabled()}；</li>
 *   <li>{@link #fire}：入口被触发时调用。入口未注册、被关掉、或这台设备上明确不可用 → 丢弃并返回 false；</li>
 *   <li>{@link #apply}：按开关和可用性启动 / 停止所有入口（设置变化、服务启动时调用）。</li>
 * </ul>
 */
public final class EntryRegistry {
    private EntryRegistry() {}

    /** 开关存储（宿主用 SharedPreferences 实现）。 */
    public interface Store {
        /** 用户设置过返回值；没设置过返回 null。 */
        Boolean get(String id);

        void put(String id, boolean on);
    }

    /** 进程内存储（默认值；单测用）。 */
    public static final class MemoryStore implements Store {
        private final Map<String, Boolean> m = new LinkedHashMap<String, Boolean>();
        public synchronized Boolean get(String id) { return m.get(id); }
        public synchronized void put(String id, boolean on) { m.put(id, on); }
    }

    private static final Map<String, EntryTrigger> REG = new LinkedHashMap<String, EntryTrigger>();
    private static final Map<String, Boolean> RUNNING = new LinkedHashMap<String, Boolean>();
    private static volatile Store sStore = new MemoryStore();
    private static volatile SummonHost sHost;
    private static volatile EntryEnv sEnv;

    public static void setStore(Store s) { sStore = s == null ? new MemoryStore() : s; }

    public static void setHost(SummonHost h) { sHost = h; }

    public static void setEnv(EntryEnv e) { sEnv = e; }

    public static EntryEnv env() { return sEnv; }

    static boolean validId(String id) {
        return id != null && id.matches("[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+") && id.length() <= 64;
    }

    /** 注册（同 id 覆盖）。id 必须形如 {@code entry.xxx} / {@code vendor.samsung.xxx}。 */
    public static synchronized void register(EntryTrigger t) {
        if (t == null || !validId(t.id())) throw new IllegalArgumentException("entry id: " + (t == null ? null : t.id()));
        REG.put(t.id(), t);
    }

    /** 清空（单测用）。不调用 stop。 */
    public static synchronized void reset() {
        REG.clear();
        RUNNING.clear();
        sStore = new MemoryStore();
        sHost = null;
        sEnv = null;
    }

    public static synchronized List<EntryTrigger> all() {
        return Collections.unmodifiableList(new ArrayList<EntryTrigger>(REG.values()));
    }

    public static synchronized EntryTrigger get(String id) {
        return id == null ? null : REG.get(id);
    }

    public static boolean isEnabled(String id) {
        EntryTrigger t = get(id);
        if (t == null) return false;
        Boolean b = null;
        try { b = sStore.get(id); } catch (Throwable ignored) { }
        return b != null ? b : t.defaultEnabled();
    }

    /** 用户拨开关。返回 false = 未注册。之后宿主应调 {@link #apply}。 */
    public static boolean setEnabled(String id, boolean on) {
        if (get(id) == null) return false;
        sStore.put(id, on);
        return true;
    }

    public static EntryTrigger.Tri availability(String id) {
        EntryTrigger t = get(id);
        if (t == null) return EntryTrigger.Tri.NO;
        try {
            EntryTrigger.Tri r = t.isAvailable(sEnv);
            return r == null ? EntryTrigger.Tri.UNKNOWN : r;
        } catch (Throwable e) {
            return EntryTrigger.Tri.UNKNOWN;
        }
    }

    /** 入口能否真正唤起：已注册 + 已打开 + 不是明确不可用（「未确认」放行，和能力页一致）。 */
    public static boolean canFire(String id) {
        return get(id) != null && isEnabled(id) && availability(id) != EntryTrigger.Tri.NO;
    }

    /**
     * 入口触发 → 唤起宿主。
     * @return true = 已交给宿主；false = 丢弃（未注册 / 已关 / 不可用 / 宿主未就绪）
     */
    public static boolean fire(String id, EntryTrigger.Mode mode) {
        if (!canFire(id)) return false;
        SummonHost h = sHost;
        if (h == null) return false;
        h.summon(id, mode == null ? EntryTrigger.Mode.VOICE : mode);
        return true;
    }

    /** 按开关与可用性启动 / 停止全部入口。返回此刻在运行的入口 id。 */
    public static List<String> apply() {
        List<EntryTrigger> list = all();
        List<String> on = new ArrayList<String>();
        for (EntryTrigger t : list) {
            boolean want = canFire(t.id());
            boolean running;
            synchronized (EntryRegistry.class) { running = Boolean.TRUE.equals(RUNNING.get(t.id())); }
            try {
                if (want && !running) running = t.start(sEnv);
                else if (!want && running) { t.stop(sEnv); running = false; }
            } catch (Throwable e) {
                running = false;
            }
            synchronized (EntryRegistry.class) { RUNNING.put(t.id(), running); }
            if (running) on.add(t.id());
        }
        return on;
    }

    public static synchronized boolean isRunning(String id) {
        return Boolean.TRUE.equals(RUNNING.get(id));
    }
}
