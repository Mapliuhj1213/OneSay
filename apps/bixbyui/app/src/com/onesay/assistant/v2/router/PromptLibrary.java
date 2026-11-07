package com.onesay.assistant.v2.router;

import android.content.Context;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.Map;

/**
 * 提示词库 —— 模板**外部化 + 热加载**。
 *
 * <h3>要解决的痛点（用户 2026-10-04 指出）</h3>
 * <p>旧实现把提示词硬编码成 Java 字符串常量（见 {@code L2LLMCompiler#buildCompilerPrompt}）。
 * 后果：<b>改一个字都要重编译 APK、重装、重启服务</b>。
 * 而提示词是**要反复试错的东西**（"让模型偏好某类输出"本质就是调 prompt），
 * 把它焊死在代码里，等于每次调参都要走一遍完整的构建部署链 —— 这是不可接受的。
 *
 * <h3>热加载设计</h3>
 * <pre>
 *   设备路径：/data/data/com.onesay.assistant/files/prompts/
 *       └─ {模板名}.txt      ← 用 adb push 推文件即改即生效
 *
 *   加载时机：
 *     ① 首次 get() 时懒加载
 *     ② 收到广播 --ez reload_prompts true 时强制重载
 *
 *   兜底：文件不存在/读失败 → 用**内置默认模板**（见 {@link Defaults}）。
 *        这样"删了文件"或"推了个坏文件"都不会让系统瘫痪，只是回退到出厂话术。
 * </pre>
 *
 * <h3>两套模板 + 可扩展（用户定的粒度）</h3>
 * <pre>
 *   ask.output    —— ASK 通道的**输出模板**（让回答偏好某种形态）
 *   do.manual     —— DO 通道的**操作规程手册**（角色扮演 + SOP）
 *   未来：可按任务类型再加 ask.output.weather / do.manual.store …
 *        —— get(name) 按名取，缺哪个用哪个的默认，架构上天然可扩展。
 * </pre>
 *
 * <h3>变量占位</h3>
 * <p>模板里用 {@code {{var}}} 占位（双花括号，避免与能力参数的 {n} 混淆）。
 * 由调用方传入 Map 完成替换；未提供的变量保持原样（便于发现漏配）。
 */
public final class PromptLibrary {

    private static final String TAG = "PromptLibrary";
    private static final String DIR_NAME = "prompts";

    /** 模板名常量（调用方只用这些 key）。 */
    public static final String T_ASK_OUTPUT = "ask.output";
    public static final String T_DO_MANUAL  = "do.manual";

    private static volatile PromptLibrary sInstance;

    private final Context mContext;
    private final Map<String, String> mCache = new HashMap<String, String>();
    private volatile boolean mLoaded = false;

    /** 每次重载计数（供自测/日志确认热加载真的生效了）。 */
    private volatile int mLoadGeneration = 0;

    private PromptLibrary(Context ctx) {
        this.mContext = ctx.getApplicationContext();
    }

    public static PromptLibrary getInstance(Context ctx) {
        if (sInstance == null) {
            synchronized (PromptLibrary.class) {
                if (sInstance == null) sInstance = new PromptLibrary(ctx);
            }
        }
        return sInstance;
    }

    /**
     * 取模板原文（未替换变量）。
     *
     * @param name 模板名（如 {@link #T_ASK_OUTPUT}）
     * @return 模板文本；永不为 null（文件缺失时回退内置默认）
     */
    public String get(String name) {
        ensureLoaded();
        String t = mCache.get(name);
        if (t != null && t.trim().length() > 0) return t;
        String def = Defaults.get(name);
        return def != null ? def : "";
    }

    /**
     * 取模板并替换变量。
     *
     * @param vars 变量表（key 不带花括号，如 "capabilities"）；可为 null
     */
    public String render(String name, Map<String, String> vars) {
        String t = get(name);
        if (vars == null || vars.isEmpty()) return t;
        String out = t;
        for (Map.Entry<String, String> e : vars.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            out = out.replace("{{" + e.getKey() + "}}", e.getValue());
        }
        return out;
    }

