package com.onesay.assistant.v2.capability;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 原子能力定义（bixbyui-v2 的核心原子契约，替代 v1 的 mapping.json 直译模型）。
 *
 * 铁律：
 * 1. 唯一事实源是 sign + paramsSchema + executor/cmd/check。
 * 2. say 只是路由提示，不等于能力本体。
 * 3. risk=2 且 confirm=true 强制进入前端确认卡分支，不可旁路直接执行。
 */
public class Capability {

    /** 能力唯一签名标识（如 settings.brightness.set / connectivity.wifi.on） */
    public final String sign;

    /** 中文自然语言能力摘要 */
    public final String summary;

    /** 业务域分类（net, settings, media, app, clock, sys） */
    public final String category;
    public final String domain;
    public final List<String> verbs;
    public final List<String> entities;
    public final List<String> examples;

    /** 显式中文关键词（方案 A 倒排索引的强信号，与 say 模板互补） */
    public final List<String> keywords;


    /** 参数约束定义（JSON Schema 校验） */
    public final ParamsSchema paramsSchema;

    /** 风险等级：0=只读安全 1=常规可逆 2=破坏性/敏感操作 */
    public final int risk;

    /** 是否必须经由用户界面弹窗二次确认 */
    public final boolean confirm;
    
    /** 副作用分类（确定性拦截判据） */
    public final SideEffect sideEffect;

    /** 特权执行通道类型（shell / intent / settings） */
    public final String executorType;

    /** 执行指令模板（支持 {param} 占位符） */
    public final String cmdTemplate;

    /** 真实状态回读命令（验证物理生效） */
    public final String checkCommand;

    /** 回读命令期望匹配输出模板 */
    public final String expectTemplate;

    /** 自然语言语音触发词示例模板列表 */
    public final List<String> sayTemplates;

    /** TTS/前端反馈提示模板 */
    public final String replyTemplate;

    /** 针对回读结果的状态到中文话术映射 */
    public final Map<String, String> replyMap;

    public Capability(Builder b) {
        this.sign = b.sign;
        this.summary = b.summary;
        this.category = b.category;
        this.domain = b.domain != null ? b.domain : b.category;
        this.verbs = Collections.unmodifiableList(new ArrayList<String>(b.verbs));
        this.entities = Collections.unmodifiableList(new ArrayList<String>(b.entities));
        this.examples = Collections.unmodifiableList(new ArrayList<String>(b.examples));
        this.keywords = Collections.unmodifiableList(new ArrayList<String>(b.keywords));

        this.paramsSchema = b.paramsSchema != null ? b.paramsSchema : new ParamsSchema();
        this.risk = b.risk;
        this.confirm = b.confirm;
        this.sideEffect = b.sideEffect != null ? b.sideEffect : SideEffect.NONE;
        this.executorType = b.executorType != null ? b.executorType : "shell";
        this.cmdTemplate = b.cmdTemplate;
        this.checkCommand = b.checkCommand;
        this.expectTemplate = b.expectTemplate;
        this.sayTemplates = Collections.unmodifiableList(new ArrayList<String>(b.sayTemplates));
        this.replyTemplate = b.replyTemplate;
        this.replyMap = Collections.unmodifiableMap(new HashMap<String, String>(b.replyMap));
    }

    /** 导出当前能力元数据为 JSON 对象（供自省接口及 LLM 提示词编译） */
    public JSONObject toJson() {
        JSONObject obj = new JSONObject();
        try {
            obj.put("sign", sign);
            obj.put("summary", summary);
            obj.put("category", category);
            obj.put("domain", domain);
            
            JSONArray verbsArray = new JSONArray();
            for (String s : verbs) verbsArray.put(s);
            obj.put("verbs", verbsArray);
            
            JSONArray entitiesArray = new JSONArray();
            for (String s : entities) entitiesArray.put(s);
            obj.put("entities", entitiesArray);
            
            JSONArray examplesArray = new JSONArray();
            for (String s : examples) examplesArray.put(s);
            obj.put("examples", examplesArray);

            obj.put("risk", risk);
            obj.put("confirm", confirm);
            obj.put("sideEffect", sideEffect.name());
            obj.put("executor", executorType);
            obj.put("paramsSchema", paramsSchema.toJsonSchema());
            JSONArray says = new JSONArray();
            for (String s : sayTemplates) {
                says.put(s);
            }
            obj.put("say", says);
            obj.put("reply", replyTemplate);
        } catch (Exception ignored) {}
        return obj;
    }

