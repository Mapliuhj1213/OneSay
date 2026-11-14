package com.onesay.assistant.v2.pclink;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * 找茬 · 手机屏幕上的确认卡（PcConfirm）：「允许」只收真人触摸（TOUCH、不在机器人刚注入手势的 1.5 秒里）、卡已显示、
 * 显示后满 0.7 秒且有焦点（失焦再回来重新等）、没过期；「拒绝」从哪来都收；没收的回答不改变卡。
 * 输入来源 7 种 × 允许 / 拒绝 × 卡的阶段 7 种 × 机器人刚动过与否；另测「始终 / 以后这类不用问」只在卡上给了且真人允许时算数、
 * 重复发来的同一张电脑卡不重复开、口头批准识别（spokenApproval）。
 */
@RunWith(Parameterized.class)
public class AdvPcConfirmTest {

    static final String[] PHASES = {"notShown", "shownEarly", "armed", "armedUnfocused", "refocusEarly", "refocusArmed", "expired"};

    @Parameterized.Parameters(name = "{index}: {0} {1}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        for (PcConfirm.Input in : PcConfirm.Input.values())
            for (int allow = 0; allow < 2; allow++)
                for (String ph : PHASES)
                    for (int robot = 0; robot < 2; robot++)
                        out.add(new Object[]{"answer", in + "/" + (allow == 1 ? "allow" : "deny") + "/" + ph + "/" + (robot == 1 ? "robot" : "-")});
        // 标志：{卡上给不给 always, 卡的记住类型, 点的是 always / remember}
        String[] flags = {"T|pc.shell_exec|always", "T|pc.shell_exec|remember", "T|pc.shell_exec|both", "T|pc.shell_exec|plain", "F|pc.shell_exec|always",
                "F|pc.shell_exec|remember", "F||remember", "F|made.up.kind|remember", "T||always", "F|pc.url|both", "F|PC.SHELL_EXEC|remember", "F| pc.url|remember"};
        for (String f : flags) out.add(new Object[]{"flags", f});
        String[] misc = {"dupOffer", "shortToken", "nullReply", "noUi", "closePc", "closeOtherFrom", "dismissed", "answerTwice", "expireSweep", "timeoutClamp"};
        for (String m : misc) out.add(new Object[]{"misc", m});
        String[] spokenYes = {"允许", "允许。", "好的！", "可以", "确认执行", "嗯允许", "那就允许吧", "OK", "ok", "Okay.", "yes", "YES!", "go ahead", "do it",
                "帮我点允许", "以后这类不用问", "请批准", "批准吧", "好", "是的", "行吧", "approve", "  确定  ", "通过了"};
        String[] spokenNo = {"", "不要", "拒绝", "取消", "等一下", "允许他访问我的相册吗", "这是什么", "打开微信", "好的我知道了但是先别", "noooo", "maybe",
                "确认一下是什么", "执行什么", "我不同意"};
        for (String s : spokenYes) out.add(new Object[]{"spokenYes", s});
        for (String s : spokenNo) out.add(new Object[]{"spokenNo", s});
        return out;
    }

    private final String kind, spec;
    private long now = 1_790_000_000_000L;
    private final AtomicInteger opened = new AtomicInteger();
    private final List<String> replies = new ArrayList<String>();

    public AdvPcConfirmTest(String kind, String spec) {
        this.kind = kind;
        this.spec = spec;
    }

    @Before public void setUp() {
        PcConfirm.reset();
        PcConfirm.clock = new PcConfirm.Clock() {
            @Override public long now() {
                return now;
            }
        };
        PcConfirm.ui = new PcConfirm.Ui() {
            @Override public void open(PcConfirm.Card c) { opened.incrementAndGet(); }

            @Override public void close(PcConfirm.Card c) { }
        };
    }

    @After public void tearDown() {
        PcConfirm.reset();
        PcConfirm.ui = null;
        PcConfirm.clock = new PcConfirm.Clock() {
            @Override public long now() {
                return System.currentTimeMillis();
            }
        };
    }

