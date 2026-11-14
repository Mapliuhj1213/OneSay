package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.Assert.*;

/**
 * 找茬 · 确认令牌状态机：对一张刚签发的令牌穷举长度 1–3 的操作序列（批准 / 拒绝 / 原样执行 / 改参数执行 /
 * 挪到别的能力执行 / 污点翻转执行 / 过期），每一步都和一个按 ExecutionGate 注释写的独立模型比对：
 * 「只能由 evaluate 签发、一次性、绑定能力签名 + 参数摘要 + 污点、过期作废、拒绝后执行报 USER_REJECTED」。
 * 污点令牌只穷举到长度 2（污点翻转在它身上是「降级」，允许）。
 */
@RunWith(Parameterized.class)
public class AdvConfirmTokenSeqTest {

    static final String[] OPS = {"approve", "reject", "auth", "authArgs", "authSign", "authTaint", "expire"};

    @Parameterized.Parameters(name = "{index}: t{0} {1}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        for (int t = 0; t < 2; t++) {
            int maxLen = t == 0 ? 3 : 2;
            List<String> seqs = new ArrayList<String>();
            gen("", 0, maxLen, seqs);
            for (String s : seqs) out.add(new Object[]{t == 1, s});
        }
        return out;
    }

    static void gen(String prefix, int len, int max, List<String> out) {
        if (len > 0) out.add(prefix);
        if (len == max) return;
        for (String op : OPS) gen(prefix.length() == 0 ? op : prefix + "," + op, len + 1, max, out);
    }

    private final boolean tokenTainted;
    private final String seq;
    private long now = 1_800_000_000_000L;

    public AdvConfirmTokenSeqTest(boolean tokenTainted, String seq) {
        this.tokenTainted = tokenTainted;
        this.seq = seq;
    }

    @Before public void clock() {
        ExecutionGate.setClockForTest(new ExecutionGate.Clock() {
            @Override public long now() {
                return now;
            }
        });
    }

    private ExecutionGate.ConfirmToken issued;

    @After public void reset() {
        // 不留登记：这里用的是假时钟（未来时刻），留下的令牌不会按真实时间过期，会挤占登记表（上限 64）影响别的测试
        if (issued != null) ExecutionGate.reject(issued);
        ExecutionGate.setClockForTest(null);
        ExecutionGate.setStrictConfirm(true);
    }

    static Capability cap(String sign) {
        return new Capability.Builder(sign, "adv").risk(2, true).sideEffect(SideEffect.DEVICE_CONTROL).cmd("echo adv").build();
    }

    @Test public void sequence() throws Exception {
        Capability cap = cap("adv.tok.main");
        JSONObject args = new JSONObject().put("level", 30).put("who", "张三");
        ExecutionGate.Decision d = ExecutionGate.evaluate(cap, args, tokenTainted);
        assertTrue(d.needsConfirm());
        ExecutionGate.ConfirmToken tok = d.token;
        assertNotNull(tok);
        issued = tok;
        // 模型
        String state = "PENDING";
        boolean registered = true, expired = false;
        int step = 0;
        for (String op : seq.split(",")) {
            step++;
            String at = seq + " @" + step + " " + op;
            if (op.equals("expire")) {
                now += ExecutionGate.TOKEN_TTL_MS + 1;
                expired = true;
                continue;
            }
            if (op.equals("approve")) {
                boolean want = state.equals("PENDING") && !expired;
                assertEquals(at, want, ExecutionGate.approve(tok));
                if (want) state = "APPROVED";
                continue;
            }
            if (op.equals("reject")) {
                ExecutionGate.reject(tok);
                if (state.equals("PENDING") || state.equals("APPROVED")) state = "REJECTED";
                registered = false;
                continue;
            }
            Capability useCap = op.equals("authSign") ? cap("adv.tok.other") : cap;
            JSONObject useArgs = op.equals("authArgs") ? new JSONObject().put("level", 31).put("who", "张三") : new JSONObject(args.toString());
            boolean useTaint = op.equals("authTaint") ? !tokenTainted : tokenTainted;
            ExecutionResult r = ExecutionGate.authorize(useCap, useArgs, useTaint, tok);
            boolean taintOk = !(useTaint && !tokenTainted);
            boolean pass = registered && state.equals("APPROVED") && !expired && !op.equals("authSign") && !op.equals("authArgs") && taintOk;
            if (pass) {
                assertNull(at + " 应放行", r);
                assertEquals(at, ExecutionGate.TokenState.CONSUMED, tok.state());
                ExecutionGate.ConsumedReceipt rc = ExecutionGate.takeConsumedReceipt();
                assertNotNull(at + " 消费回执", rc);
                assertEquals(tok.id, rc.tokenId);
                state = "CONSUMED";
                registered = false;
            } else {
                assertNotNull(at + " 不该放行", r);
                assertNull(at + " 没消费就不该有回执", ExecutionGate.takeConsumedReceipt());
                if (state.equals("REJECTED")) assertEquals(at, ErrorCode.USER_REJECTED, r.code);
                else assertEquals(at, ErrorCode.RISK_GATED, r.code);
            }
            assertEquals(at, state, tok.state().name());
        }
    }
}
