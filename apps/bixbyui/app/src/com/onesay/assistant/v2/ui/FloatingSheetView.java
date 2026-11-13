package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.inputmethod.InputMethodManager;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.ConsentPolicy;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.ui.widget.CurvedCapsuleButton;
import com.onesay.assistant.v2.ui.widget.CurvedEdgeContainer;
import com.onesay.assistant.v2.ui.widget.FluidCardView;
import com.onesay.assistant.v2.ui.widget.MaxHeightScrollView;
import com.onesay.assistant.v2.ui.widget.PillSearchBar;
import com.onesay.assistant.v2.ui.widget.WaveVisualizerView;
import org.json.JSONObject;

/**
 * 底部 Sheet 悬浮面板视图 —— 遵循 _probe/wake_animation_preview.html 小爱水光实机规范与 Apple HIG 重塑。
 *
 * 核心架构与交互布局：
 * 1. 【顶部状态行 (srow)】：
 *    - 呼吸绿点 (dot) + 标题 (正在聆听) + 副标题 (OneSay · 唤醒确认 / 识别动态)；
 *    - 右侧嵌入 5 柱流体波形动画条 (WaveVisualizerView)；
 *    - 苹果高对比「✕ 取消」/「✕ 关闭」胶囊按钮。
 * 2. 【核心模式控制胶囊组 (schips)】：
 *    - 「✦ 语音」：一键切回语音聆听与声学波形；
 *    - 「⌨ 文字」：无缝展开文字搜索输入条并唤起键盘；
 *    - 「◎ 识屏」：一键发起屏幕捕获与视觉多模态分析。
 * 3. 【流畅物理弹簧入场 (SpringSolver)】：
 *    - 采用 Apple HIG 标准阻尼模型（ζ=0.8, T=0.32s），手感温润舒展。
 * 4. 【完整继承业务能力】：
 *    - 流体提问卡、流式问答长字卡、物理状态回读卡、可反悔开关 (ToggleItem)、高危操作确认卡 (ConfirmBox)。
 */
public class FloatingSheetView extends CurvedEdgeContainer {

    /** 确认卡用户选择（黄卡三选项）。 */
    public static final int CHOICE_ONCE = 1;    // 允许单次
    public static final int CHOICE_ALWAYS = 2;  // 始终允许
    public static final int CHOICE_REJECT = 3;  // 拒绝

    public interface OnActionListener {
        void onSubmitCommand(String command);
        /** 敏感操作确认卡的用户选择（CHOICE_ONCE / CHOICE_ALWAYS / CHOICE_REJECT）。 */
        void onConfirmChoice(Capability cap, JSONObject args, boolean isTainted, int choice);
        void onCancelAction();
        void onDismiss();
        void onToggleRequested(String baseSign, boolean newState);
        void onVoiceModeRequested();
        void onTextModeRequested();
        void onScreenModeRequested();
        /** ★ 11-04 相机对话：点输入栏的相机钮 → 全屏实时取景 + 自动语音对话（退出后回到原输入模式）。 */
        void onCameraModeRequested();
        /** 拨动某条闹钟的开关（id 与目标状态）。 */
        void onAlarmToggle(int id, boolean on);
        /** 点击卡片 → 打开晨间播报对应编辑页。 */
        void onAlarmOpen(int id);
        /** ★ r2/input：聆听中点按问句卡 / 波形 = 「说完了」，马上识别（还没开口时由服务忽略）。 */
        void onFinishListening();
        /** ★ 综合包：🎤 收音中再点、还没开口 → 停止收音，回结果态（不清屏）。 */
        void onStopListening();
    }

    public static final class ToggleItem {
        public final String label;
        public final boolean on;
        public final String baseSign;
        public ToggleItem(String label, boolean on, String baseSign) {
            this.label = label;
            this.on = on;
            this.baseSign = baseSign;
        }
    }

    // 头部状态行控件（★ 固定区：不随内容滚动 —— ✕ 关闭/聆听状态永远可见）
    private final LinearLayout mHeaderRow;
    private final View mDotView;
    private final TextView mStatusTitle;
    private final TextView mStatusSub;
    private final WaveVisualizerView mWaveView;
    private final CurvedCapsuleButton mBtnCancelTop;

    // 会话内容滚动容器（状态行/胶囊/问答卡/确认卡都装在里面）。
    //   长答案或键盘挤占空间时，超出的内容进入滚动，底部固定件（搜索栏/发送/建议条）永远可见。
    private final MaxHeightScrollView mContentScroll;
    private final LinearLayout mContentInner;
    private boolean mStickToBottom = true;

    // 模式切换胶囊组 (✦ 语音 / ⌨ 文字 / ◎ 识屏)
    private final LinearLayout mChipsRow;
    private final CurvedCapsuleButton mChipVoice;
    private final CurvedCapsuleButton mChipText;
    private final CurvedCapsuleButton mChipScreen;

    // 内容展示区（流体卡片）
    private final FluidCardView mPromptCard;
    private final TextView mTranscriptText;

    private final FluidCardView mResultCard;
    private final TextView mResultText;
    /** [richout] 富内容回答容器（mResultCard 里紧跟 mResultText）；mRichSrc = 当前画着的原文（同一原文不重画，免得图卡 WebView 反复重建）。 */
    private final LinearLayout mRichBox;
    private String mRichSrc;
    private final TextView mVerifyBadge;
    private final LinearLayout mToggleContainer;

    // 晨间播报同款闹钟卡片区（OneSay ↔ 晨间播报联动的视觉主角）。
    //   每张子卡片复刻晨间播报 AlarmPanel.buildCard 的样式：
    //   大时间（34sp light）+ 倒计时 meta + 开关 + 铃声行。
    private final LinearLayout mAlarmBox;

    // 敏感操作确认卡（红卡=现行设计 / 黄卡=三选项新设计）
    private final FluidCardView mConfirmBox;
    private final TextView mConfirmTitle;
    private final TextView mConfirmDesc;
    private final LinearLayout mConfirmRowRed;
    private final CurvedCapsuleButton mBtnCancel;
    private final CurvedCapsuleButton mBtnConfirm;
    private final LinearLayout mConfirmRowYellow;
    private final CurvedCapsuleButton mBtnAlways;
    private final CurvedCapsuleButton mBtnOnce;
    private final CurvedCapsuleButton mBtnReject;

    // 快捷建议与文字输入
    private final HorizontalScrollView mSuggestionScroll;
    private final LinearLayout mChipsContainer;
    private final PillSearchBar mSearchBar;

    private OnActionListener mListener;
    private Capability mPendingCap;
    private JSONObject mPendingArgs;
    private boolean mPendingTainted;
    /** ★ round3 #1：确认卡已被裁决（显示的是「已拒绝」终态卡，不再算「未决确认卡」）。 */
    private boolean mConfirmDecided;
    private boolean mAskStreaming;

    // ★ ui-K1 全屏对话窗：同一会话第二次起卡片就地展开成对话窗（逻辑在 ui/chat/ChatWindowController，本类只留挂接点）
    private com.onesay.assistant.v2.ui.chat.ChatWindowController mChat;
    private com.onesay.assistant.v2.ui.chat.ChatWindowController.ServiceHost mChatSvc;
    private final android.graphics.Paint mChatBg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint mChatHandle = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint mChatGlow = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.RectF mChatTmp = new android.graphics.RectF();
    private android.graphics.Shader mChatGlowShader;
    private int mChatGlowW, mChatGlowH;
    private boolean mChatHandleDown;
    private long mChatAttachedAt;
    private float mChatHandleY;

