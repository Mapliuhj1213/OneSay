package com.onesay.assistant.v2.notice;

import org.junit.Test;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/** 灵动岛智能化 · 各来源的纯逻辑：麦克风 / 相机归属解析、通话状态机与打码、通知筛选与验证码、外卖打车解析、电池 / 耳机。 */
public class IslandSmartSourcesTest {

    static final long NOW = 1_800_000_000_000L;

    // ================================================================== 麦克风 / 相机

    static final String AUDIO_DUMP = "  mode owner: \n"
            + "\nRecordActivityMonitor dump time: 10-24 02:30:11.120\n"
            + "  session:57 -- source:VOICE_RECOGNITION -- uid:10213 -- pack:com.onesay.assistant -- format client=16000Hz\n"
            + "  session:89 -- source:MIC -- uid:10156 -- pack:com.tencent.mm -- format client=44100Hz\n";

    // ★ integrate 上机：三星 Note8 / Android 9 的 dumpsys media.audio_policy 格式（真机片段，数值保留、无隐私内容）
    @Test public void parsePolicyInputsSamsungAndroid9() {
        String d = " 16     -1.000     00       00\n\nInputs dump:\n- Input 430 dump:\n ID: 1353\n Sampling rate: 16000\n"
                + " Audio Sessions:\n   Audio session 1:\n   - session: 10465\n   - owner uid: 10237\n   - input source: 6\n"
                + "   Audio session 2:\n   - session: 10601\n   - owner uid: 10156\n\nStreams dump:\n";
        Map<Integer, Integer> m = PrivacyNotices.parsePolicyInputs(d);
        assertEquals(2, m.size());
        assertEquals(Integer.valueOf(10237), m.get(10465));
        assertEquals(Integer.valueOf(10156), m.get(10601));
        assertTrue(PrivacyNotices.parsePolicyInputs(null).isEmpty());
        assertTrue(PrivacyNotices.parsePolicyInputs("Permission Denial").isEmpty());
    }

    @Test public void parseAudioDumpAndFilterSelf() {
        Map<Integer, String> m = PrivacyNotices.parseAudio(AUDIO_DUMP);
        assertEquals(2, m.size());
        assertEquals("com.tencent.mm", m.get(89));
        Set<String> others = PrivacyNotices.others(m.values());
        assertEquals("OneSay 自己录音不提示自己", new LinkedHashSet<String>(Arrays.asList("com.tencent.mm")), others);
        assertTrue(PrivacyNotices.others(Arrays.asList("com.onesay.assistant.debug")).isEmpty());
        assertTrue(PrivacyNotices.parseAudio(null).isEmpty());
        assertTrue(PrivacyNotices.parseAudio("Permission Denial").isEmpty());
    }

    @Test public void parseCameraDumpAndroid9And8() {
        String p = "== Service global info: ==\nActive Camera Clients:\n[\n(Camera ID: 0, Cost: 100, PID: 1234, Score: 0, State: 0User Id: 0, "
                + "Client Package Name: com.sec.android.app.camera, Conflicting Client Devices: {})\n]\n"
                + "Allowed user IDs: 0\n Client Package Name: com.never.counted\n";
        assertEquals(new LinkedHashSet<String>(Arrays.asList("com.sec.android.app.camera")), PrivacyNotices.parseCamera(p));
        String o = "Device 0 is open. Client instance dump:\n    Client priority level: 0\n    Client package: com.instagram.android\n";
        assertEquals(new LinkedHashSet<String>(Arrays.asList("com.instagram.android")), PrivacyNotices.parseCamera(o));
        assertTrue(PrivacyNotices.parseCamera("Active Camera Clients:\n[\n]\n").isEmpty());
    }

