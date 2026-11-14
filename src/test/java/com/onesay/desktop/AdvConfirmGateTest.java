package com.onesay.desktop;

import static org.junit.Assert.assertEquals;

import java.util.Collection;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

/**
 * [adv-1114] 确认框的时间 / 输入规则（ConfirmGate 类说明）：
 * 拒绝从哪来都收；「允许」只认本机鼠标 / 键盘，要确认框已显示、窗口有焦点、显示满 0.7 秒；60 秒到了一切回答都不算（GONE）。
 * 失焦再拿回焦点重新等 0.7 秒；拿过焦点后失焦 10 秒没人点 = 拒绝（blur）；界面 8 秒没动静 = 拒绝（ui_gone）；
 * 发到手机的卡在等时失焦 / 没动静不算，但 60 秒照样超时；关窗口 = closed。手机卡的回答：同一台手机、同一个 token、真人触摸、
 * 手机上显示满 0.7 秒、离发出满 0.7 秒。
 */
@RunWith(Parameterized.class)
public class AdvConfirmGateTest {

    static final long T0 = 0, TIMEOUT = LinkPolicy.CONFIRM_TIMEOUT_MS, ARM = ConfirmGate.ARM_MS;
    static final String TOKEN = "tok-0123456789abcdefXYZ";

