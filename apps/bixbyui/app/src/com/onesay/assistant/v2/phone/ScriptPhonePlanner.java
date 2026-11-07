package com.onesay.assistant.v2.phone;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ★ fast-screen-v2：脚本化 planner（纯 Java）—— debug 包没配大模型 Key 时测执行层用。
 * 按顺序吐预先写好的 JSON；{@code {id:名字}} 换成当前提示词里该名字的编号（和模型读编号的方式一样，
 * 名字找不到就写 -9，让校验当场报「不在当前界面」）。脚本用完返回 done。
 */
public final class ScriptPhonePlanner implements PhoneAgent.Planner {

    private final List<String> mOuts = new ArrayList<String>();
    private int mI;
    public final List<String> users = new ArrayList<String>();

    public ScriptPhonePlanner(String script) {
        if (script != null) for (String s : script.split("\\|\\|\\|")) if (s.trim().length() > 0) mOuts.add(s.trim());
    }

    public int size() {
        return mOuts.size();
    }

    @Override public String next(String system, String user) {
        users.add(user);
        String o = mI < mOuts.size() ? mOuts.get(mI++) : "{\"done\":\"脚本用完\"}";
        return resolve(o, user);
    }

    static final Pattern REF = Pattern.compile("\\{id:([^}]+)\\}");

    public static String resolve(String o, String user) {
        Matcher m = REF.matcher(o);
        StringBuffer b = new StringBuffer();
        while (m.find()) {
            Matcher k = Pattern.compile("\\[(\\d+)\\] [^\\n「]*「" + Pattern.quote(m.group(1))).matcher(user == null ? "" : user);
            m.appendReplacement(b, k.find() ? k.group(1) : "-9");
        }
        m.appendTail(b);
        return b.toString();
    }
}
