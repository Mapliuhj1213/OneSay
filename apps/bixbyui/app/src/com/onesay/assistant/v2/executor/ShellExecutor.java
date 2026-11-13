package com.onesay.assistant.v2.executor;

import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;

/**
 * Root 守护特权通道执行器（基于文件 IPC）。
 *
 * 核心设计：
 * 1. 业务逻辑完全不感知 root / su / 守护的存在；
 * 2. 绕开 Magisk 单应用 su 损坏缺陷，完全走常驻守护通信；
 * 3. 具备请求自检与超时熔断保护（12 秒上限）。
 */
public class ShellExecutor implements ShellRunner {

    private static final String TAG = "ShellExecutor";
    /**
     * 审计 B-2：全局 IPC 锁从 synchronized 改成可 tryLock 的 ReentrantLock。
     * 普通命令照旧排队（{@link #execute}）；轮询类只读命令用 {@link #executePoll}：
     * 拿不到锁立即返回 BUSY（调用方跳过本轮），拿到锁也只等 {@link #POLL_TIMEOUT_MS}。
     */
    private static final java.util.concurrent.locks.ReentrantLock IPC_LOCK =
            new java.util.concurrent.locks.ReentrantLock();

    private static final long TIMEOUT_MS = 12000;
    private static final long PICKUP_MS = 1500;
    /** 轮询类只读命令的上限（≤1.5 s），见 B-2。 */
    public static final long POLL_TIMEOUT_MS = 1500;

    private final Context mContext;

    public static class ShellResponse {
        public final int rc;
        public final String output;
        public final boolean isTimeout;
        public final boolean isDown;
        /** B-2：轮询命令拿不到 IPC 锁、本轮被跳过（命令根本没发出去）。 */
        public final boolean isBusy;
        /**
         * 2026-10-25 exec 守护 v4：被守护拒绝（回执 rc=77 + {@code DENY <原因>}）——命令根本没执行，
         * 不是「命令失败」。{@link #output} 是给人看的说明（{@link ExecProtocol#describeDeny}）。
         */
        public final boolean isDenied;
        /** 守护给的拒绝原因（如 {@code PROG_NOT_WHITELISTED(sh)}、{@code SIG_MISMATCH}）；没被拒为空串。 */
        public final String denyReason;

        public ShellResponse(int rc, String output, boolean isTimeout, boolean isDown) {
            this(rc, output, isTimeout, isDown, false);
        }

        public ShellResponse(int rc, String output, boolean isTimeout, boolean isDown, boolean isBusy) {
            this(rc, output, isTimeout, isDown, isBusy, null);
        }

        /** @param denyReason 非 null = 被守护拒绝（rc 应为 77）。 */
        public ShellResponse(int rc, String output, boolean isTimeout, boolean isDown, boolean isBusy, String denyReason) {
            this.rc = rc;
            this.output = output != null ? output : "";
            this.isTimeout = isTimeout;
            this.isDown = isDown;
            this.isBusy = isBusy;
            this.isDenied = denyReason != null;
            this.denyReason = denyReason != null ? denyReason : "";
        }

        /** 守护拒绝的响应。 */
        public static ShellResponse denied(String reason) {
            String r = reason == null ? "" : reason;
            return new ShellResponse(ExecProtocol.RC_DENIED, ExecProtocol.describeDeny(r), false, false, false, r);
        }

        /** 「通道正忙，本轮跳过」的响应（rc=-2）。 */
        public static ShellResponse busy() {
            return new ShellResponse(-2, "通道正忙，跳过本轮", false, false, true);
        }

        public boolean isSuccess() {
            return rc == 0 && !isTimeout && !isDown;
        }
    }

    public ShellExecutor(Context context) {
        this.mContext = context.getApplicationContext();
        com.onesay.assistant.v2.platform.ExecBackendHost.ensureInit(this.mContext);
    }

    /** OneSay 开源版：用户选了非守护的执行后端（su 直连 / Shizuku）且可用时，命令交给它；否则 null 照旧走守护。 */
    private static ShellResponse viaBackend(String command, long timeoutMs) {
        // ★ priv-llm：执行档位 su / root 守护 / Shizuku 特权（白名单）/ 原守护，见 ExecTierRouter
        com.onesay.assistant.v2.platform.ExecTierRouter.Route rt = com.onesay.assistant.v2.platform.ExecBackendHost.route(command);
        if (rt.kind == com.onesay.assistant.v2.platform.ExecTierRouter.Kind.DENY) return ShellResponse.denied(rt.denyReason);
        if (rt.kind == com.onesay.assistant.v2.platform.ExecTierRouter.Kind.DAEMON) return null;
        com.onesay.plugin.api.exec.ExecResult r = rt.backend.run(command, timeoutMs);
        if (r.rc == ExecProtocol.RC_DENIED && r.output.startsWith("DENY ")) return ShellResponse.denied(r.output.substring(5).trim());
        return new ShellResponse(r.rc, r.output, r.timedOut, r.backendDown);
    }

