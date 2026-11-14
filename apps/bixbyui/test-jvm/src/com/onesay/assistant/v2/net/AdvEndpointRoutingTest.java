package com.onesay.assistant.v2.net;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.Assert.*;

/**
 * 找茬 · 大模型地址与备用模型：
 * <ul>
 *   <li>main：主模型接口地址（MainModelEndpoint.check）只收 https，debug 包额外收 http；不能带用户名密码、? 查询串、# 片段、空白；
 *       去末尾 / 与误填的 /chat/completions、/models；scheme 统一小写；超长拒；</li>
 *   <li>model：模型名校验；</li>
 *   <li>key：Key 只发往 https 且主机与已配置的主模型 / 百炼一致（keyAllowedFor）；http 只在 debug 包把主模型配成 http 时、且同主机；</li>
 *   <li>user：用户自填服务地址（UserEndpoint，https / wss）；</li>
 *   <li>switch：什么时候换备用（超时、5xx、429 换；401 / 402 / 400 / 403 / 404、成功不换）；换了之后请求体 model 换掉、非默认思考系去掉 thinking。</li>
 * </ul>
 * 备用模型地址只看前缀（https:// 或 http://127.0.0.1:）这一条在 AdvBackupUrlTest（2026-11-14 已修）。
 */
@RunWith(Parameterized.class)
public class AdvEndpointRoutingTest {

