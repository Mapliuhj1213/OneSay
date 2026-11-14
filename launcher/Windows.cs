using System;
using System.Diagnostics;
using System.Drawing;
using System.Globalization;
using System.IO;
using System.Threading;
using System.Windows.Forms;
using Microsoft.Web.WebView2.Core;
using Microsoft.Web.WebView2.WinForms;

namespace OneSay
{
    /// <summary>主窗口：WebView2 开在 OneSay.exe 自己的进程里（原来是 Edge 应用窗口，属于 msedge.exe）。</summary>
    static class Windows
    {
        static MainForm form;
        static string url, dataDir;
        static bool pendingShow;
        internal static volatile bool JavaGone;
        static int runtimeOk = -1; // -1 没查过，0 没有 WebView2 运行时，1 有

        internal static bool AnyOpen { get { return form != null && !form.IsDisposed; } }

        /// <summary>电脑上有没有 WebView2 运行时（Windows 11 自带；没有就让 Java 退回 Edge 应用窗口）。</summary>
        static bool RuntimeAvailable()
        {
            if (runtimeOk < 0)
            {
                try { runtimeOk = string.IsNullOrEmpty(CoreWebView2Environment.GetAvailableBrowserVersionString()) ? 0 : 1; }
                catch (Exception) { runtimeOk = 0; }
            }
            return runtimeOk == 1;
        }

        /// <summary>Java 的 App.openWindow 调这里（任意线程）。返回 false 时 Java 照旧开 Edge 窗口。</summary>
        internal static bool Open(string u, string data)
        {
            if (string.IsNullOrEmpty(u) || !RuntimeAvailable()) return false;
            Program.Post(() => Show(u, data));
            return true;
        }

        /// <summary>Java 界面服务起来了（--tray 启动时也会调）：记下地址，供再次双击 OneSay.exe 时叫出窗口。</summary>
        internal static void Ready(string u, string data)
        {
            Program.Post(() =>
            {
                url = u;
                if (!string.IsNullOrEmpty(data)) dataDir = data;
                if (pendingShow) { pendingShow = false; Show(url, dataDir); }
            });
        }

        /// <summary>又双击了一次 OneSay.exe（或开始菜单 / 任务栏）：叫出窗口。</summary>
        internal static void ShowFromOtherLaunch()
        {
            if (AnyOpen) { Activate(form); return; }
            if (url == null) { pendingShow = true; return; } // Java 还在启动，起来后再开
            if (!RuntimeAvailable()) { Fallback(url); return; }
            Show(url, dataDir);
        }

        static void Fallback(string u)
        {
            // 没有 WebView2：用默认浏览器打开（和原来没有 Edge 时一样）
            try { Process.Start(new ProcessStartInfo(u) { UseShellExecute = true }); } catch (Exception) { }
        }

