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
 * ★ ui-K1 对话窗底栏（悬浮圆角）：左 圈选识屏；中 输入框（左 Bixby 彩色标、占位「尽管问…」、右「+」与麦克风，
 * 录音时占位「聆听中…」、麦克风换成跳动的紫色波点）；右 通话（= 相机对话）。「+」展开 拍照 / 从相册选图，选完缩略图附在输入框上方。
 */
public final class ChatBottomBar extends LinearLayout {
    /** ★ 1123-dui B24：输入框最多长到几行，再多在框里滚。 */
    static final int MAX_LINES = 5;

    /** ★ 1123-dui B24：胶囊圆角：单行 26dp，多行收到 20dp。 */
    static float pillRadiusDp(int lines) {
        return lines > 1 ? 20f : 26f;
    }


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
    private android.widget.TextView mExpand;   // ★ 1123d B24
    private boolean mExpanded;
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
        bar.setGravity(Gravity.BOTTOM);   // ★ 1123-dui B24：输入框长高时左右按钮靠底
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
        pill.setGravity(Gravity.CENTER_VERTICAL);   // ★ 1123e P2：配合输入框 WRAP_CONTENT 高度（见下）
        pill.setPadding(dp(14), 0, dp(8), 0);
        pill.setMinimumHeight(dp(52));
        final GradientDrawable pb = new GradientDrawable();
        pb.setCornerRadius(dp(26));
        pb.setColor(FILL);
        pill.setBackground(pb);
        ImageView logo = new ImageView(c);
        logo.setImageDrawable(new ChatIcons(ChatIcons.BIXBY, GLYPH));
        LayoutParams llp = new LayoutParams(dp(24), dp(24));
        llp.bottomMargin = 0;   // 1123e：胶囊改居中
        pill.addView(logo, llp);
        mEdit = new EditText(c);
        mEdit.setBackground(null);
        // ★ 1123-dui B24：1 行起、随字长高到 5 行，超出在框里滚；不设多行输入类型，保住键盘「发送」、回车不发送
        mEdit.setSingleLine(false);
        mEdit.setHorizontallyScrolling(false);
        mEdit.setMinLines(1);
        mEdit.setMaxLines(MAX_LINES);
        mEdit.setVerticalScrollBarEnabled(true);
        mEdit.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        mEdit.setMinHeight(dp(52));
        mEdit.setTextColor(GLYPH);
        mEdit.setHintTextColor(HINT);
        mEdit.setHint(ChatWindowPolicy.HINT_IDLE);
        mEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        mEdit.setImeOptions(EditorInfo.IME_ACTION_SEND);
        // setInputType 会把单行打开；这里关掉单行后再用 raw 去掉多行标记（键盘仍显示「发送」），最后重设行数
        mEdit.setSingleLine(false);
        mEdit.setRawInputType(InputType.TYPE_CLASS_TEXT);
        mEdit.setHorizontallyScrolling(false);
        mEdit.setMaxLines(MAX_LINES);
        mEdit.setPadding(dp(8), dp(12), dp(4), dp(12));
        mEdit.addOnLayoutChangeListener(new OnLayoutChangeListener() {
            @Override public void onLayoutChange(View v, int l, int t, int r, int b, int ol, int ot, int or, int ob2) {
                pb.setCornerRadius(dp(pillRadiusDp(mEdit.getLineCount())));
            }
        });
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
        // ★ 1123e P2：真机（Android 16）上多行输入框配 MATCH_PARENT 高度时打进去的字不显示 → 改回 WRAP_CONTENT（随行数长高），
        //   字色用整套状态色（禁用 / 获焦都是亮字），光标跟字色走
        mEdit.setTextColor(android.content.res.ColorStateList.valueOf(GLYPH));
        mEdit.setMinWidth(dp(64));
        pill.addView(mEdit, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        // ★ 1123d B24：超过 3 行出「展开」，点了输入框长到 14 行好改长文；「收起」回 5 行。同一个输入框，字和光标都不动
        mExpand = new android.widget.TextView(c);
        mExpand.setText(ChatWindowPolicy.EXPAND_LABEL);
        mExpand.setTextColor(HINT);
        mExpand.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        mExpand.setPadding(dp(6), dp(8), dp(6), dp(14));
        mExpand.setVisibility(GONE);
        mExpand.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) {
                mExpanded = !mExpanded;
                int sel = mEdit.getSelectionEnd();
                mEdit.setMaxLines(ChatWindowPolicy.maxLines(mExpanded, MAX_LINES));
                mEdit.setMinLines(mExpanded ? ChatWindowPolicy.EXPANDED_LINES : 1);
                if (sel >= 0 && sel <= mEdit.length()) mEdit.setSelection(sel);
                refreshExpand();
            }
        });
        pill.addView(mExpand, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
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
                // ★ feat/api-first：任务执行中且没打字 = 停止键（真中断当前任务）
                if (com.onesay.assistant.v2.ui.widget.SendMicPolicy.mode(mEdit.getText(), hasAttachment(), false, mRunning)
                        == com.onesay.assistant.v2.ui.widget.SendMicPolicy.Mode.STOP) {
                    if (mStopAction != null) mStopAction.run();
                    return;
                }
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
            @Override public void afterTextChanged(android.text.Editable e) {
                refreshSendMic();
                mEdit.post(new Runnable() { @Override public void run() { refreshExpand(); } });   // 行数要等排版完才准
            }
        });
        micSlot.addView(mMic, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        mDots = new WaveDots(c);
        mDots.setVisibility(GONE);
        mDots.setContentDescription("聆听中，点按说完了");
        mDots.setOnClickListener(new OnClickListener() {
            @Override public void onClick(View v) { if (mListener != null) mListener.onMic(); }
        });
        micSlot.addView(mDots, new FrameLayout.LayoutParams(dp(38), dp(38), Gravity.CENTER));
        LayoutParams micLp = new LayoutParams(dp(40), dp(40));
        micLp.bottomMargin = 0;   // 1123e：胶囊改居中
        pill.addView(micSlot, micLp);
        LayoutParams plp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
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
    /** ★ 1123e：收不了音等情况下输入框里的短提示（null = 恢复默认）。 */
    public void setNoticeHint(String hint) {
        mNoticeHint = hint;
        mEdit.setHint(ChatWindowPolicy.barHint(mListening, mNoticeHint));
    }

    private String mNoticeHint;

    public void setListening(boolean on, boolean instant) {
        if (mListening == on && mDots.isInstant() == instant) return;
        mListening = on;
        mEdit.setHint(ChatWindowPolicy.barHint(on, mNoticeHint));
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
        com.onesay.assistant.v2.ui.widget.SendMicPolicy.Mode m = com.onesay.assistant.v2.ui.widget.SendMicPolicy.mode(mEdit.getText(), hasAttachment(), false, mRunning);
        if (m == mSendMic) return;
        mSendMic = m;
        boolean send = m == com.onesay.assistant.v2.ui.widget.SendMicPolicy.Mode.SEND;
        boolean stop = m == com.onesay.assistant.v2.ui.widget.SendMicPolicy.Mode.STOP;   // ★ feat/api-first
        if (stop && mStopIcon == null) mStopIcon = new ChatIcons(ChatIcons.STOP, GLYPH);
        mMic.setImageDrawable(send ? mSendIcon : stop ? mStopIcon : mMicIcon);
        int pad = send || stop ? dp(6) : dp(8);
        mMic.setPadding(pad, pad, pad, pad);
        mMic.setContentDescription(com.onesay.assistant.v2.ui.widget.SendMicPolicy.desc(m));
    }

    // ------------------------------------------------------------------ ★ feat/api-first：执行中停止键

    private boolean mRunning;
    private ChatIcons mStopIcon;
    private Runnable mStopAction;

    /** 任务执行中 → 空输入时右侧按钮变停止键（打了字仍是发送；录音中仍是波点）。主线程。 */
    public void setRunning(boolean on) {
        if (mRunning == on) return;
        mRunning = on;
        refreshSendMic();
    }

    public boolean running() {
        return mRunning;
    }

    /** 点停止键时执行（宿主交给服务取消当前任务）。 */
    public void setStopAction(Runnable r) {
        mStopAction = r;
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

    /** ★ 1123d B24：按行数露 / 藏「展开」；字删到 3 行以内且已展开 → 自动收回 5 行。 */
    private void refreshExpand() {
        if (mExpand == null) return;
        int lines = mEdit.getLineCount();
        if (mExpanded && lines <= ChatWindowPolicy.EXPAND_AFTER_LINES && mEdit.length() == 0) {
            mExpanded = false;
            mEdit.setMinLines(1);
            mEdit.setMaxLines(MAX_LINES);
        }
        mExpand.setText(mExpanded ? ChatWindowPolicy.COLLAPSE_LABEL : ChatWindowPolicy.EXPAND_LABEL);
        mExpand.setVisibility(ChatWindowPolicy.showExpandToggle(lines, mExpanded) ? VISIBLE : GONE);
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
