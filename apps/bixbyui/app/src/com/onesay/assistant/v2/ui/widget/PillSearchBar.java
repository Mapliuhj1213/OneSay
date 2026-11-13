package com.onesay.assistant.v2.ui.widget;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 曲面屏防遮挡胶囊输入条（PillSearchBar）
 *
 * 核心针对痛点整改：
 * 1. 彻底解决原生 Button 默认内边距导致的「发送按钮 text 被遮挡/裁切」问题；
 * 2. 右侧按钮（2026-11-05 起）：有字 = 圆形「发送」箭头，空 = 麦克风（{@link SendMicPolicy}，与对话窗底栏同一口径）；
 * 3. 内部包含软键盘 IME_ACTION_SEND 联动，回车与点击统一触达。
 */
public class PillSearchBar extends LinearLayout {

    public interface OnSubmitListener {
        void onSubmit(String text);
    }

    private final EditText mInputEdit;
    private final android.widget.ImageView mBtnSend;
    private final com.onesay.assistant.v2.ui.chat.ChatIcons mMicIcon, mSendIcon;
    private SendMicPolicy.Mode mMode;
    private OnSubmitListener mListener;
    private OnMicListener mMicListener;
    /** ★ feat/api-first：任务执行中（右侧空输入时变停止键）。 */
    private boolean mRunning;
    private final com.onesay.assistant.v2.ui.chat.ChatIcons mStopIcon;
    private Runnable mStopAction;

    /** 空输入时点右侧麦克风。 */
    public interface OnMicListener {
        void onMic();
    }

    public PillSearchBar(Context context) {
        this(context, null);
    }

