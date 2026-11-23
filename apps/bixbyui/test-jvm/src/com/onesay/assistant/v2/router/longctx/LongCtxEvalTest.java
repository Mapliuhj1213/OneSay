package com.onesay.assistant.v2.router.longctx;

import org.junit.Test;

import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ fix/1123-longctx-phone：长会话评测（假模型、确定性）。
 *
 * <p>改前（1ee84af 原样代码）只有旧做法；改后同一个测试再跑新做法（LongCtxEvalNewDriver）和「总开关关」（应与改前逐项相同）。
 * 结果表写到 {@code -Dlongctx.eval.out=<文件>}（缺省只打印）。
 */
public class LongCtxEvalTest {

    /** 评测时把会话历史预算压到 4k token，用 40 轮左右就能逼出「历史被折叠」的长会话状态（真机默认 32k，要上百轮）。 */
    public static final int EVAL_BUDGET = 4096;
    /** 加压比例。 */
    public static final int STRESS = 30;
    /** 接力对照的门槛（轮）。 */
    public static final int RELAY = 20;

    static Class<?> newDriver() {
        try {
            return Class.forName("com.onesay.assistant.v2.router.longctx.LongCtxEvalNewDriver");
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** 改前（1ee84af 原样代码）同一评测的合计（OUT.md 里有整表），总开关关时必须逐项复现。 */
    static final double[] BASE = {58.2, 31.7, 46.2, 15.4, 26.0, 66.7};
    static final long BASE_HEAVY = 1273, BASE_TOKENS = 2927;

    static double r1(double v) {
        return Math.round(v * 10) / 10.0;
    }

    @Test public void evaluate() throws Exception {
        StringBuilder out = new StringBuilder();
        Class<?> nd = newDriver();
        if (nd == null) {
            List<LongCtxEval.Score> legacy = LongCtxEval.run(new LongCtxEval.Legacy(), EVAL_BUDGET);
            out.append("### 旧做法（1ee84af 原样代码）\n").append(LongCtxEval.table(legacy)).append('\n');
            LongCtxEval.Score lt = legacy.get(legacy.size() - 1);
            assertTrue("评测集要有足够的轮次：" + lt.turns, lt.turns >= 200);
            assertTrue("评测集要有回问事实：" + lt.recall, lt.recall >= 10);
        } else {
            try {
                LongCtxEval.Driver off = (LongCtxEval.Driver) nd.getConstructor(boolean.class).newInstance(false);
                List<LongCtxEval.Score> offRows = LongCtxEval.run(off, EVAL_BUDGET);
                out.append("### 改后 · 总开关关（应与改前逐项相同）\n").append(LongCtxEval.table(offRows)).append('\n');
                LongCtxEval.Driver on = (LongCtxEval.Driver) nd.getConstructor(boolean.class).newInstance(true);
                List<LongCtxEval.Score> onRows = LongCtxEval.run(on, EVAL_BUDGET);
                out.append("### 改后 · 默认（开）\n").append(LongCtxEval.table(onRows)).append('\n');
                LongCtxEval.Score o = onRows.get(onRows.size() - 1);
                LongCtxEval.Score f = offRows.get(offRows.size() - 1);
                double[] got = {r1(f.echoRate()), r1(f.offTopicRate()), r1(f.forgetRate()), r1(f.hallucRate()), r1(f.personaRate()), r1(f.skillRate())};
                assertArrayEquals("总开关关 = 改前逐项（复读 / 偏题 / 遗忘 / 幻觉 / 人设 / 技能）", BASE, got, 0.001);
                assertEquals("总开关关 = 改前（重活外溢）", BASE_HEAVY, f.heavyPerReq());
                assertEquals("总开关关 = 改前（平均请求 token）", BASE_TOKENS, f.avgTokens());
                assertEquals("总开关关不重答", 0, f.retries);
                assertTrue("复读率应明显下降：" + f.echoRate() + " → " + o.echoRate(), o.echoRate() <= f.echoRate() / 4);
                assertTrue("偏题率应明显下降：" + f.offTopicRate() + " → " + o.offTopicRate(), o.offTopicRate() <= f.offTopicRate() / 4);
                assertTrue("遗忘率应下降：" + f.forgetRate() + " → " + o.forgetRate(), o.forgetRate() < f.forgetRate());
                assertTrue("幻觉率不应上升：" + f.hallucRate() + " → " + o.hallucRate(), o.hallucRate() <= f.hallucRate());
                assertTrue("人设违规应基本消失：" + o.personaRate(), o.personaRate() < 1.0);
                assertTrue("技能准则遵守率应上升：" + f.skillRate() + " → " + o.skillRate(), o.skillRate() > f.skillRate());
                assertTrue("重活外溢应大降：" + f.heavyPerReq() + " → " + o.heavyPerReq(), o.heavyPerReq() * 5 <= f.heavyPerReq());
                // 接力：门槛压到 RELAY 轮，长场景会自动接力；接力后仍要记得早先的事、不跑偏
                List<LongCtxEval.Score> relayRows = LongCtxEval.run((LongCtxEval.Driver) nd.getConstructor(boolean.class, int.class).newInstance(true, RELAY), EVAL_BUDGET);
                out.append("### 改后 · 自动接力门槛压到 ").append(RELAY).append(" 轮\n").append(LongCtxEval.table(relayRows)).append('\n');
                LongCtxEval.Score rl = relayRows.get(relayRows.size() - 1);
                assertTrue("要真的接力过：" + rl.relays, rl.relays >= 3);
                assertTrue("接力后不遗忘：" + rl.forgetRate(), rl.forgetRate() <= o.forgetRate());
                assertTrue("接力后不跑偏：" + rl.offTopicRate(), rl.offTopicRate() <= o.offTopicRate() + 1.0);
                assertTrue("接力后技能照样在：" + rl.skillRate(), rl.skillRate() >= o.skillRate());
                // 加压：假模型 30% 无视末尾标记与守则 —— 看出口自检兜回多少（对照：同样加压、只关自检）
                Class<?> lc = Class.forName("com.onesay.assistant.v2.router.longctx.LongCtx");
                lc.getMethod("setSelfCheck", boolean.class).invoke(null, false);
                List<LongCtxEval.Score> noCheck = LongCtxEval.run((LongCtxEval.Driver) nd.getConstructor(boolean.class).newInstance(true), EVAL_BUDGET, STRESS);
                lc.getMethod("setSelfCheck", boolean.class).invoke(null, true);
                List<LongCtxEval.Score> stress = LongCtxEval.run((LongCtxEval.Driver) nd.getConstructor(boolean.class).newInstance(true), EVAL_BUDGET, STRESS);
                out.append("### 加压（假模型 ").append(STRESS).append("% 请求无视末尾标记与守则）· 只关出口自检\n").append(LongCtxEval.table(noCheck)).append('\n');
                out.append("### 加压 · 默认（含出口自检）\n").append(LongCtxEval.table(stress)).append('\n');
                LongCtxEval.Score n = noCheck.get(noCheck.size() - 1), st = stress.get(stress.size() - 1);
                assertTrue("加压下自检要真的重答过：" + st.retries, st.retries > 0);
                assertTrue("自检后复读率更低：" + n.echoRate() + " → " + st.echoRate(), st.echoRate() < n.echoRate());
                assertTrue("自检后偏题率不更高：" + n.offTopicRate() + " → " + st.offTopicRate(), st.offTopicRate() <= n.offTopicRate());
            } finally {
                System.out.println(out);
                Class.forName("com.onesay.assistant.v2.router.longctx.LongCtx").getMethod("setSelfCheck", boolean.class).invoke(null, true);
                Class.forName("com.onesay.assistant.v2.router.longctx.LongCtx").getMethod("setEnabled", boolean.class).invoke(null, true);   // 反射：本文件在 1ee84af 上也能编（复现改前数字）
            }
        }
        String path = System.getProperty("longctx.eval.out");
        if (nd == null) System.out.println(out);
        if (path != null && path.length() > 0) {
            Writer w = new OutputStreamWriter(new FileOutputStream(path), "UTF-8");
            try {
                w.write(out.toString());
            } finally {
                w.close();
            }
        }
    }
}
