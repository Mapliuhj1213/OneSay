package com.onesay.assistant.v2.ui.chat;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * ★ ui-K1 全屏深色对话窗（同一会话第二次起，卡片就地展开成它）。
 *
 * <h3>做法</h3>
 * 对话窗<b>就是原来那张卡片视图</b>（FloatingSheetView，固定全屏窗）换了一种排布：卡片底板由宿主在 dispatchDraw 里按
 * {@link #progress()} 插值（矩形 卡片 → 全屏、圆角 → 0、苔绿卡底 → 深色），子 View 不换窗口、不重建 —— 当前这一轮的问句 / 回答 / 结果卡 /
 * 确认卡 / 插件卡（安装卡、应用网格、动作芯片…）都还是原来的那些 View、原来的回调，所以执行类结果卡天然嵌在这一轮里。
 * 本类负责：历史区（已冻结的轮次，{@link ChatAdapter} 排版）、当前轮的页脚 / 追问建议、底栏 {@link ChatBottomBar}、
 * 进出对话窗时的排布切换与形变动画（ValueAnimator，减速曲线 330 ms；收起 280 ms；系统关闭动画 → 直接跳终态）。
 *
 * <p>FloatingSheetView 只加挂接点（见 {@link ViewHost}）；服务侧能力（圈选 / 相机对话 / 选图识图 / 分享）走 {@link ServiceHost}。
 */
public final class ChatWindowController implements ChatAdapter.Actions, ChatBottomBar.Listener,
        com.onesay.assistant.v2.ui.ActionChipsView.Observer {

    private static final String TAG = "ChatWindow";

    /** 宿主卡片视图的零件（FloatingSheetView 构造时给定，不会变）。 */
    public static final class Parts {
        public ViewGroup root;
        public LinearLayout contentInner;
        public ScrollView contentScroll;
        public LinearLayout promptCard;
        public TextView transcript;
        public View resultCard;
        public TextView resultText;
        public View richBox;
        public View alarmBox;
        public View toggleBox;
        public View confirmBox;
    }

    /** 卡片视图侧的回调（FloatingSheetView 实现）。 */
    public interface ViewHost {
        com.onesay.assistant.v2.ui.wake.WakeStage.Stage stage();
        boolean instant();
        boolean fullWindow();
        boolean panelAttached();
        /** 卡片上已经有一轮回答（终稿 / 正文）——结果态底栏点按时据此先展开成对话窗。 */
        boolean hasAnswer();
        /** 形变进度变了（宿主重画底板）。 */
        void onChatProgress(float t);
        /** 进 / 出对话窗（宿主切触摸区域 / 图标行 / 输入条 / 小球的显隐）。 */
        void onChatActive(boolean on);
        void submit(String text);
        /** 打字：放开窗口焦点 + 弹键盘到对话窗输入框。 */
        void requestTyping();
        void micTap();
        void trackIme(boolean on);
        /** 当前这一轮显示中的问句（回显）/ 回答原文。 */
        String liveQuestion();
        String liveAnswer();
        /** 插件后加到卡片根布局上的卡（安装卡 / 应用网格…），冻结时一起截图。 */
        List<View> pluginCards();
        com.onesay.assistant.v2.ui.ActionChipsView chips();
    }

    /** 服务侧能力（FloatingSheetService 实现）。 */
    public interface ServiceHost {
        void circleSelect();
        void cameraCall();
        void pickImage(boolean camera);
        void askWithImage(String question, String path);
        /** 带当前屏幕截图提问（问完删图，回执不可信）。 */
        void askWithScreen(String question, String path);
        void share(String text);
        void ensureFocusable();
    }

    private final Context mCtx;
    private final Parts mP;
    private final ViewHost mHost;
    private ServiceHost mSvc;
    private final ChatTranscript mTranscript = new ChatTranscript();
    private final ChatAdapter mAdapter;
    private final LinearLayout mHistory;
    private final LinearLayout mLiveFooter;
    private final ImageView mLiveImage;
    private final ChatBottomBar mBar;

    private boolean mActive;
    private float mT;
    private ValueAnimator mAnim;
    private ViewGroup.LayoutParams mSavedScrollLp;
    private int[] mSavedPad;
    private long mReqAt;
    private String mRoute;
    private String mPendingImagePath;
    private Bitmap mPendingThumb;
    /** 附着的图是「识屏」截的当前屏幕（不是相册 / 拍照）：发送时默认问句与提示词不同。 */
    private boolean mPendingIsScreen;
    private Bitmap mLiveThumb;
    private List<String> mChipLabels = new ArrayList<String>();
    private boolean mJustFroze;
    /** 插件挂在卡片根布局上的卡（安装卡 / 应用网格 / 诊断卡…）：对话窗里挪进这一轮的流里，退出时放回。 */
    private final List<View> mMoved = new ArrayList<View>();
    private String mLastBubble;
    /** 上一次受理底栏麦克风的时刻（连点吞掉，ChatWindowPolicy.swallowRepeatTap）。 */
    private long mLastBarTapAt;

    public ChatWindowController(Context ctx, Parts parts, ViewHost host) {
        mCtx = ctx;
        mP = parts;
        mHost = host;
        mAdapter = new ChatAdapter(ctx, this);
        mHistory = new LinearLayout(ctx);
        mHistory.setOrientation(LinearLayout.VERTICAL);
        mHistory.setVisibility(View.GONE);
        mLiveFooter = new LinearLayout(ctx);
        mLiveFooter.setOrientation(LinearLayout.VERTICAL);
        mLiveFooter.setVisibility(View.GONE);
        mLiveImage = new ImageView(ctx);
        mLiveImage.setVisibility(View.GONE);
        mBar = new ChatBottomBar(ctx);
        mBar.setVisibility(View.GONE);
        mBar.setListener(this);
        com.onesay.assistant.v2.ui.ActionChipsView.sObserver = this;
    }

    public void setServiceHost(ServiceHost h) {
        mSvc = h;
    }

    public boolean active() {
        return mActive;
    }

    public float progress() {
        return mT;
    }

    public ChatBottomBar bar() {
        return mBar;
    }

    /**
     * 卡片模式（没展开）时，出结果 / 出错 / 打字阶段卡片底部也用这同一个 {@link ChatBottomBar}
     * （替代旧图标行 / 旧输入条，杜绝分叉）。展开成对话窗时它原地留着当对话窗底栏，不重建、不淡出再淡入。
     */
    public void showOnCard(boolean on) {
        if (mActive) return;
        if (on) {
            if (mBar.getParent() == null) {
                LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                bl.topMargin = dp(8);
                mP.root.addView(mBar, bl);
            }
            mBar.setListening(false, mHost.instant());
            if (mBar.getVisibility() != View.VISIBLE) mBar.setVisibility(View.VISIBLE);
        } else if (mBar.getVisibility() != View.GONE) {
            mBar.hideMenu();
            mBar.setVisibility(View.GONE);
        }
    }

    /** 卡片底栏此刻显示着（没展开）。 */
    public boolean shownOnCard() {
        return !mActive && mBar.getParent() != null && mBar.getVisibility() == View.VISIBLE;
    }

    /** 卡片底栏上点了麦克风 / 发送 / 输入框：卡片上已有一轮回答 → 先就地展开（同一会话、原形变动画），再执行这次动作。 */
    private void expandForBar(String what) {
        if (!ChatWindowPolicy.barExpands(true, mHost.fullWindow(), mActive, mHost.hasAnswer())) return;
        Log.i(TAG, "[CHAT] 结果态底栏点按「" + what + "」→ 一次就地展开成对话窗（第 " + (mTranscript.size() + 1) + " 轮）");
        enter();
    }

    public ChatTranscript transcript() {
        return mTranscript;
    }

    // ------------------------------------------------------------------ 会话 / 轮次

    /**
     * 一轮的边界（新唤醒 / 接着说 / 新输入到来，卡片内容被清掉<b>之前</b>调用）：把屏上这一轮冻结进历史（顺手截执行卡），
     * 再按 {@link ChatWindowPolicy#decide} 决定展开 / 保持卡片 / 会话超时清历史。返回 true = 对话窗开着。
     */
    public boolean boundary(ChatWindowPolicy.Trigger trigger) {
        boolean panel = mHost.panelAttached();
        boolean memory = false;
        try {
            memory = !com.onesay.assistant.v2.router.ConversationMemory.getInstance().isEmpty();
        } catch (Throwable ignore) {
        }
        ChatWindowPolicy.Decision d = ChatWindowPolicy.decide(trigger, true, mHost.fullWindow(), mActive,
                mTranscript.size(), panel, memory);
        if (d == ChatWindowPolicy.Decision.RESET_CARD) {
            Log.i(TAG, "[CHAT] 会话已超时 → 清空对话窗历史（" + mTranscript.size() + " 轮），本次照旧底部卡");
            mTranscript.clear();
            return false;
        }
        ChatTranscript.Round frozen = freezeLive();
        mJustFroze = frozen != null;
        if (d != ChatWindowPolicy.Decision.EXPAND) return false;
        if (!mActive) {
            Log.i(TAG, "[CHAT] " + trigger + "：同一会话第 " + (mTranscript.size() + 1) + " 轮 → 卡片展开成对话窗");
            enter();
        } else if (frozen != null) {
            renderHistory();
        }
        return true;
    }

    /**
     * 清掉对话窗历史（{@link ChatWindowPolicy#decide} 的轮数归零），下次唤醒照第一次弹原卡。
     * 屏上的视图不动（正在收起的动画照常播完，摘窗时 exitImmediate 清）。
     */
    public void endSession() {
        int n = mTranscript.size();
        mTranscript.clear();
        mChipLabels = new ArrayList<String>();
        deleteQuietly(mPendingImagePath);
        mPendingImagePath = null;
        mPendingThumb = null;
        mPendingIsScreen = false;
        mLiveThumb = null;
        mLastBarTapAt = 0L;
        mBar.clearAttachment();
        mBar.setText("");
        Log.i(TAG, "[CHAT] 用户关闭 → 会话结束，清对话窗历史 " + n + " 轮（下次唤醒弹原卡）");
    }

    /** 上一次 {@link #boundary} 是否把屏上那一轮冻结进了历史（宿主据此清掉屏上的同一轮，免得重复显示）。读一次即清。 */
    public boolean consumeFroze() {
        boolean f = mJustFroze;
        mJustFroze = false;
        return f;
    }

    /** 新请求开始（计时用）。 */
    public void onRequestStart() {
        mReqAt = android.os.SystemClock.elapsedRealtime();
        mRoute = null;
    }

    public void noteRoute(String route) {
        mRoute = route;
    }

    /** 这一轮出了终稿 / 执行结果（卡片模式下也记，第二次触发时才知道有「上一轮」）。 */
    public void onFinal(String question, String answer) {
        if (answer == null || answer.trim().length() == 0) return;
        long ms = mReqAt > 0 ? android.os.SystemClock.elapsedRealtime() - mReqAt : 0L;
        ChatTranscript.Round r = mTranscript.upsert(question, answer, mRoute, ms);
        if (mLiveThumb != null) r.image = mLiveThumb;
        if (mActive) rebuildLiveFooter();
    }

    /** 冻结屏上这一轮：截执行卡快照（闹钟卡 / 开关 / 富内容 / 插件卡）→ 进历史。 */
    private ChatTranscript.Round freezeLive() {
        ChatTranscript.Round live = mTranscript.live();
        if (live == null) return null;
        try {
            for (View v : cardViews()) {
                Bitmap b = shot(v);
                if (b != null) live.cards.add(b);
                if (live.cards.size() >= 3) break;
            }
        } catch (Throwable t) {
            Log.w(TAG, "[CHAT] 执行卡快照失败: " + t);
        }
        if (mLiveThumb != null && live.image == null) live.image = mLiveThumb;
        mLiveThumb = null;
        mLiveImage.setImageDrawable(null);
        mLiveImage.setVisibility(View.GONE);
        mLiveFooter.removeAllViews();
        mLiveFooter.setVisibility(View.GONE);
        mLastBubble = null;
        ChatTranscript.Round r = mTranscript.freeze();
        if (r != null) Log.i(TAG, "[CHAT] 第 " + r.seq + " 轮冻结进历史（卡片快照 " + r.cards.size() + "）");
        return r;
    }

    private List<View> cardViews() {
        List<View> l = new ArrayList<View>();
        for (View v : new View[]{mP.alarmBox, mP.toggleBox, mP.richBox}) {
            if (v != null && v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && v.getHeight() > 0) l.add(v);
        }
        List<View> plug = new ArrayList<View>(mMoved);
        List<View> rest = mHost.pluginCards();
        if (rest != null) plug.addAll(rest);
        for (View v : plug) if (v.getVisibility() == View.VISIBLE && v.getWidth() > 0 && v.getHeight() > 0) l.add(v);
        return l;
    }

    private Bitmap shot(View v) {
        int w = v.getWidth(), h = Math.min(v.getHeight(), 1600);
        if (w <= 0 || h <= 0) return null;
        float k = w > 1000 ? 1000f / w : 1f;
        Bitmap b = Bitmap.createBitmap(Math.round(w * k), Math.max(1, Math.round(h * k)), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        c.scale(k, k);
        v.draw(c);
        return b;
    }

    // ------------------------------------------------------------------ 进 / 出

    private void enter() {
        mActive = true;
        ViewGroup root = mP.root;
        if (mHistory.getParent() == null) mP.contentInner.addView(mHistory, 0, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        if (mLiveImage.getParent() == null) {
            int at = Math.max(0, mP.contentInner.indexOfChild(mP.promptCard));
            LinearLayout.LayoutParams il = new LinearLayout.LayoutParams(dp(120), dp(120));
            il.gravity = android.view.Gravity.END;
            il.topMargin = dp(18);
            mP.contentInner.addView(mLiveImage, at, il);
        }
        if (mLiveFooter.getParent() == null) {
            int at = mP.contentInner.indexOfChild(mP.confirmBox);
            LinearLayout.LayoutParams fl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            fl.topMargin = dp(6);
            mP.contentInner.addView(mLiveFooter, at >= 0 ? at + 1 : mP.contentInner.getChildCount(), fl);
        }
        final boolean barWasShown = mBar.getParent() != null && mBar.getVisibility() == View.VISIBLE;   // 结果态底栏已在卡片上：原地留用
        if (mBar.getParent() == null) {
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            bl.topMargin = dp(8);
            root.addView(mBar, bl);
        }
        mSavedScrollLp = mP.contentScroll.getLayoutParams();
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        mP.contentScroll.setLayoutParams(sl);
        mSavedPad = new int[]{root.getPaddingLeft(), root.getPaddingTop(), root.getPaddingRight()};
        root.setPadding(dp(16), dp(30), dp(16), root.getPaddingBottom());
        mHistory.setVisibility(View.VISIBLE);
        mBar.setVisibility(View.VISIBLE);
        renderHistory();
        mHost.onChatActive(true);
        mirrorChips(mChipLabels);
        styleLive();
        animateTo(1f, ChatWindowPolicy.duration(mHost.instant(), true), null);
        // 上一轮随之上移、底栏从卡片底部的图标位淡入
        if (!mHost.instant()) {
            mP.contentScroll.setTranslationY(dp(22));
            mP.contentScroll.animate().translationY(0f).setDuration(ChatWindowPolicy.EXPAND_MS)
                    .setInterpolator(new DecelerateInterpolator(ChatWindowPolicy.DECEL_FACTOR)).start();
            if (!barWasShown) {   // 底栏从卡片底部的图标位淡入；已经在卡片上的同一个底栏不再闪一下（也省一轮图层重绘）
                mBar.setAlpha(0f);
                mBar.setTranslationY(dp(14));
                mBar.animate().alpha(1f).translationY(0f).setStartDelay(60).setDuration(ChatWindowPolicy.EXPAND_MS - 60)
                        .setInterpolator(new DecelerateInterpolator(ChatWindowPolicy.DECEL_FACTOR)).start();
            }
        }
        scrollToBottom(false);
    }

    /** 关闭：反向收起（底板缩回卡片、内容淡出），收完回调（宿主再摘窗）。 */
    public void collapse(final Runnable done) {
        if (!mActive) {
            if (done != null) done.run();
            return;
        }
        long ms = ChatWindowPolicy.duration(mHost.instant(), false);
        if (ms > 0) {
            mP.contentScroll.animate().alpha(0f).translationY(dp(16)).setDuration(ms).start();
            mBar.animate().alpha(0f).translationY(dp(14)).setDuration(ms * 2 / 3).start();
            View root = mP.root;
            root.animate().alpha(0f).setStartDelay(ms / 2).setDuration(ms / 2).start();
        }
        animateTo(0f, ms, done);
    }

    /** 立即回到卡片排布（摘窗 / 复位 / 旧界面）。历史保留（会话没超时，下次再展开）。 */
    public void exitImmediate() {
        if (!mActive && mT == 0f) return;
        if (mAnim != null) {
            mAnim.removeAllListeners();
            mAnim.cancel();
            mAnim = null;
        }
        mActive = false;
        mT = 0f;
        mP.contentScroll.animate().cancel();
        mP.contentScroll.setAlpha(1f);
        mP.contentScroll.setTranslationY(0f);
        mBar.animate().cancel();
        mBar.setAlpha(1f);
        mBar.setTranslationY(0f);
        mP.root.animate().cancel();
        if (mSavedScrollLp != null) mP.contentScroll.setLayoutParams(mSavedScrollLp);
        mSavedScrollLp = null;
        if (mSavedPad != null) mP.root.setPadding(mSavedPad[0], mSavedPad[1], mSavedPad[2], mP.root.getPaddingBottom());
        mSavedPad = null;
        mHistory.setVisibility(View.GONE);
        mHistory.removeAllViews();
        mLiveFooter.setVisibility(View.GONE);
        mLiveFooter.removeAllViews();
        mLiveImage.setVisibility(View.GONE);
        mBar.setVisibility(View.GONE);
        mBar.hideMenu();
        unstyleBubble();
        restorePluginCards();
        com.onesay.assistant.v2.ui.ActionChipsView chips = mHost.chips();
        if (chips != null && !chips.labels().isEmpty()) chips.setVisibility(View.VISIBLE);
        mHost.trackIme(false);
        mHost.onChatActive(false);
        mHost.onChatProgress(0f);
        Log.i(TAG, "[CHAT] 回到卡片排布（历史 " + mTranscript.size() + " 轮保留）");
    }

    private void animateTo(final float target, long ms, final Runnable done) {
        if (mAnim != null) {
            mAnim.removeAllListeners();
            mAnim.cancel();
        }
        if (ms <= 0) {
            mT = target;
            mHost.onChatProgress(mT);
            if (done != null) done.run();
            return;
        }
        mAnim = ValueAnimator.ofFloat(mT, target);
        mAnim.setDuration(ms);
        mAnim.setInterpolator(new DecelerateInterpolator(ChatWindowPolicy.DECEL_FACTOR));
        mAnim.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator a) {
                mT = (Float) a.getAnimatedValue();
                mHost.onChatProgress(mT);
            }
        });
        mAnim.addListener(new AnimatorListenerAdapter() {
            private boolean mCancelled;

            @Override public void onAnimationCancel(Animator animation) { mCancelled = true; }

            @Override
            public void onAnimationEnd(Animator animation) {
                mAnim = null;
                if (mCancelled) return;
                mT = target;
                mHost.onChatProgress(mT);
                if (done != null) done.run();
            }
        });
        mAnim.start();
    }

    // ------------------------------------------------------------------ 每次重排（宿主 v2Render 末尾调用）

    /** 对话窗里当前这一轮的样式：问句 = 右侧气泡、回答 = 左侧正文、页脚只在出结果后显示、底栏录音态。 */
    public void styleLive() {
        if (!mActive) return;
        com.onesay.assistant.v2.ui.wake.WakeStage.Stage st = mHost.stage();
        boolean listening = st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.LISTENING;
        boolean result = st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.RESULT || st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.TEXT;
        TextView tr = mP.transcript;
        String q = tr.getText() == null ? "" : tr.getText().toString().trim();
        boolean hint = listening && q.equals(com.onesay.assistant.v2.ui.wake.WakeCopy.LISTEN_HINT);
        boolean showBubble = q.length() > 0 && !hint && st != com.onesay.assistant.v2.ui.wake.WakeStage.Stage.ERROR
                && !com.onesay.assistant.v2.ui.wake.WakeCopy.isPseudoQuestion(q);
        if (showBubble) {
            mAdapter.styleBubble(tr);
            ViewGroup.LayoutParams cur = tr.getLayoutParams();
            if (cur == null || cur.width != ViewGroup.LayoutParams.WRAP_CONTENT
                    || (cur instanceof LinearLayout.LayoutParams && ((LinearLayout.LayoutParams) cur).weight != 0f)) {
                tr.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            if (listening) {
                tr.animate().cancel();
                tr.setAlpha(0.7f);   // 还在说：淡一点
                tr.setTranslationY(0f);
            } else if (!q.equals(mLastBubble)) {
                mLastBubble = q;   // 新问句气泡：从下方淡入滑入
                if (mHost.instant()) {
                    tr.setAlpha(1f);
                    tr.setTranslationY(0f);
                } else {
                    tr.setAlpha(0f);
                    tr.setTranslationY(dp(28));
                    tr.animate().alpha(1f).translationY(0f).setDuration(260)
                            .setInterpolator(new DecelerateInterpolator(ChatWindowPolicy.DECEL_FACTOR)).start();
                }
            }
            mP.promptCard.setGravity(android.view.Gravity.END | android.view.Gravity.CENTER_VERTICAL);
            mP.promptCard.setVisibility(View.VISIBLE);
            ViewGroup.LayoutParams plp = mP.promptCard.getLayoutParams();
            if (plp instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) plp).topMargin = dp(18);
            tr.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    if (mHost.stage() == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.LISTENING) {
                        mHost.micTap();
                        return;
                    }
                    String text = mP.transcript.getText() == null ? "" : mP.transcript.getText().toString();
                    editQuestion(text);
                }
            });
        } else if (st == com.onesay.assistant.v2.ui.wake.WakeStage.Stage.ERROR) {
            unstyleBubble();   // 错误态那一句（没听清…）按普通灰字留在流里
        } else {
            mP.promptCard.setVisibility(View.GONE);
        }
        mLiveFooter.setVisibility(result && mLiveFooter.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        mBar.setListening(listening, mHost.instant());
        mLiveImage.setVisibility(mLiveThumb != null ? View.VISIBLE : View.GONE);
        adoptPluginCards();
        com.onesay.assistant.v2.ui.ActionChipsView chips = mHost.chips();
        if (chips != null && chips.getVisibility() == View.VISIBLE) chips.setVisibility(View.GONE);   // 芯片改由建议胶囊显示
        if (nearBottom()) scrollToBottom(true);   // 新内容自动滚到底（用户上翻阅读时不抢）
    }

    /** 插件卡挪进流里（这一轮回答下面、页脚上面）；插件按 tag 在整棵卡片树里找自己的卡，挪了照样能藏 / 复用。 */
    private void adoptPluginCards() {
        List<View> rest = mHost.pluginCards();
        if (rest == null || rest.isEmpty()) return;
        for (View v : rest) {
            if (v.getParent() != mP.root) continue;
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            mP.root.removeView(v);
            int at = mP.contentInner.indexOfChild(mLiveFooter);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                nl.topMargin = Math.max(m.topMargin, dp(10));
                nl.bottomMargin = m.bottomMargin;
            }
            mP.contentInner.addView(v, at >= 0 ? at : mP.contentInner.getChildCount(), nl);
            mMoved.add(v);
            Log.i(TAG, "[CHAT] 插件卡挪进对话流：" + v.getClass().getSimpleName());
        }
    }

    private void restorePluginCards() {
        for (View v : mMoved) {
            if (v.getParent() != mP.contentInner) continue;
            ViewGroup.LayoutParams lp = v.getLayoutParams();
            mP.contentInner.removeView(v);
            int at = mP.root.indexOfChild(mP.contentScroll) + 1;
            mP.root.addView(v, Math.max(0, Math.min(at, mP.root.getChildCount())), lp);
        }
        mMoved.clear();
    }

    private boolean nearBottom() {
        View c = mP.contentScroll.getChildAt(0);
        if (c == null) return true;
        int gap = c.getHeight() - mP.contentScroll.getHeight() - mP.contentScroll.getScrollY();
        return gap <= dp(220);
    }

    /** 对话窗里的回答正文（流式 / 终稿同一套简单 Markdown）。 */
    public CharSequence answerBody(String body) {
        return mAdapter.styled(com.onesay.assistant.v2.ui.wake.WakeCopy.cleanBody(body));
    }

    private void unstyleBubble() {
        TextView tr = mP.transcript;
        tr.setBackground(null);
        tr.setCompoundDrawables(null, null, null, null);
        tr.setMaxWidth(Integer.MAX_VALUE);
        tr.setAlpha(1f);
        tr.setOnClickListener(null);
        tr.setClickable(false);
        tr.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mP.promptCard.setGravity(android.view.Gravity.CENTER_VERTICAL);
        ViewGroup.LayoutParams plp = mP.promptCard.getLayoutParams();
        if (plp instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) plp).topMargin = 0;
    }

    private void renderHistory() {
        mAdapter.render(mHistory, mTranscript);
    }

    private void rebuildLiveFooter() {
        mLiveFooter.removeAllViews();
        ChatTranscript.Round r = mTranscript.live();
        if (r == null) return;
        mLiveFooter.addView(mAdapter.footer(r, mP.resultText));
        mTranscript.setSuggestions(mChipLabels);
        if (!r.suggestions.isEmpty()) {
            View s = mAdapter.suggestions(r.suggestions);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = dp(14);
            mLiveFooter.addView(s, lp);
        }
        styleLive();
    }

    private void scrollToBottom(final boolean smooth) {
        mP.contentScroll.post(new Runnable() {
            @Override
            public void run() {
                View c = mP.contentScroll.getChildAt(0);
                if (c == null) return;
                int y = Math.max(0, c.getHeight() - mP.contentScroll.getHeight());
                if (smooth && !mHost.instant()) mP.contentScroll.smoothScrollTo(0, y);
                else mP.contentScroll.scrollTo(0, y);
            }
        });
    }

    // ------------------------------------------------------------------ 动作芯片 → 追问建议胶囊

    @Override
    public void onChipsChanged(com.onesay.assistant.v2.ui.ActionChipsView v, List<String> labels) {
        if (v != mHost.chips()) return;
        mChipLabels = labels == null ? new ArrayList<String>() : labels;
        if (mActive) mirrorChips(mChipLabels);
    }

    private void mirrorChips(List<String> labels) {
        com.onesay.assistant.v2.ui.ActionChipsView chips = mHost.chips();
        if (chips != null && !labels.isEmpty()) chips.setVisibility(View.GONE);
        if (mTranscript.live() != null) rebuildLiveFooter();
    }

    @Override
    public void onSuggestion(int index, String label) {
        com.onesay.assistant.v2.ui.ActionChipsView chips = mHost.chips();
        if (chips != null && chips.clickAt(index)) return;
        mHost.submit(label);   // 芯片已经被收走：按文案当新问题
    }

    // ------------------------------------------------------------------ 页脚动作

    @Override
    public void onSpeak(ChatTranscript.Round r, ImageView icon) {
        com.onesay.assistant.v2.ui.TtsSpeaker tts = com.onesay.assistant.v2.ui.TtsSpeaker.get(mCtx);
        if (com.onesay.assistant.v2.ui.TtsSpeaker.isSpeaking()) {
            com.onesay.assistant.v2.ui.TtsSpeaker.stopAll();
            return;
        }
        tts.speakLive(ChatMarkdown.plain(r.answer), 600);
    }

    @Override
    public void onCopy(ChatTranscript.Round r) {
        try {
            ClipboardManager cm = (ClipboardManager) mCtx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("OneSay", ChatMarkdown.plain(r.answer)));
            Toast.makeText(mCtx, "已复制", Toast.LENGTH_SHORT).show();
        } catch (Throwable t) {
            Log.w(TAG, "[CHAT] 复制失败: " + t.getClass().getSimpleName());
        }
    }

    @Override
    public void onSelectText(ChatTranscript.Round r, TextView answerView) {
        if (answerView == null) return;
        if (mSvc != null) mSvc.ensureFocusable();   // 选择手柄要窗口焦点
        answerView.setTextIsSelectable(true);
        Toast.makeText(mCtx, "长按正文选择文字", Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onShare(ChatTranscript.Round r) {
        if (mSvc != null) mSvc.share(ChatMarkdown.plain(r.answer));
    }

    @Override
    public void onLike(ChatTranscript.Round r, int value, ImageView like, ImageView dislike) {
        int fb = mTranscript.toggleFeedback(r.seq, value);
        ChatAdapter.applyFeedback(fb, like, dislike);
        Log.i(TAG, "[CHAT-FEEDBACK] 第 " + r.seq + " 轮 " + (fb > 0 ? "赞" : fb < 0 ? "踩" : "取消") + "（仅本地记录）");
    }

    @Override
    public void onEditQuestion(ChatTranscript.Round r) {
        editQuestion(r.question);
    }

    private void editQuestion(String q) {
        if (q == null) return;
        mBar.setText(q);
        mBar.focusForTyping();   // 铅笔改问句 = 明确要打字
        mHost.requestTyping();
    }

    // ------------------------------------------------------------------ 底栏

    @Override
    public void onCircleSelect() {
        if (mSvc != null) mSvc.circleSelect();
    }

    @Override
    public void onCall() {
        if (mSvc != null) mSvc.cameraCall();
    }

    @Override
    public void onMic() {
        long now = android.os.SystemClock.uptimeMillis();
        if (ChatWindowPolicy.swallowRepeatTap(now, mLastBarTapAt)) {   // 展开动画中连点：不触发第二次（开录又马上停）
            Log.i(TAG, "[CHAT] 底栏麦克风 " + (now - mLastBarTapAt) + "ms 内连点，忽略");
            return;
        }
        mLastBarTapAt = now;
        expandForBar("麦克风");
        mHost.micTap();
    }

    @Override
    public void onPickImage(boolean camera) {
        if (mSvc != null) mSvc.pickImage(camera);
    }

    @Override
    public void onSubmit(String text) {
        if (mPendingImagePath != null || (text != null && text.trim().length() > 0)) expandForBar("发送");
        if (mPendingImagePath != null) {
            String path = mPendingImagePath;
            mLiveThumb = mPendingThumb;
            boolean screen = mPendingIsScreen;
            mPendingImagePath = null;
            mPendingThumb = null;
            mPendingIsScreen = false;
            mBar.clearAttachment();
            if (mLiveThumb != null) mLiveImage.setImageBitmap(mLiveThumb);
            if (mSvc != null) {
                if (screen) mSvc.askWithScreen(ChatWindowPolicy.screenQuestion(text), path);
                else mSvc.askWithImage(ChatWindowPolicy.imageQuestion(text), path);
            }
            return;
        }
        if (text == null || text.trim().length() == 0) return;
        if (!mActive) hideImeOnCard();   // 卡片上打字发送（第一次唤醒的打字态）：收键盘，卡片回去显示「正在想」
        mHost.submit(text.trim());
    }

    @Override
    public void onInputFocus(boolean focused) {
        if (focused) {
            expandForBar("输入框");
            mBar.focusForTyping();
            mHost.requestTyping();
        }
        mHost.trackIme(focused);
    }

    @Override
    public void onAttachmentRemoved() {
        deleteQuietly(mPendingImagePath);
        mPendingImagePath = null;
        mPendingThumb = null;
        mPendingIsScreen = false;
    }

    /** 当前屏幕截图回来了（主线程）：同「+」相册的缩略图样式附在输入框上方，等用户输入问题后发送。 */
    public void onScreenCaptured(String path, Bitmap thumb) {
        if (path == null) return;
        onImagePicked(path, thumb);
        mPendingIsScreen = true;
        Log.i(TAG, "[CHAT-IMG] 识屏截图已附上，等发送（空着发 = 「" + ChatWindowPolicy.SCREEN_DEFAULT_Q + "」）");
    }

    /** 当前附着的是识屏截图吗（测试 / 宿主判断用）。 */
    public boolean pendingIsScreen() {
        return mPendingImagePath != null && mPendingIsScreen;
    }

    /** 选图 / 拍照回来了（主线程）：缩略图附在输入框上方，等用户发送。 */
    public void onImagePicked(String path, Bitmap thumb) {
        if (path == null) return;
        deleteQuietly(mPendingImagePath);
        mPendingImagePath = path;
        mPendingThumb = thumb;
        mPendingIsScreen = false;
        mBar.setAttachment(thumb);
        Log.i(TAG, "[CHAT-IMG] 已附图（" + (thumb == null ? 0 : thumb.getWidth()) + "px 缩略图），等发送");
    }

    private static void deleteQuietly(String path) {
        if (path == null) return;
        try {
            new java.io.File(path).delete();
        } catch (Throwable ignore) {
        }
    }

    private void hideImeOnCard() {
        try {
            android.view.inputmethod.InputMethodManager imm = (android.view.inputmethod.InputMethodManager)
                    mCtx.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(mBar.edit().getWindowToken(), 0);
            mBar.edit().clearFocus();
        } catch (Throwable ignore) {
        }
    }

    /** 再次唤醒时输入框交出焦点（不留在打字态）。 */
    public void clearInputFocus() {
        try {
            mBar.edit().clearFocus();
        } catch (Throwable ignore) {
        }
    }

    /** 宿主 showSoftKeyboard 在对话窗里改弹到这个输入框。 */
    public android.widget.EditText input() {
        return mBar.edit();
    }

    private int dp(float v) {
        return Math.round(v * mCtx.getResources().getDisplayMetrics().density);
    }
}
