package com.onesay.assistant.v2.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
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

import com.onesay.assistant.v2.asr.AliyunAsrConfig;
import com.onesay.assistant.v2.rain.RainService;
import com.onesay.assistant.v2.search.SearchConfig;
import com.onesay.assistant.v2.security.SecretPrefs;
import com.onesay.assistant.v2.tts.CloudTtsConfig;
import com.onesay.assistant.v2.ui.console.KeyMask;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 「API 密钥」统一面板（OneSay 开源版，与 OneSay Clock 同一套样式）：每个实际用到的服务一行，
 * 显示状态（未配置 / 已配置 / 测试通过 / 失败）、粘贴框（密码样式、保存后不回显）、测试连通性、一句话说明解锁什么、
 * 必填 / 可选、「去申请」跳官方控制台；云端朗读的服务地址由用户自填（只收 wss://）。
 * 窗口与对话框都加 FLAG_SECURE；保存后若剪贴板里就是这把 Key 则清空剪贴板；日志只记长度。
 * 云端语音是唯一朗读通道：没有 Key / 音色 = 不朗读（不回退系统 TTS）。音色默认空，靠拉列表或手动填写。
 */
public class KeysActivity extends Activity {
    private static final String TAG = "Keys";

    static final String URL_DEEPSEEK = "https://platform.deepseek.com/api_keys";
    static final String URL_BAILIAN = "https://bailian.console.aliyun.com/";
    static final String URL_TAVILY = "https://app.tavily.com/";
    static final String URL_BOCHA = "https://open.bochaai.com/";
    static final String URL_QWEATHER = "https://console.qweather.com/";

    private static final String S_LLM = "llm", S_DS = "dashscope", S_TTS = "tts", S_TAVILY = "tavily", S_BOCHA = "bocha", S_QW = "qweather";
    private static final String S_NLS_APPKEY = "nls_appkey", S_NLS_AKID = "nls_akid", S_NLS_AKSECRET = "nls_aksecret";
    static final String URL_NLS = "https://nls-portal.console.aliyun.com/";

    /** 可编辑的普通字段（地址 / 模型名 / 语言）：current = 当前生效值，preset = 预置值，save 返回 null 表示已保存，否则是原因。 */
    interface Field { String current(); String preset(); boolean custom(); String save(String v); }
    private final List<Object[]> fields = new ArrayList<Object[]>();

    private final Map<String, String> test = new HashMap<String, String>();
    private final Map<String, TextView> status = new HashMap<String, TextView>();
    private TextView llmBaseSub, dsBaseSub;
    private TextView ttsEndpointSub, ttsVoiceSub, ttsModelSub, qwHostSub;
    private final Handler h = new Handler();
    private MediaPlayer mp;
    private float density;

