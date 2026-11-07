package com.onesay.assistant.v2.executor;

import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 执行门禁的<b>唯一入口</b> + 确认令牌（P1-7）。
 *
 * <h3>解决什么</h3>
 * <ul>
 *   <li>E4：同一个门禁（ToolGate + ConsentPolicy「始终允许」）在 dispatch、AgentLoop、executeInternal 三处各算一次；</li>
 *   <li>确认状态靠全局布尔 {@code mNextExecConfirmed} 传递，与具体 (能力, 参数) 没有绑定 ——
 *       并发会话下"在 A 卡上点确认"可能放行 B 步骤；而 {@code executeInternal(..., isConfirmed=true, ...)}
 *       这个布尔谁都能传，确认卡形同虚设。</li>
 * </ul>
 *
 * <h3>新流程</h3>
 * <pre>
 *   Decision d = ExecutionGate.evaluate(cap, args, tainted);   // 唯一的门禁计算（ToolGate + 始终允许 + 卡片分级）
 *   ALLOW          → executor.execute(cap, args, null, tainted)
 *   DENY           → 不执行（GATE_DENIED）
 *   REQUIRE_CONFIRM→ 弹卡（d.tier 决定红/黄卡），d.token 随卡片保存
 *                    用户点确认 → ExecutionGate.approve(d.token) → executor.execute(cap, args, d.token, tainted)
 *                    用户点拒绝 → ExecutionGate.reject(d.token)
 * </pre>
 * 令牌不可伪造（私有构造 + 进程内登记表 + 128 位随机 id），并绑定：能力签名、参数摘要（规范化 JSON 的 SHA-256）、
 * 污点标记、过期时间（{@link #TOKEN_TTL_MS}）；<b>一次性</b>，执行即作废。参数在确认之后被改动、令牌被挪用到别的能力、
 * 或过期，都会被拒绝。
 *
 * <h3>兼容</h3>
 * 旧入口 {@code UnifiedExecutor.executeInternal(cap, args, isConfirmed, isTainted)} 仍保留。2026-10-23 审计 g3（grep 核实）：
 * 生产代码里只有 debug 自测（TestRunReceiver / V2SelfTest）在调，且都传 {@code isConfirmed=false}；FloatingSheetService
 * 与 AgentLoop 已迁移到令牌入口。严格模式默认开启（r2/fixes 11，类加载即生效；FloatingSheetService.onCreate 里的
 * {@link #setStrictConfirm(boolean) setStrictConfirm(true)} 只是保险），
 * 严格模式下旧布尔不能绕过确认；非严格模式下 {@code isConfirmed=true} 仍放行并打 {@code [GATE-LEGACY]} 日志（目前没有调用方这样传）。
 */
public final class ExecutionGate {

    private static final String TAG = "ExecutionGate";

    /** 令牌有效期：与 FloatingSheetService 确认卡最长等待（120s）一致。 */
    public static final long TOKEN_TTL_MS = 120000L;

    /** 登记表上限（防泄漏：只有弹卡才产生令牌，正常不会超过个位数）。 */
    private static final int MAX_TOKENS = 64;

    /** 可替换时钟（单测）。 */
    public interface Clock {
        long now();
    }

    private static final Clock SYSTEM_CLOCK = new Clock() {
        @Override
        public long now() {
            return System.currentTimeMillis();
        }
    };

    private static volatile Clock sClock = SYSTEM_CLOCK;
    /**
     * 严格确认模式。2026-10-24 r2/fixes 11：<b>默认就开</b>（类一加载即生效，早于 Application / 内核 / 任何服务），
     * 不再等 FloatingSheetService.onCreate 才打开——之前服务起来前（例如定时任务、广播先把进程拉起）旧布尔入口
     * {@code executeInternal(..., isConfirmed=true, ...)} 还能绕过确认卡。只有单测会显式关掉它来测兼容层。
     */
    private static volatile boolean sStrictConfirm = true;
    private static final SecureRandom RNG = new SecureRandom();
    private static final Map<String, ConfirmToken> TOKENS = new HashMap<String, ConfirmToken>();

    private ExecutionGate() {}

    // ------------------------------------------------------------------ 类型

    /** 令牌状态。 */
    public enum TokenState { PENDING, APPROVED, REJECTED, CONSUMED }

    /** 确认令牌：只能由 {@link #evaluate} 签发。 */
    public static final class ConfirmToken {
        public final String id;
        public final String sign;
        final String argsDigest;
        public final boolean tainted;
        public final long expiresAt;
        private TokenState state = TokenState.PENDING;

        private ConfirmToken(String id, String sign, String argsDigest, boolean tainted, long expiresAt) {
            this.id = id;
            this.sign = sign;
            this.argsDigest = argsDigest;
            this.tainted = tainted;
            this.expiresAt = expiresAt;
        }

        public synchronized TokenState state() {
            return state;
        }

        @Override
        public String toString() {
            return "ConfirmToken{" + sign + ", " + state() + ", id=" + id.substring(0, 8) + "…}";
        }
    }

    /** 门禁结论。 */
    public static final class Decision {
        public final ToolGate.Decision decision;
        public final String reason;
        public final boolean viaAlwaysAllow;
        /** 需要确认时的卡片分级（{@link ConsentPolicy#TIER_YELLOW} / {@link ConsentPolicy#TIER_RED}），否则 0。 */
        public final int tier;
        /** 仅 REQUIRE_CONFIRM 时非空。 */
        public final ConfirmToken token;

        Decision(ToolGate.Decision decision, String reason, boolean viaAlwaysAllow, int tier, ConfirmToken token) {
            this.decision = decision;
            this.reason = reason;
            this.viaAlwaysAllow = viaAlwaysAllow;
            this.tier = tier;
            this.token = token;
        }

        public boolean allowed() { return decision == ToolGate.Decision.ALLOW; }
        public boolean needsConfirm() { return decision == ToolGate.Decision.REQUIRE_CONFIRM; }
        public boolean denied() { return decision == ToolGate.Decision.DENY; }
    }

    // ------------------------------------------------------------------ 入口

    /**
     * 唯一的门禁计算：ToolGate（风险 + 污点）→ ConsentPolicy「始终允许」→ 卡片分级；需要确认时签发令牌。
     * 不执行任何动作，可在任何线程调用。
     */
    public static Decision evaluate(Capability cap, JSONObject args, boolean tainted) {
        // ★ r2/screenoff-wake：锁屏时（面板可浮在锁屏上方语音回答）有副作用 / 读隐私的能力一律先解锁 —— 排在「始终允许」之前
        String lockWhy = com.onesay.assistant.v2.wake.ScreenOffPolicy.unlockDenyReason(cap);
        if (lockWhy != null) return new Decision(ToolGate.Decision.DENY, lockWhy, false, 0, null);
        ConsentPolicy.Outcome o = ConsentPolicy.gate(cap, args, tainted);
        if (o.decision == ToolGate.Decision.REQUIRE_CONFIRM) {
            ConfirmToken t = issue(cap, args, tainted);
            return new Decision(o.decision, o.reason, false, ConsentPolicy.tierOf(cap, tainted), t);
        }
        return new Decision(o.decision, o.reason, o.viaAlwaysAllow, 0, null);
    }

    /**
     * 用户在确认卡上点了「确认 / 允许单次 / 始终允许」。@return 令牌是否仍有效并已批准
     *
     * <p><b>调用方受限</b>（2026-10-24 r2/fixes 10）：批准令牌 = 代表用户点了确认。生产代码里只允许
     * {@link #APPROVE_CALLERS} 列出的类调用（确认卡会话 PendingConfirm、AgentLoop 确认卡回调、邮件确认卡 A 线 → B 线桥
     * LaneBMailPorts），外加只进 debug 包的 test 包。{@code ExecutionGateApproveCallersTest} 扫源码守住这张表：
     * 任何人新增「evaluate → approve → execute」的组合都会让测试失败。
     */
    public static boolean approve(ConfirmToken t) {
        if (t == null) return false;
        synchronized (t) {
            if (t.state != TokenState.PENDING || sClock.now() > t.expiresAt) return false;
            t.state = TokenState.APPROVED;
            return true;
        }
    }

    /** 用户拒绝 / 卡片超时关闭。 */
    public static void reject(ConfirmToken t) {
        if (t == null) return;
        synchronized (t) {
            if (t.state == TokenState.PENDING || t.state == TokenState.APPROVED) t.state = TokenState.REJECTED;
        }
        unregister(t);
    }

    // ------------------------------------------------------------------ 消费回执（2026-10-22 B 线）

    /**
     * [conn] 令牌消费回执：{@link #authorize} 校验并<b>消费</b>了一张已批准的确认令牌后，在<b>当前线程</b>留下一张回执，
     * 供执行后端做「必须是本次卡片确认」的二次核对（邮件发送 MailSendGuard 用）。旧布尔入口、「始终允许」、
     * 不需要确认的放行都<b>不留</b>回执，所以拿不到回执 = 这次执行没有经过确认卡。
     */
    public static final class ConsumedReceipt {
        public final String tokenId;
        public final String sign;
        public final String argsDigest;
        public final boolean tainted;
        public final long at;

        ConsumedReceipt(String tokenId, String sign, String argsDigest, boolean tainted, long at) {
            this.tokenId = tokenId;
            this.sign = sign;
            this.argsDigest = argsDigest;
            this.tainted = tainted;
            this.at = at;
        }
    }

    private static final ThreadLocal<ConsumedReceipt> RECEIPT = new ThreadLocal<ConsumedReceipt>();

    /** 取走当前线程最近一次令牌消费的回执（一次性；没有返回 null）。 */
    public static ConsumedReceipt takeConsumedReceipt() {
        ConsumedReceipt r = RECEIPT.get();
        RECEIPT.remove();
        return r;
    }

    /** 参数摘要（与令牌里记录的同一算法），供回执核对。 */
    public static String argsDigest(JSONObject args) {
        return digest(args);
    }

    /**
     * 执行前授权（UnifiedExecutor 内部调用）。重新计算门禁（策略可能在弹卡后变化），需要确认时校验并<b>消费</b>令牌。
     *
     * @return null = 放行；否则为应直接返回给调用方的失败/挂起结果
     */
    static ExecutionResult authorize(Capability cap, JSONObject args, boolean tainted, ConfirmToken token) {
        RECEIPT.remove();
        ExecutionResult locked = lockedDenied(cap, args);   // ★ r2/screenoff-wake
        if (locked != null) return locked;
        ConsentPolicy.Outcome o = ConsentPolicy.gate(cap, args, tainted);
        if (o.decision == ToolGate.Decision.DENY) {
            return ExecutionResult.failed(ErrorCode.GATE_DENIED, o.reason, "GateDenied: " + o.reason, cap, args);
        }
        if (o.decision == ToolGate.Decision.ALLOW) return null;
        // REQUIRE_CONFIRM
        if (token == null) return ExecutionResult.gated(cap, args);
        String why = validateAndConsume(token, cap, args, tainted);
        if (why == null) return null;
        Log.w(TAG, "[GATE] 令牌校验失败 " + (cap != null ? cap.sign : "null") + ": " + why);
        if (token.state() == TokenState.REJECTED) {
            return ExecutionResult.failed(ErrorCode.USER_REJECTED, "你已拒绝执行该操作", "token rejected", cap, args);
        }
        // 令牌无效：退回"需要确认"，让 UI 重新弹卡（绝不执行）
        ExecutionResult g = ExecutionResult.gated(cap, args);
        return new ExecutionResult(false, ErrorCode.RISK_GATED, ExecutionResult.Verification.UNVERIFIED,
                g.userMessage, "invalid confirm token: " + why, null, cap, args);
    }

    /**
     * 旧布尔入口的授权（兼容层；2026-10-23 生产调用方只剩 debug 自测，且都传 isConfirmed=false）。
     * 非严格模式下 {@code isConfirmed=true} 照旧放行（打日志）；严格模式下等同于没有令牌。
     */
    static ExecutionResult authorizeLegacy(Capability cap, JSONObject args, boolean tainted, boolean isConfirmed) {
        RECEIPT.remove();   // [conn] 旧布尔入口永远不留消费回执
        ExecutionResult locked = lockedDenied(cap, args);   // ★ r2/screenoff-wake
        if (locked != null) return locked;
        ConsentPolicy.Outcome o = ConsentPolicy.gate(cap, args, tainted);
        if (o.decision == ToolGate.Decision.DENY) {
            return ExecutionResult.failed(ErrorCode.GATE_DENIED, o.reason, "GateDenied: " + o.reason, cap, args);
        }
        if (o.decision == ToolGate.Decision.ALLOW) return null;
        if (isConfirmed && !sStrictConfirm) {
            Log.w(TAG, "[GATE-LEGACY] 以布尔 isConfirmed 放行需确认操作（待迁移到 ConfirmToken）: "
                    + (cap != null ? cap.sign : "null"));
            return null;
        }
        return ExecutionResult.gated(cap, args);
    }

    /** ★ r2/screenoff-wake：锁屏 + 需要解锁的能力 → 直接拒绝（不弹卡、不消费令牌；解锁后重说即可）。 */
    private static ExecutionResult lockedDenied(Capability cap, JSONObject args) {
        String why = com.onesay.assistant.v2.wake.ScreenOffPolicy.unlockDenyReason(cap);
        if (why == null) return null;
        Log.i(TAG, "[GATE] 锁屏中拒绝 " + (cap != null ? cap.sign : "null") + "（需要先解锁）");
        return ExecutionResult.failed(ErrorCode.GATE_DENIED, why, "KeyguardLocked", cap, args);
    }

    /** 允许调用 {@link #approve} 的生产源文件（相对 app/src/com/onesay/assistant/v2/）。改这张表要有充分理由。 */
    public static final String[] APPROVE_CALLERS = {
            "executor/ExecutionGate.java",
            "executor/PendingConfirm.java",
            "router/AgentLoop.java",
            "conn/port/LaneBMailPorts.java",
            // ★ feat/shortcuts：快捷指令逐步执行，和 AgentLoop 同一模式——只在宿主确认卡返回「允许」后 approve（ShortcutRunnerTest 守住）
            "shortcut/ShortcutRunner.java",
    };

    // ------------------------------------------------------------------ 配置

    /** 严格模式：旧布尔 isConfirmed 不再能放行需确认操作。r2/fixes 11 起默认 true；传 false 只给单测测兼容路径用。 */
    public static void setStrictConfirm(boolean strict) {
        sStrictConfirm = strict;
    }

    public static boolean isStrictConfirm() {
        return sStrictConfirm;
    }

    /** 单测注入时钟；传 null 恢复系统时钟。 */
    static void setClockForTest(Clock c) {
        sClock = c != null ? c : SYSTEM_CLOCK;
    }

    static int pendingTokenCountForTest() {
        synchronized (TOKENS) {
            return TOKENS.size();
        }
    }

    // ------------------------------------------------------------------ 内部

    private static ConfirmToken issue(Capability cap, JSONObject args, boolean tainted) {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        String id = hex(b);
        ConfirmToken t = new ConfirmToken(id, cap != null ? cap.sign : "", digest(args), tainted,
                sClock.now() + TOKEN_TTL_MS);
        synchronized (TOKENS) {
            purgeLocked();
            if (TOKENS.size() >= MAX_TOKENS) {
                // 极端情况：丢最早过期的
                String oldest = null;
                long min = Long.MAX_VALUE;
                for (ConfirmToken x : TOKENS.values()) {
                    if (x.expiresAt < min) { min = x.expiresAt; oldest = x.id; }
                }
                if (oldest != null) TOKENS.remove(oldest);
            }
            TOKENS.put(id, t);
        }
        return t;
    }

    /** @return null = 通过并已消费；否则为失败原因 */
    private static String validateAndConsume(ConfirmToken t, Capability cap, JSONObject args, boolean tainted) {
        synchronized (TOKENS) {
            if (TOKENS.get(t.id) != t) return "unknown token";
        }
        synchronized (t) {
            if (t.state == TokenState.CONSUMED) return "already used";
            if (t.state == TokenState.REJECTED) return "rejected";
            if (t.state != TokenState.APPROVED) return "not approved";
            if (sClock.now() > t.expiresAt) return "expired";
            String sign = cap != null ? cap.sign : "";
            if (!t.sign.equals(sign)) return "sign mismatch (" + t.sign + " vs " + sign + ")";
            if (!t.argsDigest.equals(digest(args))) return "args changed after confirmation";
            if (tainted && !t.tainted) return "taint escalated after confirmation";
            t.state = TokenState.CONSUMED;
            RECEIPT.set(new ConsumedReceipt(t.id, t.sign, t.argsDigest, t.tainted, sClock.now()));   // [conn] 消费回执
        }
        unregister(t);
        return null;
    }

    private static void unregister(ConfirmToken t) {
        synchronized (TOKENS) {
            TOKENS.remove(t.id);
        }
    }

    private static void purgeLocked() {
        long now = sClock.now();
        Iterator<Map.Entry<String, ConfirmToken>> it = TOKENS.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().expiresAt < now) it.remove();
        }
    }

    /** 参数摘要：规范化（键排序）JSON 的 SHA-256。 */
    static String digest(JSONObject args) {
        try {
            String canon = canonical(args == null ? new JSONObject() : args);
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return hex(md.digest(canon.getBytes("UTF-8")));
        } catch (Throwable t) {
            return "x";
        }
    }

    static String canonical(Object v) {
        if (v instanceof JSONObject) {
            JSONObject o = (JSONObject) v;
            List<String> keys = new ArrayList<String>();
            Iterator<?> it = o.keys();
            while (it.hasNext()) keys.add(String.valueOf(it.next()));
            Collections.sort(keys);
            StringBuilder sb = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append(JSONObject.quote(keys.get(i))).append(':').append(canonical(o.opt(keys.get(i))));
            }
            return sb.append('}').toString();
        }
        if (v instanceof JSONArray) {
            JSONArray a = (JSONArray) v;
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < a.length(); i++) {
                if (i > 0) sb.append(',');
                sb.append(canonical(a.opt(i)));
            }
            return sb.append(']').toString();
        }
        if (v == null || v == JSONObject.NULL) return "null";
        if (v instanceof String) return JSONObject.quote((String) v);
        if (v instanceof Number) {
            // 30 与 30.0 视为同一参数
            double d = ((Number) v).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d)) return String.valueOf((long) d);
            return String.valueOf(d);
        }
        return String.valueOf(v);
    }

    private static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) {
            sb.append(Character.forDigit((x >> 4) & 0xF, 16)).append(Character.forDigit(x & 0xF, 16));
        }
        return sb.toString();
    }
}
