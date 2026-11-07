package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.util.Log;

import com.onesay.assistant.v2.util.SpeechText;

import java.util.Locale;

/**
 * 语音回复（Android 系统 TextToSpeech（zh-CN），没有可用引擎时静默降级。
 *
 * <ul>
 *   <li><b>开关</b>：{@code ux_prefs/tts_reply}，默认 <b>关</b>（需真机验证后再开）；控制台「体验实验开关」可切。</li>
 *   <li><b>播什么</b>：短问答答案与动作确认（{@link SpeechText#forSpeech} 去 Markdown、截到 60 字内的句末）。</li>
 *   <li><b>打断</b>：新一次唤醒（WakeService.requestWakeInternal）调用 {@link #stopAll()}。
 *       面板收起不打断（"已打开微信"这类确认在面板让位后仍应播完）。</li>
 *   <li><b>失败语义</b>：引擎初始化失败 / 不支持中文 → 标记不可用，之后所有 speak 直接返回（面板文字照常）。</li>
 * </ul>
 * 音频走 USAGE_ASSISTANT（API 26+），与媒体音量解耦。
 *
 * <h3>★ r2/cloud-tts（第一阶段）：云端优先，失败回退系统 TTS</h3>
 * 对外接口不变。{@code bixby_tts_cfg/cloud_enabled} 开着（debug 默认开、release 默认关）且有百炼 key
 * （复用 ASR 那把，{@code AliyunAsrConfig.getDashScopeKey()}）时，整句终稿交给百炼 CosyVoice（复刻音色「c」）合成、
 * AudioTrack 流式播放（{@link com.onesay.assistant.v2.tts.CloudUtterance}）；建连约 1.5s / 首包约 2.5s 还没出声、或任何失败
 * → 当场整句改走系统 TTS。已出声后断流不补播。{@link #stopAll()} 同时停云端会话和播放（barge-in）。
 * 截断规则（40/60 字）不变；按句流式是第二阶段。
 *
 * <h3>★ r2/voice-templates：回答只说固定短句，正文看卡片</h3>
 * 问答 / 执行回执 / 失败三类回答默认不再朗读正文，改播 {@link com.onesay.assistant.v2.tts.VoiceTemplate} 里预合成的短句
 * （「好了，看屏幕」「已完成」「出错了，看屏幕」「没连上网络」「已取消」），assets 直读、不联网。
 * 播放走同一个 {@link com.onesay.assistant.v2.tts.PcmStreamPlayer} + {@link com.onesay.assistant.v2.tts.SpeechProgressGate}：
 * 出声 fireStart、播完 fireDone(false)、被 stopAll 打断 fireDone(true)，续听 / duck / 打断检测都不变。
 * 设置「朗读完整回答」（{@link #K_TTS_FULL_ANSWER}，默认关）开着时回到原来的整句朗读（云端优先）。
 * 主动播报（{@link #speakProactive}）不受影响，照旧整句。
 */
public final class TtsSpeaker {

    private static final String TAG = "TtsSpeaker";
    public static final String PREF = "ux_prefs";
    public static final String K_TTS_REPLY = "tts_reply";
    /** ★ r2/sysux P1-3：语音发起的回合默认朗读（默认开）；tts_reply 只管文字发起的回合。 */
    public static final String K_TTS_VOICE_TURN = "tts_voice_turn";
    /** ★ r2/voice-templates：朗读完整回答（默认关 = 只说模板短句）。 */
    public static final String K_TTS_FULL_ANSWER = "tts_full_answer";

    // ==================== ★ r2/sysux 最小接口（与 r2/cloud-tts 合并时只需在云端播放的开始 / 结束处补调 fireStart / fireDone） ====================

    /** 播报进度回调（WakeService 注册：开始 → 压低媒体 + 打断检测；结束 → 续听窗或归还焦点）。任何线程回调。 */
    public interface SpeechListener {
        void onSpeechStart();
        /** interrupted = 被 stopAll 打断（新唤醒 / 说话打断 / 关开关）。 */
        void onSpeechDone(boolean interrupted);
        /** 这次结果不出声（开关关 / 引擎不可用 / 文字为空）。 */
        void onSpeechSkipped(String why);
    }

    private static volatile SpeechListener sListener;
    /** 当前回合是不是语音发起的（FloatingSheetService.onSubmitCommand 设置）。 */
    private static volatile boolean sVoiceTurn;
    /** 最近一次真正播报的 utteranceId（只为它回调）。 */
    private volatile String mCurrentId;

    public static void setSpeechListener(SpeechListener l) { sListener = l; }

    public static void setVoiceTurn(boolean voice) { sVoiceTurn = voice; }

    public static boolean isVoiceTurn() { return sVoiceTurn; }

    public static boolean isVoiceTurnReplyEnabled(Context ctx) {
        return prefs(ctx).getBoolean(K_TTS_VOICE_TURN, true);
    }

