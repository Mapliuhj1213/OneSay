package com.onesay.assistant.v2.executor;

import android.content.Context;
import android.util.Log;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.InterfaceKind;

import org.json.JSONArray;
import org.json.JSONObject;

import com.onesay.assistant.v2.util.IoUtil;

import java.io.File;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * 权限确认策略（，用户实测驱动）。
 *
 * <p>"分诊需要更新：先区分任务类型，再决定是否需要同意执行" 的落地件。职责三条：
 *
 * <ol>
 *   <li><b>放行决策</b>：{@link #gate} 在 ToolGate 之后再过一道"始终允许"白名单 ——
 *       命中即把 REQUIRE_CONFIRM 降级为 ALLOW（用户此前在黄卡上点过「始终允许」）。</li>
 *   <li><b>卡片分级</b>：{@link #tierOf} 把需要确认的操作分两档：
 *       <ul>
 *         <li><b>红卡</b>（现行设计不变）：不可信来源 / 不可逆删除类 / 恢复出厂 ——
 *             只给「取消 / 确认执行」，<b>没有</b>「始终允许」（不可逆的事不能永久放行）。</li>
 *         <li><b>黄卡</b>（新设计）：可恢复的敏感操作（重启 / 关机 / 电话 / 短信 / 数据漫游…）——
 *             三选项「始终允许 / 允许单次 / 拒绝」。</li>
 *       </ul></li>
 *   <li><b>「始终允许」持久化</b>：落盘 {@code files/consent_policy.json}，
 *       格式 {@code {"always_allow":["sys.reboot"]}}，重启后仍生效。</li>
 * </ol>
 *
 * <p>边界：本类只做**策略**，不碰 UI。红/黄卡片由 FloatingSheetView 按 tier 渲染；
 * 循环挂起等待由 FloatingSheetService 的确认会话（PendingLoopConfirm）负责。
 */
public class ConsentPolicy {

    private static final String TAG = "ConsentPolicy";
    private static final String FILE_NAME = "consent_policy.json";

    /** 卡片分级：黄卡（可恢复敏感操作，三选项）。 */
    public static final int TIER_YELLOW = 1;
    /** 卡片分级：红卡（不可逆 / 不可信，保持现行设计）。 */
    public static final int TIER_RED = 2;

    /**
     * 永远保持红卡的能力（不接受「始终允许」）—— 判据：不可逆。
     * 其余红卡由运行期条件派生：不可信来源（tainted）/ 删除类接口（Kind.DELETE）。
     */
    private static final Set<String> RED_SIGNS;

    static {
        Set<String> s = new HashSet<String>();
        s.add("sys.wipe");   // 恢复出厂设置：不可逆
        s.add("connectivity.airplane.on");   // ★ R13：飞行模式一开所有网络（含远程调试）全断、语音识别也随之失效 → 红卡，不给「始终允许」
        s.add("phone.call"); // ACTION_CALL 立即拨出、产生费用、不可撤回（CommsRisk）
        RED_SIGNS = Collections.unmodifiableSet(s);
    }

    private static final Object LOCK = new Object();
    private static final Set<String> sAlwaysAllow = new HashSet<String>();
    private static File sFile;

    /** 服务启动时调用（幂等）。加载持久化的「始终允许」清单。 */
    public static void init(Context ctx) {
        if (ctx == null) return;
        synchronized (LOCK) {
            sFile = new File(ctx.getApplicationContext().getFilesDir(), FILE_NAME);
            sAlwaysAllow.clear();
            try {
                if (!sFile.exists()) return;
                String text = IoUtil.readFileOrNull(sFile);
                if (text == null || text.length() == 0) return;
                JSONObject root = new JSONObject(text);
                JSONArray arr = root.optJSONArray("always_allow");
                if (arr == null) return;
                for (int i = 0; i < arr.length(); i++) {
                    String sign = arr.optString(i);
                    if (sign != null && sign.length() > 0) sAlwaysAllow.add(sign);
                }
                Log.i(TAG, "已加载「始终允许」清单: " + sAlwaysAllow);
            } catch (Throwable t) {
                Log.w(TAG, "加载 consent_policy.json 失败（按空清单处理）: " + t.getMessage());
            }
        }
    }

    /** 闸门决策的完整结果（decision + reason + 是否由「始终允许」放行）。 */
    public static final class Outcome {
        public final ToolGate.Decision decision;
        public final String reason;
        public final boolean viaAlwaysAllow;

        Outcome(ToolGate.Decision decision, String reason, boolean viaAlwaysAllow) {
            this.decision = decision;
            this.reason = reason;
            this.viaAlwaysAllow = viaAlwaysAllow;
        }
    }

    /**
     * 带策略的执行闸门：ToolGate.check + 「始终允许」白名单（门禁"策略"的唯一实现）。
     *
     * <p>调用方请改用 {@link ExecutionGate#evaluate}（同一套策略 + 卡片分级 + 确认令牌）。
     * 2026-10-23 审计 g3 D5（grep 核实）：生产代码里本方法只由 {@link ExecutionGate} 内部调用
     * （FloatingSheetService / AgentLoop 都已迁移到令牌入口），另有 debug 自测 V2SelfTest 直接调用。
     */
    public static Outcome gate(Capability cap, JSONObject args, boolean tainted) {
        ToolGate.GateResult base = ToolGate.check(cap, args, tainted);
        // 「始终允许」只对可信来源生效。不可信来源（tainted）按设计是红卡、没有「始终允许」选项
        //   （见 tierOf），但旧实现在这里不看 tainted —— 用户曾对某能力点过「始终允许」后，网页/邮件内容
        //   就能借它免确认触发同一能力。现与红卡规则对齐。
        // 红卡专属名单（不可逆）即使清单里残留「始终允许」也不放行（旧版黄卡时期可能点过）
        if (base.decision == ToolGate.Decision.REQUIRE_CONFIRM && !tainted
                && acceptsAlwaysAllow(cap)
                && isAlwaysAllowed(cap.sign)) {
            Log.i(TAG, "已按「始终允许」放行: " + cap.sign);
            return new Outcome(ToolGate.Decision.ALLOW, "已按「始终允许」放行", true);
        }
        return new Outcome(base.decision, base.reason, false);
    }

    /**
     * 卡片分级（"先区分任务类型，再决定怎么要授权"）。
     *
     * @param cap     目标能力
     * @param tainted 参数是否来自不可信内容（网页/搜索等）
     * @return {@link #TIER_RED} 或 {@link #TIER_YELLOW}
     */
    public static int tierOf(Capability cap, boolean tainted) {
        if (cap == null) return TIER_RED;
        if (isReadOnly(cap)) return TIER_YELLOW;   // 只读动作永不出「做了就撤不回来」红卡
        if (tainted) return TIER_RED;                                              // 不可信来源：不给「始终允许」
        if (cap.sign != null && RED_SIGNS.contains(cap.sign)) return TIER_RED;     // 不可逆专属名单
        if (InterfaceKind.of(cap) == InterfaceKind.Kind.DELETE) return TIER_RED;   // 删除类接口（卸载…）
        return TIER_YELLOW;
    }

    /**
     * 副作用 NONE / PRIVATE_DATA_READ，不在红卡名单，不是删除类接口。
     * 只读永远不是「不可撤销」：最多黄卡，绝不出红卡文案。
     */
    public static boolean isReadOnly(Capability cap) {
        if (cap == null || cap.sideEffect == null) return false;
        if (cap.sideEffect != com.onesay.assistant.v2.capability.SideEffect.NONE
                && cap.sideEffect != com.onesay.assistant.v2.capability.SideEffect.PRIVATE_DATA_READ) return false;
        if (cap.sign != null && RED_SIGNS.contains(cap.sign)) return false;
        return InterfaceKind.of(cap) != InterfaceKind.Kind.DELETE;
    }

    /** 本机只读、无需确认（risk &lt; 2 或不要求确认）、不是远程 MCP 工具：污点下也直接放行（见 ToolGate）。 */
    public static boolean isLocalReadOnly(Capability cap) {
        return isReadOnly(cap) && !(cap.risk >= 2 && cap.confirm) && !isRemoteConnTool(cap);
    }

    /** 内置连接器 id（远程 MCP 服务器不能用这些 id，见 McpServerConfig.validate）。 */
    private static final Set<String> BUILTIN_CONNECTORS;

    static {
        Set<String> s = new HashSet<String>();
        s.add("google");
        s.add("mail");
        s.add("tavily");
        BUILTIN_CONNECTORS = Collections.unmodifiableSet(s);
    }

    public static boolean isBuiltinConnectorId(String id) {
        return id != null && BUILTIN_CONNECTORS.contains(id);
    }

    /**
     * 远程 MCP 工具（domain=conn 且连接器不是内置的）。2026-10-24 r2/fixes：远程工具的副作用等级来自用户手填的白名单，
     * 本机无法核实它到底做什么（例如把 gmail 的 send_email 标成 write），所以<b>一律不认「始终允许」</b>，每次单独确认。
     */
    public static boolean isRemoteConnTool(Capability cap) {
        if (cap == null) return false;
        if (!"conn".equals(cap.domain)) return false;
        String sign = cap.sign == null ? "" : cap.sign;
        String id = sign.startsWith("conn.") ? sign.substring(5) : "";
        int dot = id.indexOf('.');
        if (dot > 0) id = id.substring(0, dot);
        return !isBuiltinConnectorId(id);
    }

    /**
     * 这个能力能不能被「始终允许」放行（也决定确认卡上显不显示「始终允许」）：
     * 红卡名单、通信类（电话 / 短信）、对外发送（PUBLIC_COMMS，发邮件等）、远程 MCP 工具都不行。
     */
    public static boolean acceptsAlwaysAllow(Capability cap) {
        if (cap == null || cap.sign == null) return false;
        if (RED_SIGNS.contains(cap.sign)) return false;
        if (ToolGate.oddSign(cap.sign).length() > 0) return false; // 名字写法可疑的工具：每次都问，不能记住
        if (CommsRisk.isComms(cap.sign)) return false;
        if (cap.sideEffect == com.onesay.assistant.v2.capability.SideEffect.PUBLIC_COMMS) return false;   // [conn] 对外发送每次都要确认
        if (isRemoteConnTool(cap)) return false;
        return true;
    }

    /** 确认卡上是否给「始终允许」按钮（黄卡且 {@link #acceptsAlwaysAllow}）。 */
    public static boolean offersAlwaysAllow(Capability cap, boolean tainted) {
        return tierOf(cap, tainted) == TIER_YELLOW && acceptsAlwaysAllow(cap);
    }

    /** 写入「始终允许」（带能力契约的版本）：不接受的能力直接拒收，不留无用记录。 */
    public static void allowAlways(Capability cap) {
        if (!acceptsAlwaysAllow(cap)) {
            Log.i(TAG, "该能力不接受「始终允许」: " + (cap == null ? "null" : cap.sign));
            return;
        }
        allowAlways(cap.sign);
    }

    public static boolean isAlwaysAllowed(String sign) {
        if (sign == null) return false;
        synchronized (LOCK) {
            return sAlwaysAllow.contains(sign);
        }
    }

    /** 写入「始终允许」并落盘（幂等）。 */
    public static void allowAlways(String sign) {
        if (sign == null || sign.length() == 0) return;
        // 通信类（打电话 / 发短信）永远逐次确认，不接受「始终允许」（卡片上也不显示该键）
        if (CommsRisk.isComms(sign)) {
            Log.i(TAG, "通信类不接受「始终允许」: " + sign);
            return;
        }
        synchronized (LOCK) {
            if (!sAlwaysAllow.add(sign)) return;
            saveLocked();
            Log.i(TAG, "新增「始终允许」: " + sign + " → " + sAlwaysAllow);
        }
    }

    /** ★ console-align：「以后这类不用问」清单（控制台 › 权限与审批，按名字排序的副本）。 */
    public static java.util.List<String> alwaysAllowedList() {
        synchronized (LOCK) {
            java.util.List<String> l = new java.util.ArrayList<String>(sAlwaysAllow);
            Collections.sort(l);
            return l;
        }
    }

    /** 已经读过落盘文件（服务或控制台调过 {@link #init}）。 */
    public static boolean initialized() {
        synchronized (LOCK) {
            return sFile != null;
        }
    }

    /** 撤销「始终允许」（自测/调试用；★ console-align 起控制台「权限与审批」也用）。 */
    public static void revoke(String sign) {
        if (sign == null) return;
        synchronized (LOCK) {
            if (!sAlwaysAllow.remove(sign)) return;
            saveLocked();
            Log.i(TAG, "撤销「始终允许」: " + sign + " → " + sAlwaysAllow);
        }
    }

    private static void saveLocked() {
        if (sFile == null) {
            Log.w(TAG, "尚未 init(context)，仅内存生效");
            return;
        }
        try {
            JSONObject root = new JSONObject();
            JSONArray arr = new JSONArray();
            for (String s : sAlwaysAllow) arr.put(s);
            root.put("always_allow", arr);
            // 原子写（tmp + rename），进程中途被杀也不会留下半截 JSON
            if (!IoUtil.writeFileAtomic(sFile, root.toString())) {
                Log.w(TAG, "保存 consent_policy.json 失败");
            }
        } catch (Throwable t) {
            Log.w(TAG, "保存 consent_policy.json 失败: " + t.getMessage());
        }
    }
}
