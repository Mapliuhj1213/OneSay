package com.onesay.assistant.v2.capability;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 能力注册表（系统核心 Registry，支持自省、动态查询与契约校验）。
 * 
 * 对标魅族小溪的 AtomicAbility.apk 注册表能力：
 * 1. get_abilities（全量能力自省）
 * 2. query_abilities_details（根据 sign 描述能力签名与参数 Schema）
 * 3. 统一路由与执行支撑。
 */
public class CapabilityRegistry {

    private static volatile CapabilityRegistry sInstance;

    private final Map<String, Capability> mSignMap = new LinkedHashMap<String, Capability>();
    private final List<Capability> mAllCapabilities = new ArrayList<Capability>();

    /**
     * 只读探针能力（probe.*）。
     * 单独存放、不进 {@link #mAllCapabilities} —— 它们**可被 describe() 解析、
     * 可被 AgentLoop 执行，但不出现在普通召回清单里**（Top-K / L0 / 关键词倒排都不受污染）。
     */
    private final List<Capability> mProbeCapabilities = new ArrayList<Capability>();

    /** 挖掘资产是否已加载（幂等标记，避免重复注册）。 */
    private boolean mMinedLoaded = false;

    /** 本次启动从挖掘资产注册的条数（自省/自测用）。 */
    private int mMinedCount = 0;

    public static CapabilityRegistry getInstance() {
        if (sInstance == null) {
            synchronized (CapabilityRegistry.class) {
                if (sInstance == null) {
                    sInstance = new CapabilityRegistry();
                    BuiltinCapabilities.registerAll(sInstance);
                    // ★ fix/triage-fast：快路径模板用到的系统动作（导航键 / 媒体键 / 清空后台 / 勿扰…），不进内置快照
                    com.onesay.assistant.v2.capability.caps.FastCaps.register(sInstance);
                    // 注册只读探针（probe.*，不进普通召回，供 AgentLoop 探索族使用）
                    try {
                        com.onesay.assistant.v2.probe.ProbeCapabilities.registerAll(sInstance);
                    } catch (Throwable t) {
                        Log.w("CapabilityRegistry", "探针能力注册失败: " + t.getMessage());
                    }
                }
            }
        }
        return sInstance;
    }

    /**
     * 注入 Context 以加载**自动挖掘**的系统接口资产。
     *
     * <h3>为什么单独开一个 init 而不是塞进 getInstance</h3>
     * <p>{@link #getInstance()} 有 10 余个调用点，绝大多数（路由器、自测、UI）
     * 拿不到也不该拿 Context；若强行给 getInstance 加参数，会污染整条调用链。
     * 因此保持 getInstance 的零参签名不变，另开一个**可选**的 init：
     * 由进程入口（MainActivity / WakeService）在启动时调一次，
     * 把 assets 里的 {@code mined_intents.json} 注册进来。
     *
     * <p><b>没调 init 也不影响</b>：手写的 {@link BuiltinCapabilities} 已在
     * getInstance 里注册完毕，挖掘接口是**增量**而非基本盘。
     * 幂等：重复调用只生效一次。
     *
     * @param context 上下文（读 assets 用）
     * @return 本次（或此前）已加载的挖掘接口条数
     */
    public synchronized int init(Context context) {
        if (mMinedLoaded) return mMinedCount;
        mMinedLoaded = true;
        try {
            mMinedCount = MinedCapabilities.registerAll(this, context);
        } catch (Throwable t) {
            mMinedCount = 0;
        }
        // 叠加**运行时**挖掘资产（probe.app_intents 挖到后落盘的文件）——
        //   上次会话挖到的界面接口，这次启动直接可用（与离线资产同构、同一条构造链）。
        try {
            int rt = MinedCapabilities.registerRuntime(this, context);
            if (rt > 0) Log.i("CapabilityRegistry", "运行时挖掘接口叠加注册: " + rt + " 条");
        } catch (Throwable t) {
            Log.w("CapabilityRegistry", "运行时挖掘资产加载失败: " + t.getMessage());
        }
        return mMinedCount;
    }

    /** 已加载的挖掘接口条数（未加载则为 0）。 */
    public int minedCount() {
        return mMinedCount;
    }

    public synchronized void register(Capability capability) {
        if (capability == null) return;
        mSignMap.put(capability.sign, capability);
        mAllCapabilities.add(capability);
    }

    /**
     * 注册只读探针能力（probe.*）。
     *
     * <p>与 {@link #register} 的区别：探针**不进** {@link #mAllCapabilities}——
     * 它可被 {@link #describe}</b> 解析（AgentLoop 执行链路需要）、可被
     * {@link #listProbes()} 取出注入"探索族"，但不出现在普通召回的
     * {@link #listCapabilities()} 里（Top-K / L0 / 关键词倒排均不受污染）。
     */
    public synchronized void registerProbe(Capability capability) {
        if (capability == null) return;
        mSignMap.put(capability.sign, capability);
        mProbeCapabilities.add(capability);
    }

