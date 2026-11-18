package com.onesay.desktop;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * link-v2 · 电脑作为被控端：手机（或任何配对设备）发来的每条指令先过这里 —— 执行级别（{@link LinkPolicy}，电脑用严格矩阵）
 * + 网址 / 剪贴板信任检查 + 工作区沙箱。结论：直接执行 / 先确认（电脑本机鼠标 / 键盘，或已配对手机屏幕上确认卡的真人触摸；0.7 秒后才能点「允许」；
 * 60 秒不点、失焦没人点、关窗口、Esc、出错都算拒绝，见 {@link ConfirmGate}）/ 拒绝 / 只在工作区 scripts/ 生成脚本。
 *
 * <p>能批就不拦（2026-11-10）：批了也不行的只有三条硬规则（付款最后一下、自授权 / 自批准、不提权），其余原来关不掉的拦截走确认；
 * 确认框可勾「以后这类不用问」，按动作类型记在配置 approve_kinds（设置页能看、能撤销；远程改不了它）。
 *
 * <p>审批在执行层强制（思路借鉴 DIYgod/pinky，Apache-2.0：审批放在网关 / 执行层，Agent 绕不过；只借思路，代码自己写的）：
 * {@link #check} 放行时才发一张一次性的 {@link Pass}（绑定指令名 + 参数），{@link Agent} 执行前必须用掉它；没有 / 用过 / 对不上的一律不执行。
 *
 * <p>电脑端已有的能力（截图、音量、关机、快捷指令…）和以后新增的都必须在 {@link LinkPolicy#pcCat} 里登记类别，没登记的一律拒绝。
 * A 类（注册表、网络配置、服务 / 驱动）与工作区以外的写入：任何级别都只生成 .ps1 脚本，永远不执行；给自己授权同样只生成脚本。
 *
 * <p>快捷指令（用户在电脑上定义的、快捷指令文件夹里的 .ps1 / .bat / .cmd）单独一类：按「文件名 id + 内容 SHA-256」批准。第一次触发 / 内容改过
 * 在电脑上问「允许 / 拒绝」并可选「以后直接执行」；批准表存在配置里，界面能看、能撤销；内容碰严格规则或文件在远程可写的工作区里的不能批准。
 */
public final class PcGuard {

    public static final long CONFIRM_MS = LinkPolicy.CONFIRM_TIMEOUT_MS;
    /** 确认结果：ALWAYS = 快捷指令「以后直接执行」；REMEMBER = 允许并「以后这类不用问」（按动作类型）。 */
    public static final int ALLOW = 1, DENY = 0, TIMEOUT = -1, ALWAYS = 2, REMEMBER = 3;
    /** [approvals-1118] 「允许本次任务」/「这次运行都允许」（只在本机对话的确认卡上给）。 */
    public static final int TASK_ALLOW = 4, SESSION_ALLOW = 5;
    /** 快捷指令文件太大就不认（正常的快捷指令只有几 KB）。 */
    public static final long SHORTCUT_MAX = 256 * 1024;

    /** 电脑上弹确认（界面对话框 + 右下角通知）。 */
    public interface Confirmer {
        int ask(String from, String cmd, String detail, long timeoutMs);

        /** offerAlways：多给一个「以后直接执行」（快捷指令用），选了返回 {@link #ALWAYS}。 */
        default int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways) {
            return ask(from, cmd, detail, timeoutMs);
        }

        /** rememberKind 非空：确认框多一个「以后这类不用问」勾选，勾了并允许返回 {@link #REMEMBER}。 */
        default int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways, String rememberKind) {
            return ask(from, cmd, detail, timeoutMs, offerAlways);
        }

        /** [approvals-1118] offerTask：多给「允许本次任务」「这次运行都允许」，选了返回 {@link #TASK_ALLOW} / {@link #SESSION_ALLOW}。 */
        default int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways, String rememberKind, boolean offerTask) {
            return ask(from, cmd, detail, timeoutMs, offerAlways, rememberKind);
        }
    }

    /** 发到手机的确认卡（电脑上的这条确认，手机屏幕上也能批）。 */
    public static final class PhoneOffer {
        public final int id;
        public final String from, cmd, detail, token, rememberKind;
        public final boolean always;
        public final long deadline;

        PhoneOffer(int id, String from, String cmd, String detail, String token, boolean always, String rememberKind, long deadline) {
            this.id = id;
            this.from = from;
            this.cmd = cmd;
            this.detail = detail;
            this.token = token;
            this.always = always;
            this.rememberKind = rememberKind == null ? "" : rememberKind;
            this.deadline = deadline;
        }
    }

    /** 一次性执行凭证：只有 {@link #check} 放行时发；绑定指令名 + 参数，用一次就作废。 */
    public static final class Pass {
        private final String cmd;
        private final Map<String, Object> args;
        private final java.util.concurrent.atomic.AtomicBoolean used = new java.util.concurrent.atomic.AtomicBoolean();

        private Pass(String cmd, Map<String, Object> args) {
            this.cmd = cmd;
            this.args = args;
        }

        /** 用掉它：指令名对得上、参数还是判定时那一份（同一个对象，没被换掉）、没用过。 */
        public boolean consume(String c, Map<String, Object> a) {
            return cmd.equals(c) && args == a && used.compareAndSet(false, true);
        }
    }

    /**
     * 界面轮询用的待确认列表。每条带一个 {@link ConfirmGate}：确认框在界面上显示出来约 0.7 秒后「允许」才算数（失焦再回来重新等），
     * 「允许」只认本机鼠标 / 键盘点击；超时、失焦后没人点、界面没动静、窗口关闭、出错都按拒绝。时钟可注入（测试用）。
     */
    public static final class Confirms implements Confirmer {
        private static final int PENDING = Integer.MIN_VALUE;

        private static final class Pending {
            final CountDownLatch latch = new CountDownLatch(1);
            final AtomicInteger res = new AtomicInteger(PENDING);
            final Map<String, Object> info;
            final ConfirmGate gate;

            Pending(Map<String, Object> info, ConfirmGate gate) {
                this.info = info;
                this.gate = gate;
            }
        }

        private final Map<Integer, Pending> pending = new ConcurrentHashMap<>();
        private final AtomicInteger seq = new AtomicInteger(1);
        volatile Consumer<String> onNew = s -> { };
        /** 把这条确认也发到手机（Agent 接上：发到发令的那台手机的常连通道）；发出去了返回 true。 */
        public volatile java.util.function.Predicate<PhoneOffer> phoneOffer = o -> false;
        /** 这条确认结束了（手机上那张卡可以收起）。 */
        public volatile Consumer<PhoneOffer> phoneClose = o -> { };
        private static final java.security.SecureRandom RNG = new java.security.SecureRandom();
        /** 时钟（毫秒）；测试换成假时钟。 */
        public volatile java.util.function.LongSupplier clock = System::currentTimeMillis;
        /** 等待时多久看一次失焦 / 界面没动静。 */
        volatile long tickMs = 100;
        /** 界面最近报的窗口焦点（新弹出的请求按这个起步）。 */
        private volatile boolean windowFocused;

        private long now() { return clock.getAsLong(); }

        @Override public int ask(String from, String cmd, String detail, long timeoutMs) {
            return ask(from, cmd, detail, timeoutMs, false);
        }

        @Override public int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways) {
            return ask(from, cmd, detail, timeoutMs, offerAlways, "");
        }

        @Override public int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways, String rememberKind) {
            return ask(from, cmd, detail, timeoutMs, offerAlways, rememberKind, false);
        }

        @Override public int ask(String from, String cmd, String detail, long timeoutMs, boolean offerAlways, String rememberKind, boolean offerTask) {
            int id = seq.getAndIncrement();
            long t0 = now();
            String rk = rememberKind != null && (LinkPolicy.knownKind(rememberKind) || ChatPolicy.validKey(rememberKind)) ? rememberKind : "";
            Map<String, Object> info = new TreeMap<>();
            info.put("id", (long) id);
            info.put("from", from);
            info.put("cmd", cmd);
            info.put("detail", detail);
            info.put("deadline", t0 + timeoutMs);
            info.put("always", offerAlways);
            info.put("arm_ms", ConfirmGate.ARM_MS);
            info.put("remember_kind", rk);
            info.put("remember_zh", rk.isEmpty() ? "" : ChatPolicy.validKey(rk) ? ChatPolicy.keyZh(rk) : LinkPolicy.kindZh(rk));
            info.put("task_scope", offerTask);
            info.put("task", TASK.get() == null ? "" : TASK.get());
            Pending p = new Pending(info, new ConfirmGate(t0, timeoutMs));
            pending.put(id, p);
            byte[] tk = new byte[18];
            RNG.nextBytes(tk);
            PhoneOffer offer = new PhoneOffer(id, from, cmd, detail, java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(tk), offerAlways, rk, t0 + timeoutMs);
            boolean offered = false;
            try {
                try { onNew.accept(detail); } catch (Throwable ignored) { } // 通知失败不影响：照样等真人点击，等不到就拒
                try {
                    if (phoneOffer.test(offer)) {
                        p.gate.phoneOffered(now(), from, offer.token);
                        offered = true;
                        info.put("phone", true);
                    }
                } catch (Throwable ignored) { } // 发不到手机：照样只在电脑上等
                while (p.res.get() == PENDING) {
                    if (p.latch.await(Math.max(1, tickMs), TimeUnit.MILLISECONDS)) break;
                    String end = p.gate.expired(now());
                    if (end != null) {
                        // 窗口关了 = 拒绝；超时 / 失焦没人点 / 界面没动静 = 没人确认（也是拒绝）
                        p.res.compareAndSet(PENDING, ConfirmGate.END_CLOSED.equals(end) ? DENY : TIMEOUT);
                        break;
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                p.res.compareAndSet(PENDING, TIMEOUT);
            } catch (Throwable t) {
                p.res.compareAndSet(PENDING, DENY); // 出错 = 拒绝
            } finally {
                p.res.compareAndSet(PENDING, TIMEOUT);
                p.gate.close();
                pending.remove(id);
                if (offered) try { phoneClose.accept(offer); } catch (Throwable ignored) { }
            }
            return p.res.get();
        }

        /** 本机鼠标点击（旧调用口）：同 {@link #answer}(id, allow, false, MOUSE)。 */
        public boolean resolve(int id, boolean allow) {
            return resolve(id, allow, false);
        }

        /** 本机鼠标点击（旧调用口）。always 只在这条请求给了「以后直接执行」时才算数，否则当「允许一次」。 */
        public boolean resolve(int id, boolean allow, boolean always) {
            return answer(id, allow, always, ConfirmGate.Input.MOUSE) == ConfirmGate.Answer.ACCEPTED;
        }

        /**
         * 回答一条请求。拒绝从哪来都收；允许要本机鼠标 / 键盘、确认框已显示且已解锁（{@link ConfirmGate#check}）。
         * 没收的回答不改变请求：它照旧等本机点击（等不到就拒）。
         */
        public ConfirmGate.Answer answer(int id, boolean allow, boolean always, ConfirmGate.Input in) {
            return answer(id, allow, always, false, in);
        }

        /** 同上；remember = 勾了「以后这类不用问」（只在这条给了这个勾选、且是允许时算数）。 */
        public ConfirmGate.Answer answer(int id, boolean allow, boolean always, boolean remember, ConfirmGate.Input in) {
            return answer(id, allow, always, remember, "", in);
        }

        /** [approvals-1118] scope = "task"（允许本次任务）/ "session"（这次运行都允许）/ 空（允许一次）；只在这条给了时算数。 */
        public ConfirmGate.Answer answer(int id, boolean allow, boolean always, boolean remember, String scope, ConfirmGate.Input in) {
            Pending p = pending.get(id);
            if (p == null) return ConfirmGate.Answer.GONE;
            ConfirmGate.Answer a = p.gate.check(now(), allow, in);
            if (a != ConfirmGate.Answer.ACCEPTED) return a;
            if (allow && Boolean.TRUE.equals(p.info.get("task_scope")) && ("task".equals(scope) || "session".equals(scope))) {
                int v = "task".equals(scope) ? TASK_ALLOW : SESSION_ALLOW;
                if (!p.res.compareAndSet(PENDING, v)) return ConfirmGate.Answer.GONE;
                pending.remove(id);
                p.latch.countDown();
                return ConfirmGate.Answer.ACCEPTED;
            }
            return finish(id, p, allow, always, remember);
        }

        /**
         * 手机屏幕上的确认卡回来的回答（签名、配对设备已经由 Agent 验过）。规则见 {@link ConfirmGate#checkPhone}：同一台手机、同一个一次性 token、
         * 手机界面的真人触摸（input = "touch"）、显示满 0.7 秒。语音 / 机器人 / 别的来源回 NOT_LOCAL，请求照旧等。
         */
        public ConfirmGate.Answer answerPhone(String device, int id, String token, boolean allow, boolean always, boolean remember, String input, long shownMs) {
            Pending p = pending.get(id);
            if (p == null) return ConfirmGate.Answer.GONE;
            ConfirmGate.Input in = "touch".equals(input) ? ConfirmGate.Input.PHONE_TOUCH : ConfirmGate.Input.of(input);
            ConfirmGate.Answer a = p.gate.checkPhone(now(), allow, device, token, in, shownMs);
            if (a != ConfirmGate.Answer.ACCEPTED) return a;
            return finish(id, p, allow, always, remember);
        }

        private ConfirmGate.Answer finish(int id, Pending p, boolean allow, boolean always, boolean remember) {
            boolean offered = Boolean.TRUE.equals(p.info.get("always"));
            boolean canRemember = !String.valueOf(p.info.get("remember_kind")).isEmpty() && p.info.get("remember_kind") != null;
            int v = allow ? (always && offered ? ALWAYS : remember && canRemember ? REMEMBER : ALLOW) : DENY;
            if (!p.res.compareAndSet(PENDING, v)) return ConfirmGate.Answer.GONE;
            pending.remove(id);
            p.latch.countDown();
            return ConfirmGate.Answer.ACCEPTED;
        }

        /** 界面：这条确认框画出来了（focused = 窗口当时有没有焦点）。没有这条返回 false。 */
        public boolean seen(int id, boolean focused) {
            Pending p = pending.get(id);
            if (p == null) return false;
            windowFocused = focused;
            p.gate.seen(now(), focused);
            return true;
        }

        /** 界面：窗口拿到 / 失去焦点（含最小化、切到别的程序）。 */
        public void window(boolean focused) {
            windowFocused = focused;
            long t = now();
            for (Pending p : pending.values()) p.gate.window(t, focused);
        }

        /** 界面还活着（每次刷新状态都报）。 */
        public void uiAlive() {
            long t = now();
            for (Pending p : pending.values()) p.gate.uiAlive(t);
        }

        /** 窗口关闭：所有在等的请求按拒绝结束。返回结束了几条。 */
        public int closeAll() {
            int n = 0;
            for (Map.Entry<Integer, Pending> e : pending.entrySet()) {
                Pending p = e.getValue();
                p.gate.close();
                if (p.res.compareAndSet(PENDING, DENY)) n++;
                pending.remove(e.getKey());
                p.latch.countDown();
            }
            return n;
        }

        public boolean windowFocused() { return windowFocused; }

        public List<Object> list() {
            List<Object> out = new ArrayList<>();
            long t = now();
            for (Pending p : pending.values()) {
                Map<String, Object> m = new TreeMap<>(p.info);
                m.put("seen", p.gate.seen());
                m.put("armed", p.gate.armed(t));
                m.put("phone", p.gate.phoneOffered());
                out.add(m);
            }
            return out;
        }
    }

    /** 判定结果：reply 非空就直接回它（拒绝 / 脚本 / 确认没过）；否则用 args 执行。 */
    public static final class Outcome {
        public Actions.Result reply;
        public Map<String, Object> args;
        public LinkPolicy.Cat cat;
        public boolean headsUp;
        public String headsText = "";
        /** 放行时才有（reply == null）：执行前用掉。 */
        public Pass pass;
        /** 这条是按「以后这类不用问」直接执行的类型；空 = 不是。 */
        public String rememberedKind = "";
        /** [app-whitelist-1116] 这条是按应用白名单直接放行的（Agent 执行前提醒、计数）。 */
        public boolean whitelisted;
        /** [app-whitelist-1116] 分层策略这一步的结论（null = 没过白名单相关的层；测试 / 日志用）。 */
        public ActionPolicy.Decision layered;
        /** [approvals-1118] 本机对话：执行成功后要记成「对话自己建的」的路径（新建的文件 / 目录）。 */
        public List<String> creates = List.of();
        /** [approvals-1118] 本机对话：确认卡上多写的一句（要联网 / 改已有文件 / 只读预设）。 */
        public String localNote = "";
    }

    private final Config cfg;
    /**
     * [chat-agent-1118] 这条线程正在执行电脑端「对话」里模型发起的动作（{@link ChatAgent}）。和手机远程指令走同一套判定，差别只有两处：
     * 明显只读的命令（{@link LocalReadOnly}）按查询直接执行；沙箱默认断网拦下的下载类命令改成「每次确认、不能记住」（用户就在电脑前，
     * 确认框上写清楚要联网），审批「从不询问」下照旧拒绝。提权 / 自授权 / 编码命令 / 需要管理员这些硬规则不变。
     */
    public static final ThreadLocal<Boolean> LOCAL = ThreadLocal.withInitial(() -> Boolean.FALSE);

    static boolean local() { return Boolean.TRUE.equals(LOCAL.get()); }

    public volatile Confirmer confirmer;
    /** [app-whitelist-1116] 应用白名单（null = 不启用：判定和改前完全一样）。 */
    public volatile WhitelistStore whitelist;
    /** 链接下载：电脑上的下载工具缺不缺（App 接上 {@link Media}；没接 = 不缺）。 */
    public volatile java.util.function.BooleanSupplier mediaToolsMissing = () -> false;
    /** 告诉电脑前的人（生成脚本、完全访问下的提醒）。 */
    public volatile Consumer<String> notify = s -> { };

    public PcGuard(Config cfg, Confirmer confirmer) {
        this.cfg = cfg;
        this.confirmer = confirmer;
    }

    public File workspace() {
        return cfg.workspace().toFile();
    }

    public static LinkPolicy.Cat category(String cmd, Map<String, Object> a, File ws) {
        if ("sys_exec".equals(cmd)) return LinkPolicy.classifyShell(LinkPolicy.Target.PC, str(a, "command"));
        if ("file_write".equals(cmd)) return ws != null && LinkPolicy.resolveInside(ws, str(a, "path")) != null ? LinkPolicy.Cat.WORKSPACE_WRITE : LinkPolicy.Cat.OUTSIDE_WRITE;
        return LinkPolicy.pcCat(cmd);
    }

    /** 安全插件当前的规则（可关项从配置读；三条硬规则不在里面）。 */
    public LinkPolicy.Rules rules() {
        List<String> self = new ArrayList<>();
        self.add(cfg.file == null ? "" : cfg.file.toAbsolutePath().toString());
        // [fix-1114 B12] 配置所在的 OneSay 数据目录（%APPDATA%\OneSay：钥匙、日志、WebView2 数据）也算「自己的」；只认名字就叫 OneSay 的目录
        Path dir = cfg.file == null ? null : cfg.file.toAbsolutePath().getParent();
        if (dir != null && dir.getFileName() != null && dir.getFileName().toString().equalsIgnoreCase("onesay")) self.add(dir.toString() + File.separator);
        LinkPolicy.Rules r = new LinkPolicy.Rules(cfg.pluginEnabled && cfg.pluginEncoded, LinkPolicy.parseDomains(cfg.netAllow), self);
        // 沙箱默认断网（Codex：只读 / 工作区可写没有网络，完全访问有）：这是沙箱档的事，和插件开关无关
        r.net = cfg.mode().sandbox != LinkPolicy.Sandbox.FULL_ACCESS;
        return r;
    }

    /**
     * 安全插件：过一条指令，命中返回原因。插件「关掉」只关可关项（编码 / 隐藏命令拦截），
     * 三条硬规则（永不提权、需要管理员只生成脚本、禁止自授权）永远在；插件自己抛异常 = 拒绝（出错即拒）。
     */
    /** 测试用：插件检查时注入一个故障（验证「插件出错即拒」）。 */
    volatile Runnable pluginFault = null;

    public LinkPolicy.Hit plugin(String cmd, Map<String, Object> a) {
        try {
            Runnable fault = pluginFault;
            if (fault != null) fault.run();
            LinkPolicy.Rules r = rules(); // 插件关掉时 encoded=false；提权 / 自授权两条硬规则照样查
            if (local() && "sys_exec".equals(cmd)) r.net = false; // [chat-agent-1118] 本机对话的断网改由 check() 升级成确认
            String text = switch (cmd) {
                case "sys_exec" -> str(a, "command");
                case "script_run", "shortcut_run" -> str(a, "name");
                case "open_app" -> str(a, "name") + " " + str(a, "path") + " " + str(a, "package");
                case "file_write" -> str(a, "path");
                default -> "";
            };
            if (text.isBlank()) return null;
            LinkPolicy.Hit h = LinkPolicy.inspectCommand(text, r);
            if (h != null || !"script_run".equals(cmd)) return h;
            // 工作区脚本：内容也要过（剥注释后）；要管理员的不跑，告诉你自己用管理员身份运行
            File f = scriptFile(str(a, "name"));
            if (f == null) return null;
            if (f.length() > SHORTCUT_MAX) return new LinkPolicy.Hit(LinkPolicy.R_PLUGIN_BLOCKED, "脚本太大（超过 256 KB），不检查就不跑");
            String n = str(a, "name").toLowerCase(Locale.ROOT);
            String body = LinkPolicy.normalizeCommand(LinkPolicy.stripComments(LinkPolicy.decodeScript(Files.readAllBytes(f.toPath())), n.endsWith(".bat") || n.endsWith(".cmd")));
            h = LinkPolicy.inspectCommand(body, r);
            if (h != null) return h;
            String adm = LinkPolicy.adminReason(body);
            if (adm.isEmpty()) adm = LinkPolicy.hkcuElevationReason(body);
            if (adm.isEmpty() && LinkPolicy.classifyShell(LinkPolicy.Target.PC, body) == LinkPolicy.Cat.SELF_GRANT) adm = "涉及授予权限";
            return adm.isEmpty() ? null : new LinkPolicy.Hit(LinkPolicy.R_NEEDS_ADMIN_SCRIPT, "这个脚本" + adm + "：OneSay 不提权、不替你运行，需要你自己用管理员身份执行");
        } catch (Throwable t) {
            // 插件自己出错：拒绝，不放行
            return new LinkPolicy.Hit(LinkPolicy.R_PLUGIN_BLOCKED, "安全插件检查时出错（" + t.getClass().getSimpleName() + "），按拒绝处理");
        }
    }

    public Outcome check(String from, String cmd, Map<String, Object> a0) {
        Outcome o = new Outcome();
        Map<String, Object> a = new TreeMap<>();
        if (a0 != null) for (Map.Entry<String, Object> e : a0.entrySet()) if (!e.getKey().startsWith("_")) a.put(e.getKey(), e.getValue());
        o.args = a;
        File ws = workspace();
        LinkPolicy.Cat cat = category(cmd, a, ws);
        if (local() && "sys_exec".equals(cmd) && cat == LinkPolicy.Cat.SHELL_EXEC && LocalReadOnly.ok(str(a, "command"))) cat = LinkPolicy.Cat.QUERY; // [chat-agent-1118] 本机对话里的只读查询
        o.cat = cat;
        LinkPolicy.Facts f = new LinkPolicy.Facts();
        if ("open_url".equals(cmd)) f.url = LinkPolicy.checkUrl(str(a, "url"), LinkPolicy.parseDomains(cfg.trusted));
        if ("clipboard_set".equals(cmd)) f.clip = LinkPolicy.checkClip(a.get("text") == null ? "" : String.valueOf(a.get("text")));
        if ("open_app".equals(cmd) || "uninstall_app".equals(cmd) || "app_close".equals(cmd)) {
            String name = str(a, "name"), pkg = str(a, "package"), path = str(a, "path");
            f.appBlocked = LinkPolicy.blockedApp(pkg, name) || LinkPolicy.blockedApp(path.replaceAll(".*[\\\\/]", "").replaceAll("(?i)\\.exe$", ""), name);
            String low = (name + " " + path).toLowerCase(java.util.Locale.ROOT);
            f.appSelf = low.contains("onesay");
            f.appSystem = low.contains("\\windows\\") || low.contains("explorer") || low.contains("taskmgr") || low.contains("regedit");
            f.appLabel = name.isEmpty() ? path.replaceAll(".*[\\\\/]", "") : name;
        }
        if (cat == LinkPolicy.Cat.MEDIA_DOWNLOAD) {
            String bad = MediaArgs.checkUrl(str(a, "url"));
            if (bad == null && MediaArgs.audio(a.get("audio")) == null) bad = "仅音频只能是 m4a / mp3";
            f.mediaBad = bad == null ? "" : bad;
            try { f.mediaToolsMissing = mediaToolsMissing.getAsBoolean(); } catch (Throwable t) { f.mediaToolsMissing = true; } // 判断不了 = 当缺（多问一次）
        }
        if (cat == LinkPolicy.Cat.WORKSPACE_WRITE) f.protectedPath = LinkPolicy.protectedWorkspacePath(str(a, "path"));
        f.suspect = suspect(cmd, a, cat);
        if (cat == LinkPolicy.Cat.OUTSIDE_WRITE) a.put("_outside", Boolean.TRUE); // 判定过的工作区外写入（手机自己带的 _ 参数上面已经丢掉）
        Shortcut sc = null;
        if (cat == LinkPolicy.Cat.SHORTCUT) {
            sc = shortcut(str(a, "name"));
            f = shortcutFacts(sc);
            if (sc != null) a.put("_sha256", f.shortcutHash); // 执行前再核对一次，防判定后文件被换掉
        }
        LinkPolicy.Hit hit = plugin(cmd, a);
        boolean loc = local();
        ChatPolicy.Preset preset = loc ? chatPreset() : null;
        LinkPolicy.Mode mode = loc ? ChatPolicy.mode(preset) : cfg.mode();
        String netNote = "";
        PathScope.Plan plan = null;
        List<String> ruleHit = null;
        if (hit != null) {
            f.pluginBlocked = hit.text;
            f.pluginReason = hit.code;
        } else if (loc) {
            // [approvals-1118] 本机对话：8.3 短名照旧算可疑；联网不再一律「每次确认、不能记住」，交给下面按预设判
            String command = str(a, "command");
            if ("sys_exec".equals(cmd)) f.suspect = LinkPolicy.shortNameReason(command, false);
            if ("sys_exec".equals(cmd) && mode.sandbox != LinkPolicy.Sandbox.FULL_ACCESS) {
                String norm = LinkPolicy.normalizeCommand(command);
                netNote = LinkPolicy.netReason(norm, rules().netAllow);
                if (netNote.isEmpty()) netNote = LinkPolicy.netMaybeReason(norm, rules().netAllow);
                // 只读查询（UNC 共享、nslookup…）和只读预设：要联网的照旧每次确认、不能记住
                if (!netNote.isEmpty() && (cat == LinkPolicy.Cat.QUERY || preset == ChatPolicy.Preset.READ_ONLY))
                    f.suspect = (f.suspect.isEmpty() ? "" : f.suspect + "；") + "这条命令要联网（" + netNote + "）";
            }
            if (preset == ChatPolicy.Preset.DEFAULT) {
                PathScope.Env env = pathEnv();
                if ("sys_exec".equals(cmd) && cat == LinkPolicy.Cat.SHELL_EXEC) {
                    plan = PathScope.analyze(command, env);
                    if (!plan.ok) {
                        plan = null;
                        ruleHit = ChatPolicy.matches(command, chatRules());
                    }
                } else if ("file_write".equals(cmd) && cat == LinkPolicy.Cat.OUTSIDE_WRITE) {
                    plan = PathScope.writeFile(str(a, "path"), env);
                    if (!plan.ok) plan = null;
                }
            }
        }
        LinkPolicy.Decision d = LinkPolicy.decide(LinkPolicy.Target.PC, mode, cat, f);
        if (preset != ChatPolicy.Preset.READ_ONLY) d = LinkPolicy.remembered(d, rememberedKinds()); // 只读预设：记住过的类型也问
        if (d.remembered) o.rememberedKind = d.kind;
        if (loc) d = localRefine(preset, cmd, a, cat, f, d, plan, ruleHit, netNote, o);
        d = layered(from, cmd, a, cat, f, d, o); // [app-whitelist-1116] 分层策略：硬拒 → 支付 / 隐私外发 → 读过隐私后外发 → 认不准 → 白名单 → 原结论
        switch (d.verdict) {
            case DENY:
                o.reply = result(false, d.text, d.reason, null);
                if (LinkPolicy.R_PLUGIN_BLOCKED.equals(d.reason) || LinkPolicy.R_SELF_GRANT_BLOCKED.equals(d.reason))
                    try { notify.accept(from + " 的 " + cmd + "：" + LinkPolicy.reasonZh(d.reason)); } catch (Exception ignored) { }
                return o;
            case SCRIPT: {
                if ("script_run".equals(cmd)) {
                    // 已经是工作区里的脚本：不再生成一份，告诉你它在哪
                    Map<String, Object> x = new TreeMap<>();
                    x.put("path", "scripts/" + str(a, "name"));
                    o.reply = result(false, d.text + "：scripts/" + str(a, "name"), d.reason, x);
                    return o;
                }
                String rel = script(from, cmd, a, d.text);
                Map<String, Object> x = new TreeMap<>();
                if (rel != null) x.put("path", rel);
                String head = LinkPolicy.R_NEEDS_ADMIN_SCRIPT.equals(d.reason) ? "这件事要管理员，已生成脚本，需你自己用管理员身份执行：" : "已生成脚本，需你自己执行：";
                o.reply = result(false, rel == null ? "要生成脚本，但写不进电脑工作区" : head + rel, d.reason, x);
                return o;
            }
            case CONFIRM: {
                String detail = from + " 请求：" + detail(cmd, a, d.text);
                if (!f.suspect.isEmpty() && !detail.contains(f.suspect)) detail += "\n⚠ " + f.suspect; // 可疑升级的确认：卡上写一句原因
                if (o.layered != null && o.layered.verdict == ActionPolicy.Verdict.CONFIRM && !detail.contains(o.layered.text)) detail += "\n⚠ " + o.layered.text;
                if (loc && !o.localNote.isEmpty() && !detail.contains(o.localNote)) detail += "\n⚠ " + o.localNote; // [approvals-1118] 要联网 / 改已有文件的原因
                if (loc && SafeExec.elevated()) detail += "\n⚠ OneSay 正以管理员身份运行，这一步会带管理员权限执行，请看清再允许"; // [approvals-1118] 只写在卡上，不再因此不能记住 // [app-whitelist-1116] 白名单没放行的原因（付款 / 隐私 / 读过隐私 / 次数上限）
                Confirmer c = confirmer;
                boolean offer = sc != null;
                String rk = d.rememberable() ? d.kind : "";
                boolean offerTask = loc && preset == ChatPolicy.Preset.DEFAULT && !TASK.get().isEmpty() && taskGrantable(cmd, a, cat, d, plan);
                int r;
                try {
                    r = c == null ? TIMEOUT : offerTask ? c.ask(from, cmd, detail, CONFIRM_MS, offer, rk, true)
                            : rk.isEmpty() ? c.ask(from, cmd, detail, CONFIRM_MS, offer) : c.ask(from, cmd, detail, CONFIRM_MS, offer, rk);
                } catch (Throwable t) {
                    r = DENY; // 确认框出错 = 拒绝，绝不放行
                }
                if ((r == TASK_ALLOW || r == SESSION_ALLOW) && !offerTask) r = ALLOW;
                if (r == TASK_ALLOW) { grantTask(TASK.get()); r = ALLOW; logPerm(detail(cmd, a, d.text), "允许本次任务", from); }
                else if (r == SESSION_ALLOW) { grantSession(); r = ALLOW; logPerm(detail(cmd, a, d.text), "这次运行都允许", from); }
                else if (loc) logPerm(detail(cmd, a, d.text), r == ALLOW ? "允许一次" : r == REMEMBER ? "允许并记住" : r == TIMEOUT ? "没人确认（拒绝）" : "拒绝", from);
                if (loc && (r == ALLOW || r == REMEMBER) && approvedSame.size() < 500) approvedSame.add(sameKey(cmd, a));
                if (r == REMEMBER && rk.isEmpty()) r = ALLOW; // 这条没给「以后这类不用问」：只算允许一次
                if (offer) {
                    // 快捷指令：没批准下来时原因码是 shortcut_needs_approval / shortcut_changed，确认结果另放 confirm_result
                    Map<String, Object> x = new TreeMap<>();
                    x.put("shortcut", sc.id);
                    if (r == ALWAYS) {
                        approve(sc.id, f.shortcutHash);
                        try { notify.accept("已批准快捷指令「" + sc.name + "」以后从手机直接执行（可在快捷指令页撤销）"); } catch (Exception ignored) { }
                    } else if (r == TIMEOUT) {
                        x.put("confirm_result", LinkPolicy.R_CONFIRM_TIMEOUT);
                        o.reply = result(false, "电脑上没人确认，没执行快捷指令「" + sc.name + "」", d.reason, x);
                    } else if (r != ALLOW) {
                        x.put("confirm_result", LinkPolicy.R_CONFIRM_DENIED);
                        o.reply = result(false, "电脑上拒绝了快捷指令「" + sc.name + "」", d.reason, x);
                    }
                    if (o.reply == null) o.pass = new Pass(cmd, a);
                    return o;
                }
                if (r == TIMEOUT) o.reply = result(false, "没人确认（60 秒到了，或确认框被切走 / 界面没响应），已拒绝", LinkPolicy.R_CONFIRM_TIMEOUT, null);
                else if (r != ALLOW && r != ALWAYS && r != REMEMBER) o.reply = result(false, "电脑上拒绝了", LinkPolicy.R_CONFIRM_DENIED, null);
                else {
                    if (r == REMEMBER) {
                        remember(rk);
                        try { notify.accept(ChatPolicy.validKey(rk) ? ChatPolicy.keyZh(rk) + "（可在设置页撤销）" : "以后「" + LinkPolicy.kindZh(rk) + "」不用再问（可在设置页撤销）"); } catch (Exception ignored) { }
                    }
                    o.creates = plan == null ? List.of() : new ArrayList<>(plan.creates);
                    if (f.protectedPath) a.put("_protected_ok", Boolean.TRUE);
                    if (f.mediaToolsMissing) a.put("_media_install_ok", Boolean.TRUE); // 这张卡批的是「先装工具再下载」
                    o.pass = new Pass(cmd, a);
                }
                return o;
            }
            default:
                if (d.headsUp && !(loc && "open_url".equals(cmd))) {
                    o.headsUp = true;
                    o.headsText = d.text;
                    try { notify.accept(from + "：" + d.text); } catch (Exception ignored) { }
                }
                if (f.protectedPath && d.remembered) a.put("_protected_ok", Boolean.TRUE);
                if (plan != null) o.creates = new ArrayList<>(plan.creates); // [approvals-1118] 执行成功后记成「对话自己建的」
                if (loc && !d.text.isEmpty() && cat != LinkPolicy.Cat.QUERY) logPerm(detail(cmd, a, d.text), "自动放行", d.text);
                if (f.mediaToolsMissing && cat == LinkPolicy.Cat.MEDIA_DOWNLOAD) a.put("_media_install_ok", Boolean.TRUE); // 完全访问 + 从不询问 / 记住过「安装下载工具」
                o.pass = new Pass(cmd, a);
                return o;
        }
    }

    // ------------------------------------------------------------------ [approvals-1118] 本机对话：预设 / 前缀规则 / 按路径放行 / 任务级批准

    /** 这条线程正在执行的对话任务（Chat 设上）；空 = 不是对话任务，不给「允许本次任务」。 */
    public static final ThreadLocal<String> TASK = ThreadLocal.withInitial(() -> "");
    /** 用户点了「允许本次任务」的任务 id（同一时间只有一个）；点的时间。 */
    volatile String grantedTask = "";
    volatile long grantedAt;
    /** 用户点了「这次运行都允许」（OneSay 重启 / 改预设 / 手动撤销后失效）。 */
    volatile boolean sessionGrant;
    /** 任务批准最长有效期（防止一个任务挂一整天）。 */
    static final long TASK_GRANT_MS = 6 * 3600_000L;
    /** 这次运行里对话自己新建的文件 / 目录（规范化小写）：覆盖 / 删除它们不用问。 */
    public final java.util.Set<String> chatCreated = ConcurrentHashMap.newKeySet();
    /** 这次运行里用户批准过的完全一样的步骤（Codex 的 approved-for-session：同一条命令再来不再问）。 */
    final java.util.Set<String> approvedSame = ConcurrentHashMap.newKeySet();

    static String sameKey(String cmd, Map<String, Object> a) {
        Map<String, Object> m = new TreeMap<>();
        for (Map.Entry<String, Object> e : a.entrySet()) if (!e.getKey().startsWith("_")) m.put(e.getKey(), e.getValue());
        return cmd + "|" + Json.stringify(m);
    }

    /** 判定路径用的环境（测试换掉）。 */
    public volatile java.util.function.Supplier<PathScope.Env> pathEnvSource = this::defaultPathEnv;
    /** 权限记录（设置 › 权限记录）：最近的批准 / 拒绝 / 自动放行。 */
    final java.util.Deque<Map<String, Object>> permLog = new java.util.concurrent.ConcurrentLinkedDeque<>();
    static final int PERM_LOG_MAX = 300;

    public ChatPolicy.Preset chatPreset() { return ChatPolicy.Preset.of(cfg.chatPreset); }

    public List<List<String>> chatRules() { return ChatPolicy.parseRules(cfg.chatAllowPrefixes); }

    PathScope.Env pathEnv() {
        PathScope.Env e;
        try { e = pathEnvSource.get(); } catch (RuntimeException x) { e = null; }
        if (e == null) e = new PathScope.Env();
        e.created = new java.util.HashSet<>(chatCreated);
        return e;
    }

    PathScope.Env defaultPathEnv() {
        PathScope.Env e = new PathScope.Env();
        if (!SafeExec.WINDOWS) return e; // 只认 Windows 路径；别的系统上一律不按路径放行
        Map<String, String> env = System.getenv();
        for (String k : new String[] {"USERPROFILE", "TEMP", "TMP", "LOCALAPPDATA", "APPDATA", "OneDrive", "PUBLIC"}) {
            String v = env.get(k);
            if (v != null) e.envVars.put(k.toLowerCase(Locale.ROOT), v);
        }
        e.userProfile = env.getOrDefault("USERPROFILE", "");
        e.temp = env.getOrDefault("TEMP", "");
        e.workspace = workspace().getAbsolutePath();
        e.exists = p -> { try { return Files.exists(Paths.get(p), java.nio.file.LinkOption.NOFOLLOW_LINKS); } catch (RuntimeException x) { return true; } };
        e.isDir = p -> { try { return Files.isDirectory(Paths.get(p)); } catch (RuntimeException x) { return false; } };
        e.real = p -> {
            try {
                Path q = Paths.get(p);
                Path cur = q;
                while (cur != null && !Files.exists(cur)) cur = cur.getParent();
                if (cur == null) return p;
                Path r = cur.toRealPath();
                return r.resolve(cur.relativize(q)).toString();
            } catch (Exception x) {
                return "\\\\unresolved"; // 解析不了 = 当不在地盘里
            }
        };
        return e;
    }

    /** 执行成功后：把对话新建的路径记下（之后覆盖 / 删除它们不用问）。 */
    public void noteCreated(List<String> paths) {
        if (paths == null) return;
        for (String p : paths) if (chatCreated.size() < 2000) chatCreated.add(p.toLowerCase(Locale.ROOT));
    }

    /** 现在这一步能不能按「允许本次任务 / 这次运行都允许」直接做。 */
    boolean granted() {
        if (sessionGrant) return true;
        String t = TASK.get();
        return t != null && !t.isEmpty() && t.equals(grantedTask) && System.currentTimeMillis() - grantedAt < TASK_GRANT_MS;
    }

    public synchronized void grantTask(String task) {
        if (task == null || task.isEmpty()) return;
        grantedTask = task;
        grantedAt = System.currentTimeMillis();
    }

    public synchronized void grantSession() { sessionGrant = true; }

    /** 撤销任务 / 运行级批准（改预设、界面上点撤销、新任务开始时）。 */
    public synchronized void revokeGrants() {
        grantedTask = "";
        sessionGrant = false;
    }

    /** 新任务开始：上一个任务的批准不带过来。 */
    public synchronized void taskStarted(String task) {
        if (!grantedTask.isEmpty() && !grantedTask.equals(task)) grantedTask = "";
    }

    public Map<String, Object> grantView() {
        Map<String, Object> m = new TreeMap<>();
        m.put("task", !grantedTask.isEmpty() && System.currentTimeMillis() - grantedAt < TASK_GRANT_MS);
        m.put("session", sessionGrant);
        return m;
    }

    /** 删除 / 卸载 / 改系统设置这类：任务批准也不覆盖，每次都问。 */
    static final java.util.regex.Pattern RISKY_WORDS = java.util.regex.Pattern.compile(
            "(?i)(?<![\\w-])(remove-item|ri|rm|del|erase|rd|rmdir|clear-content|clear-item|remove-itemproperty|stop-computer|restart-computer|format-volume|clear-disk|clear-recyclebin|uninstall-package|remove-appxpackage|msiexec|set-itemproperty|new-itemproperty|set-service|stop-service|disable-\\w+|unregister-\\w+|set-date|set-timezone)(?![\\w-])|(?<![\\w-])shutdown(\\.exe)?\\s+[/-][srhpfl]\\b|卸载|(^|[\\s\"'/\\-])(uninstall|uninst|/x)([\\s\"'=]|$)");

    /** 任务 / 运行级批准能覆盖这一步吗（只看这一步本身的风险，硬规则根本走不到这里）。 */
    static boolean taskGrantable(String cmd, Map<String, Object> a, LinkPolicy.Cat cat, LinkPolicy.Decision d, PathScope.Plan plan) {
        if (d.verdict != LinkPolicy.Verdict.CONFIRM) return false;
        if (LinkPolicy.R_SUSPECT.equals(d.reason) || LinkPolicy.R_SUSPICIOUS_FORM.equals(d.reason)) return false; // 写法可疑：每次都问
        switch (cat) {
            case SHELL_EXEC: {
                String c = str(a, "command");
                if (plan != null) return true;
                return !RISKY_WORDS.matcher(LocalReadOnly.literalsBlanked(c)).find() && !RISKY_WORDS.matcher(c).find();
            }
            case OUTSIDE_WRITE:
            case OPEN_URL:
            case MEDIA_DOWNLOAD:
            case RUN_WS_SCRIPT:
                return !LinkPolicy.R_UNTRUSTED_URL.equals(d.reason) || "pc.url".equals(d.kind) || d.kind.startsWith("url:");
            case WORKSPACE_WRITE:
                return true;
            default:
                return false; // 设置、关机、卸载、系统程序、支付类程序、敏感剪贴板：每次都问
        }
    }

    /**
     * 本机对话按预设细化结论（拒绝 / 只生成脚本照旧）：
     * 只读 —— 查询以外都问、不能记住；完全访问 —— 照两轴（完全访问 · 从不）；默认 —— 按路径放行、前缀规则、普通网址直接开、
     * 「允许本次任务 / 这次运行都允许」覆盖本任务里的同类步骤（删除 / 卸载 / 设置 / 可疑写法照旧每次问）。
     */
    LinkPolicy.Decision localRefine(ChatPolicy.Preset preset, String cmd, Map<String, Object> a, LinkPolicy.Cat cat, LinkPolicy.Facts f,
                                    LinkPolicy.Decision d, PathScope.Plan plan, List<String> ruleHit, String netNote, Outcome o) {
        if (d.verdict == LinkPolicy.Verdict.DENY || d.verdict == LinkPolicy.Verdict.SCRIPT) return d;
        if (preset == ChatPolicy.Preset.FULL) return d;
        if (preset == ChatPolicy.Preset.READ_ONLY) {
            if (cat == LinkPolicy.Cat.QUERY || cat == LinkPolicy.Cat.READ_PRIVATE || cat == LinkPolicy.Cat.HARMLESS) return d;
            o.localNote = "权限预设是「只读」：除了查询每一步都要你确认";
            return new LinkPolicy.Decision(LinkPolicy.Verdict.CONFIRM, d.reason, d.text.isEmpty() ? cmd : d.text, false, "", false);
        }
        if (d.verdict == LinkPolicy.Verdict.ALLOW) {
            if (cat == LinkPolicy.Cat.OPEN_URL && !d.remembered && f.url != null && !f.url.trusted) {
                String why = ChatPolicy.dataLike(str(a, "url"));
                if (!why.isEmpty()) {
                    o.localNote = why;
                    LinkPolicy.Decision c = new LinkPolicy.Decision(LinkPolicy.Verdict.CONFIRM, "", "打开网址：" + f.url.shown, false, ChatPolicy.urlKey(f.url.host), false);
                    return granted() && taskGrantable(cmd, a, cat, c, plan) ? viaGrant(c) : c;
                }
                return new LinkPolicy.Decision(LinkPolicy.Verdict.ALLOW, "", "", false); // 普通网址：直接开，不弹通知
            }
            return d;
        }
        // CONFIRM
        LinkPolicy.Decision c = d;
        if (cat == LinkPolicy.Cat.SHELL_EXEC || cat == LinkPolicy.Cat.OUTSIDE_WRITE) {
            if (plan != null && !LinkPolicy.R_SUSPECT.equals(d.reason)) {
                List<String> bad = new ArrayList<>();
                for (String h : plan.hosts) if (!LinkPolicy.trustedHost(h, rules().netAllow)) bad.add(h);
                if (bad.isEmpty()) return new LinkPolicy.Decision(LinkPolicy.Verdict.ALLOW, "", "在桌面 / 下载 / 文档 / 工作区 / 临时目录里新建或写入", false);
                o.localNote = "要联网下载（" + String.join("、", bad) + "）";
                c = new LinkPolicy.Decision(LinkPolicy.Verdict.CONFIRM, "", d.text, false, bad.size() == 1 ? ChatPolicy.netKey(bad.get(0)) : "", false);
            } else if (ruleHit != null && !LinkPolicy.R_SUSPECT.equals(d.reason)) {
                return new LinkPolicy.Decision(LinkPolicy.Verdict.ALLOW, "", "按「以后以「" + ChatPolicy.ruleText(ruleHit) + "」开头的命令不用问」直接执行", true, ChatPolicy.prefixKey(ruleHit), true);
            } else if (!LinkPolicy.R_SUSPECT.equals(d.reason)) {
                List<String> sug = "sys_exec".equals(cmd) ? ChatPolicy.suggest(str(a, "command")) : null;
                if (!netNote.isEmpty()) o.localNote = "这条命令要联网（" + netNote + "）";
                c = new LinkPolicy.Decision(LinkPolicy.Verdict.CONFIRM, d.reason, d.text, false, sug == null ? "" : ChatPolicy.prefixKey(sug), false);
            }
        }
        if (granted() && taskGrantable(cmd, a, cat, c, plan)) return viaGrant(c);
        if (taskGrantable(cmd, a, cat, c, plan) && approvedSame.contains(sameKey(cmd, a)))
            return new LinkPolicy.Decision(LinkPolicy.Verdict.ALLOW, "", "这条完全一样的步骤这次运行里批准过：" + c.text, false);
        return c;
    }

    LinkPolicy.Decision viaGrant(LinkPolicy.Decision c) {
        return new LinkPolicy.Decision(LinkPolicy.Verdict.ALLOW, "", (sessionGrant ? "按「这次运行都允许」" : "按「允许本次任务」") + "直接执行：" + c.text, false);
    }

    /** 记一条权限记录（不进通知列表）。 */
    public void logPerm(String what, String result, String detail) {
        Map<String, Object> m = new TreeMap<>();
        m.put("at", System.currentTimeMillis());
        m.put("what", what == null ? "" : what);
        m.put("result", result == null ? "" : result);
        m.put("detail", detail == null ? "" : (detail.length() > 300 ? detail.substring(0, 300) + "…" : detail));
        m.put("task", TASK.get() == null ? "" : TASK.get());
        permLog.addFirst(m);
        while (permLog.size() > PERM_LOG_MAX) permLog.pollLast();
    }

    public List<Object> permLogView() { return new ArrayList<Object>(permLog); }

    /** 「以后不用问」的键（前缀 / 网址域名 / 联网域名）记进配置。 */
    synchronized boolean rememberRule(String key) {
        List<String> p = ChatPolicy.keyPrefix(key);
        if (p != null) {
            List<List<String>> r = new ArrayList<>(chatRules());
            if (!r.contains(p)) r.add(p);
            cfg.chatAllowPrefixes = ChatPolicy.formatRules(r);
            save();
            return true;
        }
        String h = ChatPolicy.keyHost(key);
        if (h == null) return false;
        if (key.startsWith("url:")) {
            List<String> d = new ArrayList<>(LinkPolicy.parseDomains(cfg.trusted));
            if (!d.contains(h)) d.add(h);
            cfg.trusted = String.join(",", d);
        } else {
            List<String> d = new ArrayList<>(LinkPolicy.parseDomains(cfg.netAllow));
            if (!d.contains(h)) d.add(h);
            cfg.netAllow = String.join(",", d);
        }
        save();
        return true;
    }

    /** 撤销一条前缀规则（键 prefix:[…]）。 */
    public synchronized boolean forgetRule(String key) {
        List<String> p = ChatPolicy.keyPrefix(key);
        if (p == null) return false;
        List<List<String>> r = new ArrayList<>(chatRules());
        if (!r.remove(p)) return false;
        cfg.chatAllowPrefixes = ChatPolicy.formatRules(r);
        save();
        return true;
    }

    // ------------------------------------------------------------------ [app-whitelist-1116] 统一分层策略

    /** 哪些指令有「目标程序」、能按白名单放行：打开 / 关掉 / 卸载程序，以及第一段就是那个程序的单条命令。 */
    static boolean whitelistable(LinkPolicy.Cat c) {
        return c == LinkPolicy.Cat.OPEN_APP || c == LinkPolicy.Cat.APP_OPERATE || c == LinkPolicy.Cat.UNINSTALL_APP || c == LinkPolicy.Cat.SHELL_EXEC;
    }

    /** 命令里有这些就不是「单条命令」（管道、串联、重定向、变量、子表达式、续行），不按白名单放行。 */
    private static final java.util.regex.Pattern CHAIN = java.util.regex.Pattern.compile("[&|;<>`$%^()\\n\\r]|\\b(start|call|invoke-expression|iex|cmd|powershell|pwsh)\\b", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 指令的目标程序（windows exe 名，规范化过）；没有 / 认不出 / 是系统程序或 OneSay 自己 → ""。 */
    static String targetExe(String cmd, Map<String, Object> a, LinkPolicy.Facts f) {
        if ("sys_exec".equals(cmd)) {
            String raw = str(a, "command").trim();
            if (raw.isEmpty() || raw.length() > 2000) return "";
            String first;
            String rest;
            if (raw.startsWith("\"")) {
                int k = raw.indexOf('"', 1);
                if (k < 0) return "";
                first = raw.substring(1, k);
                rest = raw.substring(k + 1);
            } else {
                int k = raw.indexOf(' ');
                first = k < 0 ? raw : raw.substring(0, k);
                rest = k < 0 ? "" : raw.substring(k);
            }
            if (CHAIN.matcher(rest).find()) return "";
            String n = AppWhitelist.normId(AppWhitelist.WINDOWS, first);
            return n == null || n.matches(".*[&;%^$`()!].*") ? "" : n;
        }
        if (f == null || f.appSystem || f.appSelf) return "";
        String path = str(a, "path"), name = str(a, "name");
        String n = path.isEmpty() ? null : AppWhitelist.normId(AppWhitelist.WINDOWS, path);
        if (n == null && !name.isEmpty()) n = AppWhitelist.normId(AppWhitelist.WINDOWS, name);
        return n == null ? "" : n;
    }

    /** [app-whitelist-1116 规则调整] 单条命令里像卸载的写法（app.exe --uninstall、/uninstall、卸载）：按卸载算，白名单也要确认。 */
    static final java.util.regex.Pattern UNINSTALL_WORDS = java.util.regex.Pattern.compile("(^|[\\s\"'/\\-])(uninstall|uninst|remove-app|/x)([\\s\"'=]|$)|卸载", java.util.regex.Pattern.CASE_INSENSITIVE);

    /** 要发出去的内容（判隐私外发用）：命令全文、剪贴板文字、网址。 */
    static String outText(String cmd, Map<String, Object> a) {
        switch (cmd) {
            case "sys_exec": return str(a, "command");
            case "clipboard_set": return a.get("text") == null ? "" : String.valueOf(a.get("text"));
            case "open_url": return str(a, "url");
            case "file_write": return a.get("content") == null ? "" : String.valueOf(a.get("content"));
            default: return "";
        }
    }

    /**
     * 原结论 d（LinkPolicy.decide + 「以后这类不用问」）再过 {@link ActionPolicy}：只有目标程序在白名单里时才会变；
     * 不启用白名单（{@link #whitelist} 为 null）或不在白名单的，原样返回 —— 改前的行为一条不变。
     */
    LinkPolicy.Decision layered(String from, String cmd, Map<String, Object> a, LinkPolicy.Cat cat, LinkPolicy.Facts f, LinkPolicy.Decision d, Outcome o) {
        WhitelistStore w = whitelist;
        if (w == null || d == null) return d;
        try {
            ActionPolicy.Facts x = new ActionPolicy.Facts();
            x.base = d.verdict == LinkPolicy.Verdict.ALLOW ? ActionPolicy.Verdict.ALLOW : d.verdict == LinkPolicy.Verdict.CONFIRM ? ActionPolicy.Verdict.CONFIRM
                    : d.verdict == LinkPolicy.Verdict.SCRIPT ? ActionPolicy.Verdict.SCRIPT : ActionPolicy.Verdict.DENY;
            x.baseReason = d.text;
            x.platform = AppWhitelist.WINDOWS;
            x.appId = whitelistable(cat) ? targetExe(cmd, a, f) : "";
            x.whitelistBroken = w.broken();
            x.whitelisted = !x.appId.isEmpty() && w.contains(AppWhitelist.WINDOWS, x.appId);
            if (!x.whitelisted) return d; // 不在白名单：原结论（各层只对白名单应用起作用）
            String text = outText(cmd, a);
            boolean net = "sys_exec".equals(cmd) && (!LinkPolicy.netMaybeReason(LinkPolicy.normalizeCommand(text), new ArrayList<>()).isEmpty()
                    || !LinkPolicy.netReason(LinkPolicy.normalizeCommand(text), new ArrayList<>()).isEmpty());
            if (f != null && f.appBlocked) x.kind = ActionPolicy.Kind.PAY; // 支付 / 银行 / 钱包 / 商店类程序：和付款一样不放行
            else if (LinkPolicy.isPayFinal(cmd)) x.kind = ActionPolicy.Kind.PAY;
            else if (cat == LinkPolicy.Cat.UNINSTALL_APP || ("sys_exec".equals(cmd) && UNINSTALL_WORDS.matcher(text).find())) x.kind = ActionPolicy.Kind.UNINSTALL; // 规则调整：卸载白名单程序必须确认
            else if (net || cat == LinkPolicy.Cat.CLIPBOARD_WRITE || cat == LinkPolicy.Cat.OPEN_URL) x.kind = ActionPolicy.Kind.OUTBOUND;
            else x.kind = ActionPolicy.Kind.OTHER;
            // 单条命令带参数（文件名、文字）也算往外交内容：参数里有隐私就按隐私外发
            x.outPrivacy = PrivacyScan.text(text);
            if (!x.outPrivacy.isEmpty() && x.kind == ActionPolicy.Kind.OTHER) x.kind = ActionPolicy.Kind.OUTBOUND;
            x.readPrivacy = w.readKinds(from);
            x.readExternal = w.readExternal(from); // 规则保持：读过外部内容后外发，白名单也确认
            x.suspect = LinkPolicy.suspectWhy(f);
            x.neverAsk = cfg.mode().approval == LinkPolicy.Approval.NEVER;
            x.passesUsed = w.passes(from);
            ActionPolicy.Decision r = ActionPolicy.decide(x);
            o.layered = r;
            if (r.layer == ActionPolicy.Layer.HARD || r.layer == ActionPolicy.Layer.DEFAULT) return d;
            w.audit(x, r, cmd);
            if (r.whitelistPass) {
                w.countPass(from);
                o.whitelisted = true;
                return new LinkPolicy.Decision(LinkPolicy.Verdict.ALLOW, "", "白名单程序「" + x.appId + "」：" + (d.text.isEmpty() ? cmd : d.text), true, d.kind, false);
            }
            if (r.verdict == ActionPolicy.Verdict.DENY) return LinkPolicy.Decision.deny(LinkPolicy.R_DENIED_BY_LEVEL, r.text);
            if (r.verdict == ActionPolicy.Verdict.CONFIRM) {
                if (d.verdict == LinkPolicy.Verdict.CONFIRM) // 原来就要问：照旧问，卡上补原因，不给「以后这类不用问」
                    return new LinkPolicy.Decision(LinkPolicy.Verdict.CONFIRM, d.reason, d.text + (d.text.isEmpty() ? "" : "\n") + "⚠ " + r.text, false, "", false);
                return new LinkPolicy.Decision(LinkPolicy.Verdict.CONFIRM, "", (d.text.isEmpty() || d.headsUp ? cmd : d.text) + "\n⚠ " + r.text, false, "", false);
            }
            return d;
        } catch (RuntimeException e) {
            return d; // 分层出错：原结论（不会因此放松）
        }
    }

    /**
     * [fix-1114] 写法可疑、守卫认不准的 → 原因（非空时 {@link LinkPolicy#decide} 把直接执行 / 可记住的确认升级成「每次确认、不能记住」）：
     * 路径 / 命令里的 8.3 短名（GIT~1 …）；沙箱默认断网开着时，认不出的可能联网命令（git clone、nslookup、mshta、.NET HttpClient …），工作区脚本内容同样看。
     * 自己出错 = 当可疑（多问一次）。
     */
    String suspect(String cmd, Map<String, Object> a, LinkPolicy.Cat cat) {
        try {
            LinkPolicy.Rules r = rules();
            switch (cmd) {
                case "file_write":
                    return LinkPolicy.shortNameReason(str(a, "path"), true);
                case "sys_exec": {
                    String raw = str(a, "command");
                    String why = LinkPolicy.shortNameReason(raw, false);
                    if (why.isEmpty() && r.net) why = LinkPolicy.netMaybeReason(LinkPolicy.normalizeCommand(raw), r.netAllow);
                    return why;
                }
                case "script_run": {
                    if (!r.net) return "";
                    File f = scriptFile(str(a, "name"));
                    if (f == null || f.length() > SHORTCUT_MAX) return "";
                    String n = str(a, "name").toLowerCase(Locale.ROOT);
                    String body = LinkPolicy.normalizeCommand(LinkPolicy.stripComments(LinkPolicy.decodeScript(Files.readAllBytes(f.toPath())), n.endsWith(".bat") || n.endsWith(".cmd")));
                    String why = LinkPolicy.netMaybeReason(body, r.netAllow);
                    return why.isEmpty() ? "" : "脚本内容" + why;
                }
                default:
                    return "";
            }
        } catch (Throwable t) {
            return "检查时出错（" + t.getClass().getSimpleName() + "），按可疑处理";
        }
    }

    /** 确认框里给人看的那句话：带上确切的动作（网址域名、命令全文、文件路径）。 */
    static String detail(String cmd, Map<String, Object> a, String why) {
        switch (cmd) {
            case "script_run": return "运行工作区脚本 scripts/" + str(a, "name");
            case "shortcut_run": return "运行快捷指令「" + str(a, "name") + "」（" + why + "）";
            case "sys_exec": return "执行命令：" + str(a, "command");
            case "uninstall_app": return "卸载 " + (str(a, "name").isEmpty() ? str(a, "package") : str(a, "name"));
            case "volume": return "调音量（" + str(a, "action") + "）";
            case "shutdown": return "关机";
            case "sleep": return "睡眠";
            case "media_get": {
                String au = MediaArgs.audio(a.get("audio"));
                String q = au != null && !au.isEmpty() ? "仅音频 " + au : "清晰度 " + ("best".equals(MediaArgs.quality(a.get("quality"))) ? "最佳" : MediaArgs.quality(a.get("quality")) + "p");
                return why + "：" + LinkPolicy.oneLine(str(a, "url")) + "（" + q + "）";
            }
            default: return why;
        }
    }

    // ------------------------------------------------------------------ 快捷指令

    /** 一个快捷指令：文件名、稳定 id（文件名小写）、路径、触发这一刻的内容。 */
    public static final class Shortcut {
        public final String name, id;
        public final Path path;
        public final byte[] bytes;

        Shortcut(Path path, byte[] bytes) {
            this.path = path;
            this.name = path.getFileName().toString();
            this.id = LinkPolicy.shortcutId(name);
            this.bytes = bytes;
        }
    }

    /** 快捷指令文件夹里名字对应的文件（和 {@link DesktopActions#shortcutRun} 用同一个查找）；没有返回 null。 */
    public Shortcut shortcut(String name) {
        Path p = DesktopActions.shortcutPath(cfg.scriptsDir, name);
        return p == null ? null : load(p);
    }

    static Shortcut load(Path p) {
        try {
            if (Files.size(p) > SHORTCUT_MAX) return new Shortcut(p, null);
            return new Shortcut(p, Files.readAllBytes(p));
        } catch (Exception e) {
            return null;
        }
    }

    /** 快捷指令列表（文件夹里的 .ps1 / .bat / .cmd，最多 200 个，按名字排序）。 */
    public List<Shortcut> shortcuts() {
        List<Shortcut> out = new ArrayList<>();
        if (cfg.scriptsDir.isEmpty()) return out;
        try (var s = Files.list(Paths.get(cfg.scriptsDir))) {
            s.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().matches("(?i).+\\.(ps1|bat|cmd)$")).sorted().limit(200)
                    .forEach(p -> { Shortcut x = load(p); if (x != null) out.add(x); });
        } catch (Exception ignored) { }
        return out;
    }

    /** 判定事实：内容哈希 / 严格规则 / 批准表，再加两条电脑端才知道的：文件太大、文件在远程可写的工作区里（不算你定义的）。 */
    LinkPolicy.Facts shortcutFacts(Shortcut sc) {
        if (sc == null) return LinkPolicy.shortcutFacts(null, null, null, false);
        LinkPolicy.Facts f = LinkPolicy.shortcutFacts(sc.id, sc.bytes == null ? new byte[0] : sc.bytes, approvals(), true, rules());
        String extra = null;
        if (sc.bytes == null) extra = "文件太大（超过 256 KB）";
        else if (insideWorkspace(sc.path)) extra = "文件在手机能写的电脑工作区里（不算你在电脑上定义的快捷指令）";
        if (extra != null && f.shortcutBlocked.isEmpty()) {
            f.shortcutBlocked = extra;
            f.shortcutApproved = false;
        }
        return f;
    }

    boolean insideWorkspace(Path p) {
        try {
            File ws = workspace().getCanonicalFile();
            File x = p.toFile().getCanonicalFile();
            for (File d = x; d != null; d = d.getParentFile()) if (d.equals(ws)) return true;
        } catch (Exception e) {
            return true; // 判断不了就当在里面（不放开）
        }
        return false;
    }

    public synchronized Map<String, String> approvals() {
        return LinkPolicy.parseApprovals(cfg.shortcutApprovals);
    }

    /** 记下「以后直接执行」：id → 内容哈希。内容碰严格规则的不会走到这里（判定直接拒）。 */
    public synchronized void approve(String id, String hash) {
        Map<String, String> m = approvals();
        m.put(LinkPolicy.shortcutId(id), hash);
        cfg.shortcutApprovals = LinkPolicy.formatApprovals(m);
        save();
    }

    /** 撤销批准；没有这条返回 false。 */
    public synchronized boolean revoke(String id) {
        Map<String, String> m = approvals();
        if (m.remove(LinkPolicy.shortcutId(id)) == null) return false;
        cfg.shortcutApprovals = LinkPolicy.formatApprovals(m);
        save();
        return true;
    }

    private void save() {
        try { cfg.save(); } catch (Exception ignored) { } // 测试里配置可能没有文件路径；内存里的批准表照样生效
    }

    // ------------------------------------------------------------------ 「以后这类不用问」

    /** 记住的动作类型（配置 approve_kinds）。 */
    public synchronized List<String> rememberedKinds() {
        return LinkPolicy.parseKinds(cfg.approveKinds);
    }

    /** 记下一类（只收认识的类型；硬规则根本不会走到确认，记不了）。 */
    public synchronized boolean remember(String kind) {
        if (ChatPolicy.validKey(kind)) return rememberRule(kind);
        if (!LinkPolicy.knownKind(kind)) return false;
        List<String> k = rememberedKinds();
        if (!k.contains(kind)) k.add(kind);
        cfg.approveKinds = LinkPolicy.formatKinds(k);
        save();
        return true;
    }

    /** 撤销一类；没有返回 false。 */
    public synchronized boolean forget(String kind) {
        List<String> k = rememberedKinds();
        if (!k.remove(kind)) return false;
        cfg.approveKinds = LinkPolicy.formatKinds(k);
        save();
        return true;
    }

    /** 设置页用：每类 {kind, zh}。 */
    public List<Object> rememberedView() {
        List<Object> out = new ArrayList<>();
        for (String k : rememberedKinds()) {
            Map<String, Object> m = new TreeMap<>();
            m.put("kind", k);
            m.put("zh", LinkPolicy.kindZh(k));
            out.add(m);
        }
        return out;
    }

    /** 界面用：每个快捷指令的状态 approved（以后直接执行）/ changed（改过，要重新批准）/ new（还没批准）/ blocked（不能批准，带原因）。 */
    public List<Object> shortcutView() {
        List<Object> out = new ArrayList<>();
        for (Shortcut sc : shortcuts()) {
            LinkPolicy.Facts f = shortcutFacts(sc);
            Map<String, Object> m = new TreeMap<>();
            m.put("name", sc.name);
            m.put("id", sc.id);
            String st = !f.shortcutBlocked.isEmpty() ? "blocked" : f.shortcutApproved ? "approved" : f.shortcutChanged ? "changed" : "new";
            m.put("status", st);
            m.put("why", f.shortcutBlocked);
            out.add(m);
        }
        return out;
    }

    /** 界面用：批准表（含文件已经没了 / 内容改过的），每条可撤销。 */
    public List<Object> approvalView() {
        Map<String, Shortcut> byId = new TreeMap<>();
        for (Shortcut sc : shortcuts()) byId.put(sc.id, sc);
        List<Object> out = new ArrayList<>();
        for (Map.Entry<String, String> e : approvals().entrySet()) {
            Shortcut sc = byId.get(e.getKey());
            String st;
            if (sc == null) st = "missing";
            else {
                LinkPolicy.Facts f = shortcutFacts(sc);
                st = !f.shortcutBlocked.isEmpty() ? "blocked" : f.shortcutApproved ? "approved" : "changed";
            }
            Map<String, Object> m = new TreeMap<>();
            m.put("id", e.getKey());
            m.put("name", sc == null ? e.getKey() : sc.name);
            m.put("status", st);
            m.put("hash", e.getValue().substring(0, 12));
            out.add(m);
        }
        return out;
    }

    String script(String from, String cmd, Map<String, Object> a, String why) {
        String command;
        String risk;
        if ("sys_exec".equals(cmd)) {
            command = str(a, "command");
            String why2 = LinkPolicy.adminReason(LinkPolicy.normalizeCommand(command));
            risk = (why2.isEmpty() ? "系统级改动" : why2) + "：可能让电脑断网、变得不安全或无法启动。OneSay 不会自己提权，这一步要你自己用管理员身份运行";
        } else if ("file_write".equals(cmd)) {
            // [fix-1114 B13] 先把单独的 CR 统一成 LF，再给每一行（含第一行）开头的 '@ 前面加空格：here-string 只能在正文后面结束
            String body = ("\n" + String.valueOf(a.get("text")).replace("\r\n", "\n").replace('\r', '\n')).replace("\n'@", "\n '@").substring(1);
            command = "$text = @'\n" + body + "\n'@\nSet-Content -LiteralPath '" + str(a, "path").replace("'", "''") + "' -Value $text -Encoding UTF8";
            risk = "写工作区以外的文件，会覆盖同名文件";
        } else {
            command = "# " + cmd + " " + LinkPolicy.oneLine(String.valueOf(a));
            risk = "未知";
        }
        LinkPolicy.Script s = LinkPolicy.buildScript(LinkPolicy.ScriptKind.PS1, cmd, "来自 " + from + " 的远程请求。" + why, risk, command, System.currentTimeMillis());
        String rel = LinkPolicy.writeScript(workspace(), s);
        if (rel != null) try { notify.accept("已生成脚本，需你自己执行：" + rel); } catch (Exception ignored) { }
        return rel;
    }

    /** 电脑端内建的工作区指令（判定已经过了）。不是这些返回 null，交给 Actions。 */
    public Actions.Result builtin(String cmd, Map<String, Object> a) {
        File ws = workspace();
        try {
            switch (cmd) {
                case "level": {
                    Map<String, Object> d = new TreeMap<>();
                    d.put("sandbox", cfg.mode().sandbox.id);
                    d.put("approval", cfg.mode().approval.id);
                    return Actions.Result.ok("电脑档位：" + cfg.mode().zh(), d);
                }
                case "file_write": {
                    String t = a.get("text") == null ? "" : String.valueOf(a.get("text"));
                    if (t.length() > 256 * 1024) return Actions.Result.fail("文件太大（超过 256 KB）");
                    // .git / .codex / .agents：只有确认过（check 放行时打的内部标记）才写
                    if (LinkPolicy.protectedWorkspacePath(str(a, "path")) && !Boolean.TRUE.equals(a.get("_protected_ok"))) return Actions.Result.fail("工作区里的 .git / .codex / .agents 是只读的（要你确认后才写）");
                    if (Boolean.TRUE.equals(a.get("_outside"))) return writeOutside(str(a, "path"), t);
                    File f = LinkPolicy.writeInside(ws, str(a, "path"), t.getBytes(StandardCharsets.UTF_8));
                    return f == null ? Actions.Result.fail("只能写工作区里的文件") : Actions.Result.ok("已写入电脑工作区 " + str(a, "path"));
                }
                case "script_list": {
                    Map<String, Object> d = new TreeMap<>();
                    d.put("scripts", new ArrayList<Object>(LinkPolicy.listScripts(ws)));
                    return Actions.Result.ok("工作区脚本", d);
                }
                case "script_run": {
                    String n = str(a, "name");
                    File f = scriptFile(n);
                    if (f == null) return Actions.Result.fail("工作区 scripts/ 里没有这个脚本");
                    String low = n.toLowerCase(java.util.Locale.ROOT);
                    ProcessBuilder pb;
                    if (low.endsWith(".ps1")) pb = new ProcessBuilder("powershell", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File", f.getPath());
                    else if (low.endsWith(".bat")) pb = new ProcessBuilder("cmd", "/c", f.getPath());
                    else return Actions.Result.fail("电脑上只运行 .ps1 / .bat");
                    SafeExec.start(pb.directory(ws).redirectErrorStream(true));
                    return Actions.Result.ok("已开始运行 " + n);
                }
                case "open_app":
                    if (local()) return openAppLocal(str(a, "name"), str(a, "path"));
                    return null;
                case "sys_exec":
                    if (local()) {
                        Object t = a.get("timeout_sec");
                        return execLocal(str(a, "command"), ws, t instanceof Number ? ((Number) t).intValue() : 60);
                    }
                    return exec(str(a, "command"), ws);
                default:
                    return null;
            }
        } catch (SafeExec.Refused e) {
            Map<String, Object> d = new TreeMap<>();
            d.put("reason", LinkPolicy.R_PLUGIN_BLOCKED);
            d.put("reason_zh", LinkPolicy.reasonZh(LinkPolicy.R_PLUGIN_BLOCKED));
            return new Actions.Result(false, e.getMessage(), d);
        } catch (Exception e) {
            return Actions.Result.fail(cmd + " 出错：" + e.getClass().getSimpleName());
        }
    }

    /** [approvals-1118] 本机对话打开已装的程序（不带参数）：开始菜单快捷方式用 explorer 打开，exe 直接启动。 */
    Actions.Result openAppLocal(String name, String path) throws Exception {
        Map<String, String> env = System.getenv();
        File f = LocalLaunch.resolve(name, path, cfg.appMap(), LocalLaunch.startMenus(env), LocalLaunch.installRoots(env));
        if (f == null) return Actions.Result.fail("没找到已安装的程序「" + (name.isEmpty() ? path : name) + "」（只认开始菜单里的程序、设置里配置的程序和安装目录里的 exe；命令行工具请用 run_command）");
        ProcessBuilder pb = f.getName().toLowerCase(Locale.ROOT).endsWith(".lnk") ? new ProcessBuilder("explorer.exe", f.getAbsolutePath())
                : new ProcessBuilder(f.getAbsolutePath()).directory(f.getParentFile());
        SafeExec.start(pb);
        return Actions.Result.ok("已打开 " + f.getName().replaceAll("(?i)\\.(lnk|exe)$", ""));
    }

    File scriptFile(String n) {
        if (n == null || n.isEmpty() || n.contains("/") || n.contains("\\")) return null;
        File f = LinkPolicy.resolveInside(workspace(), "scripts/" + n);
        return f != null && f.isFile() ? f : null;
    }

    /** 工作区外写文件：按这台电脑当前用户的权限（Windows ACL 说了算），OneSay 是管理员时远程触发的拒绝。 */
    Actions.Result writeOutside(String path, String text) throws Exception {
        SafeExec.checkInProcessWrite();
        Path p = Paths.get(path);
        if (!p.isAbsolute()) return Actions.Result.fail("工作区以外的文件要给完整路径");
        Path parent = p.toAbsolutePath().getParent();
        if (parent != null) Files.createDirectories(parent);
        Files.writeString(p, text, StandardCharsets.UTF_8);
        return Actions.Result.ok("已写入 " + p);
    }

    /** 执行一条命令（判定和插件已经放行）：Windows 走 PowerShell，其他系统走 sh；不提权，工作目录是工作区，最多 60 秒。 */
    Actions.Result exec(String command, File ws) throws Exception {
        if (command.isBlank()) return Actions.Result.fail("没有命令");
        ProcessBuilder pb = SafeExec.WINDOWS
                ? new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", command)
                : new ProcessBuilder("sh", "-c", command);
        if (ws.isDirectory()) pb.directory(ws);
        Process p = SafeExec.start(pb.redirectErrorStream(true));
        p.getOutputStream().close();
        byte[] out = p.getInputStream().readNBytes(4000);
        if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); return Actions.Result.ok("命令还在跑（超过 60 秒，已停止等待）"); }
        Map<String, Object> d = new TreeMap<>();
        d.put("exit", (long) p.exitValue());
        d.put("output", new String(out, java.nio.charset.Charset.defaultCharset()).trim());
        return p.exitValue() == 0 ? Actions.Result.ok("命令执行完了", d) : new Actions.Result(false, "命令失败（" + p.exitValue() + "）", d);
    }

    /** PowerShell -EncodedCommand 的参数：UTF-16LE 再 base64。 */
    static String encodePs(String script) {
        return java.util.Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    /**
     * 命令输出解码：先按 UTF-8（命令里设了 OutputEncoding）；不是合法 UTF-8 的（PowerShell 在执行前就报的解析错误、
     * 老程序直接写控制台代码页）按 GBK（中文 Windows 的默认代码页）解，不再显示成一串「�」。
     */
    static String decodeOut(byte[] b) {
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(b)).toString();
        } catch (java.nio.charset.CharacterCodingException e) {
            try {
                return new String(b, java.nio.charset.Charset.forName("GBK"));
            } catch (RuntimeException e2) {
                return new String(b, StandardCharsets.UTF_8);
            }
        }
    }

    /** 本机对话的输出上限（字符）。 */
    static final int LOCAL_OUT_MAX = 16000;

    /**
     * [chat-agent-1118] 本机对话执行命令（判定已经放行）：输出另起线程读（不会因为输出多把管道塞满卡住），按 UTF-8 输出、最多 {@link #LOCAL_OUT_MAX} 字；
     * 超时（5–900 秒，默认 60）就结束进程并如实说「超时，已停止」；退出码非 0 回失败并带上输出。
     */
    Actions.Result execLocal(String command, File ws, int timeoutSec) throws Exception {
        if (command.isBlank()) return Actions.Result.fail("没有命令");
        if (!ws.isDirectory()) ws.mkdirs(); // [approvals-1118] 真机上工作区还没建：模型按提示里的工作目录做事，先建出来
        int to = Math.max(5, Math.min(900, timeoutSec));
        // -EncodedCommand（UTF-16LE base64）：Java 在 Windows 上拼命令行时不转义参数里的双引号，
        // 用 -Command 传 "…*MuMu*…" 这类带引号的命令会被拆坏（真机实测：Where-Object { $_.Name -like "*MuMu*" } 解析失败）。
        // 安全检查看的是模型给的原命令（check() 里），这里只是换个不会被拆坏的传法。
        ProcessBuilder pb = SafeExec.WINDOWS
                ? new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", encodePs(
                        "[Console]::OutputEncoding=[Text.Encoding]::UTF8;$ProgressPreference='SilentlyContinue';" + command))
                : new ProcessBuilder("sh", "-c", command);
        if (ws.isDirectory()) pb.directory(ws);
        Process p = SafeExec.start(pb.redirectErrorStream(true));
        p.getOutputStream().close();
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        Thread rd = new Thread(() -> {
            byte[] b = new byte[8192];
            try (java.io.InputStream in = p.getInputStream()) {
                int n;
                while ((n = in.read(b)) > 0) synchronized (buf) { if (buf.size() < LOCAL_OUT_MAX * 4) buf.write(b, 0, n); }
            } catch (java.io.IOException ignored) { }
        }, "onesay-chat-exec-out");
        rd.setDaemon(true);
        rd.start();
        boolean fin = p.waitFor(to, TimeUnit.SECONDS);
        if (!fin) p.destroyForcibly();
        rd.join(2000);
        String out;
        synchronized (buf) { out = decodeOut(buf.toByteArray()).trim(); }
        if (out.length() > LOCAL_OUT_MAX) out = out.substring(0, LOCAL_OUT_MAX) + "…（输出太长，已截断）";
        Map<String, Object> d = new TreeMap<>();
        d.put("output", out);
        if (!fin) return new Actions.Result(false, "命令超过 " + to + " 秒没结束，已停止", d);
        d.put("exit", (long) p.exitValue());
        return p.exitValue() == 0 ? Actions.Result.ok("命令执行完了", d) : new Actions.Result(false, "命令失败（退出码 " + p.exitValue() + "）", d);
    }

    Actions.Result result(boolean ok, String text, String reason, Map<String, Object> extra) {
        Map<String, Object> d = new TreeMap<>();
        if (extra != null) d.putAll(extra);
        if (reason != null && !reason.isEmpty()) {
            d.put("reason", reason);
            d.put("reason_zh", LinkPolicy.reasonZh(reason));
        }
        return new Actions.Result(ok, text, decorate(d));
    }

    /** 回复里带上电脑的执行级别（手机界面可以显示）。 */
    public Map<String, Object> decorate(Map<String, Object> d0) {
        Map<String, Object> d = d0 == null ? new TreeMap<>() : new TreeMap<>(d0);
        d.put("level", cfg.level().id);       // 旧字段：最接近的三档（旧手机界面读这个）
        d.put("level_zh", cfg.level().zh);
        d.put("sandbox", cfg.mode().sandbox.id);
        d.put("sandbox_zh", cfg.mode().sandbox.zh);
        d.put("approval", cfg.mode().approval.id);
        d.put("approval_zh", cfg.mode().approval.zh);
        return d;
    }

    static String str(Map<String, Object> a, String k) {
        Object v = a == null ? null : a.get(k);
        return v == null ? "" : String.valueOf(v).trim();
    }
}
