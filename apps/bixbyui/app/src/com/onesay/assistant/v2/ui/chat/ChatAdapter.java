package com.onesay.assistant.v2.ui.chat;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BulletSpan;
import android.text.style.StyleSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * ★ ui-K1 对话窗列表的视图工厂（无 RecyclerView 依赖：一轮最多几十条，LinearLayout 直接排）。
 * 问句 = 右侧圆角棕色气泡、尾部铅笔；回答 = 左侧无底色正文（粗体 / 圆点列表）；页脚 = 灰字免责 + 一排图标；
 * 追问建议 = 灰色胶囊（只挂在最后一条回答下）；执行卡 = 当轮结果卡的快照。
 */
public final class ChatAdapter {

    /** 页脚 / 气泡上的操作回调（ChatWindowController 实现）。 */
    public interface Actions {
        void onSpeak(ChatTranscript.Round r, ImageView icon);
        void onCopy(ChatTranscript.Round r);
        void onSelectText(ChatTranscript.Round r, TextView answerView);
        void onShare(ChatTranscript.Round r);
        void onLike(ChatTranscript.Round r, int value, ImageView like, ImageView dislike);
        void onEditQuestion(ChatTranscript.Round r);
        void onSuggestion(int index, String label);
    }

    public static final int T1 = 0xFFF0EEEA, T2 = 0xFFB2AFAA, T3 = 0xFF807E7A;
    /** 标记：历史里的回答正文（选择文字时找它）。 */
    public static final String TAG_ANSWER = "chat_answer";

    private final Context mCtx;
    private final Actions mActions;

    public ChatAdapter(Context ctx, Actions actions) {
        mCtx = ctx;
        mActions = actions;
    }

    /** 整段重排历史（轮数少，直接重建；返回最后一个子 View 便于做入场动画）。 */
    public View render(LinearLayout into, ChatTranscript t) {
        into.removeAllViews();
        View last = null;
        List<ChatTranscript.Item> items = t.items();
        TextView lastAnswer = null;
        for (ChatTranscript.Item it : items) {
            switch (it.kind) {
                case USER:
                    if (it.round.image instanceof Bitmap) last = add(into, imageThumb((Bitmap) it.round.image), dp(18), true);
                    last = add(into, userBubble(it.round), dp(it.round.image instanceof Bitmap ? 8 : 18), true);
                    break;
                case ANSWER:
                    lastAnswer = answerView(it.round.answer);
                    last = add(into, lastAnswer, dp(16), false);
                    for (Object o : it.round.cards) {
                        if (o instanceof Bitmap) last = add(into, cardShot((Bitmap) o), dp(10), false);
                    }
                    break;
                case FOOTER:
                    last = add(into, footer(it.round, lastAnswer), dp(10), false);
                    break;
                case SUGGESTIONS:
                    // 历史里不再挂建议胶囊：芯片的回调只对当轮有效（当轮的建议由控制器挂在屏上这一轮下面）
                    break;
                default:
                    break;
            }
        }
        return last;
    }

