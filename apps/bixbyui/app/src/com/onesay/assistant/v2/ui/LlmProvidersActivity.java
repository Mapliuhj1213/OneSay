package com.onesay.assistant.v2.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.assistant.v2.llm.ProviderCheck;
import com.onesay.assistant.v2.llm.ProviderErrors;
import com.onesay.assistant.v2.llm.ProviderKind;
import com.onesay.assistant.v2.llm.ProviderPreset;
import com.onesay.assistant.v2.llm.ProviderRouting;
import com.onesay.assistant.v2.llm.ProviderStore;
import com.onesay.assistant.v2.ui.console.KeyMask;

import java.util.HashMap;
import java.util.Map;

/**
 * 「大模型服务商（自带 Key）」页：每家一张卡，Key（密钥库加密、保存后不回显）/ 服务地址 / 模型 / 「测试」/ 设为主对话 / 去申请。
 * 「测试」只调模型列表或模型详情接口（不花 token），结论一句话。窗口与对话框 FLAG_SECURE；日志只记长度。
 */
public class LlmProvidersActivity extends Activity {
    private static final String TAG = "LlmProviders";
    private static final int BG = 0xFF121412, CARD = 0xFF1C1F1C, TEXT = 0xFFECEFEA, SUB = 0xFF9AA39A, OK = 0xFF8FBF6A, WARN = 0xFFE0A050;

