package com.onesay.assistant.v2.wake;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.SideEffect;

/**
 * ★ r2/screenoff-wake：息屏低功耗听 + 命中先亮屏 + 暂停即真暂停 —— 全部判定集中在这里（纯 Java，无 android 依赖，JVM 单测入口）。
 *
 * <h3>1. 息屏 KWS 能量前置门（{@link #onFrame}）</h3>
 * 亮屏时 {@link #FEED}（与改动前逐帧一致）。息屏时只有「最近 {@link Config#hangMs} 内出现过人声能量」才喂 KWS：
 * <ul>
 *   <li>安静 → {@link #SKIP}：读循环照常 read + 这里一次 RMS（几百次乘加），不跑神经网络；</li>
 *   <li>由不喂转为喂 → {@link #ACTIVATE}：调用方先 reset KWS 流，再把滚动缓冲里最近 {@link Config#prerollMs}
 *       （已含本帧）按 100 ms 一块补喂 —— 唤醒词开头那几百毫秒在能量门判出人声之前就已经过去了，靠预滚补回来；</li>
 *   <li>门开着 → {@link #FEED}。</li>
 * </ul>
 * 亮屏 ↔ 息屏切换不需要单独处理：亮屏后第一帧若此前在 SKIP，同样返回 ACTIVATE（reset + 补喂），流不会把两段拼成假关键词。
 *
 * <p>人声判据刻意比 {@link EnergyGate} 的软门还宽（abs 120 / 底噪 ×1.8 vs 软门 110 / ×1.6 的量级）：
 * 这道门只决定「要不要花 CPU 跑 KWS」，误开只费电，漏开就是喊不醒 —— 宁宽勿严。
 *
 * <h3>2. 唤醒锁（{@link #wantPartialWakeLock}）</h3>
 * PARTIAL_WAKE_LOCK 只在「没暂停 且 息屏」时持有：亮屏本身就让 CPU 醒着；暂停时麦克风也停了，什么都不用听。
 *
 * <h3>3. 暂停即真暂停（{@link #micShouldPause}）</h3>
 * 暂停且不在声纹注册 / 捕获窗里 → 读循环 stop() AudioRecord（audioserver 的 AudioIn 锁随之释放）并放掉唤醒锁。
 *
 * <h3>4. 命中先亮屏 / 锁屏（{@link #needScreenWake}、{@link #needKeyguardHost}、{@link #unlockDenyReason}）</h3>
 * 受理唤醒时息屏 → 先点亮；锁屏 → 起透明 show-when-locked Activity 让面板浮在锁屏上方语音回答；
 * 但锁着的时候，凡有副作用 / 读隐私的能力（{@link SideEffect} 不是 NONE）一律拒绝并请用户先解锁。
 */
public final class ScreenOffPolicy {

    /** 正常喂 KWS。 */
    public static final int FEED = 0;
    /** 不喂（息屏 + 安静）。 */
    public static final int SKIP = 1;
    /** 由不喂转为喂：先 reset 流，再补喂预滚（预滚已含本帧，本帧不要再单独喂）。 */
    public static final int ACTIVATE = 2;

    /** 调参（WakeTuning screenoff_*；默认值见常量）。 */
    public static final class Config {
        public static final int DEF_ENABLED = 1;
        public static final int DEF_ABS_MIN = 120;
        public static final int DEF_FLOOR_PCT = 180;
        public static final int DEF_HANG_MS = 2000;
        public static final int DEF_PREROLL_MS = 1500;
        public static final int DEF_SCREEN_WAKE_MS = 6000;
        public static final int DEF_KEYGUARD_HOST = 1;
        public static final int DEF_HOST_MAX_MS = 60000;

        public final boolean enabled;
        /** 人声判据：RMS 绝对下限（16-bit PCM）。 */
        public final float absMin;
        /** 人声判据：RMS ≥ 底噪 × floorRatio。 */
        public final float floorRatio;
        /** 最后一帧人声之后门再开多久（唤醒词说完后 KWS 还要几帧尾静音才出结果）。 */
        public final int hangMs;
        /** 开门时补喂多长的预滚（受滚动缓冲容量约束，调用方再夹一次）。 */
        public final int prerollMs;
        /** 亮屏锁的超时（自动释放）。 */
        public final int screenWakeMs;
        /** 锁屏时是否起透明托底 Activity。 */
        public final boolean keyguardHost;
        /** 托底 Activity 最长停留（兜底超时）。 */
        public final int hostMaxMs;

        public Config(boolean enabled, int absMin, int floorPct, int hangMs, int prerollMs,
                      int screenWakeMs, boolean keyguardHost, int hostMaxMs) {
            this.enabled = enabled;
            this.absMin = clamp(absMin, 20, 4000);
            this.floorRatio = clamp(floorPct, 110, 1000) / 100f;
            this.hangMs = clamp(hangMs, 300, 10000);
            this.prerollMs = clamp(prerollMs, 0, 2500);
            this.screenWakeMs = clamp(screenWakeMs, 1000, 15000);
            this.keyguardHost = keyguardHost;
            this.hostMaxMs = clamp(hostMaxMs, 5000, 180000);
        }

