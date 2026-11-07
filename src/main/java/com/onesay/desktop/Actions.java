package com.onesay.desktop;

import java.util.List;
import java.util.Map;

/** 电脑端能力。每个能力只收结构化参数；不接自然语言、不接任意命令。 */
public interface Actions {
    final class Result {
        public final boolean ok;
        public final String text;
        public final Map<String, Object> data;

        public Result(boolean ok, String text, Map<String, Object> data) {
            this.ok = ok;
            this.text = text;
            this.data = data;
        }

        public static Result ok(String text) { return new Result(true, text, null); }
        public static Result ok(String text, Map<String, Object> data) { return new Result(true, text, data); }
        public static Result fail(String text) { return new Result(false, text, null); }
    }

    /** 能力名列表，上线时告诉对方。 */
    List<String> capabilities();

    Result run(String cmd, Map<String, Object> args) throws Exception;
}
