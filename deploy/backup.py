#!/usr/bin/env python3
"""Consistent SQLite + object-store snapshots and verified, offline restoration."""
import argparse
import datetime as dt
import fcntl
import gzip
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import subprocess
import tarfile
import tempfile

os.umask(0o077)


def command(args, project, **kwargs):
    return subprocess.run(args, cwd=project, check=True, **kwargs)


def settings(project):
    values = {}
    env = project / '.env'
    if env.exists():
        for line in env.read_text().splitlines():
            key, sep, value = line.partition('=')
            if sep and not key.startswith('#'):
                values[key.strip()] = value.strip()
    return values


def verify(snapshot):
    manifest = json.loads((snapshot / 'manifest.json').read_text())
    for name, expected in manifest['sha256'].items():
        path = snapshot / name
        if path.resolve().parent != snapshot.resolve() and not path.resolve().is_relative_to(snapshot.resolve()):
            raise RuntimeError('Invalid manifest path')
        if digest(path) != expected:
            raise RuntimeError('Snapshot checksum mismatch: ' + name)
    with sqlite3.connect(snapshot / 'ponto.db') as database:
        if database.execute('PRAGMA integrity_check').fetchone()[0] != 'ok':
            raise RuntimeError('Snapshot database failed integrity_check')
    for name in ('minio.tar.gz', 'uploads.tar.gz'):
        with tarfile.open(snapshot / name) as archive:
            for member in archive:
                if not (member.isfile() or member.isdir()) or member.name.startswith('/') or '..' in Path(member.name).parts:
                    raise RuntimeError('Unsafe archive entry: ' + member.name)
    return manifest


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def archive_dir(source, target):
    with tarfile.open(target, 'w:gz') as archive:
        if source.exists():
            for path in sorted(source.iterdir()):
                archive.add(path, arcname=path.name)


def running(project):
    result = command(['docker', 'compose', 'ps', '--services', '--status', 'running'], project,
                     capture_output=True, text=True)
    return [service for service in result.stdout.split() if service in ('ponto', 'minio')]


def resume(project, services):
    if services:
        command(['docker', 'compose', 'up', '-d', *services], project, stdout=subprocess.DEVNULL)


def retain(folder, count):
    snapshots = sorted(path for path in folder.iterdir() if path.is_dir() and not path.name.startswith('.'))
    for old in snapshots[:-count]:
        shutil.rmtree(old)