    /** ★ AgentLoop"探索族"用：全部只读探针能力（普通召回永远看不到它们）。 */
    public List<Capability> listProbes() {
        return Collections.unmodifiableList(mProbeCapabilities);
    }

    /**
     * 方案 A 关键词倒排索引查询（毫秒级、零网络）。
     * 返回命中的能力 sign（按命中词数降序）；空表或未命中返回空列表。
     */
    public List<KeywordIndex.Hit> keywordHits(String utterance) {
        KeywordIndex idx = KeywordIndex.getInstance();
        idx.ensureBuilt(mAllCapabilities);
        return idx.query(utterance);
    }

    /**
     * 能力自省：返回所有已注册能力的只读清单（对标小溪 get_abilities）。
     */
    public List<Capability> listCapabilities() {
        return Collections.unmodifiableList(mAllCapabilities);
    }

    /**
     * 能力自省：查询特定签名的详细能力契约（对标小溪 query_abilities_details）。
     */
    public Capability describe(String sign) {
        if (sign == null) return null;
        return mSignMap.get(sign);
    }

    /**
     * 导出为 LLM Tool 规范定义数组（OpenAI / DeepSeek function calling 格式）。
     *
     * <h3>补上 {@code "type":"function"} 外壳</h3>
     * <p>此方法自 2026-10-03 就写好了，但**从未被任何代码调用**（死代码），
     * 也因此没暴露一个致命格式错误：初版直接把 {@code {name, description, parameters}}
     * 平铺进数组，而 function calling 规范要求每项必须是
     * {@code {"type":"function","function":{name, description, parameters}}}。
     * 直接塞进请求体的话，DeepSeek 会报 400。
     *
     * <p>本次接入 tools 通道时一并修正，并新增按候选集导出的重载
     * {@link #exportToolsSchema(List)} —— 全量 238 条太大，实际只导出 Top-K。
     */
    public JSONArray exportToolsSchema() {
        return exportToolsSchema(mAllCapabilities);
    }

    /**
     * 把能力签名转成**端点合法的 tool name**。
     *
     * <h3>为什么必须转（真机实测 400 才发现的坑）</h3>
     * <p>DeepSeek（同 OpenAI）对 function name 有硬约束：
     * <pre>^[a-zA-Z0-9_-]+$</pre>
     * 而我们的签名**一律是点号分隔**（{@code battery.level} / {@code connectivity.wifi.on}）。
     * 直接把这些签名当 tool name 塞进 {@code tools}，端点立刻还一个 400：
     * <pre>Invalid 'tools[0].function.name': string does not match pattern.</pre>
     *
     * <p>对策：**wire 上用下划线**（{@code battery_level}），
     * 模型回传后再 {@link #toolNameToSign(String)} 换回真实签名。
     * 转换是**可逆双射**（我们的签名里不存在下划线，见 {@code BuiltinCapabilities} 与
     * {@code MinedCapabilities} 的签名生成规则），所以不会撞名。
     *
     * <p>sign 里若本就有下划线（如挖掘口的 {@code clock.set_alarm}？—— 实际是点号分隔，
     * 段内才可能有下划线，如 {@code clock.show_alarms}）—— 这种段内下划线在
     * {@link #toolNameToSign} 里无法与"点号转换来的下划线"区分。
     * 因此这里改用**双下划线**做点号替身，段内单下划线原样保留，保证可逆。
     */
    public static String signToToolName(String sign) {
        if (sign == null) return "";
        return sign.replace(".", "__");
    }

    /** 见 {@link #signToToolName(String)}：把 wire 上的 tool name 还原为真实签名。 */
    public static String toolNameToSign(String toolName) {
        if (toolName == null) return "";
        return toolName.replace("__", ".");
    }

    /**
     * 按指定候选集导出 Tool 规范定义数组。
     *
     * @param caps 候选能力（通常是 CapabilityRetriever 粗筛后的 Top-K）；null/空 → 返回空数组
     */
    public JSONArray exportToolsSchema(List<Capability> caps) {
        JSONArray array = new JSONArray();
        if (caps == null) return array;
        for (Capability cap : caps) {
            if (cap == null) continue;
            try {
                JSONObject fn = new JSONObject();
                // ★ 必须用合法 tool name（点号会被端点 400 拒掉），见 signToToolName
                fn.put("name", signToToolName(cap.sign));
                fn.put("description", cap.summary != null ? cap.summary : cap.sign);
                fn.put("parameters", cap.paramsSchema.toJsonSchema());

                JSONObject tool = new JSONObject();
                tool.put("type", "function");
                tool.put("function", fn);
                array.put(tool);
            } catch (Exception ignored) {}
        }
        return array;
    }

