#!/bin/sh
set -eu
cd /opt/uqureader/editor
exec /opt/uqureader/editor-venv/bin/python admin.py
