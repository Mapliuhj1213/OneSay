package com.onesay.assistant.v2.conn.google;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.Log;

import com.onesay.assistant.v2.conn.ConnHooks;
import com.onesay.assistant.v2.conn.ConnResult;
import com.onesay.assistant.v2.conn.ConnSettings;
import com.onesay.assistant.v2.conn.Redact;
import com.onesay.assistant.v2.kernel.ConsoleHost;

/**
 * [conn/google] Google 登录的 Android 装配：进程内单例 {@link GoogleAuth} / {@link GoogleApi}、浏览器登录（loopback + PKCE）、
 * 退出并吊销、控制台行（Client ID 配置 / 登录 / 退出）。日志只打状态，不打令牌、不打邮箱全名。
 */
public final class GoogleHooks {

    private static final String TAG = "Conn";

    private GoogleHooks() {}

    private static volatile GoogleAuth sAuth;
    private static volatile GoogleApi sApi;
    private static volatile boolean sLoginRunning;

    /** 登录等待用户在浏览器里操作的上限。 */
    static final long LOGIN_TIMEOUT_MS = 5 * 60 * 1000L;

    public static GoogleAuth auth(Context ctx) {
        GoogleAuth a = sAuth;
        if (a != null) return a;
        synchronized (GoogleHooks.class) {
            if (sAuth == null) {
                final Context app = ctx.getApplicationContext();
                sAuth = new GoogleAuth(ConnHooks.http(), ConnHooks.secrets(app), new GoogleAuth.Config() {
                    @Override public String clientId() { return ConnSettings.googleClientId(app); }
                });
            }
            return sAuth;
        }
    }

    public static GoogleApi api(Context ctx) {
        GoogleApi a = sApi;
        if (a != null) return a;
        synchronized (GoogleHooks.class) {
            if (sApi == null) sApi = new GoogleApi(ConnHooks.http(), auth(ctx));
            return sApi;
        }
    }

    public interface Done {
        void done(ConnResult r);
    }

    /**
     * 浏览器登录：本机 127.0.0.1 随机端口起回调 → 系统浏览器打开 Google 授权页 → 等回调（5 分钟）→ 换令牌 → 重建注册表。
     * 必须用系统浏览器（Google 禁止在 WebView 里授权）。
     */
    public static void startBrowserLogin(final Context ctx, final Done done) {
        final Context app = ctx.getApplicationContext();
        final GoogleAuth auth = auth(app);
        if (!auth.configured()) {
            if (done != null) done.done(ConnResult.fail(ConnResult.Code.NOT_CONFIGURED, "先填 Google OAuth Client ID"));
            return;
        }
        if (sLoginRunning) {
            if (done != null) done.done(ConnResult.fail(ConnResult.Code.DENIED, "已经有一个登录在进行中，请先在浏览器里完成或等 5 分钟超时"));
            return;
        }
        sLoginRunning = true;
        com.onesay.assistant.v2.util.Threads.start("conn-google-login", new Runnable() {
            @Override public void run() {
                ConnResult r;
                LoopbackReceiver rx = null;
                try {
                    ConnResult pr = ConnHooks.reach().probe(ConnHooks.http());
                    if (!pr.ok()) {
                        sLoginRunning = false;
                        if (done != null) done.done(ConnResult.fail(pr.code, "手机现在连不上 Google，没法登录（需要代理 / VPN）。邮件可先用 IMAP，日历会读本机"));
                        return;
                    }
                    rx = LoopbackReceiver.open();
                    GoogleAuth.Pkce p = GoogleAuth.newPkce();
                    String url = auth.authUrl(rx.redirectUri(), p, GoogleScopes.all());
                    Intent it = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    app.startActivity(it);
                    Log.i(TAG, "[CONN] Google 登录：已打开浏览器，回调端口已就绪");
                    LoopbackReceiver.Result res = rx.await(p.state, LOGIN_TIMEOUT_MS);
                    if (!res.ok()) {
                        r = ConnResult.fail("timeout".equals(res.error) ? ConnResult.Code.TIMEOUT : ConnResult.Code.DENIED,
                                "timeout".equals(res.error) ? "5 分钟内没有完成授权" : "授权没有完成（" + Redact.s(res.error, 40) + "）");
                    } else {
                        r = auth.exchangeCode(res.code, rx.redirectUri(), p);
                    }
                } catch (Throwable t) {
                    r = ConnResult.fail(ConnResult.Code.REMOTE_ERROR, "登录流程异常：" + Redact.t(t));
                } finally {
                    if (rx != null) rx.close();
                    sLoginRunning = false;
                }
                Log.i(TAG, "[CONN] Google 登录结果 " + r.code + " " + auth.status());
                if (r.ok()) ConnHooks.reload(app);
                if (done != null) done.done(r);
            }
        });
    }

    /** 退出并吊销（后台线程里调）。 */
    public static ConnResult signOut(Context ctx) {
        ConnResult r = auth(ctx).revoke();
        ConnHooks.reload(ctx);
        Log.i(TAG, "[CONN] Google 退出 " + r.code);
        return r;
    }

