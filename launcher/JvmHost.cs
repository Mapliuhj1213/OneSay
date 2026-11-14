using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

namespace OneSay
{
    /// <summary>
    /// 在本进程里用 JNI_CreateJavaVM 启动自带的 Java 运行时，跑 jar 的 main（和 jpackage 生成的启动器同一个做法）。
    /// 参数与原来的 javaw 命令行相同：-Xmx256m、file.encoding、uiScale、-Donesay.exe；另加 -Donesay.host=1，
    /// 并把 com.onesay.desktop.Host 的几个 native 方法注册到这里（开窗口、写快捷方式）。
    /// </summary>
    sealed class JvmHost
    {
        const int JNI_VERSION_10 = 0x000a0000;
        // JNINativeInterface_ 函数表下标（按 JDK 17 jni.h 数出来的，测试里有核对）
        const int FindClassIdx = 6, ExceptionOccurredIdx = 15, ExceptionClearIdx = 17, DeleteLocalRefIdx = 23,
            GetObjectClassIdx = 31, GetMethodIDIdx = 33, CallObjectMethodAIdx = 36, GetStaticMethodIDIdx = 113,
            CallStaticVoidMethodAIdx = 143, NewStringIdx = 163, GetStringLengthIdx = 164, GetStringCharsIdx = 165,
            ReleaseStringCharsIdx = 166, NewObjectArrayIdx = 172, SetObjectArrayElementIdx = 174, RegisterNativesIdx = 215;
        // JNIInvokeInterface_：DestroyJavaVM
        const int DestroyJavaVMIdx = 3;

        public event Action<int, string> Exited;

        readonly string dir, exe, jvm, jar;
        readonly bool tray;
        volatile bool created;
        readonly StringBuilder vmOut = new StringBuilder();

        public JvmHost(string dir, string exe, string jvm, string jar, bool tray)
        {
            this.dir = dir; this.exe = exe; this.jvm = jvm; this.jar = jar; this.tray = tray;
        }

        public void Start()
        {
            // Java 的 main 线程：和 java 启动器一样单独开一个线程（栈给足），界面线程留给 WinForms
            var t = new Thread(Run, 8 * 1024 * 1024) { Name = "onesay-java-main", IsBackground = true };
            t.Start();
        }

        // ---------------------------------------------------------------- 委托类型
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate int CreateJavaVMFn(out IntPtr vm, out IntPtr env, ref JavaVMInitArgs args);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate int DestroyJavaVMFn(IntPtr vm);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate IntPtr FindClassFn(IntPtr env, [MarshalAs(UnmanagedType.LPStr)] string name);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate IntPtr EnvObjFn(IntPtr env);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate void EnvVoidFn(IntPtr env);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate void DeleteLocalRefFn(IntPtr env, IntPtr obj);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate IntPtr GetObjectClassFn(IntPtr env, IntPtr obj);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate IntPtr GetMethodIDFn(IntPtr env, IntPtr cls, [MarshalAs(UnmanagedType.LPStr)] string name, [MarshalAs(UnmanagedType.LPStr)] string sig);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate IntPtr CallObjectMethodAFn(IntPtr env, IntPtr obj, IntPtr mid, IntPtr args);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate void CallStaticVoidMethodAFn(IntPtr env, IntPtr cls, IntPtr mid, IntPtr args);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate IntPtr NewStringFn(IntPtr env, [MarshalAs(UnmanagedType.LPWStr)] string chars, int len);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate int GetStringLengthFn(IntPtr env, IntPtr str);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate IntPtr GetStringCharsFn(IntPtr env, IntPtr str, IntPtr isCopy);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate void ReleaseStringCharsFn(IntPtr env, IntPtr str, IntPtr chars);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate IntPtr NewObjectArrayFn(IntPtr env, int len, IntPtr cls, IntPtr init);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate void SetObjectArrayElementFn(IntPtr env, IntPtr arr, int i, IntPtr v);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate int RegisterNativesFn(IntPtr env, IntPtr cls, IntPtr methods, int n);

        // VM 钩子
        [UnmanagedFunctionPointer(CallingConvention.Cdecl)] delegate int VfprintfHook(IntPtr fp, IntPtr format, IntPtr args);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate void ExitHook(int code);

        // Host 的 native 方法
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate byte OpenFn(IntPtr env, IntPtr cls, IntPtr url, IntPtr dataDir);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate void ReadyFn(IntPtr env, IntPtr cls, IntPtr url, IntPtr dataDir);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate byte ShortcutFn(IntPtr env, IntPtr cls, IntPtr lnk, IntPtr target);
        [UnmanagedFunctionPointer(CallingConvention.Winapi)] delegate IntPtr DesktopDirFn(IntPtr env, IntPtr cls);

