"""Playwright server: one throwaway PostgreSQL schema, then the fat jar.

Uses `$PONTO_TEST_PG` when set (CI service container). Otherwise talks to the
disposable `ponto-test-pg` container on 127.0.0.1:55432, starting it if needed.
"""
import os
import re
import signal
import subprocess
import tempfile
import time
from pathlib import Path

project = Path(__file__).resolve().parents[2]
CONTAINER = os.environ.get('PONTO_PG_CONTAINER', 'ponto-test-pg')
DEFAULT = 'jdbc:postgresql://127.0.0.1:55432/ponto_test?user=ponto&password=ponto'


def jdbc_base():
    raw = os.environ.get('PONTO_TEST_PG', '').strip()
    if not raw:
        return DEFAULT
    return raw if raw.startswith('jdbc:') else 'jdbc:' + raw


def parts(url):
    m = re.match(r'jdbc:postgresql://([^/:]+)(?::(\d+))?/([^?]+)(?:\?(.*))?$', url)
    if not m:
        raise SystemExit('PONTO_TEST_PG is not a postgresql JDBC URL: ' + url)
    host, port, db, qs = m.group(1), m.group(2) or '5432', m.group(3), m.group(4) or ''
    params = dict(pair.split('=', 1) for pair in qs.split('&') if '=' in pair)
    return host, port, db, params.get('user', 'ponto'), params.get('password', 'ponto')


def psql(host, port, db, user, password, sql):
    env = os.environ.copy()
    env['PGPASSWORD'] = password
    if shutil_which('psql'):
        return subprocess.run(
            ['psql', '-h', host, '-p', port, '-U', user, '-d', db, '-v', 'ON_ERROR_STOP=1', '-c', sql],
            env=env, capture_output=True, text=True)
    return subprocess.run(
        ['docker', 'exec', CONTAINER, 'psql', '-U', user, '-d', db,
         '-v', 'ON_ERROR_STOP=1', '-c', sql],
        capture_output=True, text=True)


def shutil_which(name):
    from shutil import which
    return which(name)


def ensure(host, port, db, user, password):
    probe = psql(host, port, db, user, password, 'SELECT 1')
    if probe.returncode == 0:
        return
    if os.environ.get('PONTO_TEST_PG'):
        raise SystemExit('PostgreSQL de teste indisponível: ' + (probe.stderr or probe.stdout))
    subprocess.run(['docker', 'rm', '-f', CONTAINER], capture_output=True)
    subprocess.run([
        'docker', 'run', '-d', '--name', CONTAINER,
        '-p', '127.0.0.1:55432:5432',
        '--tmpfs', '/var/lib/postgresql/data',
        '-e', 'POSTGRES_USER=' + user, '-e', 'POSTGRES_PASSWORD=' + password,
        '-e', 'POSTGRES_DB=' + db, '-e', 'TZ=America/Sao_Paulo',
        'postgres:16-alpine'], check=True, capture_output=True)
    last = ''
    for _ in range(60):
        probe = psql(host, port, db, user, password, 'SELECT 1')
        if probe.returncode == 0:
            return
        last = probe.stderr or probe.stdout
        time.sleep(0.5)
    raise SystemExit('ponto-test-pg não ficou pronto: ' + last)


def fresh_url():
    base = jdbc_base()
    host, port, db, user, password = parts(base)
    ensure(host, port, db, user, password)
    schema = 'e2e_' + str(os.getpid())
    made = psql(host, port, db, user, password,
                'DROP SCHEMA IF EXISTS ' + schema + ' CASCADE; CREATE SCHEMA ' + schema)
    if made.returncode != 0:
        raise SystemExit('schema e2e: ' + (made.stderr or made.stdout))
    sep = '&' if '?' in base else '?'
    return base + sep + 'currentSchema=' + schema


with tempfile.TemporaryDirectory(prefix='ponto-browser-') as folder:
    env = {key: value for key, value in os.environ.items() if not key.startswith('PONTO_S3_')}
    env['PONTO_WEB_USER'] = 'e2e'
    env['PONTO_WEB_PASSWORD'] = 'e2e-secret'
    env['PONTO_DATA_DIR'] = folder
    env['PONTO_DB'] = fresh_url()
    process = subprocess.Popen(
        ['java', '--enable-native-access=ALL-UNNAMED', '-jar', str(project / 'target/ponto.jar'),
         '--serve', '--port=38090'], env=env)

    def stop(*_):
        process.terminate()

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        process.wait()
    finally:
        if process.poll() is None:
            process.terminate()
        process.wait(timeout=15)