    /**
     * 强制从磁盘重载（广播 --ez reload_prompts true 触发）。
     *
     * @return 成功读到的模板数
     */
    public synchronized int reload() {
        mCache.clear();
        mLoaded = false;
        ensureLoaded();
        mLoadGeneration++;
        Log.i(TAG, "[PROMPT] 热加载完成 gen=" + mLoadGeneration
                + " 共 " + mCache.size() + " 个文件模板（其余走内置默认）");
        return mCache.size();
    }

    public int generation() {
        return mLoadGeneration;
    }

    /** 当前磁盘模板名列表（供自测断言）。 */
    public java.util.Set<String> loadedNames() {
        ensureLoaded();
        return new java.util.HashSet<String>(mCache.keySet());
    }

    private void ensureLoaded() {
        if (mLoaded) return;
        synchronized (this) {
            if (mLoaded) return;
            loadFromDisk();
            mLoaded = true;
        }
    }

    private void loadFromDisk() {
        try {
            File dir = new File(mContext.getFilesDir(), DIR_NAME);
            if (!dir.exists()) {
                dir.mkdirs();
                Log.i(TAG, "[PROMPT] 模板目录不存在，已创建: " + dir.getAbsolutePath());
                return;
            }
            File[] files = dir.listFiles();
            if (files == null) return;
            for (File f : files) {
                if (f == null || !f.isFile()) continue;
                String nm = f.getName();
                if (!nm.endsWith(".txt")) continue;
                String key = nm.substring(0, nm.length() - 4);
                String body = readAll(f);
                if (body != null && body.trim().length() > 0) {
                    mCache.put(key, body);
                    Log.i(TAG, "[PROMPT] 装载模板 " + key + " (" + body.length() + " 字)");
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "[PROMPT] 读取模板目录失败，全部走内置默认: " + t.getMessage());
        }
    }

    private static String readAll(File f) {
        FileInputStream in = null;
        BufferedReader r = null;
        try {
            in = new FileInputStream(f);
            r = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        } catch (Throwable t) {
            return null;
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
            try { if (in != null) in.close(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 内置默认模板 —— 文件缺失时的兜底。
     *
     * <p>这些默认值就是"出厂话术"。用户把同名 .txt 推到设备目录即覆盖，
     * 删掉就回退到这里。所以**调 prompt 不需要改代码**。
     */
    public static final class Defaults {

        private Defaults() {}

        public static String get(String name) {
            if (T_ASK_OUTPUT.equals(name)) return ASK_OUTPUT;
            if (T_DO_MANUAL.equals(name)) return DO_MANUAL;
            return null;
        }

        /** ASK 通道输出模板：约束回答的**形态**（让模型偏好某种输出）。 */
        public static final String ASK_OUTPUT =
                "你是智能手机语音助手的回答者。用户的问题只需要你**直接给出答案**。\n"
              + "\n"
              + "【回答形态要求】\n"
              + "1. 第一句就是结论，不铺垫、不寒暄、不说\"根据您的问题\"；内容多时分成短段落（适合屏幕阅读）。\n"
              + "2. 默认简短：1~3 句、约 80 字以内，简单事实一句就够，不为凑字数注水；用户明确要「详细 / 展开」时最多 {{maxChars}} 字。\n"
              + "3. 不使用 Markdown 标题、代码块、项目符号。\n"
              + "4. 涉及实时数据（天气/股价/新闻）且你没有拿到资料时，"
              + "如实说\"我没查到\"，**严禁编造**任何数值；"
              + "日期、星期、钟点一律以下方【当前时间】为准直接回答（那是本机实时读数，不算编造）。\n"
              + "5. 用户在问\"怎么操作手机\"时，不要展开成操作教程 —— "
              + "本系统会真的去执行，你只需要回答知识性问题。\n"
              + "6. **本机实时数据**（电量/亮度/音量/连接状态/时间…）："
              + "如果下方《本机可读数据》里列出了对应读数，**必须用那个读数回答**，"
              + "不要答\"我读不到\"、不要教用户自己去状态栏看；"
              + "只有该数据**不在**清单里时，才如实说你这里读不到。\n"
              + "\n"
              + "{{now}}"
              + "{{deviceData}}"
              + "{{searchContext}}"
              + "用户问题：{{query}}\n";

        /** DO 通道操作规程手册：角色扮演 + SOP（含"先读再执行"）。 */
        public static final String DO_MANUAL =
                "你是一台安卓手机的操作员。你的职责是**把用户的口头指令翻译成对手机的精确操作**。\n"
              + "你不是聊天助手 —— 用户说的是要做的事，你要去把它做掉。\n"
              + "\n"
              + "【角色铁律】\n"
              + "1. 你只能使用下方《接口手册》里登记过的接口，**严禁**自己编造接口名或 shell 命令。\n"
              + "2. 你的输出是**结构化调用**，不是解释、不是教程、不是道歉。\n"
              + "3. 参数只能照抄用户明确说出的信息。**必填参数缺失时，绝不猜值** —— "
              + "改用 ask.reply 反问用户（例：用户说\"定个闹钟\"没说几点 → 反问\"几点？\"）。\n"
              + "4. 用户要的如果本机接口做不到，如实说明做不到，不要假装做了。\n"
              + "\n"
              + "【操作规程（SOP）】\n"
              + "① 先判类型：翻《接口手册》确认这类事属于哪一类接口。\n"
              + "   - 查询类（只读）→ 可直接调用。\n"
              + "   - 修改/新增/删除类 → **必须先查后改**：先找一个查询接口读出当前状态，"
              + "确认现状与预期一致，再执行修改。\n"
              + "     ★ **例外：相对方向命令不用先查**。用户说的是「调低点 / 调高些 / 加大一点 / "
              + "减小一些」这类**相对方向**时，目标已经明确（就是让它往那个方向走），"
              + "直接调对应的修改接口即可，**不要先读一遍现状**。\n"
              + "     只有「依赖现状才能决定」的请求（如「如果还没到晚上就把亮度调到最低」）才必须先查。\n"
              + "   - 调用界面类 → 只负责拉起界面，**不要在回复里说\"已完成\"** "
              + "（界面打开≠任务完成，用户还要自己在里面操作）。\n"
              + "② 找目标：需要文件/组件/包名时，**先用查询类接口去探**"
              + "（如 resolve-activity 探组件、dumpsys 探包名），不要凭记忆填。\n"
              + "③ 出错怎么办：\n"
              + "   - 接口执行失败 → 换手册里语义相近的**备用接口**再试一次；\n"
              + "   - 全部失败 → 如实告诉用户失败原因，不要虚构成功。\n"
              + "   - 路径/组件不存在 → 不要瞎猜路径，改用查询接口重新探测。\n"
              + "④ 何时问用户：\n"
              + "   - 必填参数缺失（时间、对象、数量）→ 必须问，绝不默认。\n"
              + "     · **时间参数的例外**：句中只要出现过时间（含“40分钟后”“两小时后”这类"
              + "相对时间），就算已经给过 —— 换算成绝对时刻直接填，不要再问。\n"
              + "   - 操作不可逆（删除/卸载）且用户表述含糊 → 先问清对象再动手。\n"
              + "   - 其余情况直接做，不要过度确认。\n"
              + "\n"
              + "【输出格式（唯一允许）】单步：\n"
              + "{\"sign\": \"接口标识\", \"args\": {参数键值对}}\n"
              + "多步（一句话里有多件事）：\n"
              + "{\"mode\": \"mixed\", \"steps\": [{\"sign\": \"…\", \"args\": {…}}, …]}\n"
              + "纯问答时才用：{\"sign\": \"ask.reply\", \"args\": {\"text\": \"…\"}}\n"
              + "\n"
              + "{{now}}"
              + "{{entityIndex}}"
              + "【接口手册（可用接口清单）】\n"
              + "{{capabilities}}\n";
    }
}
