/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** id → 场景 + 频控参数 + 是否骨架。登记顺序 = 同优先级仲裁时的先后。 */
public final class SceneRegistry {

    public static final class Entry {
        public final Scene scene;
        public final RatePolicy policy;
        /** 骨架场景：规则写好了但宿主没有接信号 / 动作，即使被打开也只记日志、不展示。 */
        public final boolean skeleton;

        Entry(Scene scene, RatePolicy policy, boolean skeleton) {
            this.scene = scene;
            this.policy = policy == null ? RatePolicy.DEFAULT : policy;
            this.skeleton = skeleton;
        }
    }

    private final Map<String, Entry> mById = new LinkedHashMap<String, Entry>();

    public synchronized SceneRegistry register(Scene s, RatePolicy p) {
        return register(s, p, false);
    }

    public synchronized SceneRegistry register(Scene s, RatePolicy p, boolean skeleton) {
        if (s == null) return this;
        if (!s.id().matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("bad scene id: " + s.id());
        if (mById.containsKey(s.id())) throw new IllegalStateException("duplicate scene: " + s.id());
        mById.put(s.id(), new Entry(s, p, skeleton));
        return this;
    }

    public synchronized Entry entry(String id) {
        return mById.get(id);
    }

    public synchronized Scene scene(String id) {
        Entry e = mById.get(id);
        return e == null ? null : e.scene;
    }

    public synchronized List<Entry> entries() {
        return Collections.unmodifiableList(new ArrayList<Entry>(mById.values()));
    }

    public synchronized RatePolicy policy(String id) {
        Entry e = mById.get(id);
        return e == null ? RatePolicy.DEFAULT : e.policy;
    }

    /** D5：此场景在插件开启后的出厂默认。 */
    public synchronized boolean defaultOn(String id) {
        Entry e = mById.get(id);
        return e != null && !e.skeleton && e.scene.defaultOn();
    }

    public synchronized boolean isSkeleton(String id) {
        Entry e = mById.get(id);
        return e != null && e.skeleton;
    }
}
