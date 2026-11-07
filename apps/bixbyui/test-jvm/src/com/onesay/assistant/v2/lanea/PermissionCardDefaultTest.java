package com.onesay.assistant.v2.lanea;

import com.onesay.assistant.v2.jobs.JobStore;
import com.onesay.assistant.v2.jobs.JobsEngine;
import com.onesay.assistant.v2.jobs.Perm;
import com.onesay.assistant.v2.jobs.PermissionCardPolicy;
import com.onesay.assistant.v2.jobs.PermissionGuard;
import com.onesay.assistant.v2.jobs.TaskGrant;

import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.EnumSet;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-23 权限卡默认值（用户原始需求「逐项勾选才入库」）：可勾的项默认不勾；至少勾一项「确认授权」才可用（状态看得出来）；
 * 只勾一部分照现有逻辑——没授权的步骤到点进「等你确认」。
 */
public class PermissionCardDefaultTest {

    static final Set<Perm> CARD = EnumSet.of(Perm.READ_TODO, Perm.READ_MAIL, Perm.WEB_SEARCH);

    @Test public void everyPermDefaultsUnchecked() {
        for (Perm p : Perm.values()) assertFalse(p + " 默认不勾", PermissionCardPolicy.defaultChecked(p));
    }

    @Test public void confirmNeedsAtLeastOneChecked() {
        Set<Perm> none = EnumSet.noneOf(Perm.class);
        assertFalse("刚打开（全不勾）→ 确认不可用", PermissionCardPolicy.confirmEnabled(CARD, none));
        assertEquals("先勾选要授权的工具", PermissionCardPolicy.confirmLabel(CARD, none));
        assertTrue(PermissionCardPolicy.hint(CARD, none).contains("至少勾选一项"));

        Set<Perm> one = EnumSet.of(Perm.READ_TODO);
        assertTrue(PermissionCardPolicy.confirmEnabled(CARD, one));
        assertEquals("确认授权（已选 1/3）", PermissionCardPolicy.confirmLabel(CARD, one));
        assertTrue("只勾一部分：说明没勾的会停在等你确认", PermissionCardPolicy.hint(CARD, one).contains("等你确认"));

        assertEquals("确认授权（已选 3/3）", PermissionCardPolicy.confirmLabel(CARD, CARD));
        assertTrue(PermissionCardPolicy.hint(CARD, CARD).contains("全部"));
    }

    @Test public void uncheckablePermsDoNotCount() {
        assertFalse("勾了卡上没有的项不算", PermissionCardPolicy.confirmEnabled(CARD, EnumSet.of(Perm.SHELL)));
        assertFalse(PermissionCardPolicy.confirmEnabled(CARD, null));
    }

    @Test public void cardWithoutGrantablePermsConfirmsDirectly() {
        Set<Perm> none = EnumSet.noneOf(Perm.class);
        assertTrue("没有可预授权的项（不需要额外权限 / 只有每次确认的项）→ 直接可用", PermissionCardPolicy.confirmEnabled(none, none));
        assertEquals("确认创建", PermissionCardPolicy.confirmLabel(none, none));
        assertTrue(PermissionCardPolicy.confirmEnabled(null, none));
    }

    @Test public void disabledStateIsClearlyVisible() {
        assertTrue("灰掉要明显", PermissionCardPolicy.ALPHA_OFF <= 0.5f && PermissionCardPolicy.ALPHA_ON == 1f);
    }

    /** 只勾一部分：现有逻辑不变——勾了的能用，没勾的到点停在等确认（DENY_UNGRANTED）。 */
    @Test public void partialGrantKeepsExistingWaitForConfirm() {
        JobStore.Job j = new JobStore.Job();
        j.templateId = com.onesay.assistant.v2.jobs.DailyDigest.TEMPLATE_ID;
        j.command = "";
        Set<Perm> req = JobsEngine.analyze(j);
        assertTrue(req.contains(Perm.READ_TODO) && req.contains(Perm.WEB_SEARCH));
        TaskGrant g = TaskGrant.grant(j.spec(), req, EnumSet.of(Perm.READ_TODO), 0L);
        assertEquals(PermissionGuard.Verdict.DENY_UNGRANTED, PermissionGuard.check("web.search", null, g, false).verdict);
    }

    static String src(String rel) throws Exception {
        File f = new File(System.getProperty("bixby.root", "."), rel);
        return new String(Files.readAllBytes(f.toPath()), Charset.forName("UTF-8"));
    }

    /** 界面接线：初值来自规则（不再 setChecked(true)），勾选变化刷新按钮，点击时再查一次。 */
    @Test public void activityWiring() throws Exception {
        String a = src("app/src/com/onesay/assistant/v2/ui/PermissionCardActivity.java");
        assertFalse("不再默认全勾", a.contains("cb.setChecked(true)"));
        assertTrue(a.contains("cb.setChecked(PermissionCardPolicy.defaultChecked(p));"));
        assertTrue("先摆初值再挂监听", a.indexOf("cb.setChecked(PermissionCardPolicy.defaultChecked(p));") < a.indexOf("cb.setOnCheckedChangeListener(onCheck);"));
        assertTrue(a.contains("mOk.setEnabled(on);") && a.contains("mOk.setAlpha(") && a.contains("mOk.setText(PermissionCardPolicy.confirmLabel("));
        assertTrue("打开时就按规则摆好按钮状态", a.contains("root.addView(mHint);\n        updateConfirm();"));
        assertTrue("点击时双保险", a.contains("if (!PermissionCardPolicy.confirmEnabled(mGrantable, checked)) {"));
        assertTrue("确认仍走原来的入库路径", a.contains("PermissionCards.confirm(app, token, checked)"));
    }
}
