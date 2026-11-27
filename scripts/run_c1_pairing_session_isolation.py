"""C1 local HTTP session-generation isolation; install only the isolated c1dev112 packages."""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import xml.etree.ElementTree as ET

APP = 'com.group33.medicationreminder.c1dev112'
NAMESPACE = 'com.group33.medicationreminder'
RUNNER = APP + '.test/androidx.test.runner.AndroidJUnitRunner'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--aapt', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    out = Path(args.output).resolve()
    assert not out.exists(), 'Preserve earlier attempts'
    out.mkdir(parents=True)
    repo = Path(__file__).resolve().parents[1]

    def adb(*values):
        result = subprocess.run([args.adb, '-s', args.serial, *values], capture_output=True, timeout=240)
        assert result.returncode == 0, 'ADB command failed: ' + str(values[:2])
        return result.stdout

    def instrument(name, parameters):
        values = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', NAMESPACE + '.integration.' + name]
        for key, value in parameters.items():
            values += ['-e', key, value]
        return adb(*values, RUNNER).decode('utf-8', errors='replace')

    metadata = {'head': subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip(),
                'api': adb('shell', 'getprop', 'ro.build.version.sdk').decode().strip(), 'results': []}
    assert int(metadata['api']) >= 23
    source = repo / 'android-app/src/androidTest/kotlin/com/group33/medicationreminder/integration/PairingSessionIsolationOnlineTest.kt'
    metadata['test_source_sha256'] = hashlib.sha256(source.read_bytes()).hexdigest()
    for relative, expected in [('debug/android-app-debug.apk', APP),
                               ('androidTest/debug/android-app-debug-androidTest.apk', APP + '.test')]:
        apk = repo / 'android-app/build/outputs/apk' / relative
        badging = subprocess.check_output([args.aapt, 'dump', 'badging', str(apk)], encoding='utf-8')
        assert re.search(r"^package: name='([^']+)'", badging).group(1) == expected
        metadata[apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
        assert b'Success' in adb('install', '-r', str(apk))
    mapping = adb('reverse', '--list').decode().splitlines()
    previous = next((line.split()[2] for line in mapping if len(line.split()) == 3 and line.split()[1] == 'tcp:18082'), None)
    try:
        adb('reverse', 'tcp:18082', 'tcp:18082')
        for language, tag in [('ENGLISH', 'en'), ('SIMPLIFIED_CHINESE', 'zh-CN')]:
            adb('shell', 'am', 'force-stop', APP)
            setup = instrument('ChainOnlineFlowTest#configureLanguage', {'chainLanguage': tag})
            (out / (language + '-setup.log')).write_text(setup, encoding='utf-8')
            assert 'OK (1 test)' in setup and 'FAILURES' not in setup
            if int(metadata['api']) >= 33:
                assert tag in adb('shell', 'cmd', 'locale', 'get-app-locales', APP).decode()
            else:
                preferences = ET.fromstring(adb('exec-out', 'run-as', APP, 'cat', 'shared_prefs/language_settings.xml'))
                assert preferences.find("string[@name='choice']").text == language
            result = instrument('PairingSessionIsolationOnlineTest', {'pairingSessionIsolation': 'true'})
            (out / (language + '.log')).write_text(result, encoding='utf-8')
            passed = 'OK (7 tests)' in result and 'FAILURES' not in result
            metadata['results'].append({'language': language, 'passed': passed, 'test_methods': 7})
            (out / 'result.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
            print(language, 'PASS' if passed else 'FAIL', flush=True)
            assert passed, 'Inspect the retained real-service test failure'
    finally:
        if previous:
            adb('reverse', 'tcp:18082', previous)
        else:
            adb('reverse', '--remove', 'tcp:18082')
    print('Fourteen bilingual session/error methods passed; original ADB mapping restored', flush=True)


if __name__ == '__main__':
    main()
