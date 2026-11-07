package com.onesay.assistant.v2.tts;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * ★ 综合包（cloud-tts × sysux）：云端播报 → sysux 播报进度回调 / 焦点归属。
 * 闸门逻辑纯 Java 单测；Android 壳（PcmStreamPlayer / TtsSpeaker）用源码扫描守接线。
 */
public class CloudTtsSysuxTest {

    static final class Rec implements SpeechProgressGate.Out {
        final List<String> ev = new ArrayList<String>();
        @Override public void onStart() { ev.add("start"); }
        @Override public void onDone(boolean interrupted) { ev.add("done:" + interrupted); }
        @Override public void onSilent(String why) { ev.add("silent"); }
    }

    @Test public void normalPlaybackStartsThenDoneFalse() {
        Rec r = new Rec();
        SpeechProgressGate g = new SpeechProgressGate(r);
        g.audible();
        g.audible();   // 重复不再报
        g.finished(false, null);
        g.finished(false, null);
        assertEquals("[start, done:false]", r.ev.toString());
    }

    @Test public void stopDuringPlaybackIsInterrupted() {
        Rec r = new Rec();
        SpeechProgressGate g = new SpeechProgressGate(r);
        g.audible();
        g.finished(true, null);
        assertEquals("[start, done:true]", r.ev.toString());
    }

    @Test public void cancelIsSynchronousAndSwallowsLateFinish() {
        Rec r = new Rec();
        SpeechProgressGate g = new SpeechProgressGate(r);
        g.audible();
        g.cancel();
        assertEquals("[start, done:true]", r.ev.toString());
        g.finished(false, null);   // 播放线程迟到收尾：不能再报（否则会关掉下一句的打断检测）
        g.audible();
        assertEquals("[start, done:true]", r.ev.toString());
    }

    @Test public void cancelBeforeAudibleReportsNothing() {
        Rec r = new Rec();
        SpeechProgressGate g = new SpeechProgressGate(r);
        g.cancel();
        g.audible();
        assertTrue(r.ev.isEmpty());
    }

    @Test public void failureWithoutSoundReportsSilentNotWatchdog() {
        Rec r = new Rec();
        SpeechProgressGate g = new SpeechProgressGate(r);
        g.finished(false, "AudioTrack 初始化失败");
        assertEquals("[silent]", r.ev.toString());
    }

    @Test public void focusOwnership() {
        assertFalse("有 sysux 状态机 → 播放器不抢焦点", SpeechProgressGate.playerOwnsFocus(true));
        assertTrue("没有状态机 → 播放器自己 duck", SpeechProgressGate.playerOwnsFocus(false));
    }

    // ------------------------------------------------------------------ 接线守卫（源码扫描）

    static String src(String rel) {
        try {
            java.io.File f = new java.io.File(System.getProperty("bixby.root", "."), rel);
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), "UTF-8");
        } catch (java.io.IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Test public void playerCallsAudibleBeforePlayAndFocusIsConditional() {
        String p = src("app/src/com/onesay/assistant/v2/tts/PcmStreamPlayer.java");
        int focus = p.indexOf("if (mSelfFocus) requestFocus();");
        int aud = p.indexOf("mListener.onAudible()");
        int play = p.indexOf("t.play();");
        assertTrue("焦点申请受 selfFocus 控制", focus > 0);
        assertTrue("onAudible 在 play 之前", aud > 0 && aud < play);
        assertTrue("收尾回调 onFinished", p.contains("mListener.onFinished(mStopped, mErr)"));
    }

    @Test public void ttsSpeakerWiresGate() {
        String t = src("app/src/com/onesay/assistant/v2/ui/TtsSpeaker.java");
        assertTrue(t.contains("SpeechProgressGate.playerOwnsFocus(sListener != null)"));
        assertTrue(t.contains("@Override public void onStart() { fireStart(); }"));
        assertTrue(t.contains("@Override public void onDone(boolean interrupted) { fireDone(interrupted); }"));
        int c = t.indexOf("private void cancelCloudLocked()");
        int e = t.indexOf("}", t.indexOf("if (cu != null) cu.cancel();", c));
        assertTrue("cancelCloudLocked 同步关闸门", t.substring(c, e).contains("g.cancel()"));
        assertFalse("不再用无监听的旧构造", t.contains("new com.onesay.assistant.v2.tts.PcmStreamPlayer(mApp, null)"));
    }
}