        [StructLayout(LayoutKind.Sequential)]
        struct JavaVMOption { public IntPtr optionString; public IntPtr extraInfo; }
        [StructLayout(LayoutKind.Sequential)]
        struct JavaVMInitArgs { public int version; public int nOptions; public IntPtr options; public byte ignoreUnrecognized; }
        [StructLayout(LayoutKind.Sequential)]
        struct JNINativeMethod { public IntPtr name; public IntPtr signature; public IntPtr fnPtr; }

        // 交给 VM 的函数指针背后的委托必须一直活着
        static readonly List<object> keep = new List<object>();

        static T Fn<T>(IntPtr iface, int idx) where T : class
        {
            IntPtr table = Marshal.ReadIntPtr(iface);
            return Marshal.GetDelegateForFunctionPointer(Marshal.ReadIntPtr(table, idx * IntPtr.Size), typeof(T)) as T;
        }

        static string JStr(IntPtr env, IntPtr s)
        {
            if (s == IntPtr.Zero) return null;
            int len = Fn<GetStringLengthFn>(env, GetStringLengthIdx)(env, s);
            IntPtr p = Fn<GetStringCharsFn>(env, GetStringCharsIdx)(env, s, IntPtr.Zero);
            if (p == IntPtr.Zero) return null;
            try { return Marshal.PtrToStringUni(p, len); }
            finally { Fn<ReleaseStringCharsFn>(env, ReleaseStringCharsIdx)(env, s, p); }
        }

        /// <summary>jar 清单里的 Main-Class（读不到就用默认的）。</summary>
        static string MainClass(string jar)
        {
            try
            {
                using (var z = ZipFile.OpenRead(jar))
                {
                    var e = z.GetEntry("META-INF/MANIFEST.MF");
                    if (e != null)
                        using (var r = new StreamReader(e.Open(), Encoding.UTF8))
                        {
                            string line;
                            while ((line = r.ReadLine()) != null)
                                if (line.StartsWith("Main-Class:", StringComparison.Ordinal))
                                    return line.Substring(11).Trim();
                        }
                }
            }
            catch (Exception) { }
            return "com.onesay.desktop.Main";
        }

        string Tail()
        {
            lock (vmOut)
            {
                string s = vmOut.ToString().Trim();
                return s.Length > 1500 ? "…" + s.Substring(s.Length - 1500) : s;
            }
        }

        void Fail(int code, string msg)
        {
            var h = Exited;
            if (h != null) h(code, msg);
        }

        void Run()
        {
            try { RunCore(); }
            catch (Exception e) { Fail(1, "OneSay 启动失败：" + e.Message); }
        }

