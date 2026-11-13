package com.onesay.plan.ui;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ClipboardManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.text.InputType;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.TimePicker;
import android.widget.Toast;

import com.onesay.plan.core.Contract;
import com.onesay.plan.core.Fmt;
import com.onesay.plan.core.RawImport;
import com.onesay.plan.core.Row;
import com.onesay.plan.core.TicketText;
import com.onesay.plan.data.Store;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** 新建 / 编辑对话框（简单版）。所有写入都走 {@link Store}（与后门同一套校验），校验不过就把原因原样提示。 */
final class Editors {

    final MainActivity a;
    final Ui ui;
    final TimeZone tz = TimeZone.getDefault();
    static final int REQ_QR = 41;
    long pendingQrTicket;

    Editors(MainActivity a) {
        this.a = a;
        this.ui = a.ui;
    }

    // ------------------------------------------------------------------ 小部件

    LinearLayout form() {
        LinearLayout f = ui.col();
        f.setPadding(ui.dp(22), ui.dp(6), ui.dp(22), ui.dp(4));
        return f;
    }

    EditText field(LinearLayout f, String hint, String value) {
        EditText e = new EditText(a);
        e.setHint(hint);
        e.setText(value == null ? "" : value);
        e.setTextSize(16);
        e.setSingleLine(true);
        f.addView(e, ui.match(2, 2));
        return e;
    }

    TextView label(LinearLayout f, String s) {
        TextView t = ui.text(s, 13, Ui.TEXT3, Ui.MEDIUM);
        t.setPadding(ui.dp(4), ui.dp(10), 0, 0);
        f.addView(t);
        return t;
    }

