package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

/**
 * [r2/uxcompare] 系统控制执行后的即时反馈：报调后的结果值。
 * 对标：小爱「亮度调到 60% 了」「音量已经最大了」、小艺「已为你调高亮度」+ 滑条卡、Siri 调完给当前值。
 */
public class ControlFeedbackTest {

    @Test public void brightnessReportsPercent() {
        assertEquals("亮度已调到 47%", ControlFeedback.refine("settings.brightness.set", "120", ""));
        assertEquals("亮度调高到 63%", ControlFeedback.refine("settings.brightness.up", "160", "160"));
        assertEquals("亮度调低到 16%", ControlFeedback.refine("settings.brightness.down", "", "40"));
        assertEquals("亮度已经最高了（100%）", ControlFeedback.refine("settings.brightness.up", "255", ""));
        assertEquals("亮度已经最低了", ControlFeedback.refine("settings.brightness.down", "1", ""));
        assertEquals("亮度已调到 100%", ControlFeedback.refine("settings.brightness.set", "255", ""));
    }

    @Test public void volumeReportsLevelAndLimits() {
        assertEquals("媒体音量调大到 9（共 15 格）",
                ControlFeedback.refine("volume.media.up", "[v] stream 3\nvolume is 9 in range [0..15]", "9"));
        assertEquals("媒体音量调小到 3（共 15 格）",
                ControlFeedback.refine("volume.media.down", "volume is 3 in range [0..15]", "3"));
        assertEquals("媒体音量已经最大了", ControlFeedback.refine("volume.media.up", "volume is 15 in range [0..15]", ""));
        assertEquals("媒体音量已静音", ControlFeedback.refine("volume.media.down", "volume is 0 in range [0..15]", ""));
        assertEquals("铃声音量已调到 5（共 7 格）", ControlFeedback.refine("volume.ring.set", "volume is 5 in range [0..7]", ""));
        assertEquals("闹钟音量已调到 6", ControlFeedback.refine("volume.alarm.set", "volume is 6", ""));
        assertEquals("通知音量已调到 4（共 7 格）", ControlFeedback.refine("volume.notif.set", "volume is 4 in range [0..7]", ""));
    }

    @Test public void unknownOrUnreadableKeepsOriginalReply() {
        assertNull(ControlFeedback.refine("settings.brightness.up", "", ""));
        assertNull(ControlFeedback.refine("settings.brightness.up", "Error: permission", ""));
        assertNull(ControlFeedback.refine("settings.brightness.set", "999", ""));
        assertNull(ControlFeedback.refine("volume.media.up", "no volume here", "9"));
        assertNull(ControlFeedback.refine("volume.media.get", "volume is 9 in range [0..15]", ""));   // 查询走 ReadOnlyAnswer
        assertNull(ControlFeedback.refine("volume.media.mute", "volume is 0 in range [0..15]", ""));
        assertNull(ControlFeedback.refine("wifi.enable", "1", ""));
        assertNull(ControlFeedback.refine(null, "1", "1"));
    }

    @Test public void smsTellsUserTheNextStep() {
        String s = ControlFeedback.refine("sms.send", "", "Starting: Intent { act=android.intent.action.SENDTO }");
        assertEquals("短信已写好，核对后点「发送」就发出去了", s);
        assertFalse("不暴露号码 / 正文", s.matches(".*\\d{5,}.*"));
    }

    // ------------------------------------------------------------------ 经执行流水线

    static final class Shell implements ShellRunner {
        final List<String> cmds = new ArrayList<String>();
        final String checkOut;
        final String execOut;
        Shell(String execOut, String checkOut) { this.execOut = execOut; this.checkOut = checkOut; }
        @Override public ShellExecutor.ShellResponse execute(String command) {
            cmds.add(command);
            boolean check = command.startsWith("settings get") || command.contains("--get");
            return new ShellExecutor.ShellResponse(0, check ? checkOut : execOut, false, false);
        }
    }

    static final class NoApps implements AppResolver {
        @Override public String resolveComponent(String appName) { return null; }
        @Override public boolean launchComponent(String component) { return false; }
    }

    @Test public void pipelineUsesReadbackAndNeverTurnsSuccessIntoFailure() throws Exception {
        Capability up = new Capability.Builder("settings.brightness.up", "调高屏幕亮度")
                .cmd("b=$(settings get system screen_brightness); echo 200")
                .check("settings get system screen_brightness", "")
                .reply("屏幕亮度已调高").build();
        ExecutionResult r = new UnifiedExecutor(null, new Shell("200", "200"), new NoApps(), null)
                .execute(up, new JSONObject());
        assertTrue(r.ok);
        assertEquals("亮度调高到 78%", r.userMessage);

        // 回读读不出数 → 原回执逐字不变、仍然成功
        // 2026-10-25：brightness.up 的执行由 ShellPrograms 接管（exec 守护 v4 不执行 $(...) 脚本），它和真机原脚本一样
        //   回显新亮度值，所以这里换成没有程序接管的 brightness.set 来守住「回读读不出 → 原回执」这条语义。
        Capability set = new Capability.Builder("settings.brightness.set", "设置屏幕亮度")
                .cmd("settings put system screen_brightness 200")
                .check("settings get system screen_brightness", "")
                .reply("屏幕亮度已调整").build();
        ExecutionResult r2 = new UnifiedExecutor(null, new Shell("", ""), new NoApps(), null)
                .execute(set, new JSONObject());
        assertTrue(r2.ok);
        assertEquals("屏幕亮度已调整", r2.userMessage);
        // brightness.up 回读空：按执行输出（新亮度值）改写，仍然成功
        ExecutionResult r3 = new UnifiedExecutor(null, new Shell("", ""), new NoApps(), null)
                .execute(up, new JSONObject());
        assertTrue(r3.ok);
    }
}
