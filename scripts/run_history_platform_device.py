#!/usr/bin/env python3
"""Prove #89 display regressions on fresh job-owned API27/API35 emulators.

The baseline phase overlays only three pinned runtime files, keeping the final
test source unchanged. The final phase restores HEAD and requires all 18 cases
to pass. Expected baseline failures never relax the final acceptance gate.
Only fictional Repository data and disposable emulators are used.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import tempfile
import time

ROOT = Path(__file__).resolve().parents[1]
BASELINE = '532cb1cf451682f2cb462e6aa030ebcc51ab0f7b'
SUITE = 'com.group33.medicationreminder.caregiver.CaregiverHistoryViewTest'
RUNNER = 'com.group33.medicationreminder.test/androidx.test.runner.AndroidJUnitRunner'
RUNTIME = [
    'android-app/src/main/kotlin/com/group33/medicationreminder/caregiver/CaregiverHistoryView.kt',
    'android-app/src/main/kotlin/com/group33/medicationreminder/caregiver/CaregiverHistoryDateRangeDialog.kt',
    'android-app/src/debug/kotlin/com/group33/medicationreminder/caregiver/CaregiverHistoryTestActivity.kt',
]
TEST = 'android-app/src/androidTest/kotlin/com/group33/medicationreminder/caregiver/CaregiverHistoryViewTest.kt'
APKS = ['android-app/build/outputs/apk/debug/android-app-debug.apk',
        'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk']
TARGETS = ['debugHostAppliesSystemInsetsWithoutNestedScrolling',
           'calendarSupportsLeapDayCrossMonthAndSameDayWithoutImplicitReads',
           'calendarGridAndButtonsRemainReadableAtBothFontsAndAllWindowWidths']


def hashes(paths):
    return {p: hashlib.sha256((ROOT / p).read_bytes()).hexdigest() for p in paths}


def completions(output):
    completed, pending = [], {}
    for line in output.splitlines():
        if line.startswith('INSTRUMENTATION_STATUS: '):
            key, separator, value = line[len('INSTRUMENTATION_STATUS: '):].partition('=')
            if separator:
                pending[key] = value
        elif line.startswith('INSTRUMENTATION_STATUS_CODE: '):
            code = int(line.rsplit(': ', 1)[1])
            if code != 1 and pending.get('class') == SUITE and 'test' in pending:
                record = dict(test=pending['test'], code=code)
                if code != 0:
                    record['failure'] = pending.get('stack', '')
                completed.append(record)
            pending = {}
    return completed


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--api', type=int, choices=[27, 35], required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    out = args.output.resolve()
    if os.environ.get('GITHUB_ACTIONS') != 'true' or os.environ.get('CI') != 'true':
        raise RuntimeError('This x86 emulator runner is restricted to GitHub Actions')
    if not out.is_relative_to(Path(os.environ['RUNNER_TEMP']).resolve()):
        raise RuntimeError('Evidence must remain in the job temporary directory')
    out.mkdir(parents=True, exist_ok=True)
    head = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    if head != os.environ.get('EXPECTED_HEAD'):
        raise RuntimeError('Checkout differs from the reviewed head')
    subprocess.run(['git', 'merge-base', '--is-ancestor', BASELINE, head], cwd=ROOT, check=True)
    originals = {p: (ROOT / p).read_bytes() for p in RUNTIME}
    source_hashes = hashes(RUNTIME + [TEST])
    names = re.findall(r'@Test fun (\w+)\(', (ROOT / TEST).read_text())
    if len(names) != 18 or len(set(names)) != 18 or not set(TARGETS).issubset(names):
        raise RuntimeError('Expected the complete original 18 history regressions')
    sdk = Path(os.environ.get('ANDROID_HOME') or os.environ['ANDROID_SDK_ROOT'])
    adb, emulator = sdk / 'platform-tools/adb', sdk / 'emulator/emulator'
    avdmanager = shutil.which('avdmanager') or str(sdk / 'cmdline-tools/latest/bin/avdmanager')
    abi = 'x86' if args.api == 27 else 'x86_64'
    avd_name, serial = 'group33_history_platform', 'emulator-5580'
    avd_root = Path(tempfile.mkdtemp(prefix='group33-history-', dir=out.parent))
    env = dict(os.environ, ANDROID_AVD_HOME=str(avd_root))
    result = dict(head=head, baseline_runtime=BASELINE, api=args.api, abi=abi,
                  source_sha256=source_hashes, physical_device=False,
                  real_server=False, passed=False)
    process = None

    def device(*command, timeout=30):
        return subprocess.run([str(adb), '-s', serial, *command], capture_output=True,
                              text=True, env=env, timeout=timeout)

    try:
        if serial in subprocess.check_output([str(adb), 'devices'], text=True, env=env):
            raise RuntimeError('Selected emulator serial already exists')
        for port in (5580, 5581):
            with socket.socket() as probe:
                probe.bind(('127.0.0.1', port))
        created = subprocess.run([avdmanager, 'create', 'avd', '--name', avd_name,
            '--path', str(avd_root / 'runtime.avd'), '--package',
            f'system-images;android-{args.api};google_apis;{abi}', '--device', 'pixel_2'],
            input='no\n', capture_output=True, text=True, env=env, timeout=60)
        (out / 'create-avd.log').write_text(created.stdout + created.stderr)
        created.check_returncode()
        with (out / 'emulator.log').open('w') as log:
            process = subprocess.Popen([str(emulator), '-avd', avd_name, '-port', '5580',
                '-no-window', '-no-audio', '-no-snapshot', '-no-boot-anim', '-gpu',
                'swiftshader_indirect', '-accel', 'on', '-memory', '3072', '-cores', '2',
                '-camera-back', 'none', '-camera-front', 'none'], env=env, stdout=log, stderr=log)
            deadline = time.monotonic() + 420
            while time.monotonic() < deadline:
                if process.poll() is not None:
                    raise RuntimeError('Owned emulator exited before boot')
                if device('shell', 'getprop', 'sys.boot_completed', timeout=10).stdout.strip() == '1':
                    break
                time.sleep(2)
            else:
                raise RuntimeError('Emulator boot deadline exceeded')
            actual_api = device('shell', 'getprop', 'ro.build.version.sdk').stdout.strip()
            actual_abi = device('shell', 'getprop', 'ro.product.cpu.abi').stdout.strip()
            if actual_api != str(args.api) or actual_abi != abi:
                raise RuntimeError('Emulator API/ABI does not match the requested matrix entry')
            if avd_name not in device('emu', 'avd', 'name').stdout:
                raise RuntimeError('Refusing installation into an unowned emulator')
            result.update(actual_api=actual_api, actual_abi=actual_abi)
            for phase in ('before', 'after'):
                for path, final_bytes in originals.items():
                    data = subprocess.check_output(['git', 'show', BASELINE + ':' + path], cwd=ROOT) if phase == 'before' else final_bytes
                    (ROOT / path).write_bytes(data)
                if hashes([TEST])[TEST] != source_hashes[TEST]:
                    raise RuntimeError('Regression source changed between phases')
                proof = dict(runtime_mode='pinned baseline overlay' if phase == 'before' else 'reviewed HEAD',
                             source_sha256=hashes(RUNTIME + [TEST]))
                with (out / (phase + '-build.log')).open('w') as log:
                    subprocess.run(['./gradlew', ':android-app:assembleDebug', ':android-app:assembleDebugAndroidTest',
                        '--console=plain', '--max-workers=2', '--no-parallel'], cwd=ROOT,
                        env=env, stdout=log, stderr=log, check=True, timeout=720)
                proof['apk_sha256'] = hashes(APKS)
                for path in APKS:
                    device('install', '-r', '-t', str(ROOT / path), timeout=120).check_returncode()
                device('shell', 'pm', 'clear', 'com.group33.medicationreminder').check_returncode()
                classes = ','.join(SUITE + '#' + name for name in TARGETS) if phase == 'before' else SUITE
                run = device('shell', 'am', 'instrument', '-w', '-r', '-e', 'class', classes, RUNNER, timeout=600)
                output = run.stdout + run.stderr
                (out / (phase + '-instrumentation.log')).write_text(output)
                completed = completions(output)
                proof.update(adb_exit=run.returncode, completed=completed)
                expected_names = set(TARGETS) if phase == 'before' else set(names)
                failed = {x['test'] for x in completed if x['code'] != 0}
                expected_failures = set(TARGETS[1:]) | ({TARGETS[0]} if args.api == 27 else set())
                unique = len(completed) == len(expected_names) and {x['test'] for x in completed} == expected_names
                if phase == 'before':
                    messages = {x['test']: x.get('failure', '') for x in completed}
                    font_failure = messages.get(TARGETS[2], '')
                    locale_failure = messages.get(TARGETS[1], '')
                    causes_match = ('Calendar control must use the component font scale' in font_failure
                        and 'expected:<2.0> but was:<1.0>' in font_failure
                        and 'End date: ' in locale_failure and '结束日期：' in locale_failure)
                    if args.api == 27:
                        causes_match = causes_match and re.search(r'expected:<([1-9][0-9]*)> but was:<0>', messages.get(TARGETS[0], '')) is not None
                    proof['expected_assertion_causes'] = causes_match
                    proof['passed'] = unique and failed == expected_failures and causes_match and all(x['code'] in (0, -2) for x in completed) and 'FAILURES!!!' in output
                else:
                    proof['passed'] = unique and not failed and re.search(r'^OK \(18 tests\)$', output, re.M) is not None and 'FAILURES!!!' not in output
                proof['passed'] = proof['passed'] and run.returncode == 0
                if phase == 'after' and proof['passed']:
                    captures = {}
                    for language in ('en', 'zh'):
                        name = f'history-platform-{language}-2x.png'
                        image = subprocess.run([str(adb), '-s', serial, 'exec-out', 'run-as',
                            'com.group33.medicationreminder', 'cat', 'cache/' + name],
                            capture_output=True, env=env, timeout=30)
                        image.check_returncode()
                        if not image.stdout.startswith(b'\x89PNG\r\n\x1a\n'):
                            raise RuntimeError('Expected a real emulator PNG capture')
                        (out / name).write_bytes(image.stdout)
                        captures[name] = hashlib.sha256(image.stdout).hexdigest()
                    proof['capture_sha256'] = captures
                result[phase] = proof
                (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
                if not proof['passed']:
                    raise RuntimeError(f'{phase} regression results differ from the required evidence')
            result['passed'] = True
    finally:
        for path, data in originals.items():
            (ROOT / path).write_bytes(data)
        result['reviewed_source_restored'] = hashes(RUNTIME + [TEST]) == source_hashes
        if process is not None and process.poll() is None:
            try:
                if avd_name in device('emu', 'avd', 'name', timeout=10).stdout:
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
        result['passed'] = result['passed'] and result['reviewed_source_restored'] and result['emulator_stopped'] and result['private_avd_removed']
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
        print(json.dumps(result), flush=True)
    if not result['passed']:
        raise RuntimeError('History platform acceptance failed')


if __name__ == '__main__':
    main()
