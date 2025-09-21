#!/usr/bin/env python3
"""Synchronize AndroidManifest version attributes."""

from __future__ import annotations

import pathlib
import re
import sys


def replace_attr(content: str, attr: str, value: str, manifest: pathlib.Path) -> str:
    pattern = re.compile(rf'(android:{attr}\s*=\s*")([^"]*)(")')
    if not pattern.search(content):
        raise ValueError(f"android:{attr} attribute not found in {manifest}")
    return pattern.sub(lambda match: f"{match.group(1)}{value}{match.group(3)}", content)


def main() -> int:
    if len(sys.argv) != 4:
        print("usage: update_manifest_version.py <manifest> <version_code> <version_name>", file=sys.stderr)
        return 2

    manifest_path = pathlib.Path(sys.argv[1])
    version_code = sys.argv[2]
    version_name = sys.argv[3]

    if not manifest_path.is_file():
        print(f"error: manifest not found at {manifest_path}", file=sys.stderr)
        return 1

    text = manifest_path.read_text(encoding="utf-8")

    try:
        text = replace_attr(text, "versionCode", version_code, manifest_path)
        text = replace_attr(text, "versionName", version_name, manifest_path)
    except ValueError as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1

    manifest_path.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
