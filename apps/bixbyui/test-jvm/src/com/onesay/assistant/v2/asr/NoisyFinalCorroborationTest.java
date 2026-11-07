package com.onesay.assistant.v2.asr;

import org.junit.Test;
import static org.junit.Assert.*;

/** ★ 11-04 H：机上 17:50 嘈杂环境（冻结底噪 374/542）正常说话只有 2 帧过线、服务端出 7 字 → 应提交，不当幻觉。 */
public class NoisyFinalCorroborationTest {
    @Test public void noisyRoomRealSpeechAccepted() {
        assertTrue(RealtimeAsrPolicy.finalCorroborated(2, true, 7));
        assertTrue(RealtimeAsrPolicy.finalCorroborated(1, true, 3));
    }
    @Test public void normalPathUnchanged() {
        assertTrue(RealtimeAsrPolicy.finalCorroborated(49, false, 7));
        assertTrue(RealtimeAsrPolicy.finalCorroborated(3, false, 1));
    }
    @Test public void hallucinationStillDropped() {
        assertFalse(RealtimeAsrPolicy.finalCorroborated(0, true, 7));   // 纯静音 / 提示音尾巴
        assertFalse(RealtimeAsrPolicy.finalCorroborated(2, false, 7));  // 服务端没报说话
        assertFalse(RealtimeAsrPolicy.finalCorroborated(2, true, 2));   // 太短
    }
}
