"""End-to-end test for deploy/backup.py against the real PostgreSQL container:
backup, verify, restore (dry-run + apply), and corruption detection.

Uses the same ponto-test-pg container the Java tests use (auto-booted here in
Python so this suite does not depend on the Maven build).
"""
import json
import os
import re
import shutil
import subprocess
import tempfile
import time
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SCRIPT = ROOT / 'deploy/backup.py'
CONTAINER = 'ponto-test-pg'
DB = 'ponto_backup_test'
PORT = 55432
NOTE_BODY = 'corpo preservado pelo backup'


def sh(args, **kw):
    return subprocess.run(args, check=True, capture_output=True, text=True, **kw)


def ensure_pg():
    """Boot ponto-test-pg (postgres:16-alpine on tmpfs) at 127.0.0.1:PORT if absent."""
    r = subprocess.run(['docker', 'ps', '-q', '--filter', 'name=^' + CONTAINER + '$'],
                       capture_output=True, text=True)
    if r.stdout.strip():
        return
    subprocess.run(['docker', 'rm', '-f', CONTAINER], capture_output=True)
    sh(['docker', 'run', '-d', '--name', CONTAINER,
        '-p', f'127.0.0.1:{PORT}:5432',
        '--tmpfs', '/var/lib/postgresql/data',
        '-e', 'POSTGRES_USER=ponto', '-e', 'POSTGRES_PASSWORD=ponto',
        '-e', 'POSTGRES_DB=ponto_test', '-e', 'TZ=America/Sao_Paulo',
        'postgres:16-alpine'])
    for _ in range(120):
        r = subprocess.run(['docker', 'exec', CONTAINER, 'pg_isready', '-U', 'ponto'],
                           capture_output=True)
        if r.returncode == 0:
            return
        time.sleep(0.5)
    raise AssertionError('ponto-test-pg did not become ready')


def classpath():
    cp_file = ROOT / 'target/cp.txt'
    if not cp_file.exists():
        sh(['mvn', '-q', '-o', 'dependency:build-classpath',
            '-Dmdep.outputFile=target/cp.txt'], cwd=ROOT)
    deps = cp_file.read_text().strip()
    return os.pathsep.join([str(ROOT / 'target/classes'), deps, str(ROOT / 'target/test-classes')])


def fixture(url):
    """Create the v5 schema + one note via the app itself (BackupFixture)."""
    out = subprocess.run(['java', '-cp', classpath(), 'ponto.BackupFixture', url],
                         capture_output=True, text=True)
    self_msg = out.stdout + out.stderr
    assert out.returncode == 0, 'BackupFixture falhou: ' + self_msg


def local_server():
    """CI (and any host with psql): reuse $PONTO_TEST_PG instead of a second container."""
    raw = os.environ.get('PONTO_TEST_PG', '').strip()
    if not raw or not shutil.which('psql'):
        return None
    m = re.fullmatch(r'(?:jdbc:)?postgresql://([^/:]+)(?::(\d+))?/[^?]+(?:\?(.*))?', raw)
    if not m:
        return None
    host, port, qs = m.group(1), m.group(2) or '5432', m.group(3) or ''
    params = dict(pair.split('=', 1) for pair in qs.split('&') if '=' in pair)
    user = params.get('user', 'ponto')
    password = params.get('password', os.environ.get('PGPASSWORD', 'ponto'))
    return host, port, user, password


