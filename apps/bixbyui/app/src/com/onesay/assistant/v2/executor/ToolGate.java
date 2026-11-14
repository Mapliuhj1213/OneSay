package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;
import org.json.JSONObject;

/**
 * 确定性执行闸门（拦截不可信内容直达高危动作）。
 * 等价于 Apple App Intents 的 .onToolCall 拦截器。
 */
public class ToolGate {

    public enum Decision {
        ALLOW,
        REQUIRE_CONFIRM,
        DENY
    }

    public static class GateResult {
        public final Decision decision;
        public final String reason;

        public GateResult(Decision decision, String reason) {
            this.decision = decision;
            this.reason = reason;
        }
    }

    /**
     * 纯函数：执行前唯一一道确定性闸门。
     * 输入 = 静态元数据 + 运行时状态，输出 = 允许执行 / 需确认 / 直接拒绝。
     * 闸门只能把"直接跑"升级成"要确认"或"拒绝"，不能反向。
     */
    /** ★ 11-04 E：污点下也免确认的本机新建类动作（不删除、不批量、不外发、不花钱，用户随时可撤）。 */
    public static final java.util.Set<String> BENIGN_CREATE = java.util.Collections.unmodifiableSet(new java.util.HashSet<String>(
            java.util.Arrays.asList("clock.alarm.set", "clock.timer.set")));

    public static boolean isBenignCreate(Capability cap) {
        return cap != null && cap.sign != null && BENIGN_CREATE.contains(cap.sign) && cap.sideEffect != SideEffect.ROOT
                && cap.sideEffect != SideEffect.DESTRUCTIVE && cap.sideEffect != SideEffect.PUBLIC_COMMS;
    }

    /**
     * 工具名写法可疑（同形字、全角、零宽、空白等非 ASCII 可见字符）：守卫按名字认外发 / 只读认不准。返回给人看的原因，正常名字返回 ""。
     * 2026-11-14 对抗测试 P2-4（用户定：这类问题弹出确认就行）：这种工具一律要确认，且不收「始终允许」（{@link ConsentPolicy#acceptsAlwaysAllow}）。
     */
    public static String oddSign(String sign) {
        if (sign == null) return "";
        for (int i = 0; i < sign.length(); i++) {
            char c = sign.charAt(i);
            if (c < 0x21 || c > 0x7e) return "工具名写法可疑：有同形字 / 全角 / 不可见字符";
        }
        return "";
    }

    /** 纯回答（给用户说话，不对外发送）：和 RiskLabel.outboundSign 同一份豁免。 */
    static boolean pureAnswer(String sign) {
        String s = sign == null ? "" : sign.toLowerCase(java.util.Locale.ROOT);
        return s.equals("ask.reply") || s.equals("chat.reply") || s.equals("clarify");
    }

    public static GateResult check(Capability cap, JSONObject args, boolean isTainted) {
        GateResult r = check0(cap, args, isTainted);
        if (cap != null && r.decision == Decision.ALLOW) {
            String odd = oddSign(cap.sign);
            if (odd.length() > 0) return new GateResult(Decision.REQUIRE_CONFIRM, odd + "，守卫认不准，先问你");
        }
        return r;
    }

    private static GateResult check0(Capability cap, JSONObject args, boolean isTainted) {
        if (cap == null) {
            return new GateResult(Decision.DENY, "未指定可执行能力契约");
        }

        // 通信类（phone.call / sms.send）先做号码分级：控制字符 / 非号码直接拒绝（不弹卡）
        CommsRisk.Assessment comms = CommsRisk.assess(cap.sign, args);
        if (comms.level == CommsRisk.Level.DENY) {
            return new GateResult(Decision.DENY, "安全拦截：" + comms.reason);
        }

        // 基础门槛：原有的高危动作门禁（静态声明为风险>=2且需确认）
        boolean baseConfirmRequired = (cap.risk >= 2 && cap.confirm);

        // 如果调用源被污染（来自不可信内容如网页搜索）
        if (isTainted) {
            // ROOT 级别强制拒绝，不可旁路
            if (cap.sideEffect == SideEffect.ROOT) {
                return new GateResult(Decision.DENY, "安全拦截：不可信来源内容禁止触发最高系统权限");
            }
            
            // ★ borrow-1112：读过网页 / 通知 / 短信等外部内容后，发送 / 分享 / 提交类（按工具名认）一律要确认（防提示注入）。
            //   只加严：其余判断照旧；「始终允许」在污点下本来就不生效（ConsentPolicy.gate）。
            //   2026-11-14 对抗测试 P2-4b：除了 RiskLabel 的小写正则，再用 OutboundJudge（先归一化同形字 / 全角 / 零宽，认 dispatch / deliver /
            //   enqueue / push 这类同义动词）认一遍，副作用标成 NONE 的远程工具也不会因为名字换个写法就在污点下直接放行。
            if (com.onesay.assistant.v2.phone.RiskLabel.outboundSign(cap.sign)
                    || (!pureAnswer(cap.sign) && com.onesay.assistant.v2.security.OutboundJudge.signLooksOutbound(cap.sign))) {
                return new GateResult(Decision.REQUIRE_CONFIRM, "安全拦截：本次任务读过外部内容，发送 / 分享 / 提交前必须确认");
            }

            // 本机只读（读屏 / 查记忆 / 列表，risk 0、不需确认、不是远程 MCP 工具）在污点下也放行——
            //   读本身不改任何东西；真正外发（发送 / 写入 / 设备控制）的下一步仍在下面强制确认。避免「看一眼」弹确认卡。
            if (ConsentPolicy.isLocalReadOnly(cap)) {
                return new GateResult(Decision.ALLOW, "只读，允许执行");
            }
            // ★ 11-04 E（用户：「30分钟后叫醒我」还要确认，没必要）：新建闹钟 / 计时器是本机、可撤销、不外发、不花钱的动作，
            //   污点（会话历史读过网页）下也直接放行；删除 / 批量 / 花钱 / 对外发送仍按下面强制确认。
            //   2026-11-14 对抗测试 P2-4a：只在能力本身不要求确认时才放行（静态声明 risk2+确认的，污点下不能比不污点更宽）。
            if (isBenignCreate(cap) && !baseConfirmRequired) {
                return new GateResult(Decision.ALLOW, "新建闹钟 / 计时器，允许执行");
            }
            // 破坏性操作、隐私数据读取、持久化写入等有明显副作用的，强制确认
            if (cap.sideEffect == SideEffect.DESTRUCTIVE ||
                cap.sideEffect == SideEffect.PUBLIC_COMMS ||
                cap.sideEffect == SideEffect.PRIVATE_DATA_READ ||
                cap.sideEffect == SideEffect.PERSISTENT_WRITE ||
                cap.sideEffect == SideEffect.DEVICE_CONTROL) {
                return new GateResult(Decision.REQUIRE_CONFIRM, "安全拦截：该操作的参数来源不可信，必须二次确认");
            }
            
            // 若能力本身就需要确认，继承
            if (baseConfirmRequired) {
                return new GateResult(Decision.REQUIRE_CONFIRM, "需二次确认执行");
            }
        }

        if (baseConfirmRequired) {
            if (comms.level == CommsRisk.Level.RED || comms.level == CommsRisk.Level.YELLOW) {
                return new GateResult(Decision.REQUIRE_CONFIRM, "高危敏感操作需手动确认：" + comms.reason);
            }
            return new GateResult(Decision.REQUIRE_CONFIRM, "高危敏感操作需手动确认");
        }

        // 其他均放行
        return new GateResult(Decision.ALLOW, "允许执行");
    }
}
