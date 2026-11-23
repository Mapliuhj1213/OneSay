package com.onesay.assistant.plug.phongent.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

/** ★ sec-1123 M5：配对竞争 —— 批准后的口令只交给发起申请的那个地址；别的地址来取只看到 pending，也拿不到口令。 */
public class Sec1123PgAuthTest {

    static JSONObject body(String id) throws Exception {
        return new JSONObject().put("requestId", id).put("name", "iPad");
    }

    @Test public void tokenOnlyGoesToRequesterAddress() throws Exception {
        PgFakes f = new PgFakes();
        PgAuth a = new PgAuth(f, f, f, null);
        String id = PgFakes.rid('m');
        assertEquals(202, a.request(body(id), "100.64.0.7").code);
        assertEquals("100.64.0.7", a.pendingRemote(id));
        assertTrue(a.decide(id, true));
        assertNull("处理过就没有待批地址", a.pendingRemote(id));
        JSONObject other = a.poll(id, "192.168.1.66");
        assertEquals("pending", other.getString("status"));
        assertFalse("别的地址抢不到口令", other.has("token"));
        assertFalse(a.poll(id, null).has("token"));
        JSONObject mine = a.poll(id, "100.64.0.7");
        assertEquals("approved", mine.getString("status"));
        assertTrue(mine.getString("token").matches("[0-9a-f]{64}"));
        assertFalse("只交付一次", a.poll(id, "100.64.0.7").has("token"));
    }

    @Test public void oldCallsWithoutAddressUnchanged() throws Exception {
        PgFakes f = new PgFakes();
        PgAuth a = new PgAuth(f, f, f, null);
        String id = PgFakes.rid('n');
        assertEquals(202, a.request(body(id)).code);
        assertTrue(a.decide(id, true));
        assertTrue(a.poll(id).has("token"));
    }

    @Test public void deniedStaysDeniedForRequester() throws Exception {
        PgFakes f = new PgFakes();
        PgAuth a = new PgAuth(f, f, f, null);
        String id = PgFakes.rid('o');
        a.request(body(id), "100.64.0.9");
        assertTrue(a.decide(id, false));
        assertEquals("denied", a.poll(id, "100.64.0.9").getString("status"));
        assertEquals("pending", a.poll(id, "100.64.0.10").getString("status"));
    }
}
