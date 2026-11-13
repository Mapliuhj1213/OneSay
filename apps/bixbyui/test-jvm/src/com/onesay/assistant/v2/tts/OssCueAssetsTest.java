package com.onesay.assistant.v2.tts;

import org.junit.Test;

import java.io.File;
import java.nio.file.Files;

import static org.junit.Assert.*;

/**
 * 开源包的两个提示音（oss-device-1108 真机：每次出结果都报 {@code playAckDone 异常: ack_done.wav}、
 * 确认卡报 {@code playVoiceCue 异常: confirm_prompt.wav}）。个人版的这两个文件是云端 / 端侧 TTS 合成的人声（复刻音色），
 * 授权不清楚，不搬；开源版用 tools/gen_ui_cues.py 纯数学合成的提示音（不含 TTS、不含采样）代替。
 */
public class OssCueAssetsTest {

    static File root() {
        return new File(System.getProperty("bixby.root", "."));
    }

    @Test public void ackAndConfirmCuesShippedAndPlayable() throws Exception {
        for (String name : new String[]{VoiceTemplate.DONE.asset, VoiceTemplate.CONFIRM.asset}) {
            File f = new File(root(), "app/assets/" + name);
            assertTrue("开源包要带 " + name, f.isFile());
            byte[] b = Files.readAllBytes(f.toPath());
            VoiceTemplate.Pcm p = VoiceTemplate.parseWav(b);
            assertNotNull(name + " 能解析", p);
            assertEquals(name, 24000, p.sampleRate);
            assertTrue(name + " 时长 " + p.durationMs(), p.durationMs() >= 300 && p.durationMs() <= 4000);
            assertEquals(name + " 44 字节干净头", 44 + p.data.length, b.length);
            int peak = 0;
            for (int i = 0; i + 1 < p.data.length; i += 2) peak = Math.max(peak, Math.abs((short) ((p.data[i] & 0xFF) | (p.data[i + 1] << 8))));
            assertTrue(name + " 峰值 " + peak, peak > 3000 && peak <= 29000);
        }
        assertEquals("ack_done.wav", VoiceTemplate.DONE.asset);
        assertEquals("confirm_prompt.wav", VoiceTemplate.CONFIRM.asset);
        String gen = new String(Files.readAllBytes(new File(root(), "tools/gen_ui_cues.py").toPath()), "UTF-8");
        assertTrue("生成脚本在仓库里", gen.contains("ack_done.wav") && gen.contains("confirm_prompt.wav"));
        assertFalse("生成脚本不调用任何 TTS", gen.toLowerCase().contains("cosyvoice") || gen.toLowerCase().contains("piper") || gen.contains("dashscope"));
    }
}
