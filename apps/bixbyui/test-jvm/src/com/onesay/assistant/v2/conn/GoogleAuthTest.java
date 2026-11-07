package com.onesay.assistant.v2.conn;

import com.onesay.assistant.v2.conn.google.GoogleApi;
import com.onesay.assistant.v2.conn.google.GoogleAuth;
import com.onesay.assistant.v2.conn.google.GoogleScopes;
import com.onesay.assistant.v2.conn.google.LoopbackReceiver;

import org.json.JSONObject;
import org.junit.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** [conn/google] Codec、PKCE、令牌换取 / 刷新 / 断网 / 吊销、设备码范围限制、loopback 回调、带鉴权 API 的 401 重试。 */
public class GoogleAuthTest {

    static final String CID = "1234-abc.apps.googleusercontent.com";

    static final class Clock implements GoogleAuth.Clock {
        long t = 1000000L;
        @Override public long now() { return t; }
    }

    static GoogleAuth.Config cfg(final String id) {
        return new GoogleAuth.Config() {
            @Override public String clientId() { return id; }
        };
    }

    static String tokenJson(String at, String rt, String scope) {
        // id_token 中段 = {"email":"someone@gmail.com"}
        String idt = "x." + Codec.b64url(Codec.utf8("{\"email\":\"someone@gmail.com\"}")) + ".y";
        return "{\"access_token\":\"" + at + "\",\"expires_in\":3600" + (rt != null ? ",\"refresh_token\":\"" + rt + "\"" : "")
                + ",\"scope\":\"" + scope + "\",\"id_token\":\"" + idt + "\"}";
    }

    @Test public void codecMatchesJdk() {
        byte[][] samples = {new byte[0], {1}, {1, 2}, {1, 2, 3}, Codec.utf8("你好 Gmail 草稿 ~~~???>>>")};
        for (byte[] s : samples) {
            assertEquals(java.util.Base64.getEncoder().encodeToString(s), Codec.b64(s));
            assertEquals(java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(s), Codec.b64url(s));
            assertArrayEquals(s, Codec.unb64(Codec.b64(s)));
            assertArrayEquals(s, Codec.unb64(Codec.b64url(s)));
        }
        assertNull(Codec.unb64("a$b"));
        Map<String, String> q = Codec.query("code=4%2F0Ab&state=xy%20z&code=dup");
        assertEquals("4/0Ab", q.get("code"));
        assertEquals("xy z", q.get("state"));
    }

    @Test public void pkceAndAuthUrl() throws Exception {
        // RFC 7636 附录 B 的测试向量
        java.lang.reflect.Method m = GoogleAuth.class.getDeclaredMethod("pkceOf", String.class, String.class);
        m.setAccessible(true);
        GoogleAuth.Pkce p = (GoogleAuth.Pkce) m.invoke(null, "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk", "st");
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", p.challenge);
        GoogleAuth a = new GoogleAuth(new FakeHttp(null), new MemSecrets(), cfg(CID));
        String url = a.authUrl("http://127.0.0.1:5555", p, GoogleScopes.all());
        assertTrue(url.startsWith(GoogleAuth.AUTH_URL + "?"));
        Map<String, String> q = Codec.query(url.substring(url.indexOf('?') + 1));
        assertEquals(CID, q.get("client_id"));
        assertEquals("S256", q.get("code_challenge_method"));
        assertEquals(p.challenge, q.get("code_challenge"));
        assertEquals("offline", q.get("access_type"));
        assertEquals("http://127.0.0.1:5555", q.get("redirect_uri"));
        assertTrue(q.get("scope").contains(GoogleScopes.GMAIL_COMPOSE));
        assertTrue(q.get("scope").contains(GoogleScopes.CALENDAR_EVENTS));
        assertTrue(q.get("scope").contains(GoogleScopes.TASKS));
        assertFalse("verifier 不进 URL", url.contains(p.verifier));
        assertTrue(GoogleAuth.validClientId(CID));
        assertFalse(GoogleAuth.validClientId("http://evil"));
        assertFalse("没填 Client ID 不算配置好", new GoogleAuth(new FakeHttp(null), new MemSecrets(), cfg("")).configured());
    }

