/*
 * [suggest · 主动建议插件 core] OneSay 自写（非上游代码）。纯 Java 7，只依赖 java.* / org.json.* 与 com.onesay.assistant.plug.api。
 * 设计：fix-20261025/aicy/AICY-RESEARCH.md §5–§7；规范：fix-20261025/mika-plug/PLUGIN-SPEC.md。
 */
package com.onesay.assistant.plug.suggest.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/** 一条建议（不可变；引擎改副标题 / 计数时用 {@link #withSubtitle} 等派生新对象）。 */
public final class Suggestion {
    private static final AtomicLong SEQ = new AtomicLong();

    /** sceneId + ":" + 递增号。 */
    public final String id;
    public final String sceneId;
    /** 同 key 在去重窗口内只出一次；null = 不去重。 */
    public final String dedupKey;
    public final String title;
    /** 可空，如「3 张截图」「微信 · 刚刚」。 */
    public final String subtitle;
    public final String iconKey;
    /** 可空：本地文件路径（只给宿主渲染缩略图，不出本机）。 */
    public final String thumbRef;
    /** ≤3，第一个是主动作。 */
    public final List<SAction> actions;
    public final long ttlMs;
    /** 0 低 … 3 高；岛忙 / 静默时低优先级直接丢。 */
    public final int priority;
    public final Map<String, String> payload;
    /** 合并组（连拍截图的 burst id）：岛上已有同组卡时就地更新，不重弹。可空。 */
    public final String groupKey;
    /** 只记账不展示（取件码：S1 现有链路自己上岛，这里只接入总频控）。 */
    public final boolean external;
    /** 展示期间每秒确认这些文件还在（截图被删 → 撤卡）。可空。 */
    public final String watchPath;

    private Suggestion(Builder b, String id) {
        this.id = id;
        this.sceneId = b.sceneId;
        this.dedupKey = b.dedupKey;
        this.title = b.title == null ? "" : b.title;
        this.subtitle = b.subtitle;
        this.iconKey = b.iconKey;
        this.thumbRef = b.thumbRef;
        List<SAction> a = new ArrayList<SAction>();
        for (SAction x : b.actions) if (x != null && a.size() < SuggestDefaults.MAX_ACTIONS) a.add(x);
        this.actions = Collections.unmodifiableList(a);
        this.ttlMs = b.ttlMs > 0 ? b.ttlMs : SuggestDefaults.CARD_TTL_MS;
        this.priority = Math.max(0, Math.min(3, b.priority));
        this.payload = Collections.unmodifiableMap(new HashMap<String, String>(b.payload));
        this.groupKey = b.groupKey;
        this.external = b.external;
        this.watchPath = b.watchPath;
    }

    public SAction action(String actionId) {
        for (SAction a : actions) if (a.id.equals(actionId)) return a;
        return null;
    }

    public SAction actionAt(int index) {
        return index >= 0 && index < actions.size() ? actions.get(index) : null;
    }

    public List<String> labels() {
        List<String> out = new ArrayList<String>();
        for (SAction a : actions) out.add(a.label);
        return out;
    }

    public String get(String k) {
        return payload.get(k);
    }

    public Builder toBuilder() {
        Builder b = new Builder(sceneId);
        b.dedupKey = dedupKey;
        b.title = title;
        b.subtitle = subtitle;
        b.iconKey = iconKey;
        b.thumbRef = thumbRef;
        b.actions.addAll(actions);
        b.ttlMs = ttlMs;
        b.priority = priority;
        b.payload.putAll(payload);
        b.groupKey = groupKey;
        b.external = external;
        b.watchPath = watchPath;
        b.fixedId = id;
        return b;
    }

    public Suggestion withSubtitle(String sub) {
        Builder b = toBuilder();
        b.subtitle = sub;
        return b.build();
    }

    @Override public String toString() {
        return "Suggestion(" + id + " p=" + priority + " acts=" + actions + (external ? " external" : "") + ")";
    }

    public static Builder builder(String sceneId) {
        return new Builder(sceneId);
    }

    public static final class Builder {
        final String sceneId;
        String dedupKey, title, subtitle, iconKey, thumbRef, groupKey, watchPath, fixedId;
        final List<SAction> actions = new ArrayList<SAction>();
        long ttlMs = SuggestDefaults.CARD_TTL_MS;
        int priority = 1;
        final Map<String, String> payload = new HashMap<String, String>();
        boolean external;

        Builder(String sceneId) {
            this.sceneId = sceneId;
        }

        public Builder dedup(String k) { dedupKey = k; return this; }
        public Builder title(String t) { title = t; return this; }
        public Builder subtitle(String t) { subtitle = t; return this; }
        public Builder icon(String k) { iconKey = k; return this; }
        public Builder thumb(String p) { thumbRef = p; return this; }
        public Builder action(SAction a) { actions.add(a); return this; }
        public Builder ttl(long ms) { ttlMs = ms; return this; }
        public Builder priority(int p) { priority = p; return this; }
        public Builder put(String k, String v) { if (k != null && v != null) payload.put(k, v); return this; }
        public Builder group(String g) { groupKey = g; return this; }
        public Builder external(boolean e) { external = e; return this; }
        public Builder watch(String p) { watchPath = p; return this; }
        public Builder id(String id) { fixedId = id; return this; }

        public Suggestion build() {
            return new Suggestion(this, fixedId != null ? fixedId : sceneId + ":" + SEQ.incrementAndGet());
        }
    }
}