    @Parameterized.Parameters(name = "{index}: {0}")
    public static Collection<Object[]> rows() {
        AdvSupport.Rows r = new AdvSupport.Rows("gate");
        // 1) 单次回答：显示状态 × 回答时刻 × 来源 × 允许 / 拒绝
        String[] seen = {"none", "focused", "unfocused"};
        long[] at = {0, 350, 699, 700, 1500, 59_999, 60_000, 75_000};
        ConfirmGate.Input[] ins = {ConfirmGate.Input.MOUSE, ConfirmGate.Input.KEYBOARD, ConfirmGate.Input.PHONE_TOUCH, ConfirmGate.Input.VOICE,
                ConfirmGate.Input.REMOTE, ConfirmGate.Input.UNKNOWN, null};
        for (String s : seen)
            for (long t : at)
                for (ConfirmGate.Input in : ins)
                    for (boolean allow : new boolean[]{true, false}) {
                        ConfirmGate.Answer want;
                        if (t >= TIMEOUT) want = ConfirmGate.Answer.GONE;
                        else if (!allow) want = ConfirmGate.Answer.ACCEPTED;
                        else if (in == null || !in.local()) want = ConfirmGate.Answer.NOT_LOCAL;
                        else if (s.equals("none")) want = ConfirmGate.Answer.NOT_SHOWN;
                        else if (s.equals("unfocused") || t < ARM) want = ConfirmGate.Answer.TOO_EARLY;
                        else want = ConfirmGate.Answer.ACCEPTED;
                        r.add("once seen=" + s + " t=" + t + " in=" + in + " allow=" + allow, "once", s, t, in, allow, want, 0L, 0L, null);
                    }
        // 2) 失焦再回来：重新等 0.7 秒（blur 时刻 b，回焦 f，回答 t）
        for (long b : new long[]{800, 3000, 9000})
            for (long gap : new long[]{1, 500, 9_999})
                for (long dt : new long[]{0, ARM - 1, ARM, ARM + 2_000}) {
                    long f = b + gap, t = f + dt;
                    ConfirmGate.Answer want = t >= TIMEOUT ? ConfirmGate.Answer.GONE : dt >= ARM ? ConfirmGate.Answer.ACCEPTED : ConfirmGate.Answer.TOO_EARLY;
                    r.add("refocus b=" + b + " f=" + f + " t=" + t, "refocus", "focused", t, ConfirmGate.Input.MOUSE, true, want, b, f, null);
                }
        // 失焦期间点允许：不算
        for (long t : new long[]{1_000, 2_000, 5_000, 9_999}) r.add("during blur t=" + t, "blurred", "focused", t, ConfirmGate.Input.KEYBOARD, true, ConfirmGate.Answer.TOO_EARLY, 900L, 0L, null);
        // 3) 自己结束：失焦 10 秒 / 界面 8 秒没动静 / 超时 / 关窗口；发了手机卡时前两条不算
        for (boolean phone : new boolean[]{false, true}) {
            String p = phone ? "phone" : "pc";
            Object[][] ex = {
                    {"blur", 1_000L, 10_999L, null}, {"blur", 1_000L, 11_000L, phone ? null : ConfirmGate.END_BLUR}, {"blur", 1_000L, 30_000L, phone ? null : ConfirmGate.END_BLUR},
                    {"ui", 0L, 7_999L, null}, {"ui", 0L, 8_000L, phone ? null : ConfirmGate.END_UI_GONE}, {"ui", 5_000L, 12_999L, null},
                    {"ui", 5_000L, 13_000L, phone ? null : ConfirmGate.END_UI_GONE}, {"timeout", 0L, 59_999L, null}, {"timeout", 0L, 60_000L, ConfirmGate.END_TIMEOUT},
                    {"closed", 0L, 1L, ConfirmGate.END_CLOSED}, {"closed", 0L, 70_000L, ConfirmGate.END_CLOSED}, {"never-seen", 0L, 30_000L, null},
                    {"blur-unseen", 900L, 59_000L, null}};
            for (Object[] e : ex)
                r.add("expire " + p + " " + e[0] + " at " + e[1] + " check " + e[2], "expire:" + e[0], p, (Long) e[2], null, false, e[3], (Long) e[1], 0L, null);
        }
        // 4) 手机确认卡：设备 / token / 输入 / 显示时长 / 离发出时长
        String[] devs = {"phone-a", "phone-b", "", null};
        String[] toks = {TOKEN, TOKEN.toUpperCase(), TOKEN.substring(0, 20), TOKEN + "x", "", null};
        for (String d : devs)
            for (String tk : toks)
                for (boolean allow : new boolean[]{true, false}) {
                    boolean same = "phone-a".equals(d) && TOKEN.equals(tk);
                    ConfirmGate.Answer want = !same ? ConfirmGate.Answer.NOT_LOCAL : ConfirmGate.Answer.ACCEPTED;
                    r.add("phone dev=" + d + " tok=" + tk + " allow=" + allow, "phone", d, 2_000L, ConfirmGate.Input.PHONE_TOUCH, allow, want, 900L, 0L, tk);
                }
        ConfirmGate.Input[] pin = {ConfirmGate.Input.PHONE_TOUCH, ConfirmGate.Input.MOUSE, ConfirmGate.Input.KEYBOARD, ConfirmGate.Input.VOICE, ConfirmGate.Input.REMOTE,
                ConfirmGate.Input.UNKNOWN};
        for (ConfirmGate.Input in : pin)
            for (long shown : new long[]{0, ARM - 1, ARM, 5_000})
                for (long since : new long[]{ARM - 1, ARM}) {
                    ConfirmGate.Answer want = in != ConfirmGate.Input.PHONE_TOUCH ? ConfirmGate.Answer.NOT_LOCAL
                            : shown < ARM || since < ARM ? ConfirmGate.Answer.TOO_EARLY : ConfirmGate.Answer.ACCEPTED;
                    r.add("phone-in " + in + " shown=" + shown + " since=" + since, "phonein", "phone-a", since, in, true, want, shown, 0L, null);
                }
        // 没发过手机卡时手机来的回答一律不算；token 太短（< 16）不算发过
        r.add("phone not offered", "phone-none", "phone-a", 2_000L, ConfirmGate.Input.PHONE_TOUCH, true, ConfirmGate.Answer.NOT_LOCAL, 0L, 0L, null);
        r.add("phone short token", "phone-short", "phone-a", 2_000L, ConfirmGate.Input.PHONE_TOUCH, true, ConfirmGate.Answer.NOT_LOCAL, 0L, 0L, null);
        r.add("phone after timeout", "phone-late", "phone-a", 60_000L, ConfirmGate.Input.PHONE_TOUCH, true, ConfirmGate.Answer.GONE, 0L, 0L, null);
        r.add("phone after close", "phone-closed", "phone-a", 2_000L, ConfirmGate.Input.PHONE_TOUCH, false, ConfirmGate.Answer.GONE, 0L, 0L, null);
        return r.get();
    }