    PcConfirm.Reply reply() {
        return new PcConfirm.Reply() {
            @Override public void send(PcConfirm.Card c, boolean allow, boolean always, boolean remember, long shownMs) {
                replies.add(allow + "/" + always + "/" + remember);
            }
        };
    }

    PcConfirm.Card offer(boolean always, String kind) {
        return PcConfirm.offer("pc1", 7, "tok-0123456789abcdef", "电脑想执行命令", kind, always, LinkPolicy.CONFIRM_TIMEOUT_MS, reply());
    }

    @Test public void card() {
        if (kind.equals("answer")) answerCase();
        else if (kind.equals("flags")) flagsCase();
        else if (kind.equals("misc")) miscCase();
        else assertEquals(spec, kind.equals("spokenYes"), PcConfirm.spokenApproval(spec));
    }

    void answerCase() {
        String[] p = spec.split("/");
        PcConfirm.Input in = PcConfirm.Input.valueOf(p[0]);
        boolean allow = p[1].equals("allow");
        String phase = p[2];
        boolean robot = p[3].equals("robot");
        PcConfirm.Card c = offer(true, "pc.shell_exec");
        assertNotNull(c);
        boolean shown = !phase.equals("notShown"), armed = false;
        if (shown) {
            PcConfirm.shown(c.id, !phase.equals("armedUnfocused"));
            if (phase.equals("shownEarly")) now += LinkPolicy.CONFIRM_ARM_MS - 1;
            else if (phase.equals("armed")) { now += LinkPolicy.CONFIRM_ARM_MS; armed = true; }
            else if (phase.equals("armedUnfocused")) now += LinkPolicy.CONFIRM_ARM_MS + 100;
            else if (phase.startsWith("refocus")) {
                now += LinkPolicy.CONFIRM_ARM_MS + 100;
                PcConfirm.focus(c.id, false);
                now += 50;
                PcConfirm.focus(c.id, true);
                if (phase.equals("refocusEarly")) now += LinkPolicy.CONFIRM_ARM_MS - 1;
                else { now += LinkPolicy.CONFIRM_ARM_MS; armed = true; }
            } else if (phase.equals("expired")) now += LinkPolicy.CONFIRM_TIMEOUT_MS;
        }
        if (robot) PcConfirm.noteRobotInput();
        PcConfirm.Answer want;
        if (phase.equals("expired")) want = PcConfirm.Answer.GONE;
        else if (!allow) want = PcConfirm.Answer.ACCEPTED;
        else if (in != PcConfirm.Input.TOUCH || robot) want = PcConfirm.Answer.NOT_HUMAN;
        else if (!shown) want = PcConfirm.Answer.NOT_SHOWN;
        else if (!armed) want = PcConfirm.Answer.TOO_EARLY;
        else want = PcConfirm.Answer.ACCEPTED;
        PcConfirm.Answer got = PcConfirm.answer(c.id, allow, true, true, in);
        assertEquals(spec, want, got);
        if (want == PcConfirm.Answer.ACCEPTED) {
            assertEquals(1, replies.size());
            assertEquals(spec, allow ? "true/true/false" : "false/false/false", replies.get(0));
            assertTrue(c.ended());
            assertNull(PcConfirm.card(c.id));
        } else if (want != PcConfirm.Answer.GONE) {
            assertTrue(spec + " 没收的回答不改变卡", replies.isEmpty());
            assertFalse(c.ended());
            assertNotNull(PcConfirm.card(c.id));
            // 之后真人照规矩点，仍然能允许
            if (!shown) PcConfirm.shown(c.id, true);
            PcConfirm.focus(c.id, true);
            now += PcConfirm.ROBOT_WINDOW_MS + LinkPolicy.CONFIRM_ARM_MS + 1;
            PcConfirm.shown(c.id, true);
            if (!c.armed(now)) { PcConfirm.focus(c.id, false); PcConfirm.focus(c.id, true); now += LinkPolicy.CONFIRM_ARM_MS; }
            assertEquals(spec, PcConfirm.Answer.ACCEPTED, PcConfirm.answer(c.id, true, false, false, PcConfirm.Input.TOUCH));
        }
    }

