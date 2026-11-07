package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.conn.google.GTime;
import com.onesay.assistant.v2.conn.google.GoogleApi;
import com.onesay.assistant.v2.conn.google.GoogleAuth;
import com.onesay.assistant.v2.conn.google.GoogleConnector;
import com.onesay.assistant.v2.conn.google.GoogleScopes;
import com.onesay.assistant.v2.conn.google.LocalCalendar;
import com.onesay.assistant.v2.conn.mail.DraftLedger;
import com.onesay.assistant.v2.conn.mail.MailAccount;
import com.onesay.assistant.v2.conn.mail.MailConnector;
import com.onesay.assistant.v2.conn.mail.MailTestKit;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

/** [conn] 第 4 项：连不上 Google 时明确提示、快速失败不卡死、退回 IMAP / 本机日历。 */
public class GoogleReachTest {

    static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    static final class Clock implements GoogleReach.Clock {
        long t = 1_000_000L;
        @Override public long now() { return t; }
    }

    @Test public void cooldownDoublingAndRecovery() {
        Clock c = new Clock();
        GoogleReach r = new GoogleReach(c);
        assertTrue(r.tryGoogle());
        assertEquals("", r.notice());
        r.reportGoogle(ConnResult.fail(ConnResult.Code.UNREACHABLE, "x"));
        assertFalse(r.tryGoogle());
        assertTrue(r.notice(), r.notice().contains("连不上 Google") && r.notice().contains("代理"));
        r.reportGoogle(ConnResult.fail(ConnResult.Code.UNREACHABLE, "同一次调用的第二层回报"));
        c.t += GoogleReach.COOLDOWN_MS;
        assertTrue("10 分钟后放行一次试探（重复回报没有让冷却翻倍）", r.tryGoogle());
        r.reportGoogle(ConnResult.fail(ConnResult.Code.TIMEOUT, "x"));
        c.t += GoogleReach.COOLDOWN_MS;
        assertFalse("连续失败冷却翻倍", r.tryGoogle());
        c.t += GoogleReach.COOLDOWN_MS;
        assertTrue(r.tryGoogle());
        r.reportGoogle(ConnResult.fail(ConnResult.Code.AUTH_REVOKED, "连得上但被拒 = 网络通"));
        assertFalse(r.down());
        assertTrue(r.tryGoogle());
        r.reportGoogle(ConnResult.fail(ConnResult.Code.UNREACHABLE, "x"));
        r.reportGoogle(ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "未登录不代表网络通"));
        assertTrue(r.down());
        for (int i = 0; i < 10; i++) {
            c.t += GoogleReach.MAX_COOLDOWN_MS;
            r.reportGoogle(ConnResult.fail(ConnResult.Code.UNREACHABLE, "x"));
        }
        c.t += GoogleReach.MAX_COOLDOWN_MS;
        assertTrue("冷却上限 1 小时", r.tryGoogle());
    }

    @Test public void probeIsShortAndRecorded() {
        GoogleReach r = new GoogleReach(new Clock());
        final List<ConnHttp.Req> seen = new ArrayList<ConnHttp.Req>();
        FakeHttp down = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                seen.add(req);
                return ConnHttp.Resp.fail(new java.net.ConnectException("refused"));
            }
        });
        assertEquals(ConnResult.Code.UNREACHABLE, r.probe(down).code);
        assertEquals(3000, seen.get(0).connectMs);
        assertEquals(3000, seen.get(0).readMs);
        assertTrue(r.down());
        FakeHttp up = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) { return FakeHttp.json(404, ""); }
        });
        assertTrue(r.probe(up).ok());
        assertFalse(r.down());
    }

    static GoogleApi api(FakeHttp http, String scopes) {
        MemSecrets sec = new MemSecrets();
        sec.put("google.refresh_token", "1//r");
        sec.put("google.access_token", "ya29.t");
        sec.put("google.access_exp", String.valueOf(System.currentTimeMillis() + 3600000L));
        sec.put("google.scopes", scopes);
        return new GoogleApi(http, new GoogleAuth(http, sec, new GoogleAuth.Config() {
            @Override public String clientId() { return "1-a.apps.googleusercontent.com"; }
        }));
    }

    @Test public void apiFailsFastDuringCooldown() {
        final int[] calls = {0};
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                calls[0]++;
                return ConnHttp.Resp.fail(new java.net.UnknownHostException("www.googleapis.com"));
            }
        });
        Clock c = new Clock();
        GoogleReach reach = new GoogleReach(c);
        GoogleApi g = api(http, GoogleScopes.CALENDAR_EVENTS).reach(reach);
        assertEquals(ConnResult.Code.UNREACHABLE, g.get("https://www.googleapis.com/x").code);
        assertEquals(1, calls[0]);
        ConnResult again = g.get("https://www.googleapis.com/x");
        assertEquals(ConnResult.Code.UNREACHABLE, again.code);
        assertEquals("冷却期内不再发请求（不卡死）", 1, calls[0]);
        assertTrue(again.text, again.text.contains("连不上 Google"));
    }

    static final class FakeLocal implements LocalCalendar {
        final List<Event> events = new ArrayList<Event>();
        boolean denied;
        String inserted;

        @Override public List<Event> events(long from, long to) {
            if (denied) throw new SecurityException("READ_CALENDAR");
            return events;
        }

        @Override public boolean openInsert(String title, long begin, long end, boolean allDay, String location, String description) {
            inserted = title + "@" + begin;
            return true;
        }
    }

    @Test public void calendarFallsBackToLocal() throws Exception {
        final String[] mode = {"down"};
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                if ("timeout".equals(mode[0])) return ConnHttp.Resp.fail(new java.net.SocketTimeoutException("read"));
                return ConnHttp.Resp.fail(new java.net.UnknownHostException("www.googleapis.com"));
            }
        });
        Clock c = new Clock();
        GoogleReach reach = new GoogleReach(c);
        FakeLocal local = new FakeLocal();
        long ten = GTime.parse("2026-10-24 10:00", SH).millis;
        local.events.add(new LocalCalendar.Event("体检", ten, ten + 3600000L, false, "社区医院"));
        GoogleConnector g = new GoogleConnector(api(http, GoogleScopes.CALENDAR_EVENTS + " " + GoogleScopes.TASKS).reach(reach), SH,
                new GoogleConnector.Clock() {
                    @Override public long now() { return GTime.parse("2026-10-24 08:00", SH).millis; }
                }, local);

        ConnResult r = g.call("calendar_list", new JSONObject());
        assertTrue(r.text, r.ok());
        assertTrue(r.text, r.text.contains("手机本机日历") && r.text.contains("10-24 10:00 体检 @社区医院") && r.text.contains("连不上 Google"));

        r = g.call("calendar_create", new JSONObject().put("summary", "看牙").put("start", "2026-10-26 15:00"));
        assertTrue(r.text, r.ok() && r.text.contains("点「保存」") && r.text.contains("不会同步"));
        assertEquals("看牙@" + GTime.parse("2026-10-26 15:00", SH).millis, local.inserted);

        assertTrue(g.call("calendar_update", new JSONObject().put("event_id", "e1").put("summary", "x")).text.contains("改不了"));
        ConnResult t = g.call("tasks_list", new JSONObject());
        assertEquals(ConnResult.Code.UNREACHABLE, t.code);
        assertTrue(t.text, t.text.contains("本机待办"));

        local.denied = true;
        r = g.call("calendar_list", new JSONObject());
        assertEquals(ConnResult.Code.DENIED, r.code);
        assertTrue(r.text.contains("日历」权限"));

        // 超时：请求可能已到 Google，不自动转本机（防重复）
        c.t += GoogleReach.MAX_COOLDOWN_MS * 4;
        reach.reportGoogle(ConnResult.ok("恢复"));
        mode[0] = "timeout";
        local.inserted = null;
        r = g.call("calendar_create", new JSONObject().put("summary", "看牙").put("start", "2026-10-26 15:00"));
        assertEquals(ConnResult.Code.TIMEOUT, r.code);
        assertNull(local.inserted);
    }

    @Test public void mailSkipsGmailWithNoticeAndExplainsWhenNoImap() {
        Clock c = new Clock();
        GoogleReach reach = new GoogleReach(c);
        MailTestKit.FakeAccount gmail = new MailTestKit.FakeAccount(MailAccount.GMAIL);
        MailTestKit.FakeAccount imap = new MailTestKit.FakeAccount(MailAccount.IMAP);
        MailConnector m = new MailConnector(gmail, imap, new DraftLedger(null), reach);
        reach.reportGoogle(ConnResult.fail(ConnResult.Code.UNREACHABLE, "x"));
        ConnResult r = m.unreadImportant(5, null);
        assertTrue(r.text, r.ok() && r.text.contains("连不上 Google") && r.text.contains("已改用 IMAP") && r.text.contains("imap 未读"));

        imap.ready = false;
        gmail.failWith = ConnResult.Code.UNREACHABLE;
        r = m.unreadImportant(5, null);
        assertEquals(ConnResult.Code.UNREACHABLE, r.code);
        assertTrue(r.text, r.text.contains("IMAP") && r.text.contains("备用"));
    }

    /** r2/fixes：连接阶段超时 = 请求没发出 → 归 UNREACHABLE，建日程退回本机，不说「可能已经建好」。 */
    @Test public void connectPhaseTimeoutFallsBackAndNeverSaysMaybeCreated() throws Exception {
        final String[] mode = {"connect-timeout"};
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                if ("cancel".equals(mode[0])) return ConnHttp.Resp.fail(new java.io.InterruptedIOException("interrupted"));
                if ("tls".equals(mode[0])) {
                    javax.net.ssl.SSLHandshakeException e = new javax.net.ssl.SSLHandshakeException("Chain validation failed");
                    e.initCause(new java.security.cert.CertificateException("bad cert"));
                    return ConnHttp.Resp.fail(new ConnectPhaseException(e));
                }
                return ConnHttp.Resp.fail(new ConnectPhaseException(new java.net.SocketTimeoutException("connect timed out")));
            }
        });
        Clock c = new Clock();
        GoogleReach reach = new GoogleReach(c);
        FakeLocal local = new FakeLocal();
        GoogleConnector g = new GoogleConnector(api(http, GoogleScopes.CALENDAR_EVENTS + " " + GoogleScopes.TASKS).reach(reach), SH,
                new GoogleConnector.Clock() {
                    @Override public long now() { return GTime.parse("2026-10-24 08:00", SH).millis; }
                }, local);
        ConnResult r = g.call("calendar_create", new JSONObject().put("summary", "看牙").put("start", "2026-10-26 15:00"));
        assertTrue(r.text, r.ok() && r.text.contains("点「保存」"));
        assertFalse(r.text, r.text.contains("可能已经建好"));
        assertEquals("看牙@" + GTime.parse("2026-10-26 15:00", SH).millis, local.inserted);
        assertTrue("连接超时计入不可达", reach.down());

        // 取消：不算网络故障，不清零也不累加冷却
        c.t += GoogleReach.MAX_COOLDOWN_MS * 4;
        reach.reportGoogle(ConnResult.ok("恢复"));
        mode[0] = "cancel";
        local.inserted = null;
        r = g.call("calendar_create", new JSONObject().put("summary", "看牙").put("start", "2026-10-26 15:00"));
        assertEquals(ConnResult.Code.CANCELLED, r.code);
        assertNull("取消不转本机", local.inserted);
        assertFalse("取消不算 Google 不可达", reach.down());

        // 证书校验失败：TLS_ERROR，不是「可能在大陆网络」
        mode[0] = "tls";
        r = g.call("calendar_create", new JSONObject().put("summary", "看牙").put("start", "2026-10-26 15:00"));
        assertEquals(ConnResult.Code.TLS_ERROR, r.code);
        assertFalse(r.text, r.text.contains("可能已经建好"));
        assertTrue(reach.notice(), reach.notice().contains("证书校验失败"));
    }
}
