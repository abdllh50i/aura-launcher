"""Run the production restart shell in Android sh with substituted service commands.

Checks ordering and failure gates, not a physical radio or BC03's native code.
Only point this at a disposable emulator; it creates a small temporary directory.
"""
import argparse
import json
from pathlib import Path
import subprocess
import textwrap

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--adb', required=True)
p.add_argument('--serial', default='emulator-5570')
a = p.parse_args()
assert a.serial.startswith('emulator-'), 'Emulator only'
root = Path(__file__).resolve().parents[2]
source = (root/'app/src/main/java/com/abdllh/aura/music/BtTiming.kt').read_text(encoding='utf-8')
script = source.split('private fun restartStack(expected: String)')[1].split('LocalAdb.run("""', 1)[1].split('""".trimIndent()', 1)[0]
expected = json.loads((root/'app/src/main/assets/bt-timing/patch.json').read_text())['patchedSha256']
script = textwrap.dedent(script).replace("${'$'}", '$').replace('$expected', expected)
remote = '/data/local/tmp/amri-bt-restart-test'
script = script.replace('/dev/goc_serial', remote+'/serial')
prefix = '''
scenario=$1
restarted=0
bc03=0
sleep() { :; }
setprop() { restarted=1; }
pidof() {
    if [ "$1" = gocsdk_8800 ]; then
        if [ "$restarted" = 1 ] && [ "$scenario" != unchanged_pid ]; then
            if [ "$scenario" = ready_multi ]; then echo '6994 7153'; else echo 222; fi
        else echo 111; fi
    elif [ "$bc03" = 1 ]; then echo 333; fi
}
getprop() { echo running; }
sha256sum() {
    # Reject exactly the malformed /proc/PID_LIST/exe lookup seen on the unit.
    [ "$#" = 1 ] || { echo 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855  directory'; return 0; }
    case "$1" in /proc/*/exe) ;; *) echo 'not an executable'; return 1;; esac
    if [ "$scenario" = wrong_image ]; then echo 'wrong digest'; else echo 'EXPECTED  executable'; fi
}
am() {
    if [ "$1" = force-stop ]; then
        echo bc03-stopped
        bc03=0
    elif [ "$1" = startservice ]; then
        echo bc03-started
        [ "$scenario" != service_failed ] || return 1
        bc03=1
    else return 1; fi
}
readlink() {
    if [ "$1" = -f ] || { [ "$bc03" = 1 ] && [ "$scenario" != stale_fd ]; }; then echo /dev/pts/current; else echo /dev/pts/old; fi
}
'''.replace('EXPECTED', expected)
local = root/'out/bt-timing/restart-test.sh'
local.parent.mkdir(parents=True, exist_ok=True)
local.write_text(textwrap.dedent(prefix)+script, encoding='utf-8', newline='\n')
adb = [a.adb, '-s', a.serial]
def run(*args):
    return subprocess.run(adb+list(args), text=True, capture_output=True, timeout=30)
assert run('shell', 'id -u').stdout.strip() == '0', 'Emulator adbd must be root'
assert run('shell', f'mkdir -p {remote} && ln -sf /dev/null {remote}/serial').returncode == 0
assert run('push', str(local), remote+'/case.sh').returncode == 0
try:
    for scenario in ['ready', 'ready_multi', 'wrong_image', 'unchanged_pid', 'service_failed', 'stale_fd']:
        result = run('shell', f'sh {remote}/case.sh {scenario}')
        success = scenario in ['ready', 'ready_multi']
        assert result.stdout.strip().endswith('stack-ready') == success, (scenario, result.stdout, result.stderr)
        assert (result.returncode == 0) == success, (scenario, result.returncode)
        if scenario in ['wrong_image', 'unchanged_pid']:
            assert 'bc03-stopped' not in result.stdout, (scenario, result.stdout)
        elif success:
            assert result.stdout.index('bc03-stopped') < result.stdout.index('bc03-started') < result.stdout.index('stack-ready')
        print('PASS:', scenario)
finally:
    run('shell', f'rm -f {remote}/case.sh {remote}/serial && rmdir {remote}')
