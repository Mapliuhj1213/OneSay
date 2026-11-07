package com.onesay.assistant.v2.quicknote.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.assistant.v2.quicknote.QuickNoteActionRunner;
import com.onesay.assistant.v2.quicknote.QuickNoteActions;
import com.onesay.assistant.v2.quicknote.QuickNoteBook;
import com.onesay.assistant.v2.quicknote.QuickNoteEntry;
import com.onesay.assistant.v2.quicknote.QuickNoteHooks;
import com.onesay.assistant.v2.quicknote.QuickNoteSearch;
import com.onesay.assistant.v2.quicknote.QuickNoteThumbs;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/**
 * [quicknote] R10 控制台里的「记一记」列表 / 详情 / 最近删除（D12：保留 30 天）。
 * <b>不新增 Activity、不改 Manifest</b>：在 MainActivity 里换 content view，返回键回控制台（MainActivity 处理）。
 * 入口：控制台「记一记」卡「打开记一记」、岛上结果卡点正文（page=quicknote&amp;id=…）、语音「打开记一记」。
 */
public final class QuickNotePage {

    /** 页面宿主（MainActivity 实现）。 */
    public interface Host {
        void showPage(View v);

        void closePage();

        /** ★ R12：列表右上角「设置」→ 记一记设置页（原控制台「记一记」卡的 5 个开关）。 */
        void openQuickNoteSettings();
    }

    static final String[] CHIPS = {"全部", "码", "快递", "订单", "日程", "地址", "聊天", "文章", "联系人", "随口记", "其他"};
    /** ★ R12（D9）：颜色跟控制台主题走（深 / 浅，苔绿主色），不再是冷灰 + iOS 蓝。 */
    private final com.onesay.assistant.v2.ui.console.ConsoleTheme mT = com.onesay.assistant.v2.ui.console.ConsoleTheme.current();
    final int BG = mT.bg, CARD = mT.surface, LINE = mT.line, TXT = mT.text1, SUB = mT.text2, ACC = mT.accent, GREEN = mT.accent,
            RED = mT.danger, FIELD = mT.surface2, TXT2 = mT.text2, POS = mT.accentContainer;

    private final Activity mAct;
    private final Host mHost;
    private final QuickNoteBook mBook;
    private String mChip = "全部";
    private String mQuery = "";
    private LinearLayout mListBox;
    private TextView mStat;
    private boolean mOnList;

    /** 返回键：详情 / 最近删除 → 列表（true）；已在列表 → false（宿主回控制台）。 */
    public boolean back() {
        if (mOnList) return false;
        showList();
        return true;
    }

    /** 打开（id &gt; 0 = 直达详情）。不是 MainActivity 时发 Intent 过去。 */
    public static void open(Activity act, long id) {
        if (act instanceof Host) {
            QuickNotePage p = new QuickNotePage(act, (Host) act);
            if (id > 0) p.showDetailById(id);
            else p.showList();
            return;
        }
        QuickNoteHooks.openDetail(act.getApplicationContext(), id);
    }

    public QuickNotePage(Activity act, Host host) {
        mAct = act;
        mHost = host;
        mBook = QuickNoteHooks.book(act);
    }

    // ------------------------------------------------------------------ 列表

