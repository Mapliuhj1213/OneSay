package com.onesay.assistant.v2.summary;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** [summary] 隐私闸门：密码框 / 验证码页跳过；讲密码的文章、打过码的卡号照常；模型输出擦除。 */
public class SummaryGuardTest {

    static final List<String> ARTICLE = Arrays.asList("如何设置一个强密码", "很多人的密码太简单，建议使用 12 位以上并混合大小写。", "文章来源：某科技媒体");

    @Test public void passwordNodeDropped() {
        // UiDumpParser 丢了 1 个 password 节点（redacted=1），屏幕上没有打码行 → 有密码框
        assertEquals("密码输入框", SummaryGuard.screenReason(Arrays.asList("登录", "手机号"), 1));
        // redacted 只来自打码的卡号 → 不算密码框
        assertNull(SummaryGuard.screenReason(Arrays.asList("尾号 ****1234 的储蓄卡", "本月账单共 12 笔，合计消费 3200 元，详见下方明细。"), 1));
    }

    @Test public void otpAndPromptScreens() {
        assertEquals("验证码", SummaryGuard.screenReason(Arrays.asList("【某银行】您的验证码是 482913，5 分钟内有效"), 0));
        assertNotNull(SummaryGuard.screenReason(Arrays.asList("请输入验证码", "重新发送"), 0));
        assertNotNull(SummaryGuard.screenReason(Arrays.asList("请输入支付密码"), 0));
        assertNotNull(SummaryGuard.screenReason(Arrays.asList("忘记密码", "登录"), 0));
        assertNotNull(SummaryGuard.screenReason(Arrays.asList("登录密码"), 0));
    }

    @Test public void normalPagesPass() {
        assertNull(SummaryGuard.screenReason(ARTICLE, 0));
        assertNull(SummaryGuard.screenReason(Arrays.asList("张三：明天下午三点开会", "李四：好的，我带投影仪"), 0));
        assertNull(SummaryGuard.screenReason(Collections.<String>emptyList(), 0));
        assertNull(SummaryGuard.screenReason(null, 0));
        // 长句里顺带提到「输入密码」不算界面提示（只看短行）
        assertNull(SummaryGuard.screenReason(Arrays.asList("文章指出，很多老人在输入密码时会把密码写在纸上贴在手机背后，这很危险。"), 0));
    }

    @Test public void outputScrub() {
        assertTrue(SummaryGuard.outputLineSensitive("验证码是 482913"));
        assertTrue(SummaryGuard.outputLineSensitive("银行卡号 6222 0212 3456 7890 128"));
        assertFalse(SummaryGuard.outputLineSensitive("作者建议使用强密码"));
        assertFalse(SummaryGuard.outputLineSensitive("会议定在 2026 年 10 月 10 日下午三点"));
        int[] removed = new int[1];
        List<String> kept = SummaryGuard.scrub(Arrays.asList("讨论了骑行", "验证码 123456 已发送"), removed);
        assertEquals(1, removed[0]);
        assertEquals(Arrays.asList("讨论了骑行"), kept);
    }
}
