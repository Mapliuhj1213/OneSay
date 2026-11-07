/*
 * Copyright 2026 wangzan101 (Mika) and mylea (phongent, https://github.com/mylea1/phongent)
 * Modifications Copyright 2026 OneSay
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * 改动声明（OneSay，2026-11-02）：本文件改写自 phongent
 * app/src/main/java/com/wangzan101/mika/remote/MessageListener.kt（Kotlin → Java 7）。
 * 保留：记录 JSON 形状 {id,app,package,title,text,conversationId,canOpen,link,receivedAt,time}、
 * id = SHA-256(key|time|title|body)、新的排最前、最多 1000 条、/messages?after=&amp;wait= 长轮询语义。
 * 改动：去掉 Android 依赖（通知字段由宿主取好传进来，JVM 可测）；包名白名单由 OneSay 设置决定、默认只有微信 / QQ；
 * 不落盘（上游存 SharedPreferences）—— 消息正文只在内存，进程重启即清，减少第三方原文留存。
 */
package com.onesay.assistant.plug.phongent.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** [phongent] 通知预览环（只放白名单 App 的）。纯 Java；线程安全。 */
public final class PgMessages {

    public static final int MAX = 1000;
    static final Pattern LINK = Pattern.compile("https?://[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]+");

    private final Object mLock = new Object();
    private final List<JSONObject> mItems = new ArrayList<JSONObject>();
    private final Map<String, NotificationSource.Opener> mTargets = new HashMap<String, NotificationSource.Opener>();
    private long mRevision = System.currentTimeMillis();
    private Runnable mOnChange;

    public void setOnChange(Runnable r) {
        mOnChange = r;
    }

    static String id(String key, long time, String title, String body) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest((key + "|" + time + "|" + title + "|" + body).getBytes("UTF-8"));
            return PgAuth.hex(d);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * 一条通知进来（宿主已按白名单过滤、已跳过分组摘要 / 常驻通知）。
     * entries：MessagingStyle 的每条 [text, time]；没有就传 [正文, postTime] 一条。
     */
    public void post(NotificationSource.Notice n, long nowMs) {
        post(n.key, n.pkg, n.appName, n.title, n.shortcutId, n.entries, n.opener, nowMs);
    }

    public void post(String key, String pkg, String appName, String title, String shortcutId, List<Object[]> entries,
                     NotificationSource.Opener opener, long nowMs) {
        boolean changed = false;
        synchronized (mLock) {
            String t = title == null ? "" : title;
            for (Object[] e : entries) {
                String body = e[0] == null ? "" : String.valueOf(e[0]);
                long time = (Long) e[1];
                if (body.trim().length() == 0 && t.length() == 0) continue;
                String conv = shortcutId != null && shortcutId.length() > 0 ? shortcutId : t;
                String id = id(key, time, t, body);
                if (opener != null) mTargets.put(id, opener);
                JSONObject existing = null;
                for (JSONObject o : mItems) if (id.equals(o.optString("id"))) existing = o;
                try {
                    if (existing != null) {
                        boolean can = mTargets.containsKey(id);
                        if (existing.optBoolean("canOpen") != can) {
                            existing.put("canOpen", can);
                            changed = true;
                        }
                        continue;
                    }
                    Matcher m = LINK.matcher(body);
                    JSONObject o = new JSONObject();
                    o.put("conversationId", pkg + ":" + conv);
                    o.put("canOpen", mTargets.containsKey(id));
                    o.put("link", m.find() ? m.group() : JSONObject.NULL);
                    o.put("receivedAt", nowMs / 1000.0);
                    o.put("id", id);
                    o.put("app", appName);
                    o.put("package", pkg);
                    o.put("title", t.length() > 300 ? t.substring(0, 300) : t);
                    o.put("text", body.length() > 8000 ? body.substring(0, 8000) : body);
                    o.put("time", time / 1000.0);
                    mItems.add(0, o);
                    changed = true;
                } catch (Exception ignored) {
                }
            }
            while (mItems.size() > MAX) mTargets.remove(mItems.remove(mItems.size() - 1).optString("id"));
            if (changed) {
                mRevision++;
                mLock.notifyAll();
            }
        }
        if (changed && mOnChange != null) {
            try {
                mOnChange.run();
            } catch (Throwable ignored) {
            }
        }
    }

    public JSONArray snapshot() {
        synchronized (mLock) {
            JSONArray a = new JSONArray();
            for (JSONObject o : mItems) a.put(o);
            return a;
        }
    }

    public long revision() {
        synchronized (mLock) {
            return mRevision;
        }
    }

    /** GET /messages?after=&amp;wait=：revision 变了立刻回，否则最多等 wait 秒（0–60）。 */
    public JSONObject changes(long after, long waitSeconds) throws InterruptedException {
        synchronized (mLock) {
            long deadline = System.currentTimeMillis() + Math.max(0, Math.min(60, waitSeconds)) * 1000L;
            while (mRevision == after) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) break;
                mLock.wait(left);
            }
            JSONObject o = new JSONObject();
            try {
                o.put("revision", mRevision);
                JSONArray a = new JSONArray();
                for (JSONObject x : mItems) a.put(x);
                o.put("messages", a);
            } catch (Exception ignored) {
            }
            return o;
        }
    }

    /** POST /notification/open。 */
    public void open(String id) throws Exception {
        NotificationSource.Opener o;
        synchronized (mLock) {
            o = mTargets.get(id);
        }
        if (o == null) throw new IllegalStateException("notification_expired");
        o.open();
    }

    /** 关功能 / 撤销全部主控时清空。 */
    public void clear() {
        synchronized (mLock) {
            mItems.clear();
            mTargets.clear();
            mRevision++;
            mLock.notifyAll();
        }
    }

    public int size() {
        synchronized (mLock) {
            return mItems.size();
        }
    }
}
