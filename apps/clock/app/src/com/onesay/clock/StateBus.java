package com.onesay.clock;

import android.content.Context;
import android.content.Intent;

import com.onesay.clock.core.BriefMeta;
import com.onesay.clock.core.StateMsg;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;

/**
 * 播报状态 → files/state.json + BRIEF_STATE 广播（给自己和 OneSay：setPackage("com.onesay.assistant")，未安装无副作用）。
 * 格式见 docs/02-BRIEF_STATE接口.md。
 */
public final class StateBus {
    private StateBus() { }

    public static final String BIXBYUI = "com.onesay.assistant";

    public static void publish(Context c, String state, String detail, BriefMeta m) {
        File brief = new File(c.getFilesDir(), "brief/brief.json");
        Map<String, Object> x = StateMsg.extras(state, detail, m, brief.getPath(), System.currentTimeMillis());
        try {
            File f = new File(c.getFilesDir(), "state.json"), tmp = new File(c.getFilesDir(), "state.json.tmp");
            FileOutputStream o = new FileOutputStream(tmp);
            try { o.write(StateMsg.json(x).getBytes("UTF-8")); } finally { o.close(); }
            tmp.renameTo(f);
        } catch (Throwable ignored) { }
        Intent i = new Intent(StateMsg.ACTION);
        for (Map.Entry<String, Object> e : x.entrySet()) {
            Object v = e.getValue();
            if (v instanceof Long) i.putExtra(e.getKey(), ((Long) v).longValue());
            else i.putExtra(e.getKey(), String.valueOf(v));
        }
        try {
            c.sendBroadcast(new Intent(i).setPackage(c.getPackageName()));
            c.sendBroadcast(new Intent(i).setPackage(BIXBYUI));
        } catch (Throwable ignored) { }
    }
}