    private View add(LinearLayout into, View v, int top, boolean right) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                right ? ViewGroup.LayoutParams.WRAP_CONTENT : ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        if (right) lp.gravity = Gravity.END;
        into.addView(v, lp);
        return v;
    }

    /** 右侧圆角棕色气泡（尾部铅笔：点了把原话填回输入框）。 */
    public TextView userBubble(final ChatTranscript.Round r) {
        TextView tv = new TextView(mCtx);
        styleBubble(tv);
        tv.setText(r.question);
        tv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mActions.onEditQuestion(r); }
        });
        tv.setContentDescription("问题：" + r.question + "，点按修改后重发");
        return tv;
    }

    /** 气泡样式（历史与屏上当前这一轮共用）。 */
    public void styleBubble(TextView tv) {
        GradientDrawable b = new GradientDrawable();
        b.setCornerRadius(dp(24));
        b.setColor(ChatWindowPolicy.BUBBLE);
        tv.setBackground(b);
        tv.setTextColor(T1);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f);
        tv.setPadding(dp(18), dp(11), dp(14), dp(11));
        tv.setMaxWidth(Math.round(mCtx.getResources().getDisplayMetrics().widthPixels * 0.78f));
        ChatIcons pen = new ChatIcons(ChatIcons.PENCIL, T2);
        int s = dp(18);
        pen.setBounds(0, 0, s, s);
        tv.setCompoundDrawables(null, null, pen, null);
        tv.setCompoundDrawablePadding(dp(6));
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setLineSpacing(dp(2), 1.1f);
    }

    /** 回答正文：左侧无底色，简单 Markdown（粗体 / 圆点列表 / 标题）。 */
    public TextView answerView(String answer) {
        TextView tv = new TextView(mCtx);
        tv.setTag(TAG_ANSWER);
        tv.setTextColor(T1);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16.5f);
        tv.setLineSpacing(dp(5), 1.12f);
        tv.setText(styled(answer));
        return tv;
    }

    /** Markdown → Spannable（对话窗当前这一轮的流式 / 终稿也用它）。 */
    public CharSequence styled(String answer) {
        ChatMarkdown.Doc d = ChatMarkdown.parse(com.onesay.assistant.v2.router.UntrustedTags.strip(answer == null ? "" : answer));
        SpannableStringBuilder sb = new SpannableStringBuilder(d.text);
        for (int[] r : d.bold) sb.setSpan(new StyleSpan(Typeface.BOLD), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (int[] r : d.headings) sb.setSpan(new AbsoluteSizeSpan(18, true), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        for (int[] r : d.bullets) {
            int end = Math.min(sb.length(), r[1] + 1 <= sb.length() ? r[1] + 1 : r[1]);
            sb.setSpan(new BulletSpan(dp(10), T1), r[0], end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    /** 页脚：灰字免责 + 朗读 / 复制 / 选择文字 / 分享 / 详情 / 赞 / 踩；详情行默认收起。 */
    public View footer(final ChatTranscript.Round r, final TextView answerView) {
        LinearLayout col = new LinearLayout(mCtx);
        col.setOrientation(LinearLayout.VERTICAL);
        TextView dis = new TextView(mCtx);
        dis.setText(ChatWindowPolicy.DISCLAIMER);
        dis.setTextColor(T3);
        dis.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        if (ChatWindowPolicy.showDisclaimer(r.route)) col.addView(dis);   // 本地规则（计划 App / 记一记）答复不是 AI 生成的
        LinearLayout row = new LinearLayout(mCtx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        final TextView detail = new TextView(mCtx);
        detail.setTextColor(T3);
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        detail.setLineSpacing(dp(2), 1.1f);
        detail.setVisibility(View.GONE);
        final ImageView speak = icon(ChatIcons.PLAY, "朗读");
        speak.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mActions.onSpeak(r, speak); }
        });
        ImageView copy = icon(ChatIcons.COPY, "复制");
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mActions.onCopy(r); }
        });
        ImageView sel = icon(ChatIcons.TEXT, "选择文字");
        sel.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mActions.onSelectText(r, answerView); }
        });
        ImageView share = icon(ChatIcons.SHARE, "分享");
        share.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mActions.onShare(r); }
        });
        ImageView info = icon(ChatIcons.INFO, "详情");
        info.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                detail.setText(ChatWindowPolicy.detailLine(r.route, r.latencyMs, r.answer == null ? 0 : r.answer.length()));
                detail.setVisibility(detail.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE);
            }
        });
        final ImageView like = icon(ChatIcons.LIKE, "赞");
        final ImageView dislike = icon(ChatIcons.DISLIKE, "踩");
        like.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mActions.onLike(r, 1, like, dislike); }
        });
        dislike.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { mActions.onLike(r, -1, like, dislike); }
        });
        applyFeedback(r.feedback, like, dislike);
        for (ImageView iv : new ImageView[]{speak, copy, sel, share, info, like, dislike}) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
            lp.rightMargin = dp(8);
            row.addView(iv, lp);
        }
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.topMargin = dp(10);
        rlp.leftMargin = dp(-8);
        col.addView(row, rlp);
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.topMargin = dp(4);
        col.addView(detail, dlp);
        return col;
    }

    public static void applyFeedback(int fb, ImageView like, ImageView dislike) {
        if (like.getDrawable() instanceof ChatIcons) ((ChatIcons) like.getDrawable()).filled(fb > 0);
        if (dislike.getDrawable() instanceof ChatIcons) ((ChatIcons) dislike.getDrawable()).filled(fb < 0);
    }

    /** 追问建议胶囊（竖排，灰底）。 */
    public View suggestions(List<String> labels) {
        LinearLayout col = new LinearLayout(mCtx);
        col.setOrientation(LinearLayout.VERTICAL);
        for (int i = 0; i < labels.size(); i++) {
            final int idx = i;
            final String label = labels.get(i);
            TextView tv = new TextView(mCtx);
            tv.setText(label);
            tv.setSingleLine(true);
            tv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tv.setTextColor(T2);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f);
            tv.setPadding(dp(18), dp(10), dp(18), dp(10));
            GradientDrawable b = new GradientDrawable();
            b.setCornerRadius(dp(22));
            b.setColor(0xFF1E1E20);
            tv.setBackground(b);
            tv.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { mActions.onSuggestion(idx, label); }
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = i == 0 ? 0 : dp(10);
            col.addView(tv, lp);
        }
        return col;
    }

    public ImageView imageThumb(Bitmap bm) {
        ImageView iv = new ImageView(mCtx);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        iv.setImageBitmap(bm);
        GradientDrawable b = new GradientDrawable();
        b.setCornerRadius(dp(16));
        b.setColor(0xFF222224);
        iv.setBackground(b);
        iv.setClipToOutline(true);
        iv.setLayoutParams(new LinearLayout.LayoutParams(dp(120), dp(120)));
        iv.setMinimumWidth(dp(120));
        iv.setMinimumHeight(dp(120));
        iv.setAdjustViewBounds(false);
        iv.setContentDescription("附图");
        return iv;
    }

    private ImageView cardShot(Bitmap bm) {
        ImageView iv = new ImageView(mCtx);
        iv.setImageBitmap(bm);
        iv.setAdjustViewBounds(true);
        iv.setScaleType(ImageView.ScaleType.FIT_START);
        iv.setContentDescription("执行结果卡");
        return iv;
    }

    private ImageView icon(int kind, String desc) {
        ImageView iv = new ImageView(mCtx);
        iv.setImageDrawable(new ChatIcons(kind, T1));
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        iv.setContentDescription(desc);
        return iv;
    }

    private int dp(float v) {
        return Math.round(v * mCtx.getResources().getDisplayMetrics().density);
    }
}
