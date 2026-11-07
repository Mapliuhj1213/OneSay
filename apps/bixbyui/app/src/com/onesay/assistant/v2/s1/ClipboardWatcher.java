package com.onesay.assistant.v2.s1;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.ui.FloatingSheetService;
import com.onesay.assistant.v2.ui.FloatingSheetView;
import com.onesay.assistant.v2.ui.S1CardViews;
import com.onesay.assistant.v2.vision.screenref.ScreenAction;
import com.onesay.assistant.v2.vision.screenref.ScreenRefCapabilities;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;

/**
 * [s1] 剪贴板地址芯片的 Android 侧：前台服务里的 {@link ClipboardManager.OnPrimaryClipChangedListener}
 * → {@link ClipChipPlanner} 裁决 → 顶部小卡片（「导航」「打车」/「拨打」…），无人触碰 6 秒自动消失。
 *
 * <h3>系统版本限制（如实）</h3>
 * Android 8/9：前台服务里的监听在后台照常收到回调、能读剪贴板（本机 Note 8 = Android 8.0，满足）。
 * <b>Android 10 起</b>只有默认输入法或前台 App 能读剪贴板，本功能在 10+ 上会静默失效（回调里读到 null），
 * 需改走无障碍/输入法方案 —— 已写入。
 *
 * <h3>执行</h3>
 * 芯片点击 → 挂出面板 → {@code FloatingSheetService.screenRefExecute}：ExecutionGate.evaluate（tainted=false，用户亲手选的动作，
 * 同识屏芯片 D1）→ 需确认则弹卡（拨打 = 黄卡）→ UnifiedExecutor / App 内本地执行。不会绕过门禁。
 */
final class ClipboardWatcher {

    private static final String TAG = "S1";

    private final FloatingSheetService mSvc;
    private final WindowManager mWm;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private ClipboardManager mCm;
    private ClipboardManager.OnPrimaryClipChangedListener mListener;
    private S1CardViews.ClipChipCard mView;
    private List<ClipChipPlanner.Chip> mChips = new ArrayList<ClipChipPlanner.Chip>();
    private String mLastText;
    private long mLastAt;
    /** ★ feat/suggest：交给主动建议的那次裁决（键 = token），供岛上芯片执行 / 岛放不下时退回旧卡片。 */
    private ClipChipPlanner.Decision mSuggestDecision;
    private String mSuggestToken;
    private long mSuggestAt;
    private int mTokenSeq;

    private final Runnable mAutoDismiss = new Runnable() {
        @Override
        public void run() {
            Log.i(TAG, "[CLIP] 芯片 " + (S1Settings.CLIP_CHIP_TTL_MS / 1000) + " 秒无人触碰，自动消失");
            hideChip();
        }
    };

    ClipboardWatcher(FloatingSheetService svc, WindowManager wm) {
        mSvc = svc;
        mWm = wm;
    }

    void start() {
        try {
            mCm = (ClipboardManager) mSvc.getSystemService(Context.CLIPBOARD_SERVICE);
            if (mCm == null) return;
            mListener = new ClipboardManager.OnPrimaryClipChangedListener() {
                @Override
                public void onPrimaryClipChanged() {
                    onClipChanged();
                }
            };
            mCm.addPrimaryClipChangedListener(mListener);
            Log.i(TAG, "[CLIP] 剪贴板监听已注册（开关=" + S1Settings.clipChipEnabled(mSvc) + "）");
        } catch (Throwable t) {
            Log.w(TAG, "[CLIP] 注册剪贴板监听失败: " + t.getMessage());
        }
    }

    void destroy() {
        mMain.removeCallbacksAndMessages(null);
        try {
            if (mCm != null && mListener != null) mCm.removePrimaryClipChangedListener(mListener);
        } catch (Throwable ignored) {
        }
        S1Overlay.remove(mWm, mView);
    }

    private void onClipChanged() {
        if (!S1Settings.clipChipEnabled(mSvc)) return;
        String text = null;
        try {
            ClipData cd = mCm.getPrimaryClip();
            if (cd != null && cd.getItemCount() > 0) {
                CharSequence cs = cd.getItemAt(0).coerceToText(mSvc);
                if (cs != null) text = cs.toString();
            }
        } catch (Throwable t) {
            Log.w(TAG, "[CLIP] 读剪贴板失败（Android 10+ 后台不可读？）: " + t.getMessage());
        }
        handleText(text);
    }

