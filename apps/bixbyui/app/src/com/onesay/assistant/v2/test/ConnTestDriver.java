package com.onesay.assistant.v2.test;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import com.onesay.assistant.v2.conn.ConnHooks;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.ConnSettings;
import com.onesay.assistant.v2.conn.ConnTool;
import com.onesay.assistant.v2.conn.Connector;
import com.onesay.assistant.v2.conn.Redact;
import com.onesay.assistant.v2.conn.mcp.McpServerConfig;

import org.json.JSONObject;

/**
 * [conn] 连接器调试入口（仅 debug 包，经 TestRunReceiver；adb 需 su）。日志<b>不打任何令牌 / 邮件正文</b>（只打长度、计数、状态）。
 *
 * <pre>
 * R=com.onesay.assistant.debug/com.onesay.assistant.v2.test.TestRunReceiver
 * su -c "am broadcast -n $R --es conn status"
 * su -c "am broadcast -n $R --es conn mcp_add --es json '{\"id\":\"demo\",\"url\":\"https://…/mcp\",\"allow\":{\"search\":\"read\"}}'"
 * su -c "am broadcast -n $R --es conn mcp_token --es id demo --es value '…'"     # 令牌经 Keystore 加密保存
 * su -c "am broadcast -n $R --es conn refresh"                                     # 所有连接器 tools/list
 * su -c "am broadcast -n $R --es conn tools"                                       # 交给模型的工具（签名 + 等级）
 * su -c "am broadcast -n $R --es conn call --es sign conn.demo.search --es json '{\"q\":\"x\"}'"   # 只允许 READ 工具
 * su -c "am broadcast -n $R --es conn mcp_remove --es id demo"
 * # Google（B 线②③）：Client ID 只填你自己在 Cloud 控制台建的「桌面应用」客户端
 * su -c "am broadcast -n $R --es conn google_client --es id 'xxxx.apps.googleusercontent.com' --es value 'GOCSPX-…'"
 * su -c "am broadcast -n $R --es conn google_login"      # 打开系统浏览器授权（回调 127.0.0.1 随机端口）
 * su -c "am broadcast -n $R --es conn google_status"
 * su -c "am broadcast -n $R --es conn google_probe"      # 3 s 探测能不能连上 Google（连不上 → 邮件走 IMAP、日历读本机）
 * su -c "am broadcast -n $R --es conn google_logout"     # 吊销并清本地令牌
 * # 邮件（只读 / 草稿；发送只能在面板里经确认卡）
 * su -c "am broadcast -n $R --es conn mail_unread [--es id gmail|imap]"
 * su -c "am broadcast -n $R --es conn mail_drafts"
 * # P3 监控（数据层；定时 / 回调归 A 线，未接入时只能手动跑）
 * su -c "am broadcast -n $R --es conn watch_add --es json '{\"id\":\"w1\",\"query\":\"iPhone 18 发布会\",\"interval_min\":360}'"
 * su -c "am broadcast -n $R --es conn watch_run --es id w1"   # 第一次 = 建基线；之后有新结果才 NOTIFY
 * su -c "am broadcast -n $R --es conn watch_list"
 * su -c "am broadcast -n $R --es conn watch_remove --es id w1"
 * </pre>
 * 有副作用的工具<b>不能</b>从这里直接调：只能在面板 / AgentLoop 里经确认卡（令牌）执行。
 */
final class ConnTestDriver {

    private static final String TAG = "Conn";

    private ConnTestDriver() {}

    static void handle(final Context ctx, Intent intent) {
        final String cmd = intent.getStringExtra("conn");
        final String json = intent.getStringExtra("json");
        final String id = intent.getStringExtra("id");
        final String value = intent.getStringExtra("value");
        final String sign = intent.getStringExtra("sign");
        new Thread(new Runnable() {
            @Override public void run() {
                try {
                    runCmd(ctx, cmd, json, id, value, sign);
                } catch (Throwable t) {
                    Log.w(TAG, "[CONN-TEST] " + cmd + " 异常: " + Redact.t(t));
                }
            }
        }, "conn-test").start();
    }

    private static com.onesay.assistant.v2.conn.mail.MailConnector mail(Context ctx) {
        ConnHooks.registry(ctx);
        return com.onesay.assistant.v2.conn.mail.MailHooks.current();
    }

