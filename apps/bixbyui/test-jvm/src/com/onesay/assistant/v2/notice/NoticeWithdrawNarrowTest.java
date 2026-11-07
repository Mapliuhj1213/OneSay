package com.onesay.assistant.v2.notice;

import com.onesay.assistant.v2.conn.ConnResult;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 2026-10-23 BUG-I1 收窄：只有「连接器缺失 / 未登录 / 授权失效」才撤下日程提醒；
 * 普通的网络失败 / 超时 / 远端错误保留已有提醒，2 分钟后照常重试；calclear 行为不变。
 */
public class NoticeWithdrawNarrowTest {

    static final long MIN = 60000L;

    long now;
    NoticeCenter c;
    NoticePolicy.Env env;

    @Before public void setUp() {
        now = NoticeCenterTest.at(10, 0);
        c = new NoticeCenter(new NoticeStore.Mem(), new NoticeCenter.Clock() {
            @Override public long now() { return now; }
        });
        env = new NoticePolicy.Env();
        env.tz = NoticeCenterTest.TZ;
    }

    static String hooks() throws Exception {
        return NoticeSourcesTest.read("app/src/com/onesay/assistant/v2/notice/NoticeHooks.java");
    }

    static String calSrc() throws Exception {
        String src = hooks();
        return src.substring(src.indexOf("class CalendarSource"), src.indexOf("class MailSource"));
    }

    /** 分类：只有 NOT_CONFIGURED / AUTH_REVOKED 算「来源不可用」。 */
    @Test public void onlyMissingOrRevokedCountsAsLost() {
        List<ConnResult.Code> lost = new ArrayList<ConnResult.Code>();
        for (ConnResult.Code code : ConnResult.Code.values()) if (NoticePolicy.connectorLost(code)) lost.add(code);
        assertEquals(Arrays.asList(ConnResult.Code.NOT_CONFIGURED, ConnResult.Code.AUTH_REVOKED), lost);
        assertFalse("网络不可达不撤", NoticePolicy.connectorLost(ConnResult.Code.UNREACHABLE));
        assertFalse("超时不撤", NoticePolicy.connectorLost(ConnResult.Code.TIMEOUT));
        assertFalse("远端 5xx 不撤", NoticePolicy.connectorLost(ConnResult.Code.REMOTE_ERROR));
        assertFalse("限流不撤", NoticePolicy.connectorLost(ConnResult.Code.RATE_LIMITED));
        assertFalse(NoticePolicy.connectorLost(ConnResult.Code.OK));
        assertFalse(NoticePolicy.connectorLost(null));
    }

    /** syncSource：Unavailable → 撤；别的异常 / null → return，什么都不动（不调 withdraw、不 sync 空列表）。 */
    @Test public void syncSourceWithdrawsOnlyOnUnavailable() throws Exception {
        String src = hooks();
        String sync = src.substring(src.indexOf("static void syncSource"), src.indexOf("static void withdrawUnavailable"));
        int un = sync.indexOf("catch (NoticeSource.Unavailable u) {");
        int other = sync.indexOf("catch (Throwable t) {");
        int nul = sync.indexOf("if (fresh == null) {");
        assertTrue(un > 0 && other > un && nul > other);
        String unBranch = sync.substring(un, other);
        assertTrue(unBranch.contains("withdrawUnavailable(app, s, u.getMessage());") && unBranch.contains("return;"));
        String otherBranch = sync.substring(other, nul);
        assertFalse("普通异常不撤", otherBranch.contains("withdraw"));
        assertTrue(otherBranch.contains("return;"));
        String nulBranch = sync.substring(nul, sync.indexOf("NoticeCenter.Tick t = restored(app).sync("));
        assertFalse("返回 null 不撤", nulBranch.contains("withdraw"));
        assertTrue(nulBranch.contains("return;"));
    }

