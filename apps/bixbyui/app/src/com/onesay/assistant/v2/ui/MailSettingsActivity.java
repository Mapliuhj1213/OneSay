package com.onesay.assistant.v2.ui;

import android.app.Activity;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.executor.ExecutionResult;
import com.onesay.assistant.v2.executor.MailBridge;
import com.onesay.assistant.v2.executor.MailConfigForm;
import com.onesay.assistant.v2.executor.UnifiedExecutor;

import org.json.JSONObject;

/**
 * 邮箱（IMAP）设置页（P1-13）。
 *
 * <p>入口：控制台 MainActivity「📧 邮箱（IMAP）配置」按钮；面板里邮件能力返回 NOT_CONFIGURED /
 * AUTH_FAILED、或 AgentLoop 的方案提到授权码时出现的「去填写邮箱授权码」按钮。
 *
 * <p>存储与 TestRunReceiver 的配置口同一条路：{@link MailBridge#saveProvider} + {@link MailBridge#saveConfig}
 * （换服务器且未重填授权码时 MailBridge 会清掉旧授权码，审计 S1）。授权码<b>不回显、不写日志</b>；
 * 输入框留空 = 保留已存的授权码。SSL 固定开启（本引擎只走 IMAPS，且已删除"信任所有证书"降级，P0-7）。
 *
 * <p>不导出（exported=false），只能由本 App 打开。
 */
public class MailSettingsActivity extends Activity {

    private EditText mEtHost;
    private EditText mEtPort;
    private EditText mEtUser;
    private EditText mEtPass;
    private TextView mTvPassHint;
    private TextView mTvStatus;
    private String mProvider;
    private com.onesay.assistant.v2.ui.console.ConsoleTheme mT;

    /** 旧的写死颜色 → 主题 token（R12）。 */
    private int col(String hex) {
        if ("#121214".equals(hex)) return mT.bg;
        if ("#FFFFFF".equals(hex)) return mT.text1;
        if ("#9A9AA5".equals(hex) || "#C8C8D0".equals(hex)) return mT.text2;
        if ("#FF9F0A".equals(hex)) return mT.warn;
        if ("#30D158".equals(hex)) return mT.accent;
        if ("#0A84FF".equals(hex)) return mT.accentContainer;   // 「保存」= 苔绿容器色
        if ("#30A14E".equals(hex)) return mT.accent;            // 「保存并测试」= 实心苔绿
        if ("#555560".equals(hex)) return mT.text3;
        return mT.surface2;                                    // 输入框 / 服务商芯片
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // OneSay 开源版：页面里有 IMAP 授权码，禁止截屏 / 最近任务截图
        getWindow().setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE);
        // ★ R12（D9）：配色跟控制台主题走（深 / 浅，苔绿主色）
        mT = com.onesay.assistant.v2.ui.console.Console.themeFor(this);
        com.onesay.assistant.v2.ui.console.Console.styleWindow(getWindow(), mT);
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(col("#121214"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(22), dp(18), dp(22));
        scroll.addView(root);
        setContentView(scroll);

        TextView title = text("邮箱（IMAP）配置", 22, "#FFFFFF");
        title.getPaint().setFakeBoldText(true);
        root.addView(title);
        root.addView(text("配置后可以说「读一下我的邮件」直接读信。凭据只存在本机，不上传、不写日志。", 13, "#9A9AA5"));

        // ---- 服务商预设 ----
        root.addView(section("服务商"));
        HorizontalScrollView hs = new HorizontalScrollView(this);
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        hs.addView(chips);
        for (final String[] p : MailConfigForm.PRESETS) {
            Button b = button(p[0], "#2C2C34");
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, dp(40));
            lp.rightMargin = dp(8);
            b.setLayoutParams(lp);
            b.setOnClickListener(new View.OnClickListener() {
                @Override public void onClick(View v) { applyPreset(p[1]); }
            });
            chips.addView(b);
        }
        root.addView(hs);

