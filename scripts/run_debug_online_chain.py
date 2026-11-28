#!/usr/bin/env python3
"""Run the opt-in real local HTTP flow on two fresh emulator installations.

Bridge values stay in memory and app-private no_backup files; never print them.
Start the documented local Server first. Physical devices are deliberately refused.
"""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import time

ROOT = Path(__file__).resolve().parents[1]
PKG = 'com.group33.medicationreminder'
TEST = PKG + '.test/androidx.test.runner.AndroidJUnitRunner'
PHASES = [('elder-login', 'elder'), ('elder-recover-code', 'elder'), ('caregiver-request', 'caregiver'), ('elder-accept', 'elder'),
          ('elder-photo', 'elder'), ('caregiver-plan-ui', 'caregiver'), ('caregiver-restart', 'caregiver'),
          ('elder-sees-plan', 'elder'), ('caregiver-browser', 'caregiver'), ('caregiver-navigation', 'caregiver'), ('caregiver-lost-reply', 'caregiver'),
          ('caregiver-recover', 'caregiver'), ('caregiver-stale-401', 'caregiver'),
          ('caregiver-read-failure', 'caregiver'),
          ('elder-revoke', 'elder'), ('caregiver-denied', 'caregiver')]


def phases_for(*, live_dose, dose_upload):
    if not live_dose:
        return PHASES
    return PHASES[:-2] + [
        ('caregiver-live-dose-plan', 'caregiver'), ('elder-live-dose', 'elder'),
        ('elder-live-dose-restart', 'elder'),
    ] + ([('caregiver-sees-taken', 'caregiver')] if dose_upload else []) + PHASES[-2:]


def inspect_instrumentation(output):
    text = output.decode(errors='replace') if isinstance(output, bytes) else output
    codes = [int(value) for value in re.findall(r'^INSTRUMENTATION_STATUS_CODE: (-?\d+)$', text, re.M)]
    completed = [code for code in codes if code != 1]
    passed = (
        completed == [0]
        and len(re.findall(r'^OK \(1 test\)$', text, re.M)) == 1
        and 'FAILURES!!!' not in text
        and 'INSTRUMENTATION_FAILED' not in text
    )
    return {'passed': passed, 'status_codes': codes}


