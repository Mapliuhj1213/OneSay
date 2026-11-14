package com.onesay.clock.core;

import java.util.*;

/**
 * [已知缺陷 P2] 对外闹钟接口的标签清洗（AlarmApi.cleanLabel，「截断 30 字」）按 UTF-16 单元计数、逐个 char 追加：
 * 第 30 个单元正好是代理对的前一半时，只留下孤立的高位代理（emoji / 生僻字被切开），写进闹钟表和结果 JSON 后显示成乱码。
 * 期望：按码点截断，结果里没有孤立代理。默认门禁不跑：bash tools/adv-tests/run.sh bugs clock。
 */
public class AdvBugClockTest {
    static int pass, fail;

    static void ok(String name, boolean c) {
        if (c) pass++; else { fail++; System.out.println("  FAIL " + name); }
    }

    static boolean wellFormed(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c)) { if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) return false; i++; }
            else if (Character.isLowSurrogate(c)) return false;
        }
        return true;
    }

    static String rep(String s, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < n; i++) b.append(s);
        return b.toString();
    }

    public static void main(String[] args) {
        String[] labels = {rep("a", 29) + "\uD83D\uDE00", rep("起", 29) + "\uD83D\uDE00\uD83D\uDE00", rep("b", 29) + "\uD840\uDC00"};
        for (String l : labels) {
            String got = AlarmApi.cleanLabel(l);
            ok("cleanLabel 不切开代理对 [" + l.length() + " 单元]", wellFormed(got));
            AlarmApi.Result r = AlarmApi.apply(new ArrayList<Alarm>(), AlarmApi.A_SET, 0, 7, 0, 0, true, l);
            ok("ALARM_SET 存进去的标签合法 [" + l.length() + "]", r.alarms.size() == 1 && wellFormed(r.alarms.get(0).label));
        }
        System.out.println("AdvBugClockTest " + pass + "/" + (pass + fail) + (fail == 0 ? " ALL PASS" : " FAILED"));
        System.exit(fail == 0 ? 0 : 1);
    }
}
