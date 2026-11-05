#!/usr/bin/env python3
"""Run the opt-in real local HTTP flow on two fresh emulator installations.

Bridge values stay in memory and app-private no_backup files; never print them.
Start the documented local Server first. Physical devices are deliberately refused.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]
PKG = 'com.group33.medicationreminder'
TEST = PKG + '.test/androidx.test.runner.AndroidJUnitRunner'
PHASES = [('elder-login', 'elder'), ('caregiver-request', 'caregiver'), ('elder-accept', 'elder'),
          ('elder-photo', 'elder'), ('caregiver-plan-ui', 'caregiver'), ('caregiver-restart', 'caregiver'),
          ('elder-sees-plan', 'elder'), ('caregiver-browser', 'caregiver'), ('caregiver-navigation', 'caregiver'), ('caregiver-lost-reply', 'caregiver'),
          ('caregiver-recover', 'caregiver'), ('caregiver-stale-401', 'caregiver'),
          ('caregiver-read-failure', 'caregiver'),
          ('elder-revoke', 'elder'), ('caregiver-denied', 'caregiver')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--elder', default='emulator-5560')
    parser.add_argument('--caregiver', default='emulator-5562')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--locale', help='Persist the shared language preference, e.g. zh-CN or en-US')
    parser.add_argument('--reset-test-apps', action='store_true', help='Clear only these two fictional emulator app installations')
    args = parser.parse_args()
    if not all(x.startswith('emulator-') for x in [args.elder, args.caregiver]) or args.elder == args.caregiver:
        parser.error('Use two distinct emulator installations; this harness does not wipe/run physical devices')
    adb = str(Path(os.environ.get('ANDROID_HOME', str(Path.home() / 'Android/sdk'))) / 'platform-tools/adb')
    args.output.mkdir(parents=True, exist_ok=True)

    def run(serial, *command, data=None):
        p = subprocess.run([adb, '-s', serial, *command], input=data, capture_output=True, timeout=120)
        if p.returncode:
            raise RuntimeError('Emulator command failed (no private bridge values printed)')
        return p.stdout

    devices = {'elder': args.elder, 'caregiver': args.caregiver}
    for serial in devices.values():
        run(serial, 'install', '-r', str(ROOT / 'android-app/build/outputs/apk/debug/android-app-debug.apk'))
        run(serial, 'install', '-r', str(ROOT / 'android-app/build/outputs/apk/androidTest/debug/android-app-debug-androidTest.apk'))
        if args.reset_test_apps:
            run(serial, 'shell', 'pm', 'clear', PKG)
        if args.locale:
            setup = run(serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                        PKG + '.integration.ChainOnlineFlowTest#configureLanguage', '-e', 'chainLanguage', args.locale, TEST)
            if b'OK (1 test)' not in setup: raise RuntimeError('Shared language preference setup failed')
            # Prove the Application restores its saved choice without a live Activity delegate.
            sdk = int(run(serial, 'shell', 'getprop', 'ro.build.version.sdk').strip())
            if sdk >= 33:
                run(serial, 'shell', 'cmd', 'locale', 'set-app-locales', PKG)
            run(serial, 'shell', 'am', 'force-stop', PKG)
        run(serial, 'shell', 'am', 'start', '-n', PKG + '/.SharedLoginActivity')

    bridge = None
    results = []
    for phase, role in PHASES:
        serial = devices[role]
        if phase in ['caregiver-restart', 'elder-sees-plan', 'caregiver-recover']:
            run(serial, 'shell', 'am', 'force-stop', PKG)
        if bridge is not None:
            run(serial, 'shell', f"run-as {PKG} sh -c 'cat > no_backup/chain-bridge.json'", data=bridge)
        output = run(serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                     PKG + '.integration.ChainOnlineFlowTest#realOnlinePhase', '-e', 'chainPhase', phase, TEST)
        (args.output / (phase + '.log')).write_bytes(output)
        passed = b'OK (1 test)' in output and b'FAILURES' not in output
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
        if phase in ['elder-login', 'caregiver-request', 'elder-accept', 'elder-photo', 'caregiver-plan-ui', 'caregiver-lost-reply']:
            bridge = run(serial, 'exec-out', 'run-as', PKG, 'cat', 'no_backup/chain-bridge.json')
            json.loads(bridge)
    print('All two-emulator local HTTP phases passed. Physical-device and production-login acceptance remain separate.')


if __name__ == '__main__':
    main()
