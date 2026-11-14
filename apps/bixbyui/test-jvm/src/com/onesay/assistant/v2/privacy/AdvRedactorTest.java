package com.onesay.assistant.v2.privacy;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · 发给云端模型前的本地脱敏（Redactor）：
 * <ul>
 *   <li>pos：注释写明的写法都要换成占位符（手机号可带 +86 / 空格 / 短横；身份证 18 位校验码对上；银行卡 16–19 位 Luhn 对上、可 4 位一组；邮箱；
 *       验证码 / 校验码 / 动态码 / 确认码 / code 附近的 4–8 位），原文里不再出现原值，restore 后逐字还原；</li>
 *   <li>neg：不满足规则的（12 开头、位数不对、校验不对、没有关键字的数字、没有顶级域的邮箱）原样不动；</li>
 *   <li>stable：同一值（不同写法）同一占位符、不同值编号递增、已有占位符不被二次替换、toString 不含值。</li>
 * </ul>
 * 身份证 / 银行卡号由固定算法生成（不是真实号码）。全角数字、零宽字符、不换行空格分隔这几种写法在 adv-bugs（AdvBugRedactorVariantTest）。
 */
@RunWith(Parameterized.class)
public class AdvRedactorTest {

    /** 前 17 位 + 按 GB 11643 算出的校验码。 */
    static String id18(String first17) {
        int[] w = {7, 9, 10, 5, 8, 4, 2, 1, 6, 3, 7, 9, 10, 5, 8, 4, 2};
        char[] c = {'1', '0', 'X', '9', '8', '7', '6', '5', '4', '3', '2'};
        int s = 0;
        for (int i = 0; i < 17; i++) s += (first17.charAt(i) - '0') * w[i];
        return first17 + c[s % 11];
    }

    static char wrongCheck(String id) {
        char last = id.charAt(17);
        return last == '1' ? '2' : '1';
    }

    /** 前 n-1 位 + Luhn 校验位。 */
    static String luhn(String body) {
        for (int d = 0; d <= 9; d++) {
            String s = body + d;
            if (Redactor.luhnOk(s)) return s;
        }
        throw new IllegalStateException(body);
    }

    @Parameterized.Parameters(name = "{index}: {0} {1}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        // ---- 手机号
        String[] phones = {"13800138000", "138 0013 8000", "138-0013-8000", "+86 13800138000", "+86-138-0013-8000", "8613800138000", "+8613800138000",
                "15912345678", "17700001111", "19988887777", "16612340000", "18611112222"};
        for (String p : phones) {
            add(out, seen, "pos", "我的电话是" + p + "，请回电", "手机号:" + p);
            add(out, seen, "pos", p, "手机号:" + p);
        }
        // ---- 身份证（不同地区码 / 年份 / 月日；含 X 结尾的）
        String[] firsts = {"11010519491231002", "44030419900101123", "31010120001231456", "51010719850615789", "11010119800101001", "32050119991231000",
                "61010420200229111", "11010118000101000"};
        for (String f : firsts) {
            String id = id18(f);
            add(out, seen, "pos", "身份证号 " + id + " 已登记", "身份证:" + id);
            add(out, seen, "neg", "身份证号 " + id.substring(0, 17) + wrongCheck(id), "");
            if (id.endsWith("X")) add(out, seen, "pos", "身份证 " + id.substring(0, 17) + "x", "身份证:" + id.substring(0, 17) + "x");
        }
        // ---- 银行卡
        String[] bodies = {"622202123456789", "411111111111111", "622848001234567890", "621700000000000000", "5555555555554444".substring(0, 15)};
        for (String b : bodies) {
            String card = luhn(b);
            if (card.length() < 16 || card.length() > 19) continue;
            add(out, seen, "pos", "卡号" + card + "尾号", "银行卡:" + card);
            if (card.length() == 16) {
                String grouped = card.substring(0, 4) + " " + card.substring(4, 8) + " " + card.substring(8, 12) + " " + card.substring(12);
                add(out, seen, "pos", "卡 " + grouped + " 到期", "银行卡:" + grouped);
                String dashed = grouped.replace(' ', '-');
                add(out, seen, "pos", "卡 " + dashed, "银行卡:" + dashed);
            }
            char last = card.charAt(card.length() - 1);
            add(out, seen, "neg", "订单号 " + card.substring(0, card.length() - 1) + (last == '9' ? '0' : (char) (last + 1)), "");
        }
        // ---- 邮箱
        String[] emails = {"alice@example.com", "A.B+tag@Example.COM", "zhang_san-1@mail.qq.com", "x@sub.domain.co.uk", "user.name@163.com", "a1@b2.io"};
        for (String e : emails) add(out, seen, "pos", "发到 " + e + " 就行", "邮箱:" + e);
        // ---- 验证码
        String[][] codes = {{"验证码 123456，5 分钟内有效", "123456"}, {"您的验证码：8421", "8421"}, {"校验码为 99887766", "99887766"}, {"动态码 4455", "4455"},
                {"确认码是 778899", "778899"}, {"动态密码 135790", "135790"}, {"短信码 2468", "2468"}, {"Your verification code is 5566", "5566"},
                {"code: 31415", "31415"}, {"CODE 271828", "271828"}, {"【某银行】验证码 654321，切勿告知他人", "654321"}};
        for (String[] c : codes) add(out, seen, "pos", c[0], "验证码:" + c[1]);
        // ---- 不该动的
        String[] negs = {"12800138000", "1380013800", "138001380001", "订单 123456 已发货", "今天 2026 年 11 月 14 日", "a@b", "user@localhost",
                "价格 4111111111111112 元", "版本 1.2.3.4", "房间号 8000", "工号 20261114", "验证码已发送，请查收", "code review 今天", "@everyone 开会",
                "电话 010-65529988", "QQ 12345678", "身份证 110105194912310021X", "11010519491231002", "+1 415 555 0100", "IP 192.168.1.1"};
        for (String n : negs) add(out, seen, "neg", n, "");
        // ---- 稳定性 / 编号
        String[] stable = {"samePhoneTwice", "sameDifferentForms", "twoPhones", "mixedKinds", "placeholderInInput", "fakePlaceholderRestore", "emailCase",
                "toStringNoLeak", "clearResets", "nullEmpty", "restoreBareTag", "manyPhones"};
        for (String s : stable) add(out, seen, "stable", s, "");
        return out;
    }

