package com.onesay.assistant.v2.suggest;

import android.content.Context;
import android.content.SharedPreferences;

import com.onesay.assistant.plug.api.PlugRegistry;
import com.onesay.assistant.plug.api.PlugSpec;
import com.onesay.assistant.plug.api.PlugSwitches;
import com.onesay.assistant.plug.suggest.core.SceneRegistry;
import com.onesay.assistant.plug.suggest.core.SuggestPlug;
import com.onesay.assistant.plug.suggest.core.scenes.shot.PrivateApps;
import com.onesay.assistant.v2.mikaplug.MikaPlugs;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ★ feat/suggest：主动建议插件族的开关（宿主侧）。和 mika 插件共用一套开关来源（{@link MikaPlugs#SWITCHES}：调试覆盖 &gt; kill 文件
 * {@code plug_<id>.off} &gt; prefs {@code bixby_plug} &gt; 出厂默认），所以沿用 {@code --es plug} 广播：
 * <pre>
 *   --es plug list                         # 两族状态都打
 *   --es plug suggest=off                  # 总开关
 *   --es plug suggest.screenshot=off       # 单场景（可写 suggest.screenshot 或 screenshot 前加 suggest.）
 *   --es plug all=default                  # 两族都回出厂默认
 * </pre>
 * <b>出厂默认</b>（2026-11-04 用户拍板）：总开关 debug、release <b>都默认开</b>（{@link #MASTER_DEFAULT}；老用户 prefs 里没有 {@code suggest} 键 = 按新默认开，
 * 自己关过的照旧关）；场景仍按 D5（privacyClass 0 且非骨架才开，骨架关），依赖总开关。用户可见开关：控制台「功能与权限 › 功能」与灵动岛页的「主动建议」行。
 */
public final class SuggestPlugs {
    private SuggestPlugs() {
    }

    public static final String MASTER = SuggestPlug.ID;
    public static final String SCENE_PREFIX = MASTER + ".";
    /** 私密名单增删 / 克制档（不在 bixby_plug 里，免得被 all=default 清掉）。 */
    public static final String PREFS = "bixby_suggest";
    public static final String K_PRIVATE_ADD = "private_add", K_PRIVATE_DEL = "private_del", K_QUIET = "quiet";

    static final boolean DBG = com.onesay.assistant.v2.BuildVariant.IS_DEBUG;

    /** 总开关出厂默认 —— debug / release 一律开（原 debug 开、release 关）。 */
    public static final boolean MASTER_DEFAULT = true;

    /** 纯函数（JVM 可测）：总开关出厂默认（与包类型无关，参数保留给调用方 / 单测）。 */
    public static boolean masterDefault(boolean debug) {
        return MASTER_DEFAULT;
    }

    /** 纯函数（JVM 可测）。 */
    public static PlugRegistry build(boolean debug, PlugSwitches sw) {
        PlugRegistry r = new PlugRegistry(sw);
        r.register(new PlugSpec(MASTER, SuggestPlug.VERSION, SuggestPlug.TITLE, SuggestPlug.SUMMARY, masterDefault(debug), null, SuggestPlug.PORTED));
        SceneRegistry sr = SuggestPlug.defaultScenes(null, null);
        for (SceneRegistry.Entry e : sr.entries()) {
            String id = e.scene.id();
            String sum = "隐私等级 " + e.scene.privacyClass() + (e.skeleton ? "（规则骨架：宿主未接信号，开了也只记日志）" : "");
            r.register(new PlugSpec(SCENE_PREFIX + id, 1, e.scene.title(), sum, sr.defaultOn(id), new String[]{MASTER}, null));
        }
        return r;
    }

    public static final PlugRegistry REG = build(DBG, MikaPlugs.SWITCHES);

    public static boolean masterOn(Context ctx) {
        MikaPlugs.attach(ctx);
        return REG.on(MASTER);
    }

    /** 总开关本身（prefs bixby_plug 的 suggest 键 / kill 文件 / 调试覆盖 / 出厂默认），控制台开关显示用。 */
    public static boolean masterSelfOn(Context ctx) {
        MikaPlugs.attach(ctx);
        return REG.selfOn(MASTER);
    }

    public static boolean sceneOn(Context ctx, String sceneId) {
        MikaPlugs.attach(ctx);
        return REG.on(SCENE_PREFIX + sceneId);
    }

    public static boolean sceneSelfOn(Context ctx, String sceneId) {
        MikaPlugs.attach(ctx);
        return REG.selfOn(SCENE_PREFIX + sceneId);
    }

    public static void setScene(Context ctx, String sceneId, boolean on) {
        MikaPlugs.set(ctx, SCENE_PREFIX + sceneId, on);
    }

    public static void setMaster(Context ctx, boolean on) {
        MikaPlugs.set(ctx, MASTER, on);
    }

    /** 用户在控制台拨总开关：写 prefs bixby_plug 的 suggest 键并立刻让内核比对一次（onEnable / onDisable 即时生效）。 */
    public static void setMasterNow(Context ctx, boolean on) {
        setMaster(ctx, on);
        try {
            com.onesay.assistant.v2.kernel.FeatureKernel.get().refresh(ctx.getApplicationContext());
        } catch (Throwable ignored) {
        }
    }

    public static String status() {
        return REG.statusText();
    }

    // ------------------------------------------------------------------ 私密名单 / 克制档

    public static Set<String> privatePkgs(Context ctx) {
        Set<String> s = new LinkedHashSet<String>(PrivateApps.defaults());
        if (ctx == null) return s;
        s.add(ctx.getPackageName());   // OneSay 自己（控制台）
        try {
            SharedPreferences sp = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            s.addAll(sp.getStringSet(K_PRIVATE_ADD, new LinkedHashSet<String>()));
            s.removeAll(sp.getStringSet(K_PRIVATE_DEL, new LinkedHashSet<String>()));
        } catch (Throwable ignored) {
        }
        return s;
    }

    public static int quietLevel(Context ctx) {
        try {
            return ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(K_QUIET, 0);
        } catch (Throwable t) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ --es plug 调试命令

    /** 把一条 --es plug 命令拆成 [给 mika 的, 给 suggest 的]；list / all=… 两边都给。纯函数。 */
    public static String[] splitCommand(String arg) {
        StringBuilder m = new StringBuilder(), g = new StringBuilder();
        if (arg != null) {
            for (String part : arg.split(",")) {
                String t = part.trim();
                if (t.length() == 0) continue;
                String id = t.indexOf('=') > 0 ? t.substring(0, t.indexOf('=')).trim() : t;
                boolean sug = id.equals(MASTER) || id.startsWith(SCENE_PREFIX);
                boolean both = "all".equals(id) || "list".equalsIgnoreCase(t);
                if (sug || both) g.append(g.length() > 0 ? "," : "").append(t);
                if (!sug || both) m.append(m.length() > 0 ? "," : "").append(t);
            }
        }
        return new String[]{m.toString(), g.toString()};
    }

    /** 解析 suggest 族命令：返回 [id, on|off|default] 或 [原文, ?]。all=on 被拒（与 mika 同口径）。纯函数。 */
    public static List<String[]> parseCommand(PlugRegistry reg, String arg) {
        List<String[]> out = new ArrayList<String[]>();
        if (arg == null) return out;
        for (String part : arg.split(",")) {
            String t = part.trim();
            if (t.length() == 0 || "list".equalsIgnoreCase(t)) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) {
                out.add(new String[]{t, "?"});
                continue;
            }
            String id = t.substring(0, eq).trim(), v = t.substring(eq + 1).trim().toLowerCase(Locale.ROOT);
            String norm = "on".equals(v) || "true".equals(v) || "1".equals(v) ? "on"
                    : "off".equals(v) || "false".equals(v) || "0".equals(v) ? "off"
                    : "default".equals(v) || "reset".equals(v) ? "default" : null;
            if ("all".equals(id)) {
                if (norm == null || "on".equals(norm)) {
                    out.add(new String[]{t, "?"});
                    continue;
                }
                for (PlugSpec sp : reg.specs()) out.add(new String[]{sp.id, norm});
                continue;
            }
            if (norm == null || reg.spec(id) == null) {
                out.add(new String[]{t, "?"});
                continue;
            }
            out.add(new String[]{id, norm});
        }
        return out;
    }

    /** 执行 suggest 族命令（写 prefs，持久），返回执行结果行。改了总开关立即让内核比对一次（插件 onEnable / onDisable）。 */
    public static String applyCommand(Context ctx, String arg) {
        if (arg == null || arg.length() == 0) return "";
        StringBuilder b = new StringBuilder();
        boolean changed = false;
        for (String[] c : parseCommand(REG, arg)) {
            if ("?".equals(c[1])) {
                b.append("看不懂：").append(c[0]).append('\n');
                continue;
            }
            if ("default".equals(c[1])) MikaPlugs.reset(ctx, c[0]);
            else MikaPlugs.set(ctx, c[0], "on".equals(c[1]));
            b.append(c[0]).append(" → ").append(c[1]).append('\n');
            changed = true;
        }
        if (changed) {
            try {
                com.onesay.assistant.v2.kernel.FeatureKernel.get().refresh(ctx.getApplicationContext());
            } catch (Throwable ignored) {
            }
        }
        return b.toString();
    }
}