        static void Show(string u, string data)
        {
            if (!string.IsNullOrEmpty(data)) dataDir = data;
            if (url == null) url = u;
            if (AnyOpen)
            {
                form.Go(u);
                Activate(form);
                return;
            }
            string profile = Path.Combine(dataDir ?? Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData), "OneSay"), "webview2");
            form = new MainForm(u, profile, dataDir);
            form.FormClosed += (s, e) =>
            {
                form = null;
                if (JavaGone) Application.Exit();
            };
            form.Show();
            Activate(form);
        }

        static void Activate(Form f)
        {
            if (f.WindowState == FormWindowState.Minimized) f.WindowState = FormWindowState.Normal;
            f.Show();
            f.Activate();
            Native.SetForegroundWindow(f.Handle);
        }
    }

    sealed class MainForm : Form
    {
        readonly WebView2 web;
        readonly string boundsFile;
        string home;

        public MainForm(string url, string profile, string dataDir)
        {
            home = url;
            Text = "OneSay";
            try { Icon = Icon.ExtractAssociatedIcon(Program.Exe); } catch (Exception) { }
            AutoScaleMode = AutoScaleMode.None;
            StartPosition = FormStartPosition.CenterScreen;
            BackColor = Color.White;
            float k = DeviceDpi / 96f;
            ClientSize = new Size((int)(1200 * k), (int)(800 * k));
            MinimumSize = new Size((int)(480 * k), (int)(360 * k));
            boundsFile = dataDir == null ? null : Path.Combine(dataDir, "window-bounds.txt");
            RestoreBounds0();

            web = new WebView2 { Dock = DockStyle.Fill, DefaultBackgroundColor = Color.White };
            web.CreationProperties = new CoreWebView2CreationProperties { UserDataFolder = profile };
            Controls.Add(web);
            web.CoreWebView2InitializationCompleted += (s, e) =>
            {
                if (!e.IsSuccess)
                {
                    MessageBox.Show(this, "OneSay 窗口打不开（WebView2 出错：" + (e.InitializationException != null ? e.InitializationException.Message : "未知") + "）。\n可以从托盘图标的菜单里再试一次。", "OneSay", MessageBoxButtons.OK, MessageBoxIcon.Error);
                    Close();
                    return;
                }
                var cw = web.CoreWebView2;
                cw.Settings.IsStatusBarEnabled = false;
                cw.Settings.AreDevToolsEnabled = false;
                cw.DocumentTitleChanged += (s2, e2) => { Text = string.IsNullOrEmpty(cw.DocumentTitle) ? "OneSay" : cw.DocumentTitle; };
                // 外部链接用默认浏览器打开，不在 OneSay 窗口里跳走
                cw.NewWindowRequested += (s2, e2) => { e2.Handled = true; External(e2.Uri); };
                cw.NavigationStarting += (s2, e2) =>
                {
                    if (!IsLocal(e2.Uri)) { e2.Cancel = true; External(e2.Uri); }
                };
            };
            web.Source = new Uri(url);
            FormClosing += (s, e) => SaveBounds();
        }

        internal void Go(string url)
        {
            if (url == home) return;
            home = url;
            try { web.Source = new Uri(url); } catch (Exception) { }
        }

        static bool IsLocal(string u)
        {
            Uri x;
            if (!Uri.TryCreate(u, UriKind.Absolute, out x)) return true;
            if (x.Scheme == "about" || x.Scheme == "data" || x.Scheme == "blob") return true;
            return (x.Scheme == "http" || x.Scheme == "https") && (x.Host == "127.0.0.1" || x.Host == "localhost");
        }

        static void External(string u)
        {
            Uri x;
            if (!Uri.TryCreate(u, UriKind.Absolute, out x)) return;
            if (x.Scheme != "http" && x.Scheme != "https" && x.Scheme != "mailto") return;
            try { Process.Start(new ProcessStartInfo(x.AbsoluteUri) { UseShellExecute = true }); } catch (Exception) { }
        }

        // 记住窗口位置和大小（%APPDATA%\OneSay\window-bounds.txt：x,y,宽,高,是否最大化）
        void RestoreBounds0()
        {
            try
            {
                if (boundsFile == null || !File.Exists(boundsFile)) return;
                var p = File.ReadAllText(boundsFile).Trim().Split(',');
                if (p.Length < 5) return;
                var r = new Rectangle(int.Parse(p[0], CultureInfo.InvariantCulture), int.Parse(p[1], CultureInfo.InvariantCulture),
                    int.Parse(p[2], CultureInfo.InvariantCulture), int.Parse(p[3], CultureInfo.InvariantCulture));
                if (r.Width < 200 || r.Height < 150) return;
                bool visible = false;
                foreach (var s in Screen.AllScreens)
                    if (s.WorkingArea.IntersectsWith(new Rectangle(r.X, r.Y, r.Width, 40))) { visible = true; break; }
                if (!visible) return;
                StartPosition = FormStartPosition.Manual;
                Bounds = r;
                if (p[4] == "1") WindowState = FormWindowState.Maximized;
            }
            catch (Exception) { }
        }

        void SaveBounds()
        {
            try
            {
                if (boundsFile == null) return;
                var r = WindowState == FormWindowState.Normal ? Bounds : RestoreBounds;
                Directory.CreateDirectory(Path.GetDirectoryName(boundsFile));
                File.WriteAllText(boundsFile, string.Format(CultureInfo.InvariantCulture, "{0},{1},{2},{3},{4}",
                    r.X, r.Y, r.Width, r.Height, WindowState == FormWindowState.Maximized ? 1 : 0));
            }
            catch (Exception) { }
        }

        protected override void Dispose(bool disposing)
        {
            if (disposing && web != null) web.Dispose();
            base.Dispose(disposing);
        }
    }
}
