package com.onesay.assistant.v2.executor;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

/**
 * exec 守护 v4 的 App 侧协议（2026-10-25 渗透测试修复·集成，配套 tools/kdaemon/exec_daemon.v4.sh / fix-root.md §4）。
 * 纯 Java，JVM 可测；{@link ShellExecutor} 只负责文件往返。
 *
 * <ul>
 *   <li><b>分词</b>（{@link #tokenize}）：与守护 {@code tokenize()} 逐字同口径——单/双引号、空白分隔、{@code |} 单独成词；
 *       遇到 {@code ; & > < ` $ ( ) \ 换行}（或双引号里的 {@code $ ` \}）返回 null。先按守护的
 *       {@code strip_devnull} 去掉无害的 {@code 2>/dev/null} 一类。分得开的命令才走 v4 结构化 argv 请求；
 *       守护固定模板（{@link #FIXED_TEMPLATES}）走签名的 {@code act=tmpl}（守护 v4.1 G1）；分不开的过渡期遗留复合脚本
 *       仍按 legacy 明文发，由守护判定。</li>
 *   <li><b>HMAC</b>（{@link #mac}）：{@code mac = hex(SHA256(key ‖ hex(SHA256(key ‖ payload))))}。与守护 {@code mac_of()} 同构：
 *       守护里 key 是 {@code $(cat key.<pkg>)} 的文本（64 位 hex，去末尾换行），内层摘要以 <b>hex 文本</b>参与外层拼接；
 *       全部按 UTF-8 字节。payload = 除 mac 行外的全部行，按 {@code \n} 连接、无末尾换行。</li>
 *   <li><b>请求格式</b>（{@link #buildSigned}）：{@code #bixby-exec v4 / ts= / nonce= / act=argv / a=… / mac=}；
 *       固定模板（{@link #buildSignedTemplate}）为 {@code act=tmpl} + 恰好一行 {@code a=<模板全文>}。
 *       守护按换行切行且会吞掉空行，所以 argv 里不得含换行（含则退回 legacy）。</li>
 *   <li><b>拒绝码</b>：守护拒绝时写 {@code rc=77} + {@code DENY <原因>}（{@link #isDenied}）。</li>
 *   <li><b>原子写</b>（{@link #writeRequest}）：先写 {@code .bixby_req.tmp} 再 rename 成 {@code .bixby_req}（ROOT-5），
 *       守护不会读到半条请求。</li>
 * </ul>
 */
public final class ExecProtocol {

    private ExecProtocol() {}

    public static final String HEADER = "#bixby-exec v4";
    public static final String REQ = ".bixby_req";
    public static final String REQ_TMP = ".bixby_req.tmp";
    public static final String KEY_FILE = ".bixby_key";
    /** 守护拒绝码。 */
    public static final int RC_DENIED = 77;

    private static final SecureRandom RNG = new SecureRandom();

    // ------------------------------------------------------------------ 分词（同守护 tokenize / strip_devnull）

    private static final String[] DEVNULL = {
            " 2>/dev/null ", " 1>/dev/null ", " >/dev/null ", " </dev/null ", " 2>&1 ", " 2> /dev/null ", " > /dev/null "};

    /** 同守护 strip_devnull：只去掉以空白分隔的完整单词形式的无害重定向（首尾各补一个空格再替换）。 */
    public static String stripDevNull(String cmd) {
        String in = " " + (cmd == null ? "" : cmd) + " ";
        for (String r : DEVNULL) {
            int k;
            while ((k = in.indexOf(r)) >= 0) {
                in = in.substring(0, k) + " " + in.substring(k + r.length());
            }
        }
        return in;
    }

