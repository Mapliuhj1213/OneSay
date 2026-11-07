// OneSay 长按空格说话：Windows 底层键盘钩子。由 hotkey.ps1 经 Add-Type 编译（C# 5 语法，兼容 Windows PowerShell 5.1）。
// 判定逻辑逐行照抄 Java 的 SpaceGate（那边有单测），改一边必须改另一边。
// 标准输出协议：READY / START / END / CANCEL / LOG <文字>。标准输入关闭（主程序退出）即卸钩退出，绝不留下吞空格的孤儿钩子。
using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class OneSayHotkey {
    const int WH_KEYBOARD_LL = 13;
    const int WM_KEYDOWN = 0x100, WM_KEYUP = 0x101, WM_SYSKEYDOWN = 0x104, WM_SYSKEYUP = 0x105, WM_TIMER = 0x113, WM_QUIT = 0x12;
    const int VK_SPACE = 0x20, VK_ESCAPE = 0x1B, VK_SHIFT = 0x10, VK_CONTROL = 0x11, VK_MENU = 0x12, VK_LWIN = 0x5B, VK_RWIN = 0x5C;
    const uint LLKHF_INJECTED = 0x10;
    const uint INPUT_KEYBOARD = 1, KEYEVENTF_KEYUP = 0x2;
    const long HOLD_MS = 350;
    static readonly IntPtr MARK = new IntPtr(0x4F4E5359); // "ONSY"：自己注入的空格

    [StructLayout(LayoutKind.Sequential)]
    struct KBDLLHOOKSTRUCT { public uint vkCode; public uint scanCode; public uint flags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    struct MOUSEINPUT { public int dx; public int dy; public uint mouseData; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Sequential)]
    struct KEYBDINPUT { public ushort wVk; public ushort wScan; public uint dwFlags; public uint time; public IntPtr dwExtraInfo; }
    [StructLayout(LayoutKind.Explicit)]
    struct InputUnion { [FieldOffset(0)] public MOUSEINPUT mi; [FieldOffset(0)] public KEYBDINPUT ki; }
    [StructLayout(LayoutKind.Sequential)]
    struct INPUT { public uint type; public InputUnion u; }
    [StructLayout(LayoutKind.Sequential)]
    struct MSG { public IntPtr hwnd; public uint message; public IntPtr wParam; public IntPtr lParam; public uint time; public int ptX; public int ptY; }
    [StructLayout(LayoutKind.Sequential)]
    struct RECT { public int Left; public int Top; public int Right; public int Bottom; }
    [StructLayout(LayoutKind.Sequential)]
    struct MONITORINFO { public int cbSize; public RECT rcMonitor; public RECT rcWork; public uint dwFlags; }

    delegate IntPtr HookProc(int nCode, IntPtr wParam, IntPtr lParam);

    [DllImport("user32.dll", SetLastError = true)] static extern IntPtr SetWindowsHookEx(int idHook, HookProc fn, IntPtr hMod, uint threadId);
    [DllImport("user32.dll")] static extern bool UnhookWindowsHookEx(IntPtr hhk);
    [DllImport("user32.dll")] static extern IntPtr CallNextHookEx(IntPtr hhk, int nCode, IntPtr wParam, IntPtr lParam);
    [DllImport("kernel32.dll")] static extern IntPtr GetModuleHandle(string name);
    [DllImport("kernel32.dll")] static extern uint GetCurrentThreadId();
    [DllImport("user32.dll")] static extern int GetMessage(out MSG msg, IntPtr hwnd, uint min, uint max);
    [DllImport("user32.dll")] static extern bool PostThreadMessage(uint threadId, uint msg, IntPtr w, IntPtr l);
    [DllImport("user32.dll")] static extern UIntPtr SetTimer(IntPtr hwnd, UIntPtr id, uint ms, IntPtr fn);
    [DllImport("user32.dll", SetLastError = true)] static extern uint SendInput(uint n, INPUT[] inputs, int size);
    [DllImport("user32.dll")] static extern short GetAsyncKeyState(int vk);
    [DllImport("user32.dll")] static extern IntPtr GetForegroundWindow();
    [DllImport("user32.dll")] static extern uint GetWindowThreadProcessId(IntPtr hwnd, out uint pid);
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr hwnd, out RECT r);
    [DllImport("user32.dll")] static extern IntPtr MonitorFromWindow(IntPtr hwnd, uint flags);
    [DllImport("user32.dll")] static extern bool GetMonitorInfo(IntPtr mon, ref MONITORINFO mi);
    [DllImport("user32.dll", CharSet = CharSet.Unicode)] static extern int GetClassName(IntPtr hwnd, StringBuilder sb, int max);
    [DllImport("kernel32.dll")] static extern IntPtr OpenProcess(uint access, bool inherit, uint pid);
    [DllImport("kernel32.dll")] static extern bool CloseHandle(IntPtr h);
    [DllImport("advapi32.dll")] static extern bool OpenProcessToken(IntPtr process, uint access, out IntPtr token);
    [DllImport("kernel32.dll", CharSet = CharSet.Unicode)] static extern bool QueryFullProcessImageName(IntPtr h, int flags, StringBuilder sb, ref int size);

    // ---- 与 SpaceGate 一致的状态机 ----
    const int IDLE = 0, PENDING = 1, TALKING = 2, ROLLED = 3;
    static int state = IDLE;
    static int timerGen = 0;
    static readonly object gate = new object();

    static HookProc proc;          // 必须留引用，防被回收
    static IntPtr hook = IntPtr.Zero;
    static uint mainThread;
    static Timer holdTimer;
    static string[] skip = new string[0];
    static readonly object outLock = new object();

    static void Emit(string s) { lock (outLock) { Console.Out.WriteLine(s); Console.Out.Flush(); } }

    public static void Run(string skipList) {
        skip = (skipList ?? "").ToLowerInvariant().Split(new char[] { ',', ';' }, StringSplitOptions.RemoveEmptyEntries);
        mainThread = GetCurrentThreadId();
        proc = new HookProc(OnKey);
        if (!Install()) { Emit("LOG 挂钩失败 " + Marshal.GetLastWin32Error()); return; }
        // 主程序退出 → 标准输入关闭 → 结束
        Thread watch = new Thread(delegate () {
            try { while (Console.In.ReadLine() != null) { } } catch (Exception) { }
            PostThreadMessage(mainThread, WM_QUIT, IntPtr.Zero, IntPtr.Zero);
        });
        watch.IsBackground = true;
        watch.Start();
        SetTimer(IntPtr.Zero, UIntPtr.Zero, 10 * 60 * 1000, IntPtr.Zero); // 每 10 分钟重挂，保持排在钩子链最前
        Emit("READY");
        MSG msg;
        while (GetMessage(out msg, IntPtr.Zero, 0, 0) > 0) {
            if (msg.message == WM_TIMER) {
                lock (gate) { if (state == IDLE) { UnhookWindowsHookEx(hook); Install(); } }
            }
        }
        UnhookWindowsHookEx(hook);
    }

    static bool Install() {
        string mod = Process.GetCurrentProcess().MainModule.ModuleName;
        hook = SetWindowsHookEx(WH_KEYBOARD_LL, proc, GetModuleHandle(mod), 0);
        return hook != IntPtr.Zero;
    }

    static IntPtr OnKey(int nCode, IntPtr wParam, IntPtr lParam) {
        if (nCode < 0) return CallNextHookEx(hook, nCode, wParam, lParam);
        KBDLLHOOKSTRUCT k = (KBDLLHOOKSTRUCT)Marshal.PtrToStructure(lParam, typeof(KBDLLHOOKSTRUCT));
        if ((k.flags & LLKHF_INJECTED) != 0 && k.dwExtraInfo == MARK) return CallNextHookEx(hook, nCode, wParam, lParam);
        int m = wParam.ToInt32();
        bool down = m == WM_KEYDOWN || m == WM_SYSKEYDOWN;
        bool up = m == WM_KEYUP || m == WM_SYSKEYUP;
        bool swallow = false;
        bool inject = false;
        lock (gate) {
            if (k.vkCode == VK_SPACE && down) {
                if (state == IDLE) {
                    if (!Bypass()) {
                        state = PENDING;
                        timerGen++;
                        StartTimer(timerGen);
                        swallow = true;
                    }
                } else swallow = true; // 自动重复
            } else if (k.vkCode == VK_SPACE && up) {
                if (state == PENDING) { inject = true; swallow = true; state = IDLE; }
                else if (state == TALKING) { Emit("END"); swallow = true; state = IDLE; }
                else if (state == ROLLED) { swallow = true; state = IDLE; }
            } else if (down && (k.flags & LLKHF_INJECTED) == 0) {
                if (state == PENDING) { state = ROLLED; inject = true; }
                else if (state == TALKING && k.vkCode == VK_ESCAPE) { state = ROLLED; Emit("CANCEL"); swallow = true; }
            }
        }
        if (inject) InjectSpace();
        return swallow ? new IntPtr(1) : CallNextHookEx(hook, nCode, wParam, lParam);
    }

    static void StartTimer(int gen) {
        if (holdTimer != null) holdTimer.Dispose();
        holdTimer = new Timer(delegate (object o) {
            lock (gate) {
                if (state == PENDING && gen == timerGen) { state = TALKING; Emit("START"); }
            }
        }, null, HOLD_MS, Timeout.Infinite);
    }

    static void InjectSpace() {
        INPUT[] a = new INPUT[2];
        a[0].type = INPUT_KEYBOARD; a[0].u.ki.wVk = VK_SPACE; a[0].u.ki.dwExtraInfo = MARK;
        a[1].type = INPUT_KEYBOARD; a[1].u.ki.wVk = VK_SPACE; a[1].u.ki.dwFlags = KEYEVENTF_KEYUP; a[1].u.ki.dwExtraInfo = MARK;
        SendInput(2, a, Marshal.SizeOf(typeof(INPUT)));
    }

    static bool Pressed(int vk) { return (GetAsyncKeyState(vk) & 0x8000) != 0; }

    /** 放行：带修饰键；前台是跳过名单里的程序、全屏窗口（游戏、视频）、或管理员窗口（注入的空格进不去）。 */
    static bool Bypass() {
        if (Pressed(VK_CONTROL) || Pressed(VK_MENU) || Pressed(VK_LWIN) || Pressed(VK_RWIN) || Pressed(VK_SHIFT)) return true;
        IntPtr fg = GetForegroundWindow();
        if (fg == IntPtr.Zero) return false;
        uint pid;
        GetWindowThreadProcessId(fg, out pid);
        IntPtr h = OpenProcess(0x1000, false, pid); // PROCESS_QUERY_LIMITED_INFORMATION
        if (h != IntPtr.Zero) {
            try {
                StringBuilder sb = new StringBuilder(1024);
                int size = sb.Capacity;
                if (QueryFullProcessImageName(h, 0, sb, ref size)) {
                    string exe = sb.ToString().ToLowerInvariant();
                    int slash = exe.LastIndexOf('\\');
                    if (slash >= 0) exe = exe.Substring(slash + 1);
                    foreach (string s in skip) if (exe == s.Trim()) return true;
                }
                IntPtr tok;
                if (!OpenProcessToken(h, 0x8, out tok)) return true; // TOKEN_QUERY 都拿不到 = 比我们权限高
                CloseHandle(tok);
            } finally { CloseHandle(h); }
        } else return true;
        StringBuilder cls = new StringBuilder(64);
        GetClassName(fg, cls, cls.Capacity);
        string c = cls.ToString();
        if (c == "Progman" || c == "WorkerW" || c == "Shell_TrayWnd") return false;
        RECT r;
        if (!GetWindowRect(fg, out r)) return false;
        MONITORINFO mi = new MONITORINFO();
        mi.cbSize = Marshal.SizeOf(typeof(MONITORINFO));
        if (!GetMonitorInfo(MonitorFromWindow(fg, 2), ref mi)) return false;
        return r.Left <= mi.rcMonitor.Left && r.Top <= mi.rcMonitor.Top && r.Right >= mi.rcMonitor.Right && r.Bottom >= mi.rcMonitor.Bottom;
    }
}
