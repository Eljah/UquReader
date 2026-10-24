"""Deployment entry point; reads protected systemd environment without shell evaluation."""
import os
import runpy
import shlex
import sys

for line in open('/etc/uqureader-web.env', encoding='utf-8'):
    line = line.strip()
    if line and not line.startswith('#') and '=' in line:
        key, value = line.split('=', 1)
        parsed = shlex.split(value)
        os.environ[key] = parsed[0] if len(parsed) == 1 else value
os.environ['EDITOR_DATA_DIR'] = '/opt/uqureader/editor-data'
os.environ['EDITOR_PUBLISH_DIR'] = '/opt/uqureader/published'
script = sys.argv.pop(1) if len(sys.argv) > 1 else 'server.py'
runpy.run_path(script, run_name='__main__')