    /** 日程来源：连接器缺失 / 授权失效抛 Unavailable；网络失败返回 null；失败都记 mLastFailed → 2 分钟重试。 */
    @Test public void calendarSourceClassifiesFailures() throws Exception {
        String cal = calSrc();
        assertTrue(cal.contains("if (!connectorReady()) throw new NoticeSource.Unavailable("));
        int notOk = cal.indexOf("if (!r.ok()) {");
        String failBlock = cal.substring(notOk, cal.indexOf("List<IslandNotice> l = CalendarNotices.plan(CalendarNotices.parseGoogle"));
        assertTrue(failBlock.indexOf("if (NoticePolicy.connectorLost(r.code)) throw new NoticeSource.Unavailable(") < failBlock.indexOf("return null;"));
        assertTrue("失败先记下，成功才清", cal.indexOf("mLastFailed = true;") < cal.indexOf("if (!connectorReady()) throw"));
        assertTrue("失败后 2 分钟重试", cal.contains("static final long RETRY_MS = 2L * 60000L;") && cal.contains("mLastFailed ? RETRY_MS : POLL_MS"));
        assertTrue("提醒 / 连接器在时照常轮询（不因有提醒而每次都叫醒）",
                cal.contains("return mLastPoll <= 0 ? now : mLastPoll + (mLastFailed ? RETRY_MS : POLL_MS);"));
    }

    /** calclear 行为不变：清掉假日程后连接器缺失 → Unavailable → 撤。 */
    @Test public void calclearStillWithdraws() throws Exception {
        String src = hooks();
        String clear = src.substring(src.indexOf("public static void clearFakeEvents"));
        clear = clear.substring(0, clear.indexOf("}") + 1);
        assertTrue(clear.contains("CalendarSource.FAKE.clear();") && clear.contains("syncSource(app, sCal);"));
        String cal = calSrc();
        assertTrue("FAKE 空了才看连接器", cal.indexOf("if (!FAKE.isEmpty()) {") < cal.indexOf("if (!connectorReady()) throw"));
    }

    /** 调试模拟只在 debug 生效：release 里 sim 恒为 null。 */
    @Test public void failSimulationDebugOnly() throws Exception {
        String src = hooks();
        assertTrue(calSrc().contains("String sim = com.onesay.assistant.v2.BuildVariant.IS_DEBUG ? sFailSim : null;"));
        String fn = src.substring(src.indexOf("public static String simulateCalendarFailure"));
        assertTrue(fn.indexOf("if (!com.onesay.assistant.v2.BuildVariant.IS_DEBUG) return") < fn.indexOf("CalendarSource.sFailSim = kind;"));
    }

    /**
     * 中心层场景：网络失败期间（来源什么都不报）提醒保持 ACTIVE / PENDING，到点照常出；
     * 网络恢复后同一批同键 sync 回来 = 不变、不重新展开；之后授权失效才撤，再登录回来。
     */
    @Test public void networkBlipKeepsThenAuthLossWithdraws() {
        IslandNotice a = NoticeWithdrawTest.cal("cal:a:1", now, now + 30 * MIN);
        IslandNotice b = NoticeWithdrawTest.cal("cal:b:1", now + 2 * MIN, now + 40 * MIN);
        NoticeCenter.Tick first = c.sync(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, Arrays.asList(a, b), env);
        assertTrue(first.alert);
        // 网络失败：syncSource 直接 return，中心不受任何调用；2 分钟后到点的 b 照常出
        now += 2 * MIN;
        NoticeCenter.Tick tick = c.tick(env);
        assertEquals(Arrays.asList("cal:b:1"), NoticeWithdrawTest.keys(tick.surfaced));
        assertEquals(Arrays.asList("cal:a:1", "cal:b:1"), NoticeWithdrawTest.keys(c.active()));
        // 重试成功：同键同内容 → 不变、不再展开
        NoticeCenter.Tick back = c.sync(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, Arrays.asList(
                NoticeWithdrawTest.cal("cal:a:1", now - 2 * MIN, now + 28 * MIN), NoticeWithdrawTest.cal("cal:b:1", now, now + 38 * MIN)), env);
        assertFalse(back.alert);
        assertTrue(back.surfaced.isEmpty() && back.gone.isEmpty());
        assertEquals(IslandNotice.State.ACTIVE, c.get("cal:a:1").state);
        // 授权失效：撤
        c.withdrawSource(IslandNotice.Source.CALENDAR, CalendarNotices.TAG, NoticeCenter.NOTE_SOURCE_LOST);
        assertTrue(c.active().isEmpty());
        assertEquals(IslandNotice.State.WITHDRAWN, c.get("cal:b:1").state);
    }

}