    @Test public void sessionCacheOnlyLooksUpUnknownSessions() {
        PrivacyNotices.SessionCache sc = new PrivacyNotices.SessionCache();
        assertTrue(sc.needsLookup(Arrays.asList(57)));
        sc.learn(PrivacyNotices.parseAudio(AUDIO_DUMP));
        assertFalse("自己的唤醒会话解析过一次就不再 dumpsys", sc.needsLookup(Arrays.asList(57)));
        assertTrue(sc.needsLookup(Arrays.asList(57, 101)));
        assertEquals(new LinkedHashSet<String>(Arrays.asList("com.onesay.assistant", "com.tencent.mm")), sc.packagesOf(Arrays.asList(57, 89, 101)));
        assertTrue("认不出的会话（root 不可用）", sc.hasUnknown(Arrays.asList(101)));
    }

    @Test public void privacyDiffAndNoticeShape() {
        PrivacyNotices.Diff d = PrivacyNotices.diff(new LinkedHashSet<String>(Arrays.asList("a.b", "c.d")),
                new LinkedHashSet<String>(Arrays.asList("c.d", "e.f")));
        assertEquals(Arrays.asList("e.f"), d.started);
        assertEquals(Arrays.asList("a.b"), d.stopped);
        IslandNotice n = PrivacyNotices.notice(PrivacyNotices.Kind.MIC, "com.tencent.mm", "微信", NOW);
        assertEquals("麦克风使用中 · 微信", n.title);
        assertEquals("mic", n.tone);
        assertEquals(NOW, n.chronoBase);
        assertEquals(IslandNotice.Source.PRIVACY, n.source);
        assertEquals("settings:com.tencent.mm", n.actions.get(0).arg);
        assertEquals("cam", PrivacyNotices.notice(PrivacyNotices.Kind.CAM, "x.y", null, NOW).tone);
    }

    // ================================================================== 通话

    @Test public void numberMasking() {
        assertEquals("138****5678", CallNotices.maskNumber("13812345678"));
        assertEquals("+861****5678", CallNotices.maskNumber("+86 138-1234-5678"));
        assertEquals("10086", CallNotices.maskNumber("10086"));
        assertEquals("未知号码", CallNotices.maskNumber(""));
        assertEquals("未知号码", CallNotices.maskNumber(null));
        assertEquals("妈妈", CallNotices.display("妈妈", "13812345678", false));
        assertEquals("138****5678", CallNotices.display(null, "13812345678", false));
        assertEquals("13812345678", CallNotices.display(null, "13812345678", true));
    }

    @Test public void callMachineRingAnswerHangup() {
        CallNotices.Machine m = new CallNotices.Machine();
        CallNotices.Step s = m.on(CallNotices.RINGING, "138****5678", NOW);
        assertEquals("call:ring:" + NOW, s.post.dedupKey);
        assertEquals(IslandNotice.Priority.HIGH, s.post.priority);
        assertEquals(Arrays.asList("接听", "挂断"), s.post.actionLabels());
        s = m.on(CallNotices.OFFHOOK, "", NOW + 3000);
        assertEquals("接起来：撤来电", Arrays.asList("call:ring:"), Arrays.asList(s.retract));
        assertEquals("通话中 · 138****5678", s.post.title);
        assertEquals(NOW + 3000, s.post.chronoBase);
        assertEquals(Arrays.asList("静音", "免提", "挂断"), s.post.actionLabels());
        assertEquals(NoticeAction.Kind.INLINE, s.post.actions.get(0).kind);
        s = m.on(CallNotices.IDLE, "", NOW + 60000);
        assertNull("接过的不算未接", s.post);
        assertEquals(Arrays.asList("call:ring:", "call:live:"), Arrays.asList(s.retract));
    }

    @Test public void callMachineMissedAndOutgoing() {
        CallNotices.Machine m = new CallNotices.Machine();
        m.on(CallNotices.RINGING, "妈妈", NOW);
        CallNotices.Step s = m.on(CallNotices.IDLE, "", NOW + 20000);
        assertNotNull(s.post);
        assertEquals("未接来电 · 妈妈", s.post.title);
        assertEquals("call:miss:" + NOW, s.post.dedupKey);
        // 去拨：IDLE → OFFHOOK 直接通话中，挂了不算未接
        s = m.on(CallNotices.OFFHOOK, "", NOW + 100000);
        assertTrue(s.post.title.startsWith("通话中"));
        assertNull(m.on(CallNotices.IDLE, "", NOW + 200000).post);
    }