    @Parameterized.Parameters(name = "{index}: {0} [{1}] {2}")
    public static Collection<Object[]> data() {
        List<Object[]> out = new ArrayList<Object[]>();
        Set<String> seen = new HashSet<String>();
        String L = new String(new char[290]).replace('\0', 'a');
        // {输入, release 结果, debug 结果}：ok:<规范化后> / bad
        String[][] main = {
                {"", "ok:https://api.deepseek.com", "ok:https://api.deepseek.com"}, {"   ", "ok:https://api.deepseek.com", "ok:https://api.deepseek.com"},
                {"https://api.example.com/v1", "ok:https://api.example.com/v1", "ok:https://api.example.com/v1"},
                {"https://api.example.com/v1/", "ok:https://api.example.com/v1", "ok:https://api.example.com/v1"},
                {"https://api.example.com/v1/chat/completions", "ok:https://api.example.com/v1", "ok:https://api.example.com/v1"},
                {"https://api.example.com/v1/CHAT/COMPLETIONS/", "ok:https://api.example.com/v1", "ok:https://api.example.com/v1"},
                {"https://api.example.com/v1/models", "ok:https://api.example.com/v1", "ok:https://api.example.com/v1"},
                {"HTTPS://Api.Example.com/v1", "ok:https://Api.Example.com/v1", "ok:https://Api.Example.com/v1"},
                {"https://api.example.com:8443/v1", "ok:https://api.example.com:8443/v1", "ok:https://api.example.com:8443/v1"},
                {"  https://api.example.com/v1  ", "ok:https://api.example.com/v1", "ok:https://api.example.com/v1"},
                {"http://127.0.0.1:8080/v1", "bad", "ok:http://127.0.0.1:8080/v1"}, {"http://192.168.1.5:11434/v1", "bad", "ok:http://192.168.1.5:11434/v1"},
                {"HTTP://localhost:1234", "bad", "ok:http://localhost:1234"},
                {"https://user:pw@api.example.com/v1", "bad", "bad"}, {"https://user@api.example.com", "bad", "bad"}, {"http://u:p@127.0.0.1:8080", "bad", "bad"},
                {"https://api.example.com/v1?key=x", "bad", "bad"}, {"https://api.example.com/v1#x", "bad", "bad"}, {"https://api.example.com/v1?", "bad", "bad"},
                {"https://api.example.com/v 1", "bad", "bad"}, {"https://api.example.com/v1\n", "ok:https://api.example.com/v1", "ok:https://api.example.com/v1"},
                {"https://api.example.com/\tv1", "bad", "bad"}, {"https://api.example.com/v\u00001", "bad", "bad"}, {"ftp://api.example.com", "bad", "bad"},
                {"wss://api.example.com", "bad", "bad"}, {"api.example.com", "bad", "bad"}, {"//api.example.com", "bad", "bad"}, {"https://", "bad", "bad"},
                {"https:///v1", "bad", "bad"}, {"javascript:alert(1)", "bad", "bad"}, {"https://" + L + ".com", "bad", "bad"},
                {"https://api.example.com/\u00a0v1", "bad", "bad"}, {"https://аpi.example.com/v1", "bad", "bad"}, {"https://api.example.com/v1/chat/completions/chat/completions", "ok:https://api.example.com/v1/chat/completions", "ok:https://api.example.com/v1/chat/completions"},
                {"https://[::1]:8443/v1", "ok:https://[::1]:8443/v1", "ok:https://[::1]:8443/v1"}, {"https://api.example.com/%20", "ok:https://api.example.com/%20", "ok:https://api.example.com/%20"},
        };
        for (String[] m : main) {
            add(out, seen, "main", m[0], "release", m[1]);
            add(out, seen, "main", m[0], "debug", m[2]);
        }
        String[][] model = {{"", "ok"}, {"deepseek-chat", "ok"}, {"qwen-plus", "ok"}, {"gpt-4o-mini", "ok"}, {"org/model:latest", "ok"}, {"a@b+c", "ok"},
                {"Model.v2_1", "ok"}, {"-model", "bad"}, {"_model", "bad"}, {"model name", "bad"}, {"model;rm", "bad"}, {"模型", "bad"}, {"model\n", "ok"},
                {new String(new char[101]).replace('\0', 'm'), "bad"}, {new String(new char[100]).replace('\0', 'm'), "ok"}, {"m$(id)", "bad"}, {"m\"x", "bad"}};
        for (String[] m : model) add(out, seen, "model", m[0], "", m[1]);
        // {要发 Key 的地址, 主模型 base, debug?, 期望}
        String[][] key = {
                {"https://api.deepseek.com/chat/completions", "", "release", "true"}, {"https://API.DEEPSEEK.COM/chat/completions", "", "release", "true"},
                {"https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions", "", "release", "true"},
                {"https://evil.example/chat/completions", "", "release", "false"}, {"http://api.deepseek.com/chat/completions", "", "release", "false"},
                {"https://api.deepseek.com.evil.example/x", "", "release", "false"}, {"https://evil.example/?u=https://api.deepseek.com", "", "release", "false"},
                {"https://api.deepseek.com@evil.example/x", "", "release", "false"}, {"https://user@api.deepseek.com/x", "", "release", "true"},
                {"https://my.proxy.example/v1/chat/completions", "https://my.proxy.example/v1", "release", "true"},
                {"https://api.deepseek.com/chat/completions", "https://my.proxy.example/v1", "release", "false"},
                {"http://127.0.0.1:8080/v1/chat/completions", "http://127.0.0.1:8080/v1", "debug", "true"},
                {"http://127.0.0.1:8080/v1/chat/completions", "http://127.0.0.1:8080/v1", "release", "false"},
                {"http://127.0.0.2:8080/v1/chat/completions", "http://127.0.0.1:8080/v1", "debug", "false"},
                {"https://127.0.0.1:8080/v1/chat/completions", "http://127.0.0.1:8080/v1", "debug", "true"},
                {"http://evil.example/v1", "http://127.0.0.1:8080/v1", "debug", "false"}, {"ftp://api.deepseek.com/x", "", "release", "false"},
                {"wss://api.deepseek.com/x", "", "release", "false"}, {"", "", "release", "false"}, {"not a url", "", "release", "false"},
                {"https:///chat/completions", "", "release", "false"}, {"https://аpi.deepseek.com/chat", "", "release", "false"},
        };
        for (String[] k : key) add(out, seen, "key", k[0] + " ⟂ " + k[1], k[2], k[3]);
        String[][] user = {
                {"wss://dashscope.aliyuncs.com/api-ws/v1/inference", "wss", "n", "ok"}, {"https://api.qweather.com", "https", "n", "ok"},
                {"https://api.example.com/x?a=1", "https", "y", "ok"}, {"https://api.example.com/x?a=1", "https", "n", "bad"}, {"ws://x.example", "wss", "n", "bad"},
                {"http://api.example.com", "https", "n", "bad"}, {"https://u@api.example.com", "https", "n", "bad"}, {"https://api.example.com#f", "https", "y", "bad"},
                {"wss://", "wss", "n", "bad"}, {"WSS://Host.example/p", "wss", "n", "ok"}, {"https://" + new String(new char[510]).replace('\0', 'a'), "https", "n", "bad"},
                {"", "https", "n", "bad"}, {"https://a b.com", "https", "n", "bad"}, {"https://api.example.com", "wss", "n", "bad"},
        };
        for (String[] u : user) add(out, seen, "user", u[0], u[1] + "|" + u[2], u[3]);
        // {httpCode, error, 期望 switch}
        String[][] sw = {{"429", "", "true"}, {"500", "", "true"}, {"502", "", "true"}, {"503", "", "true"}, {"504", "", "true"}, {"599", "", "true"},
                {"-1", "连接超时", "true"}, {"-1", "首字节超时", "true"}, {"-1", "超过总时长", "true"}, {"-1", "java.net.SocketTimeoutException: Read timed out", "true"},
                {"-1", "UnknownHostException", "false"}, {"-1", "Connection refused", "false"}, {"400", "", "false"}, {"401", "", "false"}, {"402", "", "false"},
                {"403", "", "false"}, {"404", "", "false"}, {"413", "", "false"}, {"600", "", "false"}, {"499", "", "false"}, {"200", "ok", "false"}, {"0", "", "false"}};
        for (String[] s : sw) add(out, seen, "switch", s[0] + "/" + s[1], "", s[2]);
        String[] rewrite = {"deepseek-chat", "qwen-plus", "deepseek-v4-pro", "deepseek-flash", "gpt-4o"};
        for (String m : rewrite) add(out, seen, "rewrite", m, "", "");
        return out;
    }