        // ---- 表单 ----
        root.addView(section("IMAP 服务器"));
        mEtHost = field("imap.qq.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(mEtHost);
        root.addView(section("端口"));
        mEtPort = field("993", InputType.TYPE_CLASS_NUMBER);
        root.addView(mEtPort);

        CheckBox ssl = new CheckBox(this);
        ssl.setText("SSL/TLS（必需，固定开启并校验证书）");
        ssl.setTextColor(col("#C8C8D0"));
        ssl.setButtonTintList(android.content.res.ColorStateList.valueOf(mT.accent));
        ssl.setChecked(true);
        ssl.setEnabled(false);
        root.addView(ssl);

        root.addView(section("邮箱地址"));
        mEtUser = field("12345@qq.com", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
        root.addView(mEtUser);

        root.addView(section("授权码"));
        mTvPassHint = text("", 12, "#9A9AA5");
        root.addView(mTvPassHint);
        mEtPass = field("", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        mEtPass.setSaveEnabled(false);   // 审计 G2：授权码不进 Activity 保存状态（onSaveInstanceState / 最近任务快照的 Bundle）
        root.addView(mEtPass);

        Button save = button("保存", "#0A84FF");
        save.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(false); }
        });
        root.addView(save);
        Button saveTest = button("保存并测试连接", "#30A14E");
        saveTest.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) { save(true); }
        });
        root.addView(saveTest);

        mTvStatus = text("", 13, "#C8C8D0");
        mTvStatus.setPadding(0, dp(10), 0, 0);
        root.addView(mTvStatus);

        loadCurrent();
    }

    private void loadCurrent() {
        mProvider = MailBridge.provider(this);
        if (mProvider.length() == 0) mProvider = MailBridge.PROVIDER_QQ;
        mEtHost.setText(MailBridge.host(this));
        mEtPort.setText(String.valueOf(MailBridge.port(this)));
        mEtUser.setText(MailBridge.user(this));
        refreshPassHint();
        mTvStatus.setText(MailBridge.configInfo(this));   // 脱敏摘要，不含授权码
    }

    private void refreshPassHint() {
        String h = MailConfigForm.credentialHint(mProvider);
        if (MailBridge.hasPassword(this)) h += "\n已保存（不显示）；留空 = 保持不变";
        mTvPassHint.setText(h);
        mEtPass.setHint(MailBridge.hasPassword(this) ? "••••••（留空保持不变）" : "16 位授权码");
    }

    private void applyPreset(String provider) {
        mProvider = provider;
        String h = MailConfigForm.presetHost(provider);
        if (h != null) {
            mEtHost.setText(h);
            mEtPort.setText(String.valueOf(MailBridge.DEFAULT_PORT));
        } else {
            mEtHost.requestFocus();
        }
        refreshPassHint();
    }

    private void save(final boolean thenTest) {
        MailConfigForm.Result r = MailConfigForm.validate(
                mEtHost.getText().toString(), mEtPort.getText().toString(),
                mEtUser.getText().toString(), mEtPass.getText().toString(),
                MailBridge.host(this), MailBridge.hasPassword(this));
        if (!r.ok()) {
            mTvStatus.setTextColor(col("#FF9F0A"));
            mTvStatus.setText(r.error);
            return;
        }
        MailBridge.saveProvider(this, mProvider);
        MailBridge.saveConfig(this, r.host, r.port, r.user, r.pass);
        mEtPass.setText("");
        refreshPassHint();
        mTvStatus.setTextColor(col("#30D158"));
        mTvStatus.setText("已保存。" + (r.warning != null ? "\n" + r.warning : "") + "\n" + MailBridge.configInfo(this));
        Toast.makeText(this, "邮箱配置已保存", Toast.LENGTH_SHORT).show();
        if (thenTest) testConnection();
    }

    /** 读一封最新邮件（只取头部）验证连通性；结果只显示错误码与简短说明。 */
    private void testConnection() {
        mTvStatus.setTextColor(col("#C8C8D0"));
        mTvStatus.append("\n正在连接 " + MailBridge.host(this) + " …");
        final android.content.Context app = getApplicationContext();
        com.onesay.assistant.v2.util.Threads.start("MailSettingsTest", new Runnable() {
            @Override public void run() {
                String msg;
                boolean ok = false;
                try {
                    CapabilityRegistry.getInstance().init(app);   // 幂等；保证单独打开本页时注册表已就绪
                    Capability cap = CapabilityRegistry.getInstance().describe(MailBridge.SIGN_INBOX_LATEST);
                    JSONObject args = new JSONObject().put("count", 1).put("body", false);
                    ExecutionResult res = cap == null ? null : UnifiedExecutor.getInstance(app).execute(cap, args);
                    if (res == null) {
                        msg = "邮件能力未注册，无法测试";
                    } else if (res.ok) {
                        ok = true;
                        msg = "✓ 连接成功，已读到最新一封邮件的信息";
                    } else {
                        msg = "✕ " + res.code.name() + "：" + clip(res.userMessage, 160);
                    }
                } catch (Throwable t) {
                    msg = "✕ 测试异常：" + t.getClass().getSimpleName();
                }
                final String fm = msg;
                final boolean fok = ok;
                runOnUiThread(new Runnable() {
                    @Override public void run() {
                        mTvStatus.setTextColor(col(fok ? "#30D158" : "#FF9F0A"));
                        mTvStatus.append("\n" + fm);
                    }
                });
            }
        });
    }

    private static String clip(String s, int n) {
        if (s == null) return "";
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // ------------------------------------------------------------------ UI 小工具

    private TextView text(String s, int sp, String color) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(col(color));
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        return tv;
    }

    private TextView section(String s) {
        TextView tv = text(s, 13, "#9A9AA5");
        tv.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        tv.setPadding(0, dp(14), 0, dp(6));
        return tv;
    }

    private EditText field(String hint, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(col("#555560"));
        et.setTextColor(mT.text1);
        et.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        et.setSingleLine(true);
        et.setInputType(inputType);
        et.setPadding(dp(12), dp(10), dp(12), dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(col("#26262E"));
        bg.setCornerRadius(dp(12));
        et.setBackground(bg);
        return et;
    }

    private Button button(String s, String color) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        int bgc = col(color);
        b.setTextColor(bgc == mT.accent ? mT.onAccent : bgc == mT.accentContainer ? mT.accent : mT.text1);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setGravity(Gravity.CENTER);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(col(color));
        bg.setCornerRadius(dp(23));
        b.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.topMargin = dp(10);
        b.setLayoutParams(lp);
        return b;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
