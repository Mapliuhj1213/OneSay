package com.onesay.assistant.v2.ui.console;

import android.widget.LinearLayout;

import com.onesay.assistant.v2.kernel.ConsoleHost;
import com.onesay.assistant.v2.ui.TtsSpeaker;
import com.onesay.assistant.v2.ui.VoiceprintEnrollController;
import com.onesay.assistant.v2.wake.WakeTuning;

/**
 * [控制台整理第二步 2026-11-05] 原「语音」页拆成两页（同一个类、两种模式，开关和 prefs 键都不变）：
 * 「语音唤醒」= 声纹 · 唤醒手感 · 唤醒提示音；「朗读」= 回应（语音问就语音答 / 朗读完整回答 / 打字问也读出来 / 回答后接着听 / 显示正在听）。
 * 「思考模式」搬到「模型」页（{@link AdvancedPage}），行为不变（helper 仍在本类）。
 *
 * <p>[console R12] 语音页：声纹（录制流程照旧，只是搬了位置）→ 回应（语音回复 / 显示「正在听」）→ 唤醒手感（短提示音 / 自适应断句）。
 * 旧控制台「体验实验开关（需真机验证）」的 4 个开关都在这里，prefs 键不变。
 */
final class VoicePage extends Console.BuiltPage {

    private ConsoleKit.RowImpl mVpRow;
    private ConsoleKit.RowImpl mVpCancel;

    static final int MODE_WAKE = 0, MODE_READ = 1, MODE_ALL = 2;   // ★ console-align：MODE_ALL =「设置 › 语音」一页（唤醒 + 朗读）
    final int mode;

    /** 语音唤醒（声纹入口、提醒条「去录制」、状态页声纹行都进这一页）。 */
    VoicePage(Console c) {
        this(c, MODE_WAKE);
    }

    VoicePage(Console c, int mode) {
        super(c, mode == MODE_READ ? "朗读" : mode == MODE_ALL ? "语音" : "语音唤醒");
        this.mode = mode;
    }

    /** ★ console-align：「设置 › 语音」一页（唤醒 + 朗读）。 */
    static VoicePage all(Console c) {
        return new VoicePage(c, MODE_ALL);
    }

    /** 朗读页。 */
    static VoicePage read(Console c) {
        return new VoicePage(c, MODE_READ);
    }

