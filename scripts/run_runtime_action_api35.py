#!/usr/bin/env python3
"""Run the existing ten boundary regressions on one disposable API35 x86_64 AVD.

The real Android app/session/Room code receives fictional injected HTTP replies.
No real cloud, phone/SMS, physical-device or full two-device acceptance is claimed.
Requires the official SDK tools and pre-installed API35 google_apis x86_64 image.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
TEST_CLASS = 'com.group33.medicationreminder.integration.RuntimeActionBoundaryTest'
RUNNER = 'com.group33.medicationreminder.test/androidx.test.runner.AndroidJUnitRunner'


def inspect_instrumentation(log):
    completed = []
    pending = {}
    for line in log.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if separator:
                pending[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.rsplit(': ', 1)[1])
            if code != 1 and pending.get('class') == TEST_CLASS and 'test' in pending:
                completed.append(dict(test=pending['test'], code=code))
            pending = {}
    passed = (len(completed) == 10 and len({x['test'] for x in completed}) == 10
              and all(x['code'] == 0 for x in completed)
              and re.search(r'^OK \(10 tests\)$', log, re.M) is not None
              and 'FAILURES!!!' not in log)
    return dict(passed=passed, completed=completed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    adb = sdk / 'platform-tools/adb'
    emulator = sdk / 'emulator/emulator'
    avdmanager = shutil.which('avdmanager')
    if avdmanager is None:
        avdmanager = str(sdk / 'cmdline-tools/latest/bin/avdmanager')
    serial = 'emulator-5580'
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if head != os.environ.get('EXPECTED_HEAD'):
        raise RuntimeError('Checked-out head differs from the reviewed PR head')
    result = dict(head=head, api=None, abi=None, serial=serial, passed=False,
                  physical_device=False, live_http=False, tests=TEST_CLASS)
    apk_paths = ['android-app/build/outputs/apk/debug/android-app-debug.apk',
                 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk']
    proof_paths = apk_paths + [
        'android-app/src/androidTest/kotlin/com/group33/medicationreminder/integration/RuntimeActionBoundaryTest.kt',
        'android-app/src/debug/kotlin/com/group33/medicationreminder/integration/DebugDoseReminders.kt',
        'android-app/src/debug/kotlin/com/group33/medicationreminder/integration/DebugChainRepository.kt',
        'android-app/src/main/kotlin/com/group33/medicationreminder/core/reminder/ReminderCoordinator.kt']
    result['sha256'] = {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in proof_paths}
    avd_root = Path(tempfile.mkdtemp(prefix='group33-api35-', dir=out.parent))
    env = dict(os.environ, ANDROID_AVD_HOME=str(avd_root))
    process = None

    def device(*command, timeout=30):
        return subprocess.run([str(adb), '-s', serial, *command], capture_output=True,
                              text=True, timeout=timeout, env=env)

    try:
        devices = subprocess.check_output([str(adb), 'devices'], text=True, env=env)
        if serial in devices:
            raise RuntimeError('Refusing to use an existing emulator on the selected test port')
        created = subprocess.run([avdmanager, 'create', 'avd', '--name', 'group33_runtime_api35',
            '--path', str(avd_root / 'runtime.avd'), '--package', 'system-images;android-35;google_apis;x86_64',
            '--device', 'pixel_6'], input='no\n', capture_output=True, text=True, env=env, timeout=60)
        (out / 'create-avd.log').write_text(created.stdout + created.stderr)
        created.check_returncode()
        with (out / 'emulator.log').open('w') as log:
            process = subprocess.Popen([str(emulator), '-avd', 'group33_runtime_api35',
                '-port', '5580', '-no-window', '-no-audio', '-no-snapshot', '-no-boot-anim',
                '-gpu', 'swiftshader_indirect', '-accel', 'on', '-memory', '3072', '-cores', '2',
                '-camera-back', 'none', '-camera-front', 'none'], env=env, stdout=log, stderr=log)
            deadline = time.monotonic() + 420
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Disposable emulator exited before boot')
                if device('shell', 'getprop', 'sys.boot_completed', timeout=10).stdout.strip() == '1':
                    break
                time.sleep(2)
            else:
                raise RuntimeError('API35 boot deadline exceeded')
            result['api'] = device('shell', 'getprop', 'ro.build.version.sdk').stdout.strip()
            result['abi'] = device('shell', 'getprop', 'ro.product.cpu.abi').stdout.strip()
            if result['api'] != '35' or result['abi'] != 'x86_64':
                raise RuntimeError('Wrong API or ABI; no acceptance result will be recorded')
            for p in apk_paths:
                device('install', '-r', str(ROOT / p), timeout=120).check_returncode()
            run = device('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', TEST_CLASS, RUNNER, timeout=180)
            output = run.stdout + run.stderr
            (out / 'instrumentation.log').write_text(output)
            result.update(inspect_instrumentation(output))
            result['adb_exit'] = run.returncode
            result['passed'] = result['passed'] and run.returncode == 0
            if not result['passed']:
                raise RuntimeError('All ten exact-head API35 regressions must pass')
    finally:
        if process is not None and process.poll() is None:
            try:
                device('emu', 'kill', timeout=10)
                process.wait(timeout=20)
            except (subprocess.TimeoutExpired, OSError):
                process.kill()
                process.wait(timeout=10)
        result['emulator_stopped'] = process is None or process.poll() is not None
        shutil.rmtree(avd_root)
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)
    if not result['passed']:
        raise RuntimeError('API35 acceptance failed')


if __name__ == '__main__':
    main()
