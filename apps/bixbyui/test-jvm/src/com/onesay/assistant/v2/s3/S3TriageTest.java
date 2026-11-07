package com.onesay.assistant.v2.s3;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import com.onesay.assistant.v2.router.TriageDecider;

/**
 * [s3] 分诊回归：定时任务 / 待办与 S2「记一下」分开；不接管的句子保持原标签与原规则；开关关掉回到原判法；永不进 AgentLoop。
 * （单独计数，不改 TriageDeciderTest 的 241 条全集。）
 */
public class S3TriageTest {

    /** {句子, 期望标签, 期望规则前缀}；规则前缀为空 = 只看标签且不得是 6t/5t。 */
    static final String[][] CASES = {
            {"每天早上8点读一下QQ邮箱未读", "DO_ACT", "6t-s3-create"},
            // ★ int/1102 日程常识：「X点提醒我 + 事项」= 日程 + 准点提醒（日历里看得到、可撤销），由日程插件 6d 接（SchedEngine.planCreate remindAt）
            {"每周一9点提醒我交周报", "DO_ACT", "6d-sched"},
            {"明天下午3点提醒我取快递", "DO_ACT", "6d-sched"},
            {"工作日早上9点半提醒我打卡", "DO_ACT", "6t-s3-create"},
            {"每天晚上十点半提醒我睡觉", "DO_ACT", "6t-s3-create"},
            {"我有哪些定时任务", "DO_READ", "5t-s3-list"},
            {"我的提醒", "DO_READ", "5t-s3-list"},
            {"取消读邮件那个提醒", "DO_ACT", "6t-s3-cancel"},
            {"记个待办 买牛奶", "DO_ACT", "6t-todo-add"},
            {"待办：周五前交报告", "DO_ACT", "6t-todo-add"},
            {"把买牛奶加到待办", "DO_ACT", "6t-todo-add"},
            {"我的待办", "DO_READ", "5t-todo-list"},
            {"我还有哪些待办", "DO_READ", "5t-todo-list"},
            {"待办买牛奶做完了", "DO_ACT", "6t-todo-done"},
            // S2「记一下」不被 S3 吃掉
            {"记一下 车停在B2-17", "DO_ACT", "6m-memory-write"},
            {"记个事 护照在书房", "DO_ACT", "6m-memory-write"},
            {"我车停在哪", "ASK", "3m-memory-recall"},
            // 不接管：保持原来的判法（标签不变，规则不是 6t/5t）
            {"40分钟后提醒我喝水", "DO_ACT", ""},
            {"五分钟后提醒我关火", "DO_ACT", ""},
            {"提醒我明天交报告", "DO_ACT", ""},
            {"以后不要提醒我喝水", "DO_ACT", ""},
            {"如果明天下雨就提醒我带伞", "MIXED", ""},
            {"现在几点", "DO_READ", ""},
    };

    @Test public void s3TriageSet() {
        int pass = 0;
        StringBuilder fails = new StringBuilder();
        for (String[] c : CASES) {
            TriageDecider.Decision d = TriageDecider.decide(c[0], TriageDecider.Signals.offline());
            boolean ok = d.label.name().equals(c[1])
                    && (c[2].length() > 0 ? d.rule.startsWith(c[2]) : !(d.rule.startsWith("6t-") || d.rule.startsWith("5t-")));
            if (ok) pass++;
            else fails.append("  ").append(c[0]).append(" 期望=").append(c[1]).append("/").append(c[2]).append(" 实际=").append(d.label)
                    .append("/").append(d.rule).append('\n');
            if (c[2].startsWith("6t") || c[2].startsWith("5t")) assertFalse(c[0] + " 不进 AgentLoop", d.useAgentLoop);
        }
        System.out.println("[TRIAGE] S3 分诊 " + pass + "/" + CASES.length + (fails.length() > 0 ? "\n失败：\n" + fails : ""));
        assertEquals(fails.toString(), CASES.length, pass);
    }

    @Test public void switchOffRestoresOldRule() {
        TriageDecider.Decision on = TriageDecider.decide("记个待办 买牛奶", TriageDecider.Signals.offline(), true, true, true, true, true);
        TriageDecider.Decision off = TriageDecider.decide("记个待办 买牛奶", TriageDecider.Signals.offline(), true, true, true, true, false);
        assertEquals("6t-todo-add", on.rule);
        assertTrue("关掉 S3：回到 S2「记个」", off.rule.startsWith("6m-"));
    }
}
