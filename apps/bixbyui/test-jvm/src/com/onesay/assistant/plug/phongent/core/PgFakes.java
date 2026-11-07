package com.onesay.assistant.plug.phongent.core;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** phongent 插件单测用的假宿主（全部端口一个类）。 */
public class PgFakes implements Storage, Clock, PlugLog, HostInfo, Approver, TaskExecutor, PlugConfig, NotificationSource, ScreenSource, InputSink {
    public final Map<String, String> store = new HashMap<String, String>();
    public long elapsed = 1000000L, wall = 1700000000000L;
    public boolean canPrompt = true, failPut, tsOnly, loopback = true, relay = true, screen = true, busy;
    public final List<String> asked = new ArrayList<String>(), dismissed = new ArrayList<String>(), inputs = new ArrayList<String>();
    public int screenAsks;
    public final Map<Long, RunView> runs = new HashMap<Long, RunView>();
    public final List<String> submitted = new ArrayList<String>();
    public final List<Boolean> taints = new ArrayList<Boolean>();
    public final List<Long> cancelled = new ArrayList<Long>();
    public boolean full;
    long next = 100;
    public Sink sink;
    public int attaches, detaches;
    public String name = "Note8";

    @Override public synchronized Map<String, String> all() { return new HashMap<String, String>(store); }
    @Override public synchronized boolean put(String k, String v) { if (failPut) return false; store.put(k, v); return true; }
    @Override public synchronized boolean remove(String k) { return store.remove(k) != null; }
    @Override public long elapsed() { return elapsed; }
    @Override public long wall() { return wall; }
    @Override public void i(String m) { }
    @Override public void w(String m) { }
    @Override public String deviceName() { return name; }
    @Override public String rename(String n) { if (n.trim().length() == 0) throw new IllegalArgumentException("invalid_device_name"); name = n.trim(); return name; }
    @Override public boolean localBusy() { return busy; }
    @Override public boolean notificationAccess() { return true; }
    @Override public boolean listenerConnected() { return true; }
    @Override public boolean canPrompt() { return canPrompt; }
    @Override public void askPair(String id, String n) { asked.add(id + "|" + n); }
    @Override public void dismissPair(String id) { dismissed.add(id); }
    @Override public void askScreen(String n) { screenAsks++; }
    @Override public void dismissScreen() { }
    @Override public synchronized long submit(String text, boolean tainted) {
        if (full) return -1;
        long id = ++next;
        submitted.add(text);
        taints.add(tainted);
        runs.put(id, new RunView("PENDING", "", "", ""));
        return id;
    }
    @Override public synchronized RunView view(long id) { return runs.get(id); }
    @Override public synchronized boolean cancel(long id) { cancelled.add(id); if (runs.containsKey(id)) runs.put(id, new RunView("CANCELLED", "", "", "")); return true; }
    public synchronized void set(long id, String state, String step, String result, String error) { runs.put(id, new RunView(state, step, result, error)); }
    @Override public boolean tailscaleOnly() { return tsOnly; }
    @Override public boolean allowLoopback() { return loopback; }
    @Override public boolean relayNotifications() { return relay; }
    @Override public boolean screenSharing() { return screen; }
    @Override public void attach(Sink s) { sink = s; attaches++; }
    @Override public void detach() { sink = null; detaches++; }
    @Override public int[] screenSize() { return new int[]{1440, 2960}; }
    @Override public int[] streamSize() { return new int[]{352, 720}; }
    @Override public void stream(PacketSink s) throws IOException {
        s.size(352, 720);
        s.packet(new byte[]{0, 0, 0, 1, 0x67, 1, 2});
        s.packet(new byte[]{0, 0, 0, 1, 0x65, 9, 9});
    }
    @Override public boolean tap(int x, int y) { inputs.add("tap " + x + "," + y); return true; }
    @Override public boolean swipe(int a, int b, int c, int d, int ms) { inputs.add("swipe " + a + "," + b + "->" + c + "," + d + " " + ms); return true; }
    @Override public boolean key(int k) { inputs.add("key " + k); return true; }

    public static String rid(char c) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 72; i++) sb.append(c);
        return sb.toString();
    }
}
