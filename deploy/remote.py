#!/usr/bin/env python3
"""Server-side rollout: preserve old image/data, rehearse migration, verify, rollback on failure."""
import argparse
import importlib.util
import json
import os
from pathlib import Path
import secrets
import shutil
import subprocess
import tempfile
import time
import urllib.request

os.umask(0o077)

CONTAINER = os.environ.get('PONTO_PG_CONTAINER', 'marketing-postgres')
SUPERUSER = os.environ.get('PONTO_PG_SUPERUSER', 'marketing')
OWNER = os.environ.get('PONTO_PG_OWNER', 'ponto')
DATABASE = os.environ.get('PONTO_PG_DATABASE', 'ponto')


def run(args, project, **kwargs):
    return subprocess.run(args, cwd=project, check=True, **kwargs)


def ensure_edge_certs(edge_dir: Path):
    """Local CA + server cert so PiTunnel --https can verify 127.0.0.1:8443."""
    certs = edge_dir / 'certs'
    certs.mkdir(parents=True, exist_ok=True)
    ca_crt, ca_key = certs / 'ca.crt', certs / 'ca.key'
    cert, key = certs / 'cert.pem', certs / 'key.pem'
    if cert.exists() and key.exists() and ca_crt.exists():
        return
    ext = certs / 'server.ext'
    ext.write_text('authorityKeyIdentifier=keyid,issuer\n'
                   'basicConstraints=CA:FALSE\n'
                   'keyUsage = digitalSignature, keyEncipherment\n'
                   'extendedKeyUsage = serverAuth\n'
                   'subjectAltName = DNS:localhost,IP:127.0.0.1\n')
    for command in (
        ['openssl', 'genrsa', '-out', str(ca_key), '2048'],
        ['openssl', 'req', '-x509', '-new', '-nodes', '-key', str(ca_key), '-sha256', '-days', '3650',
         '-out', str(ca_crt), '-subj', '/CN=ponto-local-ca'],
        ['openssl', 'genrsa', '-out', str(key), '2048'],
        ['openssl', 'req', '-new', '-key', str(key), '-out', str(certs / 'server.csr'), '-subj', '/CN=localhost'],
        ['openssl', 'x509', '-req', '-in', str(certs / 'server.csr'), '-CA', str(ca_crt), '-CAkey', str(ca_key),
         '-CAcreateserial', '-out', str(cert), '-days', '825', '-sha256', '-extfile', str(ext)],
    ):
        subprocess.run(command, check=True)
    os.chmod(ca_key, 0o600)
    os.chmod(key, 0o600)
    system = Path('/usr/local/share/ca-certificates/ponto-local-ca.crt')
    try:
        subprocess.run(['sudo', '-n', 'cp', str(ca_crt), str(system)], check=True)
        subprocess.run(['sudo', '-n', 'update-ca-certificates'], check=True)
    except Exception as error:
        print('Warning: could not install local CA into the system trust store:', error, flush=True)


