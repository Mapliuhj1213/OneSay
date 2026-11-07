package com.onesay.assistant.v2.shortcut;

import java.util.List;

/**
 * [shortcut] 一句话 → 已存的快捷指令（纯 Java）。
 * <ul>
 *   <li>「执行快捷指令#12（晚安）」（定时触发 / 点击运行排进队列的指令）→ 按 id，不看语音开关；</li>
 *   <li>说名字（可带「执行 / 运行（快捷指令）」前缀）→ 开了语音触发的那条；</li>
 *   <li>对话里存的快捷指令：原话也算触发词（「第二次做同样的事」）。</li>
 * </ul>
 * 比较用 {@link ShortcutModel#normName}（去空白标点、小写），只认整句相等 —— 不做模糊匹配，免得「晚安」误伤「晚安曲推荐」。
 */
public final class ShortcutMatcher {

    private ShortcutMatcher() {}

    public static final class Hit {
        public final ShortcutModel.Shortcut shortcut;
        /** true = 「执行快捷指令#id」形式（定时 / 点击）；false = 说名字或原话。 */
        public final boolean byCommand;

        Hit(ShortcutModel.Shortcut s, boolean byCommand) {
            this.shortcut = s;
            this.byCommand = byCommand;
        }
    }

    static final String[] PREFIXES = {"执行快捷指令", "运行快捷指令", "快捷指令", "执行", "运行"};

    public static Hit match(String query, List<ShortcutModel.Shortcut> all) {
        if (query == null || all == null || all.isEmpty()) return null;
        long id = ShortcutModel.commandId(query);
        if (id > 0) {
            for (ShortcutModel.Shortcut s : all) if (s.id == id) return s.enabled ? new Hit(s, true) : null;
            return null;
        }
        String q = ShortcutModel.normName(query);
        if (q.length() == 0) return null;
        Hit h = find(q, all);
        if (h != null) return h;
        for (String p : PREFIXES) {
            if (q.startsWith(p) && q.length() > p.length()) {
                h = find(q.substring(p.length()), all);
                if (h != null) return h;
            }
        }
        return null;
    }

    static Hit find(String q, List<ShortcutModel.Shortcut> all) {
        for (ShortcutModel.Shortcut s : all) {
            if (!s.enabled || !s.trigger(ShortcutModel.TRIG_VOICE)) continue;
            if (ShortcutModel.normName(s.name).equals(q)) return new Hit(s, false);
        }
        for (ShortcutModel.Shortcut s : all) {
            if (!s.enabled || !s.trigger(ShortcutModel.TRIG_VOICE)) continue;
            for (String a : s.aliases) if (ShortcutModel.normName(a).equals(q)) return new Hit(s, false);
        }
        return null;
    }
}