    @Test public void exchangeRefreshOfflineAndRevoke() throws Exception {
        final MemSecrets sec = new MemSecrets();
        sec.put(GoogleAuth.K_CLIENT_SECRET, "GOCSPX-secretvalue");
        final AtomicReference<String> mode = new AtomicReference<String>("ok");
        final int[] refreshes = {0};
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                if (req.url.equals(GoogleAuth.TOKEN_URL)) {
                    Map<String, String> f = Codec.query(body);
                    if ("authorization_code".equals(f.get("grant_type"))) {
                        assertEquals("ver", f.get("code_verifier"));
                        assertEquals("GOCSPX-secretvalue", f.get("client_secret"));
                        return FakeHttp.json(200, tokenJson("ya29.first", "1//refresh", GoogleScopes.GMAIL_READONLY + " " + GoogleScopes.GMAIL_COMPOSE));
                    }
                    if ("refresh_token".equals(f.get("grant_type"))) {
                        refreshes[0]++;
                        assertEquals("1//refresh", f.get("refresh_token"));
                        if ("offline".equals(mode.get())) return ConnHttp.Resp.fail(new java.net.UnknownHostException("oauth2.googleapis.com"));
                        if ("revoked".equals(mode.get())) return FakeHttp.json(400, "{\"error\":\"invalid_grant\",\"error_description\":\"Token has been expired or revoked.\"}");
                        return FakeHttp.json(200, tokenJson("ya29.second", null, GoogleScopes.GMAIL_READONLY));
                    }
                }
                if (req.url.equals(GoogleAuth.REVOKE_URL)) {
                    if ("offline".equals(mode.get())) return ConnHttp.Resp.fail(new java.net.ConnectException("x"));
                    return FakeHttp.json(200, "{}");
                }
                return FakeHttp.json(404, "{}");
            }
        });
        Clock clock = new Clock();
        java.lang.reflect.Constructor<GoogleAuth> ctor = GoogleAuth.class.getConstructor(ConnHttp.class, SecretStore.class,
                GoogleAuth.Config.class, GoogleAuth.Clock.class);
        GoogleAuth a = ctor.newInstance(http, sec, cfg(CID), clock);
        java.lang.reflect.Method m = GoogleAuth.class.getDeclaredMethod("pkceOf", String.class, String.class);
        m.setAccessible(true);
        GoogleAuth.Pkce p = (GoogleAuth.Pkce) m.invoke(null, "ver", "st");

        assertFalse(a.signedIn());
        assertEquals(ConnResult.Code.NOT_CONFIGURED, a.accessToken().code);
        ConnResult r = a.exchangeCode("4/code", "http://127.0.0.1:1", p);
        assertTrue(r.text, r.ok());
        assertTrue(a.signedIn());
        assertTrue(a.hasScope(GoogleScopes.GMAIL_COMPOSE));
        assertEquals("someone@gmail.com", a.email());
        assertEquals("1//refresh", sec.get("google.refresh_token"));
        assertTrue("状态行不含令牌也不含完整邮箱", !a.status().contains("ya29") && !a.status().contains("someone@"));

        assertEquals("ya29.first", a.accessToken().value);
        assertEquals("没过期不刷新", 0, refreshes[0]);
        clock.t += 3600 * 1000L;   // 过期
        assertEquals("ya29.second", a.accessToken().value);
        assertEquals(1, refreshes[0]);
        assertEquals("刷新响应不带 refresh_token 时保留旧的", "1//refresh", sec.get("google.refresh_token"));

        // 断网：不清令牌，返回 UNREACHABLE
        clock.t += 3600 * 1000L;
        mode.set("offline");
        GoogleAuth.Token t = a.accessToken();
        assertEquals(ConnResult.Code.UNREACHABLE, t.code);
        assertTrue("断网不登出", a.signedIn());

        // 吊销：invalid_grant → AUTH_REVOKED 并清本地
        mode.set("revoked");
        t = a.accessToken();
        assertEquals(ConnResult.Code.AUTH_REVOKED, t.code);
        assertFalse(a.signedIn());
        assertEquals("", sec.get("google.access_token"));

        // 主动退出：远端连不上也清本地
        mode.set("ok");
        a.exchangeCode("4/code", "http://127.0.0.1:1", p);
        assertTrue(a.signedIn());
        mode.set("offline");
        ConnResult rv = a.revoke();
        assertTrue(rv.ok());
        assertTrue(rv.text.contains("手动"));
        assertFalse(a.signedIn());
        for (String k : sec.map.keySet()) assertFalse("退出后只剩 client_secret: " + k, k.contains("token"));
    }

    @Test public void deviceFlowRefusesWorkspaceScopes() {
        final int[] calls = {0};
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                calls[0]++;
                if (req.url.equals(GoogleAuth.DEVICE_URL)) {
                    return FakeHttp.json(200, "{\"device_code\":\"dc\",\"user_code\":\"ABCD-EFGH\",\"verification_url\":\"https://www.google.com/device\",\"expires_in\":1800,\"interval\":5}");
                }
                return FakeHttp.json(428, "{\"error\":\"authorization_pending\"}");
            }
        });
        GoogleAuth a = new GoogleAuth(http, new MemSecrets(), cfg(CID));
        StringBuilder err = new StringBuilder();
        assertNull(a.startDevice(GoogleScopes.all(), err));
        assertTrue(err.toString(), err.toString().contains("loopback"));
        assertEquals("不白跑请求", 0, calls[0]);
        GoogleAuth.DeviceStart d = a.startDevice(Arrays.asList(GoogleScopes.OPENID, GoogleScopes.EMAIL), new StringBuilder());
        assertNotNull(d);
        assertEquals("ABCD-EFGH", d.userCode);
        assertEquals(ConnResult.Code.RATE_LIMITED, a.pollDevice(d).code);
    }

    @Test public void loopbackIgnoresWrongStateThenAccepts() throws Exception {
        final LoopbackReceiver rx = LoopbackReceiver.open();
        assertTrue(rx.redirectUri().startsWith("http://127.0.0.1:"));
        final AtomicReference<LoopbackReceiver.Result> res = new AtomicReference<LoopbackReceiver.Result>();
        Thread t = new Thread(new Runnable() {
            @Override public void run() { res.set(rx.await("good", 10000)); }
        });
        t.start();
        assertTrue(get(rx.port(), "/?code=evil&state=bad").contains("400"));
        assertTrue(get(rx.port(), "/?code=4%2Fok&state=good").contains("200"));
        t.join(5000);
        assertNotNull(res.get());
        assertEquals("4/ok", res.get().code);

        final LoopbackReceiver rx2 = LoopbackReceiver.open();
        Thread t2 = new Thread(new Runnable() {
            @Override public void run() { res.set(rx2.await("s", 10000)); }
        });
        t2.start();
        get(rx2.port(), "/?error=access_denied&state=s");
        t2.join(5000);
        assertFalse(res.get().ok());
        assertEquals("access_denied", res.get().error);
        assertEquals("超时", "timeout", LoopbackReceiver.open().await("x", 200).error);
    }

    static String get(int port, String path) throws Exception {
        Socket s = null;
        for (int i = 0; i < 50 && s == null; i++) {
            try {
                s = new Socket("127.0.0.1", port);
            } catch (Exception e) {
                Thread.sleep(20);
            }
        }
        try {
            OutputStream os = s.getOutputStream();
            os.write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").getBytes("UTF-8"));
            os.flush();
            InputStream in = s.getInputStream();
            byte[] buf = new byte[256];
            int n = in.read(buf);
            return n <= 0 ? "" : new String(buf, 0, n, "UTF-8");
        } finally {
            s.close();
        }
    }

    @Test public void apiRetriesOnceOn401AndClassifies403() throws Exception {
        MemSecrets sec = new MemSecrets();
        sec.put("google.refresh_token", "1//r");
        final int[] api = {0}, refresh = {0};
        FakeHttp http = new FakeHttp(new FakeHttp.Handler() {
            @Override public ConnHttp.Resp handle(ConnHttp.Req req, String body) {
                if (req.url.equals(GoogleAuth.TOKEN_URL)) {
                    refresh[0]++;
                    return FakeHttp.json(200, tokenJson("ya29.n" + refresh[0], null, GoogleScopes.TASKS));
                }
                api[0]++;
                if (req.url.contains("/scope")) return FakeHttp.json(403, "{\"error\":{\"status\":\"PERMISSION_DENIED\",\"message\":\"Request had insufficient authentication scopes.\"}}");
                if ("Bearer ya29.n1".equals(req.headers.get("Authorization"))) return FakeHttp.json(401, "{}");
                return FakeHttp.json(200, "{\"ok\":1}");
            }
        });
        GoogleApi g = new GoogleApi(http, new GoogleAuth(http, sec, cfg(CID)));
        ConnResult r = g.get("https://tasks.googleapis.com/x");
        assertTrue(r.text, r.ok());
        assertEquals(2, refresh[0]);
        assertEquals(new JSONObject("{\"ok\":1}").toString(), new JSONObject(r.data).toString());
        assertEquals(ConnResult.Code.AUTH_REVOKED, g.get("https://x/scope").code);
        for (ConnHttp.Req q : http.requests) {
            assertFalse("令牌只在头里，不在 URL", q.url.contains("ya29"));
        }
        assertFalse(Redact.s("Authorization: Bearer ya29.abcdef refresh_token=1//xyz").contains("ya29.abc"));
    }
}
