package com.onesay.assistant.v2.conn.mcp;

/**
 * [conn/mcp] Server-Sent Events 行解析器（纯逻辑，按 WHATWG 规则的子集）：
 * {@code event:} / {@code data:}（多行用 \n 拼）/ {@code id:}；以 {@code :} 开头的是注释；空行派发一个事件。
 */
public final class SseParser {

    public static final class Event {
        public final String event;
        public final String data;
        public final String id;

        Event(String event, String data, String id) {
            this.event = event;
            this.data = data;
            this.id = id;
        }
    }

    private String mEvent;
    private StringBuilder mData;
    private String mId;

    /** 喂一行（不含换行符）；凑够一个事件时返回它，否则 null。 */
    public Event feed(String line) {
        if (line == null) return null;
        if (line.length() == 0) return flush();
        if (line.startsWith(":")) return null;
        int c = line.indexOf(':');
        String field = c < 0 ? line : line.substring(0, c);
        String value = c < 0 ? "" : line.substring(c + 1);
        if (value.startsWith(" ")) value = value.substring(1);
        if ("event".equals(field)) {
            mEvent = value;
        } else if ("data".equals(field)) {
            if (mData == null) mData = new StringBuilder();
            else mData.append('\n');
            mData.append(value);
        } else if ("id".equals(field)) {
            mId = value;
        }
        return null;
    }

    /** 流结束时把残留的事件吐出来（没有返回 null）。 */
    public Event flush() {
        if (mData == null) {
            mEvent = null;
            return null;
        }
        Event e = new Event(mEvent == null || mEvent.length() == 0 ? "message" : mEvent, mData.toString(), mId);
        mEvent = null;
        mData = null;
        return e;
    }
}
