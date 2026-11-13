package com.onesay.assistant.plug.vyrse.core;

import org.json.JSONObject;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import static org.junit.Assert.*;

/**
 * compat-vyrse 互通：对面是 <b>VYRSE 自己的</b> desktop/src/main/phone-bridge.ts（node headless，tools/vyrse-interop/run.sh 起），
 * 这边是 OneSay 的真实客户端代码（LanSocket + VyrsePairing + VyrseLink + VyrseDispatcher），只把手机能力换成假的。
 * 没给 -Dvyrse.interop.ctl 时整类跳过（全量 JVM 套件里不跑）。
 */
public class VyrseInteropTest {

    static String CTL;
    static final String ADDR = "127.0.0.1:47630";

    @BeforeClass public static void need() {
        CTL = System.getProperty("vyrse.interop.ctl");
        Assume.assumeTrue("需要 tools/vyrse-interop/run.sh 起的 VYRSE 桥接", CTL != null && CTL.length() > 0);
    }

    static JSONObject ctl(String path, JSONObject body) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(CTL + path).openConnection();
        c.setReadTimeout(30000);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestMethod("POST");
            OutputStream o = c.getOutputStream();
            o.write(body.toString().getBytes("UTF-8"));
            o.close();
        }
        InputStream in = c.getInputStream();
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int r;
        while ((r = in.read(buf)) > 0) b.write(buf, 0, r);
        in.close();
        return new JSONObject(b.toString("UTF-8"));
    }

    static JSONObject cmd(String action, JSONObject args) throws Exception {
        JSONObject r = ctl("/cmd", new JSONObject().put("action", action).put("args", args == null ? new JSONObject() : args));
        System.out.println("[interop] " + action + " -> " + r);
        return r;
    }

    static String code() throws Exception {
        return ctl("/pair", null).getString("code");
    }

    @Test public void endToEndAgainstVyrseBridge() throws Exception {
        System.out.println("[interop] VYRSE commit " + System.getProperty("vyrse.interop.commit"));
        VyrseHttp http = new VyrseHttp.LanSocket();

        // —— 配对：错码 → 403 Wrong code；对码 → key；错 5 次作废 ——
        String code = code();
        String wrongCode = code.equals("000000") ? "000001" : "000000";
        VyrsePairing.Result wrong = VyrsePairing.pair(http, ADDR, wrongCode, "OneSay interop");
        assertFalse(wrong.ok);
        assertTrue(wrong.error, wrong.error.contains("配对码不对"));
        VyrsePairing.Result ok = VyrsePairing.pair(http, ADDR, code, "OneSay interop");
        assertTrue(ok.error, ok.ok);
        assertEquals("http://127.0.0.1:47630", ok.base);
        assertEquals("VYRSE key：32 字节 base64url", 43, ok.token.length());
        VyrsePairing.Result reuse = VyrsePairing.pair(http, ADDR, code, "again");
        assertTrue("码用过就作废", reuse.error.contains("没在配对"));

        String c2 = code();
        String w2 = c2.equals("111111") ? "222222" : "111111";
        for (int i = 0; i < 5; i++) assertFalse(VyrsePairing.pair(http, ADDR, w2, "x").ok);
        VyrsePairing.Result after5 = VyrsePairing.pair(http, ADDR, c2, "x");
        assertFalse("错 5 次后正确码也不行", after5.ok);
        assertTrue(after5.error, after5.error.contains("没在配对"));

        // —— 假 key → 401 ——
        VyrseLink bad = new VyrseLink(http, ok.base, "not-a-real-key-not-a-real-key-000000000000", new VyrseDispatcher(new VyrseFakes.Phone()), null, null);
        try {
            bad.pollOnce();
            fail("假 key 应该 401");
        } catch (VyrseLink.Unauthorized expected) {
        }

        // —— 长轮询：起真 VyrseLink ——
        VyrseFakes.Phone phone = new VyrseFakes.Phone();
        phone.apps.add(new PhoneAbilities.AppEntry("com.tencent.mm", "微信"));
        phone.apps.add(new PhoneAbilities.AppEntry("com.eg.android.AlipayGphone", "支付宝"));
        phone.notices.add(new PhoneAbilities.Notice("com.tencent.mm", "微信", "妈妈", "验证码 123456 别告诉别人", System.currentTimeMillis()));
        VyrseFakes.Listener l = new VyrseFakes.Listener();
        final VyrseLink link = new VyrseLink(http, ok.base, ok.token, new VyrseDispatcher(phone), new VyrseLink.Sleeper() {
            @Override public boolean sleep(long ms) {
                try {
                    Thread.sleep(Math.min(ms, 500));
                    return true;
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }, l);
        Thread t = new Thread(link, "interop-link");
        t.setDaemon(true);
        t.start();
        String deviceId = null;
        for (int i = 0; i < 50 && deviceId == null; i++) {
            Thread.sleep(100);
            org.json.JSONArray ds = ctl("/status", null).getJSONArray("devices");
            for (int k = 0; k < ds.length(); k++) {
                JSONObject d = ds.getJSONObject(k);
                if (d.getBoolean("linked") && "OneSay interop".equals(d.getString("name"))) deviceId = d.getString("id");
            }
        }
        assertNotNull("VYRSE 电脑端认为这台手机在线（linked）", deviceId);

        JSONObject find = cmd("find", new JSONObject().put("state", "start"));
        assertTrue(find.getBoolean("ok"));
        assertTrue(find.getBoolean("ringing"));
        assertEquals(30, find.getInt("seconds"));
        assertEquals(15, phone.vol);
        assertEquals(Long.valueOf(30000L), phone.delays.get(0));
        JSONObject stop = cmd("find", new JSONObject().put("state", "stop"));
        assertFalse(stop.getBoolean("ringing"));
        assertEquals("恢复原音量", 3, phone.vol);

        assertEquals("on", cmd("flashlight", new JSONObject().put("state", "on")).getString("flashlight"));
        JSONObject n = cmd("notifications", new JSONObject().put("limit", 5));
        assertEquals(1, n.getInt("count"));
        assertFalse(n.toString().contains("123456"));
        assertTrue(cmd("notify", new JSONObject().put("title", "VYRSE").put("text", "from PC")).getBoolean("shown"));
        assertEquals(77, cmd("battery", null).getInt("percent"));
        assertFalse(cmd("status", null).getBoolean("fullControl"));
        assertEquals("微信", cmd("open_app", new JSONObject().put("name", "微信")).getString("opened"));
        assertTrue(cmd("open_app", new JSONObject().put("name", "支付宝")).getString("error").contains("off limits"));
        JSONObject tap = cmd("tap", new JSONObject().put("n", 1));
        assertTrue(tap.getBoolean("unsupported"));
        assertTrue(cmd("evil", null).getString("error").startsWith("Unknown phone action"));
        assertEquals(java.util.Arrays.asList("com.tencent.mm"), phone.launched);

        // —— 电脑移除这台手机 → 下一轮 401 → 停止 ——
        ctl("/remove", new JSONObject().put("id", deviceId));
        t.join(40000);
        assertFalse("循环已退出", t.isAlive());
        assertEquals(1, l.unauthorized);
        assertEquals(VyrseLink.State.UNAUTHORIZED, link.state());
        System.out.println("[interop] commands seen by OneSay: " + l.cmds);
    }
}
