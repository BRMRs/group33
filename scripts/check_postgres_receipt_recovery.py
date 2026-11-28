#!/usr/bin/env python3
"""Real packaged Server/TCP/PostgreSQL response-loss and restart verification.

Requires an explicitly configured loopback test DB (S2_RECOVERY_PG_*). Creates and drops only
its own random schema. No cloud/provider, migration-history repair, or Android transport mock.
Credentials/keys and detailed startup failures remain under a mode-0700 private directory.
"""
import argparse
import binascii
from datetime import datetime, timedelta, timezone
import hashlib
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import http.client
import json
import os
from pathlib import Path
import re
import secrets
import shutil
import socket
import struct
import subprocess
import threading
import time
import urllib.error
import urllib.request
import uuid
import zlib

ROOT = Path(__file__).resolve().parents[1]


def run_checks(output, env=None):
    incoming = dict(os.environ if env is None else env)
    def required(name):
        value = incoming.get('S2_RECOVERY_PG_' + name)
        if not value: raise RuntimeError('Missing explicit test database setting: ' + name)
        return value
    host = required('HOST'); port = int(required('PORT')); database = required('DATABASE')
    user = required('USER'); password = required('PASSWORD')
    if host not in ('127.0.0.1', 'localhost') or not 1 <= port <= 65535 or not re.fullmatch(r'[A-Za-z0-9_-]+', database):
        raise RuntimeError('Only an explicit loopback test database is supported')
    psql = incoming.get('S2_RECOVERY_PSQL') or shutil.which('psql')
    java = incoming.get('S2_RECOVERY_JAVA') or shutil.which('java')
    if not psql or not java: raise RuntimeError('Explicit psql and Java17 tools are required')
    jar = ROOT / 'server/build/libs/server-0.1.0.jar'
    if not jar.is_file(): raise RuntimeError('Build :server:bootJar first')
    out = Path(output).resolve(); out.mkdir(parents=True, exist_ok=True)
    private = out / 'private'; private.mkdir(mode=0o700, exist_ok=True); private.chmod(0o700)
    key = private / 'receipt.key'; key.write_bytes(secrets.token_bytes(32)); key.chmod(0o600)
    objects = private / 'objects'; objects.mkdir(mode=0o700, exist_ok=True); objects.chmod(0o700)
    schema = 's2_recovery_' + uuid.uuid4().hex
    pg_env = {k: v for k, v in incoming.items() if not k.startswith('PG')}
    pg_env.update(PGPASSWORD=password, PGCONNECT_TIMEOUT='5', PGSSLMODE='disable')
    app_env = {k: v for k, v in incoming.items() if k in ('PATH', 'LANG', 'TMPDIR', 'JAVA_HOME', 'HOME')}
    app_env.update(PERSISTENCE_MODE='postgres-local', PERSISTENCE_REQUIRE_VERSIONED_SCHEMA='false',
        PERSISTENCE_JDBC_URL=f'jdbc:postgresql://{host}:{port}/{database}', PERSISTENCE_SCHEMA=schema,
        PERSISTENCE_USERNAME=user, PERSISTENCE_PASSWORD=password, PERSISTENCE_RECEIPT_KEY_FILE=str(key),
        JWT_SECRET=secrets.token_urlsafe(64), PHOTO_ENABLED='true', PLAN_ENABLED='true',
        OCCURRENCE_ENABLED='true', DOSE_GROUP_ENABLED='true', HELP_ENABLED='true',
        PHOTO_OBJECT_ROOT=str(objects))
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    result = dict(passed=False, real_server=True, real_postgres=True, memory_store=False,
                  schema_fixture='isolated existing V1-V11 SQL; no managed rollout claimed', checks=[])
    process = None; processes = []; schema_created = False; proxy = None; proxy_thread = None
    def check(label, condition):
        result['checks'].append(dict(check=label, passed=bool(condition)))
        if not condition: raise AssertionError(label)
    def sql(source, scoped=True):
        command = [str(psql), '-XAtqw', '-v', 'ON_ERROR_STOP=1', '-h', host, '-p', str(port), '-U', user, '-d', database]
        text = (f'SET search_path TO {schema};\n' if scoped else '') + source
        r = subprocess.run(command, input=text, env=pg_env, capture_output=True, text=True, timeout=30)
        if r.returncode:
            (private / 'psql-error.log').write_text(r.stderr)
            raise RuntimeError('Private test SQL failed; details excluded from public results')
        return r.stdout.strip()
    def stop():
        nonlocal process
        if process is not None and process.poll() is None:
            process.terminate()
            try: process.wait(timeout=10)
            except subprocess.TimeoutExpired: process.kill(); process.wait(timeout=5)
    def start(label):
        nonlocal process
        with socket.socket() as reservation:
            reservation.bind(('127.0.0.1', 0)); server_port = reservation.getsockname()[1]
        with (private / (label + '-server.log')).open('w') as log:
            process = subprocess.Popen([str(java), '-jar', str(jar), '--server.address=127.0.0.1',
                '--server.port=' + str(server_port)], cwd=ROOT, env=app_env, stdout=log, stderr=subprocess.STDOUT)
        processes.append(process)
        base = f'http://127.0.0.1:{server_port}'; end = time.monotonic() + 60
        while process.poll() is None and time.monotonic() < end:
            try:
                with opener.open(base + '/health', timeout=1) as response:
                    if response.status == 200: return base
            except (OSError, urllib.error.URLError): pass
            time.sleep(.15)
        raise RuntimeError('Packaged test Server did not start; private log retained')
    def request(base, method, path, body=None, token=None, operation=None, content_type='application/json'):
        headers = {'Content-Type': content_type}
        if token: headers['Authorization'] = 'Bearer ' + token
        if operation: headers['Idempotency-Key'] = operation
        data = json.dumps(body).encode() if isinstance(body, dict) else body
        req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
        try: response = opener.open(req, timeout=20)
        except urllib.error.HTTPError as error: response = error
        with response: return response.status, dict(response.headers), response.read()
    def call(label, expected, base, method, path, body=None, token=None, operation=None, content_type='application/json'):
        status, headers, raw = request(base, method, path, body, token, operation, content_type)
        check(label + '-status', status == expected)
        check(label + '-no-store', 'no-store' in headers.get('Cache-Control', ''))
        return json.loads(raw) if raw else None
    def op(): return str(uuid.uuid4())
    def counts():
        return json.loads(sql("SELECT json_build_object('occurrences',(SELECT count(*) FROM registered_occurrence),"
            "'events',(SELECT count(*) FROM recorded_dose_event),'operations',(SELECT count(*) FROM dose_group_operation))"))
    try:
        sql('CREATE SCHEMA ' + schema, scoped=False); schema_created = True
        migrations = sorted((ROOT / 'server/src/schemaMigration/resources/db/migration').glob('V*__*.sql'),
                            key=lambda f: int(f.name.split('__')[0][1:]))
        check('fixture-uses-existing-eleven-migrations', [int(f.name.split('__')[0][1:]) for f in migrations] == list(range(1, 12)))
        sql('BEGIN;\n' + '\n'.join(f.read_text() for f in migrations) + '\nCOMMIT;')
        base = start('before-restart'); first_pid = process.pid
        elder = call('elder-login', 201, base, 'POST', '/api/v1/auth/anonymous',
                     dict(installationId=op(), role='ELDER', displayName='Fictional recovery Elder'))
        caregiver = call('caregiver-login', 201, base, 'POST', '/api/v1/auth/anonymous',
                         dict(installationId=op(), role='CAREGIVER', displayName='Fictional recovery caregiver'))
        eid = elder['user']['id']; et = elder['accessToken']; ct = caregiver['accessToken']
        code = call('issue-code', 201, base, 'POST', '/api/v1/pairing-codes', {}, et, op())
        pending = call('request-pairing', 201, base, 'POST', '/api/v1/pairing-requests', {'code': code['code']}, ct, op())
        call('elder-consent', 200, base, 'POST', '/api/v1/pairing-requests/' + pending['id'] + '/accept', {}, et, op())
        planned = (datetime.now(timezone.utc) - timedelta(minutes=2)).replace(second=0, microsecond=0)
        planned_at = int(planned.timestamp() * 1000); evidence = []
        def chunk(kind, value):
            return struct.pack('>I', len(value)) + kind + value + struct.pack('>I', binascii.crc32(kind + value) & 0xffffffff)
        image = b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', 1, 1, 8, 2, 0, 0, 0)) + chunk(b'IDAT', zlib.compress(b'\x00\x00\x00\x00')) + chunk(b'IEND', b'')
        for index in range(2):
            boundary = 'fixture-' + uuid.uuid4().hex
            multipart = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="fictional.png"\r\nContent-Type: image/png\r\n\r\n'.encode() + image + f'\r\n--{boundary}--\r\n'.encode())
            photo = call('photo-' + str(index), 201, base, 'POST', f'/api/v1/elders/{eid}/medication-photos',
                         multipart, et, op(), 'multipart/form-data; boundary=' + boundary)
            confirmed = call('plan-' + str(index), 201, base, 'POST', f'/api/v1/elders/{eid}/reminder-plan-confirmations',
                dict(photoId=photo['id'], medication=dict(displayName='Fictional medicine ' + str(index), dosageText='Demo unit'),
                    plan=dict(localTimes=[planned.strftime('%H:%M')], startDate=planned.date().isoformat(), executionTimeZoneId='UTC'),
                    timeZoneAcknowledgement=dict(executionTimeZoneId='UTC', mismatchAcknowledged=True)), ct, op())
            evidence.append(dict(generationId=op(), occurrence=dict(planId=confirmed['plan']['id'], localDate=planned.date().isoformat(), localTime=planned.strftime('%H:%M')),
                planRevision=1, generatedAtEpochMillis=int(time.time() * 1000), executionTimeZoneId='UTC', plannedAtEpochMillis=planned_at,
                resolvedOffsetSeconds=0, resolutionPolicyVersion='P0_TIME_V1', sourceSnapshotVersion='s2-recovery-fixture',
                sourceSnapshotServerAtEpochMillis=confirmed['plan']['confirmedAtEpochMillis']))
        registration_key = op(); registration_path = f'/api/v1/elders/{eid}/occurrence-registrations'
        registration = call('register-frozen-pair', 200, base, 'POST', registration_path, dict(items=evidence), et, registration_key)
        check('two-canonical-events-before-actions', counts() == dict(occurrences=2, events=0, operations=0))
        action_id = op(); path = f'/api/v1/elders/{eid}/dose-action-groups'
        action = dict(actionId=action_id, occurrences=[e['occurrence'] for e in evidence], kind='REMIND_LATER', recordedAtEpochMillis=int(time.time() * 1000) - 1)
        captured = {}; reached = threading.Event()
        class LostResponseProxy(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_POST(self):
                raw = self.rfile.read(int(self.headers['Content-Length']))
                status, headers, response = request(base, 'POST', path, raw, et, action_id)
                captured.update(status=status, response=response, request_sha256=hashlib.sha256(raw).hexdigest())
                reached.set()
                self.connection.shutdown(socket.SHUT_RDWR); self.connection.close()
        proxy = ThreadingHTTPServer(('127.0.0.1', 0), LostResponseProxy)
        proxy_thread = threading.Thread(target=proxy.serve_forever, daemon=True); proxy_thread.start()
        lost = False
        try: request('http://127.0.0.1:' + str(proxy.server_port), 'POST', path, action, et, action_id)
        except (OSError, http.client.HTTPException, urllib.error.URLError): lost = True
        check('client-loses-response-after-real-server-acceptance', lost and reached.wait(2) and captured['status'] == 200)
        original = json.loads(captured['response'])
        check('original-receipt-has-two-snoozed-events', original['actionId'] == action_id and len(original['events']) == 2
              and all(e['status'] == 'SNOOZED' and e['responseDeadlineEpochMillis'] == planned_at + 7_200_000 for e in original['events']))
        check('committed-whole-group-despite-lost-response', counts() == dict(occurrences=2, events=2, operations=1))
        for index in range(3):
            replay = call('same-action-replay-' + str(index), 200, base, 'POST', path, action, et, action_id)
            check('same-original-receipt-' + str(index), replay == original)
        check('retries-have-one-operation-and-no-extra-events', counts() == dict(occurrences=2, events=2, operations=1))
        changed = dict(action, kind='TAKEN')
        conflict = call('same-key-different-intent', 409, base, 'POST', path, changed, et, action_id)
        check('changed-intent-conflicts', conflict['code'] == 'IDEMPOTENCY_KEY_REUSED')
        taken_id = op(); taken = dict(action, actionId=taken_id, kind='TAKEN', recordedAtEpochMillis=int(time.time() * 1000) - 1)
        accepted_taken = call('fresh-taken', 200, base, 'POST', path, taken, et, taken_id)
        check('fresh-taken-is-current', all(e['status'] == 'TAKEN' for e in accepted_taken['events']))
        check('old-receipt-does-not-become-current-state', call('old-snooze-after-taken', 200, base, 'POST', path, action, et, action_id) == original)
        date = planned.date().isoformat(); read_path = f'/api/v1/elders/{eid}/dose-events?fromDate={date}&throughDate={date}'
        rows = call('caregiver-current-read', 200, base, 'GET', read_path, token=ct)['items']
        check('current-read-keeps-taken', len(rows) == 2 and all(e['status'] == 'TAKEN' for e in rows))
        before_restart = counts(); check('exact-two-legitimate-operations', before_restart == dict(occurrences=2, events=2, operations=2))
        stop(); check('first-server-process-stopped', processes[0].poll() is not None)
        base = start('after-restart'); check('different-server-jvm', process.pid != first_pid)
        check('original-snooze-survives-server-restart', call('old-receipt-restarted', 200, base, 'POST', path, action, et, action_id) == original)
        check('taken-receipt-survives-server-restart', call('taken-receipt-restarted', 200, base, 'POST', path, taken, et, taken_id) == accepted_taken)
        check('registration-receipt-remains-original', call('registration-restarted', 200, base, 'POST', registration_path, dict(items=evidence), et, registration_key) == registration)
        latest = call('caregiver-current-after-restart', 200, base, 'GET', read_path, token=ct)['items']
        check('current-facts-survive-restart', latest == rows and counts() == before_restart)
        help_id = op(); help_path = '/api/v1/help'
        help_request = dict(actionId=help_id, elderId=eid, groupId='p0-help-recovery-group',
                            occurrenceIds=[e['generationId'] for e in evidence],
                            occurredAtEpochMillis=int(time.time() * 1000) - 1)
        help_receipt = call('help-first-accept', 200, base, 'POST', help_path, help_request, et, help_id)
        check('help-receipt-is-accepted', help_receipt['actionId'] == help_id
              and help_receipt['groupId'] == help_request['groupId'] and help_receipt['status'] == 'ACCEPTED')
        check('help-identical-replay-keeps-original-receipt',
              call('help-identical-replay', 200, base, 'POST', help_path, help_request, et, help_id) == help_receipt)
        changed_help = dict(help_request, groupId='different-help-group')
        help_conflict = call('help-changed-replay', 409, base, 'POST', help_path, changed_help, et, help_id)
        check('help-changed-replay-conflicts', help_conflict['code'] == 'IDEMPOTENCY_KEY_REUSED')
        follow_up_path = f'/api/v1/elders/{eid}/caregiver-follow-ups'
        follow_ups = call('caregiver-help-read', 200, base, 'GET', follow_up_path, token=ct)['items']
        check('caregiver-sees-one-needs-contact', len(follow_ups) == 1 and follow_ups[0]['actionId'] == help_id
              and follow_ups[0]['state'] == 'NEEDS_CONTACT')
        check('one-postgres-help-row', sql('SELECT count(*) FROM caregiver_follow_up') == '1')
        stop(); check('help-server-process-stopped', processes[-1].poll() is not None)
        base = start('after-help-restart')
        check('help-receipt-survives-server-restart',
              call('help-replay-after-restart', 200, base, 'POST', help_path, help_request, et, help_id) == help_receipt)
        check('help-follow-up-survives-server-restart',
              call('caregiver-help-read-after-restart', 200, base, 'GET', follow_up_path, token=ct)['items'] == follow_ups)
        check('still-one-postgres-help-row', sql('SELECT count(*) FROM caregiver_follow_up') == '1')
        result.update(passed=True, receiver_mock=False, response_loss_proxy_only=True, original_action_id=action_id,
            original_receipt_sha256=hashlib.sha256(json.dumps(original, sort_keys=True).encode()).hexdigest(),
            original_payload_sha256=captured['request_sha256'], business_rows=before_restart,
            original_receipt_immutable=True, current_facts_not_overwritten=True,
            help_idempotent=True, help_follow_up_persisted=True, server_pids=[x.pid for x in processes],
            boot_jar_sha256=hashlib.sha256(jar.read_bytes()).hexdigest())
    finally:
        if proxy is not None: proxy.shutdown(); proxy.server_close()
        if proxy_thread is not None: proxy_thread.join(timeout=5)
        stop()
        result['server_processes_stopped'] = all(x.poll() is not None for x in processes)
        result['response_proxy_stopped'] = proxy_thread is None or not proxy_thread.is_alive()
        if schema_created:
            sql('DROP SCHEMA ' + schema + ' CASCADE', scoped=False)
            result['isolated_schema_removed'] = True
        if result['passed']:
            shutil.rmtree(private)
            result['private_fixture_removed'] = True
        # Never include live credentials/keys/private logs in uploaded evidence.
        (out / 'result.json').write_text(json.dumps(result, indent=2) + '\n')
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--output', required=True)
    args = parser.parse_args()
    print(json.dumps(run_checks(args.output)))
