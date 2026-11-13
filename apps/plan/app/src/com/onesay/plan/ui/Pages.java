package com.onesay.plan.ui;

import android.app.AlertDialog;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.plan.core.Capabilities;
import com.onesay.plan.core.Contract;
import com.onesay.plan.platform.CapabilityProbe;
import com.onesay.plan.core.Fmt;
import com.onesay.plan.core.Row;
import com.onesay.plan.core.Today;
import com.onesay.plan.core.recur.CnTime;
import com.onesay.plan.core.recur.SchedEvent;
import com.onesay.plan.core.recur.SchedRecur;
import com.onesay.plan.data.Settings;
import com.onesay.plan.data.Store;
import com.onesay.plan.remind.Remind;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.TimeZone;

/** 六个页面的内容（都是「大标题 + 分组卡片」）。返回值是右下角「＋」的动作（null = 不显示）。 */
final class Pages {

    final MainActivity a;
    final Ui ui;
    final Editors ed;
    final TimeZone tz = TimeZone.getDefault();

    Pages(MainActivity a) {
        this.a = a;
        this.ui = a.ui;
        this.ed = new Editors(a);
    }

    View.OnClickListener build(String tab, LinearLayout col) {
        if (Contract.T_EVENTS.equals(tab)) return events(col);
        if (Contract.T_TODOS.equals(tab)) return todos(col);
        if (Contract.T_TICKETS.equals(tab)) return tickets(col);
        if (Contract.T_PICKUPS.equals(tab)) return pickups(col);
        if (Contract.T_AUTOMATIONS.equals(tab)) return autos(col);
        return today(col);
    }

    long now() { return System.currentTimeMillis(); }

    LinearLayout card(LinearLayout col) {
        LinearLayout c = ui.card();
        col.addView(c, ui.match(0, 4));
        return c;
    }

    void add(LinearLayout card, View item) {
        if (card.getChildCount() > 0) card.addView(ui.divider());
        card.addView(item);
    }

    static String weekday(long t, TimeZone tz) {
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(t);
        return new String[]{"星期日", "星期一", "星期二", "星期三", "星期四", "星期五", "星期六"}[k.get(Calendar.DAY_OF_WEEK) - 1];
    }

    // ================================================================== 今天