    void flagsCase() {
        String[] p = spec.split("\\|", -1);
        boolean cardAlways = p[0].equals("T");
        String rk = p[1];
        String press = p[2];
        PcConfirm.Card c = offer(cardAlways, rk);
        boolean known = LinkPolicy.knownKind(rk);
        assertEquals(spec, known ? rk : "", c.rememberKind);
        PcConfirm.shown(c.id, true);
        now += LinkPolicy.CONFIRM_ARM_MS;
        boolean always = press.equals("always") || press.equals("both"), remember = press.equals("remember") || press.equals("both");
        assertEquals(PcConfirm.Answer.ACCEPTED, PcConfirm.answer(c.id, true, always, remember, PcConfirm.Input.TOUCH));
        boolean gotAlways = always && cardAlways, gotRemember = !gotAlways && remember && known;
        assertEquals(spec, "true/" + gotAlways + "/" + gotRemember, replies.get(0));
    }

    void miscCase() {
        if (spec.equals("dupOffer")) {
            PcConfirm.Card a = offer(false, "pc.url"), b = offer(false, "pc.url");
            assertSame("同一张电脑卡重复发来不重复开", a, b);
            assertEquals(1, opened.get());
        } else if (spec.equals("shortToken")) {
            assertNull(PcConfirm.offer("pc1", 1, "short", "d", "", false, 1000, reply()));
        } else if (spec.equals("nullReply")) {
            assertNull(PcConfirm.offer("pc1", 1, "tok-0123456789abcdef", "d", "", false, 1000, null));
        } else if (spec.equals("noUi")) {
            PcConfirm.ui = null;
            assertNull(offer(false, ""));
            assertEquals("没有界面 = 没人确认", PcConfirm.TIMEOUT, PcConfirm.ask("t", "d", "", 1000));
        } else if (spec.equals("closePc")) {
            PcConfirm.Card c = offer(false, "");
            PcConfirm.closePc("pc1", 7);
            assertTrue(c.ended());
            assertEquals(PcConfirm.Answer.GONE, PcConfirm.answer(c.id, true, false, false, PcConfirm.Input.TOUCH));
        } else if (spec.equals("closeOtherFrom")) {
            PcConfirm.Card c = offer(false, "");
            PcConfirm.closePc("pc2", 7);
            PcConfirm.closePc("pc1", 8);
            assertFalse("别的电脑 / 别的编号关不掉这张卡", c.ended());
        } else if (spec.equals("dismissed")) {
            PcConfirm.Card c = offer(false, "");
            PcConfirm.dismissed(c.id);
            assertEquals("返回键 = 拒绝", "false/false/false", replies.get(0));
            assertTrue(c.ended());
        } else if (spec.equals("answerTwice")) {
            PcConfirm.Card c = offer(false, "");
            assertEquals(PcConfirm.Answer.ACCEPTED, PcConfirm.answer(c.id, false, false, false, PcConfirm.Input.VOICE));
            PcConfirm.shown(c.id, true);
            now += 1000;
            assertEquals(PcConfirm.Answer.GONE, PcConfirm.answer(c.id, true, false, false, PcConfirm.Input.TOUCH));
            assertEquals(1, replies.size());
        } else if (spec.equals("expireSweep")) {
            PcConfirm.Card c = offer(false, "");
            now += LinkPolicy.CONFIRM_TIMEOUT_MS - 1;
            assertEquals(0, PcConfirm.expire());
            now += 1;
            assertEquals(1, PcConfirm.expire());
            assertTrue(c.ended());
            assertFalse(PcConfirm.anyPending());
        } else if (spec.equals("timeoutClamp")) {
            PcConfirm.Card c = PcConfirm.offer("pc1", 9, "tok-0123456789abcdef", "d", "", false, 10L * LinkPolicy.CONFIRM_TIMEOUT_MS, reply());
            assertEquals("电脑给的超时最多 60 秒", now + LinkPolicy.CONFIRM_TIMEOUT_MS, c.deadline);
            PcConfirm.Card n = PcConfirm.offer("pc1", 10, "tok-0123456789abcdef", "d", "", false, -5, reply());
            assertEquals(now, n.deadline);
        } else fail(spec);
    }
}