        void RunCore()
        {
            string bin = Path.Combine(dir, "runtime", "bin");
            // 运行时自带的 VC 运行库先按完整路径加载，其余依赖从 runtime\bin 找（javaw.exe 就在那个目录，所以原来不用管）
            Native.SetDllDirectory(bin);
            foreach (var n in new[] { "vcruntime140.dll", "vcruntime140_1.dll", "msvcp140.dll" })
            {
                string p = Path.Combine(bin, n);
                if (File.Exists(p)) Native.LoadLibrary(p);
            }
            IntPtr lib = Native.LoadLibrary(jvm);
            if (lib == IntPtr.Zero)
            {
                Fail(1, "OneSay 启动失败：加载不了自带的 Java 运行时（jvm.dll，错误码 " + Marshal.GetLastWin32Error() + "）。\n请重新安装 OneSay。");
                return;
            }
            IntPtr createPtr = Native.GetProcAddress(lib, "JNI_CreateJavaVM");
            if (createPtr == IntPtr.Zero)
            {
                Fail(1, "OneSay 启动失败：Java 运行时不完整（找不到 JNI_CreateJavaVM）。\n请重新安装 OneSay。");
                return;
            }
            var create = (CreateJavaVMFn)Marshal.GetDelegateForFunctionPointer(createPtr, typeof(CreateJavaVMFn));

            string main = MainClass(jar);
            var opts = new List<string>
            {
                "-Xmx256m",
                "-Dfile.encoding=UTF-8",
                "-Dsun.java2d.uiScale.enabled=true",
                "-Donesay.exe=" + exe,
                "-Donesay.host=1",
                "-Djava.class.path=" + jar,
                "-Dsun.java.launcher=SUN_STANDARD",
                "-Dsun.java.command=" + main + " app" + (tray ? " --tray" : ""),
            };

            VfprintfHook vf = (fp, fmt, va) =>
            {
                try
                {
                    var buf = new byte[2048];
                    int n = Native._vsnprintf(buf, (UIntPtr)(buf.Length - 1), fmt, va);
                    if (n < 0 || n > buf.Length - 1) n = buf.Length - 1;
                    string s = Encoding.Default.GetString(buf, 0, n);
                    lock (vmOut)
                    {
                        vmOut.Append(s);
                        if (vmOut.Length > 8000) vmOut.Remove(0, vmOut.Length - 8000);
                    }
                }
                catch (Exception) { }
                return 0;
            };
            ExitHook ex = code =>
            {
                // VM 自己要结束进程：启动阶段出错时先说清楚原因；正常的 System.exit 直接结束
                if (!created)
                {
                    string t = Tail();
                    Native.MessageBoxW(IntPtr.Zero, "OneSay 启动失败：Java 运行时没能启动（退出码 " + code + "）。" + (t.Length > 0 ? "\n\n" + t : ""), "OneSay", 0x10);
                }
                Native.ExitProcess((uint)code);
            };
            keep.Add(vf); keep.Add(ex);

            int count = opts.Count + 2;
            int sz = Marshal.SizeOf(typeof(JavaVMOption));
            IntPtr arr = Marshal.AllocHGlobal(sz * count);
            var allocated = new List<IntPtr>();
            for (int i = 0; i < opts.Count; i++)
            {
                // 选项按系统 ANSI 代码页传（和 javaw 命令行一致：JDK 17 在 Windows 上就是这么解释的）
                IntPtr s = Marshal.StringToHGlobalAnsi(opts[i]);
                allocated.Add(s);
                Marshal.StructureToPtr(new JavaVMOption { optionString = s, extraInfo = IntPtr.Zero }, arr + i * sz, false);
            }
            IntPtr vfName = Marshal.StringToHGlobalAnsi("vfprintf"), exName = Marshal.StringToHGlobalAnsi("exit");
            Marshal.StructureToPtr(new JavaVMOption { optionString = vfName, extraInfo = Marshal.GetFunctionPointerForDelegate(vf) }, arr + opts.Count * sz, false);
            Marshal.StructureToPtr(new JavaVMOption { optionString = exName, extraInfo = Marshal.GetFunctionPointerForDelegate(ex) }, arr + (opts.Count + 1) * sz, false);

            var init = new JavaVMInitArgs { version = JNI_VERSION_10, nOptions = count, options = arr, ignoreUnrecognized = 0 };
            IntPtr vm, env;
            int rc = create(out vm, out env, ref init);
            if (rc != 0)
            {
                string why;
                switch (rc)
                {
                    case -3: why = "版本不对"; break;
                    case -4: why = "内存不够"; break;
                    case -5: why = "本进程里已经有一个 Java 虚拟机"; break;
                    case -6: why = "启动参数无效"; break;
                    default: why = "未知错误"; break;
                }
                string t = Tail();
                Fail(1, "OneSay 启动失败：Java 运行时没能启动（" + why + "，错误码 " + rc + "）。" + (t.Length > 0 ? "\n\n" + t : "") + "\n\n请重新安装 OneSay；还不行请把这段文字发给开发者。");
                return;
            }
            created = true;

            RegisterHost(env);

            IntPtr cls = Fn<FindClassFn>(env, FindClassIdx)(env, main.Replace('.', '/'));
            IntPtr mid = cls == IntPtr.Zero ? IntPtr.Zero : Fn<GetMethodIDFn>(env, GetStaticMethodIDIdx)(env, cls, "main", "([Ljava/lang/String;)V");
            if (cls == IntPtr.Zero || mid == IntPtr.Zero)
            {
                string err = PendingException(env);
                Fail(1, "OneSay 启动失败：程序文件里找不到入口 " + main + "。" + (err != null ? "\n\n" + err : "") + "\n请重新安装 OneSay。");
                return;
            }
            IntPtr strCls = Fn<FindClassFn>(env, FindClassIdx)(env, "java/lang/String");
            var argv = tray ? new[] { "app", "--tray" } : new[] { "app" };
            IntPtr jargs = Fn<NewObjectArrayFn>(env, NewObjectArrayIdx)(env, argv.Length, strCls, IntPtr.Zero);
            for (int i = 0; i < argv.Length; i++)
            {
                IntPtr js = Fn<NewStringFn>(env, NewStringIdx)(env, argv[i], argv[i].Length);
                Fn<SetObjectArrayElementFn>(env, SetObjectArrayElementIdx)(env, jargs, i, js);
                Fn<DeleteLocalRefFn>(env, DeleteLocalRefIdx)(env, js);
            }
            IntPtr jv = Marshal.AllocHGlobal(8);
            Marshal.WriteIntPtr(jv, jargs);
            Fn<CallStaticVoidMethodAFn>(env, CallStaticVoidMethodAIdx)(env, cls, mid, jv);
            Marshal.FreeHGlobal(jv);

            string thrown = PendingException(env);
            if (thrown != null)
            {
                Fail(1, "OneSay 运行出错，已退出：\n" + thrown);
                return;
            }
            // main 正常返回：等其余非守护线程结束（和 java 启动器一样），然后告诉界面线程
            Fn<DestroyJavaVMFn>(vm, DestroyJavaVMIdx)(vm);
            Fail(0, null);
        }

