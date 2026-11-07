package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.conn.google.GTime;
import com.onesay.assistant.v2.conn.google.GoogleApi;
import com.onesay.assistant.v2.conn.google.GoogleAuth;
import com.onesay.assistant.v2.conn.google.GoogleConnector;
import com.onesay.assistant.v2.conn.google.GoogleScopes;
import com.onesay.assistant.v2.executor.ExecutionGate;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

import static org.junit.Assert.*;

/** [conn/google] 日历 / Tasks：时间换算、REST 请求形状、权限范围门控、写操作黄卡。 */
public class GoogleConnectorTest {

    static final TimeZone SH = TimeZone.getTimeZone("Asia/Shanghai");

    @Test public void timeParsing() {
        GTime.When w = GTime.parse("2026-10-24 15:00", SH);
        assertEquals("2026-10-24T15:00:00+08:00", w.dateTime);
        assertFalse(w.allDay());
        assertEquals("2026-10-24T15:05:00+08:00", GTime.parse("2026-10-24T15:05", SH).dateTime);
        assertEquals("2026-10-24T09:30:00+08:00", GTime.parse("2026/10/24 9:30", SH).dateTime);
        GTime.When utc = GTime.parse("2026-10-24T07:00:00Z", SH);
        assertEquals("换成手机时区", "2026-10-24T15:00:00+08:00", utc.dateTime);
        assertEquals("2026-10-24T15:00:00+08:00", GTime.parse("2026-10-24T15:00:00.123+08:00", SH).dateTime);
        GTime.When d = GTime.parse("2026-10-24", SH);
        assertTrue(d.allDay());
        assertEquals("2026-10-24T00:00:00.000Z", GTime.taskDue(d, SH));
        assertEquals("2026-10-24T00:00:00.000Z", GTime.taskDue(w, SH));
        assertNull(GTime.parse("明天下午", SH));
        assertNull(GTime.parse("2026-13-40", SH));
        assertEquals("10-24 15:00", GTime.human("2026-10-24T07:00:00Z", null, SH));
        assertEquals("10-24（全天）", GTime.human(null, "2026-10-24", SH));
    }

    static final class Rec {
        final List<String> calls = new ArrayList<String>();
        final List<String> bodies = new ArrayList<String>();
    }