    public PillSearchBar(Context context, AttributeSet attrs) {
        super(context, attrs);
        setOrientation(HORIZONTAL);
        setGravity(Gravity.CENTER_VERTICAL);

        // 外层胶囊容器
        GradientDrawable barBg = new GradientDrawable();
        barBg.setColor(Color.parseColor("#2C2C34"));
        barBg.setCornerRadius(dp(22));
        barBg.setStroke(dp(1), Color.parseColor("#28FFFFFF"));
        setBackground(barBg);
        setPadding(dp(14), dp(3), dp(4), dp(3));

        // 输入框
        mInputEdit = new EditText(context);
        mInputEdit.setHint("输入指令或向我提问...");
        mInputEdit.setHintTextColor(Color.parseColor("#8E8E93"));
        mInputEdit.setTextColor(Color.WHITE);
        mInputEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        // ★ r2/input：长句自动换行（最多 4 行，超出在框内竖向滚动）—— 以前单行横向滚动，打长句时前半句看不见、没法纠错。
        //   inputType 不带 MULTI_LINE：键盘上仍是「发送」键（回车不换行）；不用全屏提取界面（横屏也在我们自己的输入条里打字）。
        mInputEdit.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        mInputEdit.setHorizontallyScrolling(false);
        mInputEdit.setMaxLines(4);
        mInputEdit.setVerticalScrollBarEnabled(true);
        mInputEdit.setImeOptions(EditorInfo.IME_ACTION_SEND | EditorInfo.IME_FLAG_NO_EXTRACT_UI | EditorInfo.IME_FLAG_NO_FULLSCREEN);
        mInputEdit.setBackground(null); // 消除丑陋的下划线
        mInputEdit.setIncludeFontPadding(false);
        mInputEdit.setPadding(dp(4), dp(8), dp(8), dp(8));
        mInputEdit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEND ||
                        (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN)) {
                    triggerSubmit();
                    return true;
                }
                return false;
            }
        });
        addView(mInputEdit, new LayoutParams(0, LayoutParams.WRAP_CONTENT, 1.0f));

        // 右侧按钮统一口径（SendMicPolicy，与对话窗 / 悬浮输入条同一套）：
        //   有字 = 圆形「发送」箭头（点了发送文字），空 = 麦克风（点了开始语音）；TextWatcher 实时切换，只在模式变化时换图。
        mBtnSend = new android.widget.ImageView(context);
        mMicIcon = new com.onesay.assistant.v2.ui.chat.ChatIcons(com.onesay.assistant.v2.ui.chat.ChatIcons.MIC, Color.parseColor("#EDEBE7"));
        mSendIcon = new com.onesay.assistant.v2.ui.chat.ChatIcons(com.onesay.assistant.v2.ui.chat.ChatIcons.SEND, Color.parseColor("#EDEBE7"))
                .setCutColor(Color.parseColor("#2C2C34"));
        mStopIcon = new com.onesay.assistant.v2.ui.chat.ChatIcons(com.onesay.assistant.v2.ui.chat.ChatIcons.STOP, Color.parseColor("#EDEBE7"));
        mBtnSend.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        LayoutParams sendLp = new LayoutParams(dp(38), dp(38));
        sendLp.leftMargin = dp(4);
        mBtnSend.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                SendMicPolicy.Mode tap = SendMicPolicy.mode(mInputEdit.getText(), false, false, mRunning);
                if (tap == SendMicPolicy.Mode.SEND) {
                    triggerSubmit();
                } else if (tap == SendMicPolicy.Mode.STOP) {   // ★ feat/api-first：执行中点停止 = 真中断当前任务
                    if (mStopAction != null) mStopAction.run();
                } else if (mMicListener != null) {
                    hideSoftKeyboard();
                    mMicListener.onMic();
                }
            }
        });
        addView(mBtnSend, sendLp);
        mInputEdit.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(android.text.Editable e) { refreshSendMic(); }
        });
        mMode = null;
        refreshSendMic();
    }

    public void setOnSubmitListener(OnSubmitListener listener) {
        this.mListener = listener;
    }

    public EditText getInputEdit() {
        return mInputEdit;
    }

    /** 右侧按钮（有字 = 发送，空 = 麦克风）。 */
    public View getBtnSend() {
        return mBtnSend;
    }

    public void setOnMicListener(OnMicListener l) {
        mMicListener = l;
    }

    public SendMicPolicy.Mode sendMicMode() {
        return mMode;
    }

    /** ★ feat/api-first：任务执行中 → 空输入时右侧按钮变停止键（打了字仍是发送）。主线程。 */
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

    private void refreshSendMic() {
        SendMicPolicy.Mode m = SendMicPolicy.mode(mInputEdit.getText(), false, false, mRunning);
        if (m == mMode) return;
        mMode = m;
        boolean send = m == SendMicPolicy.Mode.SEND;
        boolean stop = m == SendMicPolicy.Mode.STOP;
        mBtnSend.setImageDrawable(send ? mSendIcon : stop ? mStopIcon : mMicIcon);
        int pad = send ? dp(5) : stop ? dp(6) : dp(8);
        mBtnSend.setPadding(pad, pad, pad, pad);
        mBtnSend.setContentDescription(SendMicPolicy.desc(m));
    }

    /**
     * 提交输入。
     *
     * ★ 缺陷修复（2026-10-02）：原先提交后既不收起软键盘也不清焦点，
     * 键盘会继续挡住下半屏，用户看不到执行结果卡片。现在提交即收键盘 + 清焦点。
     */
    private void triggerSubmit() {
        String text = mInputEdit.getText().toString().trim();
        if (TextUtils.isEmpty(text)) return;
        mInputEdit.setText("");
        hideSoftKeyboard();
        if (mListener != null) {
            mListener.onSubmit(text);
        }
    }

    /** 收起软键盘并清除输入框焦点（提交/取消时均调用） */
    public void hideSoftKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(mInputEdit.getWindowToken(), 0);
            }
            mInputEdit.clearFocus();
        } catch (Throwable ignore) {}
    }

    private int dp(float value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, getResources().getDisplayMetrics());
    }
}
