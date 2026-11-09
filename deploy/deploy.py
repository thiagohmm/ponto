#!/usr/bin/env python3
"""Build, verify, stage and deploy without ever sending a local database."""
import os
from pathlib import Path
import shlex
import subprocess
import tarfile
import tempfile
import uuid


def run(args, **kwargs):
    subprocess.run(args, check=True, **kwargs)


def main():
    project = Path(__file__).resolve().parent.parent
    os.chdir(project)
    host = os.environ.get('PONTO_HOST', 'thiagohmm@192.168.0.8')
    remote = os.environ.get('PONTO_REMOTE', '/home/thiagohmm/ponto-web')
    for command in (
        ['npm', '--prefix', 'web', 'ci'],
        ['npm', '--prefix', 'web', 'test'],
        ['npm', '--prefix', 'web', 'run', 'build'],
        ['mvn', '-B', 'verify'],
        ['./test/smoke.sh'],
        ['python3', '-m', 'unittest', 'discover', '-s', 'test', '-p', 'test_backup.py'],
        ['npm', '--prefix', 'web', 'run', 'test:e2e'],
    ):
        run(command)
    stage = remote + '/incoming/' + uuid.uuid4().hex
    run(['ssh', host, 'mkdir -p ' + shlex.quote(stage)])
    names = ['target/ponto.jar', 'Dockerfile', 'docker-compose.yml', 'Caddyfile', '.dockerignore',
             'deploy/backup.py', 'deploy/remote.py', 'deploy/smoke.py', '.env.example']
    with tempfile.TemporaryFile() as packed:
        with tarfile.open(fileobj=packed, mode='w:gz') as archive:
            for name in names:
                archive.add(project / name, arcname=name)
        packed.seek(0)
        run(['ssh', host, 'tar -xzf - -C ' + shlex.quote(stage)], stdin=packed)
    command = ['python3', stage + '/deploy/remote.py', '--project', remote, '--staging', stage]
    run(['ssh', host, shlex.join(command)])

if __name__ == '__main__':
    main()
