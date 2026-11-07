package com.onesay.assistant.v2.executor.backend;

import android.content.Context;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.executor.AppResolver;
import com.onesay.assistant.v2.executor.ShellRunner;

import org.json.JSONObject;

/** 一次执行的上下文（不可变）。 */
public final class ExecRequest {
    public final Capability cap;
    public final JSONObject args;
    /** 解析出的 "包名/Activity"（仅含 {app} 参数的能力），可能为 null。 */
    public final String component;
    public final String pkg;
    /** 已按 ShellCommand 规则填充好的执行命令，可能为 null。 */
    public final String filledCmd;
    /** Android Context（单测中可为 null）。 */
    public final Context context;
    public final ShellRunner shell;
    public final AppResolver apps;

    public ExecRequest(Capability cap, JSONObject args, String component, String pkg, String filledCmd,
                       Context context, ShellRunner shell, AppResolver apps) {
        this.cap = cap;
        this.args = args;
        this.component = component;
        this.pkg = pkg;
        this.filledCmd = filledCmd;
        this.context = context;
        this.shell = shell;
        this.apps = apps;
    }
}
