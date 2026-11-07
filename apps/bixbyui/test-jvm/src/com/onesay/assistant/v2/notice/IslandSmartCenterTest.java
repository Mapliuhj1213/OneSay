package com.onesay.assistant.v2.notice;

import org.junit.Before;
import org.junit.Test;

import java.util.TimeZone;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * 灵动岛智能化 · 中枢层：新来源同样服从 去重 / 每日上限 / 免打扰 / 开关；状态型提醒可撤；INLINE 动作留在岛上；验证码不进日志。
 */
public class IslandSmartCenterTest {

    long now = 1_800_000_000_000L;
    NoticeCenter c;
    NoticePolicy.Env env;

    @Before public void setUp() {
        c = new NoticeCenter(new NoticeStore.Mem(), new NoticeCenter.Clock() {
            @Override public long now() { return now; }
        });
        env = new NoticePolicy.Env();
        env.tz = TimeZone.getTimeZone("Asia/Shanghai");
        env.quietOverride = Boolean.FALSE;
    }

    @Test public void newSourcesRoundTripThroughJsonWithChronoAndTone() {
        IslandNotice n = CallNotices.inCall("张三", now - 5000, true, false);
        IslandNotice b = IslandNotice.fromJson(n.toJson());
        assertNotNull(b);
        assertEquals(IslandNotice.Source.CALL, b.source);
        assertEquals(now - 5000, b.chronoBase);
        assertEquals("call", b.tone);
        assertFalse(b.countdown);
        // 旧行（没有新字段）照常读回
        IslandNotice old = IslandNotice.fromJson("{\"k\":\"cb:1\",\"src\":\"CALLBACK\",\"p\":\"NORMAL\",\"t\":\"x\"}");
        assertEquals(0L, old.chronoBase);
        assertEquals("", old.tone);
    }

    @Test public void stateNoticeRetractsWhenStateEndsAndDismissStaysRemembered() {
        IslandNotice mic = PrivacyNotices.notice(PrivacyNotices.Kind.MIC, "com.tencent.mm", "微信", now);
        assertEquals(NoticeCenter.PostResult.SHOWN, c.post(mic, env, null));
        assertFalse("状态型提醒不落库", mic.persist);
        assertTrue(c.retract(mic.dedupKey, null, new NoticeCenter.Tick()));
        assertEquals(IslandNotice.State.EXPIRED, c.get(mic.dedupKey).state);
        assertEquals(NoticeCenter.NOTE_STATE_ENDED, c.get(mic.dedupKey).note);
        // 用户忽略过的：状态结束不改写成 EXPIRED（去重记忆照旧）
        IslandNotice cam = PrivacyNotices.notice(PrivacyNotices.Kind.CAM, "com.ss.android.ugc.aweme", "抖音", now);
        c.post(cam, env, null);
        c.dismiss(cam.dedupKey, null);
        assertFalse(c.retract(cam.dedupKey, null, null));
        assertEquals(IslandNotice.State.DISMISSED, c.get(cam.dedupKey).state);
    }

    @Test public void retractPrefixOnlyTouchesThatSourceAndPrefix() {
        c.post(CallNotices.ringing("李四", now), env, null);
        c.post(CallNotices.missed("王五", now - 60000), env, null);
        IslandNotice other = new IslandNotice(IslandNotice.Source.EXTERNAL, "call:ring:x", IslandNotice.Priority.NORMAL, "别的来源同前缀", "");
        c.post(other, env, null);
        int n = c.retractPrefix(IslandNotice.Source.CALL, "call:ring:", null, new NoticeCenter.Tick());
        assertEquals(1, n);
        assertEquals("未接来电不撤", IslandNotice.State.ACTIVE, c.get("call:miss:" + (now - 60000)).state);
        assertEquals("别的来源不动", IslandNotice.State.ACTIVE, c.get("call:ring:x").state);
    }

    @Test public void inlineActionKeepsNoticeActiveAndCustomFinishes() {
        IslandNotice call = CallNotices.inCall("张三", now, false, false);
        c.post(call, env, null);
        NoticeCenter.Outcome o = c.act(call.dedupKey, 0, new NoticeCenter.Tick());   // 静音
        assertEquals(NoticeAction.Kind.INLINE, o.kind);
        assertEquals(CallNotices.A_MUTE, o.arg);
        assertEquals("静音点完提醒还在岛上", IslandNotice.State.ACTIVE, c.get(call.dedupKey).state);
        // 来源更新按钮文字：同键重发 = 原地更新，不重新提醒
        NoticeCenter.Tick t = new NoticeCenter.Tick();
        assertEquals(NoticeCenter.PostResult.DUP_UPDATED, c.post(CallNotices.inCall("张三", now, true, false), env, t));
        assertFalse(t.alert);
        assertEquals("取消静音", c.get(call.dedupKey).actions.get(0).label);
        assertEquals("已静音", c.get(call.dedupKey).summary);
        o = c.act(call.dedupKey, 2, new NoticeCenter.Tick());   // 挂断
        assertEquals(NoticeAction.Kind.CUSTOM, o.kind);
        assertEquals(IslandNotice.State.ACTED, c.get(call.dedupKey).state);
    }

