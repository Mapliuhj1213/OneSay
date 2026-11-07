package com.onesay.assistant.v2.ui.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * ★ ui-K1 对话窗底栏（悬浮圆角）：左 圈选识屏；中 输入框（左 Bixby 彩色标、占位「有问题尽管问我…」、右「+」与麦克风，
 * 录音时占位「聆听中…」、麦克风换成跳动的紫色波点）；右 通话（= 相机对话）。「+」展开 拍照 / 从相册选图，选完缩略图附在输入框上方。
 */
public final class ChatBottomBar extends LinearLayout {

    public interface Listener {
        void onCircleSelect();
        void onCall();
        void onMic();
        void onPickImage(boolean camera);
        void onSubmit(String text);
        void onInputFocus(boolean focused);
        void onAttachmentRemoved();
    }

    private static final int FILL = 0xFF222224;
    private static final int OUTER = 0xFF151517;
    private static final int GLYPH = 0xFFEDEBE7;
    private static final int HINT = 0xFF8A8885;

    private final LinearLayout mMenu;
    private final FrameLayout mAttach;
    private final ImageView mAttachThumb;
    private final EditText mEdit;
    private final ImageView mPlus;
    private final ImageView mMic;
    private final WaveDots mDots;
    private ChatIcons mMicIcon, mSendIcon;
    private com.onesay.assistant.v2.ui.widget.SendMicPolicy.Mode mSendMic = com.onesay.assistant.v2.ui.widget.SendMicPolicy.Mode.MIC;
    private Listener mListener;
    private boolean mListening;
    /** 用户真的要打字（按下了输入框 / 宿主明确要打字）。被动获焦不算。 */
    private boolean mTypingIntent;

