package com.onesay.desktop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * [adv-1114] 找茬式对抗测试的公共工具（只给 Adv*Test 用，不是用例）。
 *
 * <ul>
 *   <li>{@link Rows}：参数化用例的行表。每行一个「输入签名」，重复的签名直接抛异常（类初始化失败 = 用例失败），
 *       保证不会同一个输入换名凑数。</li>
 *   <li>随机都用固定种子（{@link #rng}），每次跑出的行完全相同。</li>
 * </ul>
 */
final class AdvSupport {
    private AdvSupport() { }

    /** 固定种子：同一个 salt 每次得到同一串随机数。 */
    static Random rng(String salt) {
        return new Random(0x1114_2026L ^ salt.hashCode());
    }

    /** 参数化行表：第 0 列是显示名，key 是去重用的输入签名。 */
    static final class Rows {
        private final Map<String, Object[]> rows = new LinkedHashMap<>();
        private final String what;

        Rows(String what) { this.what = what; }

        /** 加一行；key 重复就抛（不许同一个输入出现两次）。 */
        Rows add(String key, Object... cols) {
            if (rows.containsKey(key)) throw new IllegalStateException(what + " 有重复输入：" + key);
            Object[] r = new Object[cols.length + 1];
            r[0] = label(key);
            System.arraycopy(cols, 0, r, 1, cols.length);
            rows.put(key, r);
            return this;
        }

        /** 同一个输入经不同变换后可能撞上（比如随机大小写恰好等于原文）：撞上的只留第一行，不算新用例。 */
        boolean addIfNew(String key, Object... cols) {
            if (rows.containsKey(key)) return false;
            add(key, cols);
            return true;
        }

        int size() { return rows.size(); }

        Collection<Object[]> get() {
            if (rows.isEmpty()) throw new IllegalStateException(what + " 没有用例");
            return new ArrayList<>(rows.values());
        }
    }

    /** 显示名：去掉换行 / 控制字符，太长截断（JUnit 的测试名）。 */
    static String label(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length() && b.length() < 120; i++) {
            char c = s.charAt(i);
            if (c < 0x20 || c == 0x7f) b.append(String.format("\\x%02x", (int) c));
            else if (c == '[' || c == ']') b.append(c == '[' ? '⟦' : '⟧'); // 方括号会搅乱 JUnit 的参数名显示
            else b.append(c);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ 变形（找茬）

    /** 固定种子的随机大小写。 */
    static String randomCase(String s, Random r) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) b.append(r.nextBoolean() ? Character.toUpperCase(c) : Character.toLowerCase(c));
        return b.toString();
    }

    /** cmd 的 ^ 转义：在字母之间插 ^（cmd 解析时会去掉）。 */
    static String caret(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c);
            if (Character.isLetter(c) && i + 1 < s.length() && Character.isLetter(s.charAt(i + 1)) && i % 2 == 0) b.append('^');
        }
        return b.toString();
    }

    /** PowerShell 的反引号转义：在字母前插 `（避开 `n `r `t 这几个特殊转义）。 */
    static String backtick(String s) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            char lc = Character.toLowerCase(c);
            if (i > 0 && i % 3 == 1 && Character.isLetter(c) && lc != 'n' && lc != 'r' && lc != 't' && lc != 'a' && lc != 'b' && lc != 'f' && lc != 'v' && lc != 'e' && lc != '0' && lc != 'u')
                b.append('`');
            b.append(c);
        }
        return b.toString();
    }

    /** powershell -EncodedCommand 用的 base64（UTF-16LE）。 */
    static String b64ps(String s) {
        return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_16LE));
    }

    static Map<String, Object> m(Object... kv) {
        Map<String, Object> x = new TreeMap<>();
        for (int i = 0; i < kv.length; i += 2) x.put((String) kv[i], kv[i + 1]);
        return x;
    }

    static String reason(PcGuard.Outcome o) {
        return o.reply == null ? "" : String.valueOf(o.reply.data.get("reason"));
    }

    // ------------------------------------------------------------------ 配置 / 守卫

    static final LinkPolicy.Sandbox[] SANDBOXES = LinkPolicy.Sandbox.values();
    static final LinkPolicy.Approval[] APPROVALS = LinkPolicy.Approval.values();

    static List<LinkPolicy.Mode> allModes() {
        List<LinkPolicy.Mode> out = new ArrayList<>();
        for (LinkPolicy.Sandbox s : SANDBOXES) for (LinkPolicy.Approval a : APPROVALS) out.add(new LinkPolicy.Mode(s, a));
        return out;
    }

    /** 所有能记住的动作类型（KINDS 表里的 22 个）。 */
    static final String[] ALL_KINDS = {"pc.setting", "pc.power", "pc.uninstall", "pc.script_run", "pc.shell_exec", "pc.outside_write", "pc.url",
            "pc.url.risky", "pc.clipboard", "pc.clipboard.command", "pc.clipboard.sensitive", "pc.blocked_app.open", "pc.blocked_app.uninstall",
            "pc.blocked_app.operate", "pc.system_app", "pc.workspace.protected", "phone.blocked_app.open", "phone.blocked_app.uninstall",
            "phone.system_app", "phone.url.risky", "pc.media_download", "pc.media_tool_install"};

    /** 一份临时配置（工作区、快捷指令文件夹都在临时目录里）。 */
    static Config cfg(LinkPolicy.Mode mode) throws Exception {
        Path dir = Files.createTempDirectory("adv");
        Config c = new Config(dir.resolve("desktop.properties"));
        c.deviceId = "pc-adv";
        c.pairKey = new byte[32];
        for (int i = 0; i < 32; i++) c.pairKey[i] = (byte) (i * 7 + 3);
        File ws = dir.resolve("workspace").toFile();
        ws.mkdirs();
        c.workspaceDir = ws.getCanonicalPath();
        File sc = dir.resolve("shortcuts").toFile();
        sc.mkdirs();
        c.scriptsDir = sc.getCanonicalPath();
        c.setMode(mode);
        return c;
    }

    /** 记下每次确认的桩：返回 answer；throwIt 时抛异常。 */
    static final class Stub implements PcGuard.Confirmer {
        volatile int answer = PcGuard.DENY;
        volatile boolean throwIt;
        final List<String> asked = new ArrayList<>();
        final List<String> kinds = new ArrayList<>();
        final List<Boolean> offers = new ArrayList<>();

        Stub() { }

        Stub(int answer) { this.answer = answer; }

        @Override public synchronized int ask(String from, String cmd, String detail, long timeoutMs) {
            return ask(from, cmd, detail, timeoutMs, false, "");
        }

        @Override public synchronized int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways) {
            return ask(from, cmd, detail, timeoutMs, offerAlways, "");
        }

        @Override public synchronized int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways, String rememberKind) {
            if (timeoutMs != LinkPolicy.CONFIRM_TIMEOUT_MS) throw new AssertionError("确认超时应是 60 秒，实际 " + timeoutMs);
            asked.add(cmd + "|" + detail);
            kinds.add(rememberKind == null ? "" : rememberKind);
            offers.add(offerAlways);
            if (throwIt) throw new IllegalStateException("确认框坏了");
            return answer;
        }
    }

    static String lower(String s) { return s.toLowerCase(Locale.ROOT); }
}