    private static final int BG = 0xFF121412, CARD = 0xFF1C1F1C, TEXT = 0xFFECEFEA, SUB = 0xFF9AA39A, OK = 0xFF8FBF6A, WARN = 0xFFE0A050;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        com.onesay.assistant.v2.net.LlmEndpoints.load(this);
        density = getResources().getDisplayMetrics().density;
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(16), dp(20), dp(16), dp(24));
        col.addView(text("API 密钥", 22, TEXT));
        col.addView(text("Key 只存在本机（系统密钥库加密），只发往对应服务的官方地址或你填写的地址。", 13, SUB));

        LinearLayout c = card(col, "大模型 · DeepSeek", "必填", "理解指令、回答问题、多步任务");
        status.put(S_LLM, row(c, "API Key", new Runnable() { public void run() { editSecret(S_LLM); } }));
        llmBaseSub = row(c, "接口地址（Base URL）"   /* ★ console-align：与「模型与密钥 › 主模型 › 接口地址」同名 */, new Runnable() { public void run() { editBase(true); } });
        fieldRow(c, "对话模型", "模型名，例如 deepseek-flash；留空 = 预置", new Field() {
            public String current() { return com.onesay.assistant.v2.net.LlmModels.chat(); }
            public String preset() { return com.onesay.assistant.v2.net.LlmModels.DEEPSEEK_DEFAULT; }
            public boolean custom() { return !current().equals(preset()); }
            public String save(String v) {
                if (v.length() > 0 && !com.onesay.assistant.v2.net.UserEndpoint.validId(v)) return "模型名只能是字母、数字和 . _ -";
                com.onesay.assistant.v2.router.ctx.ContextPrefs.setChatModel(KeysActivity.this, v); return null;
            }
        });
        row(c, "测试连通性", "", new Runnable() { public void run() { testLlm(); } });
        row(c, "去申请", "DeepSeek 开放平台", new Runnable() { public void run() { open(URL_DEEPSEEK); } });

        c = card(col, "阿里云百炼 · DashScope", "必填", "语音识别、云端朗读、识屏 / 看图");
        status.put(S_DS, row(c, "API Key", new Runnable() { public void run() { editSecret(S_DS); } }));
        dsBaseSub = row(c, "兼容模式地址（Base URL）", new Runnable() { public void run() { editBase(false); } });
        final AliyunAsrConfig asr = new AliyunAsrConfig(this);
        fieldRow(c, "识别模型（整段）", "例如 qwen3-asr-flash；留空 = 预置", new Field() {
            public String current() { return asr.getDashScopeModel(); }
            public String preset() { return AliyunAsrConfig.DEFAULT_DASHSCOPE_MODEL; }
            public boolean custom() { return !current().equals(preset()); }
            public String save(String v) { if (v.length() > 0 && !com.onesay.assistant.v2.net.UserEndpoint.validId(v)) return "模型名只能是字母、数字和 . _ -"; asr.setDashScopeModel(v); return null; }
        });
        fieldRow(c, "实时识别模型", "例如 qwen3-asr-flash-realtime；留空 = 预置", new Field() {
            public String current() { return asr.getRealtimeModel(); }
            public String preset() { return com.onesay.assistant.v2.asr.RealtimeAsrSession.DEFAULT_MODEL; }
            public boolean custom() { return !current().equals(preset()); }
            public String save(String v) { if (v.length() > 0 && !com.onesay.assistant.v2.net.UserEndpoint.validId(v)) return "模型名只能是字母、数字和 . _ -"; asr.setRealtimeModel(v); return null; }
        });
        fieldRow(c, "实时识别地址（wss）", "兼容百炼 realtime 协议的 WebSocket 地址，只收 wss://；不含 model 参数", new Field() {
            public String current() { return asr.getRealtimeEndpoint(); }
            public String preset() { return com.onesay.assistant.v2.asr.RealtimeAsrSession.DEFAULT_ENDPOINT; }
            public boolean custom() { return asr.hasCustomRealtimeEndpoint(); }
            public String save(String v) { return asr.setRealtimeEndpoint(v); }
        });
        fieldRow(c, "识别语言", "语言代码，例如 zh / en / ja；留空保存 = 服务端自动判断", new Field() {
            public String current() { return asr.getAsrLanguage(); }
            public String preset() { return com.onesay.assistant.v2.asr.RealtimeAsrSession.DEFAULT_LANGUAGE; }
            public boolean custom() { return !current().equals(preset()); }
            public String save(String v) { return asr.setAsrLanguage(v); }
        });
        fieldRow(c, "识屏 / 看图模型", "例如 qwen3-vl-plus；留空 = 预置", new Field() {
            public String current() { String m = asr.getVisionModel(); return m.length() == 0 ? preset() : m; }
            public String preset() { return com.onesay.assistant.v2.vision.ScreenVisionEngine.DEFAULT_MODEL; }
            public boolean custom() { return asr.getVisionModel().length() > 0; }
            public String save(String v) { if (v.length() > 0 && !com.onesay.assistant.v2.net.UserEndpoint.validId(v)) return "模型名只能是字母、数字和 . _ -"; asr.setVisionModel(v); return null; }
        });
        row(c, "测试连通性", "", new Runnable() { public void run() { testDashScope(); } });
        row(c, "去申请", "阿里云百炼控制台", new Runnable() { public void run() { open(URL_BAILIAN); } });

        c = card(col, "云端朗读（用上面的百炼 Key）", "必填 · 未配置则不朗读", "回答与提醒念出来；没有 Key 或没选音色就不出声");
        ttsEndpointSub = row(c, "服务地址", new Runnable() { public void run() { editTtsEndpoint(); } });
        ttsVoiceSub = row(c, "音色", new Runnable() { public void run() { pickVoice(); } });
        ttsModelSub = row(c, "模型", new Runnable() { public void run() { editTtsModel(); } });
        status.put(S_TTS, row(c, "测试连通性", new Runnable() { public void run() { testTts(); } }));
        row(c, "试听", "用所选音色合成一句示例", new Runnable() { public void run() { preview(); } });

        c = card(col, "阿里云智能语音 NLS（备用识别）", "可选", "一句话识别的另一条通道；不填就不用");
        status.put(S_NLS_APPKEY, row(c, "项目 AppKey", new Runnable() { public void run() { editSecret(S_NLS_APPKEY); } }));
        status.put(S_NLS_AKID, row(c, "AccessKey ID", new Runnable() { public void run() { editSecret(S_NLS_AKID); } }));
        status.put(S_NLS_AKSECRET, row(c, "AccessKey Secret", new Runnable() { public void run() { editSecret(S_NLS_AKSECRET); } }));
        fieldRow(c, "识别网关地址（https）", "例如 https://nls-gateway-cn-shanghai.aliyuncs.com/stream/v1/asr；留空 = 按地域预置", new Field() {
            public String current() { return asr.getAsrEndpoint(); }
            public String preset() { return AliyunAsrConfig.presetAsrEndpoint(asr.getRegion()); }
            public boolean custom() { return asr.hasCustomAsrEndpoint(); }
            public String save(String v) { return asr.setAsrEndpoint(v); }
        });
        fieldRow(c, "Token 签发地址（https）", "留空 = 预置；签名里的 RegionId 按地址里的地域推出", new Field() {
            public String current() { return asr.getTokenEndpoint(); }
            public String preset() { return AliyunAsrConfig.PRESET_TOKEN_ENDPOINT; }
            public boolean custom() { return asr.hasCustomTokenEndpoint(); }
            public String save(String v) { return asr.setTokenEndpoint(v); }
        });
        row(c, "去申请", "阿里云智能语音交互控制台", new Runnable() { public void run() { open(URL_NLS); } });

        c = card(col, "联网搜索 · Tavily", "可选", "查实时信息（新闻、价格等）；不填用免 Key 的网页搜索");
        status.put(S_TAVILY, row(c, "API Key", new Runnable() { public void run() { editSecret(S_TAVILY); } }));
        row(c, "测试连通性", "", new Runnable() { public void run() { testSearch(S_TAVILY); } });
        row(c, "去申请", "Tavily 控制台", new Runnable() { public void run() { open(URL_TAVILY); } });

        c = card(col, "联网搜索 · 博查", "可选", "中文网页搜索（与 Tavily 二选一或都填）");
        status.put(S_BOCHA, row(c, "API Key", new Runnable() { public void run() { editSecret(S_BOCHA); } }));
        row(c, "测试连通性", "", new Runnable() { public void run() { testSearch(S_BOCHA); } });
        row(c, "去申请", "博查开放平台", new Runnable() { public void run() { open(URL_BOCHA); } });

        c = card(col, "和风天气", "可选", "降雨提醒（分钟级）");
        status.put(S_QW, row(c, "API Key", new Runnable() { public void run() { editSecret(S_QW); } }));
        qwHostSub = row(c, "API Host", new Runnable() { public void run() { editQwHost(); } });
        row(c, "测试连通性", "", new Runnable() { public void run() { testQw(); } });
        row(c, "去申请", "和风天气控制台：创建项目后得到 API Key 和 API Host", new Runnable() { public void run() { open(URL_QWEATHER); } });

        sv.addView(col);
        setContentView(sv);
        refresh();
    }

    @Override protected void onDestroy() {
        if (mp != null) { try { mp.release(); } catch (Throwable ignored) { } mp = null; }
        super.onDestroy();
    }

    // ------------------------------------------------------------------ 存取（全部走 SecretPrefs：Keystore 加密，旧明文读到即迁移）

    private SharedPreferences llmSp() { return getSharedPreferences("bixby_llm_cfg", Context.MODE_PRIVATE); }

    String get(String s) {
        try {
            if (S_LLM.equals(s)) return SecretPrefs.get(llmSp(), "deepseek_key");
            if (S_DS.equals(s) || S_TTS.equals(s)) return new AliyunAsrConfig(this).getDashScopeKey();
            if (S_TAVILY.equals(s)) return new SearchConfig(this).getTavilyKey();
            if (S_BOCHA.equals(s)) return new SearchConfig(this).getBochaKey();
            if (S_QW.equals(s)) return RainService.key(this);
            if (S_NLS_APPKEY.equals(s)) return new AliyunAsrConfig(this).getAppKey();
            if (S_NLS_AKID.equals(s)) return new AliyunAsrConfig(this).getAccessKeyId();
            if (S_NLS_AKSECRET.equals(s)) return new AliyunAsrConfig(this).getAccessKeySecret();
        } catch (Throwable ignored) { }
        return "";
    }

    void set(String s, String v) {
        if (S_LLM.equals(s)) { SharedPreferences.Editor e = llmSp().edit(); SecretPrefs.put(e, "deepseek_key", v); e.apply(); }
        else if (S_DS.equals(s)) {
            AliyunAsrConfig a = new AliyunAsrConfig(this);
            a.setDashScopeKey(v);
            if (v.length() > 0) a.setEnabled(true);
        }
        else if (S_TAVILY.equals(s)) new SearchConfig(this).setTavilyKey(v);
        else if (S_BOCHA.equals(s)) new SearchConfig(this).setBochaKey(v);
        else if (S_QW.equals(s)) RainService.setKey(this, v);
        else if (S_NLS_APPKEY.equals(s)) { AliyunAsrConfig a = new AliyunAsrConfig(this); a.setAppKey(v); if (v.length() > 0) a.setEnabled(true); }
        else if (S_NLS_AKID.equals(s)) new AliyunAsrConfig(this).setAccessKeyId(v);
        else if (S_NLS_AKSECRET.equals(s)) new AliyunAsrConfig(this).setAccessKeySecret(v);
    }

    static String label(String s) {
        if (S_LLM.equals(s)) return "主模型 API Key";   // ★ console-align：原「DeepSeek API Key」（主模型地址可改了）
        if (S_DS.equals(s)) return "百炼 API Key";
        if (S_TAVILY.equals(s)) return "Tavily API Key";
        if (S_BOCHA.equals(s)) return "博查 API Key";
        if (S_NLS_APPKEY.equals(s)) return "智能语音 NLS 项目 AppKey";
        if (S_NLS_AKID.equals(s)) return "阿里云 AccessKey ID";
        if (S_NLS_AKSECRET.equals(s)) return "阿里云 AccessKey Secret";
        return "和风天气 API Key";
    }

    void refresh() {
        llmBaseSub.setText(com.onesay.assistant.v2.net.LlmEndpoints.deepseekBase().equals(com.onesay.assistant.v2.net.LlmEndpoints.DEEPSEEK_PRESET) ? "预置：DeepSeek " + com.onesay.assistant.v2.net.LlmEndpoints.DEEPSEEK_PRESET : com.onesay.assistant.v2.net.LlmEndpoints.deepseekBase());
        dsBaseSub.setText(com.onesay.assistant.v2.net.LlmEndpoints.dashscopeBase().equals(com.onesay.assistant.v2.net.LlmEndpoints.DASHSCOPE_PRESET) ? "预置：百炼 " + com.onesay.assistant.v2.net.LlmEndpoints.DASHSCOPE_PRESET : com.onesay.assistant.v2.net.LlmEndpoints.dashscopeBase());
        for (Object[] fr : fields) {
            Field f = (Field) fr[1];
            ((TextView) fr[0]).setText(f.custom() ? f.current() : "预置：" + f.preset());
        }
        for (String s : new String[]{S_NLS_APPKEY, S_NLS_AKID, S_NLS_AKSECRET}) {
            String k = get(s);
            setStatus(status.get(s), k.length() == 0 ? "未配置" : KeyMask.mask(k), k.length() > 0);
        }
        for (String s : new String[]{S_LLM, S_DS, S_TAVILY, S_BOCHA, S_QW}) {
            String k = get(s), t = test.get(s);
            String txt = k.length() == 0 ? "未配置" : t != null ? t : KeyMask.mask(k);
            setStatus(status.get(s), txt, k.length() > 0 && (t == null || t.startsWith("测试通过")));
        }
        CloudTtsConfig cfg = new CloudTtsConfig(this);
        ttsEndpointSub.setText(cfg.hasCustomEndpoint() ? cfg.getEndpoint() : "默认：" + CloudTtsConfig.DASHSCOPE_WS_URL);
        String vo = cfg.getVoice();
        setStatus(ttsVoiceSub, vo.length() == 0 ? "未选择：点这里拉取音色列表或手动填写" : vo, vo.length() > 0);
        ttsModelSub.setText(cfg.getModel());
        String t = test.get(S_TTS);
        boolean ready = get(S_DS).length() > 0 && vo.length() > 0;
        setStatus(status.get(S_TTS), t != null ? t : ready ? "已配置 · 还没测试" : "未配置：不朗读", ready && (t == null || t.startsWith("测试通过")));
        String qh = RainService.host(this);
        setStatus(qwHostSub, qh.length() == 0 ? "未配置" : qh, qh.length() > 0);
    }

    // ------------------------------------------------------------------ 编辑

    void editSecret(final String s) {
        final EditText e = new EditText(this);
        e.setHint("粘贴新的 Key（留空保存 = 删除）");
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secure(new AlertDialog.Builder(this).setTitle(label(s))
                .setMessage("当前：" + KeyMask.mask(get(s)) + "。保存后不再显示全文。")
                .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = e.getText().toString().trim();
                        set(s, v);
                        test.remove(s);
                        if (S_DS.equals(s)) test.remove(S_TTS);
                        e.setText("");
                        KeyMask.clearClipIf(KeysActivity.this, v);
                        Log.i(TAG, "[KEYS] " + s + " set len=" + v.length());
                        refresh();
                    }
                }).setNegativeButton("取消", null).show());
    }

    void editTtsEndpoint() {
        final CloudTtsConfig cfg = new CloudTtsConfig(this);
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(cfg.hasCustomEndpoint() ? cfg.getEndpoint() : CloudTtsConfig.DASHSCOPE_WS_URL);
        secure(new AlertDialog.Builder(this).setTitle("云端朗读服务地址")
                .setMessage("DashScope 兼容的流式语音合成 WebSocket 地址，必须 wss://。Key 只会发往这个地址的主机。")
                .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String why = cfg.setEndpoint(e.getText().toString());
                        if (why != null) { toast("没保存：" + why); return; }
                        test.remove(S_TTS); refresh();
                    }
                }).setNeutralButton("恢复默认", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { cfg.setEndpoint(""); test.remove(S_TTS); refresh(); }
                }).setNegativeButton("取消", null).show());
    }

    void editTtsModel() {
        final CloudTtsConfig cfg = new CloudTtsConfig(this);
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setText(cfg.getModel());
        secure(new AlertDialog.Builder(this).setTitle("模型").setMessage("音色必须属于这个模型（预置音色见服务商文档）。留空 = 默认。")
                .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { cfg.setModel(e.getText().toString()); test.remove(S_TTS); refresh(); }
                }).setNegativeButton("取消", null).show());
    }

    /** 大模型 Base URL：只接受 https；留空 = 恢复预置。Key 只会发往这里填的主机。 */
    void editBase(final boolean deepseek) {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint(deepseek ? com.onesay.assistant.v2.net.LlmEndpoints.DEEPSEEK_PRESET : com.onesay.assistant.v2.net.LlmEndpoints.DASHSCOPE_PRESET);
        e.setText(deepseek ? com.onesay.assistant.v2.net.LlmEndpoints.deepseekBase() : com.onesay.assistant.v2.net.LlmEndpoints.dashscopeBase());
        secure(new AlertDialog.Builder(this).setTitle(deepseek ? "主模型接口地址" : "百炼兼容模式地址")
                .setMessage("OpenAI 兼容接口的 Base URL（不含 /chat/completions），只允许 https。留空恢复预置。对应的 Key 只会发往这个主机。")
                .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        String v = e.getText().toString();
                        boolean ok = deepseek ? com.onesay.assistant.v2.net.LlmEndpoints.saveDeepseek(KeysActivity.this, v) : com.onesay.assistant.v2.net.LlmEndpoints.saveDashscope(KeysActivity.this, v);
                        if (!ok) toast("地址无效：必须是 https:// 开头的完整地址");
                        test.remove(deepseek ? S_LLM : S_DS); refresh();
                    }
                }).setNegativeButton("恢复预置", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        if (deepseek) com.onesay.assistant.v2.net.LlmEndpoints.saveDeepseek(KeysActivity.this, ""); else com.onesay.assistant.v2.net.LlmEndpoints.saveDashscope(KeysActivity.this, "");
                        test.remove(deepseek ? S_LLM : S_DS); refresh();
                    }
                }).show());
    }

    void editQwHost() {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("例如 abcxyz.re.qweatherapi.com");
        e.setText(RainService.host(this));
        secure(new AlertDialog.Builder(this).setTitle("和风天气 API Host").setMessage("只填主机名；请求一律走 https。")
                .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { RainService.setHost(KeysActivity.this, e.getText().toString()); test.remove(S_QW); refresh(); }
                }).setNegativeButton("取消", null).show());
    }

    void pickVoice() {
        final String[] it = {"拉取音色列表", "手动填写音色 ID", "清除"};
        secure(new AlertDialog.Builder(this).setTitle("音色").setItems(it, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface d, int w) {
                if (w == 0) fetchVoices();
                else if (w == 1) manualVoice();
                else { new CloudTtsConfig(KeysActivity.this).setVoice(""); test.remove(S_TTS); refresh(); }
            }
        }).show());
    }

    void manualVoice() {
        final EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("音色 ID（例如服务商文档里的预置音色）");
        e.setText(new CloudTtsConfig(this).getVoice());
        secure(new AlertDialog.Builder(this).setTitle("手动填写音色 ID").setView(e)
                .setPositiveButton("保存", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { new CloudTtsConfig(KeysActivity.this).setVoice(e.getText().toString()); test.remove(S_TTS); refresh(); }
                }).setNegativeButton("取消", null).show());
    }

    // ------------------------------------------------------------------ 网络（全部 https，不跟随重定向，Key 只放请求头）

    static final class Resp { int code; String body = ""; }

    static Resp call(String url, String method, Map<String, String> headers, String json, int timeoutMs) throws Exception {
        if (!url.startsWith("https://")) throw new Exception("只允许 https");
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setInstanceFollowRedirects(false);
        c.setConnectTimeout(Math.min(8000, timeoutMs));
        c.setReadTimeout(timeoutMs);
        c.setRequestMethod(method);
        for (Map.Entry<String, String> e : headers.entrySet()) c.setRequestProperty(e.getKey(), e.getValue());
        if (json != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json");
            OutputStream o = c.getOutputStream();
            try { o.write(json.getBytes("UTF-8")); } finally { o.close(); }
        }
        Resp r = new Resp();
        r.code = c.getResponseCode();
        if (r.code == 200) r.body = new String(readAll(c.getInputStream()), "UTF-8");
        c.disconnect();
        return r;
    }

    static byte[] readAll(InputStream in) throws Exception {
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally { in.close(); }
    }

    static Map<String, String> bearer(String key) {
        Map<String, String> m = new HashMap<String, String>();
        m.put("Authorization", "Bearer " + key);
        return m;
    }

    static String verdict(int code) {
        return code == 200 ? "测试通过" : (code == 401 || code == 403) ? "失败：Key 无效或无权限" : "失败：HTTP " + code;
    }

    interface Probe { String run() throws Exception; }

    void runTest(final String s, final Probe p) {
        test.put(s, "测试中…");
        refresh();
        com.onesay.assistant.v2.util.Threads.start("keys", new Runnable() {
            public void run() {
                String r;
                try { r = p.run(); } catch (Exception e) { r = "失败：" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
                final String fr = r;
                h.post(new Runnable() { public void run() { test.put(s, fr); if (!isFinishing()) refresh(); } });
            }
        });
    }

    void testLlm() {
        final String k = get(S_LLM);
        if (k.length() == 0) { test.put(S_LLM, "失败：未配置"); refresh(); return; }
        runTest(S_LLM, new Probe() { public String run() throws Exception {
            return verdict(call(com.onesay.assistant.v2.net.LlmEndpoints.deepseekModelsUrl(), "GET", bearer(k), null, 12000).code);
        } });
    }

    String ttsHttpsBase() {
        CloudTtsConfig cfg = new CloudTtsConfig(this);
        return CloudTtsConfig.httpsBase(cfg.hasCustomEndpoint() ? cfg.getEndpoint() : CloudTtsConfig.DASHSCOPE_WS_URL);
    }

    List<String> listVoices(String key) throws Exception {
        String body = new JSONObject().put("model", "voice-enrollment")
                .put("input", new JSONObject().put("action", "list_voice").put("page_size", 100).put("page_index", 0)).toString();
        Resp r = call(ttsHttpsBase() + "/api/v1/services/audio/tts/customization", "POST", bearer(key), body, 12000);
        if (r.code != 200) throw new Exception(verdict(r.code).replace("失败：", ""));
        List<String> out = new ArrayList<String>();
        JSONObject op = new JSONObject(r.body).optJSONObject("output");
        JSONArray l = op == null ? null : op.optJSONArray("voice_list");
        for (int i = 0; l != null && i < l.length(); i++) {
            JSONObject v = l.optJSONObject(i);
            if (v != null && v.optString("voice_id").length() > 0) out.add(v.optString("voice_id"));
        }
        return out;
    }

    void testDashScope() {
        final String k = get(S_DS);
        if (k.length() == 0) { test.put(S_DS, "失败：未配置"); refresh(); return; }
        runTest(S_DS, new Probe() { public String run() throws Exception {
            Map<String, String> hd = bearer(k);
            return verdict(call(com.onesay.assistant.v2.net.LlmEndpoints.dashscopeModelsUrl(), "GET", hd, null, 12000).code);
        } });
    }

    /** 非流式合成一句，返回 WAV（DashScope 兼容 HTTP 接口，与朗读同主机）。 */
    byte[] synth(String key, String text) throws Exception {
        CloudTtsConfig cfg = new CloudTtsConfig(this);
        JSONObject in = new JSONObject().put("text", text).put("voice", cfg.getVoice()).put("format", "wav").put("sample_rate", 24000);
        String body = new JSONObject().put("model", cfg.getModel()).put("input", in).toString();
        Resp r = call(ttsHttpsBase() + "/api/v1/services/audio/tts/SpeechSynthesizer", "POST", bearer(key), body, 20000);
        if (r.code != 200) throw new Exception(verdict(r.code).replace("失败：", ""));
        String url = new JSONObject(r.body).getJSONObject("output").getJSONObject("audio").optString("url", "");
        if (!url.startsWith("https://")) throw new Exception("没拿到音频");
        HttpURLConnection g = (HttpURLConnection) new URL(url).openConnection();   // 音频下载地址自带签名，不带 Key
        g.setConnectTimeout(8000);
        g.setReadTimeout(20000);
        return readAll(g.getInputStream());
    }

    void testTts() {
        final String k = get(S_DS);
        final String vo = new CloudTtsConfig(this).getVoice();
        if (k.length() == 0) { test.put(S_TTS, "失败：百炼 Key 未配置"); refresh(); return; }
        runTest(S_TTS, new Probe() { public String run() throws Exception {
            if (vo.length() == 0) { listVoices(k); return "测试通过（Key 有效；还需选择音色）"; }
            byte[] b = synth(k, "测试。");
            return b != null && b.length > 1000 ? "测试通过" : "失败：没拿到音频";
        } });
    }

    void fetchVoices() {
        final String k = get(S_DS);
        if (k.length() == 0) { toast("先填百炼 API Key"); return; }
        toast("正在拉取…");
        com.onesay.assistant.v2.util.Threads.start("keys", new Runnable() {
            public void run() {
                List<String> vs = null; String err = null;
                try { vs = listVoices(k); } catch (Exception e) { err = e.getMessage(); }
                final List<String> fv = vs; final String fe = err;
                h.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (fe != null) { toast("拉取失败：" + fe); return; }
                        if (fv == null || fv.isEmpty()) { toast("账号里没有自定义音色；预置音色请手动填写"); manualVoice(); return; }
                        final String[] arr = fv.toArray(new String[0]);
                        secure(new AlertDialog.Builder(KeysActivity.this).setTitle("选择音色").setItems(arr, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface d, int w) { new CloudTtsConfig(KeysActivity.this).setVoice(arr[w]); test.remove(S_TTS); refresh(); }
                        }).show());
                    }
                });
            }
        });
    }

    void preview() {
        final String k = get(S_DS);
        if (k.length() == 0 || new CloudTtsConfig(this).getVoice().length() == 0) { toast("先配好百炼 Key 和音色"); return; }
        toast("合成中…");
        com.onesay.assistant.v2.util.Threads.start("keys", new Runnable() {
            public void run() {
                String err = null; final File f = new File(getCacheDir(), "voice_preview.wav");
                try {
                    byte[] b = synth(k, "你好，我是 OneSay。现在可以开始了。");
                    FileOutputStream o = new FileOutputStream(f);
                    try { o.write(b); } finally { o.close(); }
                } catch (Exception e) { err = e.getMessage(); }
                final String fe = err;
                h.post(new Runnable() {
                    public void run() {
                        if (isFinishing()) return;
                        if (fe != null) { toast("试听失败：" + fe); return; }
                        try {
                            if (mp != null) mp.release();
                            mp = new MediaPlayer();
                            mp.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build());
                            mp.setDataSource(f.getPath());
                            mp.prepare();
                            mp.start();
                        } catch (Exception e) { toast("播放失败"); }
                    }
                });
            }
        });
    }

    void testSearch(final String s) {
        final String k = get(s);
        if (k.length() == 0) { test.put(s, "失败：未配置"); refresh(); return; }
        runTest(s, new Probe() { public String run() throws Exception {
            if (S_TAVILY.equals(s)) {
                String body = new JSONObject().put("query", "OneSay").put("max_results", 1).toString();
                return verdict(call("https://api.tavily.com/search", "POST", bearer(k), body, 15000).code);
            }
            String body = new JSONObject().put("query", "OneSay").put("count", 1).toString();
            return verdict(call("https://api.bochaai.com/v1/web-search", "POST", bearer(k), body, 15000).code);
        } });
    }

    void testQw() {
        final String k = get(S_QW), host = RainService.host(this);
        if (k.length() == 0 || host.length() == 0) { test.put(S_QW, "失败：Key 或 Host 未配置"); refresh(); return; }
        runTest(S_QW, new Probe() { public String run() throws Exception {
            Map<String, String> hd = new HashMap<String, String>();
            hd.put("X-QW-Api-Key", k);
            hd.put("Accept-Encoding", "identity");
            return verdict(call("https://" + host + "/v7/weather/now?location=116.41,39.92", "GET", hd, null, 12000).code);
        } });
    }

    // ------------------------------------------------------------------ 视图

    int dp(int v) { return (int) (v * density + 0.5f); }

    TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, dp(2), 0, dp(2));
        return t;
    }

    LinearLayout card(LinearLayout col, String title, String need, String unlocks) {
        TextView h1 = text(title + "（" + need + "）", 15, TEXT);
        h1.setPadding(dp(4), dp(18), 0, dp(2));
        col.addView(h1);
        TextView h2 = text(unlocks, 12, SUB);
        h2.setPadding(dp(4), 0, 0, dp(6));
        col.addView(h2);
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackgroundColor(CARD);
        c.setPadding(dp(14), dp(4), dp(14), dp(4));
        col.addView(c);
        return c;
    }

    TextView row(LinearLayout card, String title, Runnable r) { return row(card, title, "", r); }

    /** 普通字段行：点开编辑框，「保存」校验后写入，「恢复预置」清掉自定义值。 */
    TextView fieldRow(LinearLayout card, final String title, final String hint, final Field f) {
        final TextView[] holder = new TextView[1];
        holder[0] = row(card, title, "", new Runnable() { public void run() {
            final EditText e = new EditText(KeysActivity.this);
            e.setSingleLine(true);
            e.setHint(f.preset());
            e.setText(f.custom() ? f.current() : "");
            secure(new AlertDialog.Builder(KeysActivity.this).setTitle(title).setMessage(hint)
                    .setView(e).setPositiveButton("保存", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) {
                            String why = f.save(e.getText().toString().trim());
                            if (why != null) toast("没保存：" + why);
                            test.clear(); refresh();
                        }
                    }).setNegativeButton("恢复预置", new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface d, int w) { f.save(""); test.clear(); refresh(); }
                    }).show());
        } });
        fields.add(new Object[]{holder[0], f});
        return holder[0];
    }

    TextView row(LinearLayout card, String title, String sub, final Runnable r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        row.setMinimumHeight(dp(52));
        row.addView(text(title, 15, TEXT));
        TextView s = text(sub, 12, SUB);
        row.addView(s);
        row.setOnClickListener(new View.OnClickListener() { public void onClick(View v) { r.run(); } });
        card.addView(row);
        return s;
    }

    void setStatus(TextView t, String s, boolean good) {
        if (t == null) return;
        t.setText(s);
        t.setTextColor(good ? OK : (s.startsWith("测试中") ? SUB : WARN));
    }

    AlertDialog secure(AlertDialog d) { return KeyMask.secure(d); }

    void open(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("没有可打开网页的应用"); }
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