    public ChatBottomBar(Context c) {
        super(c);
        setOrientation(VERTICAL);
        setClipChildren(false);

        // 「+」菜单：拍照 / 从相册选图（行内展开，不用 PopupWindow —— 悬浮窗上更稳）
        mMenu = new LinearLayout(c);
        mMenu.setOrientation(HORIZONTAL);
        mMenu.setVisibility(GONE);
        mMenu.addView(menuPill(c, ChatIcons.CAMERA, "拍照", true), pillLp(0));
        mMenu.addView(menuPill(c, ChatIcons.GALLERY, "从相册选图", false), pillLp(dp(10)));
        LayoutParams mlp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mlp.leftMargin = dp(70);
        mlp.bottomMargin = dp(8);
        addView(mMenu, mlp);

        // 附图缩略图（发送时随问题一起走识图）
        mAttach = new FrameLayout(c);
        mAttach.setVisibility(GONE);
        mAttachThumb = new ImageView(c);
        mAttachThumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
        GradientDrawable tb = new GradientDrawable();
        tb.setCornerRadius(dp(12));
        tb.setColor(FILL);
        mAttachThumb.setBackground(tb);
        mAttachThumb.setClipToOutline(true);
        mAttach.addView(mAttachThumb, new FrameLayout.LayoutParams(dp(64), dp(64)));
        ImageView x = new ImageView(c);
        x.setImageDrawable(new ChatIcons(ChatIcons.CLOSE, Color.WHITE));
        GradientDrawable xb = new GradientDrawable();
        xb.setShape(GradientDrawable.OVAL);
        xb.setColor(0xCC000000);
        x.setBackground(xb);
        x.setPadding(dp(3), dp(3), dp(3), dp(3));
        x.setContentDescription("移除图片");
        FrameLayout.LayoutParams xlp = new FrameLayout.LayoutParams(dp(22), dp(22), Gravity.TOP | Gravity.START);
        xlp.leftMargin = dp(48);
        xlp.topMargin = dp(-6);
        mAttach.addView(x, xlp);
        x.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                clearAttachment();
                if (mListener != null) mListener.onAttachmentRemoved();
            }
        });
        mAttach.setClipChildren(false);
        LayoutParams alp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.leftMargin = dp(76);
        alp.bottomMargin = dp(8);
        alp.topMargin = dp(6);
        addView(mAttach, alp);

        // 外层悬浮圆角条
        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable ob = new GradientDrawable();
        ob.setCornerRadius(dp(34));
        ob.setColor(OUTER);
        bar.setBackground(ob);

        ImageView circle = roundButton(c, ChatIcons.CIRCLE, "圈选识屏");
        circle.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { if (mListener != null) mListener.onCircleSelect(); }
        });
        bar.addView(circle, new LayoutParams(dp(52), dp(52)));

        LinearLayout pill = new LinearLayout(c);
        pill.setOrientation(HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), 0, dp(8), 0);
        GradientDrawable pb = new GradientDrawable();
        pb.setCornerRadius(dp(26));
        pb.setColor(FILL);
        pill.setBackground(pb);
        ImageView logo = new ImageView(c);
        logo.setImageDrawable(new ChatIcons(ChatIcons.BIXBY, GLYPH));
        pill.addView(logo, new LayoutParams(dp(24), dp(24)));
        mEdit = new EditText(c);
        mEdit.setBackground(null);
        mEdit.setSingleLine(true);
        mEdit.setTextColor(GLYPH);
        mEdit.setHintTextColor(HINT);
        mEdit.setHint(ChatWindowPolicy.HINT_IDLE);
        mEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        mEdit.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mEdit.setPadding(dp(8), 0, dp(4), 0);
        mEdit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                // 只认键盘「发送」（IME_ACTION_SEND）：完成 / 回车 / 收键盘一律不提交（用户：收起键盘它就自动发送了）
                if (ChatWindowPolicy.isSendAction(actionId)) {
                    submit();
                    return true;
                }
                return false;
            }
        });
        mEdit.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override public void onFocusChange(View v, boolean hasFocus) {
                if (!hasFocus) {
                    mTypingIntent = false;
                    if (mListener != null) mListener.onInputFocus(false);
                    return;
                }
                // 换排布 / 别的输入框被藏 / 窗口变可获焦时系统把焦点顺手交给这里 —— 不是用户点的，
                //   不进打字态（以前会停掉刚开的收音 + 弹键盘）。焦点不清：clearFocus 后系统会立刻把焦点再交回第一个可获焦的
                //   EditText（真机 02:00:26 每 20–90 ms 一次的循环）；留着也不弹键盘，用户之后点它走 ACTION_UP 分支进打字态。
                if (!ChatWindowPolicy.typingOnFocus(true, mTypingIntent)) {
                    android.util.Log.i("ChatWindow", "[CHAT-IME] 输入框被动获焦（不是用户点的）→ 忽略，不进打字态、不弹键盘");
                    return;
                }
                mMenu.setVisibility(GONE);
                if (mListener != null) mListener.onInputFocus(true);
            }
        });
        // 卡片窗默认不可获焦：点输入框时先让服务放开焦点、再弹键盘（不等系统焦点变化）。按下就记「用户要打字」，焦点随后到来时据此放行。
        mEdit.setOnTouchListener(new OnTouchListener() {
            @Override public boolean onTouch(View v, android.view.MotionEvent e) {
                int a = e.getActionMasked();
                if (a == android.view.MotionEvent.ACTION_DOWN) mTypingIntent = true;
                if (a == android.view.MotionEvent.ACTION_UP && mListener != null && mEdit.hasFocus()) mListener.onInputFocus(true);   // 已聚焦再点 = 重新弹键盘
                return false;
            }
        });
        pill.addView(mEdit, new LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        mPlus = iconButton(c, ChatIcons.PLUS, "添加图片");
        mPlus.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                mMenu.setVisibility(mMenu.getVisibility() == VISIBLE ? GONE : VISIBLE);
            }
        });
        pill.addView(mPlus, new LayoutParams(dp(38), dp(38)));
        FrameLayout micSlot = new FrameLayout(c);
        mMic = iconButton(c, ChatIcons.MIC, com.onesay.assistant.v2.ui.widget.SendMicPolicy.DESC_MIC);
        mMicIcon = (ChatIcons) mMic.getDrawable();
        mSendIcon = new ChatIcons(ChatIcons.SEND, GLYPH).setCutColor(OUTER);
        mMic.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                // 有字 / 附图 = 发送（键盘上的发送键也可以），空 = 麦克风
                if (com.onesay.assistant.v2.ui.widget.SendMicPolicy.onTap(mEdit.getText(), hasAttachment(), false)
                        == com.onesay.assistant.v2.ui.widget.SendMicPolicy.Mode.SEND) {
                    submit();
                    return;
                }
                mTypingIntent = false;   // 点麦克风 = 只录音：之后任何被动获焦都不弹键盘
                if (mListener != null) mListener.onMic();
            }
        });
        // 有字时右侧实时变圆形「发送」箭头、清空变回麦克风（与卡片面板输入条同一口径）
        mEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(android.text.Editable e) { refreshSendMic(); }
        });
        micSlot.addView(mMic, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        mDots = new WaveDots(c);
        mDots.setVisibility(GONE);
        mDots.setContentDescription("聆听中，点按说完了");
        mDots.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { if (mListener != null) mListener.onMic(); }
        });
        micSlot.addView(mDots, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        pill.addView(micSlot, new LayoutParams(dp(40), dp(40)));
        LayoutParams plp = new LayoutParams(0, dp(52), 1f);
        plp.leftMargin = dp(8);
        plp.rightMargin = dp(8);
        bar.addView(pill, plp);

        ImageView call = roundButton(c, ChatIcons.CALL, "相机对话");
        call.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { if (mListener != null) mListener.onCall(); }
        });
        bar.addView(call, new LayoutParams(dp(52), dp(52)));
        addView(bar, new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    public EditText edit() {
        return mEdit;
    }

    /** 宿主明确要打字（⌨ / 铅笔改问句 / 结果态点输入框后展开）：放行下一次获焦。 */
    public void focusForTyping() {
        mTypingIntent = true;
        mMenu.setVisibility(GONE);
        mEdit.requestFocus();
    }

    /** 用户要打字的意图还在（测试 / 宿主判断用）。 */
    public boolean typingIntent() {
        return mTypingIntent;
    }

    /** 真在打字：输入框有焦点<b>且</b>是用户要打字（被动获焦不算——不让位、不拉满卡片、不算打字态）。 */
    public boolean typingFocus() {
        return mTypingIntent && mEdit.hasFocus();
    }

    /** 录音中：占位「聆听中…」、麦克风换成跳动紫色波点（instant = 系统关闭动画 → 波点不跳）。 */
    public void setListening(boolean on, boolean instant) {
        if (mListening == on && mDots.isInstant() == instant) return;
        mListening = on;
        mEdit.setHint(on ? ChatWindowPolicy.HINT_LISTENING : ChatWindowPolicy.HINT_IDLE);
        mMic.setVisibility(on ? GONE : VISIBLE);
        mDots.setVisibility(on ? VISIBLE : GONE);
        mDots.setRunning(on, instant);
    }

    public void setText(String s) {
        mEdit.setText(s == null ? "" : s);
        mEdit.setSelection(mEdit.getText().length());
    }

    public boolean hasText() {
        return mEdit.getText() != null && mEdit.getText().toString().trim().length() > 0;
    }

    public void setAttachment(Bitmap thumb) {
        if (thumb == null) {
            clearAttachment();
            return;
        }
        mAttachThumb.setImageBitmap(thumb);
        mAttach.setVisibility(VISIBLE);
        mMenu.setVisibility(GONE);
        refreshSendMic();
    }

    public void clearAttachment() {
        mAttachThumb.setImageDrawable(null);
        mAttach.setVisibility(GONE);
        refreshSendMic();
    }

    /** 右侧按钮：有字 / 附图 → 圆形发送箭头，空 → 麦克风（只在模式变化时换图，不每个字都重设）。 */
    void refreshSendMic() {
        if (mMic == null || mSendIcon == null) return;
        com.onesay.assistant.v2.ui.widget.SendMicPolicy.Mode m = com.onesay.assistant.v2.ui.widget.SendMicPolicy.mode(mEdit.getText(), hasAttachment(), false);
        if (m == mSendMic) return;
        mSendMic = m;
        boolean send = m == com.onesay.assistant.v2.ui.widget.SendMicPolicy.Mode.SEND;
        mMic.setImageDrawable(send ? mSendIcon : mMicIcon);
        mMic.setPadding(send ? dp(6) : dp(8), send ? dp(6) : dp(8), send ? dp(6) : dp(8), send ? dp(6) : dp(8));
        mMic.setContentDescription(com.onesay.assistant.v2.ui.widget.SendMicPolicy.desc(m));
    }

    /** 测试 / 自检用：右侧按钮当前模式。 */
    public com.onesay.assistant.v2.ui.widget.SendMicPolicy.Mode sendMicMode() {
        return mSendMic;
    }

    public boolean hasAttachment() {
        return mAttach.getVisibility() == VISIBLE;
    }

    public void hideMenu() {
        mMenu.setVisibility(GONE);
    }

    private void submit() {
        String t = mEdit.getText() == null ? "" : mEdit.getText().toString().trim();
        if (t.length() == 0 && !hasAttachment()) return;
        mEdit.setText("");
        mMenu.setVisibility(GONE);
        if (mListener != null) mListener.onSubmit(t);
    }

    private View menuPill(Context c, int icon, String label, final boolean camera) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(dp(12), dp(8), dp(14), dp(8));
        GradientDrawable b = new GradientDrawable();
        b.setCornerRadius(dp(20));
        b.setColor(0xFF2C2C2F);
        p.setBackground(b);
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(new ChatIcons(icon, GLYPH));
        p.addView(iv, new LayoutParams(dp(20), dp(20)));
        TextView tv = new TextView(c);
        tv.setText(label);
        tv.setTextColor(GLYPH);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        LayoutParams tl = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.leftMargin = dp(6);
        p.addView(tv, tl);
        p.setContentDescription(label);
        p.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                mMenu.setVisibility(GONE);
                if (mListener != null) mListener.onPickImage(camera);
            }
        });
        return p;
    }

    private LayoutParams pillLp(int left) {
        LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.leftMargin = left;
        return lp;
    }

    private ImageView roundButton(Context c, int kind, String desc) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(new ChatIcons(kind, GLYPH));
        iv.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable b = new GradientDrawable();
        b.setShape(GradientDrawable.OVAL);
        b.setColor(FILL);
        iv.setBackground(b);
        iv.setContentDescription(desc);
        return iv;
    }

    private ImageView iconButton(Context c, int kind, String desc) {
        ImageView iv = new ImageView(c);
        iv.setImageDrawable(new ChatIcons(kind, GLYPH));
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setContentDescription(desc);
        return iv;
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** 录音中的紫色跳动波点（5 点，错相正弦；系统关闭动画时静止）。 */
    static final class WaveDots extends View {
        private final Paint mP = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean mRunning, mInstant;
        private long mT0;

        WaveDots(Context c) {
            super(c);
            mP.setColor(0xFF9D7BFF);
        }

        boolean isInstant() {
            return mInstant;
        }

        void setRunning(boolean on, boolean instant) {
            mRunning = on;
            mInstant = instant;
            mT0 = android.os.SystemClock.uptimeMillis();
            invalidate();
        }

        @Override
        protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density;
            float cx = getWidth() / 2f, cy = getHeight() / 2f;
            float gap = 4.2f * d, r = 1.6f * d;
            long t = android.os.SystemClock.uptimeMillis() - mT0;
            for (int i = 0; i < 5; i++) {
                float x = cx + (i - 2) * gap;
                float k = (mRunning && !mInstant) ? (float) Math.sin(t / 140.0 + i * 0.9) : 0f;
                float h = r + Math.max(0f, k) * 3.2f * d;
                c.drawRoundRect(x - r, cy - h, x + r, cy + h, r, r, mP);
            }
            if (mRunning && !mInstant && isShown()) postInvalidateOnAnimation();
        }
    }
}
