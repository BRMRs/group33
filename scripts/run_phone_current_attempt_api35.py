#!/usr/bin/env python3
"""Run the sixteen phone current-attempt regressions on a fresh, job-owned API35 AVD.

Actual Android Keystore/session and HTTP adapters receive fictional responses.
No real Server, cloud, physical device or real phone acceptance is claimed.
Requires a Linux GitHub Actions runner, API35 google_apis x86_64 SDK image and built APKs.
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
TEST_CLASSES = ('com.group33.medicationreminder.auth.CommonPhoneSessionIntegrationTest',
                'com.group33.medicationreminder.auth.PhoneTransportIntegrationTest')
TEST_CLASS = ','.join(TEST_CLASSES)
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
            if code != 1 and pending.get('class') in TEST_CLASSES and 'test' in pending:
                completed.append(dict(test=pending['test'], suite=pending['class'], code=code))
            pending = {}
    passed = (len(completed) == 16 and len({(x['suite'], x['test']) for x in completed}) == 16
              and [sum(x['suite'] == suite for x in completed) for suite in TEST_CLASSES] == [10, 6]
              and all(x['code'] == 0 for x in completed)
              and re.search(r'^OK \(16 tests\)$', log, re.M) is not None
              and 'FAILURES!!!' not in log)
    return dict(passed=passed, completed=completed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('CI') != 'true':
        raise RuntimeError('This disposable x86_64 runner is restricted to GitHub Actions')
    if not out.is_relative_to(Path(os.environ['RUNNER_TEMP']).resolve()):
        raise RuntimeError('Evidence output must remain inside the job temporary directory')
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
                  physical_device=False, live_service=False, loopback_http=True, sdk_initialized=False, tests=TEST_CLASS)
    apk_paths = ['android-app/build/outputs/apk/debug/android-app-debug.apk',
                 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk']
    proof_paths = apk_paths + [
        'android-app/build.gradle.kts',
        'android-app/src/androidTest/kotlin/com/group33/medicationreminder/auth/CommonPhoneSessionIntegrationTest.kt',
        'android-app/src/androidTest/kotlin/com/group33/medicationreminder/auth/PhoneTransportIntegrationTest.kt',
        'android-app/src/debug/kotlin/com/group33/medicationreminder/auth/BoundPhoneExchange.kt',
        'android-app/src/debug/kotlin/com/group33/medicationreminder/auth/CommonPhoneSession.kt',
        'android-app/src/debug/kotlin/com/group33/medicationreminder/auth/FusionPhoneAuthRepository.kt',
        'android-app/src/main/kotlin/com/group33/medicationreminder/core/session/PrivateSessionStore.kt']
    result['sha256'] = {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in proof_paths}
    avd_root = Path(tempfile.mkdtemp(prefix='group33-phone-current-', dir=out.parent))
    env = dict(os.environ, ANDROID_AVD_HOME=str(avd_root))
    process = None

    def device(*command, timeout=30):
        return subprocess.run([str(adb), '-s', serial, *command], capture_output=True,
                              text=True, timeout=timeout, env=env)

    try:
        devices = subprocess.check_output([str(adb), 'devices'], text=True, env=env)
        if serial in devices:
            raise RuntimeError('Refusing to use an existing emulator on the selected test port')
        created = subprocess.run([avdmanager, 'create', 'avd', '--name', 'group33_phone_current_api35',
            '--path', str(avd_root / 'runtime.avd'), '--package', 'system-images;android-35;google_apis;x86_64',
            '--device', 'pixel_6'], input='no\n', capture_output=True, text=True, env=env, timeout=60)
        (out / 'create-avd.log').write_text(created.stdout + created.stderr)
        created.check_returncode()
        with (out / 'emulator.log').open('w') as log:
            process = subprocess.Popen([str(emulator), '-avd', 'group33_phone_current_api35',
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
            name = device('emu', 'avd', 'name')
            if 'group33_phone_current_api35' not in name.stdout:
                raise RuntimeError('Wrong job-owned AVD; refusing installation')
            for p in apk_paths:
                device('install', '-r', str(ROOT / p), timeout=120).check_returncode()
            run = device('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', TEST_CLASS, RUNNER, timeout=180)
            output = run.stdout + run.stderr
            (out / 'instrumentation.log').write_text(output)
            result.update(inspect_instrumentation(output))
            result['adb_exit'] = run.returncode
            result['passed'] = result['passed'] and run.returncode == 0
            if not result['passed']:
                raise RuntimeError('All sixteen exact-head API35 phone current-attempt regressions must pass')
    finally:
        if process is not None and process.poll() is None:
            try:
                name = device('emu', 'avd', 'name', timeout=10)
                if 'group33_phone_current_api35' in name.stdout:
                    device('emu', 'kill', timeout=10)
                else:
                    process.terminate()
                process.wait(timeout=20)
            except (subprocess.TimeoutExpired, OSError):
                process.kill()
                process.wait(timeout=10)
        result['emulator_stopped'] = process is None or process.poll() is not None
        if result['emulator_stopped']:
            shutil.rmtree(avd_root)
        result['private_avd_removed'] = not avd_root.exists()
        result['passed'] = result['passed'] and result['emulator_stopped'] and result['private_avd_removed']
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)
    if not result['passed']:
        raise RuntimeError('API35 acceptance failed')


if __name__ == '__main__':
    main()
