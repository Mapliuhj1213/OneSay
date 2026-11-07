package com.onesay.assistant.v2.agent;

import com.onesay.assistant.v2.capability.Capability;
import com.onesay.assistant.v2.capability.CapabilityRegistry;
import com.onesay.assistant.v2.capability.ParamsSchema;
import com.onesay.assistant.v2.capability.SideEffect;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * ★ arch/agent-loop（B 路）：AgentLoop <b>常驻</b>工具的定义（工具 JSON + 门禁用的 Capability + 最小提示词段）。
 *
 * <table border="1">
 *   <tr><th>签名</th><th>wire 名</th><th>做什么</th><th>门禁</th><th>A 路档位建议</th></tr>
 *   <tr><td>web.search</td><td>web__search</td><td>联网搜索，结果带来源 / 可信度</td><td>过 ExecutionGate（NONE / risk0），成功后置 tainted</td><td>查信息</td></tr>
 *   <tr><td>web.read</td><td>web__read</td><td>读网页正文（截断 + 续读）</td><td>同上</td><td>查信息</td></tr>
 *   <tr><td>research.spawn</td><td>research__spawn</td><td>只读调研子任务（独立小循环，只能 search/read）</td><td>同上</td><td>查信息</td></tr>
 *   <tr><td>task.todo</td><td>task__todo</td><td>写 / 改子目标账本</td><td>控制类，不过闸（无副作用）</td><td>常驻控制类</td></tr>
 *   <tr><td>ask_user</td><td>ask_user</td><td>中途问人（2–4 选项，60 s）</td><td>控制类；无人值守不提供</td><td>常驻控制类</td></tr>
 *   <tr><td>end_not_done</td><td>end_not_done</td><td>做不成时带原因结束</td><td>控制类</td><td>常驻控制类</td></tr>
 * </table>
 *
 * <p><b>与 A 路的边界</b>：工具分档、候选排序、提示词资源化是 A 路的；这里只做实现 + 注册接入（{@link #append} 追加到本轮 tools 末尾）。
 * ★ arch/integrate：规则段已在 assets/agent_prompt.md（loop_tools 段）；web.* 归「查信息」档，task.todo / ask_user / end_not_done
 * 是常驻控制类（{@code ToolTiers.RESIDENT}），不参与排序 / 截断 / 折叠。
 * 工具说明借鉴 Mika prompts/tools/{ask_user,spawn_agent,end_not_done,web_read_page}.md（Apache-2.0，wangzan101/mika），中文改写。
 */
public final class LoopTools {

    private LoopTools() {}

    public static final String SIGN_TODO = "task.todo";
    public static final String SIGN_ASK = "ask_user";
    public static final String SIGN_END = "end_not_done";

    /** 本轮提供哪些常驻工具。 */
    public static final class Offer {
        public final boolean web;
        public final boolean ask;

        public Offer(boolean web, boolean ask) {
            this.web = web;
            this.ask = ask;
        }
    }

    public static boolean isLoopSign(String sign) {
        return isWebSign(sign) || isControlSign(sign);
    }

    /** 查信息类（走门禁 + 本地执行）。 */
    public static boolean isWebSign(String sign) {
        return WebTools.SIGN_SEARCH.equals(sign) || WebTools.SIGN_READ.equals(sign) || WebTools.SIGN_RESEARCH.equals(sign);
    }

    /** 控制类（循环内部处理，不过闸、不进宿主执行器）。 */
    public static boolean isControlSign(String sign) {
        return SIGN_TODO.equals(sign) || SIGN_ASK.equals(sign) || SIGN_END.equals(sign);
    }

    private static volatile Capability sSearch, sRead, sResearch, sTodo, sAsk, sEnd;

    /** 门禁 / 日志用的 Capability（不进注册表、不进 L1 技能缓存）。 */
    public static Capability describe(String sign) {
        if (sign == null) return null;
        if (WebTools.SIGN_SEARCH.equals(sign)) {
            if (sSearch == null) sSearch = readOnly(sign, "联网搜索（查资料）", "web");
            return sSearch;
        }
        if (WebTools.SIGN_READ.equals(sign)) {
            if (sRead == null) sRead = readOnly(sign, "读网页正文", "web");
            return sRead;
        }
        if (WebTools.SIGN_RESEARCH.equals(sign)) {
            if (sResearch == null) sResearch = readOnly(sign, "调研子任务", "web");
            return sResearch;
        }
        if (SIGN_TODO.equals(sign)) {
            if (sTodo == null) sTodo = readOnly(sign, "更新任务清单", "agent");
            return sTodo;
        }
        if (SIGN_ASK.equals(sign)) {
            if (sAsk == null) sAsk = readOnly(sign, "问你一个问题", "agent");
            return sAsk;
        }
        if (SIGN_END.equals(sign)) {
            if (sEnd == null) sEnd = readOnly(sign, "说明没办成的原因", "agent");
            return sEnd;
        }
        return null;
    }

    private static Capability readOnly(String sign, String summary, String domain) {
        return new Capability.Builder(sign, summary)
                .domain(domain).category("agent")
                .sideEffect(SideEffect.NONE).risk(0, false)
                .executor("custom")
                .schema(new ParamsSchema())
                .build();
    }

    // ------------------------------------------------------------------ 工具 JSON

    /** 把本轮提供的常驻工具追加到 tools 末尾（已存在同名就跳过）。 */
    public static void append(JSONArray tools, Offer offer) {
        if (tools == null || offer == null) return;
        try {
            if (offer.web) {
                put(tools, WebTools.SIGN_SEARCH,
                        "联网搜索外部世界的信息（资料、评测、价格、新闻、教程、官网地址）。返回几条结果（标题、链接、可信度、摘要）。"
                                + "摘要只是线索，重要结论再用 web.read 读原文。不要为了查资料去操作浏览器界面。",
                        obj("query", str("搜索关键词，简短具体"), "max", intg("最多几条，默认 5，最多 8")), new String[]{"query"});
                put(tools, WebTools.SIGN_READ,
                        "读一个网页的正文（不打开界面）。长页分段返回：把 next_offset 作为 offset 续读。结果带 verdict："
                                + "ok / thin（字太少）/ needs_sign_in（要登录）/ refused（拒绝或要验证）/ failed。"
                                + "京东 / 天猫 / 淘宝 / 值得买这类脚本渲染页会在后台用电脑版浏览器渲染后读，开头列出价格、店铺、是否自营 / 旗舰、链接、抓取时间；"
                                + "比价可以直接读站内搜索页：https://search.jd.com/Search?enc=utf-8&keyword=商品名 、https://search.smzdm.com/?c=home&v=b&s=商品名。"
                                + "要登录 / 验证的页面会跳过，不要替用户登录。",
                        obj("url", str("要读的网址（http/https）"), "offset", intg("从第几个字开始读，默认 0")), new String[]{"url"});
                put(tools, WebTools.SIGN_RESEARCH,
                        "派一个只读调研子任务去查一个边界清楚的问题：子任务只会搜索和读网页，不能问用户、不能操作手机或对外发送，"
                                + "最多几次模型调用，返回一段摘要和来源。子任务做完不等于用户的目标完成。",
                        obj("question", str("要调研的问题，写清范围和要的结论")), new String[]{"question"});
            }
            put(tools, SIGN_TODO,
                    "写或更新本任务的子目标清单。目标有 2 个及以上时先写清单；开始做某项标 doing，做完标 done，做不成标 failed。"
                            + "带已有 id 的项按 id 更新（可以只给 state）；replace=true 重写整个清单。",
                    obj("items", todoItems(), "replace", bool("是否先清空再写，默认 false")), new String[]{"items"});
            if (offer.ask) {
                put(tools, SIGN_ASK,
                        "向用户问一个问题：信息缺失、或者选择该由用户来做（人、物、数量、钱、二选一）时用；有几个明确答案时给 2–4 个选项。"
                                + "密码和验证码不在这里问。最多等 60 秒；返回 error=no_answer 表示没人回答（这不是答案）："
                                + "按最合理的理解继续并在答复里说明，或者 end_not_done。",
                        obj("question", str("给用户看的问题，一句话"), "options", strArr("可选：2–4 个答案选项")), new String[]{"question"});
            }
            put(tools, SIGN_END,
                    "拿不到要求的结果、或只能做成一部分时结束任务。message 写清：确认做成了什么、具体卡在哪、用户下一步可以怎么做。"
                            + "用户只是叫停时不要用它。",
                    obj("subtype", strEnum("失败类型", Outcome.END_SUBTYPES), "message", str("做了什么、卡在哪、下一步")),
                    new String[]{"message"});
        } catch (Exception ignored) {
        }
    }

    // ★ arch/integrate：原 promptSection（追加在 system 末尾的最小规则段）已搬进 assets/agent_prompt.md 的 loop_tools 段
    //   （needs: web.search / task.todo / ask_user / end_not_done 按挂载逐行取舍），Java 里不再留提示词文字。

    // ------------------------------------------------------------------ JSON 小工具

    private static void put(JSONArray tools, String sign, String desc, JSONObject props, String[] required) throws Exception {
        String wire = CapabilityRegistry.signToToolName(sign);
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            JSONObject fn = t == null ? null : t.optJSONObject("function");
            if (fn != null && wire.equals(fn.optString("name"))) return;
        }
        JSONObject params = new JSONObject();
        params.put("type", "object");
        params.put("properties", props);
        JSONArray req = new JSONArray();
        for (String r : required) req.put(r);
        params.put("required", req);
        JSONObject fn = new JSONObject();
        fn.put("name", wire);
        fn.put("description", desc);
        fn.put("parameters", params);
        JSONObject tool = new JSONObject();
        tool.put("type", "function");
        tool.put("function", fn);
        tools.put(tool);
    }

    private static JSONObject obj(Object... kv) throws Exception {
        JSONObject o = new JSONObject();
        for (int i = 0; i + 1 < kv.length; i += 2) o.put((String) kv[i], kv[i + 1]);
        return o;
    }

    private static JSONObject str(String d) throws Exception {
        return obj("type", "string", "description", d);
    }

    private static JSONObject intg(String d) throws Exception {
        return obj("type", "integer", "description", d);
    }

    private static JSONObject bool(String d) throws Exception {
        return obj("type", "boolean", "description", d);
    }

    private static JSONObject strArr(String d) throws Exception {
        return obj("type", "array", "description", d, "items", obj("type", "string"), "maxItems", 4);
    }

    private static JSONObject strEnum(String d, String[] values) throws Exception {
        JSONArray e = new JSONArray();
        for (String v : values) e.put(v);
        return obj("type", "string", "description", d, "enum", e);
    }

    private static JSONObject todoItems() throws Exception {
        JSONObject item = obj("type", "object", "properties", obj(
                "id", str("子目标编号，例如 g1；新项可省略"),
                "title", str("子目标，一句话"),
                "state", strEnum("状态", new String[]{"pending", "doing", "done", "failed"})));
        return obj("type", "array", "description", "子目标列表", "items", item);
    }
}
