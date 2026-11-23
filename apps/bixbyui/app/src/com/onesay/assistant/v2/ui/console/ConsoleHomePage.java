package com.onesay.assistant.v2.ui.console;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.List;
import java.util.TimeZone;

/**
 * ★ feat/console-ui（设计稿 ①「打开 App：首页就是对话」）：顶部 One 图标 + OneSay、☰ 历史、⚙ 设置；状态小标签（真实状态）；
 * 问候；「可以这样说」四条（点了 = 用户说了这句）；「今天」块（真数据，没有就不显示）；底部输入栏（＋、输入框、蓝色麦克风）。
 *
 * <p>发出去的话、点的麦克风都交给原来的对话链路：打字 / 建议 → {@code FloatingSheetService.show(text)}（对话窗 ② 里出结果），
 * 麦克风 → {@code Entries.summonVoice}（全屏聆听 ③，说完回 ②）。原来首页的开关和设置整页挪进 ⚙（{@link HomePage} 设置模式）。
 */
final class ConsoleHomePage extends ConsoleNav.Page {

    private final Console c;
    private ConsoleKit k;
    private ConsolePalette p;
    private FlowLayout mChips;
    private TextView mHello;
    private LinearLayout mSugg;
    private View mTodayTitle;
    private LinearLayout mToday;
    private EditText mEdit;
    private ImageView mMic;
    private ConsoleGlyph mMicGlyph, mSendGlyph;
    private boolean mSendMode;
    private ConsoleStatus.Snapshot mSnap;
    private ConsoleChips.Pc mPc;
    private Boolean mLocOn;
    private long mMicDownAt;
    private boolean mMicArmed;
    private int mRetries;

    ConsoleHomePage(Console c) {
        this.c = c;
    }

    private int dp(float v) {
        return k.dp(v);
    }

    @Override protected View create() {
        k = c.kit;
        p = ConsolePalette.of(k.t.dark);
        LinearLayout root = new LinearLayout(c.act);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);

        root.addView(topBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        mChips = new FlowLayout(c.act, dp(8), dp(8));
        mChips.setPadding(dp(18), dp(2), dp(18), dp(4));
        root.addView(mChips, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout col = new LinearLayout(c.act);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(18), dp(4), dp(18), dp(16));
        ScrollView sv = new ScrollView(c.act);
        sv.setFillViewport(true);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        sv.setVerticalScrollBarEnabled(false);
        sv.addView(col, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        mHello = k.text("", 24, p.text, ConsoleKit.W_BOLD);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(26);
        col.addView(mHello, hl);
        TextView sub = k.text(ConsoleHomeModel.SUBTITLE, 15, p.sub, ConsoleKit.W_REGULAR);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sl.topMargin = dp(6);
        col.addView(sub, sl);

        col.addView(section(ConsoleHomeModel.SUGGEST_TITLE), sectionLp());
        mSugg = new LinearLayout(c.act);
        mSugg.setOrientation(LinearLayout.VERTICAL);
        col.addView(mSugg, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        mTodayTitle = section(ConsoleHomeModel.TODAY_TITLE);
        mTodayTitle.setVisibility(View.GONE);
        col.addView(mTodayTitle, sectionLp());
        mToday = new LinearLayout(c.act);
        mToday.setOrientation(LinearLayout.VERTICAL);
        mToday.setBackground(k.round(p.card, 16));
        mToday.setPadding(dp(14), dp(6), dp(14), dp(6));
        mToday.setVisibility(View.GONE);
        col.addView(mToday, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(inputBar(), new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        renderHello();
        renderSuggestions(com.onesay.assistant.v2.pclink.PcLink.paired(c.act));
        ConsoleStatus.Snapshot last = StatusProbe.last();
        mSnap = last != null ? last : StatusProbe.seed(c.act);
        renderChips();
        return root;
    }

    // ------------------------------------------------------------------ 顶部

    private View topBar() {
        LinearLayout bar = k.rowLayout();
        bar.setPadding(dp(18), dp(14), dp(14), dp(10));
        TextView logo = new TextView(c.act);
        logo.setText("One");
        logo.setTextColor(p.onBlue);
        logo.setTypeface(Typeface.DEFAULT_BOLD);
        logo.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logo.setGravity(Gravity.CENTER);
        logo.setBackground(k.round(p.blue, 11));
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        bar.addView(logo, new LinearLayout.LayoutParams(dp(34), dp(34)));
        TextView name = k.text("OneSay", 18, p.text, ConsoleKit.W_BOLD);
        LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        nl.leftMargin = dp(10);
        bar.addView(name, nl);
        bar.addView(iconButton(ConsoleGlyph.MENU, "历史对话", new Runnable() {
            @Override public void run() {
                c.push(new HistoryPage(c));
            }
        }), new LinearLayout.LayoutParams(dp(34), dp(34)));
        View gap = new View(c.act);
        bar.addView(gap, new LinearLayout.LayoutParams(dp(8), 1));
        bar.addView(iconButton(ConsoleGlyph.GEAR, "设置", new Runnable() {
            @Override public void run() {
                c.push(new HomePage(c, true));
            }
        }), new LinearLayout.LayoutParams(dp(34), dp(34)));
        return bar;
    }

    private ImageView iconButton(int glyph, String desc, final Runnable r) {
        ImageView iv = new ImageView(c.act);
        iv.setImageDrawable(new ConsoleGlyph(glyph, p.sub));
        iv.setPadding(dp(8), dp(8), dp(8), dp(8));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(p.card);
        iv.setBackground(k.ripple(bg));
        iv.setContentDescription(desc);
        iv.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                r.run();
            }
        });
        return iv;
    }

    private void renderChips() {
        mChips.removeAllViews();
        List<ConsoleChips.Chip> chips = ConsoleChips.build(mSnap, mPc, mLocOn);
        for (final ConsoleChips.Chip ch : chips) mChips.addView(chip(ch));
        mChips.setVisibility(chips.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private View chip(final ConsoleChips.Chip ch) {
        LinearLayout l = k.rowLayout();
        l.setPadding(dp(10), dp(6), dp(11), dp(6));
        GradientDrawable bg = k.round(p.card, 14);
        l.setBackground(ch.clickable() ? k.ripple(bg) : bg);
        View dot = new View(c.act);
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(p.levelColor(ch.level));
        dot.setBackground(d);
        LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(dp(7), dp(7));
        dl.rightMargin = dp(6);
        l.addView(dot, dl);
        TextView tv = k.text(ch.label, 12, p.sub, ConsoleKit.W_REGULAR);
        tv.setSingleLine(true);
        l.addView(tv);
        l.setContentDescription(ch.label + (ch.clickable() ? "，点按去处理" : ""));
        if (ch.clickable()) {
            l.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    onChip(ch.action);
                }
            });
        }
        return l;
    }

