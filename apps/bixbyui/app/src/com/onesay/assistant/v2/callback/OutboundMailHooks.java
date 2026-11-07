package com.onesay.assistant.v2.callback;

import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.provider.ContactsContract;
import android.util.Log;

import com.onesay.assistant.v2.conn.port.Ports;

import java.util.ArrayList;
import java.util.List;

/**
 * 对外发邮件的 Android 胶水：大模型、通讯录 / 往来邮件、草稿接口（B 线，当前是桩）、确认卡。
 * 入口三个，全部汇到 {@link #begin}：语音口令（CallbackPlugin.l0）、AgentLoop 想调发送类工具（CallbackPlugin.agentPrecheck 拦下）、
 * 定时任务里的发送步骤（S3Runner 转过来）。发送只在确认卡上点「发送」时发生。
 */
public final class OutboundMailHooks {

    private OutboundMailHooks() {}

    private static final String TAG = "OutboundMail";

    /** 当前线程在跑的无人值守任务：{来源, 用户原话}（S3Runner 设置；AgentLoop 拦下发送时用来定来源 / 核实收件人）。 */
    public static final ThreadLocal<String[]> UNATTENDED = new ThreadLocal<String[]>();

    static OutboundMail.Drafter drafter(final Context app) {
        return new OutboundMail.Drafter() {
            @Override public String complete(String system, String user) throws Exception {
                com.onesay.assistant.v2.router.L2LLMCompiler l2 = new com.onesay.assistant.v2.router.L2LLMCompiler(app);
                if (!l2.hasApiKey()) throw new IllegalStateException("还没配大模型密钥");
                String body = com.onesay.assistant.v2.net.LlmClient.body(com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT, 0.4, 700,
                        com.onesay.assistant.v2.net.LlmClient.messages(system, user), null, false).toString();
                com.onesay.assistant.v2.net.LlmResponse r = l2.client().withReadTimeout(25000).post(body);
                if (!r.ok()) throw new IllegalStateException(r.error);
                return r.content();
            }
        };
    }

    public static OutboundMail.Drafter drafterPublic(Context app) {
        return drafter(app);
    }