    public static void setVoiceTurnReplyEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(K_TTS_VOICE_TURN, on).apply();
    }

    public static boolean isFullAnswerEnabled(Context ctx) {
        return prefs(ctx).getBoolean(K_TTS_FULL_ANSWER, false);
    }

    public static void setFullAnswerEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(K_TTS_FULL_ANSWER, on).apply();
    }

    /**
     * 原完成应答音 ack_done 这次要不要跳过：这一轮会播回答模板（模板里已有「已完成 / 好了，看屏幕」）→ 跳过，免得连说两句。
     * 判定与 {@link #speak} 一致（语音回合看 tts_voice_turn、文字回合看 tts_reply）。
     */
    public static boolean shouldSkipAckDone(Context ctx) {
        boolean willSpeak = com.onesay.assistant.v2.wake.ConversationTurnPolicy.shouldSpeak(false, sVoiceTurn,
                isVoiceTurnReplyEnabled(ctx), isEnabled(ctx));
        return com.onesay.assistant.v2.tts.VoiceTemplate.ackDoneSuppressed(willSpeak, isFullAnswerEnabled(ctx));
    }

    /** ★ 11-04 相机对话：最近一次真正出声的时刻（elapsedRealtime；0 = 还没有）。打断检测要在出声之后才标定回声基线。 */
    private static volatile long sAudibleAt;

    public static long lastAudibleAt() {
        return sAudibleAt;
    }

    /**
     * ★ ui-K2 相机对话回声：播放走通话路由（USAGE_VOICE_COMMUNICATION），与 VOICE_COMMUNICATION 采集 + AEC 同一路，
     * 回声消除拿得到参考信号。只在相机对话期间开（LiveConversation 进出时设置），其余播报仍是 USAGE_ASSISTANT。
     */
    private static volatile boolean sCommRoute;
    private boolean mAttrsComm;

    public static void setCommRoute(boolean on) {
        sCommRoute = on;
    }

    public static boolean commRoute() {
        return sCommRoute;
    }

    /** 当前该用的播放用途（纯函数，供云端 PCM 播放器与系统 TTS 共用）。 */
    public static int playbackUsage(boolean commRoute) {
        return commRoute ? android.media.AudioAttributes.USAGE_VOICE_COMMUNICATION : android.media.AudioAttributes.USAGE_ASSISTANT;
    }

    static void fireStart() {
        sAudibleAt = android.os.SystemClock.elapsedRealtime();
        SpeechListener l = sListener;
        if (l != null) try { l.onSpeechStart(); } catch (Throwable t) { Log.w(TAG, "[TTS] start 回调异常: " + t); }
    }

    static void fireDone(boolean interrupted) {
        SpeechListener l = sListener;
        if (l != null) try { l.onSpeechDone(interrupted); } catch (Throwable t) { Log.w(TAG, "[TTS] done 回调异常: " + t); }
    }

    static void fireSkipped(String why) {
        SpeechListener l = sListener;
        if (l != null) try { l.onSpeechSkipped(why); } catch (Throwable t) { Log.w(TAG, "[TTS] skip 回调异常: " + t); }
    }

    private static volatile TtsSpeaker sInstance;

    private final Context mApp;
    private TextToSpeech mTts;
    /** OneSay：不做系统 TTS 兜底——云端语音没配置 / 失败就不出声（用户明确要求）。 */
    static final boolean SYSTEM_TTS_FALLBACK = false;
    private volatile boolean mReady = false;
    private volatile boolean mUnavailable = false;
    private String mPending;
    /** 当前云端播报（null = 没有）。 */
    private com.onesay.assistant.v2.tts.CloudUtterance mCloud;
    private com.onesay.assistant.v2.tts.PcmStreamPlayer mPlayer;
    /** ★ 综合包：当前云端播报的进度闸门（fireStart / fireDone 每句一次）。 */
    private com.onesay.assistant.v2.tts.SpeechProgressGate mCloudGate;
    /** ★ r2/voice-templates：当前模板播放（null = 没有）与它的进度闸门。 */
    private com.onesay.assistant.v2.tts.PcmStreamPlayer mTplPlayer;
    private com.onesay.assistant.v2.tts.SpeechProgressGate mTplGate;
    /** ★ arch/voice-sync：当前一次性提示（应答 / 催一次 / 提问提示）及其收尾回调。 */
    private com.onesay.assistant.v2.tts.PcmStreamPlayer mCuePlayer;
    private Runnable mCueDone;
    /** ★ arch/voice-sync：下一句回答模板改用这一句（AgentLoop / 多步计划按结局选句）；mute = 这句不出声。主线程设、speak 里取。 */
    private static volatile com.onesay.assistant.v2.tts.VoiceTemplate sReplyOverride;
    private static volatile boolean sReplyMute;
    /** 模板 PCM 缓存（每个几十 KB，读一次留着）。 */
    private final java.util.EnumMap<com.onesay.assistant.v2.tts.VoiceTemplate, com.onesay.assistant.v2.tts.VoiceTemplate.Pcm> mTplCache =
            new java.util.EnumMap<com.onesay.assistant.v2.tts.VoiceTemplate, com.onesay.assistant.v2.tts.VoiceTemplate.Pcm>(com.onesay.assistant.v2.tts.VoiceTemplate.class);
    /** 专属域名握手被拒后本进程改走 dashscope.aliyuncs.com。 */
    private static volatile boolean sHostDowngraded;

    private TtsSpeaker(Context ctx) {
        mApp = ctx.getApplicationContext();
    }

    public static TtsSpeaker get(Context ctx) {
        if (sInstance == null) {
            synchronized (TtsSpeaker.class) {
                if (sInstance == null) sInstance = new TtsSpeaker(ctx);
            }
        }
        return sInstance;
    }

    public static boolean isEnabled(Context ctx) {
        return prefs(ctx).getBoolean(K_TTS_REPLY, false);
    }

    public static void setEnabled(Context ctx, boolean on) {
        prefs(ctx).edit().putBoolean(K_TTS_REPLY, on).apply();
        if (!on) stopAll();
    }

    private static SharedPreferences prefs(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE);
    }

    /** 打断正在进行的播报（新唤醒 / 关闭开关时调用；未初始化时无操作）。 */
    public static void stopAll() {
        TtsSpeaker s = sInstance;
        if (s == null) return;
        synchronized (s) {
            s.mPending = null;
            s.cancelCloudLocked();
            s.cancelTemplateLocked();
            s.cancelCueLocked();   // ★ arch/voice-sync：打断（barge-in / ttsstop / 新唤醒）同样停掉应答 / 催一次
            try {
                if (s.mTts != null && s.mReady) s.mTts.stop();
            } catch (Throwable ignore) {}
        }
        // ★ r2/sysux：引擎对 stop() 不一定回 onStop（三星 TTS 待真机核）——这里补一次「被打断」，回调方幂等
        String cur = s.mCurrentId;
        if (cur != null) {
            s.mCurrentId = null;
            fireDone(true);
        }
    }

    public static boolean isSpeaking() {
        TtsSpeaker s = sInstance;
        try {
            if (s == null) return false;
            com.onesay.assistant.v2.tts.CloudUtterance cu = s.mCloud;
            com.onesay.assistant.v2.tts.PcmStreamPlayer pl = s.mPlayer;
            if ((cu != null && cu.isActive()) || (pl != null && pl.isPlaying())) return true;
            com.onesay.assistant.v2.tts.PcmStreamPlayer tp = s.mTplPlayer;
            if (tp != null && tp.isPlaying()) return true;
            com.onesay.assistant.v2.tts.PcmStreamPlayer cp = s.mCuePlayer;
            if (cp != null && cp.isPlaying()) return true;
            return s.mReady && s.mTts != null && s.mTts.isSpeaking();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 播报短答案（问答）。[r2/uxcompare] 先去掉"好的，""根据检索结果，"这类铺垫，只播前 40 字内的完整句子
     * （见 {@link SpeechText#forAnswer}；全文在卡片上）。
     */
    public void speakAnswer(String text) {
        // [richout] 富内容回答：代码 / 图 / 表格不念，换成一句「代码在屏幕上」之类；链接只念文字（插件关 / 纯文本 → null，照旧）
        com.onesay.assistant.plug.richout.core.RichText.Speech sp = com.onesay.assistant.plug.richout.host.RichOutHost.speechPlan(text);
        if (sp != null) text = sp.spoken;
        speak(SpeechText.stripLeadFiller(text), false, SpeechText.ANSWER_MAX, com.onesay.assistant.v2.tts.VoiceTemplate.ANSWER);
    }

    /**
     * [r2/uxcompare] 播报失败提示（原因 + 下一步，≤40 字）。原来失败一律不出声，用户眼睛不在屏上时
     * 以为"没反应"；竞品失败都会说一句。同样受「语音回复」开关控制（默认关）。
     */
    public void speakFailure(String text) {
        speakFailure(text, null);
    }

    /** ★ r2/voice-templates：带错误码，模板按码挑「已取消 / 没连上网络 / 出错了，看屏幕」。 */
    public void speakFailure(String text, com.onesay.assistant.v2.executor.ErrorCode code) {
        speak(text, false, SpeechText.FAILURE_MAX, com.onesay.assistant.v2.tts.VoiceTemplate.forFailure(code));
    }

    /** 播报动作确认（执行成功的回执）。 */
    public void speakConfirmation(String text) {
        speak(text, false, SpeechText.DEFAULT_MAX, com.onesay.assistant.v2.tts.VoiceTemplate.DONE);
    }

    /**
     * 2026-10-23 lane-a：主动播报（定时任务回调 / 「详细说说」）——不看「语音回复」开关（那是问答回执的开关），
     * 是否允许出声由调用方（回调中枢的免打扰规则）决定。max = 最多朗读字数。
     */
    public void speakProactive(String text, int max) {
        speak(text, true, max, null);
    }

    /**
     * ★ 11-04 相机对话：把视觉模型的回答整句念出来。不看「语音回复」开关（用户主动进的语音对话），不用模板短句；
     * <b>云端合成不看「云端朗读」开关</b>（只要有百炼 key）—— release 默认关云端、三星系统 TTS 又常没有中文引擎（真机 -2 静默），
     * 不这样相机对话就是哑的。云端失败照旧回退系统 TTS。
     */
    public synchronized void speakLive(String raw, int max) {
        if (com.onesay.assistant.v2.leakguard.LeakGuardHost.blockOutput(mApp, "TtsSpeaker.speakLive", raw)) return;
        String text = SpeechText.forSpeech(raw, max);
        if (text.length() == 0) return;
        com.onesay.assistant.v2.asr.AnswerEchoGuard.noteSpoken(text, android.os.SystemClock.elapsedRealtime());
        cancelTemplateLocked();
        if (trySpeakCloud(text, true, true)) return;
        speakSystemLocked(text, true);
    }

    /** debug 直测：按回答路径播一句模板（看开关、走进度回调，与真实回答一致）。 */
    public void speakTemplateForTest(com.onesay.assistant.v2.tts.VoiceTemplate t) {
        speak(t.text, false, SpeechText.DEFAULT_MAX, t);
    }

    private synchronized void speak(String raw, boolean force, int max, com.onesay.assistant.v2.tts.VoiceTemplate tpl) {
        // ★ r2/sysux P1-3：语音问就语音答 —— 语音回合看 tts_voice_turn（默认开），文字回合看 tts_reply（默认关）
        boolean want = com.onesay.assistant.v2.wake.ConversationTurnPolicy.shouldSpeak(force, sVoiceTurn,
                isVoiceTurnReplyEnabled(mApp), isEnabled(mApp));
        if (!want) {
            if (!force) fireSkipped("开关关（voiceTurn=" + sVoiceTurn + "）");
            return;
        }
        // ★ arch/voice-sync：宿主按结局选好了这一句（AgentLoop / 多步计划）→ 用它；mute（媒体在播 / 通话 / 正在说话）→ 不出声
        if (!force && tpl != null && sReplyOverride != null) {
            tpl = sReplyOverride;
            if (raw == null || raw.trim().length() == 0) raw = tpl.text;
            if (sReplyMute) {
                fireSkipped("voice-sync 静音 · " + tpl.text);
                return;
            }
        }
        // ★ fix/leak-guard：要读的是系统提示词 / 热词表回吐 → 不读（模板、云端、系统 TTS 之前统一判，看全文不看截短后的）
        if (com.onesay.assistant.v2.leakguard.LeakGuardHost.blockOutput(mApp, "TtsSpeaker.speak", raw)) {
            if (!force) fireSkipped("提示词回吐拦截");
            return;
        }
        String text = SpeechText.forSpeech(raw, max);
        if (text.length() == 0) {
            if (!force) fireSkipped("文字为空");
            return;
        }
        // ★ device-1102：登记这次要读的话（AnswerEchoGuard：开麦后识别出的若就是它 → 回采，不上屏不执行）
        com.onesay.assistant.v2.asr.AnswerEchoGuard.noteSpoken(text, android.os.SystemClock.elapsedRealtime());
        // ★ r2/voice-templates：回答默认只说模板短句（正文在卡片上）；「朗读完整回答」开着才整句读
        if (tpl != null && com.onesay.assistant.v2.tts.VoiceTemplate.useTemplate(force, isFullAnswerEnabled(mApp))) {
            // OneSay 不随包带预录音频：模板音频缺失时用云端语音念模板短句
            if (loadTemplate(tpl) != null) {
                playTemplateLocked(tpl);
                return;
            }
            text = SpeechText.forSpeech(tpl.text, max);
        }
        cancelTemplateLocked();   // ★ r2/voice-templates：整句朗读顶掉还在播的模板
        // ★ 合并 r2/cloud-tts：云端优先（系统引擎不可用也照样能走云端），失败再回退系统 TTS
        if (trySpeakCloud(text, force)) return;
        speakSystemLocked(text, force);
    }

    /** 系统 TTS 播一句（持锁调用）。force = 主动播报（不报 skipped，那不是一轮对话）。 */
    private void speakSystemLocked(String text, boolean force) {
        if (!SYSTEM_TTS_FALLBACK) {
            if (!force) fireSkipped("云端语音未配置或失败（不回退系统 TTS）");
            return;
        }
        if (com.onesay.assistant.v2.leakguard.LeakGuardHost.blockOutput(mApp, "tts.system", text)) {   // ★ fix/leak-guard：系统 TTS 前兜底
            if (!force) fireSkipped("提示词回吐拦截");
            return;
        }
        if (mUnavailable) {
            if (!force) fireSkipped("引擎不可用");
            return;
        }
        if (!mReady) {
            mPending = text;
            ensureInit();
            return;
        }
        applyRouteAttrsLocked();
        doSpeak(text);
    }

    /** ★ ui-K2：系统 TTS 的音频用途跟着通话路由切（只在变了时设一次）。 */
    private void applyRouteAttrsLocked() {
        boolean comm = sCommRoute;
        if (comm == mAttrsComm || mTts == null || Build.VERSION.SDK_INT < 26) return;
        try {
            mTts.setAudioAttributes(new android.media.AudioAttributes.Builder()
                    .setUsage(playbackUsage(comm))
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            mAttrsComm = comm;
        } catch (Throwable ignore) {}
    }

    // ------------------------------------------------------------------ ★ r2/voice-templates 模板短句

    /** 停掉上一句模板（持锁调用）。闸门同步报「被打断」（已出声才报），播放线程迟到的收尾被闸门吞掉。 */
    private void cancelTemplateLocked() {
        com.onesay.assistant.v2.tts.PcmStreamPlayer p = mTplPlayer;
        com.onesay.assistant.v2.tts.SpeechProgressGate g = mTplGate;
        mTplPlayer = null;
        mTplGate = null;
        if (g != null) g.cancel();
        if (p != null) p.stop();
    }

    private com.onesay.assistant.v2.tts.VoiceTemplate.Pcm loadTemplate(com.onesay.assistant.v2.tts.VoiceTemplate t) {
        com.onesay.assistant.v2.tts.VoiceTemplate.Pcm p = mTplCache.get(t);
        if (p != null) return p;
        java.io.InputStream in = null;
        try {
            in = mApp.getAssets().open(t.asset);
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream(64 * 1024);
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            p = com.onesay.assistant.v2.tts.VoiceTemplate.parseWav(bo.toByteArray());
        } catch (Throwable e) {
            Log.w(TAG, "[TTS] 模板音频读取失败 " + t.asset + ": " + e);
            p = null;
        } finally {
            if (in != null) try { in.close(); } catch (Throwable ignore) {}
        }
        if (p != null) mTplCache.put(t, p);
        return p;
    }

    /** 播一句模板（持锁调用）。新一句顶掉上一句（模板 / 云端 / 系统 TTS 都停）。 */
    private void playTemplateLocked(final com.onesay.assistant.v2.tts.VoiceTemplate t) {
        cancelTemplateLocked();
        cancelCloudLocked();
        cancelCueLocked();   // ★ arch/voice-sync：结果模板顶掉还在播的应答 / 催一次（收尾回调在锁外补发）
        mPending = null;
        try {
            if (mTts != null && mReady) mTts.stop();
        } catch (Throwable ignore) {}
        com.onesay.assistant.v2.tts.VoiceTemplate.Pcm pcm = loadTemplate(t);
        if (pcm == null) {
            fireSkipped("模板音频缺失 · " + t.asset);
            return;
        }
        final com.onesay.assistant.v2.tts.SpeechProgressGate gate = new com.onesay.assistant.v2.tts.SpeechProgressGate(
                new com.onesay.assistant.v2.tts.SpeechProgressGate.Out() {
                    @Override public void onStart() { fireStart(); }
                    @Override public void onDone(boolean interrupted) { fireDone(interrupted); }
                    @Override public void onSilent(String why) { fireSkipped("模板没出声 · " + why); }
                });
        final boolean selfFocus = com.onesay.assistant.v2.tts.SpeechProgressGate.playerOwnsFocus(sListener != null);
        final com.onesay.assistant.v2.tts.PcmStreamPlayer player = new com.onesay.assistant.v2.tts.PcmStreamPlayer(mApp,
                new com.onesay.assistant.v2.tts.PcmStreamPlayer.PlaybackListener() {
                    @Override public void onAudible() { gate.audible(); }
                    @Override public void onFinished(boolean stopped, String err) {
                        gate.finished(stopped, err);
                        synchronized (TtsSpeaker.this) {
                            if (mTplGate == gate) { mTplGate = null; mTplPlayer = null; }
                        }
                    }
                }, selfFocus);
        mTplPlayer = player;
        mTplGate = gate;
        Log.i(TAG, "[TTS] 模板「" + t.text + "」" + t.asset + " " + pcm.data.length + "B " + pcm.durationMs() + "ms（不联网）");
        player.begin(pcm.sampleRate);
        player.write(pcm.data);
        player.end();
    }

    // ------------------------------------------------------------------ ★ arch/voice-sync 一次性提示（应答 / 催一次 / 提问）

    /**
     * ★ arch/voice-sync：宿主按结局选的回答模板（一次性，配对调用：设 → showAskReply → {@link #clearReplyOverride}）。
     * mute = 这句不出声（媒体在播 / 通话中 / 用户正在说话）。
     */
    public static void setReplyOverride(com.onesay.assistant.v2.tts.VoiceTemplate t, boolean mute) {
        sReplyMute = mute;
        sReplyOverride = t;
    }

    public static void clearReplyOverride() {
        sReplyOverride = null;
        sReplyMute = false;
    }

    /** 当前回合「回答」会不会出声（与 {@link #speak} 同一判定；voice-sync 的提示也按它判设置开关）。 */
    public static boolean replyWillSpeak(Context ctx) {
        return com.onesay.assistant.v2.wake.ConversationTurnPolicy.shouldSpeak(false, sVoiceTurn, isVoiceTurnReplyEnabled(ctx), isEnabled(ctx));
    }

    /** debug 直测 / AgentLoop 无结果时：按回答路径播一句指定模板。 */
    public void speakReplyTemplate(com.onesay.assistant.v2.tts.VoiceTemplate t) {
        if (t != null) speak(t.text, false, SpeechText.DEFAULT_MAX, t);
    }

    /**
     * ★ arch/voice-sync：播一句一次性提示（应答「收到，我来弄」/ 催一次 / 提问提示）。<b>不进回答状态机</b>（不 fireStart / fireDone，
     * 不开续听、不动焦点状态机）；新提示顶掉上一句提示，结果模板 / 云端整句 / {@link #stopAll} 都会停掉它。
     * done 恰好回调一次（播完 / 被停 / 出错，任意线程）。返回 false = 音频缺失没播（done 不回调）。
     */
    public boolean playCue(final com.onesay.assistant.v2.tts.VoiceTemplate t, final Runnable done) {
        com.onesay.assistant.v2.tts.VoiceTemplate.Pcm pcm;
        synchronized (this) {
            cancelCueLocked();
            pcm = loadTemplate(t);
            if (pcm == null) {
                Log.w(TAG, "[TTS] 提示音频缺失 " + t.asset);
            } else {
                final com.onesay.assistant.v2.tts.PcmStreamPlayer[] self = new com.onesay.assistant.v2.tts.PcmStreamPlayer[1];
                final boolean[] once = {false};
                final Runnable fin = new Runnable() {
                    @Override public void run() {
                        synchronized (once) {
                            if (once[0]) return;
                            once[0] = true;
                        }
                        if (done != null) try { done.run(); } catch (Throwable e) { Log.w(TAG, "[TTS] 提示收尾回调异常: " + e); }
                    }
                };
                com.onesay.assistant.v2.tts.PcmStreamPlayer player = new com.onesay.assistant.v2.tts.PcmStreamPlayer(mApp,
                        new com.onesay.assistant.v2.tts.PcmStreamPlayer.PlaybackListener() {
                            @Override public void onAudible() {}
                            @Override public void onFinished(boolean stopped, String err) {
                                synchronized (TtsSpeaker.this) {
                                    if (mCuePlayer == self[0]) { mCuePlayer = null; mCueDone = null; }
                                }
                                fin.run();
                            }
                        }, false);
                self[0] = player;
                mCuePlayer = player;
                mCueDone = fin;
                Log.i(TAG, "[TTS] 提示「" + t.text + "」" + t.asset + " " + pcm.durationMs() + "ms（一次性，不进回答状态机）");
                player.begin(pcm.sampleRate);
                player.write(pcm.data);
                player.end();
            }
        }
        return pcm != null;
    }

    /**
     * 停掉当前提示（持锁调用）。收尾回调当场补发（once 吞掉播放线程迟到的 onFinished）：回调方（VoiceSyncSession）
     * 只拿它自己的锁、从不在持有它的锁时回调到这里，不会死锁。
     */
    private void cancelCueLocked() {
        com.onesay.assistant.v2.tts.PcmStreamPlayer p = mCuePlayer;
        Runnable d = mCueDone;
        mCuePlayer = null;
        mCueDone = null;
        if (p != null) {
            try { p.stop(); } catch (Throwable ignore) {}
        }
        if (d != null) d.run();
    }

    // ------------------------------------------------------------------ ★ r2/cloud-tts 云端通道

    /** 新一句之前 / 打断时：停掉上一句云端播报（持锁调用）。 */
    private void cancelCloudLocked() {
        com.onesay.assistant.v2.tts.CloudUtterance cu = mCloud;
        com.onesay.assistant.v2.tts.SpeechProgressGate g = mCloudGate;
        mCloud = null;
        mPlayer = null;
        mCloudGate = null;
        // ★ 综合包：同步报「被打断」（已出声才报），播放线程迟到的收尾被闸门吞掉
        if (g != null) g.cancel();
        if (cu != null) cu.cancel();
    }

    /** 云端可用就开一句云端播报并返回 true；不可用（开关关 / 没 key）返回 false 走系统 TTS。持锁调用。 */
    private boolean trySpeakCloud(final String text, final boolean force) {
        return trySpeakCloud(text, force, false);
    }

    /** ignoreSwitch = 不看「云端朗读」开关（相机对话，{@link #speakLive}）。 */
    private boolean trySpeakCloud(final String text, final boolean force, final boolean ignoreSwitch) {
        // ★ fix/leak-guard：云端合成前兜底（speak 入口已判过全文；这里防以后有别的调用方直接走云端）
        if (com.onesay.assistant.v2.leakguard.LeakGuardHost.blockOutput(mApp, "tts.cloud", text)) {
            if (!force) fireSkipped("提示词回吐拦截");
            return true;
        }
        final com.onesay.assistant.v2.tts.CloudTtsConfig cfg;
        final String key;
        try {
            cfg = new com.onesay.assistant.v2.tts.CloudTtsConfig(mApp);
            if (!cfg.isEnabled() && !ignoreSwitch) return false;
            key = new com.onesay.assistant.v2.asr.AliyunAsrConfig(mApp).getDashScopeKey();
        } catch (Throwable t) {
            Log.w(TAG, "[TTS] 云端配置读取失败，走系统 TTS: " + t);
            return false;
        }
        if (key == null || key.length() == 0) {
            Log.i(TAG, "[TTS] 云端开着但没有百炼 key，走系统 TTS");
            return false;
        }
        // 同 QUEUE_FLUSH：新一句顶掉上一句（云端、模板和系统都停）
        cancelCloudLocked();
        cancelTemplateLocked();
        cancelCueLocked();   // ★ arch/voice-sync
        mPending = null;
        try {
            if (mTts != null && mReady) mTts.stop();
        } catch (Throwable ignore) {}
        // 系统引擎同时预热：万一要回退，不用再等 init
        if (SYSTEM_TTS_FALLBACK && !mUnavailable) ensureInit();

        final String url = sHostDowngraded && !cfg.hasCustomEndpoint() ? com.onesay.assistant.v2.tts.CloudTtsConfig.DASHSCOPE_WS_URL : cfg.wsUrl();
        final String model = cfg.getModel();
        final String voice = cfg.getVoice();
        final int sr = cfg.getSampleRate();
        final int connectMs = cfg.getConnectTimeoutMs();
        // ★ 综合包（cloud-tts × sysux）：真正出声 → fireStart；播完 / 被停 → fireDone(false / true)；没出声的失败 → fireSkipped。
        //   焦点：WakeService 注册了 SpeechListener（AssistAudioFocus 状态机在）→ 播放器不自己申请，统一由状态机 duck / 续听 / 归还。
        final com.onesay.assistant.v2.tts.SpeechProgressGate gate = new com.onesay.assistant.v2.tts.SpeechProgressGate(
                new com.onesay.assistant.v2.tts.SpeechProgressGate.Out() {
                    @Override public void onStart() { fireStart(); }
                    @Override public void onDone(boolean interrupted) { fireDone(interrupted); }
                    @Override public void onSilent(String why) { if (!force) fireSkipped("云端没出声 · " + why); }
                });
        final boolean selfFocus = com.onesay.assistant.v2.tts.SpeechProgressGate.playerOwnsFocus(sListener != null);
        final com.onesay.assistant.v2.tts.PcmStreamPlayer player = new com.onesay.assistant.v2.tts.PcmStreamPlayer(mApp,
                new com.onesay.assistant.v2.tts.PcmStreamPlayer.PlaybackListener() {
                    @Override public void onAudible() { gate.audible(); }
                    @Override public void onFinished(boolean stopped, String err) {
                        gate.finished(stopped, err);
                        synchronized (TtsSpeaker.this) {
                            if (mCloudGate == gate) { mCloudGate = null; mPlayer = null; }
                        }
                    }
                }, selfFocus);
        final com.onesay.assistant.v2.tts.CloudUtterance[] self = new com.onesay.assistant.v2.tts.CloudUtterance[1];
        com.onesay.assistant.v2.tts.CloudUtterance u = new com.onesay.assistant.v2.tts.CloudUtterance(text,
                new com.onesay.assistant.v2.tts.CloudUtterance.SessionFactory() {
                    @Override
                    public com.onesay.assistant.v2.tts.DashScopeTtsSession create(com.onesay.assistant.v2.tts.DashScopeTtsSession.Listener l) {
                        // TCP 连接超时给到建连预算；整体建连 / 首包预算由 CloudUtterance 的看门狗卡
                        return new com.onesay.assistant.v2.tts.DashScopeTtsSession(url, key, model, voice, sr, Math.max(connectMs, 1000), l);
                    }
                },
                player,
                new com.onesay.assistant.v2.tts.CloudUtterance.Fallback() {
                    @Override
                    public void speakSystem(String t, String reason) {
                        Log.w(TAG, "[TTS] 云端未出声，回退系统 TTS：" + reason);
                        synchronized (TtsSpeaker.this) {
                            if (mCloud != self[0]) return;   // 已被新一句 / 打断顶掉
                            mCloud = null;
                            mPlayer = null;
                            mCloudGate = null;   // 没出声：闸门不报，系统 TTS 自己的 onStart / onDone 接力
                            speakSystemLocked(t, force);
                        }
                    }
                },
                new com.onesay.assistant.v2.tts.CloudUtterance.Observer() {
                    @Override
                    public void onFinished(com.onesay.assistant.v2.tts.CloudUtterance cu) {
                        com.onesay.assistant.v2.tts.DashScopeTtsSession s = cu.session();
                        int http = s == null ? -1 : s.handshakeHttpCode;
                        Log.i(TAG, "[TTS] 云端 " + cu.outcome() + " 建连=" + cu.connectMs + "ms 首包=" + cu.firstAudioMs
                                + "ms 合成完=" + cu.totalMs + "ms 字数=" + text.length()
                                + (s == null ? "" : " 音频=" + s.audioBytes() + "B 计费字符=" + s.usageCharacters
                                        + " host=" + hostOf(s.url()))
                                + (http > 0 ? " http=" + http : "") + " " + cu.reason());
                        if (!sHostDowngraded && com.onesay.assistant.v2.tts.CloudTtsConfig.shouldDowngradeHost(http)
                                && !com.onesay.assistant.v2.tts.CloudTtsConfig.DASHSCOPE_WS_URL.equals(url)) {
                            sHostDowngraded = true;
                            Log.w(TAG, "[TTS] 专属域名握手被拒（HTTP " + http + "），本进程改走 dashscope.aliyuncs.com");
                        }
                    }
                },
                connectMs, cfg.getFirstAudioTimeoutMs());
        self[0] = u;
        mCloud = u;
        mPlayer = player;
        mCloudGate = gate;
        Log.i(TAG, "[TTS] 云端播报 " + text.length() + " 字 → " + hostOf(url) + " " + model);
        u.start();
        return true;
    }

    private static String hostOf(String url) {
        try {
            return java.net.URI.create(url).getHost();
        } catch (Throwable t) {
            return "?";
        }
    }

    private void doSpeak(String text) {
        try {
            // 先留 250ms 空白：成功应答音（ack_done.wav）与语音不打架
            mTts.playSilentUtterance(250, TextToSpeech.QUEUE_FLUSH, "bixby-gap");
            String id = "bixby-" + System.currentTimeMillis();
            mCurrentId = id;
            mTts.speak(text, TextToSpeech.QUEUE_ADD, null, id);
            Log.i(TAG, "[TTS] 播报 " + text.length() + " 字");
        } catch (Throwable t) {
            Log.w(TAG, "[TTS] 播报失败: " + t.getMessage());
        }
    }

    private void ensureInit() {
        if (mTts != null) return;
        try {
            mTts = new TextToSpeech(mApp, new TextToSpeech.OnInitListener() {
                @Override
                public void onInit(int status) {
                    onEngineInit(status);
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "[TTS] 创建引擎失败，语音回复降级为静默: " + t.getMessage());
            mUnavailable = true;
        }
    }

    private synchronized void onEngineInit(int status) {
        if (status != TextToSpeech.SUCCESS || mTts == null) {
            Log.w(TAG, "[TTS] 引擎初始化失败 status=" + status + "，语音回复降级为静默");
            mUnavailable = true;
            return;
        }
        int lang;
        try {
            lang = mTts.setLanguage(Locale.SIMPLIFIED_CHINESE);
        } catch (Throwable t) {
            lang = TextToSpeech.LANG_NOT_SUPPORTED;
        }
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) {
            Log.w(TAG, "[TTS] 当前引擎不支持中文（" + lang + "），语音回复降级为静默；可在系统设置里装三星/讯飞中文 TTS");
            mUnavailable = true;
            return;
        }
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                mTts.setAudioAttributes(new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
            }
            mTts.setSpeechRate(1.1f);
        } catch (Throwable ignore) {}
        // ★ r2/sysux：播报进度（续听窗 / 焦点压低 / 打断检测要知道什么时候开始、什么时候播完）
        try {
            mTts.setOnUtteranceProgressListener(new android.speech.tts.UtteranceProgressListener() {
                @Override public void onStart(String id) {
                    if (id != null && id.equals(mCurrentId)) fireStart();
                }
                @Override public void onDone(String id) {
                    if (id != null && id.equals(mCurrentId)) { mCurrentId = null; fireDone(false); }
                }
                @Override @Deprecated public void onError(String id) {
                    if (id != null && id.equals(mCurrentId)) { mCurrentId = null; fireDone(false); }
                }
                @Override public void onStop(String id, boolean interrupted) {
                    if (id != null && id.equals(mCurrentId)) { mCurrentId = null; fireDone(true); }
                }
            });
        } catch (Throwable t) {
            Log.w(TAG, "[TTS] 进度监听注册失败（续听窗不会自动开）: " + t);
        }
        mReady = true;
        Log.i(TAG, "[TTS] 引擎就绪: " + mTts.getDefaultEngine());
        if (mPending != null) {
            String p = mPending;
            mPending = null;
            doSpeak(p);
        }
    }
}
