using System;
using System.IO;
using System.Threading;
using System.Windows.Forms;

namespace OneSay
{
    /// <summary>
    /// OneSay.exe：在本进程里加载自带运行时的 jvm.dll 运行 OneSay（jpackage 同款做法），自己不退出。
    /// 这样窗口、托盘图标、网络监听都属于 OneSay.exe（名字、图标、版本信息都是 OneSay）：
    /// Windows 防火墙弹窗写 OneSay，按 exe 认程序的任务栏 / Dock 软件（如 MyDockFinder）也能认出来。
    /// 主窗口用 WebView2 开在本进程里；电脑上没有 WebView2 运行时才退回 Edge 应用窗口。
    /// 已经开着时只把窗口叫出来；--tray 只进托盘（开机自启用）。
    /// </summary>
    static class Program
    {
        /// <summary>任务栏分组 / 固定用的 AppUserModelID；快捷方式（安装程序、程序自建）写同一个。Java 端 Host.AUMID 必须相同。</summary>
        public const string AppId = "OneSay.Desktop";
        const string MutexName = "Local\\OneSay.Desktop.Instance";
        const string ShowEventName = "Local\\OneSay.Desktop.Show";

        internal static string Dir;
        internal static string Exe;
        internal static SynchronizationContext Ui;
        internal static Control UiAnchor;

        [STAThread]
        static int Main(string[] args)
        {
            Dir = AppDomain.CurrentDomain.BaseDirectory;
            Exe = Path.Combine(Dir, "OneSay.exe");

            // 安装程序用：OneSay.exe --shortcut <lnk>... 给已有的快捷方式写上 AppUserModelID，然后退出（不启动程序）
            if (args.Length >= 1 && args[0] == "--shortcut")
            {
                int bad = 0;
                for (int i = 1; i < args.Length; i++)
                    if (!Shortcuts.Write(args[i], null)) bad++;
                return bad == 0 ? 0 : 3;
            }

            Native.SetCurrentProcessExplicitAppUserModelID(AppId);
            bool tray = Array.IndexOf(args, "--tray") >= 0;

            // 单实例（每个登录会话一个）：已经开着就只叫出窗口
            bool first;
            var mutex = new Mutex(true, MutexName, out first);
            if (!first)
            {
                if (!tray)
                {
                    Native.AllowSetForegroundWindow(-1); // ASFW_ANY：让已开着的那个能把窗口提到前台
                    try { using (var ev = EventWaitHandle.OpenExisting(ShowEventName)) ev.Set(); } catch (Exception) { }
                }
                return 0;
            }

            string jvm = Path.Combine(Dir, "runtime", "bin", "server", "jvm.dll");
            string jar = Path.Combine(Dir, "app", "onesay-desktop.jar");
            if (!File.Exists(jvm) || !File.Exists(jar))
            {
                MessageBox.Show("OneSay 文件不完整：缺少 runtime 或 app 文件夹。\n请重新安装 OneSay（或把压缩包整个解压后再运行 OneSay.exe）。", "OneSay", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 2;
            }

            Application.EnableVisualStyles();
            Application.SetCompatibleTextRenderingDefault(false);
            UiAnchor = new Control();
            UiAnchor.CreateControl();
            var h = UiAnchor.Handle; // 在界面线程上建好句柄，之后别的线程用它 BeginInvoke
            Ui = SynchronizationContext.Current ?? new WindowsFormsSynchronizationContext();

            var showEvent = new EventWaitHandle(false, EventResetMode.AutoReset, ShowEventName);
            var watcher = new Thread(() =>
            {
                while (true)
                {
                    showEvent.WaitOne();
                    Post(() => Windows.ShowFromOtherLaunch());
                }
            }) { IsBackground = true, Name = "onesay-show" };
            watcher.Start();

            var host = new JvmHost(Dir, Exe, jvm, jar, tray);
            host.Exited += (code, error) => Post(() =>
            {
                if (error != null)
                {
                    MessageBox.Show(error, "OneSay", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    Environment.Exit(code == 0 ? 1 : code);
                }
                // Java 的 main 正常结束（比如本机已有别的 OneSay 在跑，只叫出了窗口）：窗口关了就退出
                Windows.JavaGone = true;
                if (!Windows.AnyOpen) Application.Exit();
            });
            host.Start();

            Application.Run();
            GC.KeepAlive(mutex);
            GC.KeepAlive(h);
            return 0;
        }

        internal static void Post(Action a)
        {
            try { UiAnchor.BeginInvoke(a); } catch (Exception) { }
        }
    }
}
