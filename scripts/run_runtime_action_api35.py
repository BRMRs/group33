#!/usr/bin/env python3
"""Run boundaries, real-clock automatic recovery and real PostgreSQL replay verification.

The real Android app/session/Room code receives fictional injected HTTP replies.
No real cloud, phone/SMS, physical-device or full two-device acceptance is claimed.
Requires SDK tools, API35 google_apis x86_64 image, built Server JAR, psql and explicit
S2_RECOVERY_PG_* loopback test credentials. Automatic mode clears only its disposable test app.
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
TEST_CLASSES = ('com.group33.medicationreminder.integration.RuntimeActionBoundaryTest',
                'com.group33.medicationreminder.integration.RuntimeNotificationBoundaryTest',
                'com.group33.medicationreminder.elder.ElderReminderPermissionBannerLayoutTest',
                'com.group33.medicationreminder.elder.ElderReminderPermissionNavigatorDeviceTest',
                'com.group33.medicationreminder.elder.ElderReminderPermissionPanelDeviceTest')
EXPECTED_TEST_COUNTS = (10, 3, 4, 1, 2)
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
    expected_total = sum(EXPECTED_TEST_COUNTS)
    passed = (len(completed) == expected_total
              and len({(x['suite'], x['test']) for x in completed}) == expected_total
              and [sum(x['suite'] == suite for x in completed) for suite in TEST_CLASSES]
              == list(EXPECTED_TEST_COUNTS)
              and all(x['code'] == 0 for x in completed)
              and re.search(rf'^OK \({expected_total} tests\)$', log, re.M) is not None
              and 'FAILURES!!!' not in log)
    return dict(passed=passed, completed=completed)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    if not all(os.environ.get('S2_RECOVERY_PG_' + key) for key in ('HOST', 'PORT', 'DATABASE', 'USER', 'PASSWORD')):
        raise RuntimeError('Explicit loopback PostgreSQL test settings are required')
    if not (os.environ.get('S2_RECOVERY_PSQL') or shutil.which('psql')):
        raise RuntimeError('A PostgreSQL client is required for real database verification')
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
        'android-app/build.gradle.kts',
        'android-app/src/androidTest/kotlin/com/group33/medicationreminder/core/reminder/AutomaticRecoveryProbeTest.kt',
        'scripts/check_reminder_dispatch_recovery.py',
        'scripts/check_postgres_receipt_recovery.py',
        'android-app/src/androidTest/kotlin/com/group33/medicationreminder/integration/RuntimeActionBoundaryTest.kt',
        'android-app/src/androidTest/kotlin/com/group33/medicationreminder/elder/ElderReminderPermissionBannerLayoutTest.kt',
        'android-app/src/androidTest/kotlin/com/group33/medicationreminder/elder/ElderReminderPermissionNavigatorDeviceTest.kt',
        'android-app/src/androidTest/kotlin/com/group33/medicationreminder/elder/ElderReminderPermissionPanelDeviceTest.kt',
        'android-app/src/androidTest/kotlin/com/group33/medicationreminder/core/reminder/ReminderCapabilityDeviceTest.kt',
        'android-app/src/debug/kotlin/com/group33/medicationreminder/integration/DebugDoseReminders.kt',
        'android-app/src/debug/kotlin/com/group33/medicationreminder/integration/DebugChainRepository.kt',
        'android-app/src/main/kotlin/com/group33/medicationreminder/core/reminder/ReminderCoordinator.kt',
        'android-app/src/main/kotlin/com/group33/medicationreminder/core/reminder/ReminderNotifications.kt',
        'android-app/src/main/kotlin/com/group33/medicationreminder/core/reminder/ReminderAlarmReceiver.kt',
        'android-app/src/main/kotlin/com/group33/medicationreminder/elder/ElderDosePanel.kt',
        'android-app/src/main/kotlin/com/group33/medicationreminder/elder/ElderReminderPermissionNavigator.kt',
        'android-app/src/main/kotlin/com/group33/medicationreminder/elder/ElderReminderPermissionState.kt',
        'android-app/src/androidTest/kotlin/com/group33/medicationreminder/integration/RuntimeNotificationBoundaryTest.kt']
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
            boundary_tests_passed = result['passed']
            result['passed'] = False
            capability_class = ('com.group33.medicationreminder.core.reminder.'
                                'ReminderCapabilityDeviceTest')

            def capability_phase(name, method, phase):
                phase_run = device('shell', 'am', 'instrument', '-w', '-r',
                    '-e', 'class', f'{capability_class}#{method}',
                    '-e', 'capabilityPhase', phase, RUNNER, timeout=90)
                phase_output = phase_run.stdout + phase_run.stderr
                (out / f'capability-{name}.log').write_text(phase_output)
                passed = (phase_run.returncode == 0
                          and re.search(r'^OK \(1 test\)$', phase_output, re.M) is not None
                          and 'INSTRUMENTATION_STATUS_CODE: 0' in phase_output
                          and 'INSTRUMENTATION_STATUS_CODE: -3' not in phase_output
                          and 'FAILURES!!!' not in phase_output
                          and 'SKIPPED' not in phase_output)
                if not passed:
                    raise RuntimeError(f'Capability phase {name} failed or skipped')
                return dict(passed=True, method=method, phase=phase)

            package = 'com.group33.medicationreminder'
            device('shell', 'pm', 'revoke', package,
                   'android.permission.POST_NOTIFICATIONS').check_returncode()
            denied = capability_phase('notification-denied',
                'notificationDeniedLeavesTheSameOccurrenceRecoverable', 'notification-denied')
            device('shell', 'pm', 'grant', package,
                   'android.permission.POST_NOTIFICATIONS').check_returncode()
            granted = capability_phase('notification-granted',
                'notificationGrantRecoversTheSameOccurrence', 'notification-granted')
            device('shell', 'appops', 'set', package, 'SCHEDULE_EXACT_ALARM',
                   'deny').check_returncode()
            try:
                exact = capability_phase('exact-denied',
                    'exactAlarmDenialIsDegradedAndUsesInexactFallback', 'exact-denied')
            finally:
                device('shell', 'appops', 'set', package, 'SCHEDULE_EXACT_ALARM',
                       'allow').check_returncode()
            result['capability_phases'] = dict(
                notification_denied=denied,
                notification_granted=granted,
                exact_denied=exact,
            )
            result['passed'] = boundary_tests_passed and all(
                phase['passed'] for phase in result['capability_phases'].values()
            )
            screenshot = subprocess.run([str(adb), '-s', serial, 'exec-out', 'run-as',
                'com.group33.medicationreminder', 'cat', 'files/runtime-notification-feedback.png'],
                capture_output=True, timeout=20, env=env)
            if screenshot.returncode != 0 or not screenshot.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
                raise RuntimeError('Real notification-shade feedback screenshot is missing')
            (out / 'notification-feedback.png').write_bytes(screenshot.stdout)
            result['notification_screenshot_sha256'] = hashlib.sha256(screenshot.stdout).hexdigest()
            if not result['passed']:
                raise RuntimeError('All exact-head API35 regressions must pass')
            from check_reminder_dispatch_recovery import run_checks
            boundary_passed = result['passed']
            result['passed'] = False
            result['dispatch_recovery'] = run_checks(adb, serial, out / 'dispatch-recovery', env)
            result['passed'] = False
            with (out / 'automatic-runner-build.log').open('w') as automatic_log:
                subprocess.run(['./gradlew', ':android-app:assembleDebugAndroidTest', '-PautomaticRecoveryRunner=true',
                    '--console=plain', '--max-workers=2', '--no-parallel', '--no-daemon'], cwd=ROOT, env=env,
                    stdout=automatic_log, stderr=subprocess.STDOUT, check=True, timeout=300)
            test_apk = ROOT / apk_paths[1]
            result['automatic_test_apk_sha256'] = hashlib.sha256(test_apk.read_bytes()).hexdigest()
            device('install', '-r', str(test_apk), timeout=120).check_returncode()
            result['automatic_recovery'] = run_checks(adb, serial, out / 'automatic-recovery', env,
                                                       skip_room=True, automatic=True)
            from check_postgres_receipt_recovery import run_checks as run_postgres_checks
            result['postgres_recovery'] = run_postgres_checks(out / 'postgres-recovery', env)
            result['passed'] = (boundary_passed and result['dispatch_recovery']['passed']
                                and result['automatic_recovery']['passed'] and result['postgres_recovery']['passed'])
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
