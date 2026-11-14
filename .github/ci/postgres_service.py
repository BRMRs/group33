"""Restart only this GitHub job's disposable PostgreSQL service for the real restart test."""
import json
import os
import re
import subprocess
import sys
import time


def main():
    if os.environ.get('GITHUB_ACTIONS') != 'true' or len(sys.argv) != 2 or sys.argv[1] not in ['stop', 'start']:
        raise RuntimeError('This fixture helper requires its GitHub job context')
    identity = os.environ.get('CI_POSTGRES_CONTAINER', '')
    if not re.fullmatch('[0-9a-f]{64}', identity):
        raise RuntimeError('Missing exact job-owned PostgreSQL container')
    if os.environ.get('S2_PG_INTEGRATION_URL') != 'jdbc:postgresql://127.0.0.1:55432/group33_s2_local':
        raise RuntimeError('Refusing another database fixture')

    def docker(*args, timeout=15):
        return subprocess.run(['docker', *args], capture_output=True, text=True, timeout=timeout)

    inspected = docker('inspect', identity)
    inspected.check_returncode()
    info = json.loads(inspected.stdout)
    if len(info) != 1 or info[0]['Id'] != identity or info[0]['Config']['Image'] != 'postgres:17':
        raise RuntimeError('Unexpected fixture container')
    if sys.argv[1] == 'stop':
        docker('stop', '--time', '5', identity).check_returncode()
        print('Disposable PostgreSQL fixture stopped')
        return
    docker('start', identity).check_returncode()
    deadline = time.monotonic() + 25
    while time.monotonic() < deadline:
        if docker('exec', identity, 'pg_isready', '-U', 'postgres', '-d', 'group33_s2_local', timeout=5).returncode == 0:
            print('Disposable PostgreSQL fixture running')
            return
        time.sleep(1)
    raise RuntimeError('Fixture restart readiness deadline exceeded')


if __name__ == '__main__':
    main()
