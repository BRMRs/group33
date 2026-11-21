#!/usr/bin/env python3
"""Room/worker recovery checks on an explicitly supplied disposable emulator.

Uses an injected loopback HTTP receiver with a durable test ledger, not the production Server.
The first receiver commits before the host kills the uploading app process. The original
probe injects lease expiry; optional automatic mode uses production bootstrap, WorkManager
and the real clock, waiting for the original claim to expire without a manual recovery call.
Automatic mode resets the explicitly disposable app before preparation; never use personal data.
"""
import argparse
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import re
import subprocess
import threading
import time

PACKAGE = 'com.group33.medicationreminder'
RUNNER = PACKAGE + '.test/androidx.test.runner.AndroidJUnitRunner'
ROOM = PACKAGE + '.core.reminder.ReminderDispatchRecoveryTest'
PROBE = PACKAGE + '.core.reminder.ReminderDispatchProcessProbeTest'


def run_checks(adb, serial, output, env=None, skip_room=False, automatic=False):
    if not serial.startswith('emulator-'):
        raise RuntimeError('An explicit disposable emulator is required')
    out = Path(output); out.mkdir(parents=True, exist_ok=True)
    result = dict(passed=False, serial=serial, physical_device=False, production_server=False,
                  automatic_startup_end_to_end=automatic,
                  lease_clock='real clock' if automatic else 'injected after real process restart')
    runner = (PACKAGE + '.test/' + PACKAGE + '.core.reminder.AutomaticRecoveryRunner') if automatic else RUNNER
    probe = PACKAGE + '.core.reminder.AutomaticRecoveryProbeTest' if automatic else PROBE
    prefix = 'automatic-recovery' if automatic else 'dispatch-recovery-probe'
    def device(*args, timeout=90):
        return subprocess.run([str(adb), '-s', serial, *args], capture_output=True, text=True,
                              timeout=timeout, env=env)
    def instrument(suite, *extras, timeout=90):
        args = ['shell', 'am', 'instrument', '-w', '-r', '-e', 'class', suite]
        for key, value in extras: args += ['-e', key, str(value)]
        return device(*args, runner, timeout=timeout)
    def check_run(run, count, file):
        text = run.stdout + run.stderr; (out / file).write_text(text)
        passed = (run.returncode == 0 and re.search(r'^OK \(' + str(count) + r' tests?\)$', text, re.M)
                  and 'FAILURES!!!' not in text)
        if not passed: raise RuntimeError('Instrumentation failed: ' + file)
    if automatic:
        # Prior suites intentionally leave unrelated pending fixtures. Clear only this explicitly
        # disposable test installation before preparation, never between prepare and restart.
        cleared = device('shell', 'pm', 'clear', PACKAGE)
        if cleared.returncode or 'Success' not in cleared.stdout:
            raise RuntimeError('Unable to reset the disposable automatic-recovery installation')
        result['disposable_installation_reset_before_prepare'] = True
    if not skip_room and not automatic:
        try:
            check_run(instrument(ROOM), 8, 'room-worker.log')
            result['room_worker_tests'] = 8
        except Exception as error:
            result['error'] = str(error)
            (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
            raise
    lock = threading.Lock(); received = threading.Event(); release = threading.Event()
    state = dict(requests=[], effects={})
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *args): pass
        def do_POST(self):
            length = int(self.headers.get('Content-Length', '0'))
            if length not in range(1, 65_537): self.send_error(400); return
            raw = self.rfile.read(length)
            payload = json.loads(raw); key = payload['actionId']; digest = hashlib.sha256(raw).hexdigest()
            with lock:
                existing = state['effects'].get(key)
                if existing is not None and existing != digest: self.send_error(409); return
                state['requests'].append(dict(actionId=key, sha256=digest, payload=payload))
                state['effects'][key] = digest
                (out / 'receiver-ledger.json').write_text(json.dumps(state, indent=2) + '\n')
                first = len(state['requests']) == 1
            if first:
                received.set(); release.wait(45)
            try:
                self.send_response(200); self.end_headers(); self.wfile.write(b'accepted')
            except (BrokenPipeError, ConnectionResetError): pass
    server = ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.daemon_threads = True
    thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
    url = 'http://10.0.2.2:' + str(server.server_port) + '/test-action'
    preparing = None
    try:
        # Both modes are restricted to a disposable test installation. Automatic mode uses its
        # ordinary reminders DB so the unmodified production startup really discovers the row.
        for file in ['no_backup/' + prefix + '.json', 'files/' + prefix + '-result.json']:
            device('shell', 'run-as', PACKAGE, 'rm', '-f', file).check_returncode()
        if automatic:
            device('shell', 'run-as', PACKAGE, 'rm', '-f', 'shared_prefs/automatic-recovery.xml').check_returncode()
        else:
            device('shell', 'run-as', PACKAGE, 'rm', '-f', 'databases/dispatch-recovery-probe.db',
                   'databases/dispatch-recovery-probe.db-wal', 'databases/dispatch-recovery-probe.db-shm').check_returncode()
        with (out / 'prepare-killed.log').open('w') as log:
            preparing = subprocess.Popen([str(adb), '-s', serial, 'shell', 'am', 'instrument', '-w', '-r',
                '-e', 'class', probe, '-e', 'phase', 'prepare', '-e', 's2RecoveryUrl', url, runner],
                env=env, stdout=log, stderr=subprocess.STDOUT)
            if not received.wait(25): raise RuntimeError('Uploading process never reached receiver commit')
            expected = device('exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/' + prefix + '.json')
            expected.check_returncode(); original = json.loads(expected.stdout)
            if automatic:
                deadline = time.monotonic() + 10
                while 'claimedRunAttemptCount' not in original and time.monotonic() < deadline:
                    time.sleep(.05)
                    expected = device('exec-out', 'run-as', PACKAGE, 'cat', 'no_backup/' + prefix + '.json')
                    try: original = json.loads(expected.stdout)
                    except json.JSONDecodeError: continue
                assert 'workId' in original and 'claimedRunAttemptCount' in original
            (out / 'original-action.json').write_text(json.dumps(original, indent=2) + '\n')
            device('shell', 'am', 'force-stop', PACKAGE).check_returncode()
            preparing.wait(timeout=15)
            release.set()
        check_run(instrument(probe, ('phase', 'verify'), ('s2RecoveryUrl', url), timeout=210 if automatic else 90), 1, 'verify-new-process.log')
        proof = device('exec-out', 'run-as', PACKAGE, 'cat', 'files/' + prefix + '-result.json')
        proof.check_returncode(); durable = json.loads(proof.stdout)
        with lock:
            assert len(state['requests']) == 2 and len(state['effects']) == 1, state
            assert state['requests'][0]['sha256'] == state['requests'][1]['sha256']
            assert durable['actionId'] == original['actionId'] == state['requests'][0]['actionId']
        assert durable['oldPid'] != durable['newPid'] and durable['attempts'] == 2
        assert durable['state'] == 'ACKNOWLEDGED' and durable['originalDeadlinePreserved']
        if automatic:
            assert durable['manualRecoveryCalls'] == 0 and durable['observedRetry']
            assert durable['elapsedClaimMillis'] > 60_000 and durable['workId'] == original['workId']
            assert durable['originalRunAttemptCount'] == original['claimedRunAttemptCount']
            assert any(x['state'] == 'ENQUEUED' and x['actionState'] == 'DISPATCHING'
                       and x['runAttemptCount'] > original['claimedRunAttemptCount']
                       for x in durable['workObservations'])
        result.update(passed=True, process_probe=durable, receiver_requests=2, receiver_effects=1,
                      payload_replay_identical=True, prepare_process_intentionally_killed=True)
    finally:
        release.set()
        if preparing is not None and preparing.poll() is None:
            device('shell', 'am', 'force-stop', PACKAGE)
            preparing.wait(timeout=15)
        server.shutdown(); server.server_close(); thread.join(timeout=5)
        result['receiver_stopped'] = not thread.is_alive()
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--adb', required=True)
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', required=True)
    parser.add_argument('--skip-room', action='store_true')
    parser.add_argument('--automatic', action='store_true')
    args = parser.parse_args()
    print(json.dumps(run_checks(args.adb, args.serial, args.output, os.environ, args.skip_room, args.automatic)))
