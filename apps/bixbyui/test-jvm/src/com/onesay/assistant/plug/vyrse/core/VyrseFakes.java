package com.onesay.assistant.plug.vyrse.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

/** compat-vyrse 测试替身：假手机能力、假 HTTP（按脚本回应并记录请求）、假定时器。 */
final class VyrseFakes {

    private VyrseFakes() {}

    static final class Phone implements PhoneAbilities, RingController.AlarmAudio, RingController.Scheduler {
        boolean flash = true;
        Boolean torch;
        int vol = 3, max = 15;
        boolean sound, vib, soundFails;
        List<Runnable> timers = new ArrayList<Runnable>();
        List<Long> delays = new ArrayList<Long>();
        List<Notice> notices = new ArrayList<Notice>();
        boolean noticeAccess = true, canPost = true;
        List<String[]> posted = new ArrayList<String[]>();
        List<AppEntry> apps = new ArrayList<AppEntry>();
        List<String> launched = new ArrayList<String>();

        @Override public boolean hasFlashlight() { return flash; }
        @Override public void setFlashlight(boolean on) { torch = on; }
        @Override public Boolean flashlightOn() { return torch; }
        @Override public RingController.AlarmAudio alarmAudio() { return this; }
        @Override public RingController.Scheduler scheduler() { return this; }
        @Override public List<Notice> notifications(int limit) {
            if (!noticeAccess) return null;
            return notices.size() > limit ? new ArrayList<Notice>(notices.subList(0, limit)) : notices;
        }
        @Override public boolean postNotice(String title, String text) {
            if (!canPost) return false;
            posted.add(new String[]{title, text});
            return true;
        }
        @Override public int batteryPercent() { return 77; }
        @Override public boolean charging() { return true; }
        @Override public List<AppEntry> launcherApps() { return apps; }
        @Override public boolean launch(String pkg) { launched.add(pkg); return true; }
        @Override public String selfPackage() { return "com.onesay.assistant"; }
        @Override public String model() { return "Test Phone"; }
        // AlarmAudio
        @Override public int alarmVolume() { return vol; }
        @Override public int maxAlarmVolume() { return max; }
        @Override public void setAlarmVolume(int v) { vol = v; }
        @Override public void startAlarmSound() throws Exception {
            if (soundFails) throw new IllegalStateException("no ringtone");
            sound = true;
        }
        @Override public void stopAlarmSound() { sound = false; }
        @Override public void vibrate(boolean on) { vib = on; }
        // Scheduler
        @Override public Object schedule(Runnable r, long delayMs) { timers.add(r); delays.add(delayMs); return r; }
        @Override public void cancel(Object h) { timers.remove(h); }
        void fireTimers() {
            for (Runnable r : new ArrayList<Runnable>(timers)) { timers.remove(r); r.run(); }
        }
    }

    /** 记录每个请求；按队列给回应（空队列 = IOException，模拟电脑不在）。 */
    static final class Http implements VyrseHttp {
        static final class Req {
            final String method, url, token, body;
            Req(String m, String u, String t, String b) { method = m; url = u; token = t; body = b; }
        }
        final List<Req> reqs = new ArrayList<Req>();
        final LinkedList<Object> script = new LinkedList<Object>();
        /** POST /phone/command-result 的回应（默认 200）。 */
        int resultCode = 200;

        Http then(int code, String body) { script.add(new Response(code, body)); return this; }
        Http thenFail() { script.add(new IOException("down")); return this; }

        @Override public Response send(String method, String url, String token, String jsonBody, int readTimeoutMs) throws IOException {
            reqs.add(new Req(method, url, token, jsonBody));
            if (url.endsWith(VyrseProtocol.PATH_RESULT)) return new Response(resultCode, "{\"ok\":true}");
            if (script.isEmpty()) throw new IOException("no script");
            Object o = script.removeFirst();
            if (o instanceof IOException) throw (IOException) o;
            return (Response) o;
        }

        List<Req> results() {
            List<Req> r = new ArrayList<Req>();
            for (Req q : reqs) if (q.url.endsWith(VyrseProtocol.PATH_RESULT)) r.add(q);
            return r;
        }
    }

    static String commands(Object... idActionArgs) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i < idActionArgs.length; i += 3) {
            JSONObject c = new JSONObject().put("id", idActionArgs[i]).put("action", idActionArgs[i + 1]);
            if (idActionArgs[i + 2] != null) c.put("args", idActionArgs[i + 2]);
            a.put(c);
        }
        return new JSONObject().put("commands", a).toString();
    }

    /** 记录退避时长；sleepsLeft 用完返回 false（让 run() 退出）。 */
    static final class Sleeper implements VyrseLink.Sleeper {
        final List<Long> slept = new ArrayList<Long>();
        int sleepsLeft;
        Sleeper(int n) { sleepsLeft = n; }
        @Override public boolean sleep(long ms) { slept.add(ms); return --sleepsLeft >= 0; }
    }

    static final class Listener implements VyrseLink.Listener {
        final List<VyrseLink.State> states = new ArrayList<VyrseLink.State>();
        final List<String> cmds = new ArrayList<String>();
        int unauthorized;
        @Override public void onState(VyrseLink.State s) { states.add(s); }
        @Override public void onUnauthorized() { unauthorized++; }
        @Override public void onCommand(String action, boolean ok) { cmds.add(action + ":" + ok); }
    }
}