        public static Config defaults() {
            return new Config(true, DEF_ABS_MIN, DEF_FLOOR_PCT, DEF_HANG_MS, DEF_PREROLL_MS,
                    DEF_SCREEN_WAKE_MS, true, DEF_HOST_MAX_MS);
        }

        @Override public String toString() {
            return "enabled=" + enabled + " abs=" + (int) absMin + " floor×" + floorRatio + " hang=" + hangMs
                    + "ms preroll=" + prerollMs + "ms screenWake=" + screenWakeMs + "ms host=" + keyguardHost
                    + "/" + hostMaxMs + "ms";
        }
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 底噪跟踪：安静帧慢跟（上升慢、下降快）；人声帧只极慢地抬（稳态风扇 / 空调噪声约 1 分钟后不再开门，一句话几乎不动它）。 */
    static final float FLOOR_RISE = 0.02f;
    static final float FLOOR_VOICE_RISE = 0.001f;
    static final float FLOOR_FALL = 0.3f;
    static final float FLOOR_MIN = 1f;

    private Config mCfg;
    private boolean mScreenOn = true;
    private boolean mKeyguardLocked = false;
    private boolean mFeeding = true;
    private long mLastVoiceAt = Long.MIN_VALUE / 2;
    private float mFloor = -1f;
    private long mSkipped, mFed, mActivations, mVoiceFrames;

    public ScreenOffPolicy(Config cfg) {
        mCfg = cfg != null ? cfg : Config.defaults();
    }

    public synchronized void setConfig(Config cfg) {
        if (cfg != null) mCfg = cfg;
    }

    public synchronized Config config() {
        return mCfg;
    }

    // ==================== 屏幕 / 锁屏状态 ====================

    /** SCREEN_ON / SCREEN_OFF。息屏时锁屏状态由调用方用 KeyguardManager 补报（Samsung 有「息屏后 N 秒才锁」的设置）。 */
    public synchronized void setScreenOn(boolean on) {
        mScreenOn = on;
    }

    /** KeyguardManager.isKeyguardLocked() 的最新值；USER_PRESENT = false。 */
    public synchronized void setKeyguardLocked(boolean locked) {
        mKeyguardLocked = locked;
    }

    public synchronized boolean screenOn() { return mScreenOn; }

    public synchronized boolean keyguardLocked() { return mKeyguardLocked; }

    // ==================== 1. KWS 能量前置门 ====================

    /** 16-bit PCM 一块的 RMS。 */
    public static float rms(short[] buf, int n) {
        if (buf == null || n <= 0) return 0f;
        if (n > buf.length) n = buf.length;
        double acc = 0;
        for (int i = 0; i < n; i++) {
            int v = buf[i];
            acc += (double) v * v;
        }
        return (float) Math.sqrt(acc / n);
    }

    /** 读循环每帧调用一次（Acoustic Shield / 声纹录制 之后，KWS 暂停判定之后）。 */
    public int onFrame(long now, short[] buf, int n) {
        return decide(now, rms(buf, n));
    }

    /** 同 {@link #onFrame}，直接给 RMS（单测入口）。 */
    public synchronized int decide(long now, float rms) {
        if (mFloor < 0) mFloor = Math.max(FLOOR_MIN, Math.min(rms, mCfg.absMin));   // 第一帧：底噪起点不高于 abs 门
        boolean voice = isVoiceLocked(rms);
        if (voice) {
            mLastVoiceAt = now;
            mVoiceFrames++;
            if (rms > mFloor) mFloor += (rms - mFloor) * FLOOR_VOICE_RISE;
        } else {
            updateFloorLocked(rms);
        }
        boolean want = mScreenOn || !mCfg.enabled || (now - mLastVoiceAt) <= mCfg.hangMs;
        boolean was = mFeeding;
        mFeeding = want;
        if (!want) {
            mSkipped++;
            return SKIP;
        }
        mFed++;
        if (!was) {
            mActivations++;
            return ACTIVATE;
        }
        return FEED;
    }

    /** 当前人声门限（日志 / 单测用）。 */
    public synchronized float voiceThreshold() {
        float byFloor = mFloor > 0 ? mFloor * mCfg.floorRatio : 0f;
        return Math.max(mCfg.absMin, byFloor);
    }

    private boolean isVoiceLocked(float rms) {
        float byFloor = mFloor > 0 ? mFloor * mCfg.floorRatio : 0f;
        return rms >= mCfg.absMin && rms >= byFloor;
    }

    private void updateFloorLocked(float rms) {
        if (mFloor < 0) {
            mFloor = Math.max(FLOOR_MIN, rms);
            return;
        }
        float a = rms < mFloor ? FLOOR_FALL : FLOOR_RISE;
        mFloor += (rms - mFloor) * a;
        if (mFloor < FLOOR_MIN) mFloor = FLOOR_MIN;
    }

    /** 下一帧若要喂，是否需要先 reset（例如外部暂停 KWS 之后由调用方显式标记为「不在喂」）。 */
    public synchronized void markNotFeeding() {
        mFeeding = false;
    }

