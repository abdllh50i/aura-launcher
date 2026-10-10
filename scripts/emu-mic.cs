// The emulator's microphone and speaker, chosen from the PC's (scripts\emu-mic.ps1). Windows' own default devices
// stay as they are: the chosen microphone is passed on into VB-Audio Virtual Cable ("CABLE Input"), whose other end
// ("CABLE Output", Windows' default input here) is what the emulator records; and what the emulator plays (on Windows'
// default output) is passed on to the chosen speaker. C# 5 (Windows PowerShell's Add-Type).
using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;
using System.Windows.Forms;

namespace AmriEmuMic {

// ------------------------------------------------------------------------------------------ Core Audio
[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")] class MMDeviceEnumeratorCom {}
[Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceEnumerator {
    [PreserveSig] int EnumAudioEndpoints(int dataFlow, int stateMask, out IMMDeviceCollection devices);
    [PreserveSig] int GetDefaultAudioEndpoint(int dataFlow, int role, out IMMDevice endpoint);
}
[Guid("0BD7A1BE-7A1A-44DB-8397-CC5392387B5E"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDeviceCollection {
    [PreserveSig] int GetCount(out int count);
    [PreserveSig] int Item(int index, out IMMDevice device);
}
[Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IMMDevice {
    [PreserveSig] int Activate(ref Guid iid, int clsCtx, IntPtr p, [MarshalAs(UnmanagedType.IUnknown)] out object o);
    [PreserveSig] int OpenPropertyStore(int access, out IPropertyStore props);
    [PreserveSig] int GetId([MarshalAs(UnmanagedType.LPWStr)] out string id);
}
[Guid("886d8eeb-8cf2-4446-8d02-cdba1dbdcf99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IPropertyStore {
    [PreserveSig] int GetCount(out int count);
    [PreserveSig] int GetAt(int i, out PKey key);
    [PreserveSig] int GetValue(ref PKey key, out PropVar v);
}
[StructLayout(LayoutKind.Sequential)] struct PKey { public Guid fmtid; public int pid; }
[StructLayout(LayoutKind.Explicit, Size = 24)] struct PropVar { [FieldOffset(0)] public short vt; [FieldOffset(8)] public IntPtr p; }

[Guid("1CB9AD4C-DBFA-4c32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioClient {
    [PreserveSig] int Initialize(int shareMode, int streamFlags, long bufferDuration, long periodicity, IntPtr format, IntPtr session);
    [PreserveSig] int GetBufferSize(out uint frames);
    [PreserveSig] int GetStreamLatency(out long latency);
    [PreserveSig] int GetCurrentPadding(out uint frames);
    [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
    [PreserveSig] int GetMixFormat(out IntPtr format);
    [PreserveSig] int GetDevicePeriod(out long defaultPeriod, out long minimumPeriod);
    [PreserveSig] int Start();
    [PreserveSig] int Stop();
    [PreserveSig] int Reset();
    [PreserveSig] int SetEventHandle(IntPtr handle);
    [PreserveSig] int GetService(ref Guid iid, [MarshalAs(UnmanagedType.IUnknown)] out object o);
}
[Guid("C8ADBD64-E71E-48a0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
interface IAudioCaptureClient {
    [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
    [PreserveSig] int ReleaseBuffer(uint frames);
    [PreserveSig] int GetNextPacketSize(out uint frames);
}

static class CoreAudio {
    static string Name(IMMDevice d) {
        IPropertyStore ps;
        if (d.OpenPropertyStore(0, out ps) != 0) return "";
        PKey k = new PKey(); k.fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"); k.pid = 14;
        PropVar v;
        if (ps.GetValue(ref k, out v) != 0) return "";
        return v.vt == 31 ? Marshal.PtrToStringUni(v.p) : "";
    }

    /** The names of the devices that are on (0 output, 1 input). */
    public static List<string> Names(int flow) {
        var res = new List<string>();
        try {
            var e = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
            IMMDeviceCollection c;
            if (e.EnumAudioEndpoints(flow, 1, out c) != 0) return res;
            int n; c.GetCount(out n);
            for (int i = 0; i < n; i++) { IMMDevice d; if (c.Item(i, out d) == 0) res.Add(Name(d)); }
        } catch (Exception) { }
        return res;
    }

    /** Windows' default device's name (0 output, 1 input). */
    public static string DefaultName(int flow) {
        try {
            var e = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
            IMMDevice d;
            if (e.GetDefaultAudioEndpoint(flow, 0, out d) != 0) return "";
            return Name(d);
        } catch (Exception) { return ""; }
    }
}

// ------------------------------------------------------------------------------------------ waveIn / waveOut
static class Winmm {
    public const int WAVE_MAPPED = 0x0004, WHDR_DONE = 1;

    [StructLayout(LayoutKind.Sequential)]
    public struct WAVEFORMATEX { public short wFormatTag, nChannels; public int nSamplesPerSec, nAvgBytesPerSec; public short nBlockAlign, wBitsPerSample, cbSize; }
    [StructLayout(LayoutKind.Sequential)]
    public struct WAVEHDR { public IntPtr lpData; public int dwBufferLength, dwBytesRecorded; public IntPtr dwUser; public int dwFlags, dwLoops; public IntPtr lpNext, reserved; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct WAVEINCAPS { public short wMid, wPid; public int vDriverVersion; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string szPname; public int dwFormats; public short wChannels, wReserved1; }
    [StructLayout(LayoutKind.Sequential, CharSet = CharSet.Unicode)]
    public struct WAVEOUTCAPS { public short wMid, wPid; public int vDriverVersion; [MarshalAs(UnmanagedType.ByValTStr, SizeConst = 32)] public string szPname; public int dwFormats; public short wChannels, wReserved1; public int dwSupport; }

    [DllImport("winmm.dll")] public static extern int waveInGetNumDevs();
    [DllImport("winmm.dll", CharSet = CharSet.Unicode)] public static extern int waveInGetDevCapsW(IntPtr id, ref WAVEINCAPS caps, int size);
    [DllImport("winmm.dll")] public static extern int waveInOpen(out IntPtr h, int id, ref WAVEFORMATEX fmt, IntPtr cb, IntPtr inst, int flags);
    [DllImport("winmm.dll")] public static extern int waveInPrepareHeader(IntPtr h, IntPtr hdr, int size);
    [DllImport("winmm.dll")] public static extern int waveInUnprepareHeader(IntPtr h, IntPtr hdr, int size);
    [DllImport("winmm.dll")] public static extern int waveInAddBuffer(IntPtr h, IntPtr hdr, int size);
    [DllImport("winmm.dll")] public static extern int waveInStart(IntPtr h);
    [DllImport("winmm.dll")] public static extern int waveInReset(IntPtr h);
    [DllImport("winmm.dll")] public static extern int waveInClose(IntPtr h);
    [DllImport("winmm.dll")] public static extern int waveOutGetNumDevs();
    [DllImport("winmm.dll", CharSet = CharSet.Unicode)] public static extern int waveOutGetDevCapsW(IntPtr id, ref WAVEOUTCAPS caps, int size);
    [DllImport("winmm.dll")] public static extern int waveOutOpen(out IntPtr h, int id, ref WAVEFORMATEX fmt, IntPtr cb, IntPtr inst, int flags);
    [DllImport("winmm.dll")] public static extern int waveOutPrepareHeader(IntPtr h, IntPtr hdr, int size);
    [DllImport("winmm.dll")] public static extern int waveOutUnprepareHeader(IntPtr h, IntPtr hdr, int size);
    [DllImport("winmm.dll")] public static extern int waveOutWrite(IntPtr h, IntPtr hdr, int size);
    [DllImport("winmm.dll")] public static extern int waveOutReset(IntPtr h);
    [DllImport("winmm.dll")] public static extern int waveOutClose(IntPtr h);

    public static readonly int HS = Marshal.SizeOf(typeof(WAVEHDR));
    public static readonly int FLAGS = (int)Marshal.OffsetOf(typeof(WAVEHDR), "dwFlags");
    public static readonly int RECORDED = (int)Marshal.OffsetOf(typeof(WAVEHDR), "dwBytesRecorded");

    public static WAVEFORMATEX Pcm16(int rate, int channels) {
        var f = new WAVEFORMATEX();
        f.wFormatTag = 1; f.nChannels = (short)channels; f.nSamplesPerSec = rate; f.wBitsPerSample = 16;
        f.nBlockAlign = (short)(channels * 2); f.nAvgBytesPerSec = rate * channels * 2;
        return f;
    }

    public static void SetHeader(IntPtr hdr, IntPtr data, int length) {
        var w = new WAVEHDR(); w.lpData = data; w.dwBufferLength = length;
        Marshal.StructureToPtr(w, hdr, false);
    }
}

/** A device as winmm numbers it, with its full name (winmm cuts names at 31 characters). */
public class Dev {
    public int Index; public string Name;
    public override string ToString() { return Name; }

    static string Full(string shortName, List<string> full) {
        foreach (var f in full) if (f.StartsWith(shortName, StringComparison.OrdinalIgnoreCase)) return f;
        return shortName;
    }

    public static List<Dev> Inputs() {
        var full = CoreAudio.Names(1);
        var res = new List<Dev>();
        int n = Winmm.waveInGetNumDevs();
        for (int i = 0; i < n; i++) {
            var c = new Winmm.WAVEINCAPS();
            if (Winmm.waveInGetDevCapsW((IntPtr)i, ref c, Marshal.SizeOf(typeof(Winmm.WAVEINCAPS))) != 0) continue;
            var d = new Dev(); d.Index = i; d.Name = Full(c.szPname, full); res.Add(d);
        }
        return res;
    }

    public static List<Dev> Outputs() {
        var full = CoreAudio.Names(0);
        var res = new List<Dev>();
        int n = Winmm.waveOutGetNumDevs();
        for (int i = 0; i < n; i++) {
            var c = new Winmm.WAVEOUTCAPS();
            if (Winmm.waveOutGetDevCapsW((IntPtr)i, ref c, Marshal.SizeOf(typeof(Winmm.WAVEOUTCAPS))) != 0) continue;
            var d = new Dev(); d.Index = i; d.Name = Full(c.szPname, full); res.Add(d);
        }
        return res;
    }

    public static Dev Find(List<Dev> list, string start) {
        if (string.IsNullOrEmpty(start)) return null;
        foreach (var d in list) if (d.Name.StartsWith(start, StringComparison.OrdinalIgnoreCase)) return d;
        foreach (var d in list) if (d.Name.IndexOf(start, StringComparison.OrdinalIgnoreCase) >= 0) return d;
        return null;
    }
}

/** Sound written to one output device, a queue of buffers (when all are still playing, the newest sound is dropped). */
class WaveOutSink : IDisposable {
    IntPtr h = IntPtr.Zero;
    readonly IntPtr[] hdr, mem;
    readonly bool[] used;
    readonly int n, cap;
    int next;

    public WaveOutSink(int device, int rate, int channels, int count, int capacity) {
        var f = Winmm.Pcm16(rate, channels);
        int r = Winmm.waveOutOpen(out h, device, ref f, IntPtr.Zero, IntPtr.Zero, Winmm.WAVE_MAPPED);
        if (r != 0) { h = IntPtr.Zero; throw new Exception("waveOutOpen " + r); }
        n = count; cap = capacity;
        hdr = new IntPtr[n]; mem = new IntPtr[n]; used = new bool[n];
        for (int i = 0; i < n; i++) { mem[i] = Marshal.AllocHGlobal(cap); hdr[i] = Marshal.AllocHGlobal(Winmm.HS); }
    }

    public void Write(byte[] src, int offset, int count) {
        while (count > 0) {
            int chunk = Math.Min(count, cap);
            int i = Free();
            if (i < 0) return;
            Marshal.Copy(src, offset, mem[i], chunk);
            Winmm.SetHeader(hdr[i], mem[i], chunk);
            Winmm.waveOutPrepareHeader(h, hdr[i], Winmm.HS);
            Winmm.waveOutWrite(h, hdr[i], Winmm.HS);
            used[i] = true;
            offset += chunk; count -= chunk;
        }
    }

    int Free() {
        for (int k = 0; k < n; k++) {
            int i = (next + k) % n;
            if (used[i] && (Marshal.ReadInt32(hdr[i], Winmm.FLAGS) & Winmm.WHDR_DONE) != 0) {
                Winmm.waveOutUnprepareHeader(h, hdr[i], Winmm.HS);
                used[i] = false;
            }
            if (!used[i]) { next = (i + 1) % n; return i; }
        }
        return -1;
    }

    public void Dispose() {
        if (h == IntPtr.Zero) return;
        Winmm.waveOutReset(h);
        for (int i = 0; i < n; i++) {
            if (used[i]) Winmm.waveOutUnprepareHeader(h, hdr[i], Winmm.HS);
            Marshal.FreeHGlobal(mem[i]); Marshal.FreeHGlobal(hdr[i]);
        }
        Winmm.waveOutClose(h);
        h = IntPtr.Zero;
    }
}

/** A microphone passed on to an output device (here: into the cable the emulator records from; -1: only its level). */
public class MicBridge {
    public volatile float Level = -100f; // dBFS of the last 20 ms
    public volatile string Error;
    volatile bool run;
    Thread t;
    int mic, target;

    public void Start(int micDevice, int targetDevice) {
        Stop();
        mic = micDevice; target = targetDevice; Error = null; run = true;
        t = new Thread(Loop); t.IsBackground = true; t.Start();
    }

    public void Stop() {
        run = false;
        if (t != null) { t.Join(2000); t = null; }
        Level = -100f;
    }

    void Loop() {
        const int RATE = 48000, N = 10, BYTES = 960 * 2; // 20 ms buffers
        IntPtr h = IntPtr.Zero;
        var hdr = new IntPtr[N];
        var mem = new IntPtr[N];
        WaveOutSink sink = null;
        try {
            if (target >= 0) sink = new WaveOutSink(target, RATE, 1, 16, BYTES); // otherwise only its level
            var f = Winmm.Pcm16(RATE, 1);
            int r = Winmm.waveInOpen(out h, mic, ref f, IntPtr.Zero, IntPtr.Zero, Winmm.WAVE_MAPPED);
            if (r != 0) { h = IntPtr.Zero; Error = "waveInOpen " + r; return; }
            for (int i = 0; i < N; i++) {
                mem[i] = Marshal.AllocHGlobal(BYTES); hdr[i] = Marshal.AllocHGlobal(Winmm.HS);
                Winmm.SetHeader(hdr[i], mem[i], BYTES);
                Winmm.waveInPrepareHeader(h, hdr[i], Winmm.HS);
                Winmm.waveInAddBuffer(h, hdr[i], Winmm.HS);
            }
            Winmm.waveInStart(h);
            var buf = new byte[BYTES];
            int cur = 0;
            while (run) {
                if ((Marshal.ReadInt32(hdr[cur], Winmm.FLAGS) & Winmm.WHDR_DONE) == 0) { Thread.Sleep(5); continue; }
                int got = Math.Min(Marshal.ReadInt32(hdr[cur], Winmm.RECORDED), BYTES);
                if (got > 0) {
                    Marshal.Copy(mem[cur], buf, 0, got);
                    double sum = 0;
                    for (int i = 0; i + 1 < got; i += 2) { short s = (short)(buf[i] | (buf[i + 1] << 8)); sum += (double)s * s; }
                    Level = (float)(20 * Math.Log10(Math.Sqrt(sum / Math.Max(1, got / 2)) / 32768 + 1e-9));
                    if (sink != null) sink.Write(buf, 0, got);
                }
                Winmm.waveInUnprepareHeader(h, hdr[cur], Winmm.HS);
                Winmm.SetHeader(hdr[cur], mem[cur], BYTES);
                Winmm.waveInPrepareHeader(h, hdr[cur], Winmm.HS);
                Winmm.waveInAddBuffer(h, hdr[cur], Winmm.HS);
                cur = (cur + 1) % N;
            }
        } catch (Exception e) {
            Error = e.Message;
        } finally {
            if (h != IntPtr.Zero) {
                Winmm.waveInReset(h);
                for (int i = 0; i < N; i++) if (hdr[i] != IntPtr.Zero) Winmm.waveInUnprepareHeader(h, hdr[i], Winmm.HS);
                Winmm.waveInClose(h);
            }
            for (int i = 0; i < N; i++) {
                if (mem[i] != IntPtr.Zero) Marshal.FreeHGlobal(mem[i]);
                if (hdr[i] != IntPtr.Zero) Marshal.FreeHGlobal(hdr[i]);
            }
            if (sink != null) sink.Dispose();
        }
    }
}

/** What plays on Windows' default output (where the emulator plays) passed on to another output device. */
public class SpeakerBridge {
    public volatile string Error;
    volatile bool run;
    Thread t;
    int target;

    public void Start(int targetDevice) {
        Stop();
        target = targetDevice; Error = null; run = true;
        t = new Thread(Loop); t.IsBackground = true;
        t.SetApartmentState(ApartmentState.MTA); // the audio client lives on this thread
        t.Start();
    }

    public void Stop() {
        run = false;
        if (t != null) { t.Join(2000); t = null; }
    }

    void Loop() {
        IntPtr mix = IntPtr.Zero;
        IAudioClient client = null;
        WaveOutSink sink = null;
        try {
            var e = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
            IMMDevice dev;
            if (e.GetDefaultAudioEndpoint(0, 0, out dev) != 0) { Error = "no default output"; return; }
            Guid iid = typeof(IAudioClient).GUID;
            object o;
            int r = dev.Activate(ref iid, 23, IntPtr.Zero, out o);
            if (r != 0) { Error = "Activate " + r; return; }
            client = (IAudioClient)o;
            client.GetMixFormat(out mix);
            short tag = Marshal.ReadInt16(mix, 0);
            int channels = Marshal.ReadInt16(mix, 2);
            int rate = Marshal.ReadInt32(mix, 4);
            int bits = Marshal.ReadInt16(mix, 14);
            bool isFloat = tag == 3;
            if (tag == unchecked((short)0xFFFE)) {
                var sub = new byte[16];
                Marshal.Copy(new IntPtr(mix.ToInt64() + 24), sub, 0, 16);
                isFloat = new Guid(sub) == new Guid("00000003-0000-0010-8000-00aa00389b71");
            }
            r = client.Initialize(0, 0x00020000 /* loopback */, 2000000 /* 200 ms */, 0, mix, IntPtr.Zero);
            if (r != 0) { Error = "Initialize " + r; return; }
            iid = typeof(IAudioCaptureClient).GUID;
            client.GetService(ref iid, out o);
            var cap = (IAudioCaptureClient)o;
            int outCh = Math.Min(channels, 2);
            sink = new WaveOutSink(target, rate, outCh, 24, rate / 50 * outCh * 2 * 2);
            client.Start();
            var pcm = new byte[0];
            var fl = new float[0];
            while (run) {
                uint packet;
                if (cap.GetNextPacketSize(out packet) != 0) break;
                while (packet > 0 && run) {
                    IntPtr data; uint frames, flags; ulong p1, p2;
                    if (cap.GetBuffer(out data, out frames, out flags, out p1, out p2) != 0) break;
                    int count = (int)frames * outCh;
                    if (pcm.Length < count * 2) pcm = new byte[count * 2];
                    if ((flags & 2) != 0 || data == IntPtr.Zero) {
                        Array.Clear(pcm, 0, count * 2); // silent
                    } else if (isFloat && bits == 32) {
                        int all = (int)frames * channels;
                        if (fl.Length < all) fl = new float[all];
                        Marshal.Copy(data, fl, 0, all);
                        for (int f = 0; f < frames; f++) for (int c = 0; c < outCh; c++) {
                            float v = fl[f * channels + c];
                            int s = (int)(Math.Max(-1f, Math.Min(1f, v)) * 32767);
                            pcm[(f * outCh + c) * 2] = (byte)s; pcm[(f * outCh + c) * 2 + 1] = (byte)(s >> 8);
                        }
                    } else if (bits == 16) {
                        var raw = new byte[(int)frames * channels * 2];
                        Marshal.Copy(data, raw, 0, raw.Length);
                        for (int f = 0; f < frames; f++) for (int c = 0; c < outCh; c++) {
                            pcm[(f * outCh + c) * 2] = raw[(f * channels + c) * 2];
                            pcm[(f * outCh + c) * 2 + 1] = raw[(f * channels + c) * 2 + 1];
                        }
                    } else {
                        Array.Clear(pcm, 0, count * 2); // a format this does not convert
                    }
                    cap.ReleaseBuffer(frames);
                    sink.Write(pcm, 0, count * 2);
                    if (cap.GetNextPacketSize(out packet) != 0) break;
                }
                Thread.Sleep(5);
            }
            client.Stop();
        } catch (Exception ex) {
            Error = ex.Message;
        } finally {
            if (sink != null) sink.Dispose();
            if (mix != IntPtr.Zero) Marshal.FreeCoTaskMem(mix);
            if (client != null) Marshal.ReleaseComObject(client);
        }
    }
}

// ------------------------------------------------------------------------------------------ the window
public class MainForm : Form {
    const string CABLE_IN = "CABLE Input", CABLE_OUT = "CABLE Output";
    readonly ComboBox micBox = new ComboBox(), spkBox = new ComboBox();
    readonly ProgressBar meter = new ProgressBar();
    readonly Label heard = new Label(), status = new Label();
    readonly MicBridge micBridge = new MicBridge();
    readonly SpeakerBridge spkBridge = new SpeakerBridge();
    readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
    readonly string saved = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "amri-emu-mic.txt");
    Dev cable;
    bool filling;

    public MainForm() {
        Text = "عمري — مايك المحاكي";
        RightToLeft = RightToLeft.Yes;
        RightToLeftLayout = true;
        Font = new Font("Segoe UI", 11f);
        FormBorderStyle = FormBorderStyle.FixedDialog;
        MaximizeBox = false;
        ClientSize = new Size(560, 330);
        StartPosition = FormStartPosition.CenterScreen;

        var l1 = new Label(); l1.Text = "المايك اللي يسمع منه المحاكي"; l1.SetBounds(20, 16, 520, 26);
        micBox.DropDownStyle = ComboBoxStyle.DropDownList; micBox.SetBounds(20, 44, 520, 30);
        meter.SetBounds(20, 82, 400, 14); meter.Maximum = 100;
        heard.SetBounds(430, 76, 110, 26);
        var l2 = new Label(); l2.Text = "السماعة اللي تسمع منها المحاكي"; l2.SetBounds(20, 112, 520, 26);
        spkBox.DropDownStyle = ComboBoxStyle.DropDownList; spkBox.SetBounds(20, 140, 520, 30);
        status.SetBounds(20, 182, 520, 100); status.ForeColor = Color.DimGray;
        var refresh = new Button(); refresh.Text = "تحديث القائمة"; refresh.SetBounds(20, 286, 150, 32);
        refresh.Click += delegate { Fill(); };
        var settings = new Button(); settings.Text = "إعدادات الصوت في ويندوز"; settings.SetBounds(180, 286, 220, 32);
        settings.Click += delegate { try { System.Diagnostics.Process.Start("ms-settings:sound"); } catch (Exception) { } };
        Activated += delegate { if (!filling) Fill(); }; // back from Windows' settings: what changed there
        Controls.AddRange(new Control[] { l1, micBox, meter, heard, l2, spkBox, status, refresh, settings });

        micBox.SelectedIndexChanged += delegate { if (!filling) { StartMic(); Save(); } };
        spkBox.SelectedIndexChanged += delegate { if (!filling) { StartSpeaker(); Save(); } };
        timer.Interval = 60;
        timer.Tick += delegate { Tick(); };
        timer.Start();
        FormClosing += delegate { timer.Stop(); micBridge.Stop(); spkBridge.Stop(); };
        Fill();
    }

    void Fill() {
        filling = true;
        string[] last = new string[] { "", "" };
        try { if (File.Exists(saved)) { var s = File.ReadAllLines(saved); for (int i = 0; i < Math.Min(2, s.Length); i++) last[i] = s[i]; } } catch (Exception) { }
        var micWas = micBox.SelectedItem as Dev; var spkWas = spkBox.SelectedItem as Dev;
        string micName = micWas != null ? micWas.Name : last[0];
        string spkName = spkWas != null ? spkWas.Name : (spkBox.SelectedIndex == 0 ? "-" : last[1]);

        cable = Dev.Find(Dev.Outputs(), CABLE_IN);
        micBox.Items.Clear();
        foreach (var d in Dev.Inputs()) if (!d.Name.StartsWith(CABLE_OUT, StringComparison.OrdinalIgnoreCase)) micBox.Items.Add(d);
        spkBox.Items.Clear();
        spkBox.Items.Add("بدون");
        foreach (var d in Dev.Outputs()) if (!d.Name.StartsWith("CABLE", StringComparison.OrdinalIgnoreCase)) spkBox.Items.Add(d);
        // the emulator hears Windows' default input: through the cable this window feeds, or a microphone directly
        if (ViaCable()) Pick(micBox, micName); else Pick(micBox, CoreAudio.DefaultName(1));
        if (micBox.SelectedIndex < 0 && micBox.Items.Count > 0) micBox.SelectedIndex = 0;
        if (spkName == "-" || !Pick(spkBox, string.IsNullOrEmpty(spkName) ? CoreAudio.DefaultName(0) : spkName)) spkBox.SelectedIndex = 0;
        filling = false;
        StartMic();
        StartSpeaker();
    }

    static bool ViaCable() { return CoreAudio.DefaultName(1).StartsWith(CABLE_OUT, StringComparison.OrdinalIgnoreCase); }

    static bool Pick(ComboBox box, string name) {
        if (string.IsNullOrEmpty(name)) return false;
        for (int i = 0; i < box.Items.Count; i++) {
            var d = box.Items[i] as Dev;
            if (d != null && d.Name == name) { box.SelectedIndex = i; return true; }
        }
        return false;
    }

    void Save() {
        try {
            var m = micBox.SelectedItem as Dev; var s = spkBox.SelectedItem as Dev;
            File.WriteAllLines(saved, new string[] { m != null ? m.Name : "", s != null ? s.Name : "-" });
        } catch (Exception) { }
    }

    void StartMic() {
        micBridge.Stop();
        var m = micBox.SelectedItem as Dev;
        // through the cable when the emulator listens to it; otherwise only the level (the emulator hears the default)
        if (m != null) micBridge.Start(m.Index, ViaCable() && cable != null ? cable.Index : -1);
        UpdateStatus();
    }

    void StartSpeaker() {
        spkBridge.Stop();
        var s = spkBox.SelectedItem as Dev;
        string def = CoreAudio.DefaultName(0);
        // the emulator plays there already (and passing it on to itself would echo)
        if (s != null && !s.Name.Equals(def, StringComparison.OrdinalIgnoreCase)) spkBridge.Start(s.Index);
        UpdateStatus();
    }

    void UpdateStatus() {
        string defIn = CoreAudio.DefaultName(1), defOut = CoreAudio.DefaultName(0);
        var lines = new List<string>();
        var m = micBox.SelectedItem as Dev;
        const string next = "تكلم وشوف الشريط يتحرك. بعدها في عمري: الإعدادات ← المساعد الصوتي ← علّم عمري صوتك.";
        if (ViaCable()) {
            if (cable == null) lines.Add("ما لقيت VB-Audio Virtual Cable (CABLE Input) في هالكمبيوتر، وهو اللي يوصل المايك للمحاكي.");
            else { lines.Add("المحاكي يسمع المايك اللي تختاره هنا."); lines.Add(next); }
        } else if (m != null && m.Name.Equals(defIn, StringComparison.OrdinalIgnoreCase)) {
            lines.Add("المحاكي يسمع هالمايك مباشرة (هو المايك الافتراضي في ويندوز).");
            lines.Add(next);
        } else {
            lines.Add("المحاكي يسمع «" + defIn + "» (المايك الافتراضي في ويندوز).");
            lines.Add("عشان يسمع مايك ثاني: خلّه هو الافتراضي من «إعدادات الصوت في ويندوز» ← الإدخال.");
        }
        var s = spkBox.SelectedItem as Dev;
        if (s != null && s.Name.Equals(defOut, StringComparison.OrdinalIgnoreCase)) lines.Add("المحاكي يطلع صوته في هالسماعة أصلاً.");
        status.Text = string.Join("\n", lines.ToArray());
    }

    void Tick() {
        float db = micBridge.Level;
        meter.Value = (int)Math.Max(0, Math.Min(100, (db + 60) * 100 / 60));
        heard.Text = db > -45 ? "يسمعك ✓" : "";
        string err = micBridge.Error ?? spkBridge.Error;
        if (err != null && !status.Text.StartsWith("ما قدرت")) status.Text = "ما قدرت أفتح الجهاز المختار (" + err + "). اختر غيره أو اضغط تحديث القائمة.";
    }
}

public static class App {
    [STAThread]
    public static void Run() {
        Application.EnableVisualStyles();
        Application.Run(new MainForm());
    }

    /** The window drawn into a PNG (to check its layout without showing it). */
    public static void Snapshot(string path) {
        Application.EnableVisualStyles();
        var f = new MainForm();
        f.StartPosition = FormStartPosition.Manual;
        f.Location = new Point(-3000, -3000);
        f.Show();
        for (int i = 0; i < 20; i++) { Application.DoEvents(); Thread.Sleep(50); }
        using (var bmp = new Bitmap(f.Width, f.Height)) {
            f.DrawToBitmap(bmp, new Rectangle(0, 0, f.Width, f.Height));
            bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
        }
        f.Close();
    }

    /** Without the window (tests): the microphone whose name starts with [mic], for [seconds]. */
    public static void Headless(string mic, string speaker, int seconds) {
        var cable = Dev.Find(Dev.Outputs(), "CABLE Input");
        var m = Dev.Find(Dev.Inputs(), mic);
        if (cable == null || m == null) { Console.WriteLine("not found: " + (cable == null ? "CABLE Input" : mic)); return; }
        var mb = new MicBridge();
        mb.Start(m.Index, cable.Index);
        SpeakerBridge sb = null;
        var s = string.IsNullOrEmpty(speaker) || speaker == "none" ? null : Dev.Find(Dev.Outputs(), speaker);
        if (s != null) { sb = new SpeakerBridge(); sb.Start(s.Index); }
        Console.WriteLine("passing " + m.Name + " -> " + cable.Name + (s != null ? "; default output -> " + s.Name : ""));
        for (int i = 0; i < seconds; i++) {
            Thread.Sleep(1000);
            Console.WriteLine(string.Format("{0,3} s  mic {1,6:0.0} dBFS{2}", i + 1, mb.Level, mb.Error != null ? "  error " + mb.Error : "") +
                (sb != null && sb.Error != null ? "  speaker error " + sb.Error : ""));
        }
        mb.Stop();
        if (sb != null) sb.Stop();
    }
}
}