        /// <summary>有未处理的 Java 异常就清掉并返回它的 toString()。</summary>
        static string PendingException(IntPtr env)
        {
            IntPtr t = Fn<EnvObjFn>(env, ExceptionOccurredIdx)(env);
            if (t == IntPtr.Zero) return null;
            Fn<EnvVoidFn>(env, ExceptionClearIdx)(env);
            try
            {
                IntPtr c = Fn<GetObjectClassFn>(env, GetObjectClassIdx)(env, t);
                IntPtr m = Fn<GetMethodIDFn>(env, GetMethodIDIdx)(env, c, "toString", "()Ljava/lang/String;");
                IntPtr s = m == IntPtr.Zero ? IntPtr.Zero : Fn<CallObjectMethodAFn>(env, CallObjectMethodAIdx)(env, t, m, IntPtr.Zero);
                Fn<EnvVoidFn>(env, ExceptionClearIdx)(env);
                return JStr(env, s) ?? "（未知异常）";
            }
            catch (Exception) { return "（未知异常）"; }
        }

        // ---------------------------------------------------------------- Host 的 native 方法
        void RegisterHost(IntPtr env)
        {
            IntPtr hc = Fn<FindClassFn>(env, FindClassIdx)(env, "com/onesay/desktop/Host");
            if (hc == IntPtr.Zero) { Fn<EnvVoidFn>(env, ExceptionClearIdx)(env); return; } // 旧 jar 没有这个类：照旧走 Edge 窗口

            OpenFn open = (e, c, url, data) =>
            {
                try { return (byte)(Windows.Open(JStr(e, url), JStr(e, data)) ? 1 : 0); } catch (Exception) { return 0; }
            };
            ReadyFn ready = (e, c, url, data) =>
            {
                try { Windows.Ready(JStr(e, url), JStr(e, data)); } catch (Exception) { }
            };
            ShortcutFn shortcut = (e, c, lnk, target) =>
            {
                try { return (byte)(Shortcuts.Write(JStr(e, lnk), JStr(e, target)) ? 1 : 0); } catch (Exception) { return 0; }
            };
            DesktopDirFn desktop = (e, c) =>
            {
                string d = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
                return Fn<NewStringFn>(e, NewStringIdx)(e, d, d.Length);
            };
            keep.Add(open); keep.Add(ready); keep.Add(shortcut); keep.Add(desktop);

            var methods = new[]
            {
                Method("open0", "(Ljava/lang/String;Ljava/lang/String;)Z", open),
                Method("ready0", "(Ljava/lang/String;Ljava/lang/String;)V", ready),
                Method("shortcut0", "(Ljava/lang/String;Ljava/lang/String;)Z", shortcut),
                Method("desktopDir0", "()Ljava/lang/String;", desktop),
            };
            int sz = Marshal.SizeOf(typeof(JNINativeMethod));
            IntPtr arr = Marshal.AllocHGlobal(sz * methods.Length);
            for (int i = 0; i < methods.Length; i++) Marshal.StructureToPtr(methods[i], arr + i * sz, false);
            if (Fn<RegisterNativesFn>(env, RegisterNativesIdx)(env, hc, arr, methods.Length) != 0)
                Fn<EnvVoidFn>(env, ExceptionClearIdx)(env);
            Fn<DeleteLocalRefFn>(env, DeleteLocalRefIdx)(env, hc);
        }

        static JNINativeMethod Method(string name, string sig, Delegate d)
        {
            return new JNINativeMethod
            {
                name = Marshal.StringToHGlobalAnsi(name),
                signature = Marshal.StringToHGlobalAnsi(sig),
                fnPtr = Marshal.GetFunctionPointerForDelegate(d),
            };
        }
    }
}