    @Test public void callKeysAndArgsNeverContainNumber() {
        CallNotices.Machine m = new CallNotices.Machine();
        String num = "13812345678";
        CallNotices.Step s = m.on(CallNotices.RINGING, CallNotices.display(null, num, false), NOW);
        assertFalse(s.post.dedupKey.contains("1234"));
        assertFalse(s.post.toJson().contains(num));
        for (NoticeAction a : s.post.actions) assertFalse(a.arg.contains("1234"));
        IslandNotice live = CallNotices.inCall("x", NOW, true, true);
        assertEquals(Arrays.asList("取消静音", "关免提", "挂断"), live.actionLabels());
        assertEquals("已静音 · 免提", live.summary);
    }

    // ================================================================== 通知筛选 / 验证码

    static NotifyClassifier.Notif nf(String pkg, String title, String text) {
        NotifyClassifier.Notif n = new NotifyClassifier.Notif();
        n.pkg = pkg;
        n.label = pkg;
        n.title = title;
        n.text = text;
        n.keyHash = Integer.toHexString((pkg + title).hashCode());
        return n;
    }

    @Test public void otpExtraction() {
        assertEquals("384712", NotifyClassifier.extractOtp("【淘宝】您的验证码是384712，5分钟内有效，请勿泄露。"));
        assertEquals("5521", NotifyClassifier.extractOtp("验证码：5521（10分钟内有效）"));
        assertEquals("英文 + 句号结尾", "829301", NotifyClassifier.extractOtp("Your verification code is 829301. Do not share it."));
        assertNull("混合码字母过半（更像型号）不算", NotifyClassifier.extractOtp("动态码 A7K93Q"));
        assertEquals("混合码数字过半才算", "4B71", NotifyClassifier.extractOtp("校验码 4B71"));
        assertEquals("金额不算码", "6625", NotifyClassifier.extractOtp("【银行】您尾号1234的卡支付200.00元，验证码6625"));
        assertNull("没有提示词", NotifyClassifier.extractOtp("今晚 2030 见"));
        assertNull("只有时长", NotifyClassifier.extractOtp("验证码将在 5 分钟后过期"));
        assertEquals("••••12", NotifyClassifier.maskCode("384712"));
    }

    @Test public void otpNoticeIsHighMaskedNotPersistedAndCopyable() {
        NotifyClassifier.Notif in = nf("com.samsung.android.messaging", "106****", "【京东】验证码 902113，用于登录。");
        NotifyClassifier.Result r = NotifyClassifier.classify(in, new NotifyClassifier.Prefs(), NOW);
        assertEquals(NotifyClassifier.Type.OTP, r.type);
        assertEquals("验证码 · 京东", r.notice.title);
        assertFalse("默认打码", r.notice.summary.contains("902113"));
        assertTrue(r.notice.summary.contains("13"));
        assertFalse(r.notice.persist);
        assertEquals(IslandNotice.Priority.HIGH, r.notice.priority);
        assertEquals(NoticeAction.Kind.COPY_TEXT, r.notice.actions.get(0).kind);
        assertEquals("902113", r.notice.actions.get(0).arg);
        assertFalse("去重键不含码", r.notice.dedupKey.contains("902113"));
        NotifyClassifier.Prefs reveal = new NotifyClassifier.Prefs();
        reveal.revealOtp = true;
        assertEquals("902113", NotifyClassifier.classify(in, reveal, NOW).notice.summary);
        NotifyClassifier.Prefs off = new NotifyClassifier.Prefs();
        off.otp = false;
        assertNull(NotifyClassifier.classify(in, off, NOW));
        assertFalse("验证码：短信被划掉也不撤", NotifyClassifier.retractOnRemoved(r.notice.dedupKey));
    }

