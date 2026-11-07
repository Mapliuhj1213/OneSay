package com.onesay.assistant.v2.assist;

import android.os.Bundle;
import android.service.voice.VoiceInteractionSession;
import android.service.voice.VoiceInteractionSessionService;

/** ★ r2/sysux P2-1：系统按需绑定（showSession 时 BIND_TREAT_LIKE_ACTIVITY|BIND_FOREGROUND_SERVICE）。 */
public class OneSaySessionService extends VoiceInteractionSessionService {
    @Override
    public VoiceInteractionSession onNewSession(Bundle args) {
        return new OneSaySession(this);
    }
}
