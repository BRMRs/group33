"""Local-only C1 response-loss/process-restart instrumentation, no app-data clearing.

Build the isolated c1dev112 APKs against http://127.0.0.1:18082 first.
Requires a dedicated fictional-data Server already reachable on host loopback.
"""
import argparse
import hashlib
import http.client
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import re
import subprocess
import threading
import time
from urllib.parse import urlsplit

APP = 'com.group33.medicationreminder.c1dev112'
NAMESPACE = 'com.group33.medicationreminder'
RUNNER = APP + '.test/androidx.test.runner.AndroidJUnitRunner'
PHASES = [('prepareUnknown', 'drop_pairing', 'unknown'),
          ('verifyOfflineAfterRestart', 'offline', 'offline'),
          ('recoverOriginalAfterReconnect', 'online', 'recovered')]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--aapt', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--server-url', default='http://127.0.0.1:18082')
    parser.add_argument('--listen-port', type=int, default=18083)
    parser.add_argument('--output', required=True)
    parser.add_argument('--recover-existing', action='store_true', help='Only reconcile a fixture retained by a failed run')
    parser.add_argument('--inflight-reboot', action='store_true', help='Kill in-flight work, check live claim, then reboot the dedicated emulator')
    args = parser.parse_args()
    backend = urlsplit(args.server_url)
    assert backend.scheme == 'http' and backend.hostname == '127.0.0.1'
    assert backend.path in ('', '/') and not backend.query and not backend.fragment and not backend.username
    backend_port = backend.port or 80
    assert 1024 <= args.listen_port <= 65535 and args.listen_port != backend_port
    out = Path(args.output).resolve()
    assert not out.exists(), 'Use a new output directory; retain failed attempts'
    out.mkdir(parents=True)
    repo = Path(__file__).resolve().parents[1]

    def adb(*values, timeout=240):
        result = subprocess.run([args.adb, '-s', args.serial, *values], capture_output=True, timeout=timeout)
        assert result.returncode == 0, 'ADB command failed: ' + str(values[:2])
        return result.stdout

    metadata = {'head': subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD'], text=True).strip(),
                'api': adb('shell', 'getprop', 'ro.build.version.sdk').decode().strip(), 'results': []}
    assert not (args.recover_existing and args.inflight_reboot)
    if args.inflight_reboot:
        assert args.serial == 'emulator-5562', 'Only the dedicated test emulator may reboot'
        assert 'Group33_PR110_C1' in adb('emu', 'avd', 'name').decode()
        metadata['scenario'] = 'inflight-kill-live-claim-actual-emulator-reboot'
    assert int(metadata['api']) >= 33, 'This runner verifies actual per-app locales on API33+'
    for name in ['PairingFormFailureOnlineTest.kt', 'PairingResponseLossOnlineTest.kt']:
        source = repo / 'android-app/src/androidTest/kotlin/com/group33/medicationreminder/integration' / name
        metadata[name] = hashlib.sha256(source.read_bytes()).hexdigest()
    for relative, expected in [('debug/android-app-debug.apk', APP),
                               ('androidTest/debug/android-app-debug-androidTest.apk', APP + '.test')]:
        apk = repo / 'android-app/build/outputs/apk' / relative
        badging = subprocess.check_output([args.aapt, 'dump', 'badging', str(apk)], encoding='utf-8')
        assert re.search(r"^package: name='([^']+)'", badging).group(1) == expected, 'Only isolated test packages may be installed'
        metadata[apk.name] = hashlib.sha256(apk.read_bytes()).hexdigest()
        assert b'Success' in adb('install', '-r', str(apk))
    mapping = adb('reverse', '--list').decode().splitlines()
    previous = next((line.split()[2] for line in mapping if len(line.split()) == 3 and line.split()[1] == 'tcp:18082'), None)
    mode = {'value': 'online'}
    audit = []
    audit_lock = threading.Lock()
    release = threading.Event()
    held_at = {'value': None}

    class Proxy(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass

        def forward(self):
            selected = mode['value']
            if selected == 'offline':
                self.close_connection = True
                return
            body = self.rfile.read(int(self.headers.get('Content-Length', '0')))
            headers = {key: value for key, value in self.headers.items() if key.lower() not in
                       ('host', 'connection', 'transfer-encoding', 'content-length')}
            headers['Content-Length'] = str(len(body))
            upstream = http.client.HTTPConnection('127.0.0.1', backend_port, timeout=20)
            try:
                upstream.request(self.command, self.path, body=body, headers=headers)
                response = upstream.getresponse()
                payload = response.read()
                create = self.command == 'POST' and self.path == '/api/v1/pairing-requests'
                hold = create and selected == 'hold_pairing'
                drop = create and selected in ('drop_pairing', 'hold_pairing')
                if create:
                    # Never write credentials, original keys or pairing codes to diagnostics.
                    row = {'key_sha256': hashlib.sha256(self.headers.get('Idempotency-Key', '').encode()).hexdigest(),
                           'body_sha256': hashlib.sha256(body).hexdigest(), 'status': response.status, 'dropped': drop, 'held': hold}
                    with audit_lock:
                        audit.append(row)
                        with (out / 'fault-audit.jsonl').open('a', encoding='utf-8') as stream:
                            stream.write(json.dumps(row) + '\n')
                if hold:
                    held_at['value'] = time.monotonic()
                    release.wait(timeout=20)
                if drop:
                    self.close_connection = True
                    return
                self.send_response(response.status)
                for key, value in response.getheaders():
                    if key.lower() not in ('content-length', 'transfer-encoding', 'connection'):
                        self.send_header(key, value)
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)
            finally:
                upstream.close()

        do_GET = do_POST = do_PUT = do_DELETE = forward

    proxy = ThreadingHTTPServer(('127.0.0.1', args.listen_port), Proxy)
    proxy.daemon_threads = True
    thread = threading.Thread(target=proxy.serve_forever, daemon=True)
    thread.start()

    def instrument(name, parameters):
        values = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', NAMESPACE + '.integration.' + name]
        for key, value in parameters.items():
            values += ['-e', key, value]
        return adb(*values, RUNNER).decode('utf-8', errors='replace')

    def complete_phase(language, phase, stage):
        result = instrument('PairingResponseLossOnlineTest#' + phase, {'pairingResponseLoss': 'true'})
        (out / (language + '-' + phase + '.log')).write_text(result, encoding='utf-8')
        passed = 'OK (1 test)' in result and 'FAILURES' not in result
        metadata['results'].append({'language': language, 'phase': phase, 'passed': passed})
        (out / 'result.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
        print(language, phase, 'PASS' if passed else 'FAIL', flush=True)
        assert passed, 'Retain the original journal on failure'
        (out / (language + '-' + stage + '.json')).write_bytes(
            adb('exec-out', 'run-as', APP, 'cat', 'no_backup/pairing-loss-' + stage + '.json'))

    def inflight_reboot(language, tag):
        release.clear()
        held_at['value'] = None
        mode['value'] = 'hold_pairing'
        log_path = out / (language + '-prepareInFlight.log')
        values = [args.adb, '-s', args.serial, 'shell', 'am', 'instrument', '-w', '-r', '-e', 'class',
                  NAMESPACE + '.integration.PairingResponseLossOnlineTest#prepareInFlight',
                  '-e', 'pairingResponseLoss', 'true', RUNNER]
        with log_path.open('wb') as log:
            process = subprocess.Popen(values, stdout=log, stderr=subprocess.STDOUT)
            try:
                deadline = time.monotonic() + 40
                while True:
                    assert process.poll() is None, 'In-flight preparation exited before interruption'
                    marker = subprocess.run([args.adb, '-s', args.serial, 'exec-out', 'run-as', APP,
                        'cat', 'no_backup/pairing-loss-inflight-ready.json'], capture_output=True, timeout=5)
                    running = subprocess.run([args.adb, '-s', args.serial, 'shell', 'pidof', APP], capture_output=True, timeout=5)
                    pid = running.stdout.decode().strip()
                    try:
                        value = json.loads(marker.stdout) if marker.returncode == 0 else None
                    except json.JSONDecodeError:
                        value = None  # Marker may not exist or may still be being written.
                    if value is not None:
                        if str(value['pid']) == pid and value['busy'] and held_at['value'] is not None:
                            assert time.monotonic() - held_at['value'] < 8, 'Kill before the real HTTP read timeout'
                            break
                    assert time.monotonic() < deadline, 'No durable in-flight marker'
                    time.sleep(0.1)
                adb('shell', 'am', 'force-stop', APP)
                stopped = subprocess.run([args.adb, '-s', args.serial, 'shell', 'pidof', APP], capture_output=True, timeout=5)
                assert not stopped.stdout.strip()
                process.wait(timeout=20)
            finally:
                release.set()
                if process.poll() is None:
                    process.terminate()
                    process.wait(timeout=10)
        assert 'OK (1 test)' not in log_path.read_text(encoding='utf-8', errors='replace'), 'Preparation must be interrupted, not finish normally'
        metadata['results'].append({'language': language, 'phase': 'prepareInFlight', 'interruption_verified': True})
        mode['value'] = 'online'
        count = len(audit)
        complete_phase(language, 'verifyLiveClaimAfterKill', 'liveclaim')
        assert len(audit) == count, 'Live same-boot claim must block HTTP replay'
        before = adb('shell', 'settings', 'get', 'global', 'boot_count').decode().strip()
        adb('reboot')
        print(language, 'waiting for dedicated emulator reboot', flush=True)
        deadline = time.monotonic() + 120
        while True:
            state = subprocess.run([args.adb, '-s', args.serial, 'shell', 'getprop', 'sys.boot_completed'], capture_output=True, timeout=5)
            if state.returncode == 0 and state.stdout.strip() == b'1':
                boot = subprocess.run([args.adb, '-s', args.serial, 'shell', 'settings', 'get', 'global', 'boot_count'], capture_output=True, timeout=5)
                if boot.returncode == 0 and boot.stdout.strip().isdigit() and int(boot.stdout.strip()) > int(before):
                    break
            assert time.monotonic() < deadline, 'Emulator boot timeout'
            time.sleep(1)
        after = adb('shell', 'settings', 'get', 'global', 'boot_count').decode().strip()
        assert before.isdigit() and after.isdigit() and int(after) > int(before), 'Actual boot identity must advance'
        metadata.setdefault('boots', []).append({'language': language, 'before': int(before), 'after': int(after)})
        (out / 'result.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
        adb('reverse', 'tcp:18082', 'tcp:' + str(args.listen_port))
        adb('shell', 'input', 'keyevent', 'KEYCODE_WAKEUP')
        adb('shell', 'wm', 'dismiss-keyguard')
        deadline = time.monotonic() + 30
        while tag not in adb('shell', 'cmd', 'locale', 'get-app-locales', APP).decode():
            assert time.monotonic() < deadline, 'Persisted app locale must restore after boot'
            time.sleep(0.5)
        complete_phase(language, 'recoverOriginalAfterReconnect', 'recovered')

    try:
        adb('reverse', 'tcp:18082', 'tcp:' + str(args.listen_port))
        languages = [('EXISTING', None)] if args.recover_existing else [('ENGLISH', 'en'), ('SIMPLIFIED_CHINESE', 'zh-CN')]
        for language, tag in languages:
            mode['value'] = 'online'
            if tag:
                setup = instrument('ChainOnlineFlowTest#configureLanguage', {'chainLanguage': tag})
                (out / (language + '-setup.log')).write_text(setup, encoding='utf-8')
                assert 'OK (1 test)' in setup and 'FAILURES' not in setup
                assert tag in adb('shell', 'cmd', 'locale', 'get-app-locales', APP).decode()
            if args.inflight_reboot:
                inflight_reboot(language, tag)
                continue
            for phase, selected, stage in PHASES[-1:] if args.recover_existing else PHASES:
                adb('shell', 'am', 'force-stop', APP)
                stopped = subprocess.run([args.adb, '-s', args.serial, 'shell', 'pidof', APP], capture_output=True, timeout=15)
                assert not stopped.stdout.strip(), 'Target process must stop before the next phase'
                mode['value'] = selected
                result = instrument('PairingResponseLossOnlineTest#' + phase, {'pairingResponseLoss': 'true'})
                (out / (language + '-' + phase + '.log')).write_text(result, encoding='utf-8')
                passed = 'OK (1 test)' in result and 'FAILURES' not in result
                metadata['results'].append({'language': language, 'phase': phase, 'passed': passed})
                (out / 'result.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
                print(language, phase, 'PASS' if passed else 'FAIL', flush=True)
                assert passed, 'Preserve journal; use --recover-existing with the same service if needed'
                state = adb('exec-out', 'run-as', APP, 'cat', 'no_backup/pairing-loss-' + stage + '.json')
                (out / (language + '-' + stage + '.json')).write_bytes(state)
        if not args.recover_existing:
            groups = {}
            for row in audit:
                groups.setdefault(row['key_sha256'], []).append(row)
            assert len(groups) == 2
            for rows in groups.values():
                assert any(row['dropped'] for row in rows) and any(not row['dropped'] for row in rows)
                assert all(row['status'] == 201 for row in rows)
                assert len({row['body_sha256'] for row in rows}) == 1
            metadata['original_key_and_payload_verified'] = True
        (out / 'result.json').write_text(json.dumps(metadata, indent=2), encoding='utf-8')
        print('Verification complete; original ADB mapping restored on exit', flush=True)
    finally:
        release.set()
        if previous:
            adb('reverse', 'tcp:18082', previous)
        else:
            adb('reverse', '--remove', 'tcp:18082')
        proxy.shutdown()
        proxy.server_close()
        thread.join(timeout=5)


if __name__ == '__main__':
    main()
