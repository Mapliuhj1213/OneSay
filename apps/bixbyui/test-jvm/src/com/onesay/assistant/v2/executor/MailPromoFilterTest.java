package com.onesay.assistant.v2.executor;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/** 邮件推荐过滤营销 / 推广信（电商大促、上新、福利、(AD) 标记、EDM 群发域），正常工作 / 私人邮件不误伤。 */
public class MailPromoFilterTest {

    static MailDigest.Item m(String from, String subj) { return new MailDigest.Item(from, subj, 1000L, false, false); }

    @Test public void marketingFiltered() {
        String[][] promo = {
                {"京东 <jd@mail.jd.com>", "双11狂欢开启，爆款直降"},
                {"某品牌 <hello@brand.cn>", "(AD) 秋季新品上新"},
                {"店铺 <shop@x.com>", "【AD】满减券已到账"},
                {"x <offers@airline.com>", "Your exclusive fare"},
                {"y <news@m.mailchimp.com>", "Weekly picks"},
                {"z <a@b.com>", "会员专享福利，免费领"},
        };
        for (String[] p : promo) assertTrue(p[1], MailDigest.isPromo(m(p[0], p[1])));
    }

    @Test public void normalMailKept() {
        String[][] ok = {
                {"张经理 <zhang@corp.com>", "周五项目评审材料"},
                {"Mom <mom@qq.com>", "周末回家吃饭吗"},
                {"HR <hr@corp.com>", "Admin 权限申请已通过"},
                {"李四 <li@x.com>", "广州出差安排"},
        };
        for (String[] p : ok) assertFalse(p[1], MailDigest.isPromo(m(p[0], p[1])));
        List<MailDigest.Item> all = new ArrayList<MailDigest.Item>();
        all.add(m("京东 <jd@mail.jd.com>", "双11狂欢开启"));
        all.add(m("张经理 <zhang@corp.com>", "周五项目评审材料"));
        List<MailDigest.Item> picked = MailDigest.pick(all, 4);
        assertEquals(1, picked.size());
        assertEquals("周五项目评审材料", picked.get(0).subject);
    }
}
