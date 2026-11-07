package com.onesay.assistant.v2.capability;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 结构化参数校验器（符合 JSON Schema 核心语义）。
 * 
 * 作用：
 * 1. 替代 v1 中「占位符无校验直接拼接」的脆弱机制；
 * 2. 约束 Router/LLM 的入参，防止命令注入与参数溢出；
 * 3. 对入参进行类型、必填项、取值范围（min/max）及枚举限制校验。
 */
public class ParamsSchema {

    public enum ParamType {
        INTEGER,
        STRING,
        BOOLEAN
    }

    /** 参数属性规则定义 */
    public static class PropertyRule {
        public final String name;
        public final ParamType type;
        public final boolean required;
        public final Long min;
        public final Long max;
        public final String defaultValue;
        public final Set<String> enumValues;

        public PropertyRule(String name, ParamType type, boolean required, Long min, Long max, String defaultValue, Set<String> enumValues) {
            this.name = name;
            this.type = type;
            this.required = required;
            this.min = min;
            this.max = max;
            this.defaultValue = defaultValue;
            this.enumValues = enumValues != null ? enumValues : Collections.<String>emptySet();
        }
    }

    private final Map<String, PropertyRule> mRules = new HashMap<String, PropertyRule>();
    private final Set<String> mRequired = new HashSet<String>();

    public ParamsSchema addRule(PropertyRule rule) {
        mRules.put(rule.name, rule);
        if (rule.required) {
            mRequired.add(rule.name);
        }
        return this;
    }

    public ParamsSchema addInteger(String name, boolean required, long min, long max, String defaultValue) {
        return addRule(new PropertyRule(name, ParamType.INTEGER, required, min, max, defaultValue, null));
    }

    public ParamsSchema addString(String name, boolean required, String defaultValue) {
        return addRule(new PropertyRule(name, ParamType.STRING, required, null, null, defaultValue, null));
    }

    public ParamsSchema addBoolean(String name, boolean required, String defaultValue) {
        return addRule(new PropertyRule(name, ParamType.BOOLEAN, required, null, null, defaultValue, null));
    }

    public Map<String, PropertyRule> getRules() {
        return Collections.unmodifiableMap(mRules);
    }

    public Set<String> getRequired() {
        return Collections.unmodifiableSet(mRequired);
    }

    /**
     * 针对输入参数执行严格校验。
     * 若存在默认值且调用方未提供，则自动回填默认值。
     *
     * @param args        待校验的参数 JSON
     * @param errorOutput 错误原因输出容器
     * @return 校验是否完全通过
     */
    public boolean validateAndApplyDefaults(JSONObject args, StringBuilder errorOutput) {
        if (args == null) {
            args = new JSONObject();
        }

        // 1. 检查必填项与回填默认值
        for (PropertyRule rule : mRules.values()) {
            if (!args.has(rule.name)) {
                if (rule.defaultValue != null) {
                    try {
                        if (rule.type == ParamType.INTEGER) {
                            args.put(rule.name, Long.parseLong(rule.defaultValue));
                        } else if (rule.type == ParamType.BOOLEAN) {
                            args.put(rule.name, Boolean.parseBoolean(rule.defaultValue));
                        } else {
                            args.put(rule.name, rule.defaultValue);
                        }
                    } catch (Exception e) {
                        // 忽略默认值注入异常
                    }
                } else if (rule.required) {
                    if (errorOutput != null) {
                        errorOutput.append("缺少必填参数: ").append(rule.name);
                    }
                    return false;
                }
            }
        }

        // 2. 检查各项类型与取值约束
        Iterator<?> keys = args.keys();
        while (keys.hasNext()) {
            String key = (String) keys.next();
            PropertyRule rule = mRules.get(key);
            if (rule == null) {
                // 允许透传未定义扩展字段，保持向前兼容
                continue;
            }

            Object val = args.opt(key);
            if (val == null || val == JSONObject.NULL) {
                if (rule.required) {
                    if (errorOutput != null) {
                        errorOutput.append("参数不能为空: ").append(key);
                    }
                    return false;
                }
                continue;
            }

            switch (rule.type) {
                case INTEGER:
                    long intVal;
                    if (val instanceof Number) {
                        intVal = ((Number) val).longValue();
                    } else {
                        try {
                            intVal = Long.parseLong(String.valueOf(val).trim());
                            args.put(key, intVal);
                        } catch (Exception e) {
                            if (errorOutput != null) {
                                errorOutput.append("参数类型必须为整数: ").append(key);
                            }
                            return false;
                        }
                    }
                    if (rule.min != null && intVal < rule.min) {
                        if (errorOutput != null) {
                            errorOutput.append("参数 ").append(key).append(" 低于最小值: ").append(rule.min);
                        }
                        return false;
                    }
                    if (rule.max != null && intVal > rule.max) {
                        if (errorOutput != null) {
                            errorOutput.append("参数 ").append(key).append(" 高于最大值: ").append(rule.max);
                        }
                        return false;
                    }
                    break;

                case BOOLEAN:
                    if (!(val instanceof Boolean)) {
                        String s = String.valueOf(val).trim().toLowerCase();
                        if ("true".equals(s) || "1".equals(s)) {
                            try { args.put(key, true); } catch (Exception ignored) {}
                        } else if ("false".equals(s) || "0".equals(s)) {
                            try { args.put(key, false); } catch (Exception ignored) {}
                        } else {
                            if (errorOutput != null) {
                                errorOutput.append("参数类型必须为布尔值: ").append(key);
                            }
                            return false;
                        }
                    }
                    break;

                case STRING:
                    String strVal = String.valueOf(val);
                    if (!rule.enumValues.isEmpty() && !rule.enumValues.contains(strVal)) {
                        if (errorOutput != null) {
                            errorOutput.append("参数 ").append(key).append(" 必须在枚举值内: ").append(rule.enumValues);
                        }
                        return false;
                    }
                    break;
            }
        }

        return true;
    }

    /** 导出为标准 JSON 格式声明 */
    public JSONObject toJsonSchema() {
        JSONObject root = new JSONObject();
        try {
            root.put("type", "object");
            JSONObject props = new JSONObject();
            JSONArray reqArr = new JSONArray();

            for (PropertyRule rule : mRules.values()) {
                JSONObject prop = new JSONObject();
                prop.put("type", rule.type.name().toLowerCase());
                if (rule.min != null) prop.put("minimum", rule.min);
                if (rule.max != null) prop.put("maximum", rule.max);
                if (rule.defaultValue != null) prop.put("default", rule.defaultValue);
                props.put(rule.name, prop);

                if (rule.required) {
                    reqArr.put(rule.name);
                }
            }
            root.put("properties", props);
            if (reqArr.length() > 0) {
                root.put("required", reqArr);
            }
        } catch (Exception ignored) {}
        return root;
    }
}