    /**
     * 经由 root 守护执行指定 shell 指令。
     *
     * @param command shell 指令行
     * @return 结构化 Shell 响应
     */
    @Override
    public ShellResponse execute(String command) {
        if (command == null || command.trim().length() == 0) {
            return new ShellResponse(0, "", false, false);
        }
        ShellResponse viaB = viaBackend(command, TIMEOUT_MS);
        if (viaB != null) return viaB;
        IPC_LOCK.lock();
        try {
            return runLocked(command, TIMEOUT_MS);
        } finally {
            IPC_LOCK.unlock();
        }
    }

    /**
     * 审计 B-2：轮询类只读命令（例如灵动岛全屏检测的 dumpsys）。不排队：IPC 锁被别的命令占着就立即返回
     * {@link ShellResponse#busy()}；拿到锁最多等 {@link #POLL_TIMEOUT_MS}。<b>只用于只读命令。</b>
     */
    public ShellResponse executePoll(String command) {
        if (command == null || command.trim().length() == 0) {
            return new ShellResponse(0, "", false, false);
        }
        ShellResponse viaB = viaBackend(command, POLL_TIMEOUT_MS);
        if (viaB != null) return viaB;
        if (!IPC_LOCK.tryLock()) return ShellResponse.busy();
        try {
            return runLocked(command, POLL_TIMEOUT_MS);
        } finally {
            IPC_LOCK.unlock();
        }
    }

    private ShellResponse runLocked(String command, long timeoutMs) {
        // 2026-10-25 exec 守护 v4：v4 不执行复合脚本。只由 && / ; 连起来的简单命令链（如 stat … && cat …）在这里拆成
        //   单条依次执行（仍在同一把 IPC 锁里，对其它调用方而言整条链是原子的）；守护固定模板原样发。见 ExecProtocol.splitChain。
        java.util.List<String[]> chain = ExecProtocol.splitChain(command);
        if (chain != null) return runChain(chain, timeoutMs);
        return runSingle(command, timeoutMs);
    }

    /** 简单命令链的 shell 语义：{@code a && b} 前一段失败就跳过；{@code ;} 照跑。输出按 \n 连接，rc = 最后执行那段。 */
    private ShellResponse runChain(java.util.List<String[]> chain, long timeoutMs) {
        StringBuilder out = new StringBuilder();
        int rc = 0;
        boolean timeout = false;
        String deny = null;
        for (String[] seg : chain) {
            if ("&&".equals(seg[0]) && rc != 0) continue;
            String o;
            if (ExecProtocol.isLocal(seg[1])) {
                String[] l = ExecProtocol.runLocal(seg[1]);
                rc = Integer.parseInt(l[0]);
                o = l[1];
            } else {
                ShellResponse r = runSingle(seg[1], timeoutMs);
                if (r.isDown) {
                    if (out.length() > 0 && r.output.length() > 0) out.append('\n');
                    out.append(r.output);
                    return new ShellResponse(r.rc, out.toString(), timeout, true);
                }
                rc = r.rc;
                o = r.output;
                timeout |= r.isTimeout;
                if (r.isDenied && deny == null) deny = r.denyReason;
            }
            if (o != null && o.length() > 0) {
                if (out.length() > 0) out.append('\n');
                out.append(o);
            }
        }
        Log.i(TAG, "[EXEC] 简单命令链已拆成 " + chain.size() + " 段执行");
        return new ShellResponse(rc, out.toString(), timeout, false, false, deny);
    }

    private ShellResponse runSingle(String command, long timeoutMs) {
        File dir = mContext.getFilesDir();
        // 2026-10-25 exec 守护 v4（fix-root.md §4-1）：有守护交付的密钥就发结构化签名请求（分不开的命令仍发明文）
        String key = ExecProtocol.readKey(dir);
        String body = ExecProtocol.requestBody(command, key, System.currentTimeMillis() / 1000L);
        ShellResponse r = runOnce(command, body, timeoutMs);
        if (r.isDenied && !body.equals(command) && ExecProtocol.isWhitelistDeny(r.denyReason)) {
            // 过渡期：结构化请求被白名单拒了 → 用 legacy 明文重发一次。守护只在 root 开了 allow_raw 时才会放行 legacy
            //   （记 WARN LEGACY_RAW）；没开就同样 DENY。身份 / MAC 类拒绝不重发（见 ExecProtocol.isWhitelistDeny）。
            Log.w(TAG, "[EXEC-DENY] 结构化请求被拒（" + r.denyReason + "），按 legacy 明文重发一次: " + com.onesay.assistant.v2.util.LogText.u(command));
            r = runOnce(command, command, timeoutMs);
        }
        if (r.isDenied) {
            Log.w(TAG, "[EXEC-DENY] 守护拒绝 rc=77 " + r.denyReason + " cmd=" + com.onesay.assistant.v2.util.LogText.u(command));
        }
        return r;
    }