    static void add(List<Object[]> out, Set<String> seen, String kind, String in, String want) {
        if (!seen.add(kind + "\u0001" + in)) throw new IllegalStateException("dup " + in);
        out.add(new Object[]{kind, in, want});
    }

    private final String kind, in, want;

    public AdvRedactorTest(String kind, String in, String want) {
        this.kind = kind;
        this.in = in;
        this.want = want;
    }

    @Test public void redact() {
        Redactor r = new Redactor();
        if (kind.equals("pos")) {
            String[] w = want.split(":", 2);
            String out = r.redact(in);
            assertFalse(in + " → " + out + " 原值还在", out.contains(w[1]));
            assertTrue(in + " → " + out, out.contains("[" + w[0] + "#1]"));
            assertTrue(Redactor.hasPlaceholder(out));
            assertEquals("restore 逐字还原", in, r.restore(out));
            assertEquals("同一 Redactor 再脱一遍结果不变", out, r.redact(in));
            assertFalse(r.toString().contains(w[1]));
        } else if (kind.equals("neg")) {
            assertEquals(in, r.redact(in));
            assertEquals(0, r.size());
        } else stable(r);
    }

    void stable(Redactor r) {
        if (in.equals("samePhoneTwice")) {
            String o = r.redact("先打 13800138000，不通再打 13800138000");
            assertEquals("先打 [手机号#1]，不通再打 [手机号#1]", o);
            assertEquals(1, r.size());
        } else if (in.equals("sameDifferentForms")) {
            String o = r.redact("13800138000 / 138 0013 8000 / +86-138-0013-8000");
            assertEquals("[手机号#1] / [手机号#1] / [手机号#1]", o);
        } else if (in.equals("twoPhones")) {
            assertEquals("[手机号#1] 和 [手机号#2]", r.redact("13800138000 和 13900139000"));
            assertEquals("第二次任务内仍是同一编号", "[手机号#2]", r.redact("13900139000"));
        } else if (in.equals("mixedKinds")) {
            String o = r.redact("电话 13800138000 邮箱 a@b.com 验证码 1234");
            assertEquals("电话 [手机号#1] 邮箱 [邮箱#1] 验证码 [验证码#1]", o);
            assertEquals("[手机号×1, 邮箱×1, 验证码×1]", r.kindsSummary().toString());
        } else if (in.equals("placeholderInInput")) {
            assertEquals("已经是占位符的不再动", "[手机号#7] 是占位符", r.redact("[手机号#7] 是占位符"));
            assertEquals(0, r.size());
        } else if (in.equals("fakePlaceholderRestore")) {
            r.redact("13800138000");
            assertEquals("认不出的占位符原样留着", "[手机号#9] [身份证#1]", r.restore("[手机号#9] [身份证#1]"));
        } else if (in.equals("emailCase")) {
            assertEquals("[邮箱#1] [邮箱#1]", r.redact("Alice@Example.com alice@example.com"));
            assertEquals("还原用第一次见到的写法", "Alice@Example.com Alice@Example.com", r.restore("[邮箱#1] [邮箱#1]"));
        } else if (in.equals("toStringNoLeak")) {
            r.redact("13800138000 a@b.com");
            assertEquals("Redactor(2 项)", r.toString());
        } else if (in.equals("clearResets")) {
            r.redact("13800138000");
            r.clear();
            assertEquals(0, r.size());
            assertEquals("[手机号#1]", r.redact("13900139000"));
            assertEquals("clear 之后旧占位符还原不出旧值", "[手机号#1]", r.restore("[手机号#1]").replace("13900139000", "[手机号#1]"));
        } else if (in.equals("nullEmpty")) {
            assertNull(r.redact(null));
            assertEquals("", r.redact(""));
            assertNull(r.restore(null));
        } else if (in.equals("restoreBareTag")) {
            r.redact("13800138000");
            assertEquals("不带方括号的占位符也认", "打 13800138000", r.restore("打 手机号#1"));
        } else if (in.equals("manyPhones")) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < 30; i++) b.append("1380013").append(String.format("%04d", i)).append(' ');
            String o = r.redact(b.toString());
            assertTrue(o, o.contains("[手机号#30]"));
            assertFalse(o.matches(".*\\d{11}.*"));
            assertEquals(b.toString(), r.restore(o));
        } else fail(in);
    }
}
