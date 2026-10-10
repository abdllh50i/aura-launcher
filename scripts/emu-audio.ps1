# The emulator (CarUnit) with the PC's microphone and speakers, for trying the voice assistant "Amri".
# The emulator records from and plays to the devices Windows gives it: Windows' default input and output, or the ones
# chosen for the emulator alone in Windows' volume mixer (the app "qemu-system-x86_64"), e.g. a headset's microphone
# and earphones while the rest of the PC keeps its own devices. Windows remembers that choice.
#   powershell -ExecutionPolicy Bypass -File scripts\emu-audio.ps1             choose the devices, then restart the emulator
#   powershell -ExecutionPolicy Bypass -File scripts\emu-audio.ps1 -NoSettings  restart only
# If the app still hears nothing (AMRI: Settings > Voice assistant > Teach Amri your voice: the bar stays empty):
# Windows Settings > Privacy & security > Microphone > "Let desktop apps access your microphone" must be on.
param([switch]$NoSettings)
$adb = "E:\Android\Sdk\platform-tools\adb.exe"
$emu = "E:\Android\Sdk\emulator\emulator.exe"
$serial = "emulator-5570"
$env:ANDROID_AVD_HOME = "D:\k2501-work\avd"
$config = Join-Path $env:ANDROID_AVD_HOME "CarUnit.avd\config.ini"

# without hw.audioInput the virtual device has no microphone: the app gets silence whatever Windows does
$lines = @(Get-Content $config)
if ($lines -notcontains "hw.audioInput = yes") {
    $lines = @($lines | Where-Object { $_ -notmatch '^hw\.audioInput\s*=' }) + "hw.audioInput = yes"
    Set-Content -Path $config -Value $lines -Encoding ASCII
    Write-Output "The emulator now has a microphone (hw.audioInput = yes in $config)."
}

Add-Type -TypeDefinition @"
using System;
using System.Runtime.InteropServices;
using System.Collections.Generic;

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
[StructLayout(LayoutKind.Explicit, Size = 24)] struct PropVar {
  [FieldOffset(0)] public short vt;
  [FieldOffset(8)] public IntPtr p;
}
// Windows' sound devices that are on, the defaults marked (read only)
public static class AmriAudioDevices {
  static string Name(IMMDevice d) {
    IPropertyStore ps; d.OpenPropertyStore(0, out ps);
    PKey k = new PKey(); k.fmtid = new Guid("a45c254e-df1c-4efd-8020-67d146a850e0"); k.pid = 14;
    PropVar v; ps.GetValue(ref k, out v);
    return v.vt == 31 ? Marshal.PtrToStringUni(v.p) : "?";
  }
  static string Id(IMMDevice d) { string id; d.GetId(out id); return id; }
  public static List<string> List() {
    var e = (IMMDeviceEnumerator)new MMDeviceEnumeratorCom();
    var res = new List<string>();
    string[] flows = { "Output", "Input " };
    for (int f = 0; f < 2; f++) {
      string def = "";
      IMMDevice d;
      if (e.GetDefaultAudioEndpoint(f, 0, out d) == 0) def = Id(d);
      IMMDeviceCollection c; e.EnumAudioEndpoints(f, 1, out c);
      int n; c.GetCount(out n);
      for (int i = 0; i < n; i++) {
        IMMDevice x; c.Item(i, out x);
        res.Add("  " + flows[f] + "  " + Name(x) + (Id(x) == def ? "   <- Windows default" : ""));
      }
    }
    return res;
  }
}
"@

function Start-Emulator {
    Start-Process -FilePath $emu -ArgumentList "-avd", "CarUnit", "-port", "5570", "-gpu", "host", "-no-boot-anim", "-allow-host-audio", "-no-snapshot-load"
    $t0 = Get-Date
    do {
        Start-Sleep -Seconds 4
        $booted = (& $adb -s $serial shell getprop sys.boot_completed 2>$null)
    } while ($booted -ne "1" -and ((Get-Date) - $t0).TotalSeconds -lt 240)
    if ($booted -ne "1") { throw "the emulator did not boot" }
    & $adb -s $serial root 2>$null | Out-Null
    Start-Sleep -Seconds 3
    & $adb -s $serial wait-for-device
    & $adb -s $serial shell "setenforce 0; pm grant com.abdllh.aura android.permission.RECORD_AUDIO" | Out-Null
}

function Stop-Emulator {
    & $adb -s $serial emu kill 2>$null | Out-Null
    $t0 = Get-Date
    while ((Get-Process qemu-system-x86_64 -ErrorAction SilentlyContinue) -and ((Get-Date) - $t0).TotalSeconds -lt 60) { Start-Sleep -Seconds 1 }
}

Write-Output "Windows' sound devices now:"
try { [AmriAudioDevices]::List() } catch { Write-Output "  (could not list them: $_)" }

if (-not $NoSettings) {
    if (-not (Get-Process qemu-system-x86_64 -ErrorAction SilentlyContinue)) {
        Write-Output "Starting the emulator (it has to run to show up in Windows' volume mixer)..."
        Start-Emulator
    }
    Start-Process "ms-settings:apps-volume"
    Write-Output @"

In the window that opened (Settings > System > Sound > Volume mixer), under Apps open
"qemu-system-x86_64" (the emulator; maybe "Android Emulator") and choose your headset as its
Output device (the earphones) and Input device (its microphone). Only the emulator changes.
  - The headset is not in the list: connect it to the PC first (it is not in the list above either).
  - The emulator is not in the list: tap the microphone button on AMRI's home screen (it chimes) and look again.
  - Or choose the headset for the whole PC: Settings > System > Sound > Output and Input.
"@
    Read-Host "Then press Enter here to restart the emulator with them"
}

Write-Output "Restarting the emulator with sound and the microphone..."
Stop-Emulator
Start-Emulator
Write-Output "Done. In AMRI: Settings > Voice assistant > Teach Amri your voice (the bar moves when you speak)."
