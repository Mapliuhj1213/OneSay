package com.onesay.plan.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.onesay.plan.core.Contract;
import com.onesay.plan.core.Fmt;
import com.onesay.plan.core.Row;
import com.onesay.plan.data.Store;

import java.io.File;
import java.util.TimeZone;

/** 出示票：全屏白底、亮度拉满、屏幕常亮；二维码图片放最大，没有图就把取票号 / 二维码文字放大。点一下关掉，亮度自动还原。 */
public final class TicketActivity extends Activity {

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        Window w = getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        lp.screenBrightness = 1f;
        w.setAttributes(lp);
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        w.setStatusBarColor(Color.WHITE);
        w.setNavigationBarColor(Color.WHITE);
        w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | 0x10);
        Ui ui = new Ui(this);
        long id = getIntent().getLongExtra("id", 0);
        Row r = Store.get(this, Contract.T_TICKETS, id);
        LinearLayout c = ui.col();
        c.setBackgroundColor(Color.WHITE);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(ui.dp(24), ui.dp(48), ui.dp(24), ui.dp(32));
        if (r == null) {
            c.addView(ui.text("这张票已经删了", 18, Ui.TEXT2, null));
            setContentView(c);
            return;
        }
        TimeZone tz = TimeZone.getDefault();
        long now = System.currentTimeMillis();
        c.addView(ui.tag(Fmt.ticketKind(r.s("kind")) + (r.s("number").length() > 0 ? " " + r.s("number") : ""), Ui.C_TICKET),
                new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView title = ui.text(Fmt.ticketTitle(r), 26, Ui.TEXT, Ui.MEDIUM);
        title.setGravity(Gravity.CENTER);
        title.setPadding(0, ui.dp(12), 0, ui.dp(4));
        c.addView(title);
        TextView when = ui.text(Fmt.when(r.l("depart_at"), now, tz) + " · " + Fmt.left(r.l("depart_at"), now), 16, Ui.TEXT2, null);
        when.setGravity(Gravity.CENTER);
        c.addView(when);
        File f = Store.qrFile(this, id);
        Bitmap bm = f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        if (bm != null) {
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(bm);
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
            p.topMargin = ui.dp(20);
            p.bottomMargin = ui.dp(20);
            c.addView(iv, p);
        } else {
            String big = r.s("qr_text").length() > 0 ? r.s("qr_text") : r.s("order_no");
            View sp = new View(this);
            c.addView(sp, new LinearLayout.LayoutParams(1, 0, 1f));
            TextView t = ui.text(big.length() > 0 ? big : "没有二维码图片", big.length() > 0 ? 40 : 18, big.length() > 0 ? Ui.TEXT : Ui.TEXT3, Ui.MEDIUM);
            t.setGravity(Gravity.CENTER);
            t.setTextIsSelectable(true);
            c.addView(t);
            if (big.length() > 0) {
                TextView h = ui.text(r.s("qr_text").length() > 0 ? "检票码" : "取票号 / 订单号", 13, Ui.TEXT3, null);
                h.setGravity(Gravity.CENTER);
                c.addView(h);
            } else {
                TextView h = ui.text("在「票夹」里点开这张票，可以加一张二维码截图", 13.5f, Ui.TEXT3, null);
                h.setGravity(Gravity.CENTER);
                h.setPadding(0, ui.dp(6), 0, 0);
                c.addView(h);
            }
            View sp2 = new View(this);
            c.addView(sp2, new LinearLayout.LayoutParams(1, 0, 1f));
        }
        LinearLayout info = ui.row();
        info.setGravity(Gravity.CENTER);
        if (r.s("seat").length() > 0) info.addView(block(ui, "座位", r.s("seat")));
        if (r.s("gate").length() > 0) info.addView(block(ui, "flight".equals(r.s("kind")) ? "登机口" : "检票口", r.s("gate")));
        if (r.s("venue").length() > 0) info.addView(block(ui, "场馆", r.s("venue")));
        c.addView(info);
        TextView close = ui.text("点任意处关闭 · 屏幕已调到最亮", 12.5f, Ui.TEXT3, null);
        close.setGravity(Gravity.CENTER);
        close.setPadding(0, ui.dp(18), 0, 0);
        c.addView(close);
        c.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                finish();
            }
        });
        setContentView(c);
    }

    View block(Ui ui, String k, String v) {
        LinearLayout l = ui.col();
        l.setGravity(Gravity.CENTER);
        l.setPadding(ui.dp(14), 0, ui.dp(14), 0);
        l.addView(ui.text(k, 12.5f, Ui.TEXT3, null));
        l.addView(ui.text(v, 20, Ui.TEXT, Ui.MEDIUM));
        return l;
    }
}
