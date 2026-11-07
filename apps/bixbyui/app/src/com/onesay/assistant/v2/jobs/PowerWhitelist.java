package com.onesay.assistant.v2.jobs;

import org.json.JSONObject;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * root 省电白名单（lane-a 第 7 项）。纯 Java，JVM 可测；Android 一侧 {@link PowerWhitelistHooks} 负责执行和存原状态。
 *
 * <ul>
 *   <li><b>只在 debug 变体生效</b>，默认开（Hooks 用 {@code BuildVariant.IS_DEBUG} 编译期常量把关，release 包里这段永远不执行）；</li>
 *   <li>加白名单前先探测并<b>记录原状态</b>（{@link Original}）：Doze 白名单里有没有本包（user / system 两类分开记）、各后台 appops 的原模式；</li>
 *   <li>一键还原（{@link #restoreCmd}）只撤销我们改过的：原来就在 user 白名单里的不移除，appops 恢复成记录的原模式；</li>
 *   <li>实现：{@code dumpsys deviceidle whitelist +/-pkg} + {@code cmd appops set}。
 *       <b>Android 8 兼容</b>：8.0 / 8.1 只有 RUN_IN_BACKGROUND（RUN_ANY_IN_BACKGROUND 是 Android 9 才有的，8 上写它会报 Unknown operation）；
 *       个别 ROM 没有 {@code cmd appops} 服务入口 → 每条 appops 命令带 {@code || appops …} 兜底；
 *       8.x 的 {@code appops get} 没设过时输出「No operations.」→ 记为 default。</li>
 * </ul>
 * 命令只由常量 + 校验过的包名 + 白名单内的模式拼成，不含外部输入。
 */
public final class PowerWhitelist {

    private PowerWhitelist() {}

    public static final String SEP = "---bixby-pw-sep---";
    static final String[] MODES = {"allow", "ignore", "deny", "default", "foreground"};

    /** 这个系统版本要改的后台 appops。 */
    public static String[] ops(int sdk) {
        return sdk >= 28 ? new String[]{"RUN_IN_BACKGROUND", "RUN_ANY_IN_BACKGROUND"} : new String[]{"RUN_IN_BACKGROUND"};
    }

    static String pkg(String pkg) {
        if (pkg == null || !ReliabilityCheck.PKG.matcher(pkg).matches()) throw new IllegalArgumentException("pkg");
        return pkg;
    }

    static String appops(String sub) {
        return "(cmd appops " + sub + " 2>/dev/null || appops " + sub + ")";
    }

    // ---------------------------------------------------------------- 2026-10-25：exec 守护 v4 拆分执行
    // v4 守护不再执行含 ; / || / ( ) 的复合脚本（fix-root.md §3）。下面的 *Steps 是<b>真正执行</b>的形式：
    // 每一步都是单条白名单命令（dumpsys deviceidle whitelist [+-]pkg、cmd appops get/set …），分隔符改为本地 echo。
    // 原来的 *Cmd 字符串保留作可读描述（旧单测按它核对语义），Hooks 不再执行它们。

    private static com.onesay.assistant.v2.executor.ShellScript.Step appopsStep(String sub) {
        // 「cmd appops … 2>/dev/null || appops …」：个别 ROM 没有 cmd appops 才走兜底。注意 v4 白名单里没有独立的 appops
        //   程序，兜底在 v4 下会被拒（Note8 / Android 9 有 cmd appops，不受影响），见 INTEGRATION.md。
        return com.onesay.assistant.v2.executor.ShellScript.run("cmd appops " + sub).orElse("appops " + sub);
    }

    public static java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> probeSteps(String pkg, int sdk) {
        String p = pkg(pkg);
        java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> l = new java.util.ArrayList<com.onesay.assistant.v2.executor.ShellScript.Step>();
        l.add(com.onesay.assistant.v2.executor.ShellScript.run("dumpsys deviceidle whitelist"));
        for (String op : ops(sdk)) {
            l.add(com.onesay.assistant.v2.executor.ShellScript.echo(SEP));
            l.add(appopsStep("get " + p + " " + op));
        }
        return l;
    }

    public static java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> applySteps(Original o) {
        String p = pkg(o.pkg);
        java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> l = new java.util.ArrayList<com.onesay.assistant.v2.executor.ShellScript.Step>();
        if (!o.dozeUser && !o.dozeSystem) l.add(com.onesay.assistant.v2.executor.ShellScript.run("dumpsys deviceidle whitelist +" + p));
        for (String op : ops(o.sdk)) {
            if ("allow".equals(o.modes.get(op))) continue;
            l.add(appopsStep("set " + p + " " + op + " allow"));
        }
        return l;
    }

    public static java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> restoreSteps(Original o) {
        String p = pkg(o.pkg);
        java.util.List<com.onesay.assistant.v2.executor.ShellScript.Step> l = new java.util.ArrayList<com.onesay.assistant.v2.executor.ShellScript.Step>();
        if (!o.dozeUser && !o.dozeSystem) l.add(com.onesay.assistant.v2.executor.ShellScript.run("dumpsys deviceidle whitelist -" + p));
        for (Map.Entry<String, String> e : o.modes.entrySet()) {
            String op = e.getKey();
            if (!op.equals("RUN_IN_BACKGROUND") && !op.equals("RUN_ANY_IN_BACKGROUND")) continue;
            if (o.sdk < 28 && op.equals("RUN_ANY_IN_BACKGROUND")) continue;
            if ("allow".equals(e.getValue())) continue;
            l.add(appopsStep("set " + p + " " + op + " " + mode(e.getValue())));
        }
        return l;
    }

    /** 探测：Doze 白名单 + 每个 op 的当前模式，用 {@link #SEP} 分段。（可读描述；执行用 {@link #probeSteps}） */
    public static String probeCmd(String pkg, int sdk) {
        String p = pkg(pkg);
        StringBuilder sb = new StringBuilder("dumpsys deviceidle whitelist");
        for (String op : ops(sdk)) sb.append("; echo ").append(SEP).append("; ").append(appops("get " + p + " " + op));
        return sb.toString();
    }

    /** 原状态。 */
    public static final class Original {
        public String pkg = "";
        public int sdk;
        /** 原来就在 user 白名单里。 */
        public boolean dozeUser;
        /** 系统白名单（system / system-excidle）里有：动不了，也不需要动。 */
        public boolean dozeSystem;
        public final Map<String, String> modes = new LinkedHashMap<String, String>();
        public long at;

        public String toJson() {
            try {
                JSONObject o = new JSONObject();
                o.put("pkg", pkg);
                o.put("sdk", sdk);
                o.put("dozeUser", dozeUser);
                o.put("dozeSystem", dozeSystem);
                o.put("modes", new JSONObject(modes));
                o.put("at", at);
                return o.toString();
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }

        public static Original fromJson(String s) {
            if (s == null || s.length() == 0) return null;
            try {
                JSONObject o = new JSONObject(s);
                Original r = new Original();
                r.pkg = o.optString("pkg", "");
                r.sdk = o.optInt("sdk", 26);
                r.dozeUser = o.optBoolean("dozeUser", false);
                r.dozeSystem = o.optBoolean("dozeSystem", false);
                r.at = o.optLong("at", 0L);
                JSONObject m = o.optJSONObject("modes");
                if (m != null) {
                    java.util.Iterator<String> it = m.keys();
                    while (it.hasNext()) {
                        String k = it.next();
                        r.modes.put(k, mode(m.optString(k, "default")));
                    }
                }
                return r;
            } catch (Exception e) {
                return null;
            }
        }
    }

    static String mode(String m) {
        String v = m == null ? "" : m.trim().toLowerCase(Locale.ROOT);
        for (String x : MODES) if (x.equals(v)) return x;
        return "default";
    }

    /** 解析探测输出；读不到（守护没起来 / 输出不完整）返回 null —— 没有原状态就绝不改。 */
    public static Original parse(String out, String pkg, int sdk, long now) {
        if (out == null) return null;
        String[] parts = out.split(java.util.regex.Pattern.quote(SEP), -1);
        String[] ops = ops(sdk);
        if (parts.length != ops.length + 1) return null;
        Original o = new Original();
        o.pkg = pkg(pkg);
        o.sdk = sdk;
        o.at = now;
        for (String line : parts[0].split("\n")) {
            String[] f = line.trim().split(",");
            if (f.length >= 2 && f[1].trim().equals(pkg)) {
                String kind = f[0].trim();
                if (kind.equals("user")) o.dozeUser = true;
                else if (kind.startsWith("system")) o.dozeSystem = true;
            }
        }
        for (int i = 0; i < ops.length; i++) {
            String m = opMode(parts[i + 1], ops[i]);
            if (m == null) return null;
            o.modes.put(ops[i], m);
        }
        return o;
    }

    /** 「RUN_IN_BACKGROUND: allow; time=…」→ allow；「No operations.」→ default；报错 / 认不出 → null。 */
    static String opMode(String out, String op) {
        if (out == null) return null;
        String s = out.trim();
        String low = s.toLowerCase(Locale.ROOT);
        if (low.contains("no operations")) return "default";
        if (low.contains("unknown operation") || low.contains("error") || low.contains("exception")) return null;
        int i = s.indexOf(op + ":");
        if (i < 0) return s.length() == 0 ? null : null;
        String v = s.substring(i + op.length() + 1).trim();
        int end = 0;
        while (end < v.length() && Character.isLetter(v.charAt(end))) end++;
        String m = v.substring(0, end).toLowerCase(Locale.ROOT);
        for (String x : MODES) if (x.equals(m)) return x;
        return null;
    }

    /** 加白名单：只改还不是目标状态的项；什么都不用改返回空串。（可读描述；执行用 {@link #applySteps}） */
    public static String applyCmd(Original o) {
        StringBuilder sb = new StringBuilder();
        String p = pkg(o.pkg);
        if (!o.dozeUser && !o.dozeSystem) sb.append("dumpsys deviceidle whitelist +").append(p);
        for (String op : ops(o.sdk)) {
            String cur = o.modes.get(op);
            if ("allow".equals(cur)) continue;
            if (sb.length() > 0) sb.append("; ");
            sb.append(appops("set " + p + " " + op + " allow"));
        }
        return sb.toString();
    }

    /** 一键还原：按记录的原状态撤销（原来就在 user 白名单 → 不移除；appops 回到原模式）。（可读描述；执行用 {@link #restoreSteps}） */
    public static String restoreCmd(Original o) {
        StringBuilder sb = new StringBuilder();
        String p = pkg(o.pkg);
        if (!o.dozeUser && !o.dozeSystem) sb.append("dumpsys deviceidle whitelist -").append(p);
        for (Map.Entry<String, String> e : o.modes.entrySet()) {
            String op = e.getKey();
            if (!op.equals("RUN_IN_BACKGROUND") && !op.equals("RUN_ANY_IN_BACKGROUND")) continue;
            if (o.sdk < 28 && op.equals("RUN_ANY_IN_BACKGROUND")) continue;   // Android 8 没有这个 op
            if ("allow".equals(e.getValue())) continue;   // 原来就是 allow，我们没改
            if (sb.length() > 0) sb.append("; ");
            sb.append(appops("set " + p + " " + op + " " + mode(e.getValue())));
        }
        return sb.toString();
    }

    /** 自动加白名单的判定：debug 变体 + 开关开 + 还没有记录过原状态（已经加过就不重复探测 / 覆盖原状态）。 */
    public static boolean shouldAutoApply(boolean isDebugVariant, boolean autoOn, String savedOriginal) {
        return isDebugVariant && autoOn && (savedOriginal == null || savedOriginal.length() == 0);
    }
}