def network_toggle_commands(sdk, enabled):
    """Return shell commands that deterministically isolate/restore an emulator.

    API 28 and older reject the protected AIRPLANE_MODE broadcast when it is
    sent by the shell identity.  Disabling both Wi-Fi and mobile data gives the
    acceptance test the same offline boundary without relying on that broadcast.
    """
    if sdk >= 29:
        return [('shell', 'cmd', 'connectivity', 'airplane-mode',
                 'enable' if enabled else 'disable')]
    state = 'disable' if enabled else 'enable'
    return [
        ('shell', 'svc', 'wifi', state),
        ('shell', 'svc', 'data', state),
    ]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--elder', default='emulator-5560')
    parser.add_argument('--caregiver', default='emulator-5562')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--locale', help='Persist the shared language preference, e.g. zh-CN or en-US')
    parser.add_argument('--dose-upload', action='store_true', help='Also verify server-accepted dose records on the caregiver pages')
    parser.add_argument('--live-dose', action='store_true', help='Also exercise a real local reminder and durable Elder actions')
    parser.add_argument('--offline-action-recovery', action='store_true',
                        help='Record Taken with Elder connectivity disabled, then prove process/network recovery uploads the same action')
    parser.add_argument('--reset-test-apps', action='store_true', help='Clear only these two fictional emulator app installations')
    args = parser.parse_args()
    args.dose_upload = args.dose_upload or args.offline_action_recovery
    args.live_dose = args.live_dose or args.dose_upload
    if not all(x.startswith('emulator-') for x in [args.elder, args.caregiver]) or args.elder == args.caregiver:
        parser.error('Use two distinct emulator installations; this harness does not wipe/run physical devices')
    adb = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/sdk'))) / 'platform-tools/adb')
    args.output.mkdir(parents=True, exist_ok=True)
    acceptance_clock = os.environ.get('P0_ACCEPTANCE_CLOCK_FILE')
    acceptance_clock = Path(acceptance_clock).resolve() if acceptance_clock else None

    def run(serial, *command, data=None, required=True):
        p = subprocess.run([adb, '-s', serial, *command], input=data, capture_output=True, timeout=220 if args.live_dose else 120)
        if p.returncode and required:
            raise RuntimeError('Emulator command failed (no private bridge values printed)')
        return p.stdout

    def wait_for_network(serial):
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            probe = subprocess.run(
                [adb, '-s', serial, 'shell', 'ping', '-c', '1', '-W', '1', '10.0.2.2'],
                capture_output=True, timeout=5,
            )
            if probe.returncode == 0:
                return
            time.sleep(0.5)
        raise RuntimeError('Emulator network did not recover before cold-process verification')

    def set_network_offline(serial, enabled):
        sdk = int(run(serial, 'shell', 'getprop', 'ro.build.version.sdk').strip())
        for command in network_toggle_commands(sdk, enabled):
            # AVDs can expose no cellular service; Wi-Fi is the required path.
            run(serial, *command, required=command[2:4] != ('svc', 'data'))

    devices = {'elder': args.elder, 'caregiver': args.caregiver}
    for serial in devices.values():
        if args.offline_action_recovery:
            set_network_offline(serial, False)
        run(serial, 'install', '-r', str(ROOT / 'android-app/build/outputs/apk/debug/android-app-debug.apk'))
        run(serial, 'install', '-r', str(ROOT / 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'))
        if args.reset_test_apps:
            run(serial, 'shell', 'pm', 'clear', PKG)
        if args.live_dose and int(run(serial, 'shell', 'getprop', 'ro.build.version.sdk').strip()) >= 33:
            run(serial, 'shell', 'pm', 'grant', PKG, 'android.permission.POST_NOTIFICATIONS')
        if args.locale:
            setup = run(serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                        PKG + '.integration.ChainOnlineFlowTest#configureLanguage', '-e', 'chainLanguage', args.locale, TEST)
            if not inspect_instrumentation(setup)['passed']:
                raise RuntimeError('Shared language preference setup failed')
            # Prove the Application restores its saved choice without a live Activity delegate.
            sdk = int(run(serial, 'shell', 'getprop', 'ro.build.version.sdk').strip())
            if sdk >= 33:
                run(serial, 'shell', 'cmd', 'locale', 'set-app-locales', PKG)
            run(serial, 'shell', 'am', 'force-stop', PKG)
        run(serial, 'shell', 'am', 'start', '-n', PKG + '/.SharedLoginActivity')

    bridge = None
    results = []
    phases = phases_for(live_dose=args.live_dose, dose_upload=args.dose_upload)
    for phase, role in phases:
        serial = devices[role]
        cold_phases = ['elder-live-dose-restart', 'elder-recover-code', 'caregiver-restart',
                       'elder-sees-plan', 'caregiver-recover']
        already_stopped_offline_elder = phase == 'elder-live-dose-restart' and args.offline_action_recovery
        if phase in cold_phases and not already_stopped_offline_elder:
            run(serial, 'shell', 'am', 'force-stop', PKG)
        if bridge is not None:
            run(serial, 'shell', f"run-as {PKG} sh -c 'cat > no_backup/chain-bridge.json'", data=bridge)
        output = run(serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                     PKG + '.integration.ChainOnlineFlowTest#realOnlinePhase', '-e', 'chainPhase', phase,
                     '-e', 'doseUpload', str(args.dose_upload).lower(), '-e', 'offlineActionRecovery',
                     str(args.offline_action_recovery).lower(), TEST)
        if phase == 'elder-live-dose' and args.offline_action_recovery:
            # Instrumentation remains reachable over adb while Android networking is disabled.
            # Kill the old process before restoring connectivity so only cold-start recovery can sync.
            run(serial, 'shell', 'am', 'force-stop', PKG)
            set_network_offline(serial, False)
            wait_for_network(serial)
        (args.output / (phase + '.log')).write_bytes(output)
        passed = inspect_instrumentation(output)['passed']
        results.append({'phase': phase, 'role': role, 'passed': passed})
        (args.output / 'results.json').write_text(json.dumps(results, indent=2))
        print(phase, 'PASS' if passed else 'FAIL', flush=True)
        if not passed:
            raise SystemExit('Stop at first failed phase; see the phase log')
        if phase in ['caregiver-plan-ui', 'elder-sees-plan']:
            image = 'caregiver-plan' if role == 'caregiver' else 'elder-plan'
            (args.output / (image + '.png')).write_bytes(run(serial, 'exec-out', 'run-as', PKG,
                'cat', f'no_backup/chain-{image}.png'))
        if phase == 'caregiver-browser':
            for image in ['caregiver-browser', 'caregiver-photo']:
                (args.output / (image + '.png')).write_bytes(run(serial, 'exec-out', 'run-as', PKG,
                    'cat', f'no_backup/chain-{image}.png'))
        if phase in ['caregiver-live-dose-plan', 'elder-live-dose', 'elder-login', 'elder-recover-code', 'caregiver-request', 'elder-accept', 'elder-photo', 'caregiver-plan-ui', 'caregiver-lost-reply']:
            bridge = run(serial, 'exec-out', 'run-as', PKG, 'cat', 'no_backup/chain-bridge.json')
            bridge_values = json.loads(bridge)
            if phase == 'caregiver-live-dose-plan' and acceptance_clock is not None:
                temporary = acceptance_clock.with_suffix('.next')
                temporary.write_text(str(bridge_values['liveDoseAt'] + 1) + '\n')
                temporary.chmod(0o600)
                os.replace(temporary, acceptance_clock)
    print('All two-emulator local HTTP phases passed. Physical-device and production-login acceptance remain separate.')


if __name__ == '__main__':
    main()
