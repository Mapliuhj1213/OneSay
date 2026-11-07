package com.onesay.assistant.v2.ui;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * MainActivity 的 {@code page=console:<id>} 深链白名单（纯函数；2026-10-24 r2/fixes 8）。
 *
 * <p>MainActivity 是 LAUNCHER，必须导出，任何 App 都能带这个 extra 拉起它。以前直接把 {@code <id>} 交给
 * {@code Console.openPage}，等于让外部 App 把控制台打开到任意插件页。现在只认本 App 自己会发的页
 * （岛上提醒「回调历史」等，见 NoticeHooks.openConsole 的调用方）；其余一律打开控制台首页。
 * 以后新增会被深链打开的页，在 {@link #ALLOWED} 里登记（ConsoleDeepLinkTest 守住）。
 *
 * <p>安全修复 IPC-3（pentest-20261024/ipc.md §3）：白名单页也不再「任何 App 都能直达」。
 * 本 App 自己发的深链带一个<b>进程内随机口令</b>（{@link #EXTRA_TOKEN}，{@link #token()}，SecureRandom，不落盘、不进日志）；
 * MainActivity 用 {@link #decide} 判定：
 * <ul>
 *   <li>口令对 → 直接打开（{@link Action#OPEN}）；</li>
 *   <li>口令缺失 / 不对 + 敏感配置页（{@link #SENSITIVE}，如 asr.key）→ 先回首页，<b>页内再确认</b>后才打开（{@link Action#CONFIRM}）；</li>
 *   <li>口令缺失 / 不对 + 其它页 → 回首页（{@link Action#HOME}）。</li>
 * </ul>
 * 进程重启后口令会变：重启前发出的深链会按「外部」处理（降级为再确认 / 回首页），不会越权。
 */
public final class ConsoleDeepLink {

    private ConsoleDeepLink() {}

    public static final String PREFIX = "console:";

    /** 允许被深链打开的控制台页 id。 */
    static final Set<String> ALLOWED = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "callback.history",   // NoticeHooks.postCallback 的「回调历史」动作
            "asr.key"            // r2/p0 AsrFailure.CONSOLE_PAGE：唤醒卡「去配置」→ 语音识别 Key 弹窗（integrate 补）
    )));

    /** 敏感配置页：外部（无口令）深链只能经页内再确认才打开。 */
    static final Set<String> SENSITIVE = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "asr.key"
    )));

    /** 本 App 内部深链携带口令的 extra 名。 */
    public static final String EXTRA_TOKEN = "com.onesay.assistant.v2.console_token";

    private static final String TOKEN = newToken();

    private static String newToken() {
        byte[] b = new byte[16];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder sb = new StringBuilder(32);
        for (byte x : b) sb.append(String.format(java.util.Locale.ROOT, "%02x", x & 0xff));
        return sb.toString();
    }

    /** 进程内口令：只给本 App 自己构造的深链 Intent 用（{@code it.putExtra(EXTRA_TOKEN, token())}）。 */
    public static String token() {
        return TOKEN;
    }

    /** 口令比对（定长时间）。 */
    public static boolean tokenOk(String presented) {
        return presented != null && java.security.MessageDigest.isEqual(
                presented.getBytes(java.nio.charset.Charset.forName("UTF-8")),
                TOKEN.getBytes(java.nio.charset.Charset.forName("UTF-8")));
    }

    public enum Action { HOME, OPEN, CONFIRM }

    /** {@link #decide} 的结果：action + 白名单页 id（HOME 时为 null）。 */
    public static final class Decision {
        public final Action action;
        public final String id;

        Decision(Action action, String id) {
            this.action = action;
            this.id = id;
        }
    }

    /** 深链判定（纯函数）：白名单 + 来源口令 + 敏感页再确认。 */
    public static Decision decide(String page, String presentedToken) {
        String id = allowedPage(page);
        if (id == null) return new Decision(Action.HOME, null);
        if (tokenOk(presentedToken)) return new Decision(Action.OPEN, id);
        if (SENSITIVE.contains(id)) return new Decision(Action.CONFIRM, id);
        return new Decision(Action.HOME, null);
    }

    /** 是不是控制台深链（{@code console:} 开头）。 */
    public static boolean isConsoleLink(String page) {
        return page != null && page.startsWith(PREFIX);
    }

    /** @return 白名单里的页 id；不合法（不在白名单 / 空 / 带奇怪字符）返回 null = 打开首页 */
    public static String allowedPage(String page) {
        if (!isConsoleLink(page)) return null;
        String id = page.substring(PREFIX.length()).trim();
        if (id.length() == 0 || id.length() > 64) return null;
        return ALLOWED.contains(id) ? id : null;
    }

    /** 标题只做展示：去控制字符、截到 40 字。 */
    public static String safeTitle(String t) {
        if (t == null) return "";
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < t.length() && b.length() < 40; i++) {
            char c = t.charAt(i);
            if (c >= 0x20 && c != 0x7f) b.append(c);
        }
        return b.toString();
    }
}
