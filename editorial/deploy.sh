#!/bin/sh
# Run as root after uploading editorial/ to /home/eljah/editorial.
set -eu
stage=/home/eljah/editorial
install -d -o root -g root -m 755 /opt/uqureader/editor
install -d -o uqureader -g uqureader -m 750 /opt/uqureader/editor-data
install -d -o uqureader -g uqureader -m 755 /opt/uqureader/published
install -o root -g root -m 644 "$stage"/*.py "$stage"/schema.sql "$stage"/requirements.txt /opt/uqureader/editor/
if [ ! -x /opt/uqureader/editor-venv/bin/python ]; then
    python3 -m venv /opt/uqureader/editor-venv
fi
/opt/uqureader/editor-venv/bin/pip install -r /opt/uqureader/editor/requirements.txt >/dev/null
install -o root -g root -m 755 "$stage"/run-editor.sh /usr/local/bin/uqureader-editor
# A dedicated key grants only the MCP protocol, no shell, forwarding or PTY.
home=$(getent passwd uqureader | cut -d: -f6)
install -d -o uqureader -g uqureader -m 700 "$home/.ssh"
key=$(cat "$stage/uqureader-editor-key.pub")
touch "$home/.ssh/authorized_keys"
if ! grep -qF "$key" "$home/.ssh/authorized_keys"; then
    printf 'restrict,command="/usr/local/bin/uqureader-editor" %s\n' "$key" >> "$home/.ssh/authorized_keys"
fi
chown uqureader:uqureader "$home/.ssh/authorized_keys"
chmod 600 "$home/.ssh/authorized_keys"
# The existing service user has no login shell; only this forced-command key is installed.
usermod -s /bin/sh uqureader
if ! grep -q '^EDITOR_PUBLISH_DIR=' /etc/uqureader-web.env; then
    printf '\nEDITOR_PUBLISH_DIR=/opt/uqureader/published\n' >> /etc/uqureader-web.env
fi
chgrp uqureader /etc/uqureader-web.env
chmod 640 /etc/uqureader-web.env
echo 'Editorial files and restricted SSH endpoint installed'