    public FloatingSheetView(Context context) {
        super(context);

        setOnDismissListener(new OnDismissListener() {
            @Override
            public void onDismiss() {
                if (mListener != null) {
                    mListener.onDismiss();
                }
            }
        });

        // =====================================================================
        // 0. 会话内容滚动容器（MaxHeightScrollView）
        //
        //    //    长答案（真实问答常见 1000px+ 的卡片）或键盘弹起挤占空间时，面板内容
        //    总高会超过窗口可用高度 —— 原先底部的「⌨ 文字输入栏 + 发送按钮」会被
        //    布局顺序挤到窗口外被裁掉（实测 uiautomator 树里彻底消失），用户看到的
        //    现象是"点文字弹出键盘后，输入栏和发送按钮全都不见了"。
        //
        //    结构定稿（固定区 + 滚动区）：
        //      [固定] 状态行(✕/脉冲点/波形) → [固定] 模式胶囊(语音/文字/识屏)
        //      → [滚动] 问句卡 + 结果卡 + 确认卡 → [固定] 搜索栏(发送) → [固定] 建议条
        //    滚动区上限 = 窗口可用高度 − 固定区自然高度；固定区永远可见。
        // =====================================================================
        mContentInner = new LinearLayout(context);
        mContentInner.setOrientation(VERTICAL);
        mContentScroll = new MaxHeightScrollView(context);
        mContentScroll.addView(mContentInner);
        mContentScroll.setScrollListener(new MaxHeightScrollView.ScrollListener() {
            @Override
            public void onScrollChanged(int scrollY, int contentHeight, int viewHeight) {
                if (contentHeight <= viewHeight) {
                    mStickToBottom = true;
                    return;
                }
                // 距底 28dp 以内视为"跟随模式"；用户主动上翻阅读时暂停自动跟随
                mStickToBottom = (contentHeight - viewHeight - scrollY) <= dp(28);
            }
        });
        // ★ 注意：mContentScroll 挂到根布局的动作放在模式胶囊创建之后 ——
        //   根布局子视图顺序 = 固定状态行 → 固定胶囊 → 滚动区 → 搜索栏 → 建议条。

        // =====================================================================
        // 1. 头部状态行 (srow)：指示点 + 标题/副标 + 波形动画 + 取消按钮
        // =====================================================================
        mHeaderRow = new LinearLayout(context);
        final LinearLayout headerRow = mHeaderRow;
        headerRow.setOrientation(HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams headerLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        headerLp.leftMargin = dp(6);
        headerLp.rightMargin = dp(6);
        headerLp.topMargin = dp(2);
        headerLp.bottomMargin = dp(14);

        // 1.1 绿色呼吸指示圆点
        mDotView = new View(context);
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setColor(Color.parseColor("#30D158"));
        dotBg.setCornerRadius(dp(4));
        mDotView.setBackground(dotBg);
        LayoutParams dotLp = new LayoutParams(dp(8), dp(8));
        dotLp.rightMargin = dp(9);
        headerRow.addView(mDotView, dotLp);

        // 1.2 标题与副标题纵向布局
        LinearLayout titleCol = new LinearLayout(context);
        titleCol.setOrientation(VERTICAL);
        mStatusTitle = new TextView(context);
        mStatusTitle.setText("正在聆听");
        mStatusTitle.setTextColor(Color.WHITE);
        mStatusTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        mStatusTitle.getPaint().setFakeBoldText(true);
        mStatusTitle.setIncludeFontPadding(false);
        titleCol.addView(mStatusTitle);

        mStatusSub = new TextView(context);
        mStatusSub.setText("OneSay · 唤醒确认");
        mStatusSub.setTextColor(Color.parseColor("#8C8C97"));
        mStatusSub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        mStatusSub.setIncludeFontPadding(false);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = dp(2);
        titleCol.addView(mStatusSub, subLp);
        headerRow.addView(titleCol, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        // 1.3 5-柱流体音频波形组件 (WaveVisualizerView)
        mWaveView = new WaveVisualizerView(context);
        mWaveView.setOnClickListener(new OnClickListener() {   // ★ r2/input：旧界面点波形 = 说完了
            @Override
            public void onClick(View v) {
                if (isListeningForFinish() && mListener != null) mListener.onFinishListening();
            }
        });
        LayoutParams waveLp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        waveLp.rightMargin = dp(12);
        headerRow.addView(mWaveView, waveLp);

        // 1.4 苹果「✕ 取消」胶囊按钮
        mBtnCancelTop = new CurvedCapsuleButton(context);
        mBtnCancelTop.setStyle(CurvedCapsuleButton.STYLE_CANCEL);
        mBtnCancelTop.setText("✕ 取消");
        mBtnCancelTop.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        mBtnCancelTop.setCornerRadiusDp(13);
        mBtnCancelTop.setPadding(dp(12), dp(6), dp(12), dp(6));
        mBtnCancelTop.setMinHeight(dp(32));
        mBtnCancelTop.setMinWidth(dp(62));
        mBtnCancelTop.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onDismiss();
                }
            }
        });
        headerRow.addView(mBtnCancelTop, new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        addView(mHeaderRow, headerLp);

        // =====================================================================
        // 2. 模式切换三胶囊按钮组 (schips: ✦ 语音 | ⌨ 文字 | ◎ 识屏)
        // =====================================================================
        mChipsRow = new LinearLayout(context);
        mChipsRow.setOrientation(HORIZONTAL);
        mChipsRow.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams chipsRowLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        chipsRowLp.bottomMargin = dp(12);

        mChipVoice = createModeChip("✦ 语音");
        mChipVoice.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                switchToVoiceMode();
                if (mListener != null) {
                    mListener.onVoiceModeRequested();
                }
            }
        });
        mChipsRow.addView(mChipVoice, new LayoutParams(0, dp(34), 1.0f));

        mChipText = createModeChip("⌨ 文字");
        LayoutParams chipTextLp = new LayoutParams(0, dp(34), 1.0f);
        chipTextLp.leftMargin = dp(8);
        chipTextLp.rightMargin = dp(8);
        mChipText.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                switchToTextMode();
                if (mListener != null) {
                    mListener.onTextModeRequested();
                }
            }
        });
        mChipsRow.addView(mChipText, chipTextLp);

        mChipScreen = createModeChip("◎ 识屏");
        mChipScreen.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onScreenModeRequested();
                }
            }
        });
        mChipsRow.addView(mChipScreen, new LayoutParams(0, dp(34), 1.0f));
        // ★ 11-04 相机对话：旧界面胶囊组同样有入口（与「⌨ 文字」同款胶囊）
        CurvedCapsuleButton chipCamera = createModeChip("◉ 相机");
        LayoutParams chipCamLp = new LayoutParams(0, dp(34), 1.0f);
        chipCamLp.leftMargin = dp(8);
        chipCamera.setContentDescription("相机对话");
        chipCamera.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) mListener.onCameraModeRequested();
            }
        });
        mChipsRow.addView(chipCamera, chipCamLp);
        addView(mChipsRow, chipsRowLp);

        // ★ 滚动区挂载（根布局第 3 位：状态行 → 胶囊 → 滚动区 → 搜索栏 → 建议条）
        addView(mContentScroll, new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // =====================================================================
        // 3. 语音识别与提问文字流体卡片 (PromptCard)
        // =====================================================================
        mPromptCard = new FluidCardView(context);
        mPromptCard.setCardStyle(Color.parseColor("#18FFFFFF"), Color.parseColor("#1FFFFFFF"));
        LayoutParams promptLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        promptLp.bottomMargin = dp(12);

        mTranscriptText = new TextView(context);
        mTranscriptText.setTextColor(Color.WHITE);
        mTranscriptText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        mTranscriptText.setLineSpacing(dp(3), 1.2f);
        mTranscriptText.setText("随时对我说话，或点击下方文字输入...");
        mPromptCard.addView(mTranscriptText);
        mContentInner.addView(mPromptCard, promptLp);
        // ★ r2/input：聆听中点问句卡 = 说完了（小球另有「打字」，不冲突：小球自己消费点击）
        mPromptCard.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (isListeningForFinish() && mListener != null) mListener.onFinishListening();
            }
        });

        // =====================================================================
        // 4. 结果与智能问答呈现卡 (ResultCard)
        // =====================================================================
        // ★ feat/api-first：回答上方一行可折叠的执行时间轴（执行中「正在调用 X ▲」，完成后折叠成「已完成 ▼」）
        mTimelineView = new ExecTimelineView(context);
        LayoutParams tlLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlLp.bottomMargin = dp(6);
        tlLp.leftMargin = dp(4);
        mContentInner.addView(mTimelineView, tlLp);

        mResultCard = new FluidCardView(context);
        mResultCard.setCardStyle(Color.parseColor("#20202C"), Color.parseColor("#2430D158"));
        mResultCard.setVisibility(GONE);
        LayoutParams resultLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        resultLp.bottomMargin = dp(12);

        mResultText = new TextView(context);
        mResultText.setTextColor(Color.WHITE);
        mResultText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
        mResultText.setLineSpacing(dp(3), 1.25f);
        mResultCard.addView(mResultText);

        // [richout] 富内容回答（列表 / 表格 / 代码卡 / 图卡）容器：只在回答带 Markdown 特征且插件开时用，纯文本仍走 mResultText
        mRichBox = new LinearLayout(context);
        mRichBox.setOrientation(LinearLayout.VERTICAL);
        mRichBox.setVisibility(GONE);
        mResultCard.addView(mRichBox, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mVerifyBadge = new TextView(context);
        mVerifyBadge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        mVerifyBadge.setPadding(0, dp(6), 0, 0);
        mResultCard.addView(mVerifyBadge);

        // 可反悔开关容器
        mToggleContainer = new LinearLayout(context);
        mToggleContainer.setOrientation(LinearLayout.VERTICAL);
        mToggleContainer.setVisibility(GONE);
        LinearLayout.LayoutParams toggleLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        toggleLp.topMargin = dp(8);
        mResultCard.addView(mToggleContainer, toggleLp);
        mContentInner.addView(mResultCard, resultLp);

        // =====================================================================
        // 5. 敏感操作确认卡 (ConfirmBox) —— 双档重设计
        //
        //    红卡（ConsentPolicy.TIER_RED，不可逆/不可信）：保持现行设计
        //      [取消][确认执行]；没有「始终允许」（不可逆的事不能永久放行）。
        //    黄卡（ConsentPolicy.TIER_YELLOW，可恢复敏感操作）：三选项
        //      [始终允许][允许单次][拒绝]，标题/说明/按钮三段式排版，
        //      等宽按钮 42dp 高保证可点性（用户实测"字多、点不到"驱动）。
        // =====================================================================
        mConfirmBox = new FluidCardView(context);
        mConfirmBox.setCardStyle(Color.parseColor("#28FF453A"), Color.parseColor("#FF453A"));
        mConfirmBox.setVisibility(GONE);
        LayoutParams confirmLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        confirmLp.bottomMargin = dp(12);

        mConfirmTitle = new TextView(context);
        mConfirmTitle.setText("⚠️ 敏感操作安全拦截");
        mConfirmTitle.setTextColor(Color.parseColor("#FF453A"));
        mConfirmTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        mConfirmTitle.getPaint().setFakeBoldText(true);
        mConfirmBox.addView(mConfirmTitle);

        mConfirmDesc = new TextView(context);
        mConfirmDesc.setTextColor(Color.parseColor("#E5E5EA"));
        mConfirmDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        mConfirmDesc.setLineSpacing(dp(2), 1.2f);
        mConfirmDesc.setPadding(0, dp(6), 0, dp(12));
        mConfirmBox.addView(mConfirmDesc);

        // ---- 红卡按钮行（现行设计，原样保留）----
        mConfirmRowRed = new LinearLayout(context);
        mConfirmRowRed.setOrientation(HORIZONTAL);
        mConfirmRowRed.setGravity(Gravity.CENTER_VERTICAL);

        mBtnCancel = new CurvedCapsuleButton(context);
        mBtnCancel.setStyle(CurvedCapsuleButton.STYLE_SECONDARY);
        mBtnCancel.setText("取消");
        mBtnCancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        mBtnCancel.setCornerRadiusDp(14);
        mBtnCancel.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // ★ round3 #1：红卡「取消」= 拒绝这张卡（与黄卡「拒绝」同一决策通道）：
                //   显示「已拒绝」终态 → 2.5 s 回中性态 → 收起（ConfirmRejectPolicy）。
                //   旧实现走 onCancelAction（= ✕ 关闭）立即收面板，用户看不到任何反馈。
                if (mListener != null && mPendingCap != null) {
                    mListener.onConfirmChoice(mPendingCap, mPendingArgs, mPendingTainted, CHOICE_REJECT);
                    return;
                }
                hideConfirmCard();
                if (mListener != null) {
                    mListener.onCancelAction();
                }
            }
        });
        mConfirmRowRed.addView(mBtnCancel, new LayoutParams(0, dp(40), 1.0f));

        mBtnConfirm = new CurvedCapsuleButton(context);
        mBtnConfirm.setStyle(CurvedCapsuleButton.STYLE_DANGER);
        mBtnConfirm.setText("确认执行");
        mBtnConfirm.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        mBtnConfirm.setCornerRadiusDp(14);
        LayoutParams btnConfirmLp = new LayoutParams(0, dp(40), 1.0f);
        btnConfirmLp.leftMargin = dp(12);
        mBtnConfirm.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                // 红卡「确认执行」与黄卡共用同一决策通道 ——
                //   循环挂起时唤醒循环继续执行；单步卡则直接执行。
                if (mListener != null && mPendingCap != null) {
                    mListener.onConfirmChoice(mPendingCap, mPendingArgs, mPendingTainted, CHOICE_ONCE);
                }
                hideConfirmCard();
            }
        });
        mConfirmRowRed.addView(mBtnConfirm, btnConfirmLp);
        mConfirmBox.addView(mConfirmRowRed);

        // ---- 黄卡按钮行（★ 新增：三选项）----
        mConfirmRowYellow = new LinearLayout(context);
        mConfirmRowYellow.setOrientation(HORIZONTAL);
        mConfirmRowYellow.setGravity(Gravity.CENTER_VERTICAL);

        mBtnAlways = new CurvedCapsuleButton(context);
        mBtnAlways.setStyle(CurvedCapsuleButton.STYLE_SECONDARY);
        mBtnAlways.setCustomColors(Color.parseColor("#26FF9F0A"), Color.parseColor("#55FF9F0A"));
        mBtnAlways.setTextColor(Color.parseColor("#FFB340"));
        mBtnAlways.setText("始终允许");
        mBtnAlways.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mBtnAlways.setCornerRadiusDp(14);
        mConfirmRowYellow.addView(mBtnAlways, new LayoutParams(0, dp(42), 1.0f));

        mBtnOnce = new CurvedCapsuleButton(context);
        mBtnOnce.setStyle(CurvedCapsuleButton.STYLE_SECONDARY);
        mBtnOnce.setCustomColors(Color.parseColor("#FF9F0A"), Color.parseColor("#FF9F0A"));
        mBtnOnce.setTextColor(Color.parseColor("#1C1C1E"));
        mBtnOnce.setText("允许单次");
        mBtnOnce.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mBtnOnce.setCornerRadiusDp(14);
        LayoutParams btnOnceLp = new LayoutParams(0, dp(42), 1.0f);
        btnOnceLp.leftMargin = dp(8);
        mConfirmRowYellow.addView(mBtnOnce, btnOnceLp);

        mBtnReject = new CurvedCapsuleButton(context);
        mBtnReject.setStyle(CurvedCapsuleButton.STYLE_SECONDARY);
        mBtnReject.setText("拒绝");
        mBtnReject.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mBtnReject.setCornerRadiusDp(14);
        LayoutParams btnRejectLp = new LayoutParams(0, dp(42), 1.0f);
        btnRejectLp.leftMargin = dp(8);
        mConfirmRowYellow.addView(mBtnReject, btnRejectLp);

        OnClickListener yellowChoiceClick = new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null && mPendingCap != null) {
                    int choice = (v == mBtnAlways) ? CHOICE_ALWAYS
                            : (v == mBtnOnce) ? CHOICE_ONCE : CHOICE_REJECT;
                    mListener.onConfirmChoice(mPendingCap, mPendingArgs, mPendingTainted, choice);
                    // ★ round3 #1：「拒绝」由宿主把卡片换成「已拒绝」终态（showRejectedState），这里不再直接藏卡
                    if (choice == CHOICE_REJECT) return;
                }
                hideConfirmCard();
            }
        };
        mBtnAlways.setOnClickListener(yellowChoiceClick);
        mBtnOnce.setOnClickListener(yellowChoiceClick);
        mBtnReject.setOnClickListener(yellowChoiceClick);

        mConfirmRowYellow.setVisibility(GONE);
        mConfirmBox.addView(mConfirmRowYellow);

        // ---- 5.5 晨间播报同款闹钟卡片区（，置于结果卡与确认卡之间）----
        mAlarmBox = new LinearLayout(context);
        mAlarmBox.setOrientation(VERTICAL);
        mAlarmBox.setVisibility(GONE);
        LayoutParams alarmBoxLp = new LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alarmBoxLp.bottomMargin = dp(12);
        mContentInner.addView(mAlarmBox, alarmBoxLp);

        mContentInner.addView(mConfirmBox, confirmLp);

        // =====================================================================
        // 6. 文字搜索输入条 (PillSearchBar)
        // =====================================================================
        mSearchBar = new PillSearchBar(context);
        mSearchBar.setVisibility(GONE); // 默认收起，点「⌨ 文字」展开
        mSearchBar.setOnSubmitListener(new PillSearchBar.OnSubmitListener() {
            @Override
            public void onSubmit(String text) {
                if (mListener != null) {
                    mListener.onSubmitCommand(text);
                }
            }
        });
        // 点了 = 当前对话里接着说（收音中再点 = 说完了 / 停）
        mSearchBar.setOnMicListener(new PillSearchBar.OnMicListener() {
            @Override
            public void onMic() {
                if (mListener == null) return;
                // 打字阶段（TEXT）/ 结果 / 出错 → 开始续说；收音中 → 说完了 / 停（与对话窗底栏麦克风同一张表）
                switch (com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.micTap(mStage.stage(), com.onesay.assistant.v2.wake.WakeService.canFinishCapture())) {
                    case START: hideSoftKeyboard(); switchToVoiceMode(); mListener.onVoiceModeRequested(); break;
                    case FINISH: mListener.onFinishListening(); break;
                    case STOP_STAY: mListener.onStopListening(); break;
                    default: break;
                }
            }
        });
        // ★ feat/api-first：任务执行中右侧空输入变停止键 → 真中断当前任务
        mSearchBar.setStopAction(new Runnable() {
            @Override public void run() {
                requestStopTask("输入条停止键");
            }
        });
        LayoutParams searchLp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = dp(10);
        addView(mSearchBar, searchLp);
        // ★ r2/input（LC-2 复发）：不经「⌨」、直接点输入框（结果页接着问）也要进打字阶段 —— 卡片拉满、输入条贴在键盘上方、收音停掉；
        //   旧界面同样开始跟踪输入法高度（overlay 窗上 adjustResize 不压缩窗口）
        mSearchBar.getInputEdit().setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                onInputFocusChanged(hasFocus);
            }
        });

        // =====================================================================
        // 7. 快捷推荐 Chips 横向滚条 (Suggestion Pills)
        // =====================================================================
        mSuggestionScroll = new HorizontalScrollView(context);
        mSuggestionScroll.setHorizontalScrollBarEnabled(false);
        mChipsContainer = new LinearLayout(context);
        mChipsContainer.setOrientation(HORIZONTAL);
        mChipsContainer.setPadding(0, dp(2), 0, dp(8));
        mSuggestionScroll.addView(mChipsContainer);
        addView(mSuggestionScroll);
        populateDefaultChips();
    }

    private CurvedCapsuleButton createModeChip(String text) {
        CurvedCapsuleButton btn = new CurvedCapsuleButton(getContext());
        btn.setText(text);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        btn.setTextColor(Color.parseColor("#E9E9EE"));
        btn.setCornerRadiusDp(10);
        btn.setPadding(dp(10), dp(6), dp(10), dp(6));
        btn.setStyle(CurvedCapsuleButton.STYLE_SECONDARY);
        return btn;
    }

    public void setOnActionListener(OnActionListener listener) {
        this.mListener = listener;
    }

    /**
     * ★* 点「✦ 语音」= 在<b>当前会话里接着聊</b> —— 只切换聆听态外观，
     * <b>不清空转录、不隐藏结果卡</b>。
     *
     * <p>旧实现走 {@code updateState("正在聆听", …, "随时对我说话...")}，会把上一轮
     * 问答整屏擦掉。用户看到的现象是"点击语音像新开了一个对话，上下文全丢了"。
     * 会话记忆虽然没有被清，但"看得见的上下文"没了，体验上等同丢失。
     */
    /** ★ r2/sysux P1-3：续听窗没人说话 → 回到结果态（新界面：光效吸回；旧界面：中性态，不清上一轮问答）。 */
    public void endFollowUpListening() {
        if (mV2 && chatActive()) {   // 对话窗里停录音 → 清掉「聆听中」提示，回结果态（窗口不收）
            mV2Listen = null;
            if (mTranscriptText != null && com.onesay.assistant.v2.ui.wake.WakeCopy.LISTEN_HINT.contentEquals(mTranscriptText.getText() == null ? "" : mTranscriptText.getText())) {
                mTranscriptText.setText("");
            }
        }
        if (mV2) {
            if (mStage.stage() == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.LISTENING) v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.RESULT);
            return;
        }
        resetToNeutral(false);
    }

    /** 只换聆听外观，不冻结上一轮、不展开对话窗（用户开口提交时 SUBMIT 边界才展开）。 */
    public void switchToVoiceModeQuiet() {
        if (mV2) {
            mSearchBar.setVisibility(GONE);
            v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.LISTEN_UPDATE);
            return;
        }
        switchToVoiceMode();
    }

    public void switchToVoiceMode() {
        if (mV2) {
            if (mV2Final) chatBoundary(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.Trigger.CONTINUE_LISTEN);   // ★ ui-K1：接着说
            mSearchBar.setVisibility(GONE);
            hideSoftKeyboard();
            v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.LISTEN_UPDATE);
            return;
        }
        mSearchBar.setVisibility(GONE);
        mSuggestionScroll.setVisibility(VISIBLE);
        hideSoftKeyboard();
        setDotColor(Color.parseColor("#30D158"));
        if (mStatusTitle != null) mStatusTitle.setText("正在聆听");
        if (mStatusSub != null) mStatusSub.setText("已切回语音 · 请直接说话");
        mWaveView.setActive(true);
    }

    /**
     * 轻终态 —— 只改状态行（点色 / 标题 / 副标题 + 停波形），
     * <b>绝不触碰转录文字与结果卡</b>。
     *
     * <p>用于"继续聆听"会话的超时收尾：会话没结束，只是这一轮没听到 ——
     * 屏幕上必须留着上一轮问答，不能像新唤醒那样把转录区改成"请重新唤醒"。
     */
    public void markTerminalState(int dotColor, String title, String sub) {
        if (mV2) {
            com.onesay.assistant.v2.ui.wake.WakeStage.Event ev = com.onesay.assistant.v2.ui.wake.WakeStage.classifyBadge(title);
            if (ev == com.onesay.assistant.v2.ui.wake.WakeStage.Event.ERROR) mV2ErrorLine = com.onesay.assistant.v2.ui.wake.WakeCopy.errorLine(title);
            v2Event(ev);
            return;
        }
        setDotColor(dotColor);
        if (title != null && mStatusTitle != null) mStatusTitle.setText(title);
        if (sub != null && mStatusSub != null) mStatusSub.setText(sub);
        if (mWaveView != null) mWaveView.setActive(false);
    }

    /**
     * 错误态过后的中性态 —— 灰点 + 「就绪」，停波形。
     * clearTranscript=false 时保留转录区与结果卡（继续会话里的上一轮问答）。
     */
    public void resetToNeutral(boolean clearTranscript) {
        if (mV2) {
            if (clearTranscript) {
                mV2Listen = null;
                mV2Echo = null;
            }
            if (mConfirmDecided) hideConfirmCard();
            return;
        }
        setDotColor(Color.parseColor("#8E8E93"));
        if (mStatusTitle != null) mStatusTitle.setText("就绪");
        if (mStatusSub != null) mStatusSub.setText("按键唤醒，或点「✦ 语音」「⌨ 文字」");
        if (mWaveView != null) mWaveView.setActive(false);
        if (clearTranscript && mTranscriptText != null) mTranscriptText.setText("");
        if (mConfirmDecided) hideConfirmCard();   // ★ round3 #1：「已拒绝」终态卡随中性态一起收掉
    }

    /**
     * ★ round3 #1：确认卡被拒绝 → 原地换成中性灰的「已拒绝」终态卡（按钮全部隐藏），状态行同步「已拒绝」。
     * 之后是否回中性态 / 收起由宿主按 {@link ConfirmRejectPolicy} 安排。
     */
    public void showRejectedState(String summary) {
        mPendingCap = null;
        mPendingArgs = null;
        mConfirmDecided = true;
        if (mV2) {
            v2StyleConfirm(false, true);
            mConfirmTitle.setText(ConfirmRejectPolicy.TITLE);
            mConfirmDesc.setText(ConfirmRejectPolicy.cardText(summary));
            mConfirmRowRed.setVisibility(GONE);
            mConfirmRowYellow.setVisibility(GONE);
            mConfirmBox.setVisibility(VISIBLE);
            v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.RESULT);
            stickToBottomSoon();
            return;
        }
        int grey = Color.parseColor("#8E8E93");
        setDotColor(grey);
        if (mStatusTitle != null) mStatusTitle.setText(ConfirmRejectPolicy.TITLE);
        if (mStatusSub != null) mStatusSub.setText(ConfirmRejectPolicy.SUBTITLE);
        if (mWaveView != null) mWaveView.setActive(false);
        mConfirmBox.setCardStyle(Color.parseColor("#288E8E93"), grey);
        mConfirmTitle.setText("✕ " + ConfirmRejectPolicy.TITLE);
        mConfirmTitle.setTextColor(grey);
        mConfirmDesc.setText(ConfirmRejectPolicy.cardText(summary));
        mConfirmRowRed.setVisibility(GONE);
        mConfirmRowYellow.setVisibility(GONE);
        mConfirmBox.setVisibility(VISIBLE);
        stickToBottomSoon();
    }

    /** ★ round3 #1：「已拒绝」终态卡是否在显示（调试驱动 / 验收脚本探针用）。 */
    public boolean isRejectedShowing() {
        return mConfirmDecided && mConfirmBox != null && mConfirmBox.getVisibility() == VISIBLE;
    }

    /** ★ M-2：推荐芯片显隐（错误态隐藏，防止上一轮残留芯片跟着错误态常驻）。 */
    public void setSuggestionsVisible(boolean visible) {
        if (mV2) visible = false;   // ★ R13：新界面不放硬编码建议芯片（方案 §8）
        if (mSuggestionScroll != null) mSuggestionScroll.setVisibility(visible ? VISIBLE : GONE);
    }

    /** ★ M-2：用户是否正在「⌨ 文字」模式（输入条展开）。 */
    public boolean isTextModeActive() {
        if (mV2 && mChat != null && mChat.bar().typingFocus()) return true;   // 新界面打字在底栏输入框
        return mSearchBar != null && mSearchBar.getVisibility() == VISIBLE;
    }

    /** ★ M-2：确认卡是否在显示（显示中绝不自动收起）。 */
    public boolean isConfirmShowing() {
        // ★ round3 #1：「已拒绝」终态卡不算未决确认卡（否则它会挡住自己的 2.5 s 收起）
        return mConfirmBox != null && mConfirmBox.getVisibility() == VISIBLE && !mConfirmDecided;
    }

    /** 聆听中状态（只改状态行，波形保持活动）。 */
    public void markListeningState(int dotColor, String title, String sub) {
        if (mV2) {
            v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.LISTEN_UPDATE);
            return;
        }
        setDotColor(dotColor);
        if (title != null && mStatusTitle != null) mStatusTitle.setText(title);
        if (sub != null && mStatusSub != null) mStatusSub.setText(sub);
        if (mWaveView != null) mWaveView.setActive(true);
    }

    /** ★ r2/input：卡片此刻是不是「聆听中」（新界面看状态机，旧界面看波形在动）。 */
    boolean isListeningForFinish() {
        if (mV2) return mStage.stage() == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.LISTENING;
        return mWaveView != null && mWaveView.getVisibility() == VISIBLE
                && (mSearchBar == null || mSearchBar.getVisibility() != VISIBLE);
    }

    /** ★ r2/input：输入框焦点变化（直接点输入框 / ⌨ / 发送后清焦点）。 */
    void onInputFocusChanged(boolean hasFocus) {
        if (!hasFocus) {
            // 发送 / 切回语音会清焦点：不在打字阶段就停止跟踪（让位归零，卡片回原位）
            if (!mV2 || mStage.stage() != com.onesay.assistant.v2.ui.wake.WakeStage.Stage.TEXT) v2TrackIme(false);
            return;
        }
        if (mSearchBar == null || mSearchBar.getVisibility() != VISIBLE) return;
        if (mV2 && com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.enterTextOnFocus(mStage.stage())) {
            android.util.Log.i("WakeUI", "[IME] 直接点输入框（stage=" + mStage.stage() + "）→ 进打字阶段");
            v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.TEXT_MODE);
            if (mListener != null) mListener.onTextModeRequested();
        }
        v2TrackIme(true);
    }

    public void switchToTextMode() {
        if (chatActive()) {   // ★ ui-K1：对话窗里打字用底栏输入框
            showSoftKeyboard();
            v2TrackIme(true);
            return;
        }
        if (mV2) {
            // 新界面所有输入都用同一个 ChatBottomBar（旧 PillSearchBar 只留给旧界面）
            mSearchBar.setVisibility(GONE);
            v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.TEXT_MODE);
            showSoftKeyboard();
            return;
        }
        mSearchBar.setVisibility(VISIBLE);
        mSuggestionScroll.setVisibility(VISIBLE);
        mWaveView.setActive(false);
        updateState("键盘输入", Color.parseColor("#0A84FF"), "请在下方输入文字指令...");
        mSearchBar.getInputEdit().requestFocus();
        showSoftKeyboard();
        v2TrackIme(true);   // ★ r2/input：旧界面也跟踪输入法让位（以前只有新界面做）
    }

    public void showSoftKeyboard() {
        try {
            if (mSearchBar == null) return;
            boolean bar = mChat != null && (chatActive() || mV2);
            EditText edit = bar ? mChat.input() : mSearchBar.getInputEdit();
            if (edit == null) return;
            if (bar) mChat.bar().focusForTyping();   // 宿主明确要打字 → 放行这次获焦（被动获焦一律不进打字态）
            else edit.requestFocus();
            InputMethodManager imm = (InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.showSoftInput(edit, InputMethodManager.SHOW_IMPLICIT);
            }
        } catch (Throwable ignore) {}
    }

    public void hideSoftKeyboard() {
        try {
            if (mSearchBar == null) return;
            EditText edit = (mChat != null && (chatActive() || mV2)) ? mChat.input() : mSearchBar.getInputEdit();
            if (edit == null) return;
            InputMethodManager imm = (InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) {
                imm.hideSoftInputFromWindow(edit.getWindowToken(), 0);
            }
            edit.clearFocus();
        } catch (Throwable ignore) {}
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled()) {
                hideSoftKeyboard();
                if (mListener != null) {
                    mListener.onDismiss();
                }
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    // =====================================================================
    // //   测量时优先给"底部固定件"（搜索栏 + 建议条）预留高度，剩余部分才是内容滚动区的上限。
    //   这样：① 长答案不再把输入栏挤出窗口（旧实现里它们被顺序测量压成 0 或裁出可视区）；
    //        ② 键盘弹起挤占空间时，内容区自动变矮并滚动，输入栏依然贴在键盘上方可见。
    // =====================================================================

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (mContentScroll == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            return;
        }
        mContentScroll.setMaxHeightPx(0);
        if (mIconRowHiddenForIme && mImeInset == 0 && mV2IconRow != null && !chatActive()
                && !com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.cardBarVisible(mStage.stage(), mV2, false)) {
            mV2IconRow.setVisibility(VISIBLE);   // ★ r2/kb-fix：键盘收起 → 图标行回来
            mIconRowHiddenForIme = false;
        }

        int mode = MeasureSpec.getMode(heightMeasureSpec);
        int avail = MeasureSpec.getSize(heightMeasureSpec);
        if (mode != MeasureSpec.UNSPECIFIED && avail > 0) {
            int innerW = Math.max(0, MeasureSpec.getSize(widthMeasureSpec)
                    - getPaddingLeft() - getPaddingRight());
            // 固定区 = 除内容滚动区以外的所有可见子 View（状态行 + 模式胶囊 + 搜索栏 + 建议条 + 后加的
            //   「未安装 X，点此安装」提示条等，含各自竖直边距）。
            // 以前写死 4 个，插件后加到面板底部的提示条不算进固定区 → 长答案把滚动区撑到上限时
            //   提示条会被挤到面板外（点不到）。改成遍历子 View，以后谁往面板底部加东西都自动预留。
            int chrome = getPaddingTop() + getPaddingBottom();
            for (int i = 0; i < getChildCount(); i++) {
                View c = getChildAt(i);
                if (c == mContentScroll) continue;
                chrome += measureFixedHeight(c, innerW);
            }
            // ★ r2/input：键盘弹着时内容区下限 96dp → 40dp（横屏 + 键盘时 96dp 会把输入条挤到键盘下面）
            int cap = com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.contentCap(avail, chrome, mImeInset, dp(96), dp(40));
            // ★ r2/kb-fix：横屏 + 贴底键盘仍放不下 → 先收图标行，再把内容区压到 0（输入条必须完整可见）
            if (mImeInset > 0 && chrome + cap > avail) {
                int iconH = (mV2IconRow != null && mV2IconRow.getVisibility() == VISIBLE) ? measureFixedHeight(mV2IconRow, innerW) : 0;
                int[] t = com.onesay.assistant.v2.ui.ime.ImeGeometry.tightLayout(avail, chrome, iconH, mImeInset, dp(40));
                if (t[0] == 1) {
                    mV2IconRow.setVisibility(GONE);
                    mIconRowHiddenForIme = true;
                    android.util.Log.i("WakeUI", "[KB-TIGHT] 键盘弹着放不下 → 收图标行，内容区 " + t[1] + "px");
                }
                cap = t[1];
            }
            mContentScroll.setMaxHeightPx(cap);
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    /**
     * 以"高度不受限"量一个底部固定件的自然高度（含竖直边距）。
     *
     * <p>必须单独先量：若等 super.onMeasure 顺次测量，超限时它们会先被剩余空间压成 0，
     * 拿不到真实高度（这正是旧缺陷的一部分）。
     */
    private int measureFixedHeight(View v, int parentInnerW) {
        if (v == null || v.getVisibility() == GONE) return 0;
        int extra = 0;
        if (v.getLayoutParams() instanceof MarginLayoutParams) {
            MarginLayoutParams lp = (MarginLayoutParams) v.getLayoutParams();
            extra = lp.topMargin + lp.bottomMargin;
            parentInnerW -= (lp.leftMargin + lp.rightMargin);
        }
        v.measure(MeasureSpec.makeMeasureSpec(Math.max(0, parentInnerW), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        return v.getMeasuredHeight() + extra;
    }

    /** 内容更新后滚到最新一行（仅"跟随模式"生效；用户上翻阅读时自动暂停）。 */
    private void stickToBottomSoon() {
        if (mContentScroll == null || !mStickToBottom) return;
        mContentScroll.post(new Runnable() {
            @Override
            public void run() {
                if (mStickToBottom) {
                    mContentScroll.fullScroll(View.FOCUS_DOWN);
                }
            }
        });
    }

    /** 弹簧阻尼入场动画（Apple HIG ζ=0.8, T=0.32s） */
    public void animateShow() {
        if (mV2) {
            v2CardIn();
            return;
        }
        animate().cancel();
        clearAnimation();
        int h = getHeight() > 0 ? getHeight() : dp(350);
        setTranslationY(h);
        setAlpha(0.0f);
        setVisibility(VISIBLE);
        animate()
                .translationY(0f)
                .alpha(1.0f)
                .setDuration(360)
                .setInterpolator(new SpringSolver(0.8f, 0.32f))
                .start();
    }

    public void animateHide(final Runnable onComplete) {
        if (mV2) {
            v2Dismiss(onComplete);
            return;
        }
        animate().cancel();
        clearAnimation();
        int h = getHeight() > 0 ? getHeight() : dp(350);
        animate()
                .translationY(h)
                .alpha(0.0f)
                .setDuration(160)
                .setInterpolator(new AccelerateInterpolator(1.8f))
                .withEndAction(new Runnable() {
                    @Override
                    public void run() {
                        setVisibility(GONE);
                        setTranslationY(0f);
                        setAlpha(1.0f);
                        if (onComplete != null) onComplete.run();
                    }
                })
                .start();
    }

    public void setDismissLabel(String label) {
        if (label != null && mBtnCancelTop != null) mBtnCancelTop.setText(label);   // 新界面头部行隐藏，✕ 自绘
    }

    public void updateState(String badge, int color, String transcript) {
        if (badge != null) mAskStreaming = false;
        if (mV2) {
            v2UpdateState(badge, transcript);
            return;
        }

        if (mStatusTitle != null && badge != null) {
            mStatusTitle.setText(badge.replace("● ", "").replace("🎤 ", "").replace("✓ ", "").replace("✕ ", ""));
        }

        if (badge != null && (badge.contains("聆听") || badge.contains("在听") || badge.contains("🎤"))) {
            setDotColor(Color.parseColor("#30D158"));
            if (mStatusSub != null) mStatusSub.setText("OneSay · 唤醒确认");
            mWaveView.setActive(true);
            mResultCard.setVisibility(GONE);
            mConfirmBox.setVisibility(GONE);
            setDismissLabel("✕ 取消");
        } else if (badge != null && badge.contains("解析")) {
            setDotColor(Color.parseColor("#0A84FF"));
            if (mStatusSub != null) mStatusSub.setText(StatusLinePolicy.PARSING);
            mWaveView.setActive(false);
            mResultCard.setVisibility(GONE);
            mConfirmBox.setVisibility(GONE);
            setDismissLabel("✕ 取消");
        } else if (badge != null && badge.contains("键盘")) {
            // 文字模式专属副标题（旧实现落进 else，副标题会残留上一个状态
            //   的文案，实测出现"键盘输入 / 正在执行系统动作..."的错位组合）。
            setDotColor(color);
            if (mStatusSub != null) mStatusSub.setText("文字模式 · 回车或点「发送」提交");
            mWaveView.setActive(false);
        } else if (badge != null && (badge.contains("完毕") || badge.contains("执行成功")
                || badge.contains("执行异常") || badge.contains("执行未达成")
                || badge.contains("部分完成") || badge.contains("已启动应用")
                || badge.contains("未能完成") || badge.contains("已中止"))) {
            // 终态不再落进"执行中"分支 —— 旧实现"执行完毕/执行成功"含"执行"
            //   被本分支误吞，副标题写成"正在执行系统动作..."，与徽标自相矛盾（真机截图实测）。
            setDotColor(color);
            if (mStatusSub != null) {
                String cur = mStatusSub.getText() != null ? mStatusSub.getText().toString() : "";
                if (cur.contains("正在") || cur.contains("思考中") || cur.contains("执行")) {
                    mStatusSub.setText("本轮任务已结束");
                }
            }
            mWaveView.setActive(false);
        } else if (badge != null && badge.contains("正在执行")) {
            setDotColor(Color.parseColor("#BF5AF2"));
            if (mStatusSub != null) mStatusSub.setText("正在执行系统动作...");
            mWaveView.setActive(false);
            setDismissLabel("✕ 取消");
        } else {
            setDotColor(color);
            mWaveView.setActive(false);
        }

        if (transcript != null) {
            mTranscriptText.setText(transcript);
        }
    }

    private void setDotColor(int color) {
        GradientDrawable dotBg = new GradientDrawable();
        dotBg.setColor(color);
        dotBg.setCornerRadius(dp(4));
        mDotView.setBackground(dotBg);
    }

    public void showResult(ExecutionResult result) {
        if (result != null && com.onesay.assistant.v2.router.UntrustedTags.has(result.userMessage)) {   // ★ audit-J 6：ask.reply 等结果正文
            result = new ExecutionResult(result.ok, result.code, result.verification, com.onesay.assistant.v2.router.UntrustedTags.strip(result.userMessage),
                    result.detail, result.hint, result.capability, result.args);
        }
        if (mV2) {
            v2ShowResult(result);
            return;
        }
        mAskStreaming = false;
        clearToggles();
        // ★ 非闹钟结果先把旧闹钟卡片清掉（闹钟域的执行由服务层随后重建卡片）
        clearAlarmCards();
        mConfirmBox.setVisibility(GONE);
        mResultCard.setVisibility(VISIBLE);
        hideRich();   // [richout]
        mResultText.setText(result.userMessage);
        mLastSetBody = null;   // 别处改了正文 → v2Render 下次必须重设

        if (result.ok) {
            updateState("执行完毕", Color.parseColor("#30D158"), null);
            mResultCard.setCardStyle(Color.parseColor("#201E2E24"), Color.parseColor("#3330D158"));
            mVerifyBadge.setVisibility(VISIBLE);
            mVerifyBadge.setTextColor(Color.parseColor("#30D158"));
            mVerifyBadge.setText("✓ 物理硬件状态回读校验通过 (OK)");
        } else {
            updateState("执行异常", Color.parseColor("#FF9F0A"), null);
            mResultCard.setCardStyle(Color.parseColor("#202E1E1E"), Color.parseColor("#33FF9F0A"));
            mVerifyBadge.setVisibility(VISIBLE);
            mVerifyBadge.setTextColor(Color.parseColor("#FF9F0A"));
            mVerifyBadge.setText("⚠ 状态码: " + result.code.name() + " (" + result.code.description + ")");
        }
        offerMailSettings(result.userMessage, result.code,
                result.capability != null ? result.capability.sign : null);
        // 动作确认语音回复（开关 tts_reply，默认关；失败只播面板文字）
        FloatingSheetService.onResultShown("执行结果");   // ★ r2/sysux P1-4：结果上屏 → 识屏完成后可获焦（返回键归面板）
        if (result.ok) TtsSpeaker.get(getContext()).speakConfirmation(result.userMessage);
        else com.onesay.assistant.v2.wake.WakeService.onReplyNotSpoken("旧界面失败不播报");
        stickToBottomSoon();
    }

    public void setToggles(final java.util.List<ToggleItem> items) {
        if (mToggleContainer == null) return;
        mToggleContainer.removeAllViews();
        if (items == null || items.isEmpty()) {
            mToggleContainer.setVisibility(GONE);
            return;
        }
        mToggleContainer.setVisibility(VISIBLE);

        for (int i = 0; i < items.size(); i++) {
            final ToggleItem it = items.get(i);
            LinearLayout row = new LinearLayout(getContext());
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            if (i > 0) row.setPadding(0, dp(8), 0, 0);

            TextView label = new TextView(getContext());
            label.setText(it.label);
            label.setTextColor(Color.parseColor("#EBEBF5"));
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            row.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

            Switch sw = new Switch(getContext());
            sw.setSoundEffectsEnabled(false);
            sw.setHapticFeedbackEnabled(false);
            sw.setChecked(it.on);
            sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    if (mListener != null) {
                        mListener.onToggleRequested(it.baseSign, isChecked);
                    }
                }
            });
            row.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            mToggleContainer.addView(row);
        }
    }

    private void clearToggles() {
        if (mToggleContainer != null) {
            mToggleContainer.removeAllViews();
            mToggleContainer.setVisibility(GONE);
        }
    }

    // =====================================================================
    // 1 复刻三星闹钟）列表卡的样子
    //   （原来是 2026-10-05 的晨间播报暗色卡：#171B21 卡底 + 34sp 时间 + 倒计时 meta + 铃声行）。
    //   视觉常量与格式化在 AlarmCardStyle（照抄 com.onesay.clock ui/Ui + MainActivity.item）：
    //     · 白卡 #FFFFFF、圆角 26dp、内边距 24/16/16/16、卡间距 12dp
    //     · 上排：「智能播报」小胶囊（11sp medium，蓝 #3E7BF2，底 12% 蓝）
    //     · 时间行底对齐：「上午/下午」15sp + 时间 40sp light（关闭 = #B9B9BD）
    //     · 右侧：重复 = 「日 一 二 三 四 五 六」选中蓝；单次 = 「今天 / 明天」；最右 One UI 蓝开关
    //     · 卡片上方一行「X 小时 Y 分钟后响铃」（Bixby 时钟大标题下的副标题）
    //   交互不变：开关切换 → onAlarmToggle；卡片点击 → onAlarmOpen。
    // =====================================================================

    /**
     * 渲染闹钟卡片列表（Bixby 时钟样式）。
     *
     * @param items       展示模型（AlarmCardStyle.fromDump 格式化好）
     * @param highlightId 最近操作的那条（lastId），淡蓝描边；无则传 -1
     * @param summary     卡片上方那行（「X 小时后响铃」）；null / 空 = 不显示
     */
    public void showAlarmCards(final java.util.List<AlarmCardStyle.Card> items, final int highlightId, String summary) {
        if (mAlarmBox == null) return;
        mAlarmBox.removeAllViews();
        if (items == null || items.isEmpty()) {
            mAlarmBox.setVisibility(GONE);
            return;
        }
        if (summary != null && summary.length() > 0) {
            TextView sum = new TextView(getContext());
            sum.setText(summary);
            sum.setTextSize(TypedValue.COMPLEX_UNIT_SP, AlarmCardStyle.SUMMARY_SP);
            sum.setTextColor(AlarmCardStyle.T2);
            sum.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
            sum.setIncludeFontPadding(false);
            sum.setPadding(dp(8), 0, dp(8), dp(10));
            mAlarmBox.addView(sum);
        }
        for (int i = 0; i < items.size(); i++) {
            final AlarmCardStyle.Card it = items.get(i);
            LinearLayout card = buildAlarmCard(it, it.id == highlightId);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) lp.topMargin = dp(AlarmCardStyle.CARD_GAP_DP);
            mAlarmBox.addView(card, lp);
        }
        mAlarmBox.setVisibility(VISIBLE);
        stickToBottomSoon();
    }

    /** 清空闹钟卡片区（任何"新交互"进来时先清，避免旧卡片残留误导）。 */
    public void clearAlarmCards() {
        if (mAlarmBox == null) return;
        mAlarmBox.removeAllViews();
        mAlarmBox.setVisibility(GONE);
    }

    private static TextView alarmText(Context ctx, String s, float sp, int color, String family) {
        TextView t = new TextView(ctx);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create(family, Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        return t;
    }

    private LinearLayout buildAlarmCard(final AlarmCardStyle.Card a, boolean highlight) {
        Context ctx = getContext();
        LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(AlarmCardStyle.CARD);
        bg.setCornerRadius(dp(AlarmCardStyle.CARD_RADIUS_DP));
        if (highlight) {
            bg.setStroke(dp(1.5f), AlarmCardStyle.ACCENT & 0x66FFFFFF);   // 刚操作的那条：淡蓝描边（Bixby 时钟没有，低调标示）
        }
        card.setBackground(bg);
        if (Build.VERSION.SDK_INT >= 23) {
            GradientDrawable mask = new GradientDrawable();
            mask.setColor(Color.WHITE);
            mask.setCornerRadius(dp(AlarmCardStyle.CARD_RADIUS_DP));
            card.setForeground(new android.graphics.drawable.RippleDrawable(
                    android.content.res.ColorStateList.valueOf(AlarmCardStyle.RIPPLE), null, mask));
        }
        card.setContentDescription("闹钟 " + a.spoken);

        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(AlarmCardStyle.PAD_LEFT_DP), dp(AlarmCardStyle.PAD_TOP_DP),
                dp(AlarmCardStyle.PAD_RIGHT_DP), dp(AlarmCardStyle.PAD_BOTTOM_DP));

        LinearLayout left = new LinearLayout(ctx);
        left.setOrientation(VERTICAL);
        if (a.smart) {   // 上排：「智能播报」胶囊（Bixby 时钟的「智能播报」）
            LinearLayout tags = new LinearLayout(ctx);
            tags.setOrientation(HORIZONTAL);
            tags.setGravity(Gravity.CENTER_VERTICAL);
            TextView chip = alarmText(ctx, AlarmCardStyle.CHIP_SMART, AlarmCardStyle.CHIP_SP,
                    AlarmCardStyle.chipColor(a.on), "sans-serif-medium");
            chip.setPadding(dp(AlarmCardStyle.CHIP_PAD_H_DP), dp(AlarmCardStyle.CHIP_PAD_V_DP),
                    dp(AlarmCardStyle.CHIP_PAD_H_DP), dp(AlarmCardStyle.CHIP_PAD_V_DP));
            GradientDrawable cb = new GradientDrawable();
            cb.setColor(AlarmCardStyle.chipBg(a.on));
            cb.setCornerRadius(dp(AlarmCardStyle.CHIP_RADIUS_DP));
            chip.setBackground(cb);
            tags.addView(chip);
            LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            tlp.bottomMargin = dp(AlarmCardStyle.TAGS_BOTTOM_DP);
            left.addView(tags, tlp);
        }
        LinearLayout timeRow = new LinearLayout(ctx);
        timeRow.setOrientation(HORIZONTAL);
        timeRow.setGravity(Gravity.BOTTOM);
        int color = AlarmCardStyle.timeColor(a.on);
        if (a.ampm.length() > 0) {
            TextView ap = alarmText(ctx, a.ampm, AlarmCardStyle.AMPM_SP, color, "sans-serif");
            LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            alp.rightMargin = dp(AlarmCardStyle.AMPM_RIGHT_DP);
            alp.bottomMargin = dp(AlarmCardStyle.AMPM_BOTTOM_DP);
            timeRow.addView(ap, alp);
        }
        timeRow.addView(alarmText(ctx, a.time, AlarmCardStyle.TIME_SP, color, "sans-serif-light"));
        left.addView(timeRow);
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f));

        TextView days = alarmText(ctx, "", AlarmCardStyle.DAYS_SP, AlarmCardStyle.T2, "sans-serif");
        if (a.repeats()) {
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(AlarmCardStyle.daysText());
            for (int i = 0; i < 7; i++) {
                int st = AlarmCardStyle.dayCharIndex(i);
                sb.setSpan(new android.text.style.ForegroundColorSpan(AlarmCardStyle.dayColor(a.days, i, a.on)),
                        st, st + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            days.setText(sb);
        } else {
            days.setText(a.oneShot);
        }
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.rightMargin = dp(AlarmCardStyle.DAYS_RIGHT_DP);
        row.addView(days, dlp);

        Switch sw = new Switch(ctx);
        sw.setSoundEffectsEnabled(false);
        sw.setHapticFeedbackEnabled(false);
        int[][] states = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new android.content.res.ColorStateList(states,
                new int[]{AlarmCardStyle.THUMB_ON, AlarmCardStyle.THUMB_OFF}));
        sw.setTrackTintList(new android.content.res.ColorStateList(states,
                new int[]{AlarmCardStyle.TRACK_ON, AlarmCardStyle.TRACK_OFF}));
        sw.setChecked(a.on);   // 先摆初值，再挂监听（避免初始化触发回调）
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                if (mListener != null) {
                    mListener.onAlarmToggle(a.id, isChecked);
                }
            }
        });
        row.addView(sw, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        card.addView(row);

        // ---- 卡片整体点击 → 打开闹钟编辑页（开关自行消费自己的点击；原来的铃声行并进整卡点击） ----
        card.setClickable(true);
        card.setOnClickListener(new OnClickListener() {
            @Override
            public void onClick(View v) {
                if (mListener != null) {
                    mListener.onAlarmOpen(a.id);
                }
            }
        });

        return card;
    }

    /**
     * 显示敏感操作确认卡（双档）。
     *
     * @param tier {@link ConsentPolicy#TIER_RED}（不可逆/不可信，现行设计不变）
     *             或 {@link ConsentPolicy#TIER_YELLOW}（可恢复敏感操作，三选项）
     * @param note 追加说明（如循环任务的"确认后将继续完成剩余步骤"），可空
     */
    public void showConfirmCard(Capability cap, JSONObject args, boolean isTainted, int tier, String note) {
        mPendingTainted = isTainted;
        clearToggles();
        clearAlarmCards();
        mResultCard.setVisibility(GONE);
        this.mPendingCap = cap;
        this.mPendingArgs = args;
        this.mConfirmDecided = false;   // ★ round3 #1：新卡永远是未决态

        boolean red = (tier == ConsentPolicy.TIER_RED);
        if (mV2) {
            v2ShowConfirm(cap, args, red, note);
            return;
        }
        if (red) {
            // 红卡：现行设计原样（标题/说明/按钮文案与配色保持不动）
            updateState("安全拦截", Color.parseColor("#FF453A"), null);
            if (mStatusSub != null) mStatusSub.setText("高危操作 · 需手动确认");
            mConfirmBox.setCardStyle(Color.parseColor("#28FF453A"), Color.parseColor("#FF453A"));
            mConfirmTitle.setText("⚠️ 敏感操作安全拦截");
            mConfirmTitle.setTextColor(Color.parseColor("#FF453A"));
            String comms = com.onesay.assistant.v2.executor.CommsRisk.cardNote(cap.sign, args);
            String facts = com.onesay.assistant.v2.executor.ConfirmFacts.lines(cap, args);   // ★ LOGIC-6
            mConfirmDesc.setText("正在请求执行高危指令：【" + com.onesay.assistant.v2.executor.ConfirmFacts.headline(cap) + "】\n"
                    + (comms != null ? comms + "\n" : "")
                    + (facts != null ? facts + "\n" : "")
                    + "此操作具备不可逆系统状态改变，请确认是否继续？");
            mConfirmRowYellow.setVisibility(GONE);
            mConfirmRowRed.setVisibility(VISIBLE);
        } else {
            // 黄卡：可恢复敏感操作 —— 三选项 + 三段式排版（字多也读得清、点得到）
            updateState("操作待确认", Color.parseColor("#FF9F0A"), null);
            if (mStatusSub != null) mStatusSub.setText("确认后才会执行该操作");
            mConfirmBox.setCardStyle(Color.parseColor("#28FF9F0A"), Color.parseColor("#FF9F0A"));
            mConfirmTitle.setText("⚠ 敏感操作 · 请确认");
            mConfirmTitle.setTextColor(Color.parseColor("#FF9F0A"));
            StringBuilder d = new StringBuilder();
            d.append("【").append(com.onesay.assistant.v2.executor.ConfirmFacts.headline(cap)).append("】");
            String comms = com.onesay.assistant.v2.executor.CommsRisk.cardNote(cap.sign, args);
            if (comms != null) d.append("\n").append(comms);
            String facts = com.onesay.assistant.v2.executor.ConfirmFacts.lines(cap, args);   // ★ LOGIC-6
            if (facts != null) d.append("\n").append(facts);
            d.append("\n该操作需要你的授权后才会执行。");
            if (note != null && note.length() > 0) {
                d.append("\n").append(note);
            }
            mConfirmDesc.setText(d.toString());
            mConfirmRowRed.setVisibility(GONE);
            // 通信类（sms.send）黄卡不给「始终允许」，只有「允许单次 / 拒绝」
            mBtnAlways.setVisibility(com.onesay.assistant.v2.executor.ConsentPolicy.acceptsAlwaysAllow(cap) ? VISIBLE : GONE);   // r2/fixes：通信 / 对外发送 / 远程 MCP 都不给
            mConfirmRowYellow.setVisibility(VISIBLE);
        }
        mConfirmBox.setVisibility(VISIBLE);
        // ★ 卡片必须在可视区内 —— 内容多时滚到底，别让"确认键"藏在折叠区外
        stickToBottomSoon();
    }

    public void showAskReply(String question, String answer) {
        showAskReply(question, answer, StatusLinePolicy.Source.UNKNOWN);
    }

    /**
     * @param source [round7] 回答来源：答案上屏时把还停在「云端大模型思考中...」之类过渡态的副标题换成终态
     *               （本地直答不再挂着「云端大模型思考中」，见 {@link StatusLinePolicy}）。
     */
    public void showAskReply(String question, String answer, StatusLinePolicy.Source source) {
        answer = com.onesay.assistant.v2.router.UntrustedTags.strip(answer);   // ★ audit-J 6
        // ★ fix/leak-guard：回答里是系统提示词 / 热词表回吐 → 不上卡片、不朗读，按「没听清」收尾
        if (com.onesay.assistant.v2.leakguard.LeakGuardHost.blockOutput(getContext(), mV2 ? "v2ShowAskReply" : "showAskReply", answer)) {
            showLeakBlocked();
            return;
        }
        streamThrottleReset();   // 终稿直接上屏，丢掉排着的流式尾刷新
        if (mV2) {
            v2ShowAskReply(question, answer);
            return;
        }
        hideConfirmCard();
        clearToggles();
        clearAlarmCards();
        mAskStreaming = false;

        updateState("智能问答", Color.parseColor("#64D2FF"), null);
        if (mStatusSub != null) {
            String sub = StatusLinePolicy.afterReply(mStatusSub.getText() != null ? mStatusSub.getText().toString() : null, source);
            if (sub != null) mStatusSub.setText(sub);
        }
        if (question != null && question.length() > 0) {
            mTranscriptText.setText("问：“" + question + "”");
        }

        mResultCard.setVisibility(VISIBLE);
        mResultCard.setCardStyle(Color.parseColor("#22232E"), Color.parseColor("#3364D2FF"));
        mResultText.setVisibility(VISIBLE);
        mResultText.setText(withFollowups(answer, mResultText));   // ★ feat/api-first：[[追问短语]] 可点
        mLastSetBody = null;   // 别处改了正文 → v2Render 下次必须重设
        mResultText.setTextColor(Color.WHITE);
        mResultText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        showRichFinal(answer);   // [richout] 有 Markdown 特征且插件开 → 原生富渲染（隐藏 mResultText）；否则什么都不变
        mVerifyBadge.setVisibility(GONE);
        offerMailSettings(answer, null, null);
        FloatingSheetService.onResultShown("问答");   // ★ r2/sysux P1-4
        TtsSpeaker.get(getContext()).speakAnswer(answer);   // ★ P1-10 / r2/sysux：语音回合默认读，文字回合看开关
        stickToBottomSoon();
    }

    /** ★ fix/leak-guard：拦下回吐后的收尾 —— 清掉已上屏的半截回答，状态行「没听清」（不朗读）。 */
    private void showLeakBlocked() {
        mAskStreaming = false;
        if (mV2) {
            mV2Body = null;   // null = 没有答案正文（与 beginAskStream 同口径）
            mV2Final = false;
        } else {
            if (mResultText != null) mResultText.setText("");
            mLastSetBody = null;
            if (mResultCard != null) mResultCard.setVisibility(GONE);
        }
        FloatingSheetService fs = FloatingSheetService.getInstance();
        if (fs != null) fs.showTerminalPromptQuiet("没听清，可再说一次或点「⌨ 文字」输入");
        else markTerminalState(Color.parseColor("#FF9F0A"), "没听清", "可再说一次，或点「⌨ 文字」输入");
    }

    // 「去填写邮箱授权码」入口（邮件未配置/认证失败，或方案里提到授权码且确未配置时出现）
    private TextView mMailLink;

    private void offerMailSettings(String text, com.onesay.assistant.v2.executor.ErrorCode code, String sign) {
        boolean show;
        try {
            show = com.onesay.assistant.v2.executor.MailConfigForm.shouldOfferSettings(text, code, sign,
                    com.onesay.assistant.v2.executor.MailBridge.isConfigured(getContext()));
        } catch (Throwable t) {
            show = false;
        }
        if (!show) {
            if (mMailLink != null) mMailLink.setVisibility(GONE);
            return;
        }
        if (mMailLink == null) {
            mMailLink = new TextView(getContext());
            mMailLink.setText(mV2 ? "去填写邮箱授权码 ›" : "📧 去填写邮箱授权码 ›");
            mMailLink.setTextColor(Color.parseColor("#64D2FF"));
            mMailLink.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            mMailLink.setPadding(dp(4), dp(10), dp(4), dp(6));
            mMailLink.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    try {
                        android.content.Intent it = new android.content.Intent(getContext(), MailSettingsActivity.class);
                        it.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                        getContext().startActivity(it);
                    } catch (Throwable t) {
                        android.util.Log.w("FloatingSheetView", "打开邮箱设置失败: " + t.getMessage());
                    }
                    if (mListener != null) mListener.onDismiss();   // 面板让位给设置页
                }
            });
            mResultCard.addView(mMailLink);
        }
        mMailLink.setVisibility(VISIBLE);
    }

    public void beginAskStream(String question, String stageLabel) {
        hideConfirmCard();
        clearAlarmCards();
        mAskStreaming = true;
        streamThrottleReset();
        if (mV2) {
            if (!com.onesay.assistant.v2.ui.wake.WakeCopy.isPseudoQuestion(question)) mV2Echo = com.onesay.assistant.v2.ui.wake.WakeCopy.transcript(question);
            mResultCard.setVisibility(GONE);
            mConfirmBox.setVisibility(GONE);
            mV2Body = null;
            v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.THINK);
            stickToBottomSoon();
            return;
        }
        if (question != null && question.length() > 0) {
            mTranscriptText.setText("问：“" + question + "”");
        }
        mResultCard.setVisibility(GONE);
        mConfirmBox.setVisibility(GONE);
        setAskStreamStage(stageLabel);
        setDismissLabel("✕ 取消");
        stickToBottomSoon();
    }

    public void setAskStreamStage(String stageLabel) {
        if (!mAskStreaming) return;
        if (mV2) {
            if (mV2Body == null) v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.THINK);
            return;
        }
        updateState(stageLabel != null ? stageLabel : "正在作答…", Color.parseColor("#64D2FF"), null);
    }

    public void setAskStreamText(String textSoFar) {
        if (!mAskStreaming) return;
        textSoFar = com.onesay.assistant.v2.router.UntrustedTags.stripStreaming(textSoFar);   // ★ audit-J 6：字幕增量里标签被拆块也先扣住
        // ★ fix/leak-guard：流式回答攒到一半就看得出在吐提示词 → 停止上屏（终稿 showAskReply 会再判一次并收尾）
        if (com.onesay.assistant.v2.leakguard.LeakGuardHost.blockOutput(getContext(), "askStream", textSoFar)) {
            mAskStreaming = false;
            showLeakBlocked();
            return;
        }
        // 每块都记下最新全文，但上屏 ≥50 ms 合并一次（第一块立刻上屏、末尾排一次尾刷新；终稿由 showAskReply 直接上屏）
        long due = mStreamThrottle.offer(android.os.SystemClock.uptimeMillis());
        if (mV2) {
            mV2Body = textSoFar != null ? textSoFar : "";
            mV2Final = false;
            if (due == 0L) {
                v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.ANSWER_STREAM);
                stickToBottomSoon();
            } else if (due > 0L) {
                postDelayed(mStreamFlush, due);
            }
            return;
        }
        mLegacyStreamText = textSoFar;
        if (due == 0L) {
            legacyStreamRender(textSoFar);
            stickToBottomSoon();
        } else if (due > 0L) {
            postDelayed(mStreamFlush, due);
        }
    }

    private void legacyStreamRender(String textSoFar) {
        mResultCard.setVisibility(VISIBLE);
        mResultCard.setCardStyle(Color.parseColor("#22232E"), Color.parseColor("#3364D2FF"));
        mResultText.setVisibility(VISIBLE);
        mResultText.setTextColor(Color.WHITE);
        mResultText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        mVerifyBadge.setVisibility(GONE);
        hideRich();   // [richout] 流式中只用一个 TextView（代码等宽、图占位），收尾再换富渲染
        CharSequence rs = richStream(textSoFar);
        mResultText.setText(rs != null ? rs : (textSoFar != null ? com.onesay.assistant.v2.util.FollowupMarks.display(com.onesay.assistant.v2.util.FollowupMarks.streaming(textSoFar)) : ""));   // ★ feat/api-first：半截 [[ 不上屏
        mLastSetBody = null;   // 别处改了正文 → v2Render 下次必须重设
    }

    public void hideConfirmCard() {
        mConfirmBox.setVisibility(GONE);
        mPendingCap = null;
        mPendingArgs = null;
        mConfirmDecided = false;
    }

    private void populateDefaultChips() {
        String[] chips = { "现在几点", "今天天气", "打开相机", "打开wifi", "调低亮度", "打开微信" };
        for (final String chipText : chips) {
            CurvedCapsuleButton chip = new CurvedCapsuleButton(getContext());
            chip.setStyle(CurvedCapsuleButton.STYLE_CHIP);
            chip.setText(chipText);
            chip.setCornerRadiusDp(12);

            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(8);
            chip.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) {
                        mListener.onSubmitCommand(chipText);
                    }
                }
            });
            mChipsContainer.addView(chip, lp);
        }
    }

    // =====================================================================
    // ★ R13 唤醒界面 v2：悬浮连续圆角黑卡 + 显式状态机（WakeStage）+ 弹簧换入（方案 v2.2–v2.6）
    //   旧界面（classic）路径一行不动；所有 v2 分支都从各公共方法开头的 if (mV2) 进入。
    // =====================================================================

    /** 新界面的宿主回调（FloatingSheetService 实现：光晕 / 触感 / 点外部）。 */
    public interface V2Host {
        void onStageTransition(com.onesay.assistant.v2.ui.wake.WakeStage.Transition t);
        void onOutsideTapV2();
    }

    private static final int T1 = 0xFFF0EEEA, T2 = 0xFFB2AFAA, T3 = 0xFF807E7A;
    private boolean mV2;
    private boolean mV2Instant;
    private V2Host mV2Host;
    private final com.onesay.assistant.v2.ui.wake.WakeStage mStage = new com.onesay.assistant.v2.ui.wake.WakeStage();
    private com.onesay.assistant.v2.ui.wake.WakeCardPainter mPainter;
    private com.onesay.assistant.v2.ui.wake.OrbView mOrb;
    private final com.onesay.assistant.v2.island.IslandGeometry.Spring mBgTop = com.onesay.assistant.v2.ui.motion.MotionSpec.CARD_RESIZE.spring(0f);
    private final com.onesay.assistant.v2.island.IslandGeometry.Spring mInner = com.onesay.assistant.v2.ui.motion.MotionSpec.GLOW_FADE.spring(0f);
    private final com.onesay.assistant.v2.island.IslandGeometry.Spring mHalo = com.onesay.assistant.v2.ui.motion.MotionSpec.GLOW_INHALE.spring(0f);
    private final android.graphics.RectF mCardRect = new android.graphics.RectF();
    private final android.graphics.RectF mCloseRect = new android.graphics.RectF();
    private final android.graphics.Paint mCloseBg = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final android.graphics.Paint mCloseX = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private boolean mCloseDown;
    private long mV2LastFrame;
    private String mV2Listen, mV2Echo, mV2Body, mV2Task;
    /** 错误态那一句（没听清 / 没有网络 / 等待超时）。 */
    private String mV2ErrorLine;
    private boolean mV2Final;
    private CharSequence mV2ShownBody;
    // 流式 ≥50 ms 合并刷新；同一段回答的排版结果复用（不每次重排 Markdown）
    private final com.onesay.assistant.v2.ui.wake.StreamThrottle mStreamThrottle = new com.onesay.assistant.v2.ui.wake.StreamThrottle();
    private final com.onesay.assistant.v2.ui.wake.BodyMemo<CharSequence> mBodyMemo = new com.onesay.assistant.v2.ui.wake.BodyMemo<CharSequence>();
    private String mLegacyStreamText;
    /** v2Render 最近一次 setText 的对象（BodyMemo 同文同对象）；别处改 mResultText 时清空。 */
    private CharSequence mLastSetBody;
    private final Runnable mStreamFlush = new Runnable() {
        @Override public void run() {
            if (!mStreamThrottle.flushDue(android.os.SystemClock.uptimeMillis()) || !mAskStreaming) return;
            if (mV2) {
                if (mV2Final) return;
                v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.ANSWER_STREAM);
            } else {
                legacyStreamRender(mLegacyStreamText);
            }
            stickToBottomSoon();
        }
    };

    /** 终稿 / 新一轮 / 取消：丢掉还没刷的尾巴，下一块立刻上屏。 */
    private void streamThrottleReset() {
        mStreamThrottle.reset();
        removeCallbacks(mStreamFlush);
    }
    /** ★ lane C：卡片窗是固定全屏窗（WindowFlowPolicy.fullWindow）—— 卡片紧凑 / 全屏只改窗口里的卡片上沿与排布，窗口尺寸不变。 */
    private boolean mV2FullWindow;
    /** ★ lane C：卡片此刻拉满（作答 / 结果 / 确认 / 打字）。 */
    private boolean mV2CardFull;
    private boolean mV2CardTopInit;
    private final Runnable mInnerSettle = new Runnable() {
        @Override
        public void run() {
            if (!mV2) return;
            com.onesay.assistant.v2.ui.wake.WakeStage.Stage st = mStage.stage();
            if (st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.THINKING || st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.ANSWERING) {
                mInner.t = 0.12f;   // v2.4：作答 30% → 2 s 后回落到 12%
                postInvalidateOnAnimation();
            }
        }
    };

    private float dpf(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    /** 卡片在 view 里的留白（v2.3：左右 14dp、距导航栏顶 14dp；上方 20dp 给外晕 / 阴影）。 */
    private float v2Side() { return dpf(14); }
    private float v2Gutter() { return dpf(20); }
    private float v2Bottom() { return dpf(14); }
    /** ★ LC-2：卡片下沿到窗口底的距离 = 留白 + 给输入法让出的高度（打字时）。 */
    private float v2CardBottomGap() { return v2Bottom() + mImeInset; }
    private int v2PadBottomBase() { return Math.round(v2Bottom() + dpf(24)); }

    /** ★ 综合包：卡片上有上一轮的结果（🎤 停止收音时回结果态；没有就收起）。 */
    public boolean hasVisibleResult() {
        // 新界面的答案在 mV2Body / 终稿标记 / 错误行里（真机：只看 mResultCard 会误判成「没有上一轮」把卡片收掉）
        if (mV2) return mV2Body != null || mV2Final || mV2ErrorLine != null;
        return mResultCard.getVisibility() == VISIBLE;
    }

    public boolean isV2() {
        return mV2;
    }

    /** ★ lane C：服务在挂窗前告诉卡片「窗口是固定全屏的」（新界面默认；设置 window=wrap 时为 false）。 */
    public void setV2FullWindow(boolean on) {
        if (!mV2) on = false;
        if (on == mV2FullWindow) return;
        mV2FullWindow = on;
        mV2CardFull = false;
        mV2CardTopInit = false;
        setGravity(on ? Gravity.BOTTOM : Gravity.NO_GRAVITY);
        requestLayout();
    }

    // ---------------------------------------------------------------- ★ LC-1：紧凑卡阶段只让卡片收触摸

    private Object mTouchToken;
    private String mTouchLogged;
    private final com.onesay.assistant.v2.ui.wake.TouchRegionCompat.Source mTouchSource = new com.onesay.assistant.v2.ui.wake.TouchRegionCompat.Source() {
        @Override
        public boolean touchRegion(android.graphics.Region out) {
            int[] r = v2TouchRect();
            String tag = r == null ? "WINDOW" : ("CARD " + r[0] + "," + r[1] + "," + r[2] + "," + r[3]);
            if (!tag.equals(mTouchLogged)) {
                mTouchLogged = tag;
                android.util.Log.i("WakeUI", "[LC-1] 可触摸区域 → " + tag + " stage=" + mStage.stage());
            }
            if (r == null) return false;
            out.set(r[0], r[1], r[2], r[3]);
            return true;
        }
    };

    /** 当前可触摸矩形（窗口坐标）；null = 整窗。 */
    int[] v2TouchRect() {
        if (!mV2 || !mV2FullWindow) return null;
        if (chatActive()) return null;   // ★ ui-K1：对话窗全屏收触摸
        if (com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.touchArea(true, mStage.stage())
                != com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.TouchArea.CARD) return null;
        return com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.cardTouchRect(v2Side(), mBgTop.t, v2CardBottomGap(), getWidth(), getHeight());
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mChatAttachedAt = android.os.SystemClock.uptimeMillis();   // ★ ui-K1：刚挂上 ≠「面板一直在屏上」
        mImeBaseVis = 0;
        if (mV2 && mV2FullWindow && mTouchToken == null) {
            mTouchLogged = null;
            mTouchToken = com.onesay.assistant.v2.ui.wake.TouchRegionCompat.install(this, mTouchSource);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mTouchToken != null) {
            com.onesay.assistant.v2.ui.wake.TouchRegionCompat.uninstall(this, mTouchToken);
            mTouchToken = null;
        }
        v2TrackIme(false);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // ★ r2/input：转屏后「输入法弹出前的可见区底边」作废（竖屏 2094 / 横屏 1080 不能混用），重新记、重新量
        mImeBaseVis = 0;
        mImeLogged = null;
        com.onesay.assistant.v2.ui.ime.ImeProbeWindow.get(getContext()).resetBase();
        if (mImeTracking) {
            removeCallbacks(mImeProbe);
            mImeTrackSince = android.os.SystemClock.uptimeMillis();
            post(mImeProbe);
        }
    }

    // ---------------------------------------------------------------- ★ LC-2：打字时内容整体移到输入法上方

    private int mImeInset;
    private boolean mIconRowHiddenForIme;
    private int mImeBaseVis;
    private boolean mImeTracking;
    private long mImeTrackSince;
    private String mImeLogged;
    private final android.graphics.Rect mImeVis = new android.graphics.Rect();
    private final int[] mImeLoc = new int[2];
    private final Runnable mImeProbe = new Runnable() {
        @Override
        public void run() {
            if (!mImeTracking) return;
            v2ProbeIme();
            long since = android.os.SystemClock.uptimeMillis() - mImeTrackSince;
            postDelayed(this, since < 2000L ? 120L : 400L);   // 键盘弹出动画期间勤一点，之后低频跟踪（收起键盘 / 换输入法高度）
        }
    };

    private void v2TrackIme(boolean on) {
        if (on == mImeTracking) return;
        mImeTracking = on;
        removeCallbacks(mImeProbe);
        if (on) {
            mImeTrackSince = android.os.SystemClock.uptimeMillis();
            if (getWindowToken() != null) {
                getWindowVisibleDisplayFrame(mImeVis);   // 键盘是异步弹的：此刻还没出来 → 记下导航栏上沿
                mImeBaseVis = Math.max(mImeBaseVis, mImeVis.bottom);
            }
            post(mImeProbe);
            // ★ r2/kb-fix：再挂一个全高探针窗量键盘（不依赖卡片窗是不是输入法目标 / softInputMode 生不生效）
            com.onesay.assistant.v2.ui.ime.ImeProbeWindow.get(getContext()).acquire(mImeProbeListener);
        } else {
            com.onesay.assistant.v2.ui.ime.ImeProbeWindow.get(getContext()).release(mImeProbeListener);
            v2SetImeInset(0);
        }
    }

    private final com.onesay.assistant.v2.ui.ime.ImeProbeWindow.Listener mImeProbeListener =
            new com.onesay.assistant.v2.ui.ime.ImeProbeWindow.Listener() {
                @Override public void onImeChanged(int imeTop, int baseBottom, int screenH) {
                    if (mImeTracking) v2ProbeIme();
                }
            };

    private void v2ProbeIme() {
        if (getWindowToken() == null) return;
        getWindowVisibleDisplayFrame(mImeVis);
        getLocationOnScreen(mImeLoc);
        int winBottom = Math.round(mImeLoc[1] - getTranslationY()) + getHeight() + mImeInsetWindowGrowth();
        int imeH = imeVisibleHeight();
        mImeBaseVis = Math.max(mImeBaseVis, mImeVis.bottom);   // 窗口盖住导航栏时可见区会变大：底边取见过的最大值
        // ★ r2/kb-fix：三路读数（自身可见区 / 反射高度 / 探针窗）取最高的可信键盘上沿，再算要抬多少
        int sh = getResources().getDisplayMetrics().heightPixels;
        int probeTop = com.onesay.assistant.v2.ui.ime.ImeProbeWindow.get(getContext()).lastTop();
        int ownTop = com.onesay.assistant.v2.ui.ime.ImeGeometry.topFromVisible(mImeBaseVis, mImeVis.bottom, sh);
        int refTop = com.onesay.assistant.v2.ui.ime.ImeGeometry.topFromHeight(mImeBaseVis, imeH, sh);
        int imeTop = com.onesay.assistant.v2.ui.ime.ImeGeometry.combineTop(ownTop, refTop, probeTop);
        int inset = com.onesay.assistant.v2.ui.ime.ImeGeometry.liftFor(winBottom, imeTop, 0, winBottom * 3 / 4);
        String tag = "win=" + winBottom + " vis=" + mImeVis.bottom + " base=" + mImeBaseVis + " imeH=" + imeH
                + " probe=" + probeTop + " → top=" + imeTop + " lift=" + inset;
        if (!tag.equals(mImeLogged)) {
            mImeLogged = tag;
            android.util.Log.i("WakeUI", "[LC-2] 输入法让位 " + tag + (mV2FullWindow ? " (full)" : " (wrap)"));
        }
        v2SetImeInset(inset);
    }

    /** wrap 窗贴底：加了底部内边距窗口会往上长，底边不动 —— 不用修正。保留钩子便于将来窗口不贴底时改。 */
    private int mImeInsetWindowGrowth() {
        return 0;
    }

    private int imeVisibleHeight() {
        try {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm == null) return 0;
            java.lang.reflect.Method m = imm.getClass().getMethod("getInputMethodWindowVisibleHeight");
            Object v = m.invoke(imm);
            return v instanceof Integer ? (Integer) v : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** ★ r2/input：旧界面的底部内边距基线（让位从 0 变正时记下，归零时还原）。-1 = 没记。 */
    private int mV1PadBottomBase = -1;

    private void v2SetImeInset(int px) {
        if (px < 0) px = 0;
        if (px == mImeInset) return;
        if (!mV2 && mImeInset == 0 && px > 0) mV1PadBottomBase = getPaddingBottom();
        mImeInset = px;
        if (mV2) setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), v2PadBottomBase() + px);
        else if (mV1PadBottomBase >= 0) {
            // ★ r2/input：旧界面（wrap 窗贴底）也自己让位 —— 加底部内边距，窗口往上长，输入条落在键盘上方
            setPadding(getPaddingLeft(), getPaddingTop(), getPaddingRight(), mV1PadBottomBase + px);
            if (px == 0) mV1PadBottomBase = -1;
        }
        android.util.Log.i("WakeUI", "[IME] 让位 → " + px + "px（" + (mV2 ? "新界面" : "旧界面") + "）");
        mV2LastFrame = 0L;
        requestLayout();
        postInvalidateOnAnimation();
    }

    /** ★ lane C：卡片拉满 / 回到底部紧凑（只在全屏窗模式下生效）。拉满时内容从上往下排；打字时贴底（输入条挨着键盘）。 */
    private void v2ApplyFull(boolean full) {
        if (!mV2FullWindow) return;
        boolean text = mStage.stage() == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.TEXT;
        int g = full && !text ? Gravity.TOP : Gravity.BOTTOM;
        if (full != mV2CardFull || getGravityCompat() != g) {
            mV2CardFull = full;
            mV2Gravity = g;
            setGravity(g);
            requestLayout();
        }
    }

    private int mV2Gravity = Gravity.BOTTOM;

    private int getGravityCompat() {
        return mV2Gravity;
    }

    /** ★ lane C：全屏窗里卡片上沿的目标（view 坐标）：拉满 = 顶部留白；紧凑 = 第一个可见子 View 上方留出原来的内边距。 */
    private float v2CardTopTarget() {
        if (mV2CardFull) return v2Gutter();
        int first = -1;
        for (int i = 0; i < getChildCount(); i++) {
            View c = getChildAt(i);
            if (c.getVisibility() == GONE) continue;
            int top = c.getTop();
            if (c.getLayoutParams() instanceof MarginLayoutParams) top -= ((MarginLayoutParams) c.getLayoutParams()).topMargin;
            first = first < 0 ? top : Math.min(first, top);
        }
        if (first < 0) return v2Gutter();
        return Math.max(v2Gutter(), first - (getPaddingTop() - v2Gutter()));
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        if (!mV2 || !mV2FullWindow) return;
        float target = v2CardTopTarget();
        if (Math.abs(target - mBgTop.t) < 0.5f && mV2CardTopInit) return;
        boolean animate = mV2CardTopInit && !mV2Instant && getAlpha() > 0.01f;
        mBgTop.t = target;
        if (animate) {
            com.onesay.assistant.v2.ui.motion.MotionSpec.CARD_RESIZE.applyTo(mBgTop);
        } else {
            mBgTop.snap();
        }
        mV2CardTopInit = true;
        mV2LastFrame = 0L;
        postInvalidateOnAnimation();
    }

    public com.onesay.assistant.v2.ui.wake.WakeStage.Stage v2Stage() {
        return mStage.stage();
    }

    public void setV2Host(V2Host h) {
        mV2Host = h;
    }

    public void setV2Instant(boolean instant) {
        mV2Instant = instant;
        if (mOrb != null && instant) mOrb.setActive(mOrb.isShown(), true);
    }

    /** 当前卡片矩形（屏幕坐标；形变快照 / 舞台起点用）。 */
    public android.graphics.RectF cardRectOnScreen() {
        int[] loc = new int[2];
        getLocationOnScreen(loc);
        android.graphics.RectF r = new android.graphics.RectF(v2Side(), mBgTop.t, getWidth() - v2Side(), getHeight() - v2CardBottomGap());
        r.offset(loc[0], loc[1]);
        return r;
    }

    public float cardRadius() {
        return mPainter != null ? mPainter.radius : dpf(34);
    }

    /** 把卡片（不含阴影）画进一张位图（形变舞台的快照，约 1036×(卡高) px，一次）。 */
    public android.graphics.Bitmap snapshotCard() {
        try {
            float l = v2Side(), t = mBgTop.t, r = getWidth() - v2Side(), b = getHeight() - v2CardBottomGap();
            int w = Math.round(r - l), h = Math.round(b - t);
            if (w <= 0 || h <= 0) return null;
            android.graphics.Bitmap bm = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(bm);
            c.translate(-l, -t);
            float keep = mBgTop.v;
            mBgTop.v = mBgTop.t;
            draw(c);
            mBgTop.v = keep;
            return bm;
        } catch (Throwable e) {
            android.util.Log.w("WakeUI", "[WAKE-MORPH] 卡片快照失败: " + e);
            return null;
        }
    }

    /** 切新 / 旧界面（每次唤醒由服务按 WakeUiSettings 决定；kill-switch 放了文件 → 下一次唤醒就是旧界面）。 */
    public void setV2(boolean on) {
        if (on == mV2) return;
        mV2 = on;
        if (!on && mV2FullWindow) {   // ★ lane C：旧界面没有全屏窗
            mV2FullWindow = false;
            mV2CardFull = false;
            setGravity(Gravity.NO_GRAVITY);
        }
        ActionChipsView.sV2Style = on;
        relocateActionChips();
        if (on) {
            if (mPainter == null) mPainter = new com.onesay.assistant.v2.ui.wake.WakeCardPainter(getContext());
            setBackground(null);
            setHandleVisible(false);
            setFitsSystemWindows(false);
            setWillNotDraw(false);
            int side = Math.round(v2Side() + dpf(26));
            setPadding(side, Math.round(v2Gutter() + dpf(24)), side, v2PadBottomBase() + mImeInset);
            mHeaderRow.setVisibility(GONE);
            mChipsRow.setVisibility(GONE);
            mSuggestionScroll.setVisibility(GONE);
            mPromptCard.setBackground(null);
            mPromptCard.setPadding(0, 0, 0, 0);
            mPromptCard.setOrientation(HORIZONTAL);
            mPromptCard.setGravity(Gravity.CENTER_VERTICAL);
            mTranscriptText.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            mTranscriptText.setLineSpacing(dp(2), 1.1f);
            if (mOrb == null) {
                mOrb = new com.onesay.assistant.v2.ui.wake.OrbView(getContext());
                mOrb.setOnClickListener(new OnClickListener() {
                    @Override
                    public void onClick(View v) {   // D11=A：点按小球 = 打字
                        switchToTextMode();
                        if (mListener != null) mListener.onTextModeRequested();
                    }
                });
            }
            if (mOrb.getParent() == null) {
                LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(dp(30), dp(30));
                olp.leftMargin = dp(12);
                mPromptCard.addView(mOrb, olp);
            }
            attachV2IconRow();   // ★ R14：右下角 ⌨ / ◎ 两颗 32dp 图标钮
            ensureChat();   // ★ ui-K1
            ((LayoutParams) mPromptCard.getLayoutParams()).bottomMargin = dp(10);
            mResultCard.setBackground(null);
            mResultCard.setPadding(0, 0, 0, 0);
            ((LayoutParams) mResultCard.getLayoutParams()).bottomMargin = dp(4);
            mVerifyBadge.setVisibility(GONE);
            mResultText.setTextColor(T1);
            mResultText.setLineSpacing(dp(4), 1.15f);   // ★ r2/voice-templates：1.08 → 1.15，中文长答案行距更松一点
            v2StyleConfirm(true, false);
            mCloseBg.setColor(0x16FFFFFF);
            mCloseX.setColor(T2);
            mCloseX.setStrokeWidth(dpf(1.6f));
            mCloseX.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            mBgTop.v = mBgTop.t = v2Gutter();
        } else {
            if (mChat != null) mChat.exitImmediate();   // ★ ui-K1：旧界面没有对话窗
            if (mChat != null) mChat.showOnCard(false);   // 旧界面用旧输入条
            applyClassicFrame();
            setHandleVisible(true);
            setFitsSystemWindows(true);
            mHeaderRow.setVisibility(VISIBLE);
            mChipsRow.setVisibility(VISIBLE);
            mSuggestionScroll.setVisibility(VISIBLE);
            if (mOrb != null && mOrb.getParent() != null) mPromptCard.removeView(mOrb);
            if (mV2IconRow != null && mV2IconRow.getParent() == this) removeView(mV2IconRow);
            mPromptCard.setOrientation(VERTICAL);
            mPromptCard.setPadding(dp(16), dp(14), dp(16), dp(14));
            mPromptCard.setCardStyle(Color.parseColor("#18FFFFFF"), Color.parseColor("#1FFFFFFF"));
            mPromptCard.setVisibility(VISIBLE);
            ((LayoutParams) mPromptCard.getLayoutParams()).bottomMargin = dp(12);
            mTranscriptText.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            mTranscriptText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
            mTranscriptText.setTextColor(Color.WHITE);
            mTranscriptText.setLineSpacing(dp(3), 1.2f);
            mTranscriptText.setPadding(0, 0, 0, 0);
            mResultCard.setPadding(dp(16), dp(14), dp(16), dp(14));
            mResultCard.setCardStyle(Color.parseColor("#20202C"), Color.parseColor("#2430D158"));
            ((LayoutParams) mResultCard.getLayoutParams()).bottomMargin = dp(12);
            mResultText.setLineSpacing(dp(3), 1.25f);
            v2StyleConfirm(false, false);
            mConfirmBox.setCornerRadiusDp(18);
            mConfirmBox.setCardStyle(Color.parseColor("#28FF453A"), Color.parseColor("#FF453A"));
            mConfirmTitle.setTextColor(Color.parseColor("#FF453A"));
            setAlpha(1f);
            setTranslationY(0f);
            setScaleX(1f);
            setScaleY(1f);
        }
        requestLayout();
        invalidate();
    }

    /**
     * [R13 真机] 插件的动作芯片（ActionChipsView，tag = TAG_IN_SHEET）按旧面板的位置插在 index 2（内容区上面）。新卡片里那是
     *   提问回显的上面、还压着右上角的关闭钮 —— 方案 v2.5 要的是正文下面。新界面时把它放到内容区后面，切回旧界面再放回原位。
     */
    @Override
    public void addView(View child, int index, ViewGroup.LayoutParams params) {
        if (mV2 && child != null && ActionChipsView.TAG_IN_SHEET.equals(child.getTag()) && mContentScroll != null) {
            int ci = indexOfChild(mContentScroll);
            if (ci >= 0) index = ci + 1;
        }
        super.addView(child, index, params);
    }

    private void relocateActionChips() {
        View chips = findViewWithTag(ActionChipsView.TAG_IN_SHEET);
        if (chips == null || chips.getParent() != this || mContentScroll == null) return;
        int ci = indexOfChild(mContentScroll);
        int want = mV2 ? ci + 1 : Math.min(2, getChildCount() - 1);
        int cur = indexOfChild(chips);
        if (cur == want || (mV2 && cur == ci + 1)) return;
        ViewGroup.LayoutParams lp = chips.getLayoutParams();
        removeView(chips);
        int ci2 = indexOfChild(mContentScroll);
        super.addView(chips, mV2 ? ci2 + 1 : Math.min(2, getChildCount()), lp);
    }

    // ===== ★ R14（diag D 方案 A）：卡片右下角的 ⌨ 文字输入 / ◎ 识屏 =====
    //   R13 的 setV2(true) 把「✦语音 / ⌨文字 / ◎识屏」整行胶囊藏了，识屏在新界面里完全没有入口、打字只剩「点小球」（只在 LISTENING 有）。
    //   现在：两颗 32dp 圆形图标钮，右对齐放在内容区（和动作芯片）下面；聆听 / 结果 / 出错时可点，回调沿用原来的
    //   onTextModeRequested / onScreenModeRequested（⌨ 先切文字模式再回调，和小球、旧胶囊一致）。
    //   思考 / 作答这两个过渡态用 INVISIBLE（占位不点），免得 聆听→思考→结果 来回改卡片高度、多两次 WRAP_CONTENT relayout（diag C10）。
    private LinearLayout mV2IconRow;
    private com.onesay.assistant.v2.ui.wake.CardIconButton mV2BtnText, mV2BtnScreen, mV2BtnMic, mV2BtnCamera;

    private void attachV2IconRow() {
        if (mV2IconRow == null) {
            mV2IconRow = new LinearLayout(getContext());
            mV2IconRow.setOrientation(HORIZONTAL);
            mV2IconRow.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
            mV2BtnText = new com.onesay.assistant.v2.ui.wake.CardIconButton(getContext(), com.onesay.assistant.v2.ui.wake.CardIconButton.KIND_KEYBOARD);
            mV2BtnText.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    switchToTextMode();
                    if (mListener != null) mListener.onTextModeRequested();
                }
            });
            mV2BtnScreen = new com.onesay.assistant.v2.ui.wake.CardIconButton(getContext(), com.onesay.assistant.v2.ui.wake.CardIconButton.KIND_SCREEN);
            mV2BtnScreen.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) mListener.onScreenModeRequested();
                }
            });
            // ★ 综合包：🎤 续说（恢复旧「✦ 语音」：当前对话里接着说，不清屏；收音中再点 = 说完了 / 停止）
            mV2BtnMic = new com.onesay.assistant.v2.ui.wake.CardIconButton(getContext(), com.onesay.assistant.v2.ui.wake.CardIconButton.KIND_MIC);
            mV2BtnMic.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener == null) return;
                    switch (com.onesay.assistant.v2.ui.wake.MicButtonPolicy.onTap(mStage.stage(), com.onesay.assistant.v2.wake.WakeService.canFinishCapture())) {
                        case CONTINUE: switchToVoiceMode(); mListener.onVoiceModeRequested(); break;
                        case FINISH: mListener.onFinishListening(); break;
                        case STOP: mListener.onStopListening(); break;
                        default: break;
                    }
                }
            });
            LinearLayout.LayoutParams m = new LinearLayout.LayoutParams(dp(com.onesay.assistant.v2.ui.wake.MicButtonPolicy.TOUCH_DP), dp(com.onesay.assistant.v2.ui.wake.MicButtonPolicy.TOUCH_DP));
            m.rightMargin = dp(10);
            mV2IconRow.addView(mV2BtnMic, m);
            LinearLayout.LayoutParams a = new LinearLayout.LayoutParams(dp(32), dp(32));
            LinearLayout.LayoutParams b = new LinearLayout.LayoutParams(dp(32), dp(32));
            b.leftMargin = dp(10);
            mV2IconRow.addView(mV2BtnText, a);
            // ★ 11-04 相机对话：录音 / 键盘 / 相机 三种输入并排（同款 32dp 圆钮），识屏仍在最右
            mV2BtnCamera = new com.onesay.assistant.v2.ui.wake.CardIconButton(getContext(), com.onesay.assistant.v2.ui.wake.CardIconButton.KIND_CAMERA);
            mV2BtnCamera.setOnClickListener(new OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (mListener != null) mListener.onCameraModeRequested();
                }
            });
            LinearLayout.LayoutParams cam = new LinearLayout.LayoutParams(dp(32), dp(32));
            cam.leftMargin = dp(10);
            mV2IconRow.addView(mV2BtnCamera, cam);
            mV2IconRow.addView(mV2BtnScreen, b);
        }
        if (mV2IconRow.getParent() == null) {
            View chips = findViewWithTag(ActionChipsView.TAG_IN_SHEET);
            int at = (chips != null && chips.getParent() == this) ? indexOfChild(chips) + 1 : indexOfChild(mContentScroll) + 1;
            LayoutParams lp = new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(6);
            super.addView(mV2IconRow, Math.max(0, Math.min(at, getChildCount())), lp);
        }
        mV2IconRow.setVisibility(v2IconRowVisibility(mStage.stage()));
        mV2BtnMic.setActive(com.onesay.assistant.v2.ui.wake.MicButtonPolicy.active(mStage.stage()));
    }

    /** 图标行在各状态的可见性（规则在 WakeStage.iconRowVisibility，纯函数有单测）。 */
    public static int v2IconRowVisibility(com.onesay.assistant.v2.ui.wake.WakeStage.Stage st) {
        return com.onesay.assistant.v2.ui.wake.WakeStage.iconRowVisibility(st);
    }

    /** 卡片模式下内容区原来的 LayoutParams（贴底时换成 weight=1，取消时还原）。null = 没在贴底。 */
    private ViewGroup.LayoutParams mPinSavedScrollLp;

    /**
     * 底栏贴底（WindowFlowPolicy.pinBarBottom）：出结果后卡片拉满、从上往下排，内容区改成吃满剩余高度，
     * 结果态底栏（根布局最后一个子 View）因此落在卡片底 —— 屏幕底（导航让位）/ 键盘上沿（底部内边距 + 键盘高），而不是紧跟正文停在中上部。
     * 对话窗（chatActive）由 ChatWindowController 自己管内容区，这里不碰。
     */
    private void v2PinBar(boolean pin) {
        if (mContentScroll == null || chatActive()) return;
        ViewGroup.LayoutParams cur = mContentScroll.getLayoutParams();
        if (pin) {
            if (mPinSavedScrollLp != null) return;
            mPinSavedScrollLp = cur;
            mContentScroll.setLayoutParams(new LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
            android.util.Log.i("WakeUI", "[BAR-PIN] 出结果 → 内容区吃满剩余高度，底栏贴卡片底（屏幕底 / 键盘上沿）");
        } else if (mPinSavedScrollLp != null) {
            mContentScroll.setLayoutParams(mPinSavedScrollLp);
            mPinSavedScrollLp = null;
        }
    }

    /** 底栏此刻是否贴底（JVM 接线测试 / 日志用）。 */
    boolean barPinned() {
        return mPinSavedScrollLp != null;
    }

    /** 卡片模式下出结果 / 出错 / 打字时，底部换成与对话窗同一个 ChatBottomBar（ChatWindowPolicy.cardBarVisible）。 */
    private void v2RefreshCardBar(com.onesay.assistant.v2.ui.wake.WakeStage.Stage st) {
        if (mChat == null || chatActive()) return;
        mChat.showOnCard(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.cardBarVisible(st, mV2, false));
    }


    /** 预热：卡片材质烘焙（软件 Canvas，约 5–10 ms），不让它压在唤醒后的第一帧上。 */
    public void prewarmV2() {
        if (mPainter == null) mPainter = new com.onesay.assistant.v2.ui.wake.WakeCardPainter(getContext());
        mPainter.bake();
    }

    /** 新唤醒（ACTION_LISTEN）：清掉上一轮的卡片内容，状态机走 WAKE。 */
    public void v2Wake() {
        if (!mV2) return;
        chatBoundary(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.Trigger.WAKE);   // ★ ui-K1：清卡片内容之前
        mV2Listen = null;
        mV2Echo = null;
        mV2Body = null;
        mV2Task = null;
        mV2ErrorLine = null;
        mV2Final = false;
        mResultCard.setVisibility(GONE);
        hideConfirmCard();
        clearToggles();
        clearAlarmCards();
        if (mSearchBar != null) mSearchBar.setVisibility(GONE);
        v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.WAKE);
    }

    // ------------------------------------------------------------------ ★ feat/api-first：执行时间轴 / 停止键 / 可点追问

    private ExecTimelineView mTimelineView;
    private volatile com.onesay.assistant.v2.timeline.ExecTimeline mTimeline;
    private volatile boolean mTaskRunning;

    /** 新任务开始：换一份新的时间轴（任意线程；返回给调用方写事件）。 */
    public com.onesay.assistant.v2.timeline.ExecTimeline timelineBegin() {
        final com.onesay.assistant.v2.timeline.ExecTimeline t = new com.onesay.assistant.v2.timeline.ExecTimeline();
        mTimeline = t;
        setTaskRunning(true);
        post(new Runnable() {
            @Override public void run() {
                if (mTimelineView != null && mTimeline == t) mTimelineView.bind(t);
            }
        });
        return t;
    }

    /** 当前这一轮的时间轴（没有 → null）。 */
    public com.onesay.assistant.v2.timeline.ExecTimeline timeline() {
        return mTimeline;
    }

    /** 时间轴数据变了（任意线程）：重画；结束了顺手把输入框的停止键换回来。 */
    public void timelineChanged() {
        final com.onesay.assistant.v2.timeline.ExecTimeline t = mTimeline;
        if (t != null && !t.live()) setTaskRunning(false);
        post(new Runnable() {
            @Override public void run() {
                if (mTimelineView != null && mTimeline == t && t != null) mTimelineView.bind(t);
            }
        });
    }

    /** 新一轮开始：屏上的时间轴清掉（已冻结进对话历史的那份不动）。 */
    public void timelineClear() {
        mTimeline = null;
        setTaskRunning(false);
        post(new Runnable() {
            @Override public void run() {
                if (mTimelineView != null && mTimeline == null) mTimelineView.bind(null);
            }
        });
    }

    /** 输入框执行中变停止键（任意线程）。 */
    public void setTaskRunning(final boolean on) {
        if (mTaskRunning == on) return;
        mTaskRunning = on;
        post(new Runnable() {
            @Override public void run() {
                if (mSearchBar != null) mSearchBar.setRunning(mTaskRunning);
                if (mChat != null) mChat.setTaskRunning(mTaskRunning);
            }
        });
    }

    public boolean taskRunning() {
        return mTaskRunning;
    }

    /** 停止键：交给服务取消当前任务（GUI 代理 / 网络请求一并中断）。 */
    void requestStopTask(String why) {
        FloatingSheetService fs = FloatingSheetService.getInstance();
        if (fs != null) fs.stopCurrentTask(why);
    }

    /** 回答正文 → 带可点追问短语（[[x]] → 蓝色「x↵」，点了等于发送 x）。没有标记原样返回。 */
    CharSequence withFollowups(CharSequence body, TextView into) {
        if (body == null) return null;
        String src = body.toString();
        if (!com.onesay.assistant.v2.util.FollowupMarks.has(src)) return body;
        com.onesay.assistant.v2.util.FollowupMarks.Rewrite rw = com.onesay.assistant.v2.util.FollowupMarks.rewrite(src);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(rw.text);
        if (body instanceof android.text.Spanned) {   // 已有的样式（首行大字 / 灰字）按下标映射挪过去
            android.text.Spanned sp = (android.text.Spanned) body;
            for (Object o : sp.getSpans(0, sp.length(), Object.class)) {
                int a = rw.map[Math.min(sp.getSpanStart(o), rw.map.length - 1)];
                int b = rw.map[Math.min(sp.getSpanEnd(o), rw.map.length - 1)];
                if (a < b) sb.setSpan(o, a, b, sp.getSpanFlags(o));
            }
        }
        for (int i = 0; i < rw.ranges.size(); i++) {
            final String phrase = rw.phrases.get(i);
            int[] r = rw.ranges.get(i);
            sb.setSpan(new android.text.style.ClickableSpan() {
                @Override public void onClick(View w) {
                    if (mListener != null) mListener.onSubmitCommand(phrase);   // 点一下 = 用户说了这句
                }

                @Override public void updateDrawState(android.text.TextPaint ds) {
                    ds.setColor(FOLLOWUP_BLUE);
                    ds.setUnderlineText(false);
                }
            }, r[0], r[1], android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (into != null && !(into.getMovementMethod() instanceof android.text.method.LinkMovementMethod)) {
            into.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
        return sb;
    }

    static final int FOLLOWUP_BLUE = 0xFF5AA9FF;
    private static final String TAG_FOLLOWUP_ROW = "followup_row";

    /** 富渲染（表格 / 图卡）末尾挂一行可点追问短语（没有标记 → 去掉旧行）。 */
    private void addFollowupRow(String raw) {
        View old = mRichBox.findViewWithTag(TAG_FOLLOWUP_ROW);
        if (old != null) mRichBox.removeView(old);
        java.util.List<String> chips = com.onesay.assistant.v2.util.FollowupMarks.chips(raw);
        if (chips.isEmpty()) return;
        StringBuilder sb = new StringBuilder();
        for (String c : chips) sb.append(sb.length() > 0 ? "   " : "").append("[[").append(c).append("]]");
        TextView tv = new TextView(getContext());
        tv.setTag(TAG_FOLLOWUP_ROW);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f);
        tv.setText(withFollowups(sb.toString(), tv));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(10);
        mRichBox.addView(tv, lp);
    }

    /** 正文里有可点短语就让 TextView 接收点按（没有就不动，免得抢走滚动 / 选择手势）。 */
    private void armLinks(TextView tv, CharSequence body) {
        if (!(body instanceof android.text.Spanned)) return;
        android.text.Spanned sp = (android.text.Spanned) body;
        if (sp.getSpans(0, sp.length(), android.text.style.ClickableSpan.class).length == 0) return;
        if (!(tv.getMovementMethod() instanceof android.text.method.LinkMovementMethod)) {
            tv.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        }
    }

    /**
     * ★ audit-J 5：新一轮输入入场 —— 清掉上一轮的结果卡 / 回执正文 / 开关 / 闹钟卡 / 富内容（本轮的转写与状态行不动）。
     * 只在服务层收到新 query 时调用，不在本轮执行中调用。
     */
    public void clearPreviousRound() {
        if (mV2) chatBoundary(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.Trigger.SUBMIT);   // ★ ui-K1（已在「正在解析」处冻结过则无事）
        timelineClear();   // ★ feat/api-first：上一轮的时间轴已随这一轮冻结进对话历史
        if (mV2) {
            mV2Body = null;
            mV2Task = null;
            mV2ErrorLine = null;
            mV2Final = false;
            mAskStreaming = false;
        }
        if (mResultCard != null) mResultCard.setVisibility(GONE);
        clearToggles();
        clearAlarmCards();
        hideRich();
    }

    /** ★ LC-5：卡片在岛上时来了新请求 —— 清掉上一张卡（岛上任务）的内容，状态机走 NEW_REQUEST（→ THINKING）。 */
    public void v2BeginRequest() {
        if (!mV2) return;
        chatBoundary(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.Trigger.SUBMIT);   // ★ ui-K1
        mV2Listen = null;
        mV2Echo = null;
        mV2Body = null;
        mV2Task = null;
        mV2ErrorLine = null;
        mV2Final = false;
        mAskStreaming = false;
        mResultCard.setVisibility(GONE);
        hideConfirmCard();
        clearToggles();
        clearAlarmCards();
        if (mSearchBar != null) mSearchBar.setVisibility(GONE);
        v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.NEW_REQUEST);
    }

    /** 服务层的显式事件入口（收进岛 / 落岛 / 流回 / 收起 / 复位）。 */
    public void v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event e) {
        if (!mV2 || e == null) return;
        if (e == com.onesay.assistant.v2.ui.wake.WakeStage.Event.RESET && mChat != null) mChat.exitImmediate();   // ★ ui-K1：摘窗复位 → 回卡片排布（历史保留）
        com.onesay.assistant.v2.ui.wake.WakeStage.Transition t = mStage.on(e);
        if (t.changed()) android.util.Log.i("WakeUI", "[WAKE-STAGE] " + t);
        v2Render(t);
        if (mV2Host != null) mV2Host.onStageTransition(t);
    }

    private void v2UpdateState(String badge, String transcript) {
        if ("正在解析".equals(badge)) {   // ★ ui-K1：新一条输入（onSubmitCommand 入口）= 一轮的边界，卡片内容还没被改
            chatBoundary(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.Trigger.SUBMIT);
            if (mChat != null) mChat.onRequestStart();
        }
        com.onesay.assistant.v2.ui.wake.WakeStage.Event e = com.onesay.assistant.v2.ui.wake.WakeStage.classifyBadge(badge);
        String tr = com.onesay.assistant.v2.ui.wake.WakeCopy.transcript(transcript);
        if (e == null) {
            if (tr != null && mStage.stage() == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.LISTENING) {
                mV2Listen = tr;
                v2Render(null);
            }
            return;
        }
        switch (e) {
            case LISTEN_UPDATE:
                if (tr != null) mV2Listen = tr;
                break;
            case THINK:
                if (tr != null) mV2Echo = tr;
                if (!mAskStreaming) {
                    mV2Body = null;
                    mV2Final = false;
                }
                break;
            case TASK:
                if (tr != null) mV2Task = tr;
                break;
            case ERROR:
                mV2ErrorLine = com.onesay.assistant.v2.ui.wake.WakeCopy.errorLine(badge);
                break;
            case RESULT:
                // 没有正文时（「未能理解指令: …」「已被拦截」原因 / 「该操作未执行」），转写位的话就是结果；已有正文（问答 / 执行结果）不覆盖
                if (tr != null && !mV2Final) {
                    mV2Body = tr;
                    mV2Final = true;
                    if (mChat != null) mChat.onFinal(mV2Echo, mV2Body);   // ★ ui-K1
                }
                break;
            default:
                break;
        }
        v2Event(e);
    }

    private void v2ShowResult(ExecutionResult result) {
        mAskStreaming = false;
        clearToggles();
        clearAlarmCards();
        mConfirmBox.setVisibility(GONE);
        mVerifyBadge.setVisibility(GONE);
        // [r2/uxcompare] 失败时：原因 + 下一步（WakeCopy.failureLine），并播报这一句（受语音回复开关控制）
        mV2Body = result.ok ? com.onesay.assistant.v2.ui.wake.WakeCopy.resultLine(true, result.userMessage)
                : com.onesay.assistant.v2.ui.wake.WakeCopy.failureLine(result.code, result.userMessage);
        mV2Final = true;
        if (mChat != null) mChat.onFinal(mV2Echo, mV2Body);   // ★ ui-K1
        offerMailSettings(result.userMessage, result.code, result.capability != null ? result.capability.sign : null);
        FloatingSheetService.onResultShown("v2-执行结果");   // ★ r2/sysux P1-4
        if (result.ok) TtsSpeaker.get(getContext()).speakConfirmation(result.userMessage);
        else TtsSpeaker.get(getContext()).speakFailure(mV2Body, result.code);   // ★ r2/voice-templates：按码挑模板
        v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.RESULT);
        stickToBottomSoon();
    }

    private void v2ShowAskReply(String question, String answer) {
        if (com.onesay.assistant.v2.leakguard.LeakGuardHost.blockOutput(getContext(), "v2ShowAskReply", answer)) {   // ★ fix/leak-guard（入口已判过；直调兜底）
            showLeakBlocked();
            return;
        }
        hideConfirmCard();
        clearToggles();
        clearAlarmCards();
        mAskStreaming = false;
        if (!com.onesay.assistant.v2.ui.wake.WakeCopy.isPseudoQuestion(question)) {
            String q = com.onesay.assistant.v2.ui.wake.WakeCopy.transcript(question);
            if (q != null) mV2Echo = q;
        }
        mV2Body = answer;
        mV2Final = true;
        if (mChat != null) mChat.onFinal(mV2Echo, mV2Body);   // ★ ui-K1
        offerMailSettings(answer, null, null);
        FloatingSheetService.onResultShown("v2-问答");   // ★ r2/sysux P1-4
        TtsSpeaker.get(getContext()).speakAnswer(answer);
        v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.RESULT);
        stickToBottomSoon();
    }

    private void v2ShowConfirm(Capability cap, JSONObject args, boolean red, String note) {
        v2StyleConfirm(true, false);
        String comms = v2CommsNote(cap, args);
        mConfirmTitle.setText(com.onesay.assistant.v2.ui.wake.WakeCopy.confirmTitle(red));
        // ★ LOGIC-6：卡面显示真实实参（收件人 / 网址 host / 金额 / 短信正文 / 远程工具真实 sign + 参数）
        mConfirmDesc.setText(com.onesay.assistant.v2.ui.wake.WakeCopy.confirmBodyFor(red, cap, args, red ? null : note));
        if (red) {
            mConfirmRowYellow.setVisibility(GONE);
            mConfirmRowRed.setVisibility(VISIBLE);
        } else {
            mConfirmRowRed.setVisibility(GONE);
            mBtnAlways.setVisibility(com.onesay.assistant.v2.executor.ConsentPolicy.acceptsAlwaysAllow(cap) ? VISIBLE : GONE);   // r2/fixes：通信 / 对外发送 / 远程 MCP 都不给
            mConfirmRowYellow.setVisibility(VISIBLE);
        }
        mConfirmBox.setVisibility(VISIBLE);
        v2Event(com.onesay.assistant.v2.ui.wake.WakeStage.Event.CONFIRM);
        stickToBottomSoon();
    }

    /** 通信类能力（打电话 / 发短信）的呼叫对象说明，红黄卡都写（与旧界面同一来源）。 */
    private static String v2CommsNote(Capability c, JSONObject a) {
        return com.onesay.assistant.v2.executor.CommsRisk.cardNote(c.sign, a);
    }

    /** 确认卡新 / 旧样式（新：白 6% 底、22dp 圆角、42dp 胶囊按钮；拒绝终态灰）。 */
    private void v2StyleConfirm(boolean v2, boolean rejected) {
        if (!v2 && !rejected) {
            mConfirmTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            mConfirmTitle.getPaint().setFakeBoldText(true);
            mConfirmDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            mConfirmDesc.setTextColor(Color.parseColor("#E5E5EA"));
            mBtnCancel.setStyle(CurvedCapsuleButton.STYLE_SECONDARY);
            mBtnConfirm.setStyle(CurvedCapsuleButton.STYLE_DANGER);
            mBtnAlways.setCustomColors(Color.parseColor("#26FF9F0A"), Color.parseColor("#55FF9F0A"));
            mBtnAlways.setTextColor(Color.parseColor("#FFB340"));
            mBtnOnce.setCustomColors(Color.parseColor("#FF9F0A"), Color.parseColor("#FF9F0A"));
            mBtnOnce.setTextColor(Color.parseColor("#1C1C1E"));
            for (CurvedCapsuleButton b : new CurvedCapsuleButton[] {mBtnCancel, mBtnConfirm, mBtnAlways, mBtnOnce, mBtnReject}) b.setCornerRadiusDp(14);
            return;
        }
        mConfirmBox.setCardStyle(0x0FFFFFFF, 0);
        mConfirmBox.setCornerRadiusDp(22);
        mConfirmTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        mConfirmTitle.getPaint().setFakeBoldText(false);
        mConfirmTitle.setTextColor(rejected ? T3 : T1);
        mConfirmDesc.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f);
        mConfirmDesc.setTextColor(rejected ? T3 : T2);
        if (rejected) return;
        mBtnCancel.setCustomColors(0x16FFFFFF, 0x16FFFFFF);
        mBtnCancel.setTextColor(T1);
        mBtnConfirm.setCustomColors(0x4DFF453A, 0x4DFF453A);
        mBtnConfirm.setTextColor(0xFFFFC2BD);
        mBtnAlways.setCustomColors(0x16FFFFFF, 0x16FFFFFF);
        mBtnAlways.setTextColor(T1);
        mBtnOnce.setCustomColors(0x4D8CD27A, 0x4D8CD27A);
        mBtnOnce.setTextColor(0xFFD6EAC6);
        mBtnReject.setCustomColors(0x16FFFFFF, 0x16FFFFFF);
        mBtnReject.setTextColor(T1);
        for (CurvedCapsuleButton b : new CurvedCapsuleButton[] {mBtnCancel, mBtnConfirm, mBtnAlways, mBtnOnce, mBtnReject}) b.setCornerRadiusDp(21);
    }

    /** 按当前状态摆内容（文字 / 小球 / 正文 / 内发光目标），再执行转移带来的卡片效果。t 可空（只刷内容）。 */
    private void v2Render(com.onesay.assistant.v2.ui.wake.WakeStage.Transition t) {
        com.onesay.assistant.v2.ui.wake.WakeStage.Stage st = mStage.stage();
        boolean listening = st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.LISTENING;
        boolean error = st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.ERROR;
        CharSequence oldTr = mTranscriptText.getText();
        if (listening) {
            mTranscriptText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
            mTranscriptText.setTextColor(mV2Listen != null ? T1 : T3);
            mTranscriptText.setText(mV2Listen != null ? mV2Listen : com.onesay.assistant.v2.ui.wake.WakeCopy.LISTEN_HINT);
            mTranscriptText.setPadding(0, 0, 0, 0);
            mPromptCard.setVisibility(VISIBLE);
        } else if (error) {
            mTranscriptText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 21f);
            mTranscriptText.setTextColor(T2);
            mTranscriptText.setText(mV2ErrorLine != null ? mV2ErrorLine : com.onesay.assistant.v2.ui.wake.WakeCopy.NO_SPEECH_LINE);
            mTranscriptText.setPadding(0, 0, dp(36), 0);
            mPromptCard.setVisibility(VISIBLE);
        } else {
            mTranscriptText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            mTranscriptText.setTextColor(T3);
            mTranscriptText.setPadding(0, 0, dp(36), 0);
            String echo = mV2Echo != null ? mV2Echo : mV2Listen;
            if (echo != null) {
                mTranscriptText.setText(echo);
                mPromptCard.setVisibility(VISIBLE);
            } else {
                mTranscriptText.setText("");
                mPromptCard.setVisibility(st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.TEXT ? GONE : GONE);
            }
        }
        boolean chat = chatActive();   // ★ ui-K1：对话窗里不要小球 / 图标行 / 旧输入条（换成底栏）
        if (mOrb != null) {
            mOrb.setVisibility(listening && !chat ? VISIBLE : GONE);
            mOrb.setActive(listening && !chat, mV2Instant);
        }
        if (chat && mSearchBar.getVisibility() != GONE) mSearchBar.setVisibility(GONE);
        if (mV2IconRow != null) {
            int vis = chat ? GONE : v2IconRowVisibility(st);
            if (com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.cardBarVisible(st, mV2, chat)) vis = GONE;   // 出结果后换成新版底栏
            if (mV2IconRow.getVisibility() != vis) mV2IconRow.setVisibility(vis);
            if (mV2BtnMic != null) mV2BtnMic.setActive(com.onesay.assistant.v2.ui.wake.MicButtonPolicy.active(st));
        }
        if (!chat) v2RefreshCardBar(st);
        // 正文
        // ★ lane C：正文裁决搬到 WindowFlowPolicy.body（纯函数、有单测）。修了「生成完成后点 ⌨，上一条回答被刷掉」：
        //   旧 switch 没有 TEXT 分支 → 结果卡 GONE。
        CharSequence body = null;
        boolean richFinal = false;   // [richout]
        switch (com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.body(st, mV2Body != null, mV2Final)) {
            case THINKING_PLACEHOLDER:
                body = v2ProgressBody();   // ★ 11-04 午 进度反馈：「思考中 · 3 s」/「执行中：设闹钟 3:53 · 1 s」（≤1 s 上屏，每秒刷新）
                break;
            case STREAM: {
                final boolean chatF = chat;
                // 同一段文本的排版结果复用（进度 / 阶段 / 芯片触发的重排不再重做 Markdown）
                body = mBodyMemo.get(mV2Body, chat ? 11 : 10, new com.onesay.assistant.v2.ui.wake.BodyMemo.Maker<CharSequence>() {
                    @Override public CharSequence make(String text) {
                        text = com.onesay.assistant.v2.util.FollowupMarks.streaming(text);   // ★ feat/api-first：没写完的 [[ 先不上屏
                        CharSequence b = richStream(com.onesay.assistant.v2.util.FollowupMarks.plain(text));   // [richout] 插件关 / 纯文本 → null → 原排版
                        if (b == null) b = chatF ? mChat.answerBody(text) : v2Body(com.onesay.assistant.v2.util.FollowupMarks.display(text), false);   // ★ ui-K1：对话窗正文（简单 Markdown）
                        return b;
                    }
                });
                break;
            }
            case FINAL:
                if (richWanted(mV2Body)) {
                    richFinal = true;
                    body = mV2Body;   // 只用于「变没变」比较；真正上屏的是 mRichBox
                } else {
                    final boolean chatF = chat;
                    body = mBodyMemo.get(mV2Body, chat ? 21 : 20, new com.onesay.assistant.v2.ui.wake.BodyMemo.Maker<CharSequence>() {
                        @Override public CharSequence make(String text) {
                            return chatF ? mChat.answerBody(text) : withFollowups(v2Body(text, true), null);   // ★ feat/api-first：可点追问
                        }
                    });
                }
                break;
            case TASK:
                body = v2Task();
                break;
            default:
                break;
        }
        v2ApplyFull(com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.cardFull(st));
        if (!chat) v2PinBar(com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.pinBarBottom(st, mV2, mV2FullWindow, mChat != null && mChat.shownOnCard()));
        if (chat) v2TrackIme(com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.trackIme(st, true, mChat.bar().typingFocus()));   // ★ ui-K1（★ 11-05：被动获焦不算）
        else if (mChat != null) v2TrackIme(com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.trackIme(st, mChat.shownOnCard(),
                mChat.bar().typingFocus()));   // 卡片上打字也用同一个底栏输入框
        else v2TrackIme(com.onesay.assistant.v2.ui.wake.WindowFlowPolicy.trackIme(st, mSearchBar.getVisibility() == VISIBLE,
                mSearchBar.getInputEdit().hasFocus()));   // ★ r2/input：结果页直接点输入框也让位（LC-2 复发根因）
        boolean bodyChanged = body == null ? mV2ShownBody != null : !body.toString().equals(mV2ShownBody == null ? null : mV2ShownBody.toString());
        if (body != null) {
            if (richFinal) {
                showRichFinal(mV2Body);   // [richout]
            } else {
                hideRich();
                // 正文没变就不 setText（每次 setText 都整段重新测量 / 排版 / requestLayout）
                if (bodyChanged || body != mLastSetBody || mResultText.getVisibility() != VISIBLE) {
                    mResultText.setText(body);
                    armLinks(mResultText, body);   // ★ feat/api-first：可点追问短语
                    mLastSetBody = body;
                }
                mResultText.setVisibility(VISIBLE);
            }
            mResultCard.setVisibility(VISIBLE);
        } else {
            hideRich();   // [richout]
            mResultCard.setVisibility(st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.RESULT && mToggleContainer.getVisibility() == VISIBLE ? VISIBLE : GONE);
            if (mResultCard.getVisibility() == VISIBLE) mResultText.setVisibility(GONE);
        }
        mV2ShownBody = body;
        v2ProgressTrack(st);   // ★ 11-04 午 进度反馈：思考 / 任务阶段计时 + 呼吸动画
        if (chat) mChat.styleLive();   // ★ ui-K1：问句气泡 / 页脚 / 底栏录音态
        // 内发光 / 外晕目标（v2.4）
        mInner.t = com.onesay.assistant.v2.ui.wake.WakeStage.innerGlow(st);
        mHalo.t = (st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.THINKING || st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.ANSWERING) ? 0.17f
                : (st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.RESULT || st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.TASK
                || st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.CONFIRM) ? 0.08f : 0f;
        if (st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.THINKING || st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.ANSWERING) {
            removeCallbacks(mInnerSettle);
            if (t != null && t.changed()) postDelayed(mInnerSettle, 2000L);
        }
        if (mV2Instant) {
            mInner.snap();
            mHalo.snap();
        }
        mV2LastFrame = 0L;
        postInvalidateOnAnimation();
        if (t == null) return;
        float dens = getResources().getDisplayMetrics().density;
        if (t.has(com.onesay.assistant.v2.ui.wake.WakeStage.Effect.CONTENT_IN)) {
            if (oldTr == null || !oldTr.toString().equals(String.valueOf(mTranscriptText.getText()))) {
                com.onesay.assistant.v2.ui.motion.ViewSpring.textIn(mPromptCard, dens, 0, mV2Instant);
            }
            if (bodyChanged && body != null) com.onesay.assistant.v2.ui.motion.ViewSpring.textIn(mResultCard, dens, 40, mV2Instant);
        }
        if (t.has(com.onesay.assistant.v2.ui.wake.WakeStage.Effect.CARD_PRESS) && !mV2Instant) {
            setPivotX(getWidth() / 2f);
            setPivotY(getHeight());
            com.onesay.assistant.v2.ui.motion.ViewSpring.animate(this, com.onesay.assistant.v2.ui.motion.MotionSpec.PRESS, 0, 1f, 1f, 0f, 0f, 0.97f, 1f, false, null);
        }
    }

    // ------------------------------------------------------------------ [richout] 富内容回答

    /** 插件开 + 回答有 Markdown 特征（代码块 / 列表 / 表格 / 链接…）才走富渲染；纯文本一律 false（不建任何 View / WebView）。 */
    private static boolean richWanted(String text) {
        if (text == null) return false;
        if (com.onesay.assistant.plug.richout.host.RichOutHost.tableGranted(text)) return true;   // ★ 富内容关、用户明确要表格：只放行原生表格卡
        if (com.onesay.assistant.v2.router.MarketChart.match(text, System.currentTimeMillis()) != null) return true;   // ★ 11-04：行情结论带指数图
        if (com.onesay.assistant.v2.router.ResearchVisuals.match(text, System.currentTimeMillis()) != null) return true;   // ★ 11-04 ④：调研 / 购物结论配图
        return com.onesay.assistant.plug.richout.host.RichOutHost.on()
                && com.onesay.assistant.plug.richout.core.RichText.needsRich(text);
    }

    private com.onesay.assistant.plug.richout.host.RichRenderer.Style richStyle() {
        com.onesay.assistant.plug.richout.host.RichRenderer.Style st = new com.onesay.assistant.plug.richout.host.RichRenderer.Style();
        st.t1 = T1;
        st.t2 = T2;
        st.t3 = T3;
        return st;
    }

    /** 流式中的近似样式（单个 TextView，不开 WebView）；不需要富渲染时返回 null。 */
    private CharSequence richStream(String text) {
        if (!richWanted(text)) return null;
        try {
            return com.onesay.assistant.plug.richout.host.RichRenderer.streaming(getContext(), com.onesay.assistant.v2.ui.wake.WakeCopy.cleanBody(text), richStyle());
        } catch (Throwable t) {
            android.util.Log.w("FloatingSheetView", "[RICHOUT] 流式富渲染失败，回落纯文本: " + t);
            return null;
        }
    }

    /** 终稿：需要富渲染 → 画进 mRichBox、隐藏 mResultText，返回 true；否则收掉 mRichBox 返回 false（调用方照旧显示纯文本）。 */
    private boolean showRichFinal(String text) {
        if (!richWanted(text)) {
            hideRich();
            return false;
        }
        final String rawWithMarks = text;
        text = com.onesay.assistant.v2.util.FollowupMarks.plain(text);   // ★ feat/api-first：富渲染里不出现 [[ ]]，可点短语另起一行挂在末尾
        try {
            com.onesay.assistant.v2.router.MarketChart.Model chart = com.onesay.assistant.v2.router.MarketChart.match(text, System.currentTimeMillis());
            com.onesay.assistant.v2.router.ResearchVisuals.Model vis = chart != null ? null : com.onesay.assistant.v2.router.ResearchVisuals.match(text, System.currentTimeMillis());
            if (vis != null && (!text.equals(mRichSrc) || mRichBox.getChildCount() == 0)) {
                // ★ 11-04 ④：代表款缩略图（公网 https，内存解码，失败隐藏，不落盘）+ 价格条形图 + 要点文字
                mRichBox.removeAllViews();
                if (!vis.images.isEmpty()) {
                    LinearLayout row = new LinearLayout(getContext());
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    for (String url : vis.images) {
                        android.widget.ImageView iv = new android.widget.ImageView(getContext());
                        iv.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                        iv.setVisibility(GONE);
                        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(dp(96), dp(96));
                        ilp.rightMargin = dp(8);
                        row.addView(iv, ilp);
                        ThumbLoader.load(url, iv);
                    }
                    mRichBox.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                }
                if (vis.hasChart()) {
                    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                    clp.topMargin = dp(8);
                    mRichBox.addView(new PriceBarView(getContext(), vis, T1, T2, T3), clp);
                }
                TextView tv = new TextView(getContext());
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                tv.setLineSpacing(dp(3), 1.25f);
                tv.setText(com.onesay.assistant.v2.ui.wake.WakeCopy.cleanBody(text));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = dp(10);
                mRichBox.addView(tv, tlp);
                android.util.Log.i("FloatingSheetView", "[VISUALS] 画图 条 " + vis.bars.size() + "、图 " + vis.images.size());
                mRichSrc = text;
            } else if (chart != null && (!text.equals(mRichSrc) || mRichBox.getChildCount() == 0)) {
                // ★ 11-04（用户 00:13）：行情结论 = 原生 Canvas 指数图（MarketChartView）+ 板块与原因文字；不建 WebView、不联网
                mRichBox.removeAllViews();
                mRichBox.addView(new MarketChartView(getContext(), chart, T1, T2, T3),
                        new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
                TextView tv = new TextView(getContext());
                tv.setTextColor(Color.WHITE);
                tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f);
                tv.setLineSpacing(dp(3), 1.25f);
                // ★ 11-04（用户）：正文首段用普通正文样式（不走 splitHeadline 的 24sp 大标题），段间留半行
                String plain = com.onesay.assistant.v2.ui.wake.WakeCopy.cleanBody(text);
                tv.setText(com.onesay.assistant.v2.ui.wake.WakeCopy.readableParagraphs(plain).replace("\n", "\n\n").replace("\n\n\n\n", "\n\n"));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                tlp.topMargin = dp(10);
                mRichBox.addView(tv, tlp);
                android.util.Log.i("FloatingSheetView", "[MARKET-CHART] 画图 " + chart.barCount() + " 条");
                mRichSrc = text;
            } else if (chart == null && vis == null && (!text.equals(mRichSrc) || mRichBox.getChildCount() == 0)) {
                mRichBox.removeAllViews();   // 旧图卡离开窗口 → 各自销毁 WebView
                String body = com.onesay.assistant.v2.ui.wake.WakeCopy.cleanBody(text);
                mRichBox.addView(com.onesay.assistant.plug.richout.host.RichOutHost.on()
                        ? com.onesay.assistant.plug.richout.host.RichRenderer.render(getContext(), body, richStyle())
                        : com.onesay.assistant.plug.richout.host.RichRenderer.renderTablesOnly(getContext(), body, richStyle()));   // ★ 表格轮：只画表格（纯文本单元格、无 WebView）
                mRichSrc = text;
            }
            addFollowupRow(rawWithMarks);
            mResultText.setVisibility(GONE);
            mRichBox.setVisibility(VISIBLE);
            return true;
        } catch (Throwable t) {
            android.util.Log.w("FloatingSheetView", "[RICHOUT] 富渲染失败，回落纯文本: " + t);
            hideRich();
            mResultText.setVisibility(VISIBLE);
            mResultText.setText(com.onesay.assistant.plug.richout.core.RichText.plainFallback(text));
            mLastSetBody = null;   // 别处改了正文 → v2Render 下次必须重设
            return false;
        }
    }

    private void hideRich() {
        if (mRichBox == null) return;
        if (mRichBox.getChildCount() > 0) mRichBox.removeAllViews();
        if (mRichBox.getVisibility() != GONE) mRichBox.setVisibility(GONE);
        mRichSrc = null;
    }

    /** 正文排版：首行 24sp（Note 8 的中文字体没有 Light，按 D12 回退 Regular 并把 26sp 降到 24sp）+ 其余 16.5sp T2。 */
    private CharSequence v2Body(String text, boolean split) {
        String t = com.onesay.assistant.v2.ui.wake.WakeCopy.cleanBody(text);
        if (t.length() == 0) return v2Muted(com.onesay.assistant.v2.ui.wake.WakeCopy.THINKING);
        String[] hb = com.onesay.assistant.v2.ui.wake.WakeCopy.splitHeadline(t);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (hb[0] != null) {
            int s0 = sb.length();
            sb.append(hb[0]);
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(24, true), s0, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.ForegroundColorSpan(T1), s0, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        if (hb[1] != null) {
            if (sb.length() > 0) v2ParaGap(sb);
            // ★ r2/voice-templates：答案不再读出来 → 长正文按句拆成短段落、段间留半行（只动空白，不改字）
            String[] paras = (split ? com.onesay.assistant.v2.ui.wake.WakeCopy.readableParagraphs(hb[1]) : hb[1]).split("\n");
            int s1 = sb.length();
            for (int i = 0; i < paras.length; i++) {
                if (i > 0) {
                    if (split) v2ParaGap(sb);
                    else sb.append("\n");
                }
                sb.append(paras[i]);
            }
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(hb[0] == null ? 18 : 16, true), s1, sb.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.ForegroundColorSpan(hb[0] == null ? T1 : T2), s1, sb.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            v2ShrinkGaps(sb);
        }
        return sb;
    }

    /** 段间距：换行 + 一个只有换行的小字号空行（约半行高）。 */
    private static final String PARA_GAP = "\n\u200B\n";

    private static void v2ParaGap(android.text.SpannableStringBuilder sb) {
        sb.append(PARA_GAP);
    }

    /** 把所有段间空行缩成 8sp 高（外层 16/18sp 的尺寸 span 盖不住内层更小的 span：后设的同类 span 优先）。 */
    private static void v2ShrinkGaps(android.text.SpannableStringBuilder sb) {
        String str = sb.toString();
        int from = 0;
        while (true) {
            int i = str.indexOf(PARA_GAP, from);
            if (i < 0) break;
            sb.setSpan(new android.text.style.AbsoluteSizeSpan(8, true), i + 1, i + PARA_GAP.length(),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = i + PARA_GAP.length();
        }
    }

    // ------------------------------------------------------------------ ★ 11-04 午 进度反馈（思考中 / 执行中：步骤 · 计时 · 第几步）

    /** 本次请求受理时刻（0 = 不在思考 / 任务阶段）。 */
    private long mProgT0;
    private boolean mProgThinking = true;
    private String mProgStep;
    private long mProgAt;
    private int mProgN;
    private android.animation.ObjectAnimator mBreath;
    private final Runnable mProgTick = new Runnable() {
        @Override public void run() {
            v2ProgressTickNow();
        }
    };

    /** 循环线程：进入 / 离开「思考中」（等模型）。 */
    public void progressThinking(final boolean on) {
        post(new Runnable() {
            @Override public void run() {
                if (mProgThinking == on) return;
                mProgThinking = on;
                if (on) mProgAt = System.currentTimeMillis();
                v2ProgressTickNow();
            }
        });
    }

    /** 任意线程：开始执行真实的一步（「读取：什么值得买」「设闹钟 3:53」）。 */
    public void progressStep(final String step) {
        if (step == null || step.trim().length() == 0) return;
        post(new Runnable() {
            @Override public void run() {
                mProgThinking = false;
                mProgStep = step.trim();
                mProgN++;
                mProgAt = System.currentTimeMillis();
                v2ProgressTickNow();
            }
        });
    }

    String v2ProgressLine() {
        long now = System.currentTimeMillis();
        com.onesay.assistant.v2.island.ProgressFeedback.Phase ph = mProgThinking
                ? com.onesay.assistant.v2.island.ProgressFeedback.Phase.THINKING : com.onesay.assistant.v2.island.ProgressFeedback.Phase.WORKING;
        String line = com.onesay.assistant.v2.island.ProgressFeedback.line(ph, mProgStep, now - (mProgAt > 0 ? mProgAt : mProgT0));
        return line + "  ·  " + com.onesay.assistant.v2.island.ProgressFeedback.meta(mProgN, 0, now - mProgT0);
    }

    private CharSequence v2ProgressBody() {
        if (mProgT0 <= 0) {
            mProgT0 = System.currentTimeMillis();
            mProgAt = mProgT0;
        }
        return v2Muted(v2ProgressLine());
    }

    /** v2Render 末尾：思考 / 任务阶段 → 计时开着；其余阶段 → 停表、清状态。 */
    private void v2ProgressTrack(com.onesay.assistant.v2.ui.wake.WakeStage.Stage st) {
        boolean live = st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.THINKING || st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.TASK;
        if (live) {
            if (mProgT0 <= 0) {
                mProgT0 = System.currentTimeMillis();
                mProgAt = mProgT0;
            }
            removeCallbacks(mProgTick);
            postDelayed(mProgTick, 1000L);
            v2Breath(mProgThinking && st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.THINKING);
        } else {
            removeCallbacks(mProgTick);
            v2Breath(false);
            mProgT0 = 0L;
            mProgAt = 0L;
            mProgThinking = true;
            mProgStep = null;
            mProgN = 0;
        }
    }

    /** 每秒：只改正文那一行（不重排卡片、不触发入场动画）。 */
    private void v2ProgressTickNow() {
        removeCallbacks(mProgTick);
        com.onesay.assistant.v2.ui.wake.WakeStage.Stage st = mStage.stage();
        if (mProgT0 <= 0) return;
        if (st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.THINKING) {
            CharSequence b = v2Muted(v2ProgressLine());
            mResultText.setText(b);
            mLastSetBody = null;   // 别处改了正文 → v2Render 下次必须重设
            mV2ShownBody = b;
            v2Breath(mProgThinking);
        } else if (st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.TASK) {
            CharSequence b = v2Task();
            mResultText.setText(b);
            mLastSetBody = null;   // 别处改了正文 → v2Render 下次必须重设
            mV2ShownBody = b;
            v2Breath(false);
        } else {
            return;
        }
        postDelayed(mProgTick, 1000L);
    }

    /** 思考中：正文呼吸（透明度 1 ↔ 0.45，700 ms 往返）；执行中 / 其它：恒定。原生属性动画，无新依赖。 */
    private void v2Breath(boolean on) {
        if (on) {
            if (mBreath != null && mBreath.isRunning()) return;
            mBreath = android.animation.ObjectAnimator.ofFloat(mResultText, "alpha", 1f, 0.45f);
            mBreath.setDuration(700L);
            mBreath.setRepeatMode(android.animation.ValueAnimator.REVERSE);
            mBreath.setRepeatCount(android.animation.ValueAnimator.INFINITE);
            mBreath.start();
        } else {
            if (mBreath != null) {
                mBreath.cancel();
                mBreath = null;
            }
            mResultText.setAlpha(1f);
        }
    }

    private CharSequence v2Muted(String s) {
        android.text.SpannableString sp = new android.text.SpannableString(s);
        sp.setSpan(new android.text.style.AbsoluteSizeSpan(18, true), 0, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sp.setSpan(new android.text.style.ForegroundColorSpan(T3), 0, s.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sp;
    }

    private CharSequence v2Task() {
        String head = "好，我来处理";
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(head);
        sb.setSpan(new android.text.style.AbsoluteSizeSpan(21, true), 0, head.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.ForegroundColorSpan(T1), 0, head.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        String sub = mProgT0 > 0 ? v2ProgressLine() : mV2Task != null ? mV2Task : "做完在顶部告诉你";   // ★ 11-04 午：真实步骤 + 计时
        sb.append("\n");
        int s1 = sb.length();
        sb.append(sub);
        sb.setSpan(new android.text.style.AbsoluteSizeSpan(14, true), s1, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new android.text.style.ForegroundColorSpan(T3), s1, sb.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    /** CARD_IN：y+40dp→0、scale 0.94→1、alpha 0→1（bounce 0.18）。 */
    public void v2CardIn() {
        setVisibility(VISIBLE);
        setPivotX(getWidth() > 0 ? getWidth() / 2f : 0f);
        setPivotY(getHeight() > 0 ? getHeight() : 0f);
        com.onesay.assistant.v2.ui.motion.ViewSpring.animate(this, com.onesay.assistant.v2.ui.motion.MotionSpec.CARD_IN, 0, 0f, 1f, dpf(40), 0f, 0.94f, 1f,
                mV2Instant, null);
    }

    /** DISMISS：沉 24dp + 缩到 0.96 + 淡出（不弹），结束后回调（服务摘窗）。 */
    public void v2Dismiss(final Runnable onComplete) {
        if (chatActive()) {   // ★ ui-K1：对话窗先反向收起（底板缩回卡片、内容淡出），收完直接交给服务摘窗
            mChat.collapse(new Runnable() {
                @Override
                public void run() {
                    setVisibility(GONE);
                    setTranslationY(0f);
                    setScaleX(1f);
                    setScaleY(1f);
                    if (mChat != null) mChat.exitImmediate();
                    setAlpha(1f);
                    if (onComplete != null) onComplete.run();
                }
            });
            return;
        }
        setPivotX(getWidth() / 2f);
        setPivotY(getHeight());
        float a0 = getAlpha();
        com.onesay.assistant.v2.ui.motion.ViewSpring.animate(this, com.onesay.assistant.v2.ui.motion.MotionSpec.DISMISS, 0, a0, 0f, getTranslationY(), dpf(24),
                getScaleX(), 0.96f, mV2Instant, new Runnable() {
                    @Override
                    public void run() {
                        setVisibility(GONE);
                        setTranslationY(0f);
                        setScaleX(1f);
                        setScaleY(1f);
                        setAlpha(1f);
                        if (onComplete != null) onComplete.run();
                    }
                });
    }

    /** 回程：卡片窗已挂上但先透明，舞台流到位后再显示。 */
    public void v2HoldInvisible() {
        com.onesay.assistant.v2.ui.motion.ViewSpring.cancel(this);
        setVisibility(VISIBLE);
        setAlpha(0f);
        setTranslationY(0f);
        setScaleX(1f);
        setScaleY(1f);
    }

    public void v2RevealAfterUnmorph() {
        com.onesay.assistant.v2.ui.motion.ViewSpring.cancel(this);
        setAlpha(1f);
        setTranslationY(0f);
        setScaleX(1f);
        setScaleY(1f);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (!mV2) return;
        if (mV2FullWindow) return;   // ★ lane C：全屏窗的卡片上沿由 onLayout 按内容定（窗口尺寸只在键盘弹收时变）
        // CARD_RESIZE：窗口是 WRAP_CONTENT、贴底 —— 内容变多时窗口上沿瞬间上移，卡片背景的上沿从「原来的屏幕位置」弹到新位置，
        //   子 View 按卡片矩形裁切 → 看起来是卡片被内容撑开，而不是一帧跳高
        if (oldh > 0 && h > oldh && !mV2Instant && getAlpha() > 0.01f) {
            mBgTop.v = mBgTop.v + (h - oldh);
            mBgTop.t = v2Gutter();
            com.onesay.assistant.v2.ui.motion.MotionSpec.CARD_RESIZE.applyTo(mBgTop);
        } else {
            mBgTop.t = v2Gutter();
            mBgTop.snap();
        }
        mV2LastFrame = 0L;
        postInvalidateOnAnimation();
    }

    /**
     * ★ R14.1 卡顿：入场 / 收起时整张卡在做 alpha 动画，HWUI 会把 alpha&lt;1 且「有重叠绘制」的 View 提升成离屏层，
     * 而卡里的光晕 / 波形每帧都在变 → 每帧先画进 1080×~420 的层再合成（trace 里的「Issue HW Layer DisplayList FloatingSheetView」）。
     * 新界面卡片的子 View 都在卡片底色之上、彼此不叠，按绘制操作逐个乘 alpha 的观感差别只在淡入那 200 ms 里，换掉这层离屏。
     */
    @Override
    public boolean hasOverlappingRendering() {
        return !mV2;
    }

    @Override
    protected void dispatchDraw(android.graphics.Canvas c) {
        if (!mV2) {
            super.dispatchDraw(c);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        float dt = mV2LastFrame == 0L ? 0.016f : Math.min(0.033f, (now - mV2LastFrame) / 1000f);
        mV2LastFrame = now;
        mBgTop.step(dt);
        mInner.step(dt);
        mHalo.step(dt);
        float top = Math.max(0f, Math.min(mBgTop.v, getHeight() - v2CardBottomGap() - dpf(40)));
        mCardRect.set(v2Side(), top, getWidth() - v2Side(), getHeight() - v2CardBottomGap());
        float chatT = mChat != null ? mChat.progress() : 0f;
        if (chatT > 0f) {   // ★ ui-K1：对话窗形变（同一张卡：矩形 → 全屏、圆角 → 0、苔绿卡底 → 深色）
            chatDraw(c, chatT);
            boolean movingC = !(mBgTop.settled(0.5f) && mInner.settled(0.003f) && mHalo.settled(0.003f));
            if (movingC) postInvalidateOnAnimation();
            return;
        }
        if (mPainter != null) mPainter.draw(c, mCardRect, 1f, mInner.v, mHalo.v, com.onesay.assistant.v2.ui.wake.WakeCardPainter.MOSS);
        c.save();
        c.clipRect(mCardRect);
        super.dispatchDraw(c);
        c.restore();
        if (com.onesay.assistant.v2.ui.wake.WakeStage.closeOn(mStage.stage())) {
            float r = dpf(15);
            float cx = mCardRect.right - dpf(14) - r, cy = mCardRect.top + dpf(14) + r;
            mCloseRect.set(cx - r - dpf(8), cy - r - dpf(8), cx + r + dpf(8), cy + r + dpf(8));
            c.drawCircle(cx, cy, r, mCloseBg);
            float k = dpf(4.5f);
            c.drawLine(cx - k, cy - k, cx + k, cy + k, mCloseX);
            c.drawLine(cx - k, cy + k, cx + k, cy - k, mCloseX);
        } else {
            mCloseRect.setEmpty();
        }
        boolean moving = !(mBgTop.settled(0.5f) && mInner.settled(0.003f) && mHalo.settled(0.003f));
        if (moving) postInvalidateOnAnimation();
    }

    @Override
    public boolean dispatchTouchEvent(android.view.MotionEvent ev) {
        if (!mV2) return super.dispatchTouchEvent(ev);
        int a = ev.getActionMasked();
        float x = ev.getX(), y = ev.getY();
        if (chatActive()) {   // ★ ui-K1：顶部拖拽条 —— 下拉或点按 = 收起对话窗
            if (a == android.view.MotionEvent.ACTION_DOWN && y < dpf(34)) {
                mChatHandleDown = true;
                mChatHandleY = y;
                return true;
            }
            if (mChatHandleDown) {
                if (a == android.view.MotionEvent.ACTION_UP) {
                    mChatHandleDown = false;
                    float dy = y - mChatHandleY;
                    if ((dy > dpf(56) || Math.abs(dy) < dpf(8)) && mListener != null) mListener.onDismiss();
                } else if (a == android.view.MotionEvent.ACTION_MOVE) {
                    float dy = Math.max(0f, y - mChatHandleY);
                    setTranslationY(Math.min(dy * 0.5f, dpf(80)));   // 跟手一点点
                    return true;
                } else if (a == android.view.MotionEvent.ACTION_CANCEL) {
                    mChatHandleDown = false;
                }
                if (a != android.view.MotionEvent.ACTION_MOVE) setTranslationY(0f);
                return true;
            }
            return super.dispatchTouchEvent(ev);
        }
        if (a == android.view.MotionEvent.ACTION_DOWN) {
            mCloseDown = !mCloseRect.isEmpty() && mCloseRect.contains(x, y);
            if (mCloseDown) return true;
            if (!mCardRect.isEmpty() && !mCardRect.contains(x, y)) {
                // 卡片四周的透明留白（阴影 / 外晕区）也在窗口里 —— 点在这里等同点外部
                if (mV2Host != null) mV2Host.onOutsideTapV2();
                return true;
            }
        } else if (mCloseDown) {
            if (a == android.view.MotionEvent.ACTION_UP) {
                mCloseDown = false;
                if (mCloseRect.contains(x, y) && mListener != null) mListener.onDismiss();
            } else if (a == android.view.MotionEvent.ACTION_CANCEL) {
                mCloseDown = false;
            }
            return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    // ------------------------------------------------------------------ ★ ui-K1 对话窗挂接点

    /** 对话窗开着时再次唤醒 —— 收键盘、退出打字态，让底栏立刻进「聆听中…」。 */
    public void chatPrepareRewake() {
        if (!chatActive()) return;
        hideSoftKeyboard();
        if (mChat != null) mChat.clearInputFocus();
    }

    /** 清对话窗历史，下次唤醒照第一次弹原卡。 */
    public void chatEndSession() {
        if (mChat != null) mChat.endSession();
    }

    /** 对话窗开着（或正在形变）。 */
    public boolean chatActive() {
        return mChat != null && mChat.active();
    }

    /** 服务侧能力（圈选 / 相机对话 / 选图 / 分享）。 */
    public void setChatServiceHost(com.onesay.assistant.v2.ui.chat.ChatWindowController.ServiceHost h) {
        mChatSvc = h;
        if (mChat != null) mChat.setServiceHost(h);
    }

    /** 服务登记本轮路由（详情 i 里显示）。 */
    public void chatNoteRoute(String route) {
        if (mChat != null) mChat.noteRoute(route);
    }

    /** 当前屏幕截图回来了。 */
    public void chatOnScreenCaptured(String path, android.graphics.Bitmap thumb) {
        if (mChat != null) mChat.onScreenCaptured(path, thumb);
        else if (path != null) try { new java.io.File(path).delete(); } catch (Throwable ignore) { }
    }

    /** 选图 / 拍照回来了。 */
    public void chatOnImagePicked(String path, android.graphics.Bitmap thumb) {
        if (mChat != null) mChat.onImagePicked(path, thumb);
    }

    /** 一轮的边界：冻结上一轮、决定是否展开（新界面 + 全屏窗才有对话窗）。 */
    private void chatBoundary(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.Trigger t) {
        if (!mV2 || mChat == null) return;
        try {
            boolean on = mChat.boundary(t);
            if (on && mChat.consumeFroze()) {
                // 屏上这一轮已进历史：卡片上的同一轮清掉（否则「接着说」时历史和卡片各显示一遍）
                mV2Body = null;
                mV2Final = false;
                mV2Echo = null;
                mV2Listen = null;
                mV2Task = null;
                mV2ErrorLine = null;
                mAskStreaming = false;
                mV2ShownBody = null;
                mResultCard.setVisibility(GONE);
                clearToggles();
                clearAlarmCards();
                hideRich();
                if (mConfirmDecided) hideConfirmCard();
            }
        } catch (Throwable e) {
            android.util.Log.w("WakeUI", "[CHAT] 边界处理异常: " + e);
        }
    }

    private void ensureChat() {
        if (mChat != null) return;
        com.onesay.assistant.v2.ui.chat.ChatWindowController.Parts p = new com.onesay.assistant.v2.ui.chat.ChatWindowController.Parts();
        p.root = this;
        p.contentInner = mContentInner;
        p.contentScroll = mContentScroll;
        p.promptCard = mPromptCard;
        p.transcript = mTranscriptText;
        p.resultCard = mResultCard;
        p.resultText = mResultText;
        p.richBox = mRichBox;
        p.alarmBox = mAlarmBox;
        p.toggleBox = mToggleContainer;
        p.confirmBox = mConfirmBox;
        mChat = new com.onesay.assistant.v2.ui.chat.ChatWindowController(getContext(), p, new com.onesay.assistant.v2.ui.chat.ChatWindowController.ViewHost() {
            @Override public com.onesay.assistant.v2.ui.wake.WakeStage.Stage stage() { return mStage.stage(); }
            @Override public boolean instant() { return mV2Instant; }
            @Override public boolean fullWindow() { return mV2FullWindow; }
            @Override public boolean panelAttached() {
                // 这次唤醒才挂上的窗不算「面板还在」（会话是否还在交给会话记忆判断）
                return getParent() != null && getVisibility() == VISIBLE
                        && android.os.SystemClock.uptimeMillis() - mChatAttachedAt > 800L;
            }
            @Override public boolean hasAnswer() { return mV2Final || mV2Body != null; }
            @Override public void onChatProgress(float t) {
                mV2LastFrame = 0L;
                postInvalidateOnAnimation();
            }
            @Override public void onChatActive(boolean on) {
                if (mV2IconRow != null) mV2IconRow.setVisibility(on ? GONE : v2IconRowVisibility(mStage.stage()));
                if (!on) v2RefreshCardBar(mStage.stage());   // 回到卡片排布 → 按阶段重新挂结果态底栏
                if (on) {
                    mSearchBar.setVisibility(GONE);
                    if (mOrb != null) mOrb.setVisibility(GONE);
                }
                requestLayout();
                postInvalidateOnAnimation();
            }
            @Override public void submit(String text) {
                if (mListener != null) mListener.onSubmitCommand(text);
            }
            @Override public void requestTyping() {
                if (mListener != null) mListener.onTextModeRequested();
                showSoftKeyboard();
                v2TrackIme(true);
            }
            @Override public void micTap() {
                if (mListener == null) return;
                // 对话窗底栏麦克风 = 录音开关；停止录音留在对话窗（收起只有拖拽条 / 返回键）
                switch (com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.micTap(mStage.stage(), com.onesay.assistant.v2.wake.WakeService.canFinishCapture())) {
                    case START: hideSoftKeyboard(); switchToVoiceMode(); mListener.onVoiceModeRequested(); break;
                    case FINISH: mListener.onFinishListening(); break;
                    case STOP_STAY: mListener.onStopListening(); break;
                    default: break;
                }
            }
            @Override public void trackIme(boolean on) {
                if (on || mStage.stage() != com.onesay.assistant.v2.ui.wake.WakeStage.Stage.TEXT) v2TrackIme(on);
            }
            @Override public String liveQuestion() { return mV2Echo; }
            @Override public String liveAnswer() { return mV2Body; }
            @Override public java.util.List<View> pluginCards() {
                java.util.List<View> l = new java.util.ArrayList<View>();
                for (int i = 0; i < getChildCount(); i++) {
                    View c = getChildAt(i);
                    if (c == mHeaderRow || c == mChipsRow || c == mContentScroll || c == mSearchBar || c == mSuggestionScroll
                            || c == mV2IconRow || c == mChat.bar() || ActionChipsView.TAG_IN_SHEET.equals(c.getTag())) continue;
                    l.add(c);
                }
                return l;
            }
            @Override public ActionChipsView chips() {
                View v = findViewWithTag(ActionChipsView.TAG_IN_SHEET);
                return v instanceof ActionChipsView ? (ActionChipsView) v : null;
            }
            @Override public com.onesay.assistant.v2.timeline.ExecTimeline liveTimeline() { return mTimeline; }   // ★ feat/api-first
            @Override public void stopTask() { requestStopTask("对话窗停止键"); }
        });
        if (mChatSvc != null) mChat.setServiceHost(mChatSvc);
        mChatBg.setColor(com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.BG);
        mChatHandle.setColor(0x66FFFFFF);
        mChatGlow.setStyle(android.graphics.Paint.Style.STROKE);
    }

    /** 对话窗底板：卡片矩形 → 全屏插值；t<1 时苔绿卡底淡出、深色底淡入；顶部拖拽条；录音中屏幕边缘彩色光。 */
    private void chatDraw(android.graphics.Canvas c, float t) {
        float[] r = com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.lerpRect(
                new float[]{mCardRect.left, mCardRect.top, mCardRect.right, mCardRect.bottom},
                new float[]{0f, 0f, getWidth(), getHeight()}, t);
        mCardRect.set(r[0], r[1], r[2], r[3]);
        float rad = com.onesay.assistant.v2.ui.chat.ChatWindowPolicy.radius(cardRadius(), t);
        if (t < 1f && mPainter != null) {
            mPainter.draw(c, mCardRect, 1f - t, mInner.v * (1f - t), mHalo.v * (1f - t), com.onesay.assistant.v2.ui.wake.WakeCardPainter.MOSS);
        }
        mChatBg.setAlpha(Math.round(255 * Math.min(1f, t * 1.25f)));
        c.drawRoundRect(mCardRect, rad, rad, mChatBg);
        boolean listening = mStage.stage() == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.LISTENING;
        if (listening) chatGlow(c, t);
        c.save();
        c.clipRect(mCardRect);
        super.dispatchDraw(c);
        c.restore();
        mCloseRect.setEmpty();
        float cx = getWidth() / 2f, hy = mCardRect.top + dpf(12);
        mChatHandle.setAlpha(Math.round(0x66 * t));
        mChatTmp.set(cx - dpf(20), hy, cx + dpf(20), hy + dpf(4));
        c.drawRoundRect(mChatTmp, dpf(2), dpf(2), mChatHandle);
        if (listening && !mV2Instant && t >= 1f) postInvalidateOnAnimation();   // 边缘光呼吸
    }

    /** 录音中：屏幕边缘粉 / 紫 / 橙的柔光（三层描边，透明度呼吸；系统关闭动画时不呼吸）。 */
    private void chatGlow(android.graphics.Canvas c, float t) {
        int w = getWidth(), h = getHeight();
        if (mChatGlowShader == null || w != mChatGlowW || h != mChatGlowH) {
            mChatGlowShader = new android.graphics.SweepGradient(w / 2f, h / 2f,
                    new int[]{0xFFFF5FA2, 0xFF8E6BFF, 0xFF5AA9FF, 0xFFFFB24D, 0xFFFF5FA2}, null);
            mChatGlowW = w;
            mChatGlowH = h;
        }
        mChatGlow.setShader(mChatGlowShader);
        float breath = mV2Instant ? 1f : 0.75f + 0.25f * (float) Math.sin(android.os.SystemClock.uptimeMillis() / 420.0);
        float[] widths = {dpf(10), dpf(5), dpf(2)};
        int[] alphas = {40, 80, 150};
        for (int i = 0; i < 3; i++) {
            mChatGlow.setStrokeWidth(widths[i]);
            mChatGlow.setAlpha(Math.round(alphas[i] * breath * t));
            float in = widths[i] / 2f;
            mChatTmp.set(mCardRect.left + in, mCardRect.top + in, mCardRect.right - in, mCardRect.bottom - in);
            c.drawRoundRect(mChatTmp, dpf(28), dpf(28), mChatGlow);
        }
    }

    /** 测试 / 调试：当前 v2 卡片上显示的正文（纯文字）。 */
    public String v2BodyText() {
        return mV2ShownBody == null ? null : mV2ShownBody.toString();
    }

    // 控件访问器（供自测探针与无障碍自动化测试）
    public CurvedCapsuleButton getBtnCancelTop() { return mBtnCancelTop; }
    public CurvedCapsuleButton getChipVoice() { return mChipVoice; }
    public CurvedCapsuleButton getChipText() { return mChipText; }
    public CurvedCapsuleButton getChipScreen() { return mChipScreen; }
    public PillSearchBar getSearchBar() { return mSearchBar; }
    public CurvedCapsuleButton getBtnConfirm() { return mBtnConfirm; }
    public CurvedCapsuleButton getBtnCancel() { return mBtnCancel; }
    public CurvedCapsuleButton getBtnAlways() { return mBtnAlways; }
    public CurvedCapsuleButton getBtnOnce() { return mBtnOnce; }
    public CurvedCapsuleButton getBtnReject() { return mBtnReject; }
    public WaveVisualizerView getWaveView() { return mWaveView; }
}
