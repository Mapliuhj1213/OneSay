package com.onesay.assistant.v2.kernel;

import com.onesay.assistant.plug.api.PlugRegistry;
import com.onesay.assistant.plug.api.PlugSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * ★ int/1102：debug 入口 {@code --es plug …} 的统一分派（纯 Java，JVM 可测）。
 *
 * <p>整合前 rich-output / suggest / mika-plug 三个分支各自在 TestRunReceiver 里抢 {@code plug} 参数，谁先 return 谁赢。
 * 现在一条命令按逗号拆成若干项，每项按 id 前缀分派给所属插件族：</p>
 * <ul>
 *   <li>完整 id 精确命中某族注册表 → 该族；</li>
 *   <li>id 等于族名或以「族名.」开头（{@code mika.xxx}、{@code richout.xxx}、{@code suggest.xxx}）→ 该族（未知 id 由该族报「看不懂」）；</li>
 *   <li>短名（不带点、非完整 id，如 {@code guard}、{@code diagram}）→ 在每个族里试「族名.短名」：恰好一族命中才改写成完整 id；
 *       多族同时命中 = <b>重名</b>，整项拒绝并提示写全名（不猜）；都不中 = 看不懂；</li>
 *   <li>{@code all=off|default} 发给所有族（{@code all=on} 各族都拒绝，不许一键全开）；{@code list} 只看状态。</li>
 * </ul>
 */
public final class PlugCommand {
    private PlugCommand() {
    }

    /** 一个插件族：名字（= id 前缀）、注册表、是否需要把 all=… 展开成逐个 id（自己的解析器不认 all 的族）。 */
    public static final class Family {
        public final String name;
        public final PlugRegistry reg;
        public final boolean expandAll;

        public Family(String name, PlugRegistry reg, boolean expandAll) {
            this.name = name;
            this.reg = reg;
            this.expandAll = expandAll;
        }
    }

    /** 分派结果：族名 → 该族要执行的子命令（逗号分隔）；errors 是在分派层就拒绝的项（重名 / 看不懂 / all=on）。 */
    public static final class Routed {
        public final Map<String, String> parts = new LinkedHashMap<String, String>();
        public final List<String> errors = new ArrayList<String>();

        void add(String fam, String item) {
            String old = parts.get(fam);
            parts.put(fam, old == null || old.length() == 0 ? item : old + "," + item);
        }

        public String part(String fam) {
            String s = parts.get(fam);
            return s == null ? "" : s;
        }
    }

    public static Routed route(String arg, List<Family> fams) {
        Routed r = new Routed();
        for (Family f : fams) r.parts.put(f.name, "");
        if (arg == null) return r;
        for (String raw : arg.split(",")) {
            String t = raw.trim();
            if (t.length() == 0 || "list".equalsIgnoreCase(t)) continue;
            int eq = t.indexOf('=');
            if (eq <= 0) {
                r.errors.add("看不懂：" + t + "（写成 id=on|off|default）");
                continue;
            }
            String id = t.substring(0, eq).trim();
            String v = t.substring(eq + 1).trim();
            String vl = v.toLowerCase(Locale.ROOT);
            if ("all".equals(id)) {
                if (!"off".equals(vl) && !"false".equals(vl) && !"0".equals(vl) && !"default".equals(vl) && !"reset".equals(vl)) {
                    r.errors.add("不许一键全开：" + t + "（只支持 all=off / all=default）");
                    continue;
                }
                for (Family f : fams) {
                    if (f.expandAll) {
                        for (PlugSpec s : f.reg.specs()) r.add(f.name, s.id + "=" + v);
                    } else {
                        r.add(f.name, t);
                    }
                }
                continue;
            }
            Family owner = null;
            for (Family f : fams) {
                if (f.reg.spec(id) != null) { owner = f; break; }
            }
            if (owner == null) {
                for (Family f : fams) {
                    if (id.equals(f.name) || id.startsWith(f.name + ".")) { owner = f; break; }
                }
            }
            if (owner != null) {
                r.add(owner.name, id + "=" + v);
                continue;
            }
            if (id.indexOf('.') < 0) {
                List<Family> hit = new ArrayList<Family>();
                for (Family f : fams) if (f.reg.spec(f.name + "." + id) != null) hit.add(f);
                if (hit.size() == 1) {
                    r.add(hit.get(0).name, hit.get(0).name + "." + id + "=" + v);
                    continue;
                }
                if (hit.size() > 1) {
                    StringBuilder b = new StringBuilder("重名：「" + id + "」同时是 ");
                    for (int i = 0; i < hit.size(); i++) b.append(i > 0 ? " / " : "").append(hit.get(i).name).append('.').append(id);
                    r.errors.add(b.append("，请写全名").toString());
                    continue;
                }
            }
            r.errors.add("看不懂：" + t + "（没有这个插件 id）");
        }
        return r;
    }

    /** 各族之间会重名的短名（JVM 测试断言为空；以后新加插件撞名会立刻红）。也检查完整 id 跨族重复。 */
    public static Set<String> collisions(List<Family> fams) {
        Set<String> out = new LinkedHashSet<String>();
        Map<String, String> shortOwner = new LinkedHashMap<String, String>();
        Map<String, String> fullOwner = new LinkedHashMap<String, String>();
        for (Family f : fams) {
            for (PlugSpec s : f.reg.specs()) {
                String prevFull = fullOwner.put(s.id, f.name);
                if (prevFull != null && !prevFull.equals(f.name)) out.add(s.id + "（完整 id：" + prevFull + " / " + f.name + "）");
                if (s.id.startsWith(f.name + ".")) {
                    String sh = s.id.substring(f.name.length() + 1);
                    if (sh.indexOf('.') >= 0) continue;
                    String prev = shortOwner.put(sh, f.name);
                    if (prev != null && !prev.equals(f.name)) out.add(sh + "（短名：" + prev + " / " + f.name + "）");
                }
            }
        }
        // 短名与别族的完整 id 撞（如某族短名 skillpack 与 mika 族的完整 id skillpack）
        for (Map.Entry<String, String> e : shortOwner.entrySet()) {
            String fo = fullOwner.get(e.getKey());
            if (fo != null && !fo.equals(e.getValue())) out.add(e.getKey() + "（短名 " + e.getValue() + " / 完整 id " + fo + "）");
        }
        return out;
    }
}