    @Override void build(LinearLayout col) {
        final ConsoleKit k = c.kit;
        if (mode == MODE_READ) {
            buildRead(col);
            return;
        }
        final VoiceprintEnrollController vp = c.voiceprint();

        LinearLayout gVp = k.group(col, "声纹");
        mVpRow = k.actionRow(gVp, "声纹", "正在读取…", "录制", new Runnable() {
            @Override public void run() {
                vp.onToggle();
                c.syncScreenOn();
            }
        });
        mVpCancel = k.actionRow(gVp, "取消录制", "离开本页也会自动取消", "取消", new Runnable() {
            @Override public void run() {
                vp.abort();
                c.syncScreenOn();
            }
        });
        mVpCancel.button.setTextColor(k.t.danger);
        mVpCancel.button.setBackground(k.ripple(k.round(k.t.dangerContainer, 20)));
        showCancel(vp.isActive());
        vp.setListener(new VoiceprintEnrollController.Listener() {
            @Override public void onVpMessage(int color, String message) {
                mVpRow.setSub(message);
                mVpRow.setSubTone(toneOf(color));
            }

            @Override public void onVpButton(String label, boolean danger) {
                mVpRow.button.setText(clean(label));
                mVpRow.button.setTextColor(danger ? k.t.danger : k.t.accent);
                mVpRow.button.setBackground(k.ripple(k.round(danger ? k.t.dangerContainer : k.t.accentContainer, 20)));
                showCancel(vp.isActive());
            }

            @Override public void onVpFinished(boolean enrolled, int used, int total) {
                c.syncScreenOn();
            }
        });
        if (!vp.isActive()) refreshVp();

        final WakeTuning tuning = WakeTuning.get(c.act);
        LinearLayout gFeel = k.group(col, "唤醒手感");
        k.switchRow(gFeel, "按下即听", "短提示音 + 边缘光，唤醒那一刻就开始收音；关掉 = 原版「我在」后再说", tuning.shortChime(), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                tuning.setFlag(WakeTuning.K_SHORT_CHIME, on);
            }
        });
        // ★ r2/sysux：「我在」长应答保留为可选项 —— 只在息屏 + 语音唤醒时说，边说边听
        k.switchRow(gFeel, "息屏唤醒时说「我在」", "只在屏幕关着、喊唤醒词时说；说的同时已经在听", tuning.wakeAckVoice(), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                tuning.setFlag(WakeTuning.K_WAKE_ACK_VOICE, on);
            }
        });
        // ★ D1（lane-d）：按键唤醒时提示音期间就开始收音（只在按下键之后，不会往前录）
        k.switchRow(gFeel, "抢话不丢字", "按键唤醒时提示音一响就开始收音（只录按键之后的声音）", tuning.keyPreRoll(), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                tuning.setFlag(WakeTuning.K_KEY_PREROLL, on);
            }
        });
        k.switchRow(gFeel, "自适应断句", "短句停 0.7 秒、长句停 0.9 秒就算说完", tuning.adaptiveEndpoint(), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                tuning.setFlag(WakeTuning.K_ADAPTIVE_EP, on);
            }
        });
        // 唤醒提示音三选一（系统提示音 / 方案 A / 旧版）；选完试听一次。系统提示音从手机自带通知音里挑，运行时解码，不可用自动退回方案 A
        LinearLayout gCue = k.group(col, "唤醒提示音");
        final ConsoleKit.RowImpl cueRow = k.actionRow(gCue, "唤醒提示音", cueSub(com.onesay.assistant.v2.wake.WakeCue.pref(), com.onesay.assistant.v2.wake.WakeCue.effective()),
                cueLabel(com.onesay.assistant.v2.wake.WakeCue.pref()), null);
        cueRow.button.setOnClickListener(new android.view.View.OnClickListener() {
            @Override public void onClick(android.view.View v) {
                final String[] keys = {"system", "a", "legacy"};
                final String[] names = {"系统提示音（自选）", "方案 A", "旧版"};
                int cur = java.util.Arrays.asList(keys).indexOf(com.onesay.assistant.v2.wake.WakeCue.key(com.onesay.assistant.v2.wake.WakeCue.pref()));
                k.dialog().setTitle("唤醒提示音").setSingleChoiceItems(names, Math.max(0, cur), new android.content.DialogInterface.OnClickListener() {
                    @Override public void onClick(android.content.DialogInterface d, final int which) {
                        d.dismiss();
                        final android.app.Activity act = c.act;
                        if (which == 0) { pickSystemCue(act, cueRow); return; }
                        com.onesay.assistant.v2.util.Threads.start("wakecue-ui", new Runnable() {
                            @Override public void run() {
                                com.onesay.assistant.v2.wake.WakeCueLoader.set(act, keys[which]);
                                previewCue();
                                act.runOnUiThread(new Runnable() {
                                    @Override public void run() {
                                        cueRow.button.setText(cueLabel(com.onesay.assistant.v2.wake.WakeCue.pref()));
                                        cueRow.setSub(cueSub(com.onesay.assistant.v2.wake.WakeCue.pref(), com.onesay.assistant.v2.wake.WakeCue.effective()));
                                    }
                                });
                            }
                        });
                    }
                }).setNegativeButton("取消", null).show();
            }
        });
        k.footer(col, "声纹只存在本机，永不上传。录制时跟着念 4 句话，约 1 分钟；录制期间唤醒会暂停。");
        if (mode == MODE_ALL) buildRead(col);   // ★ console-align：首页「语音」= 唤醒 + 朗读一页（电脑端「语音」同名）
    }

    /** 朗读页：原「语音 › 回应」五个开关原样搬来。 */
    private void buildRead(LinearLayout col) {
        final ConsoleKit k = c.kit;
        final WakeTuning tuning = WakeTuning.get(c.act);
        LinearLayout gReply = k.group(col, "朗读");   // ★ console-align：组名「回应」→「朗读」
        // ★ r2/sysux P1-3：语音问就语音答（默认开）；原「语音回复」开关改管文字发起的回合
        k.switchRow(gReply, "语音问就语音答", "用嘴问的问题，用一句短提示回应（答案在卡片上）", TtsSpeaker.isVoiceTurnReplyEnabled(c.act), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                TtsSpeaker.setVoiceTurnReplyEnabled(c.act, on);
            }
        });
        // ★ r2/voice-templates：默认只说「好了，看屏幕」这类短句；开了才把答案整句读出来（云端音色）
        k.switchRow(gReply, "朗读完整回答", "关：只说短提示，答案看卡片；开：把答案读出来", TtsSpeaker.isFullAnswerEnabled(c.act), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                TtsSpeaker.setFullAnswerEnabled(c.act, on);
            }
        });
        k.switchRow(gReply, "打字问也读出来", "文字输入的问题也用语音回复", TtsSpeaker.isEnabled(c.act), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                TtsSpeaker.setEnabled(c.act, on);
            }
        });
        k.switchRow(gReply, "回答后接着听", "读完自动再听几秒，可以直接追问；说话也能打断播报", tuning.followUp(), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                tuning.setFlag(WakeTuning.K_FOLLOWUP, on);
            }
        });
        k.switchRow(gReply, "显示「正在听 / 识别中」", "面板上实时显示收音状态", tuning.liveState(), new ConsoleHost.Toggle() {
            @Override public void onToggle(boolean on) {
                tuning.setFlag(WakeTuning.K_LIVE_STATE, on);
            }
        });

        k.footer(col, "默认只说「好了，看屏幕」这类短提示，答案在卡片上；开了「朗读完整回答」才把答案读出来。");
    }

    /** 从手机自带通知音里选一个当唤醒提示音（列表在后台读）。 */
    private void pickSystemCue(final android.app.Activity act, final ConsoleKit.RowImpl cueRow) {
        com.onesay.assistant.v2.util.Threads.start("wakecue-list", new Runnable() {
            @Override public void run() {
                final java.util.List<String[]> l = com.onesay.assistant.v2.wake.WakeCueLoader.listSystemSounds(act);
                act.runOnUiThread(new Runnable() {
                    @Override public void run() {
                        final String[] titles = new String[l.size() + 1];
                        titles[0] = "系统默认通知音";
                        for (int i = 0; i < l.size(); i++) titles[i + 1] = l.get(i)[0];
                        c.kit.dialog().setTitle("选一个系统提示音").setItems(titles, new android.content.DialogInterface.OnClickListener() {
                            @Override public void onClick(android.content.DialogInterface d, final int w) {
                                com.onesay.assistant.v2.util.Threads.start("wakecue-ui", new Runnable() {
                                    @Override public void run() {
                                        com.onesay.assistant.v2.wake.WakeCueLoader.setUri(act, w == 0 ? "" : l.get(w - 1)[1]);
                                        previewCue();
                                        act.runOnUiThread(new Runnable() {
                                            @Override public void run() {
                                                cueRow.button.setText(cueLabel(com.onesay.assistant.v2.wake.WakeCue.pref()));
                                                cueRow.setSub(cueSub(com.onesay.assistant.v2.wake.WakeCue.pref(), com.onesay.assistant.v2.wake.WakeCue.effective()));
                                            }
                                        });
                                    }
                                });
                            }
                        }).setNegativeButton("取消", null).show();
                    }
                });
            }
        });
    }

    /** ★ int/1102：唤醒提示音按钮文字。 */
    static String cueLabel(com.onesay.assistant.v2.wake.WakeCue.Source s) {
        return s == com.onesay.assistant.v2.wake.WakeCue.Source.A ? "方案 A" : s == com.onesay.assistant.v2.wake.WakeCue.Source.LEGACY ? "旧版" : "系统提示音";
    }

    /** ★ int/1102：唤醒提示音副标题（选了系统提示音但不可用时说明退回）。 */
    static String cueSub(com.onesay.assistant.v2.wake.WakeCue.Source pref, com.onesay.assistant.v2.wake.WakeCue.Source eff) {
        if (pref == com.onesay.assistant.v2.wake.WakeCue.Source.SYSTEM && eff != com.onesay.assistant.v2.wake.WakeCue.Source.SYSTEM) return "系统提示音不可用，正在用方案 A";
        if (pref == com.onesay.assistant.v2.wake.WakeCue.Source.A) return "两声上扬的短提示（约 0.24 秒）";
        if (pref == com.onesay.assistant.v2.wake.WakeCue.Source.LEGACY) return "早期版本的提示音";
        return "手机自带的通知音（自选）";
    }

    /** 选完试听一次（助手提示音通道，和真实唤醒同一路）。 */
    static void previewCue() {
        try {
            short[] pcm = com.onesay.assistant.v2.wake.WakeCue.pcm();
            if (pcm == null || pcm.length == 0) return;
            android.media.AudioTrack t = new android.media.AudioTrack.Builder()
                    .setAudioAttributes(new android.media.AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
                    .setAudioFormat(new android.media.AudioFormat.Builder().setSampleRate(com.onesay.assistant.v2.wake.Earcon.SAMPLE_RATE)
                            .setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                    .setTransferMode(android.media.AudioTrack.MODE_STATIC).setBufferSizeInBytes(pcm.length * 2).build();
            t.write(pcm, 0, pcm.length);
            t.play();
            Thread.sleep(Math.min(1500, com.onesay.assistant.v2.wake.Earcon.durationMs(pcm, com.onesay.assistant.v2.wake.Earcon.SAMPLE_RATE) + 200));
            t.release();
        } catch (Throwable ignored) {
        }
    }

    /** ★ int/1102：思考模式副标题。 */
    static String thinkSub(com.onesay.assistant.v2.net.ThinkingMode.Level l) {
        switch (l) {
            case AGENT: return "多步任务先想再做，问答照旧快答；思考过程不显示、不朗读";
            case ALL: return "问答和多步任务都先想再答，首字会慢几秒；思考过程不显示、不朗读";
            default: return "关：回答最快（默认）";
        }
    }

    private void showCancel(boolean active) {
        if (mVpCancel == null) return;
        mVpCancel.root.setVisibility(active ? android.view.View.VISIBLE : android.view.View.GONE);
        LinearLayout g = (LinearLayout) mVpCancel.root.getParent();
        int i = g.indexOfChild(mVpCancel.root);
        if (i > 0) g.getChildAt(i - 1).setVisibility(active ? android.view.View.VISIBLE : android.view.View.GONE);
    }

    /** 声纹按钮文案去掉 emoji / 符号（「🎙 录制声纹」→「录制声纹」）。 */
    static String clean(String label) {
        if (label == null) return "";
        return label.replaceAll("^[^\\p{L}\\p{N}]+", "").trim();
    }

    private int toneOf(int color) {
        switch (color) {
            case 0xFFFF453A: return ConsoleHost.TONE_BAD;
            case 0xFFFF9F0A: return ConsoleHost.TONE_WARN;
            case 0xFF30D158: return ConsoleHost.TONE_OK;
            default: return ConsoleHost.TONE_NORMAL;
        }
    }

    private void refreshVp() {
        com.onesay.assistant.v2.util.Threads.start("console-vp", new Runnable() {
            @Override public void run() {
                final com.onesay.assistant.v2.asr.SpeakerVerifier sv = com.onesay.assistant.v2.asr.SpeakerVerifier.get(c.act);
                final boolean avail = sv.isAvailable();
                final String err = avail ? "" : sv.getLoadError();
                final boolean has = avail && sv.hasName("owner");
                c.ui(new Runnable() {
                    @Override public void run() {
                        if (mVpRow == null || c.voiceprint().isActive()) return;
                        if (!avail) {
                            mVpRow.setSub("声纹引擎不可用：" + err);
                            mVpRow.setSubTone(ConsoleHost.TONE_WARN);
                        } else if (has) {
                            mVpRow.setSub("已注册 · 想重录随时点「重录」");
                            mVpRow.setSubTone(ConsoleHost.TONE_OK);
                            mVpRow.button.setText("重录");
                        } else {
                            mVpRow.setSub("还没注册 · 跟着念 4 句话就行");
                            mVpRow.setSubTone(ConsoleHost.TONE_NORMAL);
                        }
                    }
                });
            }
        });
    }

    @Override public void onShown() {
        if (mode == MODE_WAKE && c.voiceprint().isActive()) return;   // 录制中不重画（别把引导文案踩掉）
        super.onShown();
    }

    @Override public void onRemoved() {
        if (mode != MODE_WAKE) return;
        VoiceprintEnrollController vp = c.voiceprint();
        if (vp.isActive()) {
            vp.abort();
            c.syncScreenOn();
        }
    }
}