    /** 日期时间选择：显示一行，点了先选日期再选时刻。hold[0] = 毫秒（0 = 没设）。 */
    TextView whenField(LinearLayout f, final long[] hold, final boolean optional, final String emptyText) {
        final TextView t = ui.text("", 16, Ui.ACCENT, null);
        t.setPadding(ui.dp(4), ui.dp(10), ui.dp(4), ui.dp(10));
        final Runnable show = new Runnable() {
            @Override public void run() {
                t.setText(hold[0] > 0 ? Fmt.when(hold[0], System.currentTimeMillis(), tz) : emptyText);
            }
        };
        show.run();
        t.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                final Calendar k = Calendar.getInstance(tz);
                k.setTimeInMillis(hold[0] > 0 ? hold[0] : System.currentTimeMillis() + 3600000L);
                new DatePickerDialog(a, new DatePickerDialog.OnDateSetListener() {
                    @Override public void onDateSet(DatePicker dp, int y, int m, int d) {
                        k.set(y, m, d);
                        new TimePickerDialog(a, new TimePickerDialog.OnTimeSetListener() {
                            @Override public void onTimeSet(TimePicker tp, int h, int mi) {
                                k.set(Calendar.HOUR_OF_DAY, h);
                                k.set(Calendar.MINUTE, mi);
                                k.set(Calendar.SECOND, 0);
                                k.set(Calendar.MILLISECOND, 0);
                                hold[0] = k.getTimeInMillis();
                                show.run();
                            }
                        }, k.get(Calendar.HOUR_OF_DAY), k.get(Calendar.MINUTE), true).show();
                    }
                }, k.get(Calendar.YEAR), k.get(Calendar.MONTH), k.get(Calendar.DAY_OF_MONTH)).show();
            }
        });
        if (optional) {
            t.setOnLongClickListener(new View.OnLongClickListener() {
                @Override public boolean onLongClick(View v) {
                    hold[0] = 0;
                    show.run();
                    return true;
                }
            });
        }
        f.addView(t);
        return t;
    }

    Spinner spinner(LinearLayout f, String[] items, int sel) {
        Spinner s = new Spinner(a);
        s.setAdapter(new ArrayAdapter<String>(a, android.R.layout.simple_spinner_dropdown_item, items));
        s.setSelection(Math.max(0, Math.min(items.length - 1, sel)));
        f.addView(s, ui.match(2, 2));
        return s;
    }

    Switch toggle(LinearLayout f, String text, boolean on) {
        Switch s = new Switch(a);
        s.setText(text);
        s.setTextSize(15);
        s.setChecked(on);
        f.addView(s, ui.match(8, 4));
        return s;
    }

    View scroll(LinearLayout f) {
        ScrollView s = new ScrollView(a);
        s.addView(f);
        return s;
    }

    /** 保存按钮不自动关窗：校验不过就提示、窗口留着。 */
    void dialog(String title, View body, final Row existing, final String table, final Saver saver) {
        AlertDialog.Builder b = new AlertDialog.Builder(a).setTitle(title).setView(body).setPositiveButton("保存", null).setNegativeButton("取消", null);
        if (existing != null) b.setNeutralButton("删除", new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                confirmDelete(table, existing.id(), existing.s(table.equals(Contract.T_PICKUPS) ? "code" : "title"));
            }
        });
        final AlertDialog d = b.create();
        d.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override public void onShow(DialogInterface di) {
                d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        try {
                            Row r = saver.collect();
                            if (r == null) return;
                            if (existing == null) Store.insert(a, table, r, false, null);
                            else Store.update(a, table, one(existing.id()), r, null);
                            d.dismiss();
                        } catch (IllegalArgumentException e) {
                            Toast.makeText(a, "没存上：" + e.getMessage(), Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        });
        d.show();
    }

    interface Saver {
        Row collect();
    }

    void confirmDelete(final String table, final long id, String name) {
        new AlertDialog.Builder(a).setTitle("删掉「" + name + "」？").setMessage("删了就找不回来了。")
                .setPositiveButton("删除", new DialogInterface.OnClickListener() {
                    @Override public void onClick(DialogInterface d, int w) {
                        Store.delete(a, table, one(id), null);
                    }
                }).setNegativeButton("取消", null).show();
    }

    static List<Long> one(long id) {
        List<Long> l = new ArrayList<Long>();
        l.add(id);
        return l;
    }

    static String str(EditText e) {
        return e.getText().toString().trim();
    }

    // ------------------------------------------------------------------ 新建入口（今天页的＋）

    void pickNew() {
        final String[] items = {"日程", "待办", "票", "取件码", "自动任务"};
        new AlertDialog.Builder(a).setTitle("新建").setItems(items, new DialogInterface.OnClickListener() {
            @Override public void onClick(DialogInterface d, int w) {
                if (w == 0) event(null);
                else if (w == 1) todo(null);
                else if (w == 2) ticket(null);
                else if (w == 3) pickup(null);
                else auto(null);
            }
        }).show();
    }

    // ------------------------------------------------------------------ 日程

    static final String[] REPEATS = {"不重复", "每天", "每周（同一天）", "工作日", "每月（同一天）", "每年"};
    static final String[] LEADS = {"按默认（提前 10 分钟）", "准点", "提前 5 分钟", "提前 15 分钟", "提前 30 分钟", "提前 1 小时", "提前 1 天"};
    static final int[] LEAD_V = {-1, 0, 5, 15, 30, 60, 1440};

    void event(final Row r) {
        LinearLayout f = form();
        final EditText title = field(f, "做什么（如：开会）", r == null ? "" : r.s("title"));
        label(f, "开始");
        final long[] start = {r == null ? nextHour() : r.l("start_at")};
        whenField(f, start, false, "选时间");
        label(f, "结束（可不填，长按清除）");
        final long[] end = {r == null ? 0 : r.l("end_at")};
        whenField(f, end, true, "不设结束");
        final Switch allDay = toggle(f, "全天", r != null && r.l("all_day") != 0);
        final EditText loc = field(f, "地点（可不填）", r == null ? "" : r.s("location"));
        label(f, "重复");
        final Spinner rep = spinner(f, REPEATS, r == null ? 0 : (int) r.l("repeat"));
        label(f, "提醒");
        int li = 0;
        if (r != null) for (int i = 0; i < LEAD_V.length; i++) if (LEAD_V[i] == r.l("lead_min", -1)) li = i;
        final Spinner lead = spinner(f, LEADS, li);
        final EditText note = field(f, "备注（可不填）", r == null ? "" : r.s("note"));
        if (r != null && "import".equals(r.s("source")) && r.s("ext_id").length() > 0) label(f, "这条是从系统日历导入的副本");
        dialog(r == null ? "新建日程" : "编辑日程", scroll(f), r, Contract.T_EVENTS, new Saver() {
            @Override public Row collect() {
                long s = start[0];
                boolean ad = allDay.isChecked();
                if (ad) s = com.onesay.plan.core.recur.CnTime.dayStart(s, tz);
                Row x = new Row().with("title", str(title)).with("start_at", s).with("end_at", ad ? 0 : end[0]).with("all_day", ad ? 1 : 0)
                        .with("location", str(loc)).with("note", str(note)).with("lead_min", LEAD_V[lead.getSelectedItemPosition()]);
                int rp = rep.getSelectedItemPosition();
                x.with("repeat", rp);
                Calendar k = Calendar.getInstance(tz);
                k.setTimeInMillis(s);
                x.with("week_mask", rp == 2 ? 1 << (k.get(Calendar.DAY_OF_WEEK) - 1) : 0).with("month_day", rp == 4 ? k.get(Calendar.DAY_OF_MONTH) : 0);
                return x;
            }
        });
    }

    long nextHour() {
        Calendar k = Calendar.getInstance(tz);
        k.add(Calendar.HOUR_OF_DAY, 1);
        k.set(Calendar.MINUTE, 0);
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    // ------------------------------------------------------------------ 待办

    void todo(final Row r) {
        LinearLayout f = form();
        final EditText title = field(f, "要做什么", r == null ? "" : r.s("title"));
        label(f, "截止（可不填，长按清除）");
        final long[] due = {r == null ? 0 : r.l("due_at")};
        whenField(f, due, true, "不设截止");
        final Switch remind = toggle(f, "到截止时提醒我", r == null || r.l("remind", 1) != 0);
        // [borrow-1112] 强提醒：到点像闹钟一样响铃、整屏，音量键稍后、电源键停止（思路来源 涛声 TaoSheng，MIT）
        final Switch strong = toggle(f, "强提醒（到点像闹钟一样响，直到处理）", r != null && r.l("strong") != 0);
        final EditText note = field(f, "备注（可不填）", r == null ? "" : r.s("note"));
        dialog(r == null ? "新建待办" : "编辑待办", scroll(f), r, Contract.T_TODOS, new Saver() {
            @Override public Row collect() {
                return new Row().with("title", str(title)).with("due_at", due[0]).with("remind", remind.isChecked() ? 1 : 0).with("note", str(note))
                        .with("strong", strong.isChecked() ? 1 : 0);
            }
        });
    }

    // ------------------------------------------------------------------ 票

    static final String[] KINDS_ZH = {"火车票", "机票", "汽车票", "演出票", "电影票", "其他"};

    void ticket(final Row r0) {
        final Row r = r0;
        LinearLayout f = form();
        TextView paste = ui.pill("从剪贴板识别（复制购票短信 / 订单文字后点这里）", Ui.C_TICKET, null);
        f.addView(paste, ui.match(4, 6));
        int ki = 0;
        if (r != null) for (int i = 0; i < Contract.TICKET_KINDS.length; i++) if (Contract.TICKET_KINDS[i].equals(r.s("kind"))) ki = i;
        label(f, "种类");
        final Spinner kind = spinner(f, KINDS_ZH, ki);
        final EditText title = field(f, "名称（如：回家的车 / 演唱会）", r == null ? "" : r.s("title"));
        final EditText number = field(f, "车次 / 航班号（可不填）", r == null ? "" : r.s("number"));
        final EditText from = field(f, "出发地（可不填）", r == null ? "" : r.s("from_place"));
        final EditText to = field(f, "到达地（可不填）", r == null ? "" : r.s("to_place"));
        label(f, "出发 / 开场时间");
        final long[] dep = {r == null ? nextHour() : r.l("depart_at")};
        final TextView depT = whenField(f, dep, false, "选时间");
        final EditText venue = field(f, "场馆 / 影厅（可不填）", r == null ? "" : r.s("venue"));
        final EditText seat = field(f, "座位（如：05车12F）", r == null ? "" : r.s("seat"));
        final EditText gate = field(f, "检票口 / 登机口", r == null ? "" : r.s("gate"));
        final EditText order = field(f, "订单号 / 取票号（可不填）", r == null ? "" : r.s("order_no"));
        final Switch used = r == null ? null : toggle(f, "已经用过了", r.l("status") != 0);
        if (r != null) {
            TextView qr = ui.pill(Store.qrFile(a, r.id()).exists() ? "换一张二维码图片" : "添加二维码图片（从相册选截图）", Ui.ACCENT, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    pendingQrTicket = r.id();
                    Intent i = new Intent(Intent.ACTION_GET_CONTENT).setType("image/*");
                    a.startActivityForResult(Intent.createChooser(i, "选二维码截图"), REQ_QR);
                }
            });
            f.addView(qr, ui.match(10, 4));
        } else {
            label(f, "存好后再点进来可以加二维码图片");
        }
        paste.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ClipboardManager cm = (ClipboardManager) a.getSystemService(android.content.Context.CLIPBOARD_SERVICE);
                CharSequence s = cm.hasPrimaryClip() && cm.getPrimaryClip().getItemCount() > 0 ? cm.getPrimaryClip().getItemAt(0).coerceToText(a) : null;
                Row p = TicketText.parse(s == null ? null : s.toString(), System.currentTimeMillis(), tz);
                if (p == null) {
                    Toast.makeText(a, "没认出来，手动填一下吧", Toast.LENGTH_SHORT).show();
                    return;
                }
                for (int i = 0; i < Contract.TICKET_KINDS.length; i++) if (Contract.TICKET_KINDS[i].equals(p.s("kind"))) kind.setSelection(i);
                title.setText(p.s("title"));
                number.setText(p.s("number"));
                from.setText(p.s("from_place"));
                to.setText(p.s("to_place"));
                venue.setText(p.s("venue"));
                seat.setText(p.s("seat"));
                gate.setText(p.s("gate"));
                order.setText(p.s("order_no"));
                dep[0] = p.l("depart_at");
                depT.setText(Fmt.when(dep[0], System.currentTimeMillis(), tz));
                Toast.makeText(a, "认出来了，核对一下再保存", Toast.LENGTH_SHORT).show();
            }
        });
        dialog(r == null ? "存一张票" : "编辑票", scroll(f), r, Contract.T_TICKETS, new Saver() {
            @Override public Row collect() {
                String t = str(title);
                if (t.length() == 0) t = KINDS_ZH[kind.getSelectedItemPosition()] + (str(number).length() > 0 ? " " + str(number) : "");
                Row x = new Row().with("kind", Contract.TICKET_KINDS[kind.getSelectedItemPosition()]).with("title", t).with("number", str(number))
                        .with("from_place", str(from)).with("to_place", str(to)).with("depart_at", dep[0]).with("venue", str(venue))
                        .with("seat", str(seat)).with("gate", str(gate)).with("order_no", str(order));
                if (used != null) x.with("status", used.isChecked() ? 1 : 0);
                return x;
            }
        });
    }

    void onQrPicked(Uri uri) {
        if (uri == null || pendingQrTicket <= 0) return;
        try {
            java.io.InputStream in = a.getContentResolver().openInputStream(uri);
            File dst = Store.qrFile(a, pendingQrTicket);
            dst.getParentFile().mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(dst);
            byte[] b = new byte[16384];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            out.close();
            in.close();
            Store.setQrPath(a, pendingQrTicket, dst.getAbsolutePath(), null);
            Toast.makeText(a, "二维码存好了", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(a, "图片没存上：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    // ------------------------------------------------------------------ 取件码

    void pickup(final Row r) {
        LinearLayout f = form();
        final EditText code = field(f, "取件码（如 5-2-1234）", r == null ? "" : r.s("code"));
        code.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        label(f, "种类");
        final String[] kinds = {"取件码", "取餐码"};
        final Spinner kind = spinner(f, kinds, r != null && "取餐码".equals(r.s("kind")) ? 1 : 0);
        final EditText station = field(f, "在哪取（驿站 / 快递柜，可不填）", r == null ? "" : r.s("station"));
        label(f, "截止（可不填，长按清除）");
        final long[] dl = {r == null ? 0 : r.l("deadline_at")};
        whenField(f, dl, true, "不设截止");
        final Switch taken = r == null ? null : toggle(f, "已经取了", r.l("taken_at") != 0);
        dialog(r == null ? "记一个取件码" : "编辑取件码", scroll(f), r, Contract.T_PICKUPS, new Saver() {
            @Override public Row collect() {
                Row x = new Row().with("code", str(code).replaceAll("\\s+", "")).with("kind", kinds[kind.getSelectedItemPosition()])
                        .with("station", str(station)).with("deadline_at", dl[0]);
                if (taken != null) x.with("taken_at", taken.isChecked() ? (r.l("taken_at") > 0 ? r.l("taken_at") : System.currentTimeMillis()) : 0L);
                return x;
            }
        });
    }

    // ------------------------------------------------------------------ 自动任务（简单版）

    static final String[] MODES = {"定时：到点就做", "触发：遇到情况就做"};
    static final String[] AUTO_REPEATS = {"只一次", "每天", "每周（同一天）", "工作日"};
    static final String[] TRIG = {"charging", "wifi", "bluetooth", "notification"};
    static final String[] TRIG_ZH = {"充电（插上）", "连上 Wi‑Fi", "连上蓝牙", "收到通知（含关键词）"};
    static final String[] ACTS = {"提醒我", "让 OneSay 去做"};

    void auto(final Row r) {
        LinearLayout f = form();
        final EditText title = field(f, "名字（如：早上吃药）", r == null ? "" : r.s("title"));
        label(f, "什么时候");
        final Spinner mode = spinner(f, MODES, r != null && Contract.MODE_TRIGGER.equals(r.s("mode")) ? 1 : 0);
        final LinearLayout timeBox = ui.col(), trigBox = ui.col();
        f.addView(timeBox);
        f.addView(trigBox);
        final long[] at = {r != null && Contract.MODE_TIME.equals(r.s("mode")) ? atOf(r) : nextHour()};
        whenField(timeBox, at, false, "选时间");
        final Spinner rep = spinner(timeBox, AUTO_REPEATS, r == null ? 1 : (int) r.l("repeat"));
        int ti = 0;
        JSONObject tj = new JSONObject();
        if (r != null) {
            for (int i = 0; i < TRIG.length; i++) if (TRIG[i].equals(r.s("trigger_type"))) ti = i;
            try {
                tj = new JSONObject(r.s("trigger_json").length() == 0 ? "{}" : r.s("trigger_json"));
            } catch (Exception ignored) {
            }
        }
        final Spinner trig = spinner(trigBox, TRIG_ZH, ti);
        final EditText target = field(trigBox, "Wi‑Fi 名 / 蓝牙设备名（空 = 任意）", tj.optString("target", ""));
        JSONArray kw = tj.optJSONArray("keywords");
        StringBuilder ks = new StringBuilder();
        if (kw != null) for (int i = 0; i < kw.length(); i++) ks.append(i > 0 ? "、" : "").append(kw.optString(i));
        final EditText keywords = field(trigBox, "通知关键词（用、隔开）", ks.toString());
        final EditText apps = field(trigBox, "通知来自哪个 App（包名，用、隔开）", joinArr(tj.optJSONArray("apps")));
        mode.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) {
                timeBox.setVisibility(pos == 0 ? View.VISIBLE : View.GONE);
                trigBox.setVisibility(pos == 1 ? View.VISIBLE : View.GONE);
            }

            @Override public void onNothingSelected(android.widget.AdapterView<?> p) {
            }
        });
        label(f, "做什么");
        final Spinner act = spinner(f, ACTS, r != null && Contract.ACT_AGENT.equals(r.s("action_type")) ? 1 : 0);
        final EditText action = field(f, "提醒的话 / 给 OneSay 的指令", r == null ? "" : r.s("action"));
        final Switch on = toggle(f, "开启", r == null || r.l("enabled", 1) != 0);
        if (r != null && Contract.ACT_BUILTIN.equals(r.s("action_type"))) label(f, "这是 OneSay 内置任务（" + r.s("action") + "），这里只能开关和改时间");
        dialog(r == null ? "新建自动任务" : "编辑自动任务", scroll(f), r, Contract.T_AUTOMATIONS, new Saver() {
            @Override public Row collect() {
                boolean time = mode.getSelectedItemPosition() == 0;
                String a1 = str(action), t = str(title);
                if (t.length() == 0) t = a1;
                Row x = new Row().with("title", t).with("enabled", on.isChecked() ? 1 : 0);
                if (r == null || !Contract.ACT_BUILTIN.equals(r.s("action_type")))
                    x.with("action_type", act.getSelectedItemPosition() == 0 ? Contract.ACT_REMIND : Contract.ACT_AGENT).with("action", a1);
                if (time) {
                    Calendar k = Calendar.getInstance(tz);
                    k.setTimeInMillis(at[0]);
                    int rp = rep.getSelectedItemPosition();
                    x.with("mode", Contract.MODE_TIME).with("trigger_type", "time").with("repeat", rp).with("hour", k.get(Calendar.HOUR_OF_DAY))
                            .with("minute", k.get(Calendar.MINUTE)).with("once_at", rp == 0 ? at[0] : 0L)
                            .with("week_mask", rp == 2 ? 1 << (k.get(Calendar.DAY_OF_WEEK) - 1) : 0);
                } else {
                    try {
                        JSONObject j = new JSONObject().put("on", true).put("target", str(target)).put("keywords", split(str(keywords))).put("apps", split(str(apps)));
                        x.with("mode", Contract.MODE_TRIGGER).with("trigger_type", TRIG[trig.getSelectedItemPosition()]).with("trigger_json", j.toString());
                    } catch (Exception e) {
                        throw new IllegalArgumentException(e.getMessage());
                    }
                }
                if (r == null) x.with("owner", "bixbyui");
                return x;
            }
        });
    }

    long atOf(Row r) {
        if (r.l("repeat") == 0 && r.l("once_at") > 0) return r.l("once_at");
        Calendar k = Calendar.getInstance(tz);
        k.set(Calendar.HOUR_OF_DAY, (int) r.l("hour"));
        k.set(Calendar.MINUTE, (int) r.l("minute"));
        k.set(Calendar.SECOND, 0);
        k.set(Calendar.MILLISECOND, 0);
        return k.getTimeInMillis();
    }

    static JSONArray split(String s) {
        JSONArray a = new JSONArray();
        for (String p : s.split("[、,，\\s]+")) if (p.trim().length() > 0) a.put(p.trim());
        return a;
    }

    static String joinArr(JSONArray a) {
        StringBuilder sb = new StringBuilder();
        if (a != null) for (int i = 0; i < a.length(); i++) sb.append(i > 0 ? "、" : "").append(a.optString(i));
        return sb.toString();
    }

    // ------------------------------------------------------------------ 导入

    void importNow() {
        File f = new File(new File(a.getFilesDir(), "import"), "bixbyui.json");
        if (!f.exists()) {
            new AlertDialog.Builder(a).setTitle("没找到导入包").setMessage("让 OneSay 导出一次（装好新版 OneSay 后它会自动导），或用 root 把 bixbyui.json 放进计划 App 的 files/import/ 里。")
                    .setPositiveButton("知道了", null).show();
            return;
        }
        try {
            JSONObject b = new JSONObject(Store.readAll(f));
            RawImport.Plan p = RawImport.map(b, System.currentTimeMillis(), tz);
            Store.ImportResult res = Store.importPlan(a, p, null);
            com.onesay.plan.data.Settings.markImported(a, res.summary());
            if (!f.renameTo(new File(f.getPath() + ".done"))) android.util.Log.w("OneSayPlan", "改名失败");
            new AlertDialog.Builder(a).setTitle("导入完成").setMessage(res.detail()).setPositiveButton("好", null).show();
        } catch (Exception e) {
            Toast.makeText(a, "导入失败：" + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
