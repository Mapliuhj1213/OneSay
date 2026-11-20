package com.onesay.desktop;

import io.roastedroot.quickjs4j.core.Builtins;
import io.roastedroot.quickjs4j.core.Engine;
import io.roastedroot.quickjs4j.core.Runner;
import run.endive.runtime.ByteArrayMemory;
import run.endive.wasm.types.MemoryLimits;

import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

/**
 * [workflow-1120] 代码节点 / JS 技能的沙箱：quickjs4j（QuickJS 编成 WASM，再转成纯 Java 字节码，没有 JNI）。
 *
 * <ul>
 *   <li><b>默认什么都碰不到</b>：没有 fetch / require / 文件 / 网络 / 环境变量 / 子进程；WASI 不给任何目录。</li>
 *   <li><b>限时</b>：超过 timeoutMs 中断（Runner 自带超时 + 线程中断）。</li>
 *   <li><b>限内存</b>：WASM 线性内存封顶 memMB（默认 32 MB，最多 128 MB）。</li>
 *   <li><b>宿主函数按权限给</b>：只有调用方明确传进来的（比如技能声明了 net 才有 onesay.http_get）才会出现在 JS 里。</li>
 * </ul>
 * 写法：代码里写 {@code function main(input) { return {...} }}；没写 main 就把整段当函数体（可以直接 return）。不支持 async。
 */
public final class WfCode {
    private WfCode() { }

    public static final int DEFAULT_TIMEOUT_MS = 3000, MAX_TIMEOUT_MS = 30_000, DEFAULT_MEM_MB = 32, MAX_MEM_MB = 128;
    static final int MAX_OUT = 1024 * 1024;

    /** 跑完的结果。ok=false 时 error 是给人看的中文；kind = timeout / memory / error / output。 */
    public static final class Result {
        public final boolean ok;
        public final Object value;
        public final String error, kind, log;
        public final long ms;

        Result(boolean ok, Object value, String error, String kind, String log, long ms) { this.ok = ok; this.value = value; this.error = error; this.kind = kind; this.log = log; this.ms = ms; }
    }

    /** 跑一段 JS。host = 额外的宿主函数（名字 → 字符串进字符串出），会挂在 JS 的 onesay 对象上；null / 空 = 一个都不给。 */
    public static Result run(String code, Object input, int timeoutMs, int memMB, Map<String, Function<String, String>> host) {
        long t0 = System.currentTimeMillis();
        int tmo = timeoutMs <= 0 ? DEFAULT_TIMEOUT_MS : Math.min(timeoutMs, MAX_TIMEOUT_MS);
        int mem = memMB <= 0 ? DEFAULT_MEM_MB : Math.min(memMB, MAX_MEM_MB);
        int capPages = mem * 16; // 64 KB 一页
        AtomicReference<String> out = new AtomicReference<>();
        Builtins rt = Builtins.builder("onesay_rt").addStringToString("result", s -> { if (s != null && s.length() <= MAX_OUT) out.set(s); else out.set("\u0000too_big"); return ""; }).build();
        Engine.Builder eb = Engine.builder().addBuiltins(rt)
                .withMemoryFactory(l -> new ByteArrayMemory(new MemoryLimits(l.initialPages(), Math.max(l.initialPages(), Math.min(l.maximumPages(), capPages)))));
        if (host != null && !host.isEmpty()) {
            Builtins.Builder hb = Builtins.builder("onesay");
            for (Map.Entry<String, Function<String, String>> e : host.entrySet()) {
                Function<String, String> f = e.getValue();
                hb.addStringToString(e.getKey(), s -> { try { String r = f.apply(s); return r == null ? "" : r; } catch (RuntimeException x) { return "{\"error\":" + Json.stringify(String.valueOf(x.getMessage())) + "}"; } });
            }
            eb.addBuiltins(hb.build());
        }
        String js = wrap(code, input);
        Engine engine = eb.build();
        try (Runner r = Runner.builder().withEngine(engine).withTimeoutMs(tmo).withCompilationTimeoutMs(Math.max(tmo, 5000)).build()) {
            try {
                r.compileAndExec(js);
            } catch (Throwable e) {
                String log = clip(safe(r::stdout), 4000);
                String msg = String.valueOf(e.getMessage());
                if (msg.contains("Timeout")) return new Result(false, null, "代码运行超时（超过 " + tmo + " 毫秒），已中断", "timeout", log, System.currentTimeMillis() - t0);
                if (msg.contains("out of memory") || e instanceof OutOfMemoryError) return new Result(false, null, "代码占用内存超过上限（" + mem + " MB），已中断", "memory", log, System.currentTimeMillis() - t0);
                return new Result(false, null, "代码出错：" + jsError(msg), "error", log, System.currentTimeMillis() - t0);
            }
            String log = clip(safe(r::stdout), 4000);
            String o = out.get();
            if (o == null) return new Result(false, null, "代码没有返回结果", "output", log, System.currentTimeMillis() - t0);
            if (o.equals("\u0000too_big")) return new Result(false, null, "代码返回的结果太大（超过 1 MB）", "output", log, System.currentTimeMillis() - t0);
            Object v;
            try { v = Json.parse(o); } catch (RuntimeException e) { return new Result(false, null, "代码返回的结果不是合法 JSON", "output", log, System.currentTimeMillis() - t0); }
            return new Result(true, v, null, "ok", log, System.currentTimeMillis() - t0);
        } catch (Throwable e) {
            return new Result(false, null, "代码沙箱启动失败：" + e.getClass().getSimpleName(), "error", "", System.currentTimeMillis() - t0);
        } finally {
            try { engine.close(); } catch (Throwable ignored) { }
        }
    }

    interface S { String get(); }

    static String safe(S s) { try { String v = s.get(); return v == null ? "" : v; } catch (Throwable e) { return ""; } }

    /** 包一层：注入 input，调 main，把结果交回 Java。用户代码自己的 console.log 进运行日志。 */
    static String wrap(String code, Object input) {
        String c = code == null ? "" : code;
        boolean hasMain = c.matches("(?s).*\\bfunction\\s+main\\s*\\(.*") || c.matches("(?s).*\\b(const|let|var)\\s+main\\s*=.*");
        StringBuilder sb = new StringBuilder();
        sb.append("const input = JSON.parse(").append(Json.stringify(Json.stringify(input))).append(");\n");
        if (hasMain) sb.append(c).append("\n");
        else sb.append("function main(input) {\n").append(c).append("\n}\n");
        sb.append(";(function(){ const __r = main(input);\n")
          .append(" if (__r && typeof __r.then === 'function') throw new Error('这一版不支持 async / Promise');\n")
          .append(" onesay_rt.result(JSON.stringify(__r === undefined ? null : __r));\n})();\n");
        return sb.toString();
    }

    static String jsError(String m) {
        String s = m == null ? "" : m;
        int i = s.indexOf("Error:");
        if (i >= 0) {
            String rest = s.substring(i);
            int nl = rest.indexOf('\n');
            s = nl > 0 ? rest.substring(0, nl) : rest;
        }
        return clip(s.trim(), 300);
    }

    static String clip(String s, int n) { return s == null ? "" : s.length() <= n ? s : s.substring(0, n) + "…"; }

    /** 结果统一成对象：返回的不是对象就包成 {result: 值}。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asOutput(Object v) {
        if (v instanceof Map) return (Map<String, Object>) v;
        Map<String, Object> m = new TreeMap<>();
        m.put("result", v);
        return m;
    }
}
