package com.onesay.assistant.v2.ui;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * OneSay 声纹注册「引导式录制」控制器。
 *
 * <p>**从唤醒面板（FloatingSheetService）整体迁出** —— 用户定档：
 * 声纹录制属于"设置类"功能，入口放 App 本体（MainActivity）的界面上，
 * 不再挂在语音交互的唤醒面板里（那个面板是"喊指令"的场景，塞录制按钮语义混乱）。
 *
 * <p>本类只做**流程编排**，不碰任何具体 UI 组件：状态变化通过 {@link Listener}
 * 回调给宿主（当前 = MainActivity）。音频采集由
 * {@link com.onesay.assistant.v2.wake.WakeService} 的采集循环独立攒取（录制期间跳过
 * KWS/能量门/云端缓冲，防引导句触发唤醒或被执行）。
 *
 * <pre>
 * 状态机（四态）：
 * IDLE      → 主按钮："🎙 录制声纹"        点击：进入引导，显示第 1 句
 * READY     → 主按钮："● 开始录制"          点击：开始录制当前句
 * RECORDING → 主按钮："⏹ 停止录音"          点击：停止 → 收段（只看声纹自洽；字比对已停用，见 TEXT_CHECK_ENABLED）
 * CHECKING  → 主按钮："记录中…"（不响应）   完成后：下一句（回 READY）或整体注册（回 IDLE）
 * </pre>
 *
 * <p><b>设计原则（用户定档）</b>：用户念什么系统收什么 —— 段的质量（字比对分 /
 * 自洽性）只在**最后统一筛选**时由系统内部使用，**绝不用于打回用户重录**。
 * 声纹是"系统拟合用户的声音"，不是"用户迎合系统的识别"。
 *
 * <p><b>唤醒互斥</b>：会话进入（READY）即置 {@link com.onesay.assistant.v2.wake.WakeService#setEnrollSession(boolean)}
 * 为 true —— 引导句本身就是唤醒词，会话全程压制唤醒，防"每念一句弹一次面板"。
 * 会话结束（完成 / 取消 / 宿主 onStop 调 {@link #abort()}）时复位。
 */
public class VoiceprintEnrollController {

    private static final String TAG = "VpEnroll";

    /**
     * 宿主的 UI 回调（全部在主线程触发）。
     */
    public interface Listener {
        /**
         * 状态区文案更新（正文已含全部信息，无需小标题）。
         *
         * @param color   文字颜色
         * @param message 正文（多行引导文案）
         */
        void onVpMessage(int color, String message);

        /**
         * 主按钮文案/样式更新。
         *
         * @param label  按钮文案（"🎙 录制声纹" / "● 开始录制" / "⏹ 停止录音" / "记录中…"）
         * @param danger true = 录制中的警示样式（红）
         */
        void onVpButton(String label, boolean danger);

        /**
         * 会话结束（主线程）。
         *
         * @param enrolled 是否成功完成注册（false = 失败/取消/无有效段）
         * @param used     实际采用的段数
         * @param total    录到的总段数
         */
        void onVpFinished(boolean enrolled, int used, int total);
    }

    /**
     * r2/fixes 15：「字比对」<b>明确降级停用</b>。本地离线识别（vosk）2026-10-04 已整体下线，
     * {@code WakeService.recognizeOffline} 恒返回空串 —— 这一步实际上什么也没比。改用云端 ASR 比对会把声纹注册录音传出本机，
     * 与「只存在本机，永不上传」的承诺冲突，所以不修，直接停用：不再调用识别、不再记「识别文本」、筛选只按声纹自洽；
     * 界面在引导句和完成提示里说明「只学声音，不核对念的字」。
     */
    public static final boolean TEXT_CHECK_ENABLED = false;

    /** r2/fixes 15：界面上的说明（引导句和完成提示都带）。 */
    public static final String TEXT_CHECK_NOTE = "只学声音，不核对念的字";

    // ---------- 状态机 ----------
    private static final int VP_IDLE = 0;       // 空闲
    private static final int VP_READY = 1;      // 已显示当前句，待用户点「● 开始录制」
    private static final int VP_RECORDING = 2;  // 录制中
    private static final int VP_CHECKING = 3;   // 校对/保存中（不响应点击）

    private int mStage = VP_IDLE;
    private int mPhraseIdx = 0;
    /** 流程代数：进入引导与中止时 +1，用于作废在途的校对/注册回调（防迟到回调污染新一轮）。 */
    private int mSession = 0;
    /** 已收下的录音段（校对线程 add，主线程仅在流程开始/中止时 clear —— stage 机保证不相交）。 */
    private final List<VpSeg> mSegs = new ArrayList<VpSeg>();

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final Context mAppCtx;
    private Listener mListener;

    /**
     * 声纹注册引导句：多句不同内容（同一句重复会让模板对内容过拟合）。OneSay 不内置唤醒词，引导句只用普通命令。
     */
    public static final String[] PHRASES = {
            "你好，现在几点",
            "打开相机",
            "今天天气怎么样",
            "提醒我明天早上八点开会",
    };

    /**
     * ★ 一段录音的收集结果。
     * emb = null 表示本段无效（太短/太静），被静默跳过、不打断用户。
     */
    private static class VpSeg {
        float[] emb;        // 声纹向量（null = 本段无效，已被跳过）
        float match;        // 字比对匹配率（-1 = 未测；r2/fixes 15 起字比对停用，恒为 -1）
        int heardLen;       // 离线识别文本长度（r2/fixes 15 起恒为 0）
        float sec;          // 时长（秒）
    }

    /** 录制实时计时（200ms 刷"已录 X 秒"，让用户看得见节奏）。 */
    private final Runnable mTimerRunnable = new Runnable() {
        @Override
        public void run() {
            if (mStage != VP_RECORDING) return;
            long ms = com.onesay.assistant.v2.wake.WakeService.getVoiceprintRecordedMs();
            int maxMs = com.onesay.assistant.v2.asr.PcmWindowBuffer.MAX_SECONDS * 1000;
            String tail = (ms >= maxMs) ? "（已达上限，可以停了）" : "，念完点「⏹ 停止录音」";
            msg(Color.parseColor("#FF453A"),
                    "第 " + (mPhraseIdx + 1) + "/" + PHRASES.length + " 句…已录 "
                            + String.format("%.1f", ms / 1000.0f) + " 秒" + tail);
            mHandler.postDelayed(this, 200);
        }
    };

    public VoiceprintEnrollController(Context ctx) {
        mAppCtx = ctx.getApplicationContext();
    }

    public void setListener(Listener l) {
        mListener = l;
    }

    /** 会话是否活跃（非 IDLE）—— 宿主 onStop 时据此决定要不要 abort。 */
    public boolean isActive() {
        return mStage != VP_IDLE;
    }

    // ---------------------------------------------------------------- 主入口

    /**
     * 主按钮点击（两态：空闲=开始引导 / 准备态=开始录制 / 录制态=停止）。
     */
    public void onToggle() {
        if (mStage == VP_CHECKING) {
            Log.i(TAG, "[VP] 校对中，忽略点击");
            return;
        }

        // ---------- IDLE → 进入引导 ----------
        if (mStage == VP_IDLE) {
            if (!com.onesay.assistant.v2.wake.WakeService.isServiceRunning()) {
                msg(Color.parseColor("#FF9F0A"), "唤醒服务未运行，麦克风不可用。");
                return;
            }
            com.onesay.assistant.v2.asr.SpeakerVerifier sv =
                    com.onesay.assistant.v2.asr.SpeakerVerifier.get(mAppCtx);
            if (!sv.isAvailable()) {
                msg(Color.parseColor("#FF9F0A"),
                        "声纹引擎不可用：" + sv.getLoadError());
                return;
            }
            mSession++;
            mPhraseIdx = 0;
            mSegs.clear();
            mStage = VP_READY;
            // ★ 会话开始：压制唤醒（引导句本身就是唤醒词）
            com.onesay.assistant.v2.wake.WakeService.setEnrollSession(true);
            btn("● 开始录制", false);
            showPhrasePrompt();
            Log.i(TAG, "[VP] 进入引导式声纹录制（共 " + PHRASES.length + " 句）");
            return;
        }

        // ---------- READY → 开始录制当前句 ----------
        if (mStage == VP_READY) {
            boolean ok = com.onesay.assistant.v2.wake.WakeService.startVoiceprintRecording();
            if (!ok) {
                msg(Color.parseColor("#FF9F0A"), "麦克风未就绪，请稍后再试。");
                return;
            }
            mStage = VP_RECORDING;
            btn("⏹ 停止录音", true);
            mHandler.removeCallbacks(mTimerRunnable);
            mHandler.post(mTimerRunnable);
            return;
        }

        // ---------- RECORDING → 停止并收下这一段（**绝不打回用户**） ----------
        mStage = VP_CHECKING;
        mHandler.removeCallbacks(mTimerRunnable);
        btn("记录中…", false);
        msg(Color.parseColor("#5E5CE6"), "正在记下你这段声音…");
        final byte[] pcm = com.onesay.assistant.v2.wake.WakeService.stopVoiceprintRecording();
        final int mySession = mSession;
        final int idx = mPhraseIdx;
        com.onesay.assistant.v2.util.Threads.start("voiceprintenrollcontroller", new Runnable() {
            @Override
            public void run() {
                final float[] samples = com.onesay.assistant.v2.asr.SpeakerVerifier.pcm16leToFloat(pcm);
                final float sec = samples.length / 16000.0f;

                // 收段：能算出向量就收（这是用户的声音，学了就是收益）；
                // 算不出（< 1.2s / 全静音，提不出有效特征）→ 静默跳过，流程照常前进。
                float[] emb = null;
                if (sec >= 1.2f) {
                    com.onesay.assistant.v2.asr.SpeakerVerifier sv =
                            com.onesay.assistant.v2.asr.SpeakerVerifier.get(mAppCtx);
                    emb = sv.extractFromSamples(samples, 16000);
                }

                // 字比对：r2/fixes 15 明确降级停用（离线识别已下线、恒返回空串，见 TEXT_CHECK_ENABLED）。
                // 不再调用识别；筛选只按声纹自洽（pickConsistentSegs 的 textOutlier 永远不成立）。
                float match = -1f;
                int heardLen = 0;
                advanceAfterSeg(mySession, idx, emb, match, heardLen, sec);
            }
        });
    }

    /**
     * 中止会话（用户点「取消」/ 宿主 onStop）——
     * 丢弃已录数据、复位状态、解除唤醒压制。可重复调用（幂等）。
     */
    public void abort() {
        if (mStage == VP_IDLE) return;
        mHandler.removeCallbacks(mTimerRunnable);
        boolean wasRec = (mStage == VP_RECORDING);
        mStage = VP_IDLE;
        mPhraseIdx = 0;
        mSession++;                 // 让在途的校对/注册回调全部作废
        mSegs.clear();
        com.onesay.assistant.v2.wake.WakeService.abortVoiceprintRecording();
        com.onesay.assistant.v2.wake.WakeService.setEnrollSession(false);
        btn("🎙 录制声纹", false);
        msg(Color.parseColor("#8E8E93"), "录制已取消。想学随时再点「🎙 录制声纹」。");
        Log.i(TAG, "[VP] 会话中止（wasRecording=" + wasRec + "），数据已丢弃");
    }

    // ---------------------------------------------------------------- 内部流程

    /**
     * 收下一段并推进（主线程）。**没有任何"重录"分支** ——
     * 段有效则收下，段无效（太短/太静）则静默跳过，两种都直接进入下一句。
     * 筛选与过滤统一放到 {@link #doEnroll} 完成时由系统自己做。
     */
    private void advanceAfterSeg(final int mySession, final int idx, final float[] emb,
                                 final float match, final int heardLen, final float sec) {
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                if (mySession != mSession) return;   // 会话已中止 / 已重置 → 丢弃
                if (emb != null) {
                    VpSeg s = new VpSeg();
                    s.emb = emb;
                    s.match = match;
                    s.heardLen = heardLen;
                    s.sec = sec;
                    mSegs.add(s);
                    Log.i(TAG, "[VP] 收下第 " + (idx + 1) + " 段（" + fmt1(sec) + "s）"
                            + "，累计 " + mSegs.size() + " 段");
                } else {
                    Log.i(TAG, "[VP] 第 " + (idx + 1) + " 段提不出特征（太短/太静），"
                            + "静默跳过，不打断用户");
                }
                if (idx + 1 < PHRASES.length) {
                    mPhraseIdx = idx + 1;
                    mStage = VP_READY;
                    btn("● 开始录制", false);
                    showPhrasePrompt();
                    return;
                }
                doEnroll(mySession);
            }
        });
    }

    /** 显示当前句的引导文案（全程零门槛：念什么算什么都收，绝不要求重录）。 */
    private void showPhrasePrompt() {
        int n = PHRASES.length;
        int got = mSegs.size();
        String progress = (got > 0) ? "（已录下 " + got + " 句声音）" : "";
        msg(Color.parseColor("#5E5CE6"),
                "第 " + (mPhraseIdx + 1) + "/" + n + " 句" + progress + "：请自然念出\n「"
                        + PHRASES[mPhraseIdx] + "」\n（不用刻意，正常说话就好；" + TEXT_CHECK_NOTE + "）准备好后点「● 开始录制」");
    }

    /**
     * ★ 系统侧自动筛选（**内部逻辑，用户无感**）。
     *
     * <p>用户定档的设计原则：声纹是"系统拟合用户"，不是"用户迎合系统"。
     * 所以筛选只发生在**全部录完之后**，且只做一件事：如果某一段跟其它段明显不是
     * 同一种声音（平均余弦 < 0.45 = 可能混入了旁人说话/电视声），就把它剔掉再用剩下的段建模。
     * （原先还有「字比对显示完全不是引导句内容」一条，r2/fixes 15 起字比对停用，那条恒不成立。）
     * 挑不出异常（或剔除后不足 2 段）就全用 —— **宁可全用，也不让用户重来**。
     */
    private static List<VpSeg> pickConsistentSegs(List<VpSeg> segs) {
        if (segs.size() < 3) return segs;   // 2 段以内无从筛选，全用

        int worst = -1;
        float worstAvg = 2f;
        for (int i = 0; i < segs.size(); i++) {
            float sum = 0;
            int cnt = 0;
            for (int j = 0; j < segs.size(); j++) {
                if (i == j) continue;
                float c = com.onesay.assistant.v2.asr.SpeakerVerifier.cosine(segs.get(i).emb, segs.get(j).emb);
                if (c > -1.5f) { sum += c; cnt++; }
            }
            float avg = (cnt > 0) ? (sum / cnt) : 1f;
            if (avg < worstAvg) {
                worstAvg = avg;
                worst = i;
            }
        }
        if (worst < 0) return segs;

        VpSeg w = segs.get(worst);
        boolean spkOutlier = worstAvg < 0.45f;                       // 跟谁都不像
        boolean textOutlier = (w.heardLen >= 4) && (w.match <= 0.001f); // 完全不是这句内容
        if ((spkOutlier || textOutlier) && (segs.size() - 1 >= 2)) {
            Log.i(TAG, "[VP] 自动筛选: 剔除第 " + (worst + 1) + " 段"
                    + "（平均余弦 " + fmt2(worstAvg) + "，字匹配 " + fmt2(w.match) + "）");
            List<VpSeg> keep = new ArrayList<VpSeg>(segs);
            keep.remove(worst);
            return keep;
        }
        Log.i(TAG, "[VP] 自动筛选: 各段一致性 OK（最低平均余弦 " + fmt2(worstAvg) + "），全部采用");
        return segs;
    }

    /** 全部句录完 → 系统自动筛选 → 整体注册（落盘 + 更新管理器）。 */
    private void doEnroll(final int mySession) {
        mStage = VP_CHECKING;   // 保持"不响应"直到保存完
        final int n = PHRASES.length;
        final List<VpSeg> all = new ArrayList<VpSeg>(mSegs);
        if (all.isEmpty()) {
            // 唯一会"要求重来"的情况：一段有效声音都没录到（物理性失败，非用户念错）
            finishSession(mySession, false, 0, 0,
                    "这次没录到有效的声音。靠近一点，再来一次吧。",
                    "#FF9F0A");
            return;
        }
        msg(Color.parseColor("#5E5CE6"),
                n + " 句已录完，正在整段学习你的声音…");
        com.onesay.assistant.v2.util.Threads.start("voiceprintenrollcontroller", new Runnable() {
            @Override
            public void run() {
                // 系统侧筛选（用户无感）
                final List<VpSeg> use = pickConsistentSegs(all);
                List<float[]> embs = new ArrayList<float[]>();
                for (int i = 0; i < use.size(); i++) {
                    embs.add(use.get(i).emb);
                }
                com.onesay.assistant.v2.asr.SpeakerVerifier sv =
                        com.onesay.assistant.v2.asr.SpeakerVerifier.get(mAppCtx);
                final int segs = sv.enrollFromEmbeddings("owner", embs);
                final int used = use.size();
                final int total = all.size();
                mHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (segs > 0) {
                            String extra = (used < total)
                                    ? "（已自动滤掉 " + (total - used) + " 段不一致的声音）" : "";
                            finishSession(mySession, true, used, total,
                                    "✓ 已学会你的声音（" + used + " 句样本" + extra + "）。"
                                            + TEXT_CHECK_NOTE + "；只存在本机，永不上传。想重录随时再点「🎙 录制声纹」。",
                                    "#30D158");
                        } else {
                            finishSession(mySession, false, used, total,
                                    "声纹保存失败，请稍后再试。", "#FF9F0A");
                        }
                        Log.i(TAG, "[VP] 注册完成: 采用 " + used + "/" + total + " 段, 结果=" + segs);
                    }
                });
            }
        });
    }

    /** 会话收尾（成功/失败统一出口）：复位状态 + 解除唤醒压制 + 通知宿主。 */
    private void finishSession(final int mySession, final boolean enrolled,
                               final int used, final int total,
                               final String message, final String colorHex) {
        if (mySession != mSession) return;   // 已中止 → 不再动 UI
        mStage = VP_IDLE;
        mPhraseIdx = 0;
        mSegs.clear();
        com.onesay.assistant.v2.wake.WakeService.setEnrollSession(false);
        btn("🎙 录制声纹", false);
        msg(Color.parseColor(colorHex), message);
        if (mListener != null) {
            mListener.onVpFinished(enrolled, used, total);
        }
    }

    // ---------------------------------------------------------------- UI 回调小工具

    private void msg(int color, String message) {
        if (mListener != null) {
            mListener.onVpMessage(color, message);
        }
    }

    private void btn(String label, boolean danger) {
        if (mListener != null) {
            mListener.onVpButton(label, danger);
        }
    }

    // ---------- 校对用的小工具（字比对；public 供探针复测同一实现） ----------

    public static String normCn(String s) {
        if (s == null) return "";
        return s.replaceAll("[\\s，。？！、：；“”‘’\"'`,.!?~—…]", "");
    }

    /** 字符级 LCS 匹配率 = LCS(heard, expect) / len(expect)。 */
    public static float matchRatio(String heard, String expect) {
        if (expect.length() == 0) return 1f;
        if (heard.length() == 0) return 0f;
        int n = expect.length();
        int m = heard.length();
        int[][] dp = new int[n + 1][m + 1];
        for (int i = 1; i <= n; i++) {
            for (int j = 1; j <= m; j++) {
                dp[i][j] = (expect.charAt(i - 1) == heard.charAt(j - 1))
                        ? dp[i - 1][j - 1] + 1
                        : Math.max(dp[i - 1][j], dp[i][j - 1]);
            }
        }
        return (float) dp[n][m] / n;
    }

    private static String fmt1(float v) { return String.format("%.1f", v); }

    private static String fmt2(float v) { return String.format("%.2f", v); }
}