    public void showList() {
        LinearLayout root = page();
        mOnList = true;
        root.addView(topBar("记一记", "最近删除", new View.OnClickListener() {
            @Override public void onClick(View v) {
                showDeleted();
            }
        }, "设置", new View.OnClickListener() {
            @Override public void onClick(View v) {
                mHost.openQuickNoteSettings();
            }
        }));
        final EditText search = new EditText(mAct);
        search.setHint("搜标题、内容、来源 App…");
        search.setHintTextColor(SUB);
        search.setTextColor(TXT);
        search.setSingleLine(true);
        search.setInputType(InputType.TYPE_CLASS_TEXT);
        search.setText(mQuery);
        search.setBackground(round(FIELD, 10));
        search.setPadding(dp(12), dp(8), dp(12), dp(8));
        root.addView(search, lpW(dp(8)));
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {
                mQuery = s.toString().trim();
                reload();
            }
        });
        HorizontalScrollView hs = new HorizontalScrollView(mAct);
        hs.setHorizontalScrollBarEnabled(false);
        final LinearLayout chips = new LinearLayout(mAct);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(chips);
        root.addView(hs, lpW(dp(8)));
        for (final String c : CHIPS) {
            final TextView t = new TextView(mAct);
            t.setText(c);
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setPadding(dp(12), dp(6), dp(12), dp(6));
            styleChip(t, c.equals(mChip));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.rightMargin = dp(6);
            chips.addView(t, lp);
            t.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    mChip = c;
                    for (int i = 0; i < chips.getChildCount(); i++) {
                        TextView x = (TextView) chips.getChildAt(i);
                        styleChip(x, x.getText().toString().equals(mChip));
                    }
                    reload();
                }
            });
        }
        mStat = small("", SUB);
        root.addView(mStat, lpW(dp(8)));
        mListBox = new LinearLayout(mAct);
        mListBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(mListBox, lpW(dp(4)));
        show(root);
        reload();
    }

    private void reload() {
        final String chip = mChip, query = mQuery;
        bg(new Runnable() {
            @Override public void run() {
                QuickNoteHooks.housekeep(mAct);
                long now = System.currentTimeMillis();
                List<QuickNoteEntry> all = mBook.list();
                final List<QuickNoteEntry> rows = filter(all, chip, query, now);
                long fen = 0;
                int bills = 0;
                long[] month = monthRange(now);
                for (QuickNoteEntry e : all) {
                    if ("订单".equals(e.category) && e.createdAt >= month[0] && e.createdAt < month[1] && e.amountFen > 0) {
                        fen += e.amountFen;
                        bills++;
                    }
                }
                final String stat = "共 " + all.size() + " 条" + (rows.size() != all.size() ? " · 筛出 " + rows.size() + " 条" : "")
                        + ("订单".equals(chip) ? " · 本月账单 " + bills + " 笔 ¥" + yuan(fen) : "");
                ui(new Runnable() {
                    @Override public void run() {
                        renderRows(rows, stat);
                    }
                });
            }
        });
    }

    static List<QuickNoteEntry> filter(List<QuickNoteEntry> all, String chip, String query, long now) {
        List<QuickNoteEntry> base = new ArrayList<QuickNoteEntry>();
        for (QuickNoteEntry e : all) if ("全部".equals(chip) || QuickNoteSearch.inCategory(e, chip)) base.add(e);
        if (query == null || query.length() == 0) return base;
        List<QuickNoteEntry> out = new ArrayList<QuickNoteEntry>();
        String q = query.toLowerCase();
        for (QuickNoteEntry e : base) {
            String hay = (e.title + " " + e.summary + " " + e.body + " " + e.notes + " " + e.appLabel + " " + e.tags + " " + e.codeValue).toLowerCase();
            if (hay.contains(q)) out.add(e);
        }
        if (out.isEmpty()) {
            for (QuickNoteSearch.Hit h : QuickNoteSearch.search(base, query, now, TimeZone.getDefault(), 50)) out.add(h.e);
        }
        return out;
    }

    private void renderRows(List<QuickNoteEntry> rows, String stat) {
        if (mListBox == null) return;
        mStat.setText(stat);
        mListBox.removeAllViews();
        if (rows.isEmpty()) {
            mListBox.addView(small("这里还没有记一记。长按 Bixby 键记下当前屏幕，或说「随口记 …」。", SUB), lpW(dp(16)));
            return;
        }
        String lastGroup = null;
        long now = System.currentTimeMillis();
        for (final QuickNoteEntry e : rows) {
            String g = timeGroup(e.createdAt, now);
            if (!g.equals(lastGroup)) {
                TextView h = small(g, ACC);
                h.setTypeface(Typeface.DEFAULT_BOLD);
                mListBox.addView(h, lpW(dp(12)));
                lastGroup = g;
            }
            View row = row(e, false);
            row.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showDetail(e);
                }
            });
            row.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    confirm("删除这条记一记？", "30 天内可以在「最近删除」里恢复。", "删除", new Runnable() {
                        @Override public void run() {
                            bg(new Runnable() {
                                @Override public void run() {
                                    QuickNoteHooks.undo(mAct, e.id);
                                    ui(new Runnable() { @Override public void run() { reload(); } });
                                }
                            });
                        }
                    });
                    return true;
                }
            });
            mListBox.addView(row, lpW(dp(6)));
        }
    }

    private View row(QuickNoteEntry e, boolean deleted) {
        LinearLayout row = new LinearLayout(mAct);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setBackground(round(CARD, 12));
        row.setPadding(dp(12), dp(10), dp(12), dp(10));
        LinearLayout col = new LinearLayout(mAct);
        col.setOrientation(LinearLayout.VERTICAL);
        row.addView(col, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        TextView tag = small(e.category + (e.tags.contains("已记账") ? " · 已记账" : "") + QuickNoteBook.codeStateText(e), GREEN);
        col.addView(tag);
        TextView title = new TextView(mAct);
        title.setText(e.title);
        title.setTextColor(TXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        col.addView(title);
        if (!e.summary.equals(e.title)) {
            TextView sum = small(e.summary, TXT2);
            sum.setSingleLine(true);
            sum.setEllipsize(TextUtils.TruncateAt.END);
            col.addView(sum);
        }
        String meta = mBook.when(e.createdAt, System.currentTimeMillis()).trim() + (e.appLabel.length() > 0 ? " · " + e.appLabel : "")
                + (deleted ? " · 删除于 " + mBook.when(e.deletedAt, System.currentTimeMillis()).trim() : "");
        col.addView(small(meta, SUB));
        if (e.thumbPath.length() > 0 && new File(e.thumbPath).exists()) {
            ImageView iv = new ImageView(mAct);
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setImageBitmap(BitmapFactory.decodeFile(e.thumbPath));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(48), dp(72));
            lp.leftMargin = dp(10);
            row.addView(iv, lp);
        }
        return row;
    }

    // ------------------------------------------------------------------ 详情

    public void showDetailById(final long id) {
        bg(new Runnable() {
            @Override public void run() {
                final QuickNoteEntry e = mBook.store().get(id);
                ui(new Runnable() {
                    @Override public void run() {
                        if (e == null) {
                            Toast.makeText(mAct, "这条记一记已经不在了", Toast.LENGTH_SHORT).show();
                            showList();
                        } else {
                            showDetail(e);
                        }
                    }
                });
            }
        });
    }

    public void showDetail(final QuickNoteEntry e) {
        LinearLayout root = page();
        root.addView(topBar(e.deleted ? "已删除的记一记" : "记一记详情", null, null));
        root.addView(small(e.category + " · " + mBook.when(e.createdAt, System.currentTimeMillis()).trim()
                + (e.appLabel.length() > 0 ? " · 来自 " + e.appLabel : "") + QuickNoteBook.codeStateText(e), GREEN), lpW(dp(8)));
        TextView title = new TextView(mAct);
        title.setText(e.title);
        title.setTextColor(TXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextIsSelectable(true);
        root.addView(title, lpW(dp(6)));
        if (e.hasCode()) {
            TextView code = new TextView(mAct);
            code.setText(e.codeKind + "  " + e.codeValue);
            code.setTextColor(TXT);
            code.setTextSize(TypedValue.COMPLEX_UNIT_SP, 28);
            code.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            code.setTextIsSelectable(true);
            root.addView(code, lpW(dp(8)));
        }
        if (!e.summary.equals(e.title)) {
            TextView sum = small(e.summary, TXT);
            sum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            sum.setTextIsSelectable(true);
            root.addView(sum, lpW(dp(8)));
        }
        if (e.ledgerId > 0) root.addView(small("已记账 ¥" + yuan(e.amountFen) + "（S2 账本 #" + e.ledgerId + "）", SUB), lpW(dp(6)));
        if (!e.tags.isEmpty()) root.addView(small("标签：" + TextUtils.join(" · ", e.tags), SUB), lpW(dp(6)));
        if (e.thumbPath.length() > 0 && new File(e.thumbPath).exists()) {
            ImageView iv = new ImageView(mAct);
            iv.setAdjustViewBounds(true);
            iv.setImageBitmap(BitmapFactory.decodeFile(e.thumbPath));
            root.addView(iv, lpW(dp(10)));
        }
        if (!e.deleted) {
            LinearLayout acts = new LinearLayout(mAct);
            acts.setOrientation(LinearLayout.VERTICAL);
            root.addView(acts, lpW(dp(10)));
            for (final QuickNoteActions.Action a : QuickNoteActions.all(e, TimeZone.getDefault())) {
                Button b = button(a.label, FIELD);
                b.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        String msg = QuickNoteActionRunner.run(mAct, e, a);
                        Toast.makeText(mAct, msg, Toast.LENGTH_SHORT).show();
                    }
                });
                acts.addView(b);
            }
            if (e.hasCode() && e.codeState == QuickNoteEntry.CODE_ON_ISLAND) {
                Button taken = button("标记已取（从灵动岛拿掉）", POS);
                taken.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        bg(new Runnable() {
                            @Override public void run() {
                                QuickNoteHooks.markTaken(mAct, e.id);
                                ui(new Runnable() { @Override public void run() { showDetailById(e.id); } });
                            }
                        });
                    }
                });
                acts.addView(taken);
            }
            // 备注
            final EditText notes = new EditText(mAct);
            notes.setHint("加一句备注…");
            notes.setHintTextColor(SUB);
            notes.setTextColor(TXT);
            notes.setText(e.notes);
            notes.setBackground(round(FIELD, 10));
            notes.setPadding(dp(12), dp(8), dp(12), dp(8));
            root.addView(notes, lpW(dp(10)));
            Button save = button("保存备注", FIELD);
            save.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    final String t = notes.getText().toString().trim();
                    bg(new Runnable() {
                        @Override public void run() {
                            QuickNoteEntry cur = mBook.store().get(e.id);
                            if (cur == null) return;
                            cur.notes = t;
                            cur.updatedAt = System.currentTimeMillis();
                            mBook.store().update(cur);
                            ui(new Runnable() { @Override public void run() { Toast.makeText(mAct, "备注已保存", Toast.LENGTH_SHORT).show(); } });
                        }
                    });
                }
            });
            root.addView(save);
        } else if (e.notes.length() > 0) {
            root.addView(small("备注：" + e.notes, SUB), lpW(dp(8)));
        }
        if (e.body.length() > 0) {
            final TextView body = small(e.body, TXT2);
            body.setTextIsSelectable(true);
            body.setMaxLines(6);
            body.setEllipsize(TextUtils.TruncateAt.END);
            root.addView(small("全文（点开 / 收起，可长按复制）", ACC), lpW(dp(12)));
            root.addView(body, lpW(dp(4)));
            body.setOnClickListener(new View.OnClickListener() {
                boolean open;
                @Override public void onClick(View v) {
                    open = !open;
                    body.setMaxLines(open ? Integer.MAX_VALUE : 6);
                }
            });
        }
        if (e.deleted) {
            Button restore = button("恢复", POS);
            restore.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    bg(new Runnable() {
                        @Override public void run() {
                            mBook.store().restore(e.id);
                            ui(new Runnable() { @Override public void run() { showDeleted(); } });
                        }
                    });
                }
            });
            root.addView(restore, lpW(dp(12)));
            Button gone = button("彻底删除", RED);
            gone.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    confirm("彻底删除？", "删除后不能恢复。", "彻底删除", new Runnable() {
                        @Override public void run() {
                            bg(new Runnable() {
                                @Override public void run() {
                                    QuickNoteThumbs.delete(e.thumbPath);
                                    mBook.store().deleteForever(e.id);
                                    ui(new Runnable() { @Override public void run() { showDeleted(); } });
                                }
                            });
                        }
                    });
                }
            });
            root.addView(gone);
        } else {
            Button del = button("删除", RED);
            del.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    bg(new Runnable() {
                        @Override public void run() {
                            QuickNoteHooks.undo(mAct, e.id);
                            ui(new Runnable() {
                                @Override public void run() {
                                    Toast.makeText(mAct, "已删除（30 天内可在「最近删除」恢复）", Toast.LENGTH_SHORT).show();
                                    showList();
                                }
                            });
                        }
                    });
                }
            });
            root.addView(del, lpW(dp(12)));
        }
        show(root);
    }

    // ------------------------------------------------------------------ 最近删除

    public void showDeleted() {
        final LinearLayout root = page();
        root.addView(topBar("最近删除", null, null));
        root.addView(small("删除的记一记保留 30 天，到期自动彻底删除。点一条可以恢复。", SUB), lpW(dp(6)));
        final LinearLayout box = new LinearLayout(mAct);
        box.setOrientation(LinearLayout.VERTICAL);
        root.addView(box, lpW(dp(6)));
        show(root);
        bg(new Runnable() {
            @Override public void run() {
                QuickNoteHooks.housekeep(mAct);
                final List<QuickNoteEntry> rows = mBook.deletedList();
                ui(new Runnable() {
                    @Override public void run() {
                        if (rows.isEmpty()) box.addView(small("没有删除的条目。", SUB), lpW(dp(12)));
                        for (final QuickNoteEntry e : rows) {
                            View r = row(e, true);
                            r.setOnClickListener(new View.OnClickListener() {
                                @Override public void onClick(View v) {
                                    showDetail(e);
                                }
                            });
                            box.addView(r, lpW(dp(6)));
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------ 小工具

    private LinearLayout page() {
        mListBox = null;
        mOnList = false;
        LinearLayout root = new LinearLayout(mAct);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(18), dp(16), dp(28));
        return root;
    }

    private void show(LinearLayout root) {
        ScrollView sv = new ScrollView(mAct);
        sv.setBackgroundColor(BG);
        sv.addView(root);
        sv.setTag(this);
        mHost.showPage(sv);
    }

    private View topBar(String title, String right, View.OnClickListener rightClick) {
        return topBar(title, right, rightClick, null, null);
    }

    private View topBar(String title, String right, View.OnClickListener rightClick, String right2, View.OnClickListener rightClick2) {
        LinearLayout bar = new LinearLayout(mAct);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        View back = new com.onesay.assistant.v2.ui.console.ConsoleIcon(mAct, com.onesay.assistant.v2.ui.console.ConsoleIcon.BACK, TXT, 0);
        back.setContentDescription("返回");
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(48)));
        back.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                mAct.onBackPressed();
            }
        });
        bar.addView(back);
        TextView t = new TextView(mAct);
        t.setText(title);
        t.setTextColor(TXT);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        bar.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        if (right != null) {
            TextView r = new TextView(mAct);
            r.setText(right);
            r.setTextColor(ACC);
            r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            r.setPadding(dp(12), dp(10), dp(8), dp(10));
            r.setOnClickListener(rightClick);
            bar.addView(r);
        }
        if (right2 != null) {
            TextView r = new TextView(mAct);
            r.setText(right2);
            r.setTextColor(ACC);
            r.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            r.setPadding(dp(12), dp(10), dp(4), dp(10));
            r.setOnClickListener(rightClick2);
            bar.addView(r);
        }
        return bar;
    }

    private void styleChip(TextView t, boolean on) {
        t.setTextColor(on ? mT.onAccent : TXT);
        t.setBackground(round(on ? ACC : FIELD, 16));
    }

    private TextView small(String s, int color) {
        TextView t = new TextView(mAct);
        t.setText(s);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        return t;
    }

    private Button button(String text, int color) {
        Button b = new Button(mAct);
        b.setText(text);
        b.setTextColor(color == POS ? ACC : TXT);
        b.setAllCaps(false);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setBackground(round(color, 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        lp.topMargin = dp(6);
        b.setLayoutParams(lp);
        return b;
    }

    private GradientDrawable round(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private LinearLayout.LayoutParams lpW(int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = top;
        return lp;
    }

    private void confirm(String title, String msg, String ok, final Runnable yes) {
        new AlertDialog.Builder(mAct).setTitle(title).setMessage(msg)
                .setPositiveButton(ok, new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        yes.run();
                    }
                }).setNegativeButton("取消", null).show();
    }

    private void bg(Runnable r) {
        com.onesay.assistant.v2.util.Threads.start("quicknote-page", r);
    }

    private void ui(Runnable r) {
        mAct.runOnUiThread(r);
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, mAct.getResources().getDisplayMetrics());
    }

    static String yuan(long fen) {
        return fen % 100 == 0 ? String.valueOf(fen / 100) : String.format(java.util.Locale.ROOT, "%.2f", fen / 100.0);
    }

    static String timeGroup(long t, long now) {
        Calendar a = Calendar.getInstance();
        a.setTimeInMillis(now);
        Calendar b = Calendar.getInstance();
        b.setTimeInMillis(t);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "今天";
        a.add(Calendar.DAY_OF_YEAR, -1);
        if (a.get(Calendar.YEAR) == b.get(Calendar.YEAR) && a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)) return "昨天";
        if (now - t < 7L * 24L * 3600L * 1000L) return "本周";
        return "更早";
    }

    static long[] monthRange(long now) {
        Calendar c = Calendar.getInstance();
        c.setTimeInMillis(now);
        c.set(Calendar.DAY_OF_MONTH, 1);
        c.set(Calendar.HOUR_OF_DAY, 0);
        c.set(Calendar.MINUTE, 0);
        c.set(Calendar.SECOND, 0);
        c.set(Calendar.MILLISECOND, 0);
        long a = c.getTimeInMillis();
        c.add(Calendar.MONTH, 1);
        return new long[]{a, c.getTimeInMillis()};
    }
}
