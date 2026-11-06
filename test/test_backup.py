import json
from pathlib import Path
import sqlite3
import subprocess
import tempfile
import unittest

SCRIPT = Path(__file__).resolve().parent.parent / 'deploy/backup.py'


class BackupTest(unittest.TestCase):
    def test_snapshot_restore_and_corruption_detection(self):
        with tempfile.TemporaryDirectory() as raw:
            root = Path(raw)
            project, data, objects, backups = [root / name for name in ('project', 'data', 'objects', 'backups')]
            for folder in (project, data, objects):
                folder.mkdir()
            (objects / 'attachment').write_bytes(b'attachment bytes')
            (data / 'uploads').mkdir()
            (data / 'uploads/pending').write_bytes(b'pending upload')
            (project / '.env').write_text('PONTO_S3_SECRET=private\n')
            connection = sqlite3.connect(data / 'ponto.db')
            connection.execute('PRAGMA journal_mode=WAL')
            connection.execute('CREATE TABLE notes(body TEXT)')
            connection.execute("INSERT INTO notes VALUES('committed WAL')")
            connection.commit()
            args = ['python3', str(SCRIPT), 'backup', '--project', str(project), '--data', str(data),
                    '--objects', str(objects), '--root', str(backups), '--offline']
            result = subprocess.run(args, check=True, capture_output=True, text=True)
            snapshot = Path(result.stdout.strip())
            self.assertEqual(1, len(list((backups / 'weekly').iterdir())))
            with sqlite3.connect(snapshot / 'ponto.db') as db:
                self.assertEqual('committed WAL', db.execute('SELECT body FROM notes').fetchone()[0])
            restored_data, restored_objects = root / 'restored-data', root / 'restored-objects'
            restore = ['python3', str(SCRIPT), 'restore', '--project', str(project), '--data', str(restored_data),
                       '--objects', str(restored_objects), '--root', str(backups), '--snapshot', str(snapshot), '--offline']
            subprocess.run(restore, check=True, capture_output=True)
            self.assertFalse(restored_data.exists())
            subprocess.run(restore + ['--apply'], check=True, capture_output=True)
            self.assertEqual(b'attachment bytes', (restored_objects / 'attachment').read_bytes())
            self.assertEqual(b'pending upload', (restored_data / 'uploads/pending').read_bytes())
            with sqlite3.connect(restored_data / 'ponto.db') as db:
                self.assertEqual('ok', db.execute('PRAGMA integrity_check').fetchone()[0])
            (snapshot / 'minio.tar.gz').write_bytes(b'corrupted')
            failed = subprocess.run(restore + ['--apply'], capture_output=True)
            self.assertNotEqual(0, failed.returncode)
            self.assertEqual(b'attachment bytes', (restored_objects / 'attachment').read_bytes())
            connection.close()

if __name__ == '__main__':
    unittest.main()