    private ShellResponse runOnce(String command, String body, long timeoutMs) {
        {
            File dir = mContext.getFilesDir();
            File req = new File(dir, ExecProtocol.REQ);
            File res = new File(dir, ".bixby_res");

            try {
                // 确保结果文件先行存在，以便守护更新 SELinux 标签
                if (!res.exists()) {
                    try { res.createNewFile(); } catch (Throwable ignored) {}
                }
                try {
                    FileOutputStream z = new FileOutputStream(res, false);
                    z.close();
                } catch (Throwable ignored) {}

                // 写入请求：先写 .bixby_req.tmp 再 rename（ROOT-5，守护不会读到半条）
                if (!ExecProtocol.writeRequest(dir, body)) {
                    Log.w(TAG, "[EXEC] .bixby_req.tmp rename 失败，已退回直接写");
                }

                long t0 = SystemClock.elapsedRealtime();
                boolean picked = false;

                while (SystemClock.elapsedRealtime() - t0 < timeoutMs) {
                    if (res.length() > 0) {
                        ShellResponse resp = parseResultFile(res);
                        if (resp != null) {
                            return resp;
                        }
                    }

                    // 守护清空请求文件即视为已取走指令
                    if (!picked && req.length() == 0) {
                        picked = true;
                    }

                    if (!picked && SystemClock.elapsedRealtime() - t0 > PICKUP_MS) {
                        Log.w(TAG, "Root 守护未能在 1.5s 内取走指令，判定为离线: " + com.onesay.assistant.v2.util.LogText.u(command));
                        ShellResponse late = abandonRequest(req, res, body);
                        if (late != null) return late;
                        return new ShellResponse(-1, "特权执行守护未就绪", false, true);
                    }

                    SystemClock.sleep(30);
                }

                Log.w(TAG, "Root 指令执行超时: " + com.onesay.assistant.v2.util.LogText.u(command));
                if (!picked) {
                    ShellResponse late = abandonRequest(req, res, body);
                    if (late != null) return late;
                }
                return new ShellResponse(124, "命令超时未返回", picked, !picked);

            } catch (Throwable t) {
                Log.e(TAG, "IPC 文件通道异常: " + t.getMessage(), t);
                return new ShellResponse(-1, t.getMessage(), false, true);
            }
        }
    }

    /**
     * 2026-10-23 lane-a（上机报告 §5-1）：失败分支里清空仍是本次命令的请求文件，防止守护恢复后补跑已报失败的命令。
     * 若守护恰好在这一刻取走了（文件已空），再给它 {@link #ABANDON_GRACE_MS} 交结果：拿到就按真实结果返回，不误报失败。
     */
    private ShellResponse abandonRequest(File req, File res, String body) {
        ExecRequestFile.Abandon a = ExecRequestFile.abandon(req, body);
        Log.w(TAG, "[EXEC] 失败分支收尾：请求文件 " + a);
        if (a != ExecRequestFile.Abandon.ALREADY_TAKEN) return null;
        long t1 = SystemClock.elapsedRealtime();
        while (SystemClock.elapsedRealtime() - t1 < ABANDON_GRACE_MS) {
            if (res.length() > 0) {
                ShellResponse r = parseResultFile(res);
                if (r != null) return r;
            }
            SystemClock.sleep(30);
        }
        return null;
    }

    /** 守护在失败判定的同一刻取走请求时，再等结果的时长。 */
    static final long ABANDON_GRACE_MS = 300;

    private ShellResponse parseResultFile(File res) {
        try {
            SystemClock.sleep(20);
            FileInputStream fin = new FileInputStream(res);
            BufferedReader reader = new BufferedReader(new InputStreamReader(fin, "UTF-8"));

            String firstLine = reader.readLine();
            if (firstLine == null) {
                reader.close();
                return null;
            }

            int rc;
            try {
                rc = Integer.parseInt(firstLine.trim());
            } catch (Exception e) {
                reader.close();
                return null;
            }

            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append("\n");
                sb.append(line);
            }
            reader.close();

            String out = sb.toString();
            if (ExecProtocol.isDenied(rc, out)) return ShellResponse.denied(ExecProtocol.denyReason(out));
            boolean timedOut = (rc == 124 || out.contains("TIMEOUT:"));
            return new ShellResponse(rc, out, timedOut, false);
        } catch (Throwable ignored) {
            return null;
        }
    }
}
