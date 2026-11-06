#!/usr/bin/env python3
"""Server-side rollout: preserve old image/data, rehearse migration, verify, rollback on failure."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import sqlite3
import subprocess
import tempfile
import time
import urllib.request

os.umask(0o077)


def run(args, project, **kwargs):
    return subprocess.run(args, cwd=project, check=True, **kwargs)


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def counts(file):
    with sqlite3.connect(f'file:{file}?mode=ro', uri=True) as db:
        names = {row[0] for row in db.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        return {table: db.execute('SELECT count(*) FROM ' + table).fetchone()[0]
                for table in ('ponto', 'note', 'note_file', 'folder', 'tag', 'note_tag', 'company', 'dia_flag') if table in names}


def wait(base):
    for _ in range(90):
        try:
            with urllib.request.urlopen(base + '/api/health', timeout=5) as response:
                health = json.load(response)
                if health['status'] == 'ok' and health['storage'] == 'ok':
                    return health
        except Exception:
            pass
        time.sleep(1)
    raise RuntimeError('Application or storage failed readiness verification')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--project', type=Path, required=True)
    parser.add_argument('--staging', type=Path, required=True)
    args = parser.parse_args()
    project, stage = args.project.resolve(), args.staging.resolve()
    backup = module(stage / 'deploy/backup.py', 'backup')
    smoke = module(stage / 'deploy/smoke.py', 'smoke')
    env = backup.settings(project)
    env.setdefault('PONTO_DATA_DIR', str(Path.home() / '.local/share/ponto'))
    env.setdefault('PONTO_OBJECT_DIR', str(Path.home() / '.local/share/ponto-minio'))
    env.setdefault('PONTO_BACKUP_DIR', str(Path.home() / '.local/share/ponto-backups'))
    env.setdefault('PONTO_PORT', '8090')
    env.setdefault('PONTO_TZ', 'America/Sao_Paulo')
    env.setdefault('PONTO_S3_BUCKET', 'ponto')
    # Rotate the old default pair once. Values are never printed or sent from the workstation.
    if env.get('PONTO_S3_ACCESS') in (None, '', 'minioadmin'):
        env['PONTO_S3_ACCESS'] = 'ponto-' + secrets.token_hex(10)
    if env.get('PONTO_S3_SECRET') in (None, '', 'minioadmin'):
        env['PONTO_S3_SECRET'] = secrets.token_urlsafe(36)
    (stage / '.env').write_text(''.join(key + '=' + value + '\n' for key, value in env.items()))
    os.chmod(stage / '.env', 0o600)
    candidate = 'ponto:candidate-' + secrets.token_hex(5)
    run(['docker', 'build', '-t', candidate, '.'], stage)
    # Backup uses the currently deployed compose/config, before installing the candidate.
    result = run(['python3', str(stage / 'deploy/backup.py'), 'backup', '--project', str(project),
                  '--data', env['PONTO_DATA_DIR'], '--objects', env['PONTO_OBJECT_DIR'], '--root', env['PONTO_BACKUP_DIR'],
                  '--deployment', '--keep-stopped'], project, capture_output=True, text=True)
    snapshot = Path(result.stdout.strip().splitlines()[-1])
    print('Deployment snapshot: ' + str(snapshot), flush=True)
    installed = False
    try:
        baseline = counts(snapshot / 'ponto.db')
        with tempfile.TemporaryDirectory(prefix='migration-check-', dir=project) as trial:
            shutil.copy2(snapshot / 'ponto.db', Path(trial) / 'ponto.db')
            run(['docker', 'run', '--rm', '--network', 'none', '-v', trial + ':/data', candidate,
                 '--db=/data/ponto.db', '--check-db'], project)
            migrated = counts(Path(trial) / 'ponto.db')
            assert all(migrated.get(name) == value for name, value in baseline.items()), 'Migration changed existing row counts'
        for name in ('Dockerfile', '.dockerignore', 'docker-compose.yml', '.env', 'target/ponto.jar',
                     'deploy/backup.py', 'deploy/smoke.py', 'deploy/remote.py'):
            destination = project / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(stage / name, destination)
        installed = True
        run(['docker', 'tag', candidate, 'ponto:web'], project)
        run(['docker', 'compose', 'up', '-d', '--force-recreate', 'minio', 'ponto'], project)
        base = 'http://127.0.0.1:' + env['PONTO_PORT']
        wait(base)
        smoke.check(base)
        after = counts(Path(env['PONTO_DATA_DIR']) / 'ponto.db')
        assert all(after.get(name, 0) >= value for name, value in baseline.items()), 'Existing row count decreased'
        # Also verify database-level constraints on the real migrated database.
        with sqlite3.connect(Path(env['PONTO_DATA_DIR']) / 'ponto.db') as db:
            assert db.execute('PRAGMA integrity_check').fetchone()[0] == 'ok'
            assert db.execute('PRAGMA foreign_key_check').fetchone() is None
        for _ in range(15):
            if urllib.request.urlopen(base + '/api/health', timeout=5):
                with urllib.request.urlopen(base + '/api/health', timeout=5) as response:
                    if json.load(response)['pendingObjects'] == 0:
                        break
            time.sleep(1)
        # Preserve all existing cron entries; replace only this application's backup line.
        current = subprocess.run(['crontab', '-l'], capture_output=True, text=True)
        lines = [line for line in current.stdout.splitlines() if '# ponto-backup' not in line]
        import shlex
        cron_command = shlex.join(['python3', str(project / 'deploy/backup.py'), 'backup', '--project', str(project)])
        lines.append('15 3 * * * ' + cron_command + ' >> ' + shlex.quote(str(Path(env['PONTO_BACKUP_DIR']) / 'backup.log')) + ' 2>&1 # ponto-backup')
        run(['crontab', '-'], project, input='\n'.join(lines) + '\n', text=True)
        release = {'image': candidate, 'snapshot': str(snapshot), 'url': 'http://192.168.0.8:' + env['PONTO_PORT'],
                   'sha256': backup.digest(project / 'target/ponto.jar'), 'counts': after}
        (project / 'release.json').write_text(json.dumps(release, indent=2) + '\n')
        print(json.dumps(release, indent=2))
    except Exception:
        print('Deployment failed; restoring previous application and data.', flush=True)
        if installed:
            run(['python3', str(stage / 'deploy/backup.py'), 'restore', '--project', str(project),
                 '--data', env['PONTO_DATA_DIR'], '--objects', env['PONTO_OBJECT_DIR'], '--root', env['PONTO_BACKUP_DIR'],
                 '--snapshot', str(snapshot), '--apply', '--restore-config'], project)
        else:
            run(['docker', 'compose', 'up', '-d', 'minio', 'ponto'], project)
        raise

if __name__ == '__main__':
    main()