def backup(args, project, data, objects, root):
    root.mkdir(parents=True, exist_ok=True)
    kind = 'deploy' if args.deployment else 'daily'
    group = root / kind
    group.mkdir(exist_ok=True)
    now = dt.datetime.now().astimezone()
    target = group / now.strftime('%Y-%m-%d_%H%M%S_%f')
    staging = Path(tempfile.mkdtemp(prefix='.snapshot-', dir=group))
    services = [] if args.offline else running(project)
    complete = False
    try:
        if args.deployment and not args.offline:
            with (staging / 'image.tar.gz').open('wb') as output:
                save = subprocess.Popen(['docker', 'image', 'save', 'ponto:web'], stdout=subprocess.PIPE)
                try:
                    with gzip.GzipFile(fileobj=output, mode='wb', compresslevel=1) as packed:
                        shutil.copyfileobj(save.stdout, packed)
                finally:
                    save.stdout.close()
                if save.wait() != 0:
                    raise RuntimeError('Cannot preserve previous application image')
        if services:
            command(['docker', 'compose', 'stop', '-t', '45', *services], project, stdout=subprocess.DEVNULL)
        if not (data / 'ponto.db').is_file():
            raise RuntimeError('Database absent; refusing to create an empty backup')
        with sqlite3.connect(f'file:{data / "ponto.db"}?mode=ro', uri=True) as source:
            with sqlite3.connect(staging / 'ponto.db') as destination:
                source.backup(destination)
            names = {row[0] for row in source.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            if 'note_file' in names and source.execute('SELECT count(*) FROM note_file').fetchone()[0] and not objects.is_dir():
                raise RuntimeError('Attachments exist but the object volume is absent')
        archive_dir(objects, staging / 'minio.tar.gz')
        archive_dir(data / 'uploads', staging / 'uploads.tar.gz')
        for name in ('.env', 'docker-compose.yml', 'Dockerfile', '.dockerignore', 'target/ponto.jar'):
            original = project / name
            if original.is_file():
                destination = staging / 'config' / name
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(original, destination)
        files = sorted(path for path in staging.rglob('*') if path.is_file())
        manifest = {'created': now.isoformat(), 'kind': kind,
                    'sha256': {str(path.relative_to(staging)): digest(path) for path in files}}
        (staging / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
        verify(staging)
        staging.rename(target)
        complete = True
        if kind == 'daily':
            # One completed snapshot per day; weekly snapshots share immutable files by hard link.
            for old in group.glob(now.strftime('%Y-%m-%d') + '_*'):
                if old != target:
                    shutil.rmtree(old)
            weekly = root / 'weekly'
            weekly.mkdir(exist_ok=True)
            week = weekly / now.strftime('%G-W%V')
            if not week.exists():
                shutil.copytree(target, week, copy_function=os.link)
            retain(weekly, 4)
            retain(group, 7)
        else:
            retain(group, 3)
        print(target)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
        if not args.keep_stopped or not complete:
            resume(project, services)


def extract(snapshot, name, destination):
    destination.mkdir(parents=True, exist_ok=True)
    with tarfile.open(snapshot / name) as archive:
        # Python 3.11.2 on the LAN host predates tarfile extraction filters.
        # verify() already rejects links, devices, absolute paths and traversal.
        for member in archive:
            target = destination / member.name
            if member.isdir():
                target.mkdir(parents=True, exist_ok=True)
            else:
                target.parent.mkdir(parents=True, exist_ok=True)
                with archive.extractfile(member) as source, target.open('wb') as output:
                    shutil.copyfileobj(source, output)


def restore(args, project, data, objects, root):
    snapshot = Path(args.snapshot).resolve()
    verify(snapshot)
    if not args.apply:
        print('Verified. To restore, stop other writers and repeat with --apply. Target: ' + str(data))
        return
    data.parent.mkdir(parents=True, exist_ok=True)
    objects.parent.mkdir(parents=True, exist_ok=True)
    stage_data = Path(tempfile.mkdtemp(prefix='.ponto-restore-', dir=data.parent))
    stage_objects = Path(tempfile.mkdtemp(prefix='.minio-restore-', dir=objects.parent))
    moved = []
    services = [] if args.offline else running(project)
    stamp = dt.datetime.now().strftime('%Y%m%d%H%M%S%f')
    try:
        shutil.copy2(snapshot / 'ponto.db', stage_data / 'ponto.db')
        extract(snapshot, 'uploads.tar.gz', stage_data / 'uploads')
        extract(snapshot, 'minio.tar.gz', stage_objects)
        if services:
            command(['docker', 'compose', 'stop', '-t', '45', *services], project, stdout=subprocess.DEVNULL)
        for destination, stage in ((data, stage_data), (objects, stage_objects)):
            old = destination.with_name(destination.name + '.before-restore-' + stamp)
            if destination.exists():
                destination.rename(old)
                moved.append((destination, old))
            stage.rename(destination)
        if args.restore_config:
            for original in (snapshot / 'config').rglob('*'):
                if original.is_file():
                    destination = project / original.relative_to(snapshot / 'config')
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(original, destination)
            if (snapshot / 'image.tar.gz').is_file():
                command(['docker', 'image', 'load', '-i', str(snapshot / 'image.tar.gz')], project, stdout=subprocess.DEVNULL)
        if not args.offline:
            command(['docker', 'compose', 'up', '-d', '--force-recreate', 'minio', 'ponto'], project, stdout=subprocess.DEVNULL)
        print('Restored ' + str(snapshot) + '; previous directories retained with .before-restore-' + stamp)
    except Exception:
        for destination, old in reversed(moved):
            if destination.exists():
                shutil.rmtree(destination)
            old.rename(destination)
        resume(project, services)
        raise
    finally:
        for stage in (stage_data, stage_objects):
            if stage.exists():
                shutil.rmtree(stage)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('backup', 'verify', 'restore'))
    parser.add_argument('--project', type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument('--data', type=Path)
    parser.add_argument('--objects', type=Path)
    parser.add_argument('--root', type=Path)
    parser.add_argument('--snapshot')
    parser.add_argument('--offline', action='store_true', help='For isolated tests or when all writers are already stopped')
    parser.add_argument('--deployment', action='store_true')
    parser.add_argument('--keep-stopped', action='store_true')
    parser.add_argument('--apply', action='store_true')
    parser.add_argument('--restore-config', action='store_true')
    args = parser.parse_args()
    project = args.project.resolve()
    env = settings(project)
    data = (args.data or Path(env.get('PONTO_DATA_DIR', str(Path.home() / '.local/share/ponto')))).resolve()
    objects = (args.objects or Path(env.get('PONTO_OBJECT_DIR', str(Path.home() / '.local/share/ponto-minio')))).resolve()
    root = (args.root or Path(env.get('PONTO_BACKUP_DIR', str(Path.home() / '.local/share/ponto-backups')))).resolve()
    if args.action == 'verify':
        verify(Path(args.snapshot).resolve())
        print('Snapshot verified')
        return
    root.mkdir(parents=True, exist_ok=True)
    with (root / '.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        if args.action == 'backup':
            backup(args, project, data, objects, root)
        else:
            if not args.snapshot:
                parser.error('--snapshot is required for restore')
            restore(args, project, data, objects, root)

if __name__ == '__main__':
    main()
