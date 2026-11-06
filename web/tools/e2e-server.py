import os
from pathlib import Path
import signal
import subprocess
import tempfile

project = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='ponto-browser-') as folder:
    env = {key: value for key, value in os.environ.items() if not key.startswith('PONTO_S3_')}
    process = subprocess.Popen(['java', '--enable-native-access=ALL-UNNAMED', '-jar', str(project / 'target/ponto.jar'),
                                '--serve', '--port=38090', '--db=' + folder + '/ponto.db'], env=env)
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