    static GoogleConnector conn(final Rec rec, String scopes) {
        MemSecrets sec = new MemSecrets();
        sec.put("google.refresh_token", "1//r");
        sec.put("google.access_token", "ya29.t");
        sec.put("google.access_exp", String.valueOf(System.currentTimeMillis() + 3600000L));
        sec.put("google.scopes", scopes);
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                rec.calls.add(req.method + " " + req.url);
                rec.bodies.add(body);
                if (req.url.contains("/calendar/v3/") && "GET".equals(req.method)) {
                    return FakeHttp.json(200, "{\"items\":[{\"id\":\"ev1\",\"summary\":\"组会\",\"location\":\"A301\","
                            + "\"start\":{\"dateTime\":\"2026-10-24T10:00:00+08:00\"},\"end\":{\"dateTime\":\"2026-10-24T11:00:00+08:00\"}},"
                            + "{\"id\":\"ev2\",\"summary\":\"生日\",\"start\":{\"date\":\"2026-10-24\"},\"end\":{\"date\":\"2026-10-25\"}}]}");
                }
                if (req.url.contains("/calendar/v3/")) return FakeHttp.json(200, "{\"id\":\"evN\",\"summary\":\"看牙\"}");
                if (req.url.contains("tasks.googleapis.com") && "GET".equals(req.method)) {
                    return FakeHttp.json(200, "{\"items\":[{\"id\":\"t1\",\"title\":\"交房租\",\"status\":\"needsAction\",\"due\":\"2026-10-25T00:00:00.000Z\"}]}");
                }
                if (req.url.contains("tasks.googleapis.com")) return FakeHttp.json(200, "{\"id\":\"t9\",\"title\":\"交房租\",\"status\":\"completed\"}");
                return FakeHttp.json(404, "{}");
            }
        });
        return new GoogleConnector(new GoogleApi(http, new GoogleAuth(http, sec, new GoogleAuth.Config() {
            @Override public String clientId() { return "1-a.apps.googleusercontent.com"; }
        })), SH, new GoogleConnector.Clock() {
            @Override public long now() { return GTime.parse("2026-10-24 08:00", SH).millis; }
        });
    }

    @Test public void calendarAndTasksOverRest() throws Exception {
        Rec rec = new Rec();
        GoogleConnector g = conn(rec, GoogleScopes.CALENDAR_EVENTS + " " + GoogleScopes.TASKS);
        assertEquals(6, g.tools().size());
        ConnResult r = g.call("calendar_list", new JSONObject());
        assertTrue(r.text, r.ok());
        assertTrue(r.text, r.text.contains("10-24 10:00–11:00 组会 @A301") && r.text.contains("event_id=ev1") && r.text.contains("生日"));
        String u = Codec.urlDecode(rec.calls.get(0));
        assertTrue(u, u.contains("timeMin=2026-10-24T00:00:00+08:00") && u.contains("timeMax=2026-10-25T00:00:00+08:00") && u.contains("singleEvents=true"));

        r = g.call("calendar_create", new JSONObject().put("summary", "看牙").put("start", "2026-10-26 15:00"));
        assertTrue(r.text, r.ok());
        JSONObject body = new JSONObject(rec.bodies.get(1));
        assertEquals("2026-10-26T15:00:00+08:00", body.getJSONObject("start").getString("dateTime"));
        assertEquals("默认 1 小时", "2026-10-26T16:00:00+08:00", body.getJSONObject("end").getString("dateTime"));
        assertEquals("Asia/Shanghai", body.getJSONObject("start").getString("timeZone"));
        assertEquals(ConnResult.Code.BAD_ARGS, g.call("calendar_create", new JSONObject().put("summary", "x").put("start", "下周")).code);
        assertEquals(ConnResult.Code.BAD_ARGS, g.call("calendar_create", new JSONObject().put("summary", "x")
                .put("start", "2026-10-26 15:00").put("end", "2026-10-26 14:00")).code);

        r = g.call("calendar_update", new JSONObject().put("event_id", "ev1").put("location", "B502"));
        assertTrue(r.ok());
        assertTrue(rec.calls.get(rec.calls.size() - 1).startsWith("PATCH ") && rec.calls.get(rec.calls.size() - 1).endsWith("/events/ev1"));
        assertEquals("只改给了的字段", "{\"location\":\"B502\"}", rec.bodies.get(rec.bodies.size() - 1));
        assertEquals("id 防注入", ConnResult.Code.BAD_ARGS, g.call("calendar_update", new JSONObject().put("event_id", "../x").put("summary", "y")).code);

        r = g.call("tasks_list", new JSONObject());
        assertTrue(r.text, r.text.contains("交房租（截止 10-25）") && r.text.contains("task_id=t1"));
        assertTrue(g.call("tasks_create", new JSONObject().put("title", "交房租").put("due", "2026-10-25")).ok());
        assertEquals("2026-10-25T00:00:00.000Z", new JSONObject(rec.bodies.get(rec.bodies.size() - 1)).getString("due"));
        r = g.call("tasks_update", new JSONObject().put("task_id", "t1").put("completed", true));
        assertTrue(r.text, r.ok() && r.text.contains("已完成"));
        assertEquals("completed", new JSONObject(rec.bodies.get(rec.bodies.size() - 1)).getString("status"));
    }

    @Test public void scopesGateToolsAndWritesNeedCard() throws Exception {
        Rec rec = new Rec();
        GoogleConnector onlyTasks = conn(rec, GoogleScopes.TASKS);
        for (ConnTool t : onlyTasks.tools()) assertTrue(t.name, t.name.startsWith("tasks_"));
        assertEquals(ConnResult.Code.AUTH_REVOKED, onlyTasks.call("calendar_list", new JSONObject()).code);
        assertTrue(conn(rec, GoogleScopes.GMAIL_READONLY).tools().isEmpty());

        ConnectorRegistry reg = new ConnectorRegistry();
        reg.register(conn(rec, GoogleScopes.CALENDAR_EVENTS + " " + GoogleScopes.TASKS));
        Capability create = reg.describe("conn.google.calendar_create");
        Capability list = reg.describe("conn.google.calendar_list");
        Capability tcreate = reg.describe("conn.google.tasks_create");
        assertTrue("写日历黄卡", ExecutionGate.evaluate(create, new JSONObject(), false).needsConfirm());
        assertTrue("写待办黄卡", ExecutionGate.evaluate(tcreate, new JSONObject(), false).needsConfirm());
        assertTrue("读日历直接过", ExecutionGate.evaluate(list, new JSONObject(), false).allowed());
        assertNull("写操作不能走 App 内只读执行", reg.executeRead(create, new JSONObject()));
        assertNotNull(reg.executeRead(list, new JSONObject()));
    }
}