    static List<OutboundMail.Contact> contacts(Context app) {
        List<OutboundMail.Contact> l = new ArrayList<OutboundMail.Contact>();
        Cursor c = null;
        try {
            c = app.getContentResolver().query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[]{ContactsContract.Contacts.DISPLAY_NAME, ContactsContract.CommonDataKinds.Email.ADDRESS}, null, null, null);
            while (c != null && c.moveToNext() && l.size() < 2000) l.add(new OutboundMail.Contact(c.getString(0), c.getString(1)));
        } catch (Throwable t) {
            Log.w(TAG, "[MAIL-OUT] 读通讯录失败: " + t);
        } finally {
            if (c != null) c.close();
        }
        return l;
    }

    static List<OutboundMail.Contact> correspondents() {
        List<OutboundMail.Contact> l = new ArrayList<OutboundMail.Contact>();
        try {
            for (String[] p : Ports.mailRead().correspondents(300)) {
                if (p != null && p.length >= 2) l.add(new OutboundMail.Contact(p[0], p[1]));
            }
        } catch (Throwable t) {
            Log.w(TAG, "[MAIL-OUT] 读往来邮件失败: " + t);
        }
        return l;
    }

    static String self(Context app) {
        try {
            String u = com.onesay.assistant.v2.executor.MailBridge.user(app);
            return u != null && OutboundMail.EMAIL.matcher(u).matches() ? u : null;
        } catch (Throwable t) {
            return null;
        }
    }

    /** 2026-10-23 A+B 合并：没配置邮箱时不起草、不存草稿、不弹卡——不出现任何发送路径。 */
    public static final String NO_MAILBOX = "还没配置邮箱，发不了邮件。先在控制台「连接器」里登录 Google（Gmail）或填好「邮箱（IMAP）配置」。";

    public static boolean mailReady(Context ctx) {
        com.onesay.assistant.v2.conn.port.LaneBMailPorts.ensure(ctx);
        return Ports.mailDraft().available();
    }

    /** 后台起草 → 存草稿 → 弹确认卡；失败上岛 + 通知说明。origin = voice / agent / scheduled。 */
    public static void begin(Context ctx, final String utterance, final String who, final String intent, final String origin) {
        final Context app = ctx.getApplicationContext();
        if (!mailReady(app)) {
            Log.i(TAG, "[MAIL-OUT] " + origin + " 邮箱未配置 → 不起草、不弹卡");
            return;
        }
        com.onesay.assistant.v2.util.Threads.start("mail-out", new Runnable() {
            @Override public void run() {
                OutboundMail.Recipient r = OutboundMail.verifyRecipient(utterance, who, contacts(app), correspondents(), self(app));
                OutboundMail.Session s = OutboundMail.start(intent, r, origin, drafter(app), Ports.mailDraft(), System.currentTimeMillis());
                Log.i(TAG, "[MAIL-OUT] " + origin + " 收件人=" + r.status + " 会话=" + s.state + (s.error.length() > 0 ? " " + s.error : ""));
                if (s.state == OutboundMail.State.AWAIT_CONFIRM) {
                    if (OutboundMail.popCardNow(origin)) {
                        show(app, s.id);
                    } else {
                        // 定时任务 / 队列：只存草稿，不发。通知里点开确认卡，用户在卡上点「发送」才会发。
                        // ★：岛上一条「草稿待确认」提醒（立即查看 = 打开确认卡；发送仍只在卡上）。
                        // r2/fixes 7：提醒（或邮件来源）关着 = 不上岛，不再退回旧完成卡；下面的通知是打开确认卡的唯一入口，保留
                        boolean onIsland = com.onesay.assistant.v2.notice.NoticeHooks.postDraftPending(app, s.id, s.recipient.display, s.content.subject);
                        if (!onIsland) Log.i(TAG, "[MAIL-OUT] 提醒关着：草稿待确认不上岛，只发通知");
                        notifyCard(app, s);
                    }
                } else {
                    // r2/fixes 7：起草失败的岛提示也走 NoticeCenter（回调来源；去重 / 免打扰 / 每日上限），关着就不上岛
                    com.onesay.assistant.v2.notice.NoticeHooks.postCallback(app, "mail.out." + s.id, "邮件没起草",
                            CallbackHub.shortText(s.error, 120), com.onesay.assistant.v2.callback.DeliveryPolicy.Urgency.NORMAL);
                    CallbackHub.notify(app, s.id.hashCode(), "邮件没起草", s.error);
                }
            }
        });
    }

    /** 「草稿待确认」通知，点开 = 确认卡。 */
    static void notifyCard(Context app, OutboundMail.Session s) {
        try {
            Intent it = new Intent(app, com.onesay.assistant.v2.ui.MailConfirmActivity.class);
            it.putExtra(com.onesay.assistant.v2.ui.MailConfirmActivity.EXTRA_SESSION, s.id);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            android.app.PendingIntent pi = android.app.PendingIntent.getActivity(app, s.id.hashCode(), it,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 23 ? android.app.PendingIntent.FLAG_IMMUTABLE : 0));
            CallbackHub.notify(app, s.id.hashCode(), "邮件草稿待确认", "给" + s.recipient.display + "：" + s.content.subject + "（点开确认，不确认不会发）", pi);
        } catch (Throwable t) {
            Log.w(TAG, "[MAIL-OUT] 草稿通知失败: " + t);
        }
    }

    public static void show(Context app, String sessionId) {
        try {
            Intent it = new Intent(app, com.onesay.assistant.v2.ui.MailConfirmActivity.class);
            it.putExtra(com.onesay.assistant.v2.ui.MailConfirmActivity.EXTRA_SESSION, sessionId);
            it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            app.startActivity(it);
        } catch (Throwable t) {
            Log.w(TAG, "[MAIL-OUT] 弹确认卡失败: " + t);
        }
    }

    /** AgentLoop 里的发送类工具：转成「起草 + 确认卡」，返回给模型的观测。 */
    /**
     * @param userWords 用户自己的原话（定时任务 = 创建时的指令）；模型给的 to 不算「用户明确说出」，只能靠通讯录 / 往来邮件核实。
     */
    public static String divertAgentSend(Context ctx, org.json.JSONObject args, String origin, String userWords) {
        String to = args == null ? "" : args.optString("to", "");
        String subject = args == null ? "" : args.optString("subject", "");
        String body = args == null ? "" : args.optString("body", args == null ? "" : args.optString("content", ""));
        String intent = (subject + "：" + body).trim();
        if (!mailReady(ctx)) {
            Log.i(TAG, "[MAIL-OUT] " + origin + " 邮箱未配置 → 发送类工具直接拒绝");
            return NO_MAILBOX + "这一步不能完成，不要重试，也不要尝试别的发送方式。";
        }
        begin(ctx, userWords == null ? "" : userWords, to, intent, origin);
        return "按规则，发邮件不能直接发送：已改为先起草草稿并弹确认卡，用户在卡上点「发送」才会发出。这一步已经处理，不要重试，也不要尝试别的发送方式。";
    }
}