    private final Handler h = new Handler();
    private final Map<String, String> tests = new HashMap<String, String>();
    private final Map<String, Boolean> testOk = new HashMap<String, Boolean>();
    private LinearLayout col;
    private float density;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        com.onesay.assistant.v2.net.LlmEndpoints.load(this);
        density = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(24));
        sv.addView(col);
        setContentView(sv);
        build();
    }

    private void build() {
        col.removeAllViews();
        col.addView(text("大模型服务商（自带 Key）", 22, TEXT));
        col.addView(text("选一家做「主对话」：理解指令、回答问题、多步任务都走它。Key 只存在本机（系统密钥库加密），只发往该服务的 https 地址，"
                + "不跟随跳转。没选别家时照旧用 DeepSeek。", 13, SUB));
        String active = ProviderStore.activeId(this);
        String using = ProviderRouting.active() == null ? "DeepSeek（默认通道）" : ProviderPreset.get(ProviderRouting.active().providerId).title
                + " · " + ProviderRouting.active().model;
        TextView now = text("正在用：" + using, 14, OK);
        now.setPadding(dp(4), dp(10), 0, 0);
        col.addView(now);
        if (active.length() > 0 && !ProviderPreset.DEEPSEEK.equals(active) && ProviderRouting.active() == null) {
            col.addView(text("已选 " + ProviderPreset.get(active).title + "，但模型名或地址还没填好 —— 先照旧用 DeepSeek。", 12, WARN));
        }
        for (ProviderPreset p : ProviderPreset.all()) addCard(p, active);
    }

    private void addCard(final ProviderPreset p, String active) {
        boolean isActive = p.id.equals(active) || (active.length() == 0 && ProviderPreset.DEEPSEEK.equals(p.id));
        TextView h1 = text(p.title + (isActive ? "  ✓ 主对话" : ""), 15, isActive ? OK : TEXT);
        h1.setPadding(dp(4), dp(18), 0, dp(2));
        col.addView(h1);
        col.addView(text(kindLabel(p.kind), 12, SUB));
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(CARD);
        c.setPadding(dp(14), dp(4), dp(14), dp(4));
        col.addView(c);

        String key = ProviderStore.key(this, p);
        row(c, "API Key", key.length() == 0 ? "未配置" : "已配置 " + KeyMask.mask(key), key.length() == 0 ? WARN : SUB, new Runnable() {
            public void run() { editKey(p); }
        });
        String base = ProviderStore.base(this, p);
        row(c, "服务地址", base.length() == 0 ? "未填写（https://…）" : base, SUB, new Runnable() { public void run() { editBase(p); } });
        String model = ProviderStore.model(this, p);
        row(c, "模型", model.length() == 0 ? "未填写" : model, model.length() == 0 ? WARN : SUB, new Runnable() { public void run() { editModel(p); } });
        String t = tests.get(p.id);
        row(c, "测试", t == null ? "查一次模型列表 / 模型详情（不花 token）" : t,
                t == null ? SUB : (Boolean.TRUE.equals(testOk.get(p.id)) ? OK : WARN), new Runnable() { public void run() { test(p); } });
        if (!isActive) {
            row(c, "设为主对话", ProviderPreset.DEEPSEEK.equals(p.id) ? "回到默认 DeepSeek 通道" : "之后的对话改走这家", SUB, new Runnable() {
                public void run() {
                    ProviderStore.setActive(LlmProvidersActivity.this, ProviderPreset.DEEPSEEK.equals(p.id) ? "" : p.id);
                    build();
                }
            });
        }
        if (p.consoleUrl.length() > 0) row(c, "去申请", p.consoleUrl, SUB, new Runnable() { public void run() { open(p.consoleUrl); } });
    }

    static String kindLabel(ProviderKind k) {
        switch (k) {
            case ANTHROPIC: return "Anthropic Messages 接口";
            case GEMINI: return "Gemini generateContent 接口";
            default: return "OpenAI 兼容接口（/chat/completions）";
        }
    }

    private void test(final ProviderPreset p) {
        tests.put(p.id, "测试中…");
        testOk.remove(p.id);
        build();
        final String key = ProviderStore.key(this, p), base = ProviderStore.base(this, p), model = ProviderStore.model(this, p);
        com.onesay.assistant.v2.util.Threads.start("llm-provider-test", new Runnable() {
            @Override public void run() {
                final ProviderErrors.Verdict v = ProviderCheck.run(p.kind, base, model, key, null);
                Log.i(TAG, "[TEST] " + p.id + " " + v.kind + " http=" + v.status);   // 不记 Key、不记正文
                h.post(new Runnable() {
                    @Override public void run() {
                        tests.put(p.id, (v.ok() ? "通过：" : "失败：") + v.message);
                        testOk.put(p.id, v.ok());
                        if (!isFinishing()) build();
                    }
                });
            }
        });
    }

    private void editKey(final ProviderPreset p) {
        final EditText e = new EditText(this);
        e.setHint("粘贴新的 Key（留空保存 = 删除）");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        KeyMask.secure(new AlertDialog.Builder(this).setTitle(p.title + " · API Key")
                .setMessage("当前：" + KeyMask.mask(ProviderStore.key(this, p)) + "。保存后不再显示全文。")
                .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = e.getText().toString().trim();
                        ProviderStore.saveKey(LlmProvidersActivity.this, p, v);
                        e.setText("");
                        KeyMask.clearClipIf(LlmProvidersActivity.this, v);
                        Log.i(TAG, "[KEYS] " + p.id + " set len=" + v.length());
                        tests.remove(p.id);
                        build();
                    }
                }).setNegativeButton("取消", null).show());
    }

    private void editBase(final ProviderPreset p) {
        field(p.title + " · 服务地址", "只收 https://；误填的 /chat/completions、/v1/messages 等会自动去掉。留空保存 = 恢复预置。",
                p.defaultBase, ProviderStore.base(this, p), new Saver() {
                    public String save(String v) { return ProviderStore.saveBase(LlmProvidersActivity.this, p, v); }
                });
    }

    private void editModel(final ProviderPreset p) {
        field(p.title + " · 模型", "模型名以服务商控制台为准；留空保存 = 恢复预置（没有预置的要自己填）。",
                p.defaultModel, ProviderStore.model(this, p), new Saver() {
                    public String save(String v) { return ProviderStore.saveModel(LlmProvidersActivity.this, p, v); }
                });
    }

    interface Saver { String save(String v); }

    private void field(String title, String hint, String preset, String current, final Saver s) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(preset);
        e.setText(current);
        KeyMask.secure(new AlertDialog.Builder(this).setTitle(title).setMessage(hint).setView(e)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String why = s.save(e.getText().toString().trim());
                        if (why != null) toast("没保存：" + why);
                        build();
                    }
                }).setNegativeButton("取消", null).show());
    }

    private void row(LinearLayout card, String title, String sub, int subColor, final Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setMinimumHeight(dp(52));
        row.addView(text(title, 15, TEXT));
        row.addView(text(sub, 12, subColor));
        row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { r.run(); } });
        card.addView(row);
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    private int dp(int v) { return (int) (v * density + 0.5f); }

    private void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("没有可打开网页的应用"); }
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