    /**
     * 根据关键词或业务域检索能力。
     */
    public List<Capability> search(String query) {
        List<Capability> results = new ArrayList<Capability>();
        if (query == null || query.trim().length() == 0) return results;
        String q = query.trim().toLowerCase();
        for (Capability cap : mAllCapabilities) {
            if (cap.sign.toLowerCase().contains(q)
                    || cap.summary.contains(q)
                    || cap.category.toLowerCase().contains(q)) {
                results.add(cap);
            }
        }
        return results;
    }

    public int size() {
        return mSignMap.size();
    }

    // ============================================================
    // 接口数据库视图（新增）
    //
    // 用户定的分类体系：一级按软件、二级按接口类型（增删改查 / 调用UI 分列）。
    // 这不是"再建一份数据"，而是把注册表里**已有的能力**按两轴重新索引 ——
    // 单一真源仍是 mAllCapabilities，本区只是它的两种读法。
    //
    // 两种消费方：
    //   ① LLM（DO 手册）：需要"软件 → 类型 → 接口+参数+用法"的分层清单
    //   ② 执行层：需要按类型分派（QUERY 直接跑；MUTATE 先读再写；UI 走 intent）
    // ============================================================

    /**
     * 一级分类：软件 → 该软件下的所有能力（保持注册顺序）。
     */
    public Map<String, List<Capability>> bySoftware() {
        Map<String, List<Capability>> out = new LinkedHashMap<String, List<Capability>>();
        for (Capability cap : mAllCapabilities) {
            String sw = InterfaceKind.softwareOf(cap);
            List<Capability> list = out.get(sw);
            if (list == null) {
                list = new ArrayList<Capability>();
                out.put(sw, list);
            }
            list.add(cap);
        }
        return out;
    }

    /**
     * 二级分类：软件 → 接口类型 → 该类型下的能力。
     *
     * <p>层级深度固定两层，因为用户的分类意图就是"一级软件、二级接口类型"。
     */
    public Map<String, Map<InterfaceKind.Kind, List<Capability>>> bySoftwareAndKind() {
        Map<String, Map<InterfaceKind.Kind, List<Capability>>> out =
                new LinkedHashMap<String, Map<InterfaceKind.Kind, List<Capability>>>();
        for (Capability cap : mAllCapabilities) {
            String sw = InterfaceKind.softwareOf(cap);
            InterfaceKind.Kind kind = InterfaceKind.of(cap);
            Map<InterfaceKind.Kind, List<Capability>> byKind = out.get(sw);
            if (byKind == null) {
                byKind = new LinkedHashMap<InterfaceKind.Kind, List<Capability>>();
                out.put(sw, byKind);
            }
            List<Capability> list = byKind.get(kind);
            if (list == null) {
                list = new ArrayList<Capability>();
                byKind.put(kind, list);
            }
            list.add(cap);
        }
        return out;
    }

    /** 只按二级类型分组（扁平视图，供执行层按类型分派）。 */
    public Map<InterfaceKind.Kind, List<Capability>> byKind() {
        Map<InterfaceKind.Kind, List<Capability>> out =
                new LinkedHashMap<InterfaceKind.Kind, List<Capability>>();
        for (Capability cap : mAllCapabilities) {
            InterfaceKind.Kind kind = InterfaceKind.of(cap);
            List<Capability> list = out.get(kind);
            if (list == null) {
                list = new ArrayList<Capability>();
                out.put(kind, list);
            }
            list.add(cap);
        }
        return out;
    }

    /**
     * 导出接口数据库为**分层 JSON**（供提示词注入 / 落盘 / 审计）。
     *
     * <pre>
     * {
     *   "settings": {
     *      "查询": [ {sign, summary, params, usage} … ],
     *      "修改": [ … ]
     *   },
     *   "store": { "调用界面": [ … ] }
     * }
     * </pre>
     */
    public JSONObject exportInterfaceDb() {
        JSONObject root = new JSONObject();
        Map<String, Map<InterfaceKind.Kind, List<Capability>>> tree = bySoftwareAndKind();
        try {
            for (Map.Entry<String, Map<InterfaceKind.Kind, List<Capability>>> swEntry : tree.entrySet()) {
                JSONObject swObj = new JSONObject();
                for (Map.Entry<InterfaceKind.Kind, List<Capability>> kEntry : swEntry.getValue().entrySet()) {
                    JSONArray arr = new JSONArray();
                    for (Capability cap : kEntry.getValue()) {
                        arr.put(cap.toInterfaceJson());
                    }
                    swObj.put(kEntry.getKey().name(), arr);
                }
                root.put(swEntry.getKey(), swObj);
            }
        } catch (Exception ignored) {}
        return root;
    }
}