    /** 点黄 / 红标签：直接去对应的处理处。 */
    private void onChip(ConsoleChips.Action a) {
        switch (a) {
            case PC_PAGE:
                c.openPage(com.onesay.assistant.v2.pclink.PcLinkUi.PAGE, com.onesay.assistant.v2.pclink.PcLinkUi.TITLE);
                break;
            case START_WAKE:
                c.startWakeService();
                mChips.postDelayed(new Runnable() {
                    @Override public void run() {
                        refresh();
                    }
                }, 2500);
                break;
            case STATUS_PAGE:
                c.push(new StatusPage(c));
                break;
            case PERM_PAGE:
                c.push(new PermSetupPage(c));
                break;
            case MIC_SETTINGS:
                c.openAppSettings();
                break;
            case ASR_KEY:
                c.editAsrKey(new Runnable() {
                    @Override public void run() {
                        refresh();
                    }
                });
                break;
            case LOCATION_SETTINGS:
                try {
                    c.act.startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS));
                } catch (Throwable t) {
                    c.toast("打不开定位设置，请到系统设置里打开定位");
                }
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ 问候 / 建议 / 今天

    private void renderHello() {
        int h = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY);
        mHello.setText(ConsoleHomeModel.greeting(h));
    }

    private TextView section(String t) {
        return k.text(t, 12, p.dim, ConsoleKit.W_REGULAR);
    }

    private LinearLayout.LayoutParams sectionLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(22);
        lp.bottomMargin = dp(10);
        return lp;
    }

    private boolean mSuggPc;
    private boolean mSuggBuilt;

    private void renderSuggestions(boolean pcPaired) {
        if (mSuggBuilt && mSuggPc == pcPaired) return;
        mSuggBuilt = true;
        mSuggPc = pcPaired;
        mSugg.removeAllViews();
        List<ConsoleHomeModel.Suggestion> l = ConsoleHomeModel.suggestions(pcPaired);
        LinearLayout row = null;
        for (int i = 0; i < l.size(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(c.act);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setBaselineAligned(false);
                LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                if (i > 0) rl.topMargin = dp(10);
                mSugg.addView(row, rl);
            }
            LinearLayout.LayoutParams cl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f);
            if (i % 2 == 1) cl.leftMargin = dp(10);
            row.addView(suggestion(l.get(i)), cl);
        }
    }

    private View suggestion(final ConsoleHomeModel.Suggestion s) {
        LinearLayout box = new LinearLayout(c.act);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(14), dp(12), dp(12), dp(12));
        box.setBackground(k.ripple(k.round(p.card, 16)));
        TextView say = k.text(s.say, 14, p.text, ConsoleKit.W_REGULAR);
        box.addView(say);
        TextView tag = k.text(s.tag, 11, p.sub, ConsoleKit.W_REGULAR);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tl.topMargin = dp(4);
        box.addView(tag, tl);
        box.setContentDescription(s.say + "，点按就当你说了这句");
        k.pressable(box);
        box.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ConsoleHomeModel.tapSuggestion(s, sender());
            }
        });
        return box;
    }

    private void renderToday(List<ConsoleToday.Row> rows) {
        mToday.removeAllViews();
        for (ConsoleToday.Row r : rows) {
            LinearLayout line = k.rowLayout();
            line.setPadding(0, dp(7), 0, dp(7));
            line.addView(k.text(r.label, 13, p.text, ConsoleKit.W_REGULAR), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            line.addView(k.text(r.value, 13, p.sub, ConsoleKit.W_REGULAR));
            mToday.addView(line);
        }
        int vis = rows.isEmpty() ? View.GONE : View.VISIBLE;   // 全空 → 整块（连标题）不显示
        mToday.setVisibility(vis);
        mTodayTitle.setVisibility(vis);
    }

    // ------------------------------------------------------------------ 输入栏：＋ · 输入框 · 麦克风（点一下说话 · 按住说完松手）

    private View inputBar() {
        LinearLayout wrap = new LinearLayout(c.act);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(dp(14), dp(8), dp(14), dp(14));
        LinearLayout box = k.rowLayout();
        box.setGravity(Gravity.CENTER_VERTICAL);
        box.setMinimumHeight(dp(50));
        box.setPadding(dp(8), dp(6), dp(6), dp(6));
        box.setBackground(k.round(p.card2, 25));

        ImageView plus = new ImageView(c.act);
        plus.setImageDrawable(new ConsoleGlyph(ConsoleGlyph.PLUS, p.sub));
        plus.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable pb = new GradientDrawable();
        pb.setShape(GradientDrawable.OVAL);
        pb.setColor(0x00000000);
        pb.setStroke(Math.max(1, dp(1)), p.line);
        plus.setBackground(k.ripple(pb));
        plus.setContentDescription("添加图片或拍照");
        plus.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                if (!overlayOk()) return;
                hideIme();
                com.onesay.assistant.v2.ui.FloatingSheetService.openChat(c.act, true);
            }
        });
        box.addView(plus, new LinearLayout.LayoutParams(dp(32), dp(32)));

        mEdit = new EditText(c.act);
        mEdit.setBackground(null);
        mEdit.setHint(ConsoleHomeModel.INPUT_HINT);
        mEdit.setHintTextColor(p.dim);
        mEdit.setTextColor(p.text);
        mEdit.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        mEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        mEdit.setImeOptions(EditorInfo.IME_ACTION_SEND);
        mEdit.setSingleLine(false);
        mEdit.setRawInputType(InputType.TYPE_CLASS_TEXT);
        mEdit.setHorizontallyScrolling(false);
        mEdit.setMaxLines(4);
        mEdit.setPadding(dp(10), dp(8), dp(6), dp(8));
        mEdit.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                // 只认键盘「发送」：收键盘 / 回车都不发（底栏既定规则）
                if (actionId == EditorInfo.IME_ACTION_SEND) {
                    send();
                    return true;
                }
                return false;
            }
        });
        mEdit.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int n) { }
            @Override public void afterTextChanged(Editable e) {
                setSendMode(ConsoleHomeModel.showSend(e == null ? null : e.toString()));
            }
        });
        box.addView(mEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        mMic = new ImageView(c.act);
        mMicGlyph = new ConsoleGlyph(ConsoleGlyph.MIC, p.onBlue);
        mSendGlyph = new ConsoleGlyph(ConsoleGlyph.PLANE, p.onBlue).stroke(1.9f);   // 有字 → 纸飞机「发送」
        mMic.setImageDrawable(mMicGlyph);
        mMic.setPadding(dp(9), dp(9), dp(9), dp(9));
        GradientDrawable mb = new GradientDrawable();
        mb.setShape(GradientDrawable.OVAL);
        mb.setColor(p.blue);
        mMic.setBackground(k.ripple(mb));
        mMic.setContentDescription("说话：点一下说，或按住说完松手");
        mMic.setOnTouchListener(new View.OnTouchListener() {
            @Override public boolean onTouch(View v, MotionEvent e) {
                return onMicTouch(e);
            }
        });
        box.addView(mMic, new LinearLayout.LayoutParams(dp(38), dp(38)));
        wrap.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView hint = k.text(ConsoleHomeModel.BAR_HINT, 11, p.dim, ConsoleKit.W_REGULAR);
        hint.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams hl = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hl.topMargin = dp(8);
        wrap.addView(hint, hl);
        return wrap;
    }

    private void setSendMode(boolean send) {
        if (send == mSendMode) return;
        mSendMode = send;
        mMic.setImageDrawable(send ? mSendGlyph : mMicGlyph);
        mMic.setContentDescription(send ? "发送" : "说话：点一下说，或按住说完松手");
    }

    /** 有字 = 发送；没字 = 按下就开始听（不弹键盘），松手时按住够久就当说完了。 */
    private boolean onMicTouch(MotionEvent e) {
        int a = e.getActionMasked();
        if (mSendMode) {
            if (a == MotionEvent.ACTION_UP) send();
            return true;
        }
        if (a == MotionEvent.ACTION_DOWN) {
            mMicArmed = false;
            if (!overlayOk()) return true;
            if (com.onesay.assistant.v2.wake.AssistantPause.isPaused(c.act)) {   // 暂停时唤起会被挡掉：说清楚，不让人干按
                c.toast("助手暂停着：到右上角设置里打开总开关再说话");
                return true;
            }
            hideIme();
            mMicDownAt = android.os.SystemClock.uptimeMillis();
            mMicArmed = true;
            mMic.setPressed(true);
            com.onesay.assistant.v2.entry.Entries.summonVoice(c.act, "console-home");   // → 全屏聆听（③）
            return true;
        }
        if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
            mMic.setPressed(false);
            if (!mMicArmed) return true;
            mMicArmed = false;
            long held = android.os.SystemClock.uptimeMillis() - mMicDownAt;
            switch (ConsoleHomeModel.onRelease(held, com.onesay.assistant.v2.wake.WakeService.canFinishCapture())) {
                case HOLD_FINISH:
                    com.onesay.assistant.v2.wake.WakeService.finishCaptureFromUi("首页按住说完松手");
                    break;
                case HOLD_CANCEL: {
                    com.onesay.assistant.v2.ui.FloatingSheetService s = com.onesay.assistant.v2.ui.FloatingSheetService.getInstance();
                    if (s != null) s.onStopListening();   // 按住没说话就松手：停掉，不发
                    break;
                }
                default:
                    break;   // 点一下：接着听，自己说完为止
            }
            return true;
        }
        return true;
    }

    private void send() {
        if (!overlayOk()) return;
        boolean sent = ConsoleHomeModel.submit(mEdit.getText() == null ? null : mEdit.getText().toString(), sender());
        if (sent) {
            mEdit.setText("");
            hideIme();
        }
    }

    /** 发出一句话：打开对话窗（②）并提交，和用户在对话窗里打字一模一样。 */
    private ConsoleHomeModel.Sender sender() {
        return new ConsoleHomeModel.Sender() {
            @Override public void send(String text) {
                if (!overlayOk()) return;
                com.onesay.assistant.v2.ui.FloatingSheetService.show(c.act, text);
            }
        };
    }

    /** 对话窗 / 全屏聆听是悬浮窗：没有悬浮窗权限就先说清楚、带去授权。 */
    private boolean overlayOk() {
        if (Build.VERSION.SDK_INT < 23) return true;
        try {
            if (Settings.canDrawOverlays(c.act)) return true;
        } catch (Throwable t) {
            return true;
        }
        c.toast("对话要显示在最上层：先给 OneSay 打开「悬浮窗」权限");
        c.openOverlaySettings();
        return false;
    }

    private void hideIme() {
        try {
            InputMethodManager imm = (InputMethodManager) c.act.getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(mEdit.getWindowToken(), 0);
            mEdit.clearFocus();
        } catch (Throwable ignore) {
        }
    }

    // ------------------------------------------------------------------ 刷新（每次露出来）

    @Override public void onShown() {
        refresh();
    }

    void refresh() {
        if (view == null) return;
        renderHello();
        StatusProbe.run(c.act, c.compiler, new StatusProbe.Listener() {
            @Override public void onStatus(ConsoleStatus.Snapshot s) {
                if (view == null) return;
                mSnap = s;
                renderChips();
                // 语音引擎还在启动：过 2 秒再探一次（最多 6 次）
                if (s.done && !s.paused && ConsoleChips.wake(s).level == ConsoleStatus.Level.WARN && mRetries < 6) {
                    mRetries++;
                    mChips.postDelayed(new Runnable() {
                        @Override public void run() {
                            if (view != null && view.isAttachedToWindow()) refresh();
                        }
                    }, 2000);
                } else if (ConsoleChips.wake(s).level == ConsoleStatus.Level.OK) {
                    mRetries = 0;
                }
            }
        });
        final Context app = c.act.getApplicationContext();
        final ConsoleChips.Pc pc = new ConsoleChips.Pc();
        final com.onesay.assistant.v2.kernel.FeatureSwitch sw = com.onesay.assistant.v2.pclink.PcLink.SWITCH;
        pc.state = PcEntry.state(sw.on(app), sw.killed(app), com.onesay.assistant.v2.pclink.PcLink.paired(app), com.onesay.assistant.v2.pclink.PcLink.keyLost(app));
        pc.name = com.onesay.assistant.v2.pclink.PcLink.pcName(app);
        if (mPc != null && pc.state.equals(mPc.state)) pc.online = mPc.online;   // 先画上次的在线状态，探完再改
        mPc = pc;
        mLocOn = locationOn(app);
        renderSuggestions(PcEntry.PAIRED.equals(pc.state));
        renderChips();
        com.onesay.assistant.v2.util.Threads.start("console-home2", new Runnable() {
            @Override public void run() {
                final ConsoleToday.Input in = todayInput(app);
                c.ui(new Runnable() {
                    @Override public void run() {
                        if (view == null) return;
                        renderToday(ConsoleToday.rows(in, System.currentTimeMillis(), TimeZone.getDefault()));
                    }
                });
                if (!PcEntry.PAIRED.equals(pc.state)) return;
                Boolean online;
                try {
                    online = com.onesay.assistant.v2.pclink.PcLink.probe(app).ok;
                } catch (Throwable t) {
                    online = Boolean.FALSE;
                }
                final Boolean fo = online;
                c.ui(new Runnable() {
                    @Override public void run() {
                        if (view == null || mPc != pc) return;
                        pc.online = fo;
                        pc.name = com.onesay.assistant.v2.pclink.PcLink.pcName(app);   // 第一次在线时会顺带取到电脑名
                        renderChips();
                    }
                });
            }
        });
    }

    /** 系统定位总开关；查不到 = null（不显示标签）。 */
    static Boolean locationOn(Context ctx) {
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                android.location.LocationManager lm = (android.location.LocationManager) ctx.getSystemService(Context.LOCATION_SERVICE);
                return lm == null ? null : Boolean.valueOf(lm.isLocationEnabled());
            }
            @SuppressWarnings("deprecation")
            int mode = Settings.Secure.getInt(ctx.getContentResolver(), Settings.Secure.LOCATION_MODE);
            return Boolean.valueOf(mode != 0);
        } catch (Throwable t) {
            return null;
        }
    }

    /** 「今天」的真数据（后台线程）：系统下一个闹钟、今天的待办、电脑任务。读不到的项留 null。 */
    static ConsoleToday.Input todayInput(Context app) {
        ConsoleToday.Input in = new ConsoleToday.Input();
        try {
            android.app.AlarmManager am = (android.app.AlarmManager) app.getSystemService(Context.ALARM_SERVICE);
            android.app.AlarmManager.AlarmClockInfo ci = am == null ? null : am.getNextAlarmClock();
            if (ci != null) in.nextAlarmAt = Long.valueOf(ci.getTriggerTime());
        } catch (Throwable ignore) {
        }
        try {
            long now = System.currentTimeMillis();
            long end = com.onesay.assistant.v2.s3.S3Schedule.dayEnd(now, TimeZone.getDefault());
            int n = 0;
            for (com.onesay.assistant.v2.s3.S3Store.Todo t : com.onesay.assistant.v2.s3.S3Hooks.engine(app).store().openTodos()) {
                if (t.dueAt <= 0 || t.dueAt <= end) n++;
            }
            in.openTodos = Integer.valueOf(n);
        } catch (Throwable ignore) {
        }
        try {
            in.pcRunning = Integer.valueOf(com.onesay.assistant.v2.pclink.PcGoals.runningCount());
        } catch (Throwable ignore) {
        }
        return in;
    }
}