    @Parameterized.Parameter(0) public String name;
    @Parameterized.Parameter(1) public String kind;
    @Parameterized.Parameter(2) public String state;
    @Parameterized.Parameter(3) public long t;
    @Parameterized.Parameter(4) public ConfirmGate.Input in;
    @Parameterized.Parameter(5) public boolean allow;
    @Parameterized.Parameter(6) public Object want;
    @Parameterized.Parameter(7) public long a;
    @Parameterized.Parameter(8) public long b;
    @Parameterized.Parameter(9) public String tok;

    @Test public void gate() {
        ConfirmGate g = new ConfirmGate(T0, TIMEOUT);
        if (kind.equals("once")) {
            if (!state.equals("none")) g.seen(T0, state.equals("focused"));
            assertEquals(name, want, g.check(t, allow, in));
        } else if (kind.equals("refocus")) {
            g.seen(T0, true);
            g.window(a, false);
            g.window(b, true);
            assertEquals(name, want, g.check(t, allow, in));
        } else if (kind.equals("blurred")) {
            g.seen(T0, true);
            g.window(a, false);
            assertEquals(name, want, g.check(t, allow, in));
        } else if (kind.startsWith("expire:")) {
            String what = kind.substring(7);
            if (state.equals("phone")) g.phoneOffered(T0, "phone-a", TOKEN);
            switch (what) {
                case "blur": g.seen(T0, true); g.window(a, false); for (long x = a; x <= t; x += 2_500) g.uiAlive(x); break;
                case "ui": g.seen(T0, true); if (a > 0) g.uiAlive(a); break;
                case "timeout": g.seen(T0, true); for (long x = 0; x <= t; x += 2_500) g.uiAlive(x); break;
                case "closed": g.seen(T0, true); g.close(); break;
                case "never-seen": break;
                case "blur-unseen": g.window(a, false); break; // 还没显示就失焦：不算「拿过焦点又失焦」
                default: throw new AssertionError(what);
            }
            assertEquals(name, want, g.expired(t));
        } else if (kind.equals("phone")) {
            g.phoneOffered(T0, "phone-a", TOKEN);
            assertEquals(name, want, g.checkPhone(t, allow, state, tok, in, a));
        } else if (kind.equals("phonein")) {
            g.phoneOffered(T0, "phone-a", TOKEN);
            assertEquals(name, want, g.checkPhone(t, allow, state, TOKEN, in, a));
        } else if (kind.equals("phone-none")) {
            assertEquals(name, want, g.checkPhone(t, allow, state, TOKEN, in, 5_000));
        } else if (kind.equals("phone-short")) {
            g.phoneOffered(T0, "phone-a", "short");
            assertEquals(name, false, g.phoneOffered());
            assertEquals(name, want, g.checkPhone(t, allow, state, "short", in, 5_000));
        } else if (kind.equals("phone-late")) {
            g.phoneOffered(T0, "phone-a", TOKEN);
            assertEquals(name, want, g.checkPhone(t, allow, state, TOKEN, in, 5_000));
        } else if (kind.equals("phone-closed")) {
            g.phoneOffered(T0, "phone-a", TOKEN);
            g.close();
            assertEquals(name, want, g.checkPhone(t, allow, state, TOKEN, in, 5_000));
        } else throw new AssertionError(kind);
    }


}