def module(path, name):
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def counts(database=DATABASE):
    out = subprocess.run(['docker', 'exec', CONTAINER, 'psql', '-U', SUPERUSER, '-d', database,
                           '-v', 'ON_ERROR_STOP=1', '-t', '-A', '-c',
                           'SELECT count(*) FROM ponto UNION ALL SELECT count(*) FROM note UNION ALL '
                           'SELECT count(*) FROM note_file UNION ALL SELECT count(*) FROM folder UNION ALL '
                           'SELECT count(*) FROM tag UNION ALL SELECT count(*) FROM note_tag UNION ALL '
                           'SELECT count(*) FROM company UNION ALL SELECT count(*) FROM dia_flag'],
                          check=True, capture_output=True, text=True).stdout.strip().splitlines()
    return dict(zip(('ponto', 'note', 'note_file', 'folder', 'tag', 'note_tag', 'company', 'dia_flag'),
                    (int(x) for x in out)))

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
    env.setdefault('PONTO_EDGE_DIR', str(Path.home() / '.local/share/ponto-edge'))
    # Rotate the old default pair once. Values are never printed or sent from the workstation.
    if env.get('PONTO_S3_ACCESS') in (None, '', 'minioadmin'):
        env['PONTO_S3_ACCESS'] = 'ponto-' + secrets.token_hex(10)
    if env.get('PONTO_S3_SECRET') in (None, '', 'minioadmin'):
        env['PONTO_S3_SECRET'] = secrets.token_urlsafe(36)
    if not env.get('PONTO_WEB_USER') or not env.get('PONTO_WEB_PASSWORD'):
        raise RuntimeError('Configure PONTO_WEB_USER and PONTO_WEB_PASSWORD in the server .env before deploy')
    ensure_edge_certs(Path(env['PONTO_EDGE_DIR']))
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
        baseline = counts()
        trial_db = 'ponto_trial_' + secrets.token_hex(3)
        url = env['PONTO_DB_URL']
        base_url, params = url.split('?', 1) if '?' in url else (url, '')
        # The base URL may already carry a database name (e.g. "…/5432/ponto").
        # Drop any existing last path segment and insert only the trial db name,
        # so the trial URL becomes "…/5432/ponto_trial_xxx" and the driver parses it.
        scheme, _, rest = base_url.partition('://')
        slash = rest.rfind('/')
        hostport = rest[:slash] if slash >= 0 else rest
        trial_url = scheme + '://' + hostport + '/' + trial_db + ('?' + params if params else '')
        run(['docker', 'exec', CONTAINER, 'psql', '-U', SUPERUSER, '-d', 'postgres',
             '-v', 'ON_ERROR_STOP=1', '-c', 'CREATE DATABASE ' + trial_db + ' OWNER ' + OWNER + ';'], project)
        subprocess.run(['docker', 'cp', str(snapshot / 'ponto.dump'), CONTAINER + ':/tmp/ponto.dump'],
                       check=True, capture_output=True)
        try:
            # --role so objects land as OWNER even though restore runs as SUPERUSER.
            run(['docker', 'exec', CONTAINER, 'pg_restore', '-U', SUPERUSER, '-d', trial_db,
                 '--no-owner', '--role=' + OWNER, '/tmp/ponto.dump'], project)
            run(['docker', 'run', '--rm', '--network', 'agenttk_default',
                 '-e', 'TZ=' + env['PONTO_TZ'], '-e', 'PONTO_DB=' + trial_url, candidate, '--check-db'], project)
            migrated = counts(trial_db)
            assert all(migrated.get(name) == value for name, value in baseline.items()), 'Migration changed existing row counts'
        finally:
            run(['docker', 'exec', CONTAINER, 'psql', '-U', SUPERUSER, '-d', 'postgres',
                 '-v', 'ON_ERROR_STOP=1', '-c', 'DROP DATABASE IF EXISTS ' + trial_db + ' WITH (FORCE);'], project)
            subprocess.run(['docker', 'exec', CONTAINER, 'rm', '-f', '/tmp/ponto.dump'], check=False)
        for name in ('Dockerfile', '.dockerignore', 'docker-compose.yml', 'Caddyfile', '.env', 'target/ponto.jar',
                     'deploy/backup.py', 'deploy/smoke.py', 'deploy/remote.py'):
            destination = project / name
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(stage / name, destination)
        installed = True
        run(['docker', 'tag', candidate, 'ponto:web'], project)
        # Drop a manual edge test container if present so compose owns the name/port.
        subprocess.run(['docker', 'rm', '-f', 'ponto-edge', 'ponto-edge-test'], check=False)
        run(['docker', 'compose', 'up', '-d', '--force-recreate', 'minio', 'ponto', 'edge'], project)
        base = 'http://127.0.0.1:' + env['PONTO_PORT']
        wait(base)
        smoke.check(base, user=env['PONTO_WEB_USER'], password=env['PONTO_WEB_PASSWORD'])
        after = counts()
        assert all(after.get(name, 0) >= value for name, value in baseline.items()), 'Existing row count decreased'
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
            run(['docker', 'compose', 'up', '-d', 'minio', 'ponto', 'edge'], project)
        raise

if __name__ == '__main__':
    main()