    @Test public void voipAlarmTimerNavMusicWhitelist() {
        NotifyClassifier.Prefs p = new NotifyClassifier.Prefs();
        NotifyClassifier.Notif v = nf("com.tencent.mm", "张三", "邀请你语音通话");
        v.category = "call";
        NotifyClassifier.Result r = NotifyClassifier.classify(v, p, NOW);
        assertEquals(NotifyClassifier.Type.VOIP, r.type);
        assertFalse("默认不显示是谁", r.notice.title.contains("张三"));
        NotifyClassifier.Notif dial = nf("com.samsung.android.dialer", "来电", "138");
        dial.category = "call";
        assertNull("系统拨号盘交给电话状态", NotifyClassifier.classify(dial, p, NOW));

        NotifyClassifier.Notif t = nf("com.sec.android.app.clockpackage", "计时器", "");
        t.ongoing = true;
        t.usesChronometer = true;
        t.chronoCountDown = true;
        t.when = NOW + 300000;
        r = NotifyClassifier.classify(t, p, NOW);
        assertEquals(NotifyClassifier.Type.TIMER, r.type);
        assertTrue(r.notice.countdown);
        assertEquals(NOW + 300000, r.notice.chronoBase);
        NotifyClassifier.Notif a = nf("com.sec.android.app.clockpackage", "07:30", "闹钟");
        a.category = "alarm";
        assertEquals(NotifyClassifier.Type.ALARM, NotifyClassifier.classify(a, p, NOW).type);

        NotifyClassifier.Notif nav = nf("com.autonavi.minimap", "200米后右转", "进入中山路");
        nav.ongoing = true;
        r = NotifyClassifier.classify(nav, p, NOW);
        assertEquals(NotifyClassifier.Type.NAV, r.type);
        assertEquals("200米后右转", r.notice.title);
        assertTrue(NotifyClassifier.retractOnRemoved(r.notice.dedupKey));

        NotifyClassifier.Notif mu = nf("com.netease.cloudmusic", "晴天", "周杰伦");
        mu.template = "android.app.Notification$MediaStyle";
        mu.ongoing = true;
        assertNull("正在播放默认关", NotifyClassifier.classify(mu, p, NOW));
        p.music = true;
        assertEquals(NotifyClassifier.Type.MUSIC, NotifyClassifier.classify(mu, p, NOW).type);

        NotifyClassifier.Notif w = nf("com.tencent.mobileqq", "李四", "明天开会");
        assertNull("不在白名单不上岛", NotifyClassifier.classify(w, p, NOW));
        p.whitelist.add("com.tencent.mobileqq");
        r = NotifyClassifier.classify(w, p, NOW);
        assertEquals(NotifyClassifier.Type.WHITELIST, r.type);
        assertFalse("默认打码：不显示发件人和正文", r.notice.title.contains("李四") || r.notice.summary.contains("开会"));
        p.revealContent = true;
        assertTrue(NotifyClassifier.classify(w, p, NOW).notice.summary.contains("开会"));

        assertNull("自己的通知不上岛", NotifyClassifier.classify(nf("com.onesay.assistant", "验证码 1234", ""), p, NOW));
        assertNull("普通通知不上岛", NotifyClassifier.classify(nf("com.taobao.taobao", "双11", "大促"), new NotifyClassifier.Prefs(), NOW));
    }

    // ================================================================== 外卖 / 打车

