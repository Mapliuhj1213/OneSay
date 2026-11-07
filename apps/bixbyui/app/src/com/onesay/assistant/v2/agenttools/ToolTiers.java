package com.onesay.assistant.v2.agenttools;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ★ arch/tool-tiers（A 路）：工具 → 档位（声明式规则表，代码按属性办事、不在 AgentLoop 里写死工具名），
 * 以及「按档位 + 相关度」给候选排序再截断。
 *
 * <p><b>为什么不加到 Capability 字段里</b>：BuiltinCapabilities 有全字段快照测试（builtin_caps_snapshot.txt），
 * 档位是 AgentLoop 侧的编排属性，放在这一张表里集中维护，注册表与 L1 缓存口径不变。
 *
 * <p><b>排序</b>（替代旧的「字面打分 Top-40」）：
 * <ol>
 *   <li>先按相关度分三段：强（≥{@link #STRONG}：语素对齐 / 口语模板 / 状态同义）、中（≥{@link #MEDIUM}：命中动词等）、弱（&gt;0：摘要二元组蹭分）；</li>
 *   <li>同一段里按档位（接口 &lt; 查信息 &lt; 深链 &lt; 点界面），再按分数；</li>
 *   <li>截断到 K。旧做法里摘要蹭分的弱相关项可能挤掉真正对口的接口，现在弱相关只能排在所有强 / 中相关项之后。</li>
 * </ol>
 */
public final class ToolTiers {

    private ToolTiers() {}

    /** 强相关下限：一次语素对齐（+25）/ 口语模板（+40）/ 状态同义（+30）就够。 */
    public static final int STRONG = 25;
    /** 中相关下限：命中一个动词（+10）。 */
    public static final int MEDIUM = 10;

    /** 相关度分段：3 强 / 2 中 / 1 弱 / 0 无。 */
    public static int band(int score) {
        if (score >= STRONG) return 3;
        if (score >= MEDIUM) return 2;
        if (score > 0) return 1;
        return 0;
    }

    // ------------------------------------------------------------------ 分档

    /** GUI 档的工具（phone.operate 与它折叠时的入口）。 */
    public static final String SIGN_GUI_OPERATE = "phone.operate";
    public static final String SIGN_GUI_ENTRY = "gui.unfold";

    /**
     * ★ arch/integrate：常驻控制类工具（B 路 task.todo / ask_user / end_not_done）。它们由循环自己处理、不过闸、
     * 每轮都在 tools 末尾（{@code LoopTools.append}，在档位排序截断<b>之后</b>追加，GUI 展开重新导出后也补回），
     * <b>不参与档位排序、不被 Top-K 截断、不被折叠</b>；提示词的各档清单里单列「常驻」，不算进「接口」档。
     */
    public static final java.util.List<String> RESIDENT = java.util.Collections.unmodifiableList(
            java.util.Arrays.asList("task.todo", "ask_user", "end_not_done"));

    /** 是否常驻控制类（签名或 wire 名）。 */
    public static boolean isResident(String signOrWire) {
        return RESIDENT.contains(norm(signOrWire));
    }

    /** 按签名分档（插件工具没有注册表 Capability 时也能用）。签名可以是 wire 名（{@code a__b}）。 */
    public static ToolTier ofSign(String signOrWire) {
        String s = norm(signOrWire);
        if (s.length() == 0) return ToolTier.API;
        if (s.equals(SIGN_GUI_OPERATE) || s.equals(SIGN_GUI_ENTRY)) return ToolTier.GUI;
        // 查信息：搜索 / 读网页 / 查记忆 / 读屏幕文字（B 路的 web.search / web.read / research.spawn 落在这一档）
        if (s.startsWith("web.") || s.startsWith("research.") || s.startsWith("search.")
                || s.equals("memory.search") || s.equals("vision.screen.understand") || s.equals("screen.context")) {
            return ToolTier.INFO;
        }
        if (s.startsWith("conn.") && (s.endsWith(".search") || s.endsWith(".read") || s.endsWith(".fetch")
                || s.endsWith(".extract"))) {
            return ToolTier.INFO;
        }
        // 深链：打开到某个页面
        if (s.equals("app.launch") || s.startsWith("settings.page.") || s.startsWith("store.")
                || s.startsWith("browser.") || s.equals("screen.open.url") || s.equals("screen.nav.to")) {
            return ToolTier.DEEPLINK;
        }
        return ToolTier.API;
    }

    /** 按能力分档：签名规则优先；其余 executor=intent / 指令含 am start（且不是面板内闹钟联动）→ 深链；再其余 → 接口。 */
    public static ToolTier of(Capability cap) {
        if (cap == null) return ToolTier.API;
        ToolTier bySign = ofSign(cap.sign);
        if (bySign != ToolTier.API) return bySign;
        if ("alarmbridge".equalsIgnoreCase(cap.executorType)) return ToolTier.API;
        String cmd = cap.cmdTemplate == null ? "" : cap.cmdTemplate;
        if ("intent".equals(cap.executorType) || cmd.contains("am start")) return ToolTier.DEEPLINK;
        return ToolTier.API;
    }

    static String norm(String signOrWire) {
        if (signOrWire == null) return "";
        String s = signOrWire.trim();
        if (s.indexOf("__") >= 0) s = CapabilityRegistry.toolNameToSign(s);
        return s.toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------ 排序

    /** 一条带分数的候选。 */
    public static final class Scored {
        public final Capability cap;
        public final int score;
        public final ToolTier tier;
        final int order;

        public Scored(Capability cap, int score, int order) {
            this.cap = cap;
            this.score = score;
            this.tier = of(cap);
            this.order = order;
        }
    }

    static final Comparator<Scored> BY_BAND_TIER_SCORE = new Comparator<Scored>() {
        @Override public int compare(Scored a, Scored b) {
            int c = Integer.compare(band(b.score), band(a.score));
            if (c != 0) return c;
            c = Integer.compare(a.tier.rank, b.tier.rank);
            if (c != 0) return c;
            c = Integer.compare(b.score, a.score);
            if (c != 0) return c;
            return Integer.compare(a.order, b.order);
        }
    };

    /**
     * 按「相关度段 → 档位 → 分数 → 注册顺序」排序后截断到 k。
     * 与旧 getTopK 一样：零分项只在前 {@code minKeep} 个名额内保留（兜底）。
     */
    public static List<Scored> rank(List<Scored> all, int k, int minKeep) {
        List<Scored> sorted = new ArrayList<Scored>(all);
        Collections.sort(sorted, BY_BAND_TIER_SCORE);
        List<Scored> out = new ArrayList<Scored>();
        for (int i = 0; i < Math.min(k, sorted.size()); i++) {
            Scored s = sorted.get(i);
            if (s.score > 0 || i < minKeep) out.add(s);
        }
        return out;
    }

    public static List<Capability> caps(List<Scored> list) {
        List<Capability> out = new ArrayList<Capability>(list.size());
        for (Scored s : list) out.add(s.cap);
        return out;
    }

    // ------------------------------------------------------------------ 提示词拼装用

    /** tools 数组（OpenAI 规范）里实际挂载的签名（wire 名还原为签名，按出现顺序去重）。 */
    public static Set<String> mountedSigns(JSONArray tools) {
        Set<String> out = new LinkedHashSet<String>();
        if (tools == null) return out;
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t == null) continue;
            JSONObject fn = t.optJSONObject("function");
            String name = fn != null ? fn.optString("name", "") : t.optString("name", "");
            if (name.length() == 0) continue;
            out.add(CapabilityRegistry.toolNameToSign(name));
        }
        return out;
    }
}