    private static void runCmd(Context ctx, String cmd, String json, String id, String value, String sign) throws Exception {
        if ("status".equals(cmd)) {
            Log.i(TAG, "[CONN-TEST] status " + ConnHooks.status(ctx) + " settings=" + ConnSettings.dump(ctx));
        } else if ("mcp_add".equals(cmd)) {
            String why = ConnSettings.putMcpServer(ctx, McpServerConfig.fromJson(new JSONObject(json)));
            ConnHooks.reload(ctx);
            Log.i(TAG, "[CONN-TEST] mcp_add " + (why == null ? "OK" : "FAIL " + why));
        } else if ("mcp_token".equals(cmd)) {
            ConnHooks.secrets(ctx).put("mcp." + id + ".token", value);
            ConnHooks.reload(ctx);
            Log.i(TAG, "[CONN-TEST] mcp_token id=" + id + " len=" + (value == null ? 0 : value.length())
                    + " state=" + ConnHooks.secrets(ctx).state("mcp." + id + ".token"));
        } else if ("mcp_remove".equals(cmd)) {
            boolean hit = ConnSettings.removeMcpServer(ctx, id);
            ConnHooks.secrets(ctx).put("mcp." + id + ".token", null);
            ConnHooks.reload(ctx);
            Log.i(TAG, "[CONN-TEST] mcp_remove id=" + id + " hit=" + hit);
        } else if ("refresh".equals(cmd)) {
            for (Connector c : ConnHooks.registry(ctx).all()) {
                Log.i(TAG, "[CONN-TEST] refresh " + c.id() + " → " + c.refreshTools());
            }
        } else if ("tools".equals(cmd)) {
            for (ConnTool t : ConnHooks.registry(ctx).agentTools()) {
                Log.i(TAG, "[CONN-TEST] tool " + t.sign + " effect=" + t.effect);
            }
            Log.i(TAG, "[CONN-TEST] tools total=" + ConnHooks.registry(ctx).agentTools().size());
        } else if ("call".equals(cmd)) {
            ConnTool t = ConnHooks.registry(ctx).findAny(sign);
            if (t == null) {
                Log.i(TAG, "[CONN-TEST] call " + sign + " → 不存在 / 不在白名单");
            } else if (t.effect.hasSideEffect()) {
                Log.i(TAG, "[CONN-TEST] call " + sign + " → 拒绝：有副作用的工具只能经确认卡执行");
            } else {
                ConnResult r = ConnHooks.registry(ctx).get(t.connectorId).call(t.name, json == null ? new JSONObject() : new JSONObject(json));
                Log.i(TAG, "[CONN-TEST] call " + sign + " → " + r.code + " len=" + r.text.length());
            }
        } else if ("google_client".equals(cmd)) {
            String why = ConnSettings.setGoogleClientId(ctx, id);
            if (why == null && value != null && value.length() > 0) {
                ConnHooks.secrets(ctx).put(com.onesay.assistant.v2.conn.google.GoogleAuth.K_CLIENT_SECRET, value);
            }
            ConnHooks.reload(ctx);
            Log.i(TAG, "[CONN-TEST] google_client " + (why == null ? "OK secret=" + (value != null && value.length() > 0 ? "set" : "unchanged") : "FAIL " + why));
        } else if ("google_login".equals(cmd)) {
            com.onesay.assistant.v2.conn.google.GoogleHooks.startBrowserLogin(ctx, new com.onesay.assistant.v2.conn.google.GoogleHooks.Done() {
                @Override public void done(ConnResult r) {
                    Log.i(TAG, "[CONN-TEST] google_login → " + r.code + " " + Redact.s(r.text, 120));
                }
            });
        } else if ("google_status".equals(cmd)) {
            Log.i(TAG, "[CONN-TEST] google_status " + com.onesay.assistant.v2.conn.google.GoogleHooks.auth(ctx).status() + " " + ConnHooks.reach().status());
        } else if ("google_probe".equals(cmd)) {
            Log.i(TAG, "[CONN-TEST] google_probe → " + ConnHooks.reach().probe(ConnHooks.http()) + " " + ConnHooks.reach().status());
        } else if ("google_logout".equals(cmd)) {
            Log.i(TAG, "[CONN-TEST] google_logout → " + com.onesay.assistant.v2.conn.google.GoogleHooks.signOut(ctx).text);
        } else if ("watch_add".equals(cmd)) {
            String why = com.onesay.assistant.v2.conn.watch.WatchHooks.service(ctx).add(com.onesay.assistant.v2.conn.watch.WatchSpec.fromJson(new JSONObject(json)));
            Log.i(TAG, "[CONN-TEST] watch_add " + (why == null ? "OK" : "FAIL " + why));
        } else if ("watch_list".equals(cmd)) {
            for (com.onesay.assistant.v2.conn.watch.WatchSpec w : com.onesay.assistant.v2.conn.watch.WatchHooks.service(ctx).list()) {
                com.onesay.assistant.v2.conn.watch.WatchState st = com.onesay.assistant.v2.conn.watch.WatchHooks.service(ctx).stateOf(w.id);
                Log.i(TAG, "[CONN-TEST] watch " + w.id + " enabled=" + w.enabled + " every=" + w.intervalMin + "min mode=" + w.mode
                        + " baselined=" + (st != null && st.baselined) + " seen=" + (st == null ? 0 : st.seenCount())
                        + " pending=" + (st == null ? 0 : st.pending().size()) + " failures=" + (st == null ? 0 : st.failures));
            }
        } else if ("watch_run".equals(cmd)) {
            com.onesay.assistant.v2.conn.watch.WatchJudge.Verdict v = com.onesay.assistant.v2.conn.watch.WatchHooks.service(ctx).runOne(id);
            Log.i(TAG, "[CONN-TEST] watch_run " + id + " → " + (v == null ? "不存在" : v.outcome + " items=" + v.items.size() + " " + v.reason));
        } else if ("watch_remove".equals(cmd)) {
            Log.i(TAG, "[CONN-TEST] watch_remove " + id + " → " + com.onesay.assistant.v2.conn.watch.WatchHooks.service(ctx).remove(id));
        } else if ("mail_unread".equals(cmd)) {
            com.onesay.assistant.v2.conn.mail.MailConnector m = mail(ctx);
            ConnResult r = m == null ? null : m.unreadImportant(5, id);
            Log.i(TAG, "[CONN-TEST] mail_unread → " + (r == null ? "no mail connector" : r.code + " len=" + r.text.length()));
        } else if ("mail_drafts".equals(cmd)) {
            com.onesay.assistant.v2.conn.mail.MailConnector m = mail(ctx);
            Log.i(TAG, "[CONN-TEST] mail_drafts " + (m == null ? "no mail connector" : m.ledger().all().size() + " 封（台账）"));
        } else {
            Log.i(TAG, "[CONN-TEST] 未知命令 " + cmd + "（status / mcp_add / mcp_token / mcp_remove / refresh / tools / call / google_client / google_login / google_status / google_probe / google_logout / mail_unread / mail_drafts / watch_add / watch_list / watch_run / watch_remove）");
        }
    }
}