    /** 控制台行：状态 / Client ID / 登录 / 退出。 */
    public static void addConsoleRows(final ConsoleHost host, android.widget.LinearLayout root) {
        final android.app.Activity act = host.activity();
        final GoogleAuth auth = auth(act);
        host.valueRow(root, "Google 账号", auth.status(), auth.signedIn() ? "已登录" : auth.configured() ? "未登录" : "未配置",
                auth.signedIn() ? ConsoleHost.TONE_OK : ConsoleHost.TONE_MUTED);
        host.actionRow(root, "Google OAuth Client ID", auth.configured() ? "已填（" + mask(auth.clientId()) + "）" : "自己在 Google Cloud 建「桌面应用」客户端后填入",
                "填写", new Runnable() {
                    @Override public void run() {
                        clientIdDialog(host);
                    }
                });
        final com.onesay.assistant.v2.conn.GoogleReach reach = ConnHooks.reach();
        host.actionRow(root, "Google 连通性", reach.down() ? reach.notice() + "；期间邮件走 IMAP、日历读本机" : "最近一次访问正常（或还没访问过）",
                "检测", new Runnable() {
                    @Override public void run() {
                        com.onesay.assistant.v2.util.Threads.start("conn-google-probe", new Runnable() {
                            @Override public void run() {
                                final ConnResult r = reach.probe(ConnHooks.http());
                                host.ui(new Runnable() {
                                    @Override public void run() {
                                        host.toast(r.ok() ? "能连上 Google" : "连不上 Google：邮件会用 IMAP、日历读本机；需要代理 / VPN 才能用 Gmail / Google 日历");
                                    }
                                });
                            }
                        });
                    }
                });
        if (!auth.signedIn()) {
            host.actionRow(root, "登录 Google（浏览器）", "授权 Gmail 读取 / 草稿、日历事件、Tasks；需要手机能连上 Google", "登录", new Runnable() {
                @Override public void run() {
                    startBrowserLogin(act, new Done() {
                        @Override public void done(final ConnResult r) {
                            host.ui(new Runnable() {
                                @Override public void run() {
                                    host.toast(r.ok() ? "Google 登录成功" : "Google 登录失败：" + r.text);
                                }
                            });
                        }
                    });
                }
            });
        } else {
            host.actionRow(root, "退出 Google 并吊销授权", "清除本机令牌，并请求 Google 撤销本应用的授权", "退出", new Runnable() {
                @Override public void run() {
                    host.confirm("退出 Google", "会清除本机令牌并请求 Google 撤销授权，之后需要重新登录。", "退出", new Runnable() {
                        @Override public void run() {
                            com.onesay.assistant.v2.util.Threads.start("conn-google-logout", new Runnable() {
                                @Override public void run() {
                                    final ConnResult r = signOut(act);
                                    host.ui(new Runnable() {
                                        @Override public void run() {
                                            host.toast(r.text);
                                        }
                                    });
                                }
                            });
                        }
                    });
                }
            });
        }
    }

    static String mask(String id) {
        if (id == null || id.length() < 12) return "***";
        return id.substring(0, 6) + "…" + id.substring(id.length() - 6);
    }

    private static void clientIdDialog(final ConsoleHost host) {
        final android.app.Activity act = host.activity();
        final android.widget.LinearLayout box = new android.widget.LinearLayout(act);
        box.setOrientation(android.widget.LinearLayout.VERTICAL);
        int pad = (int) (16 * act.getResources().getDisplayMetrics().density);
        box.setPadding(pad, pad / 2, pad, 0);
        final android.widget.EditText id = new android.widget.EditText(act);
        id.setHint("xxxx.apps.googleusercontent.com");
        id.setSingleLine(true);
        id.setText(ConnSettings.googleClientId(act));
        final android.widget.EditText secret = new android.widget.EditText(act);
        secret.setHint("Client Secret（桌面应用会给一个，可留空不改）");
        secret.setSingleLine(true);
        secret.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD);
        box.addView(id);
        box.addView(secret);
        com.onesay.assistant.v2.ui.console.KeyMask.secure(new android.app.AlertDialog.Builder(act)
                .setTitle("Google OAuth 客户端")
                .setView(box)
                .setPositiveButton("保存", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        String why = ConnSettings.setGoogleClientId(act, id.getText().toString());
                        if (why != null) {
                            host.toast(why);
                            return;
                        }
                        String s = secret.getText().toString().trim();
                        if (s.length() > 0) ConnHooks.secrets(act).put(GoogleAuth.K_CLIENT_SECRET, s);
                        secret.setText("");
                        com.onesay.assistant.v2.ui.console.KeyMask.clearClipIf(act, s);
                        ConnHooks.reload(act);
                        host.toast("已保存（Secret " + (s.length() > 0 ? "已加密保存" : "未改") + "）");
                    }
                })
                .setNegativeButton("取消", null)
                .show());
    }
}
