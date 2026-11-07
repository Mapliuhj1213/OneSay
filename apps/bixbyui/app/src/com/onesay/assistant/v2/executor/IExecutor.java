package com.onesay.assistant.v2.executor;

import com.onesay.assistant.v2.capability.Capability;
import org.json.JSONObject;

/**
 * 特权执行器接口。
 */
public interface IExecutor {
    ExecutionResult execute(Capability capability, JSONObject args);
}