    public synchronized boolean feeding() { return mFeeding; }

    /** 预滚样本数：min(配置, 滚动缓冲容量)。 */
    public synchronized int prerollSamples(int sampleRate, int bufferCapacity) {
        long want = (long) mCfg.prerollMs * sampleRate / 1000L;
        if (want > bufferCapacity) want = bufferCapacity;
        return (int) Math.max(0, want);
    }

    public synchronized String stats() {
        long total = mSkipped + mFed;
        int pct = total > 0 ? (int) (mSkipped * 100 / total) : 0;
        return "screenOn=" + mScreenOn + " locked=" + mKeyguardLocked + " feeding=" + mFeeding
                + " skipped=" + mSkipped + "(" + pct + "%) fed=" + mFed + " activations=" + mActivations
                + " voiceFrames=" + mVoiceFrames + " floor=" + Math.round(mFloor) + " thr=" + Math.round(voiceThreshold());
    }

    public synchronized long skippedFrames() { return mSkipped; }

    public synchronized long activations() { return mActivations; }

    // ==================== 2/3. 唤醒锁、暂停 ====================

    /** PARTIAL_WAKE_LOCK 只在「没暂停 且 息屏」时需要。 */
    public static boolean wantPartialWakeLock(boolean paused, boolean screenOn) {
        return !paused && !screenOn;
    }

    /**
     * ★ r2/sysux R1：再加一条「麦克风真的在录」（{@link MicRecordingState}）。录音初始化失败、没在录、暂停时都不持有；
     * 录音恢复成功后再获取。
     */
    public static boolean wantPartialWakeLock(boolean paused, boolean screenOn, boolean micRecording) {
        return micRecording && wantPartialWakeLock(paused, screenOn);
    }

    /**
     * 读循环是否应停掉 AudioRecord。暂停时的例外：声纹注册（会话 / 录制）要麦克风；捕获窗开着（暂停前刚受理的那一句）收完再停。
     */
    public static boolean micShouldPause(boolean paused, boolean vpRecording, boolean enrollSession, boolean captureOpen) {
        return paused && !vpRecording && !enrollSession && !captureOpen;
    }

    /**
     * ★ ui-K2：相机对话要自己开麦（VOICE_COMMUNICATION + AEC，liveOwnMic）→ 本服务停录让出麦克风（声纹注册会话例外）；
     * 否则同 {@link #micShouldPause(boolean, boolean, boolean, boolean)}。
     */
    public static boolean micShouldPause(boolean paused, boolean vpRecording, boolean enrollSession, boolean captureOpen, boolean liveOwnMic) {
        if (liveOwnMic && !enrollSession) return true;
        return micShouldPause(paused, vpRecording, enrollSession, captureOpen);
    }

    // ==================== 4. 命中先亮屏 / 锁屏 ====================

    /** 受理唤醒时屏幕灭着 → 先点亮再出面板。 */
    public static boolean needScreenWake(boolean interactive) {
        return !interactive;
    }

    /** 锁着 → 面板要浮到锁屏上方，需要透明 show-when-locked Activity 让锁屏处于「被遮挡」态。 */
    public static boolean needKeyguardHost(boolean keyguardLocked, Config cfg) {
        return keyguardLocked && (cfg == null || cfg.keyguardHost);
    }

    /** 锁屏时是否要求先解锁：任何有副作用或读隐私的能力（SideEffect ≠ NONE）。 */
    public static boolean requiresUnlock(SideEffect se) {
        return se != null && se != SideEffect.NONE;
    }

    public static final String UNLOCK_REASON = "手机还锁着：这一步会读隐私或改动手机（发消息、下单、改设置、读屏等），请先解锁再说一次";

    /** 锁屏探针（WakeService 注入 KeyguardManager；JVM 默认 null = 视为未锁，行为与改动前一致）。 */
    public interface KeyguardProbe {
        boolean locked();
    }

    private static volatile KeyguardProbe sProbe;

    public static void setKeyguardProbe(KeyguardProbe p) {
        sProbe = p;
    }

    public static boolean deviceLocked() {
        KeyguardProbe p = sProbe;
        if (p == null) return false;
        try {
            return p.locked();
        } catch (Throwable t) {
            return false;
        }
    }

    /** ExecutionGate 入口：锁着且该能力需要解锁 → 拒绝理由；否则 null。 */
    public static String unlockDenyReason(Capability cap) {
        if (cap == null) return null;
        if (!requiresUnlock(cap.sideEffect)) return null;
        // 锁屏下「7点叫我 / 30分钟后提醒我」原被这里按 DEVICE_CONTROL 拒绝（排在 ToolGate 之前，
        //   ToolGate.isBenignCreate 根本轮不到）。新建闹钟 / 计时器 = 本机、可撤销、不读隐私、不外发 → 锁屏也放行（与三星 / 谷歌助手一致）；
        //   删除闹钟、改设置、读屏、发消息等照旧要先解锁。
        if (com.onesay.assistant.v2.executor.ToolGate.isBenignCreate(cap)) return null;
        return deviceLocked() ? UNLOCK_REASON : null;
    }
}
