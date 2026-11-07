package com.onesay.assistant.v2.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.assistant.v2.callback.OutboundMail;
import com.onesay.assistant.v2.conn.port.Ports;

/**
 * 发邮件确认卡（lane-a 第 5 项）：显示收件人 / 主题 / 摘要（可展开正文），三个选项 发送 / 修改 / 取消。
 * 「发送」是全仓库唯一的发送入口：卡片每次渲染为当前这一版草稿签发一次性令牌（{@link OutboundMail#cardToken}），
 * 点「发送」时消费令牌调 {@link OutboundMail#confirmSend}。「修改」重新起草后重新渲染、重新签发，旧令牌作废。不导出。
 */
public class MailConfirmActivity extends Activity {

    public static final String EXTRA_SESSION = "mail_session";

    private String mId;
    /** 当前显示的这一版草稿的确认令牌。 */
    private String mToken;
    private LinearLayout mRoot;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        com.onesay.assistant.v2.conn.port.LaneBMailPorts.ensure(this);   // 2026-10-23 A+B 合并：进程只起了这个页面时也接上 B 线适配器
        mId = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_SESSION);
        render();
    }

    private void render() {
        final OutboundMail.Session s = OutboundMail.session(mId);
        if (s == null || s.state != OutboundMail.State.AWAIT_CONFIRM || s.content == null) {
            Toast.makeText(this, "这封邮件已经处理过了", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        mToken = OutboundMail.cardToken(mId, System.currentTimeMillis());
        int pad = dp(20);
        mRoot = new LinearLayout(this);
        mRoot.setOrientation(LinearLayout.VERTICAL);
        mRoot.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1F2420"));
        bg.setCornerRadius(dp(20));
        mRoot.setBackground(bg);
        mRoot.addView(text("确认发送这封邮件？", 20, "#E6F0E0", true));
        mRoot.addView(text("收件人：" + s.recipient.display + (s.recipient.display.equals(s.recipient.address) ? "" : " <" + s.recipient.address + ">"), 14, "#E6F0E0", false));
        mRoot.addView(text("主题：" + s.content.subject, 14, "#E6F0E0", false));
        mRoot.addView(text("摘要：" + s.content.summary, 14, "#B8C4B2", false));
        final TextView body = text(s.content.body, 13, "#8A9686", false);
        body.setVisibility(View.GONE);
        TextView toggle = text("展开正文 ▾", 13, "#C9E3B5", false);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { body.setVisibility(body.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE); }
        });
        mRoot.addView(toggle);
        mRoot.addView(body);
        if (s.error.length() > 0) mRoot.addView(text(s.error, 12, "#F2C48D", false));
        mRoot.addView(text("草稿已存进草稿箱" + (s.revisions > 0 ? "（第 " + (s.revisions + 1) + " 版）" : "") + "。不点「发送」就不会发出。", 12, "#8A9686", false));

        final EditText fb = new EditText(this);
        fb.setHint("修改意见（例如：语气再客气一点，提一下附件）");
        fb.setTextColor(Color.parseColor("#E6F0E0"));
        fb.setHintTextColor(Color.parseColor("#6E7A6A"));
        fb.setVisibility(View.GONE);
        mRoot.addView(fb);

        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        Button cancel = new Button(this);
        cancel.setText("取消");
        cancel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                bg(new Runnable() {
                    @Override public void run() { OutboundMail.cancel(mId, Ports.mailDraft()); }
                }, "已取消，没有发送", true);
            }
        });
        final Button modify = new Button(this);
        modify.setText("修改");
        modify.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (fb.getVisibility() != View.VISIBLE) {
                    fb.setVisibility(View.VISIBLE);
                    modify.setText("重新起草");
                    return;
                }
                final String feedback = fb.getText().toString();
                final android.content.Context app = getApplicationContext();
                modify.setEnabled(false);
                modify.setText("起草中…");
                com.onesay.assistant.v2.util.Threads.start("mail-redraft", new Runnable() {
                    @Override public void run() {
                        OutboundMail.modify(mId, feedback, com.onesay.assistant.v2.callback.OutboundMailHooks.drafterPublic(app), Ports.mailDraft());
                        mMain.post(new Runnable() {
                            @Override public void run() { render(); }
                        });
                    }
                });
            }
        });
        Button send = new Button(this);
        send.setText("发送");
        // r2/fixes 10：防点击劫持——窗口被别的悬浮窗遮住时丢弃触摸，别人诱导不了用户点到「发送」
        send.setFilterTouchesWhenObscured(true);
        send.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final String id = mId;
                final String token = mToken;
                mToken = null;
                final android.content.Context app = getApplicationContext();
                com.onesay.assistant.v2.util.Threads.start("mail-send", new Runnable() {
                    @Override public void run() {
                        final OutboundMail.Session r = OutboundMail.confirmSend(id, token, Ports.mailDraft(), System.currentTimeMillis());
                        mMain.post(new Runnable() {
                            @Override public void run() {
                                Toast.makeText(app, r != null && r.state == OutboundMail.State.SENT ? "已发送" : (r == null ? "会话已失效" : r.error),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                });
                finish();
            }
        });
        row.addView(cancel);
        row.addView(modify);
        row.addView(send);
        mRoot.addView(row);
        ScrollView sv = new ScrollView(this);
        sv.addView(mRoot);
        sv.setFilterTouchesWhenObscured(true);     // r2/fixes 10：整张卡都防遮挡点击
        mRoot.setFilterTouchesWhenObscured(true);
        setContentView(sv);
    }

    private void bg(final Runnable r, final String toast, final boolean close) {
        final android.content.Context app = getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("mail-card", new Runnable() {
            @Override public void run() {
                r.run();
                mMain.post(new Runnable() {
                    @Override public void run() { Toast.makeText(app, toast, Toast.LENGTH_SHORT).show(); }
                });
            }
        });
        if (close) finish();
    }

    private TextView text(String s, int sp, String color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        if (bold) t.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(6), 0, dp(4));
        return t;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
