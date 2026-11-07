/*
 * [插件规范 · 公共端口] OneSay 自写（非上游代码）。core 包只依赖 java.* 与 org.json，宿主实现窄接口把插件接进来。
 * 规范见 fix-20261025/mika-plug/PLUGIN-SPEC.md（与 phongent 路 PHONGENT-PORT.md §插件规范对齐）。
 */
package com.onesay.assistant.plug.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 统一插件注册表：登记规格、按开关 + 依赖裁决「此刻开没开」、给出状态行。纯 Java，线程安全（登记一般只在启动时）。
 *
 * <ul>
 *   <li>{@link #on(String)}：自己的开关开着，且依赖链上每一个都开着（依赖关 = 本插件视同关，零副作用回落）；
 *       没登记的 id 一律 false；</li>
 *   <li>依赖缺失 / 成环在 {@link #register} 时不报错（允许乱序登记），{@link #problems()} 里列出，相关插件视同关；</li>
 *   <li>开关现查不缓存：宿主改了设置、放了 kill 文件，下一次调用就生效。</li>
 * </ul>
 */
public final class PlugRegistry {

    private final Map<String, PlugSpec> mSpecs = new LinkedHashMap<String, PlugSpec>();
    private volatile PlugSwitches mSwitches;

    public PlugRegistry(PlugSwitches switches) {
        mSwitches = switches == null ? PlugSwitches.DEFAULTS : switches;
    }

    public void setSwitches(PlugSwitches s) {
        mSwitches = s == null ? PlugSwitches.DEFAULTS : s;
    }

    public synchronized PlugRegistry register(PlugSpec s) {
        if (s == null) return this;
        if (mSpecs.containsKey(s.id)) throw new IllegalStateException("duplicate plug id: " + s.id);
        mSpecs.put(s.id, s);
        return this;
    }

    public synchronized PlugSpec spec(String id) {
        return mSpecs.get(id);
    }

    public synchronized List<PlugSpec> specs() {
        return Collections.unmodifiableList(new ArrayList<PlugSpec>(mSpecs.values()));
    }

    /** 只看自己的开关（不看依赖）。 */
    public boolean selfOn(String id) {
        PlugSpec s = spec(id);
        if (s == null) return false;
        try {
            return mSwitches.on(id, s.defaultOn);
        } catch (Throwable t) {
            return false;
        }
    }

    /** 自己开着且依赖链全开（缺依赖 / 成环 = 关）。 */
    public boolean on(String id) {
        return on(id, new HashSet<String>());
    }

    private boolean on(String id, Set<String> path) {
        if (!path.add(id)) return false;   // 成环
        PlugSpec s = spec(id);
        if (s == null || !selfOn(id)) return false;
        for (String d : s.deps) if (!on(d, path)) return false;
        path.remove(id);
        return true;
    }

    /** 为什么关着（开着返回 null）。 */
    public String whyOff(String id) {
        PlugSpec s = spec(id);
        if (s == null) return "未登记";
        if (!selfOn(id)) return "开关关";
        for (String d : s.deps) {
            if (spec(d) == null) return "缺依赖 " + d;
            if (!on(d)) return "依赖 " + d + " 关";
        }
        return on(id) ? null : "依赖成环";
    }

    /** 登记层面的问题：缺依赖、成环。 */
    public synchronized List<String> problems() {
        List<String> out = new ArrayList<String>();
        for (PlugSpec s : mSpecs.values()) {
            for (String d : s.deps) if (!mSpecs.containsKey(d)) out.add(s.id + " 缺依赖 " + d);
            if (cyclic(s.id, new HashSet<String>())) out.add(s.id + " 依赖成环");
        }
        return out;
    }

    private boolean cyclic(String id, Set<String> path) {
        if (!path.add(id)) return true;
        PlugSpec s = mSpecs.get(id);
        if (s != null) for (String d : s.deps) if (cyclic(d, path)) return true;
        path.remove(id);
        return false;
    }

    /** 一行一个：{@code mika.screen@1 开 （默认开）读屏对齐 Mika}。 */
    public String statusText() {
        StringBuilder b = new StringBuilder();
        for (PlugSpec s : specs()) {
            String why = whyOff(s.id);
            b.append(s.id).append('@').append(s.version).append(' ').append(why == null ? "开" : "关(" + why + ")")
                    .append(" （默认").append(s.defaultOn ? "开" : "关").append("）").append(s.title).append('\n');
        }
        for (String p : problems()) b.append("! ").append(p).append('\n');
        return b.toString();
    }
}
