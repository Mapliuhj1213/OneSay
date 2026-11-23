package com.onesay.assistant.v2.pclink;

import com.onesay.assistant.v2.shortcut.ShortcutModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * [overnight-1119] 电脑看 / 运行手机上的快捷指令（shortcut_list / shortcut_run）的纯 Java 部分：列表给电脑看什么、按名字找哪一条。
 * 运行本身交给手机上的 OneSay（和在手机上点「运行」同一条执行链：每一步照旧按手机的规则，打电话 / 发消息 / 付款这类在手机上弹卡）。
 */
public final class PcShortcuts {

    private PcShortcuts() {}

    /** 给电脑看的列表：名字、几步、开着没（停用的也列出来，标明运行不了）。最多 50 条。 */
    public static List<Object> view(List<ShortcutModel.Shortcut> all) {
        List<Object> out = new ArrayList<Object>();
        if (all == null) return out;
        for (ShortcutModel.Shortcut s : all) {
            if (out.size() >= 50) break;
            Map<String, Object> m = new TreeMap<String, Object>();
            m.put("name", s.name);
            m.put("steps", s.steps.size() + " 步");
            m.put("enabled", s.enabled);
            List<Object> t = new ArrayList<Object>();
            for (ShortcutModel.Step st : s.steps) if (t.size() < 6) t.add(st.title != null && st.title.length() > 0 ? st.title : "一步");
            m.put("step_titles", t);
            if (!s.aliases.isEmpty()) m.put("aliases", new ArrayList<Object>(s.aliases));
            out.add(m);
        }
        return out;
    }

    /** 找结果：命中的那条 / 一句给人看的原因。 */
    public static final class Pick {
        public final ShortcutModel.Shortcut hit;
        public final String why;

        Pick(ShortcutModel.Shortcut hit, String why) {
            this.hit = hit;
            this.why = why;
        }
    }

    /** 按名字找：名字全等 → 别名全等 → 名字包含（只有一条包含才算，多条让电脑说清楚）。停用的不跑。 */
    public static Pick find(List<ShortcutModel.Shortcut> all, String name) {
        String want = ShortcutModel.normName(name);
        if (want.length() == 0) return new Pick(null, "要给快捷指令名");
        if (all == null || all.isEmpty()) return new Pick(null, "手机上还没有快捷指令");
        ShortcutModel.Shortcut hit = null;
        for (ShortcutModel.Shortcut s : all) if (ShortcutModel.normName(s.name).equals(want)) { hit = s; break; }
        if (hit == null) for (ShortcutModel.Shortcut s : all) {
            for (String a : s.aliases) if (ShortcutModel.normName(a).equals(want)) { hit = s; break; }
            if (hit != null) break;
        }
        if (hit == null) {
            List<ShortcutModel.Shortcut> part = new ArrayList<ShortcutModel.Shortcut>();
            for (ShortcutModel.Shortcut s : all) {
                String n = ShortcutModel.normName(s.name);
                if (n.length() > 0 && (n.contains(want) || want.contains(n))) part.add(s);
            }
            if (part.size() == 1) hit = part.get(0);
            else if (part.size() > 1) {
                StringBuilder b = new StringBuilder();
                for (int i = 0; i < part.size() && i < 5; i++) b.append(i == 0 ? "" : "、").append("「").append(part.get(i).name).append("」");
                return new Pick(null, "有几条都像：" + b + "，说全名");
            }
        }
        if (hit == null) return new Pick(null, "手机上没有叫「" + name.trim() + "」的快捷指令");
        if (!hit.enabled) return new Pick(null, "快捷指令「" + hit.name + "」在手机上停用了");
        if (hit.steps.isEmpty()) return new Pick(null, "快捷指令「" + hit.name + "」没有步骤");
        return new Pick(hit, "");
    }
}