    /**
     * 导出**接口库**视图（面向数据库/文档/人，不是面向 LLM 提示词）。
     *
     * <h3>为什么必须与 {@link #toJson()} 分开（2026-10-04 补）</h3>
     * <p>初版接口库直接复用 {@code toJson()}，结果落盘的 interface_db.json 里
     * **没有 cmd / check / expect / keywords** —— 也就是"接口名 + 参数"有了，
     * 但**"这个接口怎么调、调完怎么验"没有**。
     * 用户对接口库的要求原文是"接口跟参数还有**用法**都得挖出来"，
     * 缺了 cmd（调用方式）与 check/expect（验证方式）的库，不算接口库，只能算能力清单。
     *
     * <p>不能直接改 {@code toJson()}：它同时被 LLM 提示词编译复用，
     * 把整条 shell 命令塞进提示词会显著增加 token 且干扰模型选接口。
     * 因此拆成两个视图：{@code toJson()} 保持精简（选接口用），
     * 本方法输出完整（执行/验收/归档用）。
     *
     * @return 含调用方式与验证方式的完整接口描述
     */
    public JSONObject toInterfaceJson() {
        JSONObject obj = toJson();
        try {
            // ---- 用法三要素：怎么调 / 怎么读回 / 读到什么算成功 ----
            obj.put("executor", executorType);
            obj.put("cmd", cmdTemplate != null ? cmdTemplate : "");
            obj.put("check", checkCommand != null ? checkCommand : "");
            obj.put("expect", expectTemplate != null ? expectTemplate : "");

            // ---- 路由强信号：keywords 是方案 A 倒排索引的原始料 ----
            JSONArray kws = new JSONArray();
            for (String k : keywords) kws.put(k);
            obj.put("keywords", kws);

            // ---- 二级接口类型 + 只读/破坏性属性（消费方不必再自己推）----
            InterfaceKind.Kind kind = InterfaceKind.of(this);
            obj.put("kind", kind.name());
            obj.put("kindCn", kind.cn());
            obj.put("readOnly", kind.isReadOnly());
            obj.put("destructive", kind.isDestructive());
            obj.put("software", InterfaceKind.softwareOf(this));

            // ---- 状态→话术映射（回读后如何向用户表述）----
            if (!replyMap.isEmpty()) {
                JSONObject rm = new JSONObject();
                for (Map.Entry<String, String> e : replyMap.entrySet()) {
                    rm.put(e.getKey(), e.getValue());
                }
                obj.put("replyMap", rm);
            }
        } catch (Exception ignored) {}
        return obj;
    }

    public static class Builder {
        private String sign;
        private String summary;
        private String category = "general";
        private String domain;
        private List<String> verbs = new ArrayList<String>();
        private List<String> entities = new ArrayList<String>();
        private List<String> examples = new ArrayList<String>();
        private List<String> keywords = new ArrayList<String>();

        private ParamsSchema paramsSchema;
        private int risk = 0;
        private boolean confirm = false;
        private SideEffect sideEffect = SideEffect.NONE;
        private String executorType = "shell";
        private String cmdTemplate;
        private String checkCommand;
        private String expectTemplate;
        private List<String> sayTemplates = new ArrayList<String>();
        private String replyTemplate;
        private Map<String, String> replyMap = new HashMap<String, String>();

        public Builder(String sign, String summary) {
            this.sign = sign;
            this.summary = summary;
        }

        
        public Builder domain(String domain) {
            this.domain = domain;
            return this;
        }

        public Builder verbs(String... verbs) {
            for (String v : verbs) this.verbs.add(v);
            return this;
        }

        public Builder entities(String... entities) {
            for (String e : entities) this.entities.add(e);
            return this;
        }

        public Builder examples(String... examples) {
            for (String e : examples) this.examples.add(e);
            return this;
        }

        /** 显式中文关键词（方案 A 倒排索引的强信号）。 */
        public Builder keywords(String... keywords) {
            if (keywords != null) {
                for (String k : keywords) {
                    if (k != null && k.trim().length() > 0) this.keywords.add(k.trim());
                }
            }
            return this;
        }

        public Builder category(String cat) { this.category = cat; return this; }
        public Builder schema(ParamsSchema s) { this.paramsSchema = s; return this; }
        public Builder risk(int r, boolean conf) { this.risk = r; this.confirm = conf; return this; }
        public Builder sideEffect(SideEffect effect) { this.sideEffect = effect; return this; }
        public Builder executor(String e) { this.executorType = e; return this; }
        public Builder cmd(String c) { this.cmdTemplate = c; return this; }
        public Builder check(String chk, String expect) { this.checkCommand = chk; this.expectTemplate = expect; return this; }
        public Builder say(String... sayings) {
            if (sayings != null) {
                for (String s : sayings) {
                    if (s != null && s.trim().length() > 0) this.sayTemplates.add(s.trim());
                }
            }
            return this;
        }
        public Builder reply(String r) { this.replyTemplate = r; return this; }
        public Builder addReplyMapping(String key, String chineseText) {
            this.replyMap.put(key, chineseText);
            return this;
        }

        public Capability build() {
            if (sign == null || sign.length() == 0) {
                throw new IllegalArgumentException("能力签名 sign 不能为空");
            }
            return new Capability(this);
        }
    }
}
