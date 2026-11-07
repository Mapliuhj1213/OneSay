/*
 * [富内容输出插件 · 宿主适配] OneSay 自写。把 core 的块画成原生 View。
 */
package com.onesay.assistant.plug.richout.host;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.method.LinkMovementMethod;
import android.text.style.AbsoluteSizeSpan;
import android.text.style.BackgroundColorSpan;
import android.text.style.ClickableSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.assistant.plug.richout.core.CodeHighlighter;
import com.onesay.assistant.plug.richout.core.InlineMd;
import com.onesay.assistant.plug.richout.core.MarkdownBlocks;
import com.onesay.assistant.plug.richout.core.RichBlock;
import com.onesay.assistant.plug.richout.core.RichOutPlugs;

import java.util.List;

/**
 * 回答 → 原生 View：段落 / 标题 / 列表 / 引用用 TextView + Span；表格用 TableLayout（横向可滚）；
 * 代码块是卡片（语言标签、复制、长代码折叠、横向滚动、词法着色）；图交给 {@link DiagramCard}（沙箱 WebView）。
 * 纯文本回答根本不会走到这里（宿主先用 RichText.needsRich 判断），所以不影响原来的速度。
 */
public final class RichRenderer {

    private RichRenderer() {}

    /** 配色 / 字号（深色面板）。 */
    public static final class Style {
        public int t1 = 0xFFF0EEEA;
        public int t2 = 0xFFB2AFAA;
        public int t3 = 0xFF807E7A;
        public int accent = 0xFF64D2FF;
        public int codeBg = 0x14FFFFFF;
        public int inlineCodeBg = 0x22FFFFFF;
        public float bodySp = 16f;
        public float codeSp = 13f;
        public boolean dark = true;
    }

    // Xcode 深色配色（对比度够、色弱也分得开）
    static int colorOf(CodeHighlighter.Kind k) {
        switch (k) {
            case KEYWORD: return 0xFFFF7AB2;
            case TYPE: return 0xFF5DD8FF;
            case STRING: return 0xFFFC6A5D;
            case NUMBER: return 0xFFD0BF69;
            case COMMENT: return 0xFF7F8C98;
            case FUNCTION: return 0xFF67B7A4;
            case ANNOTATION: return 0xFFFD8F3F;
            case TAG: return 0xFF5DD8FF;
            case ATTR: return 0xFFD0A8FF;
            case VARIABLE: return 0xFFA167E6;
            default: return 0xFFE5E5EA;
        }
    }

