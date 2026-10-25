#!/bin/sh
set -eu
stage=/home/eljah/editorial
target=/opt/uqureader/editor
cd "$stage"
/opt/uqureader/editor-venv/bin/python -m unittest test_analysis_bundle test_analyzers test_validation -v
backup="$target/backups/analysis-bundle-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$backup"
for file in store.py server.py; do
    cp -p "$target/$file" "$backup/$file"
done
for file in analysis_bundle.py test_analysis_bundle.py store.py server.py; do
    install -o root -g root -m 644 "$stage/$file" "$target/$file"
done
echo 'Private MCP updated; public reader process unchanged.'