    @Test public void copyTextOutcomeIsMaskedInLogsAndOpenAppRejectsBadPackage() {
        NotifyClassifier.Notif in = new NotifyClassifier.Notif();
        in.pkg = "com.samsung.android.messaging";
        in.label = "信息";
        in.text = "【淘宝】您的验证码是 384712，5分钟内有效。";
        in.keyHash = "a1";
        IslandNotice otp = NotifyClassifier.classify(in, new NotifyClassifier.Prefs(), now).notice;
        c.post(otp, env, null);
        NoticeCenter.Outcome o = c.act(otp.dedupKey, 0, null);
        assertEquals(NoticeAction.Kind.COPY_TEXT, o.kind);
        assertEquals("动作参数里是完整的码（复制要用）", "384712", o.arg);
        assertFalse("日志里不出现验证码", o.toString().contains("384712"));
        assertFalse(otp.toJson().contains("384712") && otp.persist);

        IslandNotice bad = new IslandNotice(IslandNotice.Source.NOTIFY, "nl:x", IslandNotice.Priority.NORMAL, "x", "");
        bad.action(new NoticeAction("打开", NoticeAction.Kind.OPEN_APP, "intent://evil#Intent;end"));
        c.post(bad, env, null);
        assertNull("非法包名不执行", c.act("nl:x", 0, null).kind);
        assertEquals(IslandNotice.State.ACTIVE, c.get("nl:x").state);
        assertTrue(NoticeAction.safePackage("com.tencent.mm"));
        assertTrue(NoticeAction.safePackage("settings:com.tencent.mm"));
        assertFalse(NoticeAction.safePackage("com.x;rm -rf"));
    }

    @Test public void quietHoursPrivacyAndLiveCallShowButMissedCallAndNotifyAreHeld() {
        env.quietOverride = Boolean.TRUE;
        assertEquals(NoticeCenter.PostResult.SHOWN, c.post(PrivacyNotices.notice(PrivacyNotices.Kind.MIC, "com.a.b", "A", now), env, null));
        assertEquals(NoticeCenter.PostResult.SHOWN, c.post(CallNotices.inCall("张三", now, false, false), env, null));
        assertEquals("未接来电压到免打扰结束", NoticeCenter.PostResult.HELD_QUIET, c.post(CallNotices.missed("张三", now), env, null));
        NotifyClassifier.Notif in = new NotifyClassifier.Notif();
        in.pkg = "com.tencent.mm";
        in.label = "微信";
        in.title = "张三";
        in.text = "在吗";
        in.keyHash = "k";
        NotifyClassifier.Prefs p = new NotifyClassifier.Prefs();
        p.whitelist.add("com.tencent.mm");
        assertEquals(NoticeCenter.PostResult.HELD_QUIET, c.post(NotifyClassifier.classify(in, p, now).notice, env, null));
        assertEquals(NoticeCenter.PostResult.HELD_QUIET, c.post(SystemStatusNotices.headset("Buds", true, now), env, null));
        // 用户关掉「免打扰照常显示」→ 也压着
        env.quietExemptSources.clear();
        assertEquals(NoticeCenter.PostResult.HELD_QUIET, c.post(PrivacyNotices.notice(PrivacyNotices.Kind.CAM, "com.c.d", "C", now), env, null));
    }

    @Test public void perSourceSwitchAndDailyCapApply() {
        env.disabled.add(IslandNotice.Source.SYSTEM);
        assertEquals(NoticeCenter.PostResult.DROP_DISABLED, c.post(SystemStatusNotices.charge(50, now), env, null));
        env.disabled.clear();
        env.caps.put(IslandNotice.Source.SYSTEM, 2);
        assertEquals(NoticeCenter.PostResult.SHOWN, c.post(SystemStatusNotices.charge(50, now), env, null));
        now += 1;
        assertEquals(NoticeCenter.PostResult.SHOWN, c.post(SystemStatusNotices.charge(51, now), env, null));
        now += 1;
        assertEquals("每日上限", NoticeCenter.PostResult.DROP_CAP, c.post(SystemStatusNotices.charge(52, now), env, null));
        assertEquals(Integer.valueOf(60), NoticePolicy.DEFAULT_CAPS.get(IslandNotice.Source.PRIVACY));
        assertEquals(Integer.valueOf(20), NoticePolicy.DEFAULT_CAPS.get(IslandNotice.Source.SYSTEM));
        for (IslandNotice.Source s : IslandNotice.Source.values()) assertNotNull("每个来源都有上限 " + s, NoticePolicy.DEFAULT_CAPS.get(s));
    }

    @Test public void sourceSwitchOffWithdrawsLiveStateNotice() {
        IslandNotice mic = PrivacyNotices.notice(PrivacyNotices.Kind.MIC, "com.a.b", "A", now);
        c.post(mic, env, null);
        env.disabled.add(IslandNotice.Source.PRIVACY);
        NoticeCenter.Tick t = c.tick(env);
        assertEquals(1, t.gone.size());
        assertEquals(IslandNotice.State.WITHDRAWN, c.get(mic.dedupKey).state);
    }

    @Test public void settingsDefaults() {
        assertFalse("外卖打车默认关（可选）", NoticeSettings.defaultOf(NoticeSettings.K_SRC_ERRAND));
        assertFalse("正在播放默认关", NoticeSettings.defaultOf(NoticeSettings.K_N_MUSIC));
        assertFalse("验证码默认打码", NoticeSettings.defaultOf(NoticeSettings.K_REVEAL_OTP));
        assertFalse("通知正文默认打码", NoticeSettings.defaultOf(NoticeSettings.K_REVEAL_CONTENT));
        assertFalse("号码默认打码", NoticeSettings.defaultOf(NoticeSettings.K_REVEAL_NUMBER));
        assertTrue(NoticeSettings.defaultOf(NoticeSettings.K_SRC_PRIVACY));
        assertTrue(NoticeSettings.defaultOf(NoticeSettings.K_SRC_CALL));
        assertTrue(NoticeSettings.defaultOf(NoticeSettings.K_SRC_NOTIFY));
        assertTrue(NoticeSettings.defaultOf(NoticeSettings.K_SRC_SYSTEM));
        assertEquals(2, NoticeSettings.parseWhitelist("com.tencent.mm, com.tencent.mobileqq，bad pkg;rm").size());
    }
}