    /** 终稿：整棵 View。 */
    public static LinearLayout render(Context ctx, String text, Style st) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        List<RichBlock> blocks = MarkdownBlocks.parse(text);
        int diagrams = 0;
        boolean first = true;
        for (RichBlock b : blocks) {
            View v;
            switch (b.kind) {
                case CODE:
                    v = codeCard(ctx, b.lang, b.text, st);
                    break;
                case DIAGRAM:
                    if (RichOutHost.diagramsOn() && diagrams < RichOutPlugs.MAX_DIAGRAMS && !b.open) {
                        diagrams++;
                        v = new DiagramCard(ctx, b.lang, b.text, st).view();
                    } else {
                        v = codeCard(ctx, b.lang, b.text, st);
                    }
                    break;
                case TABLE:
                    v = table(ctx, b, st);
                    break;
                case RULE: {
                    View hr = new View(ctx);
                    hr.setBackgroundColor(0x22FFFFFF);
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 1) / 2));
                    lp.topMargin = dp(ctx, 8);
                    lp.bottomMargin = dp(ctx, 8);
                    box.addView(hr, lp);
                    continue;
                }
                case QUOTE:
                    v = quote(ctx, b.text, st);
                    break;
                default: {
                    TextView tv = textView(ctx, st);
                    tv.setText(blockText(ctx, b, st));
                    v = tv;
                }
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = first ? 0 : dp(ctx, b.kind == RichBlock.Kind.HEADING ? 12 : 8);
            box.addView(v, lp);
            first = false;
        }
        return box;
    }

    /**
     * 只放行表格（富内容关、用户明确要表格）：表格用原生 TableLayout、单元格纯文本（不设 Span、不响应链接）；
     * 表格外的块只当纯文本句子；代码 / 图不画（不建 WebView、不执行任何脚本）。
     */
    public static LinearLayout renderTablesOnly(Context ctx, String text, Style st) {
        LinearLayout box = new LinearLayout(ctx);
        box.setOrientation(LinearLayout.VERTICAL);
        boolean first = true;
        for (RichBlock b : MarkdownBlocks.parse(text)) {
            View v;
            if (b.kind == RichBlock.Kind.TABLE) {
                v = table(ctx, b, st, true);
            } else if (b.kind == RichBlock.Kind.CODE || b.kind == RichBlock.Kind.DIAGRAM || b.kind == RichBlock.Kind.RULE) {
                continue;
            } else {
                String plain = b.kind == RichBlock.Kind.LIST ? listText(b) : InlineMd.plain(b.text);
                if (plain == null || plain.trim().length() == 0) continue;
                TextView tv = plainTextView(ctx, st);
                tv.setText(plain.trim());
                v = tv;
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.topMargin = first ? 0 : dp(ctx, 8);
            box.addView(v, lp);
            first = false;
        }
        return box;
    }

    private static String listText(RichBlock b) {
        StringBuilder sb = new StringBuilder();
        for (String it : b.items) sb.append("· ").append(InlineMd.plain(it.replace("\t", ""))).append('\n');
        return sb.toString();
    }

    /** 纯文本 TextView：不设 LinkMovementMethod、不自动识别链接。 */
    private static TextView plainTextView(Context ctx, Style st) {
        TextView tv = new TextView(ctx);
        tv.setTextColor(st.t1);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, st.bodySp);
        tv.setLineSpacing(dp(ctx, 3), 1.15f);
        tv.setAutoLinkMask(0);
        tv.setLinksClickable(false);
        return tv;
    }

    /** 流式中：单个 TextView 能装下的近似样式（代码等宽、图显示占位），收尾时再换成 {@link #render}。 */
    public static CharSequence streaming(Context ctx, String text, Style st) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        for (RichBlock b : MarkdownBlocks.parse(text)) {
            if (sb.length() > 0) sb.append("\n");
            int s0 = sb.length();
            switch (b.kind) {
                case CODE:
                    sb.append(b.text);
                    sb.setSpan(new TypefaceSpan("monospace"), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new AbsoluteSizeSpan((int) st.codeSp, true), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new ForegroundColorSpan(st.t2), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case DIAGRAM:
                    sb.append("〔").append(kindLabel(b.lang)).append(b.open ? "生成中…〕" : "〕");
                    sb.setSpan(new ForegroundColorSpan(st.t3), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case TABLE:
                    sb.append("〔表格〕");
                    sb.setSpan(new ForegroundColorSpan(st.t3), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case RULE:
                    break;
                default:
                    sb.append(blockText(ctx, b, st));
            }
        }
        return sb;
    }

    static String kindLabel(String kind) {
        if ("svg".equals(kind)) return "SVG 图";
        if ("mermaid".equals(kind)) return "流程图";
        if ("chart".equals(kind)) return "图表";
        return "图";
    }

    private static TextView textView(Context ctx, Style st) {
        TextView tv = new TextView(ctx);
        tv.setTextColor(st.t1);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, st.bodySp);
        tv.setLineSpacing(dp(ctx, 3), 1.15f);
        tv.setLinkTextColor(st.accent);
        tv.setMovementMethod(LinkMovementMethod.getInstance());
        tv.setHighlightColor(0x3364D2FF);
        return tv;
    }

    /** 段落 / 标题 / 列表 → 带 Span 的文字。 */
    static CharSequence blockText(Context ctx, RichBlock b, Style st) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        switch (b.kind) {
            case HEADING: {
                appendInline(ctx, sb, b.text, st);
                int size = b.level <= 1 ? 21 : b.level == 2 ? 19 : 17;
                sb.setSpan(new AbsoluteSizeSpan(size, true), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                break;
            }
            case LIST: {
                int n = b.start;
                for (int i = 0; i < b.items.size(); i++) {
                    if (i > 0) sb.append("\n");
                    String it = b.items.get(i);
                    int depth = 0;
                    while (depth < it.length() && it.charAt(depth) == '\t') depth++;
                    it = it.substring(depth);
                    String bullet = depth > 0 ? "◦ " : b.ordered ? (n++) + ". " : "• ";
                    if (depth > 0 && it.matches("^\\d+\\. .*")) bullet = "";
                    int s0 = sb.length();
                    sb.append(bullet);
                    sb.setSpan(new ForegroundColorSpan(st.t3), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    appendInline(ctx, sb, it, st);
                    int indent = dp(ctx, 4 + depth * 18);
                    int hang = indent + (int) (st.bodySp * ctx.getResources().getDisplayMetrics().scaledDensity * (b.ordered && depth == 0 ? 1.3f : 0.9f));
                    sb.setSpan(new LeadingMarginSpan.Standard(indent, hang), s0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                break;
            }
            default:
                appendInline(ctx, sb, b.text, st);
        }
        return sb;
    }

    /** 行内 Markdown → Span（粗 / 斜 / 删 / 行内代码 / 可点链接）。 */
    static void appendInline(Context ctx, SpannableStringBuilder sb, String md, Style st) {
        InlineMd.Result r = InlineMd.parse(md);
        int base = sb.length();
        sb.append(r.text);
        for (InlineMd.Span s : r.spans) {
            int a = base + s.start, e = base + s.end;
            if (e <= a) continue;
            switch (s.type) {
                case BOLD:
                    sb.setSpan(new StyleSpan(Typeface.BOLD), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case ITALIC:
                    sb.setSpan(new StyleSpan(Typeface.ITALIC), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case STRIKE:
                    sb.setSpan(new StrikethroughSpan(), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case CODE:
                    sb.setSpan(new TypefaceSpan("monospace"), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    sb.setSpan(new BackgroundColorSpan(st.inlineCodeBg), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                case LINK:
                    sb.setSpan(new LinkSpan(s.url, st.accent), a, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                    break;
                default:
                    break;
            }
        }
    }

    /** 点击走 {@link LinkRouter}（应用内阅读器 / 确认 / 拦截），不交给系统浏览器。 */
    static final class LinkSpan extends ClickableSpan {
        final String url;
        final int color;

        LinkSpan(String url, int color) {
            this.url = url;
            this.color = color;
        }

        @Override public void onClick(View widget) {
            LinkRouter.open(widget.getContext(), url);
        }

        @Override public void updateDrawState(TextPaint ds) {
            ds.setColor(color);
            ds.setUnderlineText(true);
        }
    }

    private static View quote(Context ctx, String text, Style st) {
        LinearLayout row = new LinearLayout(ctx);
        row.setOrientation(LinearLayout.HORIZONTAL);
        View bar = new View(ctx);
        bar.setBackgroundColor(0x55FFFFFF);
        row.addView(bar, new LinearLayout.LayoutParams(dp(ctx, 3), ViewGroup.LayoutParams.MATCH_PARENT));
        TextView tv = textView(ctx, st);
        tv.setTextColor(st.t2);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        appendInline(ctx, sb, text, st);
        tv.setText(sb);
        tv.setPadding(dp(ctx, 10), 0, 0, 0);
        row.addView(tv, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        return row;
    }

    private static View table(Context ctx, RichBlock b, Style st) {
        return table(ctx, b, st, false);
    }

    private static View table(Context ctx, RichBlock b, Style st, boolean plainCells) {
        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(false);
        TableLayout tl = new TableLayout(ctx);
        tl.setBackground(rounded(st.codeBg, dp(ctx, 10)));
        tl.setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4));
        tl.addView(row(ctx, b.header, b.aligns, st, true, plainCells));
        for (int i = 0; i < b.rows.size(); i++) {
            TableRow r = row(ctx, b.rows.get(i), b.aligns, st, false, plainCells);
            if (i % 2 == 0) r.setBackgroundColor(0x08FFFFFF);
            tl.addView(r);
        }
        hs.addView(tl);
        return hs;
    }

    private static TableRow row(Context ctx, List<String> cells, List<Character> aligns, Style st, boolean header, boolean plainCells) {
        TableRow r = new TableRow(ctx);
        for (int c = 0; c < cells.size(); c++) {
            TextView tv = plainCells ? plainTextView(ctx, st) : textView(ctx, st);
            tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, st.bodySp - 2);
            tv.setMaxWidth(dp(ctx, 220));
            tv.setPadding(dp(ctx, 8), dp(ctx, 6), dp(ctx, 8), dp(ctx, 6));
            SpannableStringBuilder sb = new SpannableStringBuilder();
            if (plainCells) sb.append(InlineMd.plain(cells.get(c)));   // 表格轮：单元格纯文本，不解析链接 / 行内代码
            else appendInline(ctx, sb, cells.get(c), st);
            if (header) sb.setSpan(new StyleSpan(Typeface.BOLD), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            tv.setText(sb);
            if (!header) tv.setTextColor(st.t2);
            char a = c < aligns.size() ? aligns.get(c) : 'L';
            tv.setGravity(a == 'R' ? Gravity.END : a == 'C' ? Gravity.CENTER_HORIZONTAL : Gravity.START);
            r.addView(tv);
        }
        return r;
    }

    // ------------------------------------------------------------------ 代码卡片

    static View codeCard(final Context ctx, String lang, final String code, final Style st) {
        final LinearLayout card = new LinearLayout(ctx);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(rounded(st.codeBg, dp(ctx, 12)));

        LinearLayout head = new LinearLayout(ctx);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(ctx, 12), dp(ctx, 6), dp(ctx, 4), dp(ctx, 2));
        TextView label = new TextView(ctx);
        label.setText(lang == null || lang.length() == 0 ? "代码" : lang);
        label.setTextColor(st.t3);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        head.addView(label, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        final String[] lines = code.split("\n", -1);
        final boolean foldable = lines.length > RichOutPlugs.CODE_FOLD_LINES;
        final int shownWhenFolded = RichOutPlugs.CODE_FOLD_LINES - 4;
        final TextView toggle = chip(ctx, "展开全部 " + lines.length + " 行", st);
        if (foldable) head.addView(toggle);
        TextView copy = chip(ctx, "复制", st);
        head.addView(copy);
        card.addView(head);

        HorizontalScrollView hs = new HorizontalScrollView(ctx);
        hs.setHorizontalScrollBarEnabled(true);
        hs.setFillViewport(true);
        final TextView body = new TextView(ctx);
        body.setTypeface(Typeface.MONOSPACE);
        body.setTextSize(TypedValue.COMPLEX_UNIT_SP, st.codeSp);
        body.setTextColor(0xFFE5E5EA);
        body.setLineSpacing(dp(ctx, 2), 1.0f);
        body.setPadding(dp(ctx, 12), dp(ctx, 4), dp(ctx, 12), dp(ctx, 10));
        body.setTextIsSelectable(false);
        final CharSequence full = highlighted(lang, code);
        final CharSequence folded = foldable ? highlighted(lang, join(lines, shownWhenFolded) + "\n…") : full;
        body.setText(foldable ? folded : full);
        hs.addView(body);
        card.addView(hs);

        final boolean[] open = {false};
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                open[0] = !open[0];
                body.setText(open[0] ? full : folded);
                toggle.setText(open[0] ? "收起" : "展开全部 " + lines.length + " 行");
            }
        });
        copy.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                copyText(ctx, code, "代码已复制");
            }
        });
        return card;
    }

    static CharSequence highlighted(String lang, String code) {
        SpannableStringBuilder sb = new SpannableStringBuilder(code);
        for (CodeHighlighter.Token t : CodeHighlighter.tokenize(lang, code)) {
            if (t.end > sb.length()) break;
            sb.setSpan(new ForegroundColorSpan(colorOf(t.kind)), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            if (t.kind == CodeHighlighter.Kind.COMMENT) sb.setSpan(new StyleSpan(Typeface.ITALIC), t.start, t.end, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return sb;
    }

    private static String join(String[] lines, int n) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < Math.min(n, lines.length); i++) {
            if (i > 0) b.append('\n');
            b.append(lines[i]);
        }
        return b.toString();
    }

    static TextView chip(Context ctx, String text, Style st) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(st.accent);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        t.setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6));
        t.setBackground(rounded(0x00000000, dp(ctx, 12)));
        return t;
    }

    static void copyText(Context ctx, String text, String toast) {
        try {
            ClipboardManager cm = (ClipboardManager) ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm != null) cm.setPrimaryClip(ClipData.newPlainText("OneSay", text));
            Toast.makeText(ctx.getApplicationContext(), toast, Toast.LENGTH_SHORT).show();
        } catch (Throwable ignored) {
        }
    }

    static GradientDrawable rounded(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    static int dp(Context ctx, float v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }

    static FrameLayout.LayoutParams matchWrap() {
        return new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    static int parse(String c) {
        return Color.parseColor(c);
    }
}
