package com.onesay.assistant.v2.mikaplug;

import android.content.Context;

import com.onesay.assistant.plug.api.ModelPort;
import com.onesay.assistant.plug.skill.core.SkillLearner;
import com.onesay.assistant.plug.skills.core.LlmPicker;
import com.onesay.assistant.plug.skills.core.RuleDrafter;
import com.onesay.assistant.plug.skills.core.Skill;
import com.onesay.assistant.plug.skills.core.SkillDrafter;
import com.onesay.assistant.plug.skills.core.SkillIndex;
import com.onesay.assistant.plug.skills.core.SkillMatcher;
import com.onesay.assistant.plug.skills.core.SkillSession;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ★ skill-pack：技能包的宿主适配（薄层）。核心在 {@code com.onesay.assistant.plug.skills.core}（纯 Java）。
 *
 * <ul>
 *   <li>{@link #forAgent}：AgentLoop 组好系统提示词后调一次，返回要追加在系统提示词末尾的技能块（插件关 / 没命中 = null，原行为逐字节不变）；</li>
 *   <li>{@link #forAsk}：自由问答（L2LLMCompiler.ask / askStream）同上，只挑 {@code paths} 含 ask 的技能、不查工具表；</li>
 *   <li>{@link #afterRun}：任务成功后按 mika.skilllearn（默认关）沉淀草稿技能到 debug 目录（只写文件，永不加载）；</li>
 *   <li>{@link #dryRun}：debug 广播 {@code --es skill "<一句话>"} 看命中结果（不动会话）。</li>
 * </ul>
 * 插件关时 {@link #forAgent} / {@link #forAsk} 第一行就返回，连 assets 都不读（零副作用）。命中日志 {@code [SKILL] id score reason}。
 */
public final class SkillPack {
    private SkillPack() {
    }

    static final String TAG = "SkillPack";
    private static volatile SkillIndex sIndex;
    static final SkillSession SESSION = new SkillSession();
    static final SkillMatcher.Config CFG = new SkillMatcher.Config();
    /** LLM 兜底超时（只在 skillpack.llm 开时用）。 */
    static final int LLM_TIMEOUT_MS = 1500;

    public static SkillIndex index(Context ctx) {
        SkillIndex i = sIndex;
        if (i == null) {
            synchronized (SkillPack.class) {
                if (sIndex == null) {
                    SkillIndex n = new SkillIndex();
                    for (Map.Entry<String, String> e : AssetMd.load(ctx, "skill_").entrySet()) n.add(e.getKey(), e.getValue());
                    for (String p : n.problems()) log("[SKILL] 加载问题 " + p);
                    sIndex = n;
                }
                i = sIndex;
            }
        }
        return i;
    }

    /** 单测用：索引是否已加载（插件关时应一直是 false = 零副作用）。 */
    static boolean indexLoaded() {
        return sIndex != null;
    }

    /** 单测用：换索引（null = 下次重读）并清会话。 */
    public static void reset(SkillIndex i) {
        sIndex = i;
        SESSION.reset();
    }

    public static boolean enabled(Context ctx) {
        return MikaPlugs.on(ctx, MikaPlugs.SKILLPACK);
    }

    /**
     * AgentLoop：按本条指令挑技能。
     *
     * @param intents      意图标签（候选工具前几名的 category），可空
     * @param tools        本轮导出的工具 JSON（wire 名会还原成签名，用来检查 requires.tools）
     * @param unattended   无人值守（定时任务）：不用会话保持
     * @param freshConversation 会话历史为空（新会话 / 面板收起过）→ 先清会话
     * @return 追加在系统提示词末尾的块；null = 不加
     */
    public static String forAgent(Context ctx, String query, List<String> intents, JSONArray tools, boolean unattended, boolean freshConversation) {
        if (!enabled(ctx)) return null;
        try {
            SkillMatcher.Input in = new SkillMatcher.Input(query);
            in.path = "agent";
            in.intents = intents;
            in.tools = toolSigns(tools);
            screen(in);
            return pick(ctx, in, unattended ? null : session(freshConversation));
        } catch (Throwable t) {
            log("[SKILL] 异常（跳过技能注入）: " + t);
            return null;
        }
    }

    /** 自由问答：只挑 paths 含 ask 的技能，不查工具表。 */
    public static String forAsk(Context ctx, String query, boolean freshConversation) {
        if (!enabled(ctx)) return null;
        try {
            SkillMatcher.Input in = new SkillMatcher.Input(query);
            in.path = "ask";
            screen(in);
            return pick(ctx, in, session(freshConversation));
        } catch (Throwable t) {
            log("[SKILL] 异常（跳过技能注入）: " + t);
            return null;
        }
    }

    /** 拼到系统提示词末尾（null 块 = 原样返回）。 */
    public static String append(String system, String block) {
        if (block == null || block.length() == 0) return system;
        return (system == null ? "" : system) + "\n\n" + block;
    }

    static SkillSession session(boolean fresh) {
        if (fresh) SESSION.reset();
        return SESSION;
    }

    static String pick(Context ctx, SkillMatcher.Input in, SkillSession session) {
        SkillIndex idx = index(ctx);
        if (idx.size() == 0) return null;
        SkillMatcher m = new SkillMatcher(idx, CFG);
        long now = System.currentTimeMillis();
        SkillMatcher.Selection sel = m.select(in, session, now);
        for (String l : sel.log) log(l);
        if (sel.isEmpty() && MikaPlugs.on(ctx, MikaPlugs.SKILLPACK_LLM)) {
            ModelPort model = HostModel.forContext(ctx);
            Skill s = LlmPicker.pick(model, idx, in.query, in.path, LLM_TIMEOUT_MS);
            if (s != null) {
                String sec = renderOne(m, s);
                if (sec != null) {
                    log("[SKILL] " + s.id + " 0 llm_pick chars=" + sec.length());
                    return sec;
                }
            }
        }
        return sel.isEmpty() ? null : sel.block;
    }

    /** LLM 兜底挑中的单个技能：走同一套预算渲染（无会话、无 Top-K 竞争）。 */
    static String renderOne(SkillMatcher m, Skill s) {
        return m.render(s);
    }

    /** 唤醒瞬间已经抓好的屏幕上下文（不额外截屏）：前台包名 + 屏幕文字。 */
    static void screen(SkillMatcher.Input in) {
        try {
            com.onesay.assistant.v2.vision.screenref.ScreenContext c = com.onesay.assistant.v2.vision.screenref.ScreenContextCapture.fresh();
            if (c == null) return;
            in.foregroundPkg = c.pkg;
            in.screenTexts = c.texts;
        } catch (Throwable ignored) {
        }
    }

    static Set<String> toolSigns(JSONArray tools) {
        if (tools == null) return null;
        Set<String> out = new LinkedHashSet<String>();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            JSONObject fn = t == null ? null : t.optJSONObject("function");
            String n = fn == null ? null : fn.optString("name", null);
            if (n == null) continue;
            out.add(com.onesay.assistant.v2.capability.CapabilityRegistry.toolNameToSign(n));
            out.add(n);
        }
        return out;
    }

    /** debug：看一句话会命中什么（不动会话、不查工具表）。 */
    public static String dryRun(Context ctx, String query) {
        StringBuilder b = new StringBuilder();
        b.append("skillpack ").append(enabled(ctx) ? "开" : "关").append("；技能 ").append(index(ctx).size()).append(" 个\n");
        SkillMatcher m = new SkillMatcher(index(ctx), CFG);
        SkillMatcher.Input in = new SkillMatcher.Input(query);
        screen(in);
        SkillMatcher.Selection sel = m.select(in);
        for (SkillMatcher.Scored s : m.scoreAll(in)) if (s.score > 0) b.append("[SKILL] ").append(s).append('\n');
        for (String l : sel.log) b.append(l).append('\n');
        b.append("注入 ").append(sel.ids()).append(" 共 ").append(sel.chars).append(" 字");
        for (String p : index(ctx).problems()) b.append("\n问题：").append(p);
        return b.toString();
    }

    // ------------------------------------------------------------------ 草稿沉淀（mika.skilllearn，默认关）

    static volatile SkillDrafter sDrafter = new RuleDrafter();

    /**
     * 任务收尾：成功（DONE 且 finished）且 mika.skilllearn 开、且是 debug 包 → 生成草稿写到 debug 目录。
     * steps 形如「工具签名(参数键,…)」，不带参数值。返回写出的文件（没写为 null）。
     */
    public static File afterRun(Context ctx, String query, List<String> steps, boolean done, boolean debugBuild) {
        if (ctx == null || !done || !debugBuild || !MikaPlugs.on(ctx, MikaPlugs.SKILL)) return null;
        return writeDraft(draftDir(ctx), query, steps);
    }

    /** 生成草稿并写进 dir（宿主无关部分，JVM 可测）。不值得沉淀 / 写失败返回 null。 */
    static File writeDraft(File dir, String query, List<String> steps) {
        try {
            SkillDrafter.Draft d = sDrafter.draft(new SkillLearner.Episode(query, null, "done", steps), System.currentTimeMillis());
            if (d == null || dir == null || !(dir.isDirectory() || dir.mkdirs())) return null;
            File f = new File(dir, d.fileName);
            FileOutputStream o = new FileOutputStream(f);
            try {
                o.write(d.markdown.getBytes("UTF-8"));
            } finally {
                o.close();
            }
            log("[SKILL] 草稿已写 " + f.getAbsolutePath() + "（status: draft，不加载）");
            return f;
        } catch (Throwable t) {
            log("[SKILL] 草稿写入失败: " + t);
            return null;
        }
    }

    static File draftDir(Context ctx) {
        File ext = null;
        try {
            ext = ctx.getExternalFilesDir("skill_drafts");
        } catch (Throwable ignored) {
        }
        return ext != null ? ext : new File(ctx.getFilesDir(), "skill_drafts");
    }

    /** AgentLoop 的步骤 → 「签名(参数键,…)」（不带值）。 */
    public static List<String> stepShapes(List<String> tools, List<JSONObject> args) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < tools.size(); i++) {
            StringBuilder b = new StringBuilder(tools.get(i) == null ? "?" : tools.get(i)).append('(');
            JSONObject a = args == null || i >= args.size() ? null : args.get(i);
            if (a != null) {
                java.util.Iterator<String> it = a.keys();
                int n = 0;
                while (it.hasNext()) b.append(n++ > 0 ? "," : "").append(it.next());
            }
            out.add(b.append(')').toString());
        }
        return out;
    }

    static void log(String s) {
        try {
            android.util.Log.i(TAG, s);
        } catch (Throwable ignored) {
        }
    }
}
