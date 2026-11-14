using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
using System.Text;

namespace OneSay
{
    static class Native
    {
        [DllImport("shell32.dll", CharSet = CharSet.Unicode)]
        public static extern int SetCurrentProcessExplicitAppUserModelID(string appId);

        [DllImport("user32.dll")]
        public static extern bool AllowSetForegroundWindow(int processId);

        [DllImport("user32.dll")]
        public static extern bool SetForegroundWindow(IntPtr hwnd);

        [DllImport("user32.dll", CharSet = CharSet.Unicode)]
        public static extern int MessageBoxW(IntPtr hwnd, string text, string caption, uint type);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern bool SetDllDirectory(string path);

        [DllImport("kernel32.dll", CharSet = CharSet.Unicode, SetLastError = true)]
        public static extern IntPtr LoadLibrary(string path);

        [DllImport("kernel32.dll", CharSet = CharSet.Ansi, SetLastError = true)]
        public static extern IntPtr GetProcAddress(IntPtr module, string name);

        [DllImport("kernel32.dll")]
        public static extern void ExitProcess(uint code);

        [DllImport("msvcrt.dll", CallingConvention = CallingConvention.Cdecl)]
        public static extern int _vsnprintf(byte[] buffer, UIntPtr count, IntPtr format, IntPtr args);
    }

    /// <summary>写 .lnk 快捷方式并带上 AppUserModelID（任务栏分组 / 固定图标靠它和运行中的窗口对上）。</summary>
    static class Shortcuts
    {
        [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
        class CShellLink { }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("000214F9-0000-0000-C000-000000000046")]
        interface IShellLinkW
        {
            void GetPath([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder file, int cch, IntPtr fd, uint flags);
            void GetIDList(out IntPtr ppidl);
            void SetIDList(IntPtr pidl);
            void GetDescription([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder name, int cch);
            void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string name);
            void GetWorkingDirectory([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder dir, int cch);
            void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string dir);
            void GetArguments([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder args, int cch);
            void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string args);
            void GetHotkey(out short hotkey);
            void SetHotkey(short hotkey);
            void GetShowCmd(out int showCmd);
            void SetShowCmd(int showCmd);
            void GetIconLocation([Out, MarshalAs(UnmanagedType.LPWStr)] StringBuilder path, int cch, out int icon);
            void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string path, int icon);
            void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string rel, uint reserved);
            void Resolve(IntPtr hwnd, uint flags);
            void SetPath([MarshalAs(UnmanagedType.LPWStr)] string file);
        }

        [ComImport, InterfaceType(ComInterfaceType.InterfaceIsIUnknown), Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99")]
        interface IPropertyStore
        {
            void GetCount(out uint count);
            void GetAt(uint index, out PropertyKey key);
            void GetValue(ref PropertyKey key, out PropVariant value);
            void SetValue(ref PropertyKey key, ref PropVariant value);
            void Commit();
        }

        [StructLayout(LayoutKind.Sequential, Pack = 4)]
        struct PropertyKey { public Guid fmtid; public uint pid; }

        [StructLayout(LayoutKind.Sequential)]
        struct PropVariant
        {
            public ushort vt; public ushort r1, r2, r3;
            public IntPtr p; public IntPtr p2;
        }

        [DllImport("ole32.dll")]
        static extern int PropVariantClear(ref PropVariant pv);

        static readonly PropertyKey AppUserModelId = new PropertyKey { fmtid = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), pid = 5 };

        /// <summary>
        /// target 不为空：新建（或覆盖）指向 target 的快捷方式；target 为空：打开已有的 .lnk，只补写 AppUserModelID（安装程序用）。
        /// </summary>
        public static bool Write(string lnk, string target)
        {
            if (string.IsNullOrEmpty(lnk)) return false;
            object o = null;
            try
            {
                o = new CShellLink();
                var link = (IShellLinkW)o;
                var file = (IPersistFile)o;
                if (string.IsNullOrEmpty(target))
                {
                    if (!System.IO.File.Exists(lnk)) return false;
                    file.Load(lnk, 2 /* STGM_READWRITE */);
                }
                else
                {
                    link.SetPath(target);
                    link.SetWorkingDirectory(System.IO.Path.GetDirectoryName(target));
                    link.SetIconLocation(target, 0);
                    link.SetDescription("OneSay");
                }
                var store = (IPropertyStore)o;
                var key = AppUserModelId;
                var v = new PropVariant { vt = 31 /* VT_LPWSTR */, p = Marshal.StringToCoTaskMemUni(Program.AppId) };
                try
                {
                    store.SetValue(ref key, ref v);
                    store.Commit();
                }
                finally { PropVariantClear(ref v); }
                string dir = System.IO.Path.GetDirectoryName(lnk);
                if (!string.IsNullOrEmpty(dir)) System.IO.Directory.CreateDirectory(dir);
                file.Save(lnk, true);
                return true;
            }
            catch (Exception)
            {
                return false;
            }
            finally
            {
                if (o != null) Marshal.ReleaseComObject(o);
            }
        }
    }
}
