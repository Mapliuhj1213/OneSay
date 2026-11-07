package com.onesay.assistant.v2.executor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 把原来的复合 shell 脚本（{@code a; b && c || d}）拆成<b>多次单条命令</b>依次发给 root 守护
 * （2026-10-25 渗透测试修复·集成，exec 守护 v4 不再执行含 {@code ; && || > $()} 的命令，见 fix-root.md §3）。
 * 纯 Java，JVM 可测；每一步仍走 {@link ShellRunner#execute}（单条命令、可签名、可被守护白名单判定）。
 *
 * <ul>
 *   <li>{@link #run}：相当于 {@code ;}——不管成败继续下一步；</li>
 *   <li>{@link #must}：相当于 {@code &&}——这一步失败（rc≠0 / 被拒 / 超时）就停，后面的不跑；</li>
 *   <li>{@link #echo}：本地追加一段输出（原脚本里的 {@code echo 分隔符}），<b>不发给守护</b>；</li>
 *   <li>{@link Step#orElse}：相当于 {@code (a 2>/dev/null || b)}——a 失败（含输出里有 {@code Error}，{@code am start} 认不出
 *       Intent 时 rc 仍是 0）才跑 b，结果用 b 的。</li>
 * </ul>
 * 合并结果：各步非空输出以 {@code \n} 连接；rc = 最后执行那一步的 rc；任何一步守护离线立即返回 isDown；
 * 有效结果（orElse 被兜底成功的不算）里出现过守护拒绝则 {@code isDenied} 并带第一条拒绝原因。
 * 与原脚本的差别：步骤之间不再是原子的（各自拿一次 IPC 锁），并行的步骤（{@code screencap & … wait}）改为顺序执行。
 */
public final class ShellScript {

    private ShellScript() {}

    public static final class Step {
        public final String cmd;
        /** 非 null = 本地 echo，不发命令。 */
        public final String local;
        public final boolean required;
        public final String alt;
        /** orElse 的失败判据是否把「输出含 Error」也算失败（am start）。 */
        public final boolean errorText;
        /** 失败（rc≠0 / 被拒 / 超时）时丢掉这一步的输出——相当于原脚本里的 {@code (cmd) 2>/dev/null} 读不到就空着。 */
        public final boolean quiet;

        private Step(String cmd, String local, boolean required, String alt, boolean errorText, boolean quiet) {
            this.cmd = cmd;
            this.local = local;
            this.required = required;
            this.alt = alt;
            this.errorText = errorText;
            this.quiet = quiet;
        }

        /** a 失败才跑 alt（rc≠0 / 被拒 / 超时；输出含 {@code Error} 也算失败）。 */
        public Step orElse(String alt) {
            return new Step(cmd, local, required, alt, true, quiet);
        }

        /** 失败时不要输出（见 {@link #quiet}）。 */
        public Step quiet() {
            return new Step(cmd, local, required, alt, errorText, true);
        }

        @Override public String toString() {
            return local != null ? "echo:" + local : (required ? "&& " : "; ") + cmd + (alt != null ? " || " + alt : "");
        }
    }

    public static Step run(String cmd) {
        return new Step(cmd, null, false, null, false, false);
    }

    public static Step must(String cmd) {
        return new Step(cmd, null, true, null, false, false);
    }

    public static Step echo(String text) {
        return new Step(null, text == null ? "" : text, false, null, false, false);
    }

    /** 这组步骤里真正会发给守护的命令（含 orElse 的兜底命令）。给 CI 白名单判定用。 */
    public static List<String> commands(List<Step> steps) {
        List<String> l = new ArrayList<String>();
        for (Step s : steps) {
            if (s.local != null) continue;
            l.add(s.cmd);
            if (s.alt != null) l.add(s.alt);
        }
        return l;
    }

    static boolean failed(ShellExecutor.ShellResponse r, boolean errorText) {
        if (r == null) return true;
        if (r.isDenied || r.isTimeout || r.isDown || r.rc != 0) return true;
        return errorText && r.output != null && r.output.contains("Error");
    }

    /** device-1102：am start 一类「rc=0 但输出含 Error」也算失败（导航逐级回退用）。 */
    public static boolean failedForNav(ShellExecutor.ShellResponse r) {
        return failed(r, true);
    }

    public static ShellExecutor.ShellResponse exec(ShellRunner sh, Step... steps) {
        return exec(sh, Arrays.asList(steps));
    }

    public static ShellExecutor.ShellResponse exec(ShellRunner sh, List<Step> steps) {
        StringBuilder out = new StringBuilder();
        int rc = 0;
        boolean timeout = false;
        String deny = null;
        for (Step s : steps) {
            if (s.local != null) {
                append(out, s.local);
                continue;
            }
            ShellExecutor.ShellResponse r = sh.execute(s.cmd);
            if (r == null) r = new ShellExecutor.ShellResponse(-1, "", false, true);
            if (r.isDown) {
                append(out, r.output);
                return new ShellExecutor.ShellResponse(r.rc, out.toString(), timeout, true);
            }
            if (s.alt != null && failed(r, s.errorText)) {
                ShellExecutor.ShellResponse a = sh.execute(s.alt);
                if (a == null) a = new ShellExecutor.ShellResponse(-1, "", false, true);
                if (a.isDown) {
                    append(out, a.output);
                    return new ShellExecutor.ShellResponse(a.rc, out.toString(), timeout, true);
                }
                // 兜底也被拒 / 失败时保留首选那一步的输出更有用（例如 cmd appops 的真实报错）
                r = (a.isDenied && !r.isDenied) ? r : a;
            }
            if (!(s.quiet && failed(r, false))) append(out, r.output);
            rc = r.rc;
            timeout |= r.isTimeout;
            if (r.isDenied && deny == null) deny = r.denyReason;
            if (s.required && failed(r, false)) break;
        }
        return new ShellExecutor.ShellResponse(rc, out.toString(), timeout, false, false, deny);
    }

    private static void append(StringBuilder sb, String s) {
        if (s == null || s.length() == 0) return;
        if (sb.length() > 0) sb.append('\n');
        sb.append(s);
    }
}