class BackupTest(unittest.TestCase):
    def test_snapshot_backup_restore_and_corruption(self):
        local = local_server()
        if local:
            host, port, user, password = local
            env_pg = os.environ.copy()
            env_pg['PGPASSWORD'] = password
            sh(['psql', '-h', host, '-p', port, '-U', user, '-d', 'postgres', '-v', 'ON_ERROR_STOP=1',
                '-c', f'DROP DATABASE IF EXISTS {DB} WITH (FORCE);',
                '-c', f'CREATE DATABASE {DB} OWNER {user};'], env=env_pg)
            url = f'jdbc:postgresql://{host}:{port}/{DB}?user={user}&password={password}'
            pg_env = dict(PONTO_PG_CONTAINER='', PONTO_PG_URL=f'postgresql://{user}:{password}@{host}:{port}/{DB}',
                          PONTO_PG_DATABASE=DB, PONTO_PG_SUPERUSER=user, PONTO_PG_OWNER=user)
            query = lambda sql: sh(['psql', '--dbname=' + pg_env['PONTO_PG_URL'], '-t', '-A', '-c', sql]).stdout.strip()
        else:
            if not shutil.which('docker'):
                self.skipTest('docker not available')
            ensure_pg()
            # Drop & recreate the test database; the fixture rebuilds the v5 schema.
            sh(['docker', 'exec', CONTAINER, 'psql', '-U', 'ponto', '-d', 'postgres', '-v', 'ON_ERROR_STOP=1',
                '-c', f'DROP DATABASE IF EXISTS {DB} WITH (FORCE);',
                '-c', f'CREATE DATABASE {DB} OWNER ponto;'])
            url = f'jdbc:postgresql://127.0.0.1:{PORT}/{DB}?user=ponto&password=ponto'
            pg_env = dict(PONTO_PG_CONTAINER=CONTAINER, PONTO_PG_SUPERUSER='ponto',
                          PONTO_PG_DATABASE=DB, PONTO_PG_OWNER='ponto')
            query = lambda sql: sh(['docker', 'exec', CONTAINER, 'psql', '-U', 'ponto', '-d', DB,
                                    '-t', '-A', '-c', sql]).stdout.strip()
        fixture(url)
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            project, data, objects, backups = (root / n for n in ('project', 'data', 'objects', 'backups'))
            project.mkdir(); objects.mkdir(); (data / 'uploads').mkdir(parents=True)
            (objects / 'attachment').write_bytes(b'attachment bytes')
            (data / 'uploads' / 'pending').write_bytes(b'pending upload')
            (project / '.env').write_text('PONTO_S3_SECRET=private\n')
            env = dict(os.environ, **pg_env)

            def backup_cli(*args):
                # Caller flags come last so restore can override --data/--objects.
                return ['python3', str(SCRIPT), '--project', str(project),
                        '--data', str(data), '--objects', str(objects), '--root', str(backups),
                        '--offline', *args]

            # 1) backup: one weekly snapshot with dump + archives + config
            out = subprocess.run(backup_cli('backup'), env=env, capture_output=True, text=True)
            self.assertEqual(0, out.returncode, out.stderr)
            snapshot = Path(out.stdout.strip())
            self.assertEqual(1, len(list((backups / 'weekly').iterdir())))
            for name in ('ponto.dump', 'minio.tar.gz', 'uploads.tar.gz', 'manifest.json'):
                self.assertTrue((snapshot / name).is_file(), name)
            self.assertTrue((snapshot / 'config' / '.env').is_file())
            self.assertIn('PONTO_S3_SECRET=private', (snapshot / 'config' / '.env').read_text())

            # 2) verify: dump lists the real core tables
            out = subprocess.run(['python3', str(SCRIPT), 'verify', '--snapshot', str(snapshot)],
                                 env=env, capture_output=True, text=True)
            self.assertEqual(0, out.returncode, out.stderr)

            # 3) restore dry-run: verifies but writes nothing
            restored_data, restored_objects = root / 'restored-data', root / 'restored-objects'
            out = subprocess.run(backup_cli('restore', '--snapshot', str(snapshot),
                                            '--data', str(restored_data),
                                            '--objects', str(restored_objects)),
                                 env=env, capture_output=True, text=True)
            self.assertEqual(0, out.returncode, out.stderr)
            self.assertFalse(restored_data.exists())
            self.assertFalse(restored_objects.exists())

            # 4) restore --apply: objects, uploads and database come back
            out = subprocess.run(backup_cli('restore', '--snapshot', str(snapshot),
                                            '--data', str(restored_data),
                                            '--objects', str(restored_objects), '--apply'),
                                 env=env, capture_output=True, text=True)
            self.assertEqual(0, out.returncode, out.stderr)
            self.assertEqual(b'attachment bytes', (restored_objects / 'attachment').read_bytes())
            self.assertEqual(b'pending upload', (restored_data / 'uploads' / 'pending').read_bytes())
            body = query('SELECT body FROM note')
            self.assertEqual(NOTE_BODY, body)
            version = query('SELECT version FROM schema_migrations')
            self.assertEqual('6', version)

            # 5) corruption detection: a broken archive aborts the restore
            (snapshot / 'minio.tar.gz').write_bytes(b'corrupted')
            out = subprocess.run(backup_cli('restore', '--snapshot', str(snapshot),
                                            '--data', str(restored_data),
                                            '--objects', str(restored_objects), '--apply'),
                                 env=env, capture_output=True, text=True)
            self.assertNotEqual(0, out.returncode)
            self.assertEqual(b'attachment bytes', (restored_objects / 'attachment').read_bytes())
            self.assertEqual(b'pending upload', (restored_data / 'uploads' / 'pending').read_bytes())


if __name__ == '__main__':
    unittest.main()
