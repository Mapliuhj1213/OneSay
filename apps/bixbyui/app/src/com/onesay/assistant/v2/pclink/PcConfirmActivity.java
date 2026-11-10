package com.onesay.assistant.v2.pclink;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * [approve-not-block] 手机屏幕上的确认卡（规则全在纯 Java 的 {@link PcConfirm} 里，这里只画卡、把触摸交进去）。不导出。
 *
 * <ul>
 *   <li>「允许」只认真人手指：{@link HumanButton} 只在手指按下又在按钮里抬起时报 {@link PcConfirm.Input#TOUCH}；
 *       被别的窗口遮住（FLAG_WINDOW_IS_OBSCURED / PARTIALLY_OBSCURED）报 OBSCURED，带无障碍注入标记的报 ACCESSIBILITY，
 *       无障碍的 ACTION_CLICK / performClick 一律不算（机器人用无障碍点不了）；OneSay 自己的机器人刚注入过手势也不算（{@link PcConfirm#noteRobotInput}）。</li>
 *   <li>窗口 FLAG_SECURE：截屏 / 录屏看不到这张卡（机器人靠截图看屏幕，也看不到）。</li>
 *   <li>显示后 0.7 秒才解锁「允许」，焦点默认在「拒绝」；60 秒没人点、返回键、关掉页面 = 拒绝。</li>
 * </ul>
 */
public class PcConfirmActivity extends Activity {

    static final String EXTRA_ID = "pc_confirm_id", ACTION_CLOSE = "com.onesay.assistant.v2.pclink.CONFIRM_CLOSE";
    static final int NID_CONFIRM = 47870;
    /** 无障碍注入的触摸带的标记（AOSP MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT，隐藏常量）。 */
    static final int FLAG_IS_ACCESSIBILITY_EVENT = 0x800;
    static final int FLAG_WINDOW_IS_PARTIALLY_OBSCURED = 0x2;

    private int mId;
    private boolean mAnswered;
    private Button mAllow;
    private CheckBox mRemember;
    private TextView mHint;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private BroadcastReceiver mClose;

    /** 进程里装上确认卡界面（PcRxAndroid 起来时调用；装过就不再装）。 */
    public static synchronized void install(final Context c) {
        if (PcConfirm.ui != null) return;
        final Context app = c.getApplicationContext();
        PcConfirm.ui = new PcConfirm.Ui() {
            @Override public void open(PcConfirm.Card card) {
                Intent i = new Intent(app, PcConfirmActivity.class).putExtra(EXTRA_ID, card.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION);
                // 后台拉不起页面时（Android 10+ 后台启动限制）靠这条通知：点一下打开确认卡
                try {
                    PendingIntent pi = PendingIntent.getActivity(app, card.id, i, PendingIntent.FLAG_UPDATE_CURRENT | (android.os.Build.VERSION.SDK_INT >= 23 ? 0x04000000 : 0));
                    Notification n = PcRxAndroid.builder(app).setSmallIcon(app.getApplicationInfo().icon).setContentTitle(card.title)
                            .setContentText(card.detail).setStyle(new Notification.BigTextStyle().bigText(card.detail + "\n点开确认（60 秒不点 = 拒绝）"))
                            .setContentIntent(pi).setFullScreenIntent(pi, true).setAutoCancel(true).setCategory(Notification.CATEGORY_CALL).build();
                    PcRxAndroid.channel(app).notify(NID_CONFIRM + card.id % 100, n);
                } catch (Throwable t) {
                    Log.w(PcRxAndroid.TAG, "[PCLINK] 确认卡通知: " + t.getClass().getSimpleName());
                }
                try {
                    app.startActivity(i);
                } catch (Throwable t) {
                    Log.w(PcRxAndroid.TAG, "[PCLINK] 确认卡页面: " + t.getClass().getSimpleName());
                }
            }

            @Override public void close(PcConfirm.Card card) {
                try {
                    PcRxAndroid.channel(app).cancel(NID_CONFIRM + card.id % 100);
                } catch (Throwable ignored) {
                }
                app.sendBroadcast(new Intent(ACTION_CLOSE).setPackage(app.getPackageName()).putExtra(EXTRA_ID, card.id));
            }
        };
    }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        mId = getIntent() == null ? 0 : getIntent().getIntExtra(EXTRA_ID, 0);
        PcConfirm.Card c = PcConfirm.card(mId);
        if (c == null) {
            mAnswered = true;
            finish();
            return;
        }
        mClose = new BroadcastReceiver() {
            @Override public void onReceive(Context ctx, Intent i) {
                if (i.getIntExtra(EXTRA_ID, -1) == mId) {
                    mAnswered = true;
                    finish();
                }
            }
        };
        registerReceiver(mClose, new IntentFilter(ACTION_CLOSE));
        render(c);
        tick();
    }

    private void render(PcConfirm.Card c) {
        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Color.parseColor("#1F2420"));
        bg.setCornerRadius(dp(20));
        root.setBackground(bg);
        root.addView(text(c.title, 20, "#E6F0E0", true));
        root.addView(text(c.detail, 15, "#E6F0E0", false));
        if (PcConfirm.KIND_PC.equals(c.kind)) root.addView(text("这条请求在电脑上也能批；在这里点也算数。", 12, "#B8C4B2", false));
        if (c.rememberKind.length() > 0) {
            mRemember = new CheckBox(this);
            mRemember.setText("以后这类不用问（" + c.rememberZh + "，可在控制台撤销）");
            mRemember.setTextColor(Color.parseColor("#B8C4B2"));
            root.addView(mRemember);
        }
        mHint = text("「允许」稍后可点（防误点）。只认手指点击，说话不算；60 秒不点、按返回都算拒绝。", 12, "#8A9686", false);
        root.addView(mHint);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.END);
        Button deny = new Button(this);
        deny.setText("拒绝");
        deny.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { done(false, PcConfirm.Input.UNKNOWN); } // 拒绝从哪来都收
        });
        mAllow = new HumanButton(this);
        mAllow.setText(c.always ? "允许" : "允许一次");
        mAllow.setEnabled(false);
        row.addView(deny);
        row.addView(mAllow);
        root.addView(row);
        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        sv.setFilterTouchesWhenObscured(true);
        root.setFilterTouchesWhenObscured(true);
        setContentView(sv);
        deny.requestFocus(); // 焦点默认在「拒绝」
    }

    @Override protected void onResume() {
        super.onResume();
        PcConfirm.shown(mId, hasWindowFocus());
    }

    @Override public void onWindowFocusChanged(boolean f) {
        super.onWindowFocusChanged(f);
        PcConfirm.shown(mId, f); // 第一次 = 显示出来；之后 = 焦点变化（拿回焦点重新等 0.7 秒）
        PcConfirm.focus(mId, f);
        if (!f && mAllow != null) mAllow.setEnabled(false);
    }

    private void tick() {
        mMain.postDelayed(new Runnable() {
            @Override public void run() {
                if (isFinishing()) return;
                PcConfirm.expire();
                PcConfirm.Card c = PcConfirm.card(mId);
                if (c == null) {
                    mAnswered = true;
                    finish();
                    return;
                }
                if (mAllow != null) mAllow.setEnabled(c.armed(System.currentTimeMillis()));
                tick();
            }
        }, 200);
    }

    void done(boolean allow, PcConfirm.Input in) {
        boolean remember = allow && mRemember != null && mRemember.isChecked();
        PcConfirm.Answer a = PcConfirm.answer(mId, allow, false, remember, in);
        if (a == PcConfirm.Answer.ACCEPTED || a == PcConfirm.Answer.GONE) {
            mAnswered = true;
            finish();
        } else if (mHint != null) {
            mHint.setText(a == PcConfirm.Answer.NOT_HUMAN ? "这次点击不算（只认手指直接点，被遮住 / 代点的不算），请再点一次" : "「允许」还没解锁，稍等再点");
        }
    }

    @Override public void onBackPressed() {
        done(false, PcConfirm.Input.UNKNOWN);
        super.onBackPressed();
    }

    @Override protected void onDestroy() {
        if (mClose != null) try { unregisterReceiver(mClose); } catch (Throwable ignored) { }
        if (!mAnswered && isFinishing()) PcConfirm.dismissed(mId); // 页面被关掉 = 拒绝
        super.onDestroy();
    }

    /** 只认真人手指的按钮：无障碍代点 / performClick 不算，遮挡下的触摸不算。 */
    final class HumanButton extends Button {
        private boolean down;

        HumanButton(Context c) {
            super(c);
            setFilterTouchesWhenObscured(true);
            setAccessibilityDelegate(new View.AccessibilityDelegate() {
                @Override public boolean performAccessibilityAction(View host, int action, Bundle args) {
                    if (action == AccessibilityNodeInfo.ACTION_CLICK || action == AccessibilityNodeInfo.ACTION_LONG_CLICK) return false; // 无障碍点不了
                    return super.performAccessibilityAction(host, action, args);
                }
            });
        }

        @Override public boolean performClick() {
            return false; // 只认 onTouchEvent 里的真手指
        }

        @Override public boolean onTouchEvent(MotionEvent ev) {
            if (!isEnabled()) return true;
            int f = ev.getFlags();
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    down = true;
                    setPressed(true);
                    return true;
                case MotionEvent.ACTION_UP: {
                    boolean inside = ev.getX() >= 0 && ev.getY() >= 0 && ev.getX() <= getWidth() && ev.getY() <= getHeight();
                    setPressed(false);
                    if (!down || !inside) return true;
                    down = false;
                    PcConfirm.Input in;
                    if ((f & (MotionEvent.FLAG_WINDOW_IS_OBSCURED | FLAG_WINDOW_IS_PARTIALLY_OBSCURED)) != 0) in = PcConfirm.Input.OBSCURED;
                    else if ((f & FLAG_IS_ACCESSIBILITY_EVENT) != 0) in = PcConfirm.Input.ACCESSIBILITY;
                    else if (ev.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER && ev.getToolType(0) != MotionEvent.TOOL_TYPE_STYLUS) in = PcConfirm.Input.UNKNOWN;
                    else in = PcConfirm.Input.TOUCH;
                    done(true, in);
                    return true;
                }
                case MotionEvent.ACTION_CANCEL:
                    down = false;
                    setPressed(false);
                    return true;
                default:
                    return true;
            }
        }
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
