#!/bin/sh
set -eu
jar=/opt/uqureader/app/web-app-1.1.0.jar
backup="$jar.before-editorial-$(date +%Y%m%d-%H%M%S)"
cp -p "$jar" "$backup"
install -o uqureader -g uqureader -m 644 /home/eljah/editorial/web-app-1.1.0.jar "$jar"
systemctl restart uqureader-web
for attempt in $(seq 1 30); do
    if curl -fsS http://127.0.0.1:8091/api/works >/dev/null; then
        echo 'Reader ready; previous JAR backed up'
        exit 0
    fi
    sleep 1
done
cp -p "$backup" "$jar"
systemctl restart uqureader-web
echo 'Reader readiness failed; restored previous JAR' >&2
exit 1
