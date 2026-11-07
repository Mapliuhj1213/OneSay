using System;
using System.Diagnostics;
using System.IO;
using System.Windows.Forms;

namespace OneSay
{
    /// <summary>
    /// 双击启动器：用自带的精简 Java 运行时启动 OneSay，自己马上退出。
    /// 已经开着时，Java 那边只把窗口叫出来。--tray 只进托盘（开机自启用）。
    /// </summary>
    static class Program
    {
        [STAThread]
        static int Main(string[] args)
        {
            string dir = AppDomain.CurrentDomain.BaseDirectory;
            string javaw = Path.Combine(dir, "runtime", "bin", "javaw.exe");
            string jar = Path.Combine(dir, "app", "onesay-desktop.jar");
            if (!File.Exists(javaw) || !File.Exists(jar))
            {
                MessageBox.Show("OneSay 文件不完整：缺少 runtime 或 app 文件夹。\n请把压缩包整个解压后再运行 OneSay.exe。", "OneSay", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 2;
            }
            string exe = Path.Combine(dir, "OneSay.exe");
            bool tray = Array.IndexOf(args, "--tray") >= 0;
            var psi = new ProcessStartInfo(javaw,
                "-Xmx256m -Dfile.encoding=UTF-8 -Dsun.java2d.uiScale.enabled=true \"-Donesay.exe=" + exe + "\" -jar \"" + jar + "\" app" + (tray ? " --tray" : ""))
            {
                UseShellExecute = false,
                WorkingDirectory = dir,
                CreateNoWindow = true
            };
            try
            {
                Process.Start(psi);
                return 0;
            }
            catch (Exception e)
            {
                MessageBox.Show("OneSay 启动失败：" + e.Message, "OneSay", MessageBoxButtons.OK, MessageBoxIcon.Error);
                return 1;
            }
        }
    }
}
