# A small window to choose the PC microphone the emulator hears and the speaker it is heard on, for trying the voice
# assistant "Amri" with your own voice. Windows' default devices stay as they are: the chosen microphone is passed on
# into VB-Audio Virtual Cable ("CABLE Input"), whose other end ("CABLE Output", Windows' default input on this PC) is
# what the emulator records (scripts\emu-mic.cs). Double-click scripts\emu-mic.bat, or:
#   powershell -STA -ExecutionPolicy Bypass -File scripts\emu-mic.ps1
#   powershell -ExecutionPolicy Bypass -File scripts\emu-mic.ps1 -Mic HitPaw [-Speaker none] -Seconds 60   (no window)
param([string]$Mic, [string]$Speaker = "none", [int]$Seconds = 30, [string]$Snapshot)
try {
    $src = Get-Content -Raw -Encoding UTF8 (Join-Path $PSScriptRoot "emu-mic.cs")
    Add-Type -TypeDefinition $src -ReferencedAssemblies System.Windows.Forms, System.Drawing
    if ($Snapshot) { [AmriEmuMic.App]::Snapshot($Snapshot) }
    elseif ($Mic) { [AmriEmuMic.App]::Headless($Mic, $Speaker, $Seconds) }
    else { [AmriEmuMic.App]::Run() }
} catch {
    Add-Type -AssemblyName System.Windows.Forms
    if ($Mic) { throw } else { [System.Windows.Forms.MessageBox]::Show("$_", "AMRI emulator microphone") | Out-Null }
}