    static void add(List<Object[]> out, Set<String> seen, String kind, String in, String mode, String want) {
        if (!seen.add(kind + "\u0001" + in + "\u0001" + mode)) throw new IllegalStateException("dup " + in);
        out.add(new Object[]{kind, in, mode, want});
    }

    private final String kind, in, mode, want;

    public AdvEndpointRoutingTest(String kind, String in, String mode, String want) {
        this.kind = kind;
        this.in = in;
        this.mode = mode;
        this.want = want;
    }

    @After public void reset() {
        MainModelEndpoint.setForProcess("", false);
        LlmEndpoints.setForProcess(null, null);
    }

    @Test public void endpoint() throws Exception {
        String at = kind + " [" + in + "] " + mode;
        if (kind.equals("main")) {
            MainModelEndpoint.Check c = MainModelEndpoint.check(in, mode.equals("debug"));
            if (want.equals("bad")) {
                assertFalse(at + " → " + c.base, c.ok);
                assertNotNull(c.error);
                // 进程内设置非法值 → 回落默认
                MainModelEndpoint.setForProcess(in, mode.equals("debug"));
                assertEquals(at, MainModelEndpoint.DEFAULT_BASE, MainModelEndpoint.base());
            } else {
                assertTrue(at + " → " + c.error, c.ok);
                assertEquals(at, want.substring(3), c.base);
                MainModelEndpoint.setForProcess(in, mode.equals("debug"));
                assertEquals(want.substring(3) + "/chat/completions", MainModelEndpoint.chatUrl());
                assertFalse("日志只有主机名", MainModelEndpoint.logLine().contains("/v1"));
            }
        } else if (kind.equals("model")) {
            assertEquals(at, want.equals("ok"), MainModelEndpoint.checkModel(in) == null);
        } else if (kind.equals("key")) {
            String[] p = in.split(" ⟂ ", -1);
            MainModelEndpoint.setForProcess(p[1], mode.equals("debug"));
            // [ci-1114] 测试隔离：LlmClientTest 等在静态块里把 sTestAllowLoopback 置 true 且不复位，同一 JVM 先跑它们时
            //   这里的 127.0.0.1 用例会被测试后门放行。判定生产行为时强制关掉后门，断言本身不放宽
            boolean prev = LlmEndpoints.sTestAllowLoopback;
            LlmEndpoints.sTestAllowLoopback = false;
            try {
                assertEquals(at, Boolean.parseBoolean(want), LlmEndpoints.keyAllowedFor(p[0]));
            } finally {
                LlmEndpoints.sTestAllowLoopback = prev;
            }
        } else if (kind.equals("user")) {
            String[] p = mode.split("\\|");
            String got = UserEndpoint.normalize(in, p[0], p[1].equals("y"));
            assertEquals(at, want.equals("ok"), got != null);
        } else if (kind.equals("switch")) {
            String[] p = in.split("/", 2);
            int code = Integer.parseInt(p[0]);
            LlmResponse r = code == 200 ? LlmResponse.parse(200, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"hi\"}}]}")
                    : LlmResponse.failure(code, "", p[1].length() == 0 ? null : p[1]);
            assertEquals(at, Boolean.parseBoolean(want), LlmFallback.shouldSwitch(r));
            if (Boolean.parseBoolean(want)) assertTrue(LlmFallback.why(r).startsWith("主模型"));
        } else if (kind.equals("rewrite")) {
            LlmFallback.Backup b = new LlmFallback.Backup("https://b.example/v1/chat/completions", in, "", new LlmClient.KeyProvider() {
                @Override public String apiKey() { return "sk-test"; }
            });
            String body = new JSONObject().put("model", "deepseek-chat").put("thinking", new JSONObject().put("type", "enabled")).put("messages", new org.json.JSONArray()).toString();
            JSONObject o = new JSONObject(LlmFallback.rewrite(body, b));
            assertEquals(in, o.getString("model"));
            assertEquals(at, LlmModels.thinkingByDefault(in), o.has("thinking"));
            assertTrue(o.has("messages"));
            assertEquals("坏请求体原样返回", "not json", LlmFallback.rewrite("not json", b));
            assertTrue(b.usable());
            assertEquals(in, b.label);
            assertFalse("同地址同模型 = 同一个，不换", !LlmFallback.same("https://b.example/v1/chat/completions", new JSONObject().put("model", in).toString(), b));
        } else fail(kind);
    }
}