    @Test public void errandMeituanStagesAndEta() {
        IslandNotice n = ErrandParser.parse("com.sankuai.meituan.takeoutnew", "美团外卖", "骑手正在送餐", "预计 12:35 送达，骑手已取餐", "k", NOW);
        assertNotNull(n);
        assertEquals("外卖 · 配送中 · 12:35", n.title);
        assertEquals("美团外卖 · 预计 12:35 送达", n.summary);
        assertEquals(IslandNotice.Source.ERRAND, n.source);
        assertEquals("errand:k", n.dedupKey);
        assertEquals("商家备餐中", ErrandParser.stage("me.ele", "商家已接单，正在备餐"));
        n = ErrandParser.parse("me.ele", "饿了么", "订单已送达", "感谢使用", "k2", NOW);
        assertEquals("外卖 · 已送达", n.title);
        assertEquals("ok", n.tone);
        assertEquals(NOW + 10L * 60000L, n.expireAt);
        assertEquals("约 8 分钟", ErrandParser.eta("骑手距您 1.2 公里，还有8分钟"));
    }

    @Test public void errandDidiPlateNoAddressAndQuietFailure() {
        IslandNotice n = ErrandParser.parse("com.sdu.didi.psnger", "滴滴", "司机正在赶来", "白色 大众 京A·12345 距您1.5公里 预计3分钟到达 上车点：xx小区北门", "d", NOW);
        assertNotNull(n);
        assertEquals("打车 · 司机接驾中", n.title);
        assertTrue(n.summary.contains("京A·12345"));
        assertTrue(n.summary.contains("约 3 分钟"));
        assertFalse("地址不上岛", n.summary.contains("小区"));
        assertNull("认不出阶段 → 不显示", ErrandParser.parse("com.sankuai.meituan", "美团", "领红包", "满30减10", "x", NOW));
        assertNull("不支持的包", ErrandParser.parse("com.taobao.taobao", "淘宝", "配送中", "", "x", NOW));
        NotifyClassifier.Prefs p = new NotifyClassifier.Prefs();
        NotifyClassifier.Notif in = nf("me.ele", "骑手正在送餐", "预计 19:05 送达");
        in.ongoing = true;
        assertNull("外卖默认关", NotifyClassifier.classify(in, p, NOW));
        p.errand = true;
        assertEquals(NotifyClassifier.Type.ERRAND, NotifyClassifier.classify(in, p, NOW).type);
        NotifyClassifier.Notif ad = nf("me.ele", "今日特价", "下单立减");
        assertNull("认得包但解析不出 → 安静", NotifyClassifier.classify(ad, p, NOW));
    }

    // ================================================================== 电池 / 耳机

    @Test public void batteryChargeFullLowOnce() {
        SystemStatusNotices.Battery b = new SystemStatusNotices.Battery();
        assertNull("服务刚起来的第一次快照不算插上", b.on(50, true, 20, NOW));
        assertNull(b.on(51, true, 20, NOW));
        assertNull(b.on(51, false, 20, NOW));
        IslandNotice c = b.on(51, true, 20, NOW + 1);
        assertEquals("正在充电 · 51%", c.title);
        assertEquals(NOW + 1 + SystemStatusNotices.BRIEF_MS, c.expireAt);
        assertEquals("电已充满 · 100%", b.on(100, true, 20, NOW + 2).title);
        assertNull("充满只提醒一次", b.on(100, true, 20, NOW + 3));
        assertNull(b.on(30, false, 20, NOW + 4));
        IslandNotice low = b.on(20, false, 20, NOW + 5);
        assertEquals("电量低 · 20%", low.title);
        assertEquals(IslandNotice.Priority.NORMAL, low.priority);
        assertNull("同一档不重复", b.on(19, false, 20, NOW + 6));
        IslandNotice crit = b.on(10, false, 20, NOW + 7);
        assertEquals(IslandNotice.Priority.HIGH, crit.priority);
        assertNull(b.on(8, false, 20, NOW + 8));
        b.on(9, true, 20, NOW + 9);
        b.on(25, true, 20, NOW + 10);
        assertNotNull("充上来过阈值再掉下去会再提醒", b.on(20, false, 20, NOW + 11));
    }

    @Test public void headsetNotice() {
        assertEquals("蓝牙耳机已连接 · Galaxy Buds", SystemStatusNotices.headset("Galaxy Buds", true, NOW).title);
        assertEquals("耳机已连接", SystemStatusNotices.headset(null, false, NOW).title);
    }
}
