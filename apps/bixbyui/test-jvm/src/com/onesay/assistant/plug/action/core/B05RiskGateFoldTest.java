package com.onesay.assistant.plug.action.core;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

/** ★ rel-1118 B05：风险门认出带空格 / 零宽 / 全角 / 繁体的按钮，以及转账、充值、发红包、购买、分享等（t20k action.riskgate 43 条）。 */
public class B05RiskGateFoldTest {

    private static RiskGate.Irrev d(String name, String rid) {
        return RiskGate.detect("tap", name, rid, false);
    }

    private static void consent(String name, RiskGate.Irrev... allowed) {
        for (String rid : new String[]{"", "com.x:id/btn_ok"}) {
            RiskGate.Irrev hit = d(name, rid);
            assertNotNull(name + " #" + rid, hit);
            assertTrue(name + " → " + hit, Arrays.asList(allowed).contains(hit));
            assertTrue(name, RiskGate.assess("tap", hit, Arrays.asList("合计 ¥99.00")).needsConsent());
        }
    }

    @Test public void payAndOrder() {
        for (String s : new String[]{"支 付", "付\u200b款", "ＰＡＹ", "Pay now", "转账", "确认转账", "立即转账", "充值", "发红包", "塞钱进红包", "一键支付", "Checkout", "確認轉帳"}) {
            consent(s, RiskGate.Irrev.PAY, RiskGate.Irrev.ORDER);
        }
        for (String s : new String[]{"购买", "立即抢购", "Buy now", "去结算", "購買"}) consent(s, RiskGate.Irrev.ORDER, RiskGate.Irrev.PAY);
    }

    @Test public void deleteAndSend() {
        for (String s : new String[]{"删 除", "刪除", "解绑", "退出群聊", "注销账号", "REMOVE"}) consent(s, RiskGate.Irrev.DELETE);
        for (String s : new String[]{"分享", "上传", "发朋友圈", "拨打", "呼叫", "發送", "发", "转发给朋友"}) consent(s, RiskGate.Irrev.SEND);
    }

    @Test public void ordinaryButtonsStayQuiet() {
        for (String s : new String[]{"确定", "返回", "确认", "确认收款", "授权", "同意并继续", "抢红包", "下一步", "设置", "callback"}) {
            assertNull(s, d(s, ""));
        }
    }
}