    View.OnClickListener today(LinearLayout col) {
        long now = now();
        Calendar k = Calendar.getInstance(tz);
        k.setTimeInMillis(now);
        Today.Data d = Store.today(a, now);
        col.addView(ui.largeTitle("今天", (k.get(Calendar.MONTH) + 1) + "月" + k.get(Calendar.DAY_OF_MONTH) + "日 " + weekday(now, tz) + " · " + Today.summary(d).replaceFirst("^今天 ", ""),
                ui.iconButton("⋮", new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        settings();
                    }
                })));
        if (d.empty()) {
            LinearLayout c = card(col);
            c.addView(ui.empty("今天很清净", "对 OneSay 说「明天下午三点开会」「记个待办…」，或点右下角加一条"));
        }
        if (!d.events.isEmpty()) {
            col.addView(ui.section("日程", Ui.C_EVENT, d.events.size() + " 个"));
            LinearLayout c = card(col);
            for (final Today.Line l : d.events) {
                boolean past = "past".equals(l.flag);
                View it = ui.item(Ui.C_EVENT, l.title, l.sub, past ? ui.tag("已过", Ui.TEXT3) : null, past);
                it.setOnClickListener(open(Contract.T_EVENTS, l.refId));
                add(c, it);
            }
        }
        if (!d.pickups.isEmpty()) {
            col.addView(ui.section("待取件", Ui.C_PICKUP, d.pickups.size() + " 个"));
            LinearLayout c = card(col);
            for (final Today.Line l : d.pickups) add(c, pickupItem(Store.get(a, Contract.T_PICKUPS, l.refId)));
        }
        if (!d.tickets.isEmpty()) {
            col.addView(ui.section("临近的票", Ui.C_TICKET, d.tickets.size() + " 张"));
            LinearLayout c = card(col);
            for (final Today.Line l : d.tickets) {
                View it = ui.item(Ui.C_TICKET, l.title, l.sub, "soon".equals(l.flag) ? ui.tag("快出发", Ui.RED) : ui.pill("出示", Ui.C_TICKET, showTicket(l.refId)), false);
                it.setOnClickListener(showTicket(l.refId));
                add(c, it);
            }
        }
        if (!d.todos.isEmpty()) {
            col.addView(ui.section("待办", Ui.C_TODO, d.todos.size() + " 件"));
            LinearLayout c = card(col);
            for (final Today.Line l : d.todos) add(c, todoItem(Store.get(a, Contract.T_TODOS, l.refId)));
        }
        if (!d.autos.isEmpty()) {
            col.addView(ui.section("今天会跑的自动任务", Ui.C_AUTO, d.autos.size() + " 个"));
            LinearLayout c = card(col);
            for (final Today.Line l : d.autos) {
                View it = ui.item(Ui.C_AUTO, l.title, l.sub, null, false);
                it.setOnClickListener(autoDetail(l.refId));
                add(c, it);
            }
        }
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                ed.pickNew();
            }
        };
    }

    View.OnClickListener open(final String table, final long id) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                Row r = Store.get(a, table, id);
                if (r == null) return;
                if (Contract.T_EVENTS.equals(table)) ed.event(r);
            }
        };
    }

    // ================================================================== 日程

    View.OnClickListener events(LinearLayout col) {
        long now = now();
        List<Row> rows = Store.list(a, Contract.T_EVENTS, "deleted_at=0", null, null);
        List<SchedEvent> evs = new ArrayList<SchedEvent>();
        for (Row r : rows) evs.add(r.toEvent());
        long d0 = CnTime.dayStart(now, tz);
        List<SchedRecur.Occ> occ = SchedRecur.expandAll(evs, d0, CnTime.addDays(d0, 30, tz), tz);
        col.addView(ui.largeTitle("日程", rows.isEmpty() ? "还没有日程" : "接下来 30 天 · 共 " + rows.size() + " 条日程", null));
        if (rows.isEmpty()) {
            card(col).addView(ui.empty("还没有日程", "说「明天下午三点开会」，或点右下角新建"));
        } else if (occ.isEmpty()) {
            card(col).addView(ui.empty("30 天内没有安排", null));
        }
        long curDay = -1;
        LinearLayout c = null;
        int shown = 0;
        for (final SchedRecur.Occ o : occ) {
            long day = CnTime.dayStart(o.start, tz);
            if (day != curDay) {
                curDay = day;
                col.addView(ui.section(Fmt.day(o.start, now, tz) + (Fmt.day(o.start, now, tz).length() <= 2 ? " · " + weekday(o.start, tz) : ""), Ui.C_EVENT, null));
                c = card(col);
            }
            boolean past = o.end <= now;
            String rep = Fmt.repeatLabel(o.event);
            View it = ui.item(Ui.C_EVENT, o.event.title, Fmt.eventLine(o.event, o.start, tz) + (rep.length() > 0 ? " · " + rep : ""), null, past);
            final long id = o.event.id;
            it.setOnClickListener(open(Contract.T_EVENTS, id));
            add(c, it);
            if (++shown > 200) break;
        }
        // 过去的、不重复的：给个入口删掉
        int old = 0;
        for (Row r : rows) if (r.l("repeat") == 0 && r.l("start_at") + 86400000L < d0) old++;
        if (old > 0) {
            col.addView(ui.section("已经过去", Ui.TEXT3, old + " 条"));
            LinearLayout oc = card(col);
            for (Row r : rows) {
                if (r.l("repeat") != 0 || r.l("start_at") + 86400000L >= d0) continue;
                View it = ui.item(Ui.C_EVENT, r.s("title"), Fmt.when(r.l("start_at"), now, tz), null, true);
                it.setOnClickListener(open(Contract.T_EVENTS, r.id()));
                add(oc, it);
            }
        }
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                ed.event(null);
            }
        };
    }

    // ================================================================== 待办

    View.OnClickListener todos(LinearLayout col) {
        List<Row> open = Store.list(a, Contract.T_TODOS, "done_at=0", null, "CASE WHEN due_at=0 THEN 1 ELSE 0 END, due_at, _id");
        List<Row> done = Store.list(a, Contract.T_TODOS, "done_at>0", null, "done_at DESC LIMIT 20");
        col.addView(ui.largeTitle("待办", open.isEmpty() ? "都做完了" : "还有 " + open.size() + " 件没做", null));
        if (open.isEmpty()) card(col).addView(ui.empty("没有待办", "说「记个待办：交水费」，或点右下角新建"));
        else {
            LinearLayout c = card(col);
            for (Row r : open) add(c, todoItem(r));
        }
        if (!done.isEmpty()) {
            col.addView(ui.section("最近做完的", Ui.TEXT3, null));
            LinearLayout c = card(col);
            for (Row r : done) add(c, todoItem(r));
        }
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                ed.todo(null);
            }
        };
    }

    View todoItem(final Row r) {
        if (r == null) return new View(a);
        final boolean done = r.l("done_at") != 0;
        long due = r.l("due_at");
        String sub = due > 0 ? Fmt.when(due, now(), tz) + (due < now() && !done ? " · 逾期" : "") : "";
        CheckView check = new CheckView(a, done, Ui.C_TODO);
        check.setLayoutParams(new LinearLayout.LayoutParams(ui.dp(28), ui.dp(28)));
        check.setContentDescription(done ? "标为没做完" : "完成");
        check.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Store.update(a, Contract.T_TODOS, one(r.id()), new Row().with("done_at", done ? 0L : now()), null);
            }
        });
        LinearLayout it = ui.item(due > 0 && due < now() && !done ? Ui.RED : Ui.C_TODO, r.s("title"), sub, check, done);
        it.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ed.todo(r);
            }
        });
        return it;
    }

    // ================================================================== 票夹

    View.OnClickListener tickets(LinearLayout col) {
        long now = now();
        List<Row> active = Store.list(a, Contract.T_TICKETS, "status=0 AND depart_at>=?", new String[]{String.valueOf(now - Today.TICKET_BEHIND_MS)}, "depart_at");
        List<Row> past = Store.list(a, Contract.T_TICKETS, "status<>0 OR depart_at<?", new String[]{String.valueOf(now - Today.TICKET_BEHIND_MS)}, "depart_at DESC LIMIT 30");
        col.addView(ui.largeTitle("票夹", active.isEmpty() ? "没有待用的票" : active.size() + " 张待用", null));
        if (active.isEmpty()) card(col).addView(ui.empty("票夹是空的", "在订单页说「把这张票存起来」，或点右下角粘贴短信"));
        for (final Row r : active) col.addView(ticketCard(r), ui.match(0, 10));
        if (!past.isEmpty()) {
            col.addView(ui.section("用过 / 过期", Ui.TEXT3, null));
            LinearLayout c = card(col);
            for (final Row r : past) {
                View it = ui.item(Ui.C_TICKET, Fmt.ticketTitle(r), Fmt.when(r.l("depart_at"), now, tz) + " · " + Fmt.ticketKind(r.s("kind")), null, true);
                it.setOnClickListener(new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        ed.ticket(r);
                    }
                });
                add(c, it);
            }
        }
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                ed.ticket(null);
            }
        };
    }

    /** 票样式卡片：上半出发到达大字，下半座位 / 检票口，右下「出示」。 */
    View ticketCard(final Row r) {
        long now = now();
        LinearLayout c = ui.card();
        c.setPadding(ui.dp(22), ui.dp(18), ui.dp(22), ui.dp(16));
        LinearLayout top = ui.row();
        top.addView(ui.tag(Fmt.ticketKind(r.s("kind")) + (r.s("number").length() > 0 ? " " + r.s("number") : ""), Ui.C_TICKET));
        top.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1f));
        top.addView(ui.text(Fmt.day(r.l("depart_at"), now, tz) + " · " + Fmt.left(r.l("depart_at"), now), 13, Ui.TEXT2, null));
        c.addView(top);
        LinearLayout mid = ui.row();
        mid.setPadding(0, ui.dp(12), 0, ui.dp(6));
        if (r.s("from_place").length() > 0) {
            mid.addView(place(r.s("from_place"), Fmt.hm(r.l("depart_at"), tz)), ui.weight());
            TextView arrow = ui.text("→", 22, Ui.TEXT3, null);
            arrow.setPadding(ui.dp(8), 0, ui.dp(8), 0);
            mid.addView(arrow);
            mid.addView(place(r.s("to_place"), r.l("arrive_at") > 0 ? Fmt.hm(r.l("arrive_at"), tz) : ""), ui.weight());
        } else {
            LinearLayout one = ui.col();
            one.addView(ui.single(r.s("title"), 22, Ui.TEXT, Ui.MEDIUM));
            one.addView(ui.text(Fmt.hm(r.l("depart_at"), tz) + (r.s("venue").length() > 0 ? " · " + r.s("venue") : ""), 15, Ui.TEXT2, null));
            mid.addView(one, ui.weight());
        }
        c.addView(mid);
        View dash = new View(a);
        dash.setBackgroundColor(Ui.LINE);
        c.addView(dash, ui.match(6, 10));
        dash.getLayoutParams().height = Math.max(1, ui.dp(1));
        LinearLayout bot = ui.row();
        String seat = r.s("seat"), gate = r.s("gate");
        LinearLayout info = ui.col();
        if (seat.length() > 0) info.addView(ui.text("座位  " + seat, 14, Ui.TEXT, null));
        if (gate.length() > 0) info.addView(ui.text(("flight".equals(r.s("kind")) ? "登机口  " : "检票口  ") + gate, 14, Ui.TEXT, null));
        if (seat.length() == 0 && gate.length() == 0) info.addView(ui.text(r.s("order_no").length() > 0 ? "订单 " + r.s("order_no") : "点「出示」全屏亮码", 13.5f, Ui.TEXT2, null));
        bot.addView(info, ui.weight());
        bot.addView(ui.pill("出示", Ui.C_TICKET, showTicket(r.id())));
        c.addView(bot);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                ed.ticket(r);
            }
        });
        return c;
    }

    View place(String name, String time) {
        LinearLayout l = ui.col();
        l.addView(ui.single(name, 22, Ui.TEXT, Ui.MEDIUM));
        if (time.length() > 0) l.addView(ui.text(time, 15, Ui.TEXT2, null));
        return l;
    }

    View.OnClickListener showTicket(final long id) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                a.startActivity(new Intent(a, TicketActivity.class).putExtra("id", id));
            }
        };
    }

    // ================================================================== 取件

    View.OnClickListener pickups(LinearLayout col) {
        List<Row> open = Store.list(a, Contract.T_PICKUPS, "taken_at=0", null, "created_at DESC");
        List<Row> taken = Store.list(a, Contract.T_PICKUPS, "taken_at>0", null, "taken_at DESC LIMIT 20");
        col.addView(ui.largeTitle("取件", open.isEmpty() ? "没有待取的" : open.size() + " 个待取", null));
        if (open.isEmpty()) card(col).addView(ui.empty("没有待取的件", "收到取件短信会自动出现；也可以说「取件码是 5-2-1234」"));
        else {
            LinearLayout c = card(col);
            for (Row r : open) add(c, pickupItem(r));
        }
        if (!taken.isEmpty()) {
            col.addView(ui.section("已取", Ui.TEXT3, null));
            LinearLayout c = card(col);
            for (Row r : taken) add(c, pickupItem(r));
        }
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                ed.pickup(null);
            }
        };
    }

    View pickupItem(final Row r) {
        if (r == null) return new View(a);
        final boolean taken = r.l("taken_at") != 0;
        long dl = r.l("deadline_at");
        String sub = r.s("station") + (dl > 0 ? (r.s("station").length() > 0 ? " · " : "") + Fmt.when(dl, now(), tz) + " 前" : "");
        if (sub.length() == 0) sub = Fmt.when(r.l("created_at"), now(), tz) + " 收到";
        View right;
        if (taken) right = ui.tag("已取", Ui.TEXT3);
        else right = ui.pill("已取", Ui.C_PICKUP, new View.OnClickListener() {
            @Override public void onClick(View v) {
                Remind.markTaken(a, r.id());
                Toast.makeText(a, "标记为已取", Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout it = ui.item(dl > 0 && dl < now() && !taken ? Ui.RED : Ui.C_PICKUP, "", sub, right, taken);
        // 标题换成大号等宽的码
        LinearLayout tc = (LinearLayout) it.getChildAt(1);
        TextView code = (TextView) tc.getChildAt(0);
        code.setText(r.s("code"));
        code.setTextSize(22);
        code.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        code.setLetterSpacing(0.04f);
        TextView kind = ui.text(r.s("kind"), 12, taken ? Ui.TEXT3 : Ui.C_PICKUP, Ui.MEDIUM);
        tc.addView(kind, 0);
        it.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                Remind.copy(a, r.s("code"));
                Toast.makeText(a, "已复制 " + r.s("code"), Toast.LENGTH_SHORT).show();
            }
        });
        it.setOnLongClickListener(new View.OnLongClickListener() {
            @Override public boolean onLongClick(View v) {
                ed.pickup(r);
                return true;
            }
        });
        return it;
    }

    // ================================================================== 自动任务

    View.OnClickListener autos(LinearLayout col) {
        List<Row> time = Store.list(a, Contract.T_AUTOMATIONS, "mode='time'", null, "enabled DESC, hour, minute");
        List<Row> trig = Store.list(a, Contract.T_AUTOMATIONS, "mode='trigger'", null, "enabled DESC, _id");
        col.addView(ui.largeTitle("自动任务", "快捷指令 · 到点做（定时）· 遇到情况做（触发）· 由 OneSay 执行", null));
        col.addView(ui.section("快捷指令：说名字就做", Ui.C_AUTO, Store.count(a, Contract.T_SHORTCUTS) + " 个"));
        new ShortcutUi(a, ed).section(col, card(col));
        col.addView(ui.section("定时：到点就做", Ui.C_AUTO, time.size() + " 个"));
        LinearLayout c = card(col);
        if (time.isEmpty()) c.addView(ui.empty("还没有定时任务", "说「每天八点提醒我吃药」「每周五下午五点帮我总结邮件」"));
        for (Row r : time) add(c, autoItem(r));
        col.addView(ui.section("触发：遇到情况就做", Ui.C_AUTO, trig.size() + " 个"));
        LinearLayout c2 = card(col);
        if (trig.isEmpty()) c2.addView(ui.empty("还没有触发任务", "说「连上家里 Wi‑Fi 时打开勿扰」「收到快递短信时提醒我」"));
        for (Row r : trig) add(c2, autoItem(r));
        TextView foot = ui.text("计划 App 只存任务和运行记录；到点 / 触发后由 OneSay 去执行。OneSay 没装或没开时，这里的任务不会跑。", 12.5f, Ui.TEXT3, null);
        foot.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), 0);
        col.addView(foot);
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                new android.app.AlertDialog.Builder(a).setTitle("新建").setItems(new String[]{"快捷指令", "自动任务（定时 / 触发）"},
                        new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                if (w == 0) new ShortcutUi(a, ed).editor(null);
                                else ed.auto(null);
                            }
                        }).show();
            }
        };
    }

    View autoItem(final Row r) {
        long now = now();
        boolean on = r.l("enabled", 1) != 0;
        final Switch sw = new Switch(a);
        sw.setChecked(on);
        sw.setOnCheckedChangeListener(new android.widget.CompoundButton.OnCheckedChangeListener() {
            @Override public void onCheckedChanged(android.widget.CompoundButton b, boolean v) {
                Store.update(a, Contract.T_AUTOMATIONS, one(r.id()), new Row().with("enabled", v ? 1 : 0), null);
            }
        });
        String when = Fmt.autoWhen(r, now, tz);
        String last = r.l("last_run_at") > 0 ? " · 上次 " + Fmt.when(r.l("last_run_at"), now, tz) : "";
        boolean unsupported = Contract.MODE_TRIGGER.equals(r.s("mode")) && !in(r.s("trigger_type"), Contract.TRIGGERS_DETECTED_TODAY);
        LinearLayout it = ui.item(Ui.C_AUTO, r.s("title"), when + " · " + Fmt.autoAction(r) + last, sw, !on);
        if (unsupported) {
            LinearLayout tc = (LinearLayout) it.getChildAt(1);
            tc.addView(ui.text("OneSay 现在还检测不了「" + Fmt.triggerLabel(r.s("trigger_type")) + "」，先存着", 12, Ui.RED, null));
        }
        it.setOnClickListener(autoDetail(r.id()));
        return it;
    }

    static boolean in(String v, String[] a) {
        for (String x : a) if (x.equals(v)) return true;
        return false;
    }

    View.OnClickListener autoDetail(final long id) {
        return new View.OnClickListener() {
            @Override public void onClick(View v) {
                final Row r = Store.get(a, Contract.T_AUTOMATIONS, id);
                if (r == null) return;
                long now = now();
                LinearLayout box = ui.col();
                box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(4));
                box.addView(ui.text(Fmt.autoWhen(r, now, tz), 15, Ui.TEXT, Ui.MEDIUM));
                box.addView(ui.text(Fmt.autoAction(r), 14, Ui.TEXT2, null));
                if (Contract.MODE_TIME.equals(r.s("mode")) && r.l("next_at", -1) > 0)
                    box.addView(ui.text("下次：" + Fmt.when(r.l("next_at"), now, tz), 14, Ui.TEXT2, null));
                TextView h = ui.text("最近运行", 13, Ui.TEXT3, Ui.MEDIUM);
                h.setPadding(0, ui.dp(14), 0, ui.dp(4));
                box.addView(h);
                List<Row> runs = Store.list(a, Contract.T_RUNS, "automation_id=?", new String[]{String.valueOf(id)}, "started_at DESC LIMIT 10");
                if (runs.isEmpty()) box.addView(ui.text(r.l("last_run_at") > 0 ? Fmt.when(r.l("last_run_at"), now, tz) + "  " + r.s("last_result") : "还没跑过", 14, Ui.TEXT2, null));
                for (Row x : runs) {
                    String st = x.s("status");
                    int color = "ok".equals(st) ? Ui.GREEN : "fail".equals(st) ? Ui.RED : Ui.TEXT2;
                    String label = "ok".equals(st) ? "成功" : "fail".equals(st) ? "失败" : "skip".equals(st) ? "跳过" : "missed".equals(st) ? "错过" : "进行中";
                    TextView t = ui.text(Fmt.when(x.l("started_at"), now, tz) + "  " + label + (x.s("result").length() > 0 ? " · " + x.s("result") : ""), 13.5f, color, null);
                    t.setPadding(0, ui.dp(3), 0, ui.dp(3));
                    box.addView(t);
                }
                new AlertDialog.Builder(a).setTitle(r.s("title")).setView(box)
                        .setPositiveButton("编辑", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                ed.auto(r);
                            }
                        })
                        .setNegativeButton("删除", new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, int w) {
                                ed.confirmDelete(Contract.T_AUTOMATIONS, r.id(), r.s("title"));
                            }
                        })
                        .setNeutralButton("关闭", null).show();
            }
        };
    }

    // ================================================================== 设置

    void settings() {
        LinearLayout box = ui.col();
        box.setPadding(ui.dp(24), ui.dp(8), ui.dp(24), ui.dp(8));
        final Switch n = new Switch(a);
        n.setText("到点提醒（通知）");
        n.setTextSize(15);
        n.setChecked(Settings.notify(a));
        box.addView(n, ui.match(4, 4));
        // 系统能力：探测不到的按可用；不可用只置灰并写原因，已保存的开关值原样保留（保存时 isChecked 不变）
        Capabilities.Env env = CapabilityProbe.probe(a);
        Capabilities.Status ns = Capabilities.of(Capabilities.Feature.NOTIFY, env);
        if (!ns.available) {
            n.setEnabled(false);
            box.addView(ui.text("不可用：" + ns.reason, 12.5f, Ui.RED, null), ui.match(0, 4));
        }
        box.addView(ui.text("票提前几分钟提醒（逗号分隔，留空=不提醒）", 13.5f, Ui.TEXT2, null), ui.match(8, 0));
        final android.widget.EditText tl = new android.widget.EditText(a);
        tl.setText(Settings.ticketLeads(a));
        tl.setSingleLine(true);
        tl.setTextSize(15);
        box.addView(tl, ui.match(0, 4));
        String imp = Settings.lastImport(a);
        box.addView(ui.text("从 OneSay 导入：" + (imp.length() > 0 ? Fmt.when(Settings.lastImportAt(a), now(), tz) + " · " + imp : "还没导入过"), 13.5f, Ui.TEXT2, null), ui.match(10, 4));
        box.addView(ui.text("OneSay 经数据接口读写这里：content://" + Contract.AUTHORITY + "（只认与本 App 同一证书签名的 App）", 12.5f, Ui.TEXT3, null), ui.match(6, 4));
        for (Capabilities.Status st : Capabilities.all(env)) {
            if (st.feature == Capabilities.Feature.NOTIFY || (st.available && st.reason.length() == 0)) continue;
            box.addView(ui.text(st.feature.label + (st.available ? "：" : "：不可用 · ") + st.reason, 12.5f, st.available ? Ui.TEXT2 : Ui.RED, null),
                    ui.match(4, 0));
        }
        box.addView(ui.text("版本 " + Settings.versionName(a), 12.5f, Ui.TEXT3, null), ui.match(6, 0));
        new AlertDialog.Builder(a).setTitle("设置").setView(box)
                .setPositiveButton("好", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        Settings.setNotify(a, n.isChecked());
                        Settings.setTicketLeads(a, tl.getText().toString().trim());
                        Remind.schedule(a);
                    }
                })
                .setNeutralButton("现在导入", new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, int w) {
                        ed.importNow();
                    }
                }).show();
    }

    static List<Long> one(long id) {
        List<Long> l = new ArrayList<Long>();
        l.add(id);
        return l;
    }
}