    /** 处理一段新复制的文字（监听回调 / 调试注入共用）。主线程。返回裁决。 */
    ClipChipPlanner.Decision handleText(String text) {
        long now = System.currentTimeMillis();
        boolean own = ClipboardMemory.consumeOwnWrite(text, now);
        ClipChipPlanner.Decision d = ClipChipPlanner.plan(text, own, mLastText, mLastAt, now, TimeZone.getDefault());
        // 日志只记裁决与长度，不记内容（可能是隐私）
        Log.i(TAG, "[CLIP] 剪贴板变化 len=" + (text == null ? 0 : text.length()) + " → " + (d.show ? "SHOW " + d.chips.size() + " 个芯片" : "忽略（" + d.reason + "）"));
        if (own || "重复复制".equals(d.reason)) return d;
        mLastText = text;
        mLastAt = now;
        // 「导航到刚复制的地址」只认最近一次外部复制：新内容没有可用实体/敏感时清空旧记忆
        ClipboardMemory.remember(d.show ? d.entities : null, now);
        // ★ feat/suggest：主动建议在跑且岛能放 → 交给它（链接直达 + 单号 / 地址芯片统一频控）；岛拒绝时 S1Hooks.clipFallback 退回本卡片。
        //   插件关 / 非岛模式 / 面板开着 → 原样走旧卡片（零副作用）。
        if (com.onesay.assistant.v2.suggest.SuggestHost.takeoverClip(mSvc)) {
            String t = text == null ? null : text.trim();
            boolean safe = t != null && t.length() > 0 && t.length() <= ClipChipPlanner.MAX_LEN && ClipChipPlanner.sensitiveReason(t) == null;
            mSuggestToken = String.valueOf(++mTokenSeq);
            mSuggestDecision = d.show ? d : null;
            mSuggestAt = now;
            StringBuilder labels = new StringBuilder();
            if (d.show) for (ClipChipPlanner.Chip c : d.chips) labels.append(labels.length() > 0 ? "\n" : "").append(c.label());
            com.onesay.assistant.v2.suggest.SuggestHost.offerClip(mSvc, safe ? t : null, d.show ? labels.toString() : null, d.title, mSuggestToken,
                    t == null ? null : com.onesay.assistant.plug.suggest.core.scenes.BaseScene.hash(t));
            Log.i(TAG, "[CLIP] 交给主动建议（suggest）");
            return d;
        }
        if (d.show) showChip(d);
        return d;
    }

    /** ★ feat/suggest：岛上剪贴板建议卡的第 index 个芯片被点 → 原执行链路。主线程。token 过期返回 false。 */
    boolean runSuggestChip(String token, int index) {
        ClipChipPlanner.Decision d = mSuggestDecision;
        if (d == null || token == null || !token.equals(mSuggestToken) || index < 0 || index >= d.chips.size()) return false;
        execute(d.chips.get(index));
        return true;
    }

    /** ★ feat/suggest：岛放不下 → 退回旧卡片（token 为 null = 最近一次，10 s 内有效）。主线程。 */
    boolean showLegacyFor(String token) {
        ClipChipPlanner.Decision d = mSuggestDecision;
        if (d == null || !d.show) return false;
        if (token != null && !token.equals(mSuggestToken)) return false;
        if (System.currentTimeMillis() - mSuggestAt > 10000L) return false;
        showChip(d);
        return true;
    }

    private void showChip(ClipChipPlanner.Decision d) {
        if (mView == null) {
            mView = new S1CardViews.ClipChipCard(mSvc);
            mView.setListener(new S1CardViews.ClipChipCard.Listener() {
                @Override
                public void onChip(int index) {
                    if (index < 0 || index >= mChips.size()) return;
                    ClipChipPlanner.Chip c = mChips.get(index);
                    hideChip();
                    execute(c);
                }

                @Override
                public void onClose() {
                    hideChip();
                }

                @Override
                public void onTouched() {
                    mMain.removeCallbacks(mAutoDismiss);   // 用户碰过了：不再自动消失
                }
            });
        }
        mChips = new ArrayList<ClipChipPlanner.Chip>(d.chips);
        List<String> labels = new ArrayList<String>();
        for (ClipChipPlanner.Chip c : d.chips) labels.add(c.label());
        mView.bind(d.title, labels);
        S1Overlay.add(mWm, mView, S1Overlay.smallParams(Gravity.TOP | Gravity.CENTER_HORIZONTAL, 0, S1Overlay.dp(mSvc, 96)));
        mMain.removeCallbacks(mAutoDismiss);
        mMain.postDelayed(mAutoDismiss, S1Settings.CLIP_CHIP_TTL_MS);
    }

    private void hideChip() {
        mMain.removeCallbacks(mAutoDismiss);
        S1Overlay.remove(mWm, mView);
    }

    /** 芯片 → 能力 → 面板的门禁/确认卡/执行链路。 */
    private void execute(ClipChipPlanner.Chip chip) {
        ScreenAction a = S1Capabilities.actionFor(chip);
        // [s2] 习惯默认值：打车/导航芯片改用默认 App（打车默认高德 = 原深链不变）
        if (a != null && (chip.kind == ClipChipPlanner.ChipKind.TAXI || chip.kind == ClipChipPlanner.ChipKind.NAV)) {
            a = com.onesay.assistant.v2.s2.S2Hooks.chipAction(mSvc, chip.kind.name(), chip.entity, a);
        }
        if (a == null) {
            Log.w(TAG, "[CLIP] 芯片无法映射为动作: " + chip.kind);
            return;
        }
        Capability cap = S1Capabilities.localCapability(a.sign);
        if (cap == null) cap = CapabilityRegistry.getInstance().describe(a.sign);
        if (cap == null) cap = com.onesay.assistant.v2.s2.S2Hooks.describe(a.sign);   // [s2] s2.nav.app
        JSONObject args = ScreenRefCapabilities.isScreenSign(a.sign)
                ? ScreenRefCapabilities.normalizeArgs(a.sign, a.args, System.currentTimeMillis(), TimeZone.getDefault())
                : a.args;
        if (cap == null || args == null) {
            Log.w(TAG, "[CLIP] 缺少能力或参数无效: " + a.sign);
            return;
        }
        String query = "剪贴板：" + a.label;
        Log.i(TAG, "[CLIP] 芯片点击 → " + cap.sign + "（经 ExecutionGate）");
        mSvc.s1AttachSheet();
        FloatingSheetView sheet = mSvc.getSheetView();
        if (sheet != null) {
            sheet.showAskReply(query, "正在处理剪贴板里的" + chip.entity.kindLabel() + "…");
            sheet.setDismissLabel("✕ 关闭");
        }
        mSvc.screenRefExecute(cap, args, query);
    }
}