    /** 分词；含 shell 元字符 / 引号不配对 / 空命令 → null。{@code |} 作为单独的一个元素。 */
    public static List<String> tokenize(String cmd) {
        if (cmd == null) return null;
        String s = stripDevNull(cmd);
        List<String> out = new ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        boolean have = false;
        int st = 0;   // 0 普通 / 1 单引号 / 2 双引号
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (st == 1) {
                if (c == '\'') st = 0; else cur.append(c);
                continue;
            }
            if (st == 2) {
                if (c == '"') st = 0;
                else if (c == '$' || c == '`' || c == '\\') return null;
                else cur.append(c);
                continue;
            }
            switch (c) {
                case ' ':
                case '\t':
                    if (have) { out.add(cur.toString()); cur.setLength(0); have = false; }
                    break;
                case '\'': st = 1; have = true; break;
                case '"': st = 2; have = true; break;
                case '|':
                    if (have) { out.add(cur.toString()); cur.setLength(0); have = false; }
                    out.add("|");
                    break;
                case ';': case '&': case '>': case '<': case '`': case '$': case '(': case ')': case '\\': case '\n':
                    return null;
                default:
                    cur.append(c);
                    have = true;
            }
        }
        if (st != 0) return null;
        if (have) out.add(cur.toString());
        if (out.isEmpty()) return null;
        // 守护按行解析请求：任何元素含 CR/LF 都不能走结构化（双引号里的换行上面已拒；这里兜单引号里的）
        for (String a : out) if (a.indexOf('\n') >= 0 || a.indexOf('\r') >= 0) return null;
        return out;
    }

    // ------------------------------------------------------------------ 简单命令链自动拆分

    /**
     * 守护 v4 的固定模板（tools/kdaemon/exec_daemon.v4.sh {@code fixed_template()}，逐字匹配；CI 用判定模式核对两边一致）。
     * 这些必须<b>原样整串</b>发，不能被 {@link #splitChain} 拆开（拆开后 {@code am broadcast} 无 -n 会被拒）。
     */
    public static final String[] FIXED_TEMPLATES = {
            "settings put global airplane_mode_on 1 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state true",
            "settings put global airplane_mode_on 0 && am broadcast -a android.intent.action.AIRPLANE_MODE --ez state false",
            "settings put secure location_providers_allowed +gps; settings put secure location_providers_allowed +network",
            "settings put secure location_providers_allowed -gps; settings put secure location_providers_allowed -network",
            "echo 1 > /sys/class/leds/torch-sec1/brightness",
            "echo 0 > /sys/class/leds/torch-sec1/brightness",
            "reboot",
            "reboot -p",
            // 守护 v4.1 G7：控制台按键守护状态（守护内建只读实现）
            com.onesay.assistant.v2.ui.console.ConsoleStatus.DAEMON_PROBE,
            // ★ r2/fast-screen：守护 v4.3 内建模板，拉起常驻 root 读屏助手（守护自己拼命令，不带参数）
            com.onesay.assistant.v2.fastscreen.FsProtocol.START_TEMPLATE,
            // ★ fix/triage-fast：守护 v4.4 内建模板，清空后台（回桌面 → am stack remove 1–4 → am kill-all；不带参数）
            com.onesay.assistant.v2.executor.backend.RecentsClear.DAEMON_TEMPLATE};

    public static boolean isFixedTemplate(String cmd) {
        for (String t : FIXED_TEMPLATES) if (t.equals(cmd)) return true;
        return false;
    }

    /**
     * 兜底兼容层：只由 {@code &&} / {@code ;} 连起来、每一段都能 {@link #tokenize} 的<b>简单命令链</b>
     * （如 {@code stat -c %u F && cat F}），拆成 [连接符, 段] 列表，由 {@link ShellExecutor} 在同一把 IPC 锁里依次单条执行。
     * 第一段的连接符为 {@code ;}。含 {@code || & ( ) > < $ `}、引号不配对、只有一段、或是守护固定模板 → null（原样发）。
     * 显式拆分过的调用点（ShellScript / ShellPrograms）不依赖这里；这里是防漏网的安全网。
     */
    public static List<String[]> splitChain(String cmd) {
        if (cmd == null || isFixedTemplate(cmd)) return null;
        List<String[]> out = new ArrayList<String[]>();
        StringBuilder cur = new StringBuilder();
        String op = ";";
        int st = 0;
        for (int i = 0; i < cmd.length(); i++) {
            char c = cmd.charAt(i);
            if (st == 1) { cur.append(c); if (c == '\'') st = 0; continue; }
            if (st == 2) { cur.append(c); if (c == '"') st = 0; continue; }
            if (c == '\'') { st = 1; cur.append(c); continue; }
            if (c == '"') { st = 2; cur.append(c); continue; }
            if (c == ';') {
                if (!addSeg(out, op, cur)) return null;
                op = ";";
                continue;
            }
            if (c == '&') {
                if (i + 1 < cmd.length() && cmd.charAt(i + 1) == '&') {
                    if (!addSeg(out, op, cur)) return null;
                    op = "&&";
                    i++;
                    continue;
                }
                // 单个 &：可能是 2>&1 这类重定向的一部分，交给分词判断；其它情况分词会拒
            }
            if (c == '|' && i + 1 < cmd.length() && cmd.charAt(i + 1) == '|') return null;
            cur.append(c);
        }
        if (st != 0) return null;
        if (cur.toString().trim().length() > 0) {
            if (!addSeg(out, op, cur)) return null;
        } else if (";".equals(op) && !out.isEmpty()) {
            // 末尾的「;」
        } else {
            return null;   // 以 && 结尾
        }
        if (out.size() < 2) return null;
        for (String[] s : out) {
            if (isLocal(s[1])) continue;
            if (tokenize(s[1]) == null) return null;
        }
        return out;
    }

    private static boolean addSeg(List<String[]> out, String op, StringBuilder cur) {
        String s = cur.toString().trim();
        cur.setLength(0);
        if (s.length() == 0) return false;
        out.add(new String[]{op, s});
        return true;
    }

    /** 链里在本地就能算出结果的段：{@code true} / {@code :} / {@code false} / 不带管道的 {@code echo …}。 */
    public static boolean isLocal(String seg) {
        String s = seg.trim();
        if (s.equals("true") || s.equals(":") || s.equals("false")) return true;
        List<String> t = tokenize(s);
        if (t == null || t.size() < 1 || !"echo".equals(t.get(0)) || t.contains("|")) return false;
        for (int i = 1; i < t.size(); i++) if (t.get(i).matches("-[neE]+") || t.get(i).indexOf('\\') >= 0) return false;   // echo -n / 转义交给真 echo
        return true;
    }

    /** 本地段的结果：{rc, 输出}。 */
    public static String[] runLocal(String seg) {
        String s = seg.trim();
        if (s.equals("true") || s.equals(":")) return new String[]{"0", ""};
        if (s.equals("false")) return new String[]{"1", ""};
        List<String> t = tokenize(s);
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i < t.size(); i++) {
            if (i > 1) sb.append(' ');
            sb.append(t.get(i));
        }
        return new String[]{"0", sb.toString()};
    }

    // ------------------------------------------------------------------ HMAC

    public static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(64);
            for (byte b : d) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 守护 mac_of：SHA256( key ‖ SHA256( key ‖ payload ) )，内层以 hex 文本参与拼接，输出小写 hex。 */
    public static String mac(String key, String payload) {
        String inner = sha256Hex(key + payload);
        return sha256Hex(key + inner);
    }

    /** 8-64 位字母数字（守护 is_nonce）。这里取 32 位 hex。 */
    public static String newNonce() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        return sb.toString();
    }

    /** 签名部分（除 mac 行外），无末尾换行。 */
    public static String payload(List<String> argv, long ts, String nonce) {
        StringBuilder sb = new StringBuilder(HEADER);
        sb.append("\nts=").append(ts);
        sb.append("\nnonce=").append(nonce);
        sb.append("\nact=argv");
        for (String a : argv) sb.append("\na=").append(a);
        return sb.toString();
    }

    /** 签名的固定模板请求：act=tmpl + 恰好一行 a=<模板全文>（守护 v4.1 parse_request 的 tmpl 分支）。 */
    public static String buildSignedTemplate(String template, String key, long ts, String nonce) {
        String p = HEADER + "\nts=" + ts + "\nnonce=" + nonce + "\nact=tmpl\na=" + template;
        return p + "\nmac=" + mac(key, p);
    }

    /** 完整 v4 请求（payload + mac 行），无末尾换行（守护用 $(cat) 读，本来也会吞掉）。 */
    public static String buildSigned(List<String> argv, String key, long ts, String nonce) {
        String p = payload(argv, ts, nonce);
        return p + "\nmac=" + mac(key, p);
    }

    /**
     * 决定这条命令发什么：没有密钥 → 原样明文；固定模板 → 签名 {@code act=tmpl}（需守护 v4.1，v4.0 会拒 ACT_UNKNOWN，
     * 部署顺序见 fix-root2.md §5）；分得开 → v4 结构化签名 argv 请求；分不开 → 原样明文（守护 warn 模式下仍接受；
     * {@code require_mac} 打开后会被拒，见 INTEGRATION.md）。
     */
    public static String requestBody(String command, String key, long tsSec) {
        if (key == null || key.length() == 0) return command;
        // 守护 v4.1（fix-root2 G1）：固定模板走签名的 act=tmpl（恰好一行 a=<模板全文>），require_mac 下照常执行。
        if (isFixedTemplate(command)) return buildSignedTemplate(command, key, tsSec, newNonce());
        List<String> argv = tokenize(command);
        if (argv == null) return command;
        return buildSigned(argv, key, tsSec, newNonce());
    }

    /** 读守护交付的密钥（{@code files/.bixby_key}）：同 shell 的 {@code $(cat)}，去掉末尾换行；读不到 / 是链接 / 过大 → null。 */
    public static String readKey(File filesDir) {
        if (filesDir == null) return null;
        File f = new File(filesDir, KEY_FILE);
        try {
            if (!f.isFile() || f.length() == 0 || f.length() > 4096) return null;
            // 符号链接不认（只比文件这一级：/data/user/0 本身就是指向 /data/data 的链接，不能拿整条路径比）
            File parent = filesDir.getCanonicalFile();
            if (!f.getCanonicalPath().equals(new File(parent, KEY_FILE).getPath())) return null;
            FileInputStream in = new FileInputStream(f);
            try {
                ByteArrayOutputStream bo = new ByteArrayOutputStream();
                byte[] b = new byte[512];
                int n;
                while ((n = in.read(b)) > 0) bo.write(b, 0, n);
                String k = new String(bo.toByteArray(), "UTF-8");
                int end = k.length();
                while (end > 0 && k.charAt(end - 1) == '\n') end--;
                k = k.substring(0, end);
                return k.length() == 0 ? null : k;
            } finally {
                in.close();
            }
        } catch (Throwable t) {
            return null;
        }
    }

    // ------------------------------------------------------------------ 原子写

    /** 先写 {@code .bixby_req.tmp} 再 rename 成 {@code .bixby_req}。rename 失败才退回直接写（并返回 false 供记日志）。 */
    public static boolean writeRequest(File dir, String body) throws Exception {
        File tmp = new File(dir, REQ_TMP);
        File req = new File(dir, REQ);
        byte[] data = body.getBytes("UTF-8");
        try {
            FileOutputStream fos = new FileOutputStream(tmp, false);
            try {
                fos.write(data);
                fos.flush();
                try { fos.getFD().sync(); } catch (Throwable ignored) { }
            } finally {
                fos.close();
            }
            if (tmp.renameTo(req)) return true;
        } catch (Throwable ignored) {
        }
        tmp.delete();
        FileOutputStream fos = new FileOutputStream(req, false);
        try {
            fos.write(data);
            fos.flush();
        } finally {
            fos.close();
        }
        return false;
    }

    // ------------------------------------------------------------------ 拒绝码

    /** 守护拒绝：rc=77 且输出以 {@code DENY } 开头（真实程序恰好退出 77 不会带这个前缀）。 */
    public static boolean isDenied(int rc, String out) {
        return rc == RC_DENIED && out != null && out.startsWith("DENY ");
    }

    /** {@code DENY SIG_MISMATCH} → {@code SIG_MISMATCH}。 */
    public static String denyReason(String out) {
        if (out == null || !out.startsWith("DENY ")) return "";
        String r = out.substring(5);
        int nl = r.indexOf('\n');
        return (nl >= 0 ? r.substring(0, nl) : r).trim();
    }

    /**
     * 是不是「命令白名单」一类的拒绝（而不是身份 / 完整性 / raw 开关）。只有这一类在过渡期值得用 legacy 明文重发一次：
     * 守护开着 {@code allow_raw} 时 legacy 才可能被放行（记 WARN LEGACY_RAW），结构化请求不会走这条兜底。
     * 身份、MAC、符号链接、raw 关闭这些拒绝重发也没用（甚至不该重发），一律不重发。
     */
    public static boolean isWhitelistDeny(String reason) {
        if (reason == null || reason.length() == 0) return false;
        String[] noRetry = {"MAC_", "RAW_DISABLED", "SIG_MISMATCH", "UID_MISMATCH", "REVOKED_CERT", "PIN_MISMATCH", "NO_IDENTITY",
                "MULTI_CERT", "BAD_OWNER", "SYMLINK", "STAT_FAIL", "NO_FILES_DIR", "FILESDIR_OWNED_BY_ROOT",
                "PKGDIR_UID_MISMATCH", "TAKE_FAIL", "ACT_UNKNOWN", "OTHER_APP_DATA", "TMPL_"};
        for (String p : noRetry) if (reason.startsWith(p)) return false;
        return true;
    }

    /** 给用户 / 模型看的拒绝说明（不混进普通「命令失败」）。 */
    public static String describeDeny(String reason) {
        String r = reason == null ? "" : reason;
        String why;
        if (r.startsWith("MAC_")) why = "请求签名校验未通过";
        else if (r.startsWith("SIG_") || r.startsWith("REVOKED") || r.startsWith("UID_") || r.startsWith("PIN_")
                || r.startsWith("NO_IDENTITY") || r.startsWith("MULTI_CERT") || r.startsWith("PKGDIR") || r.startsWith("FILESDIR"))
            why = "调用方身份 / 签名校验未通过";
        else if (r.startsWith("RAW_DISABLED")) why = "复合脚本已被禁止";
        else why = "命令不在安全白名单内";
        return "root 守护拒绝执行（" + why + "：" + r + "）";
    }
}
