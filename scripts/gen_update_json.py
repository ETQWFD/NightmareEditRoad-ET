#!/usr/bin/env python3
"""Generate update.json for the NERT update channel.

AppUpdater.check() reads this manifest first (Pages / raw / jsDelivr / gh-proxy /
Gitee) and only falls back to the GitHub Releases API, so the file must exist at
both the repo root and docs/update.json.
"""
import argparse
import hashlib
import json
import os


def sha256(path: str) -> str:
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--apk", required=True)
    p.add_argument("--apk-url", required=True)
    p.add_argument("--version-code", type=int, required=True)
    p.add_argument("--version-name", required=True)
    p.add_argument("--title", required=True)
    p.add_argument("--notes", required=True)
    p.add_argument("--out", action="append", required=True, help="one or more output paths")
    a = p.parse_args()

    data = {
        "versionCode": a.version_code,
        "versionName": a.version_name,
        "title": a.title,
        "apkUrl": a.apk_url,
        "size": os.path.getsize(a.apk),
        "sha256": sha256(a.apk),
        "releaseNotes": a.notes,
        "url": a.apk_url,
        "force": False,
        "mustUpdate": False,
    }
    text = json.dumps(data, ensure_ascii=False, indent=2) + "\n"
    for out in a.out:
        os.makedirs(os.path.dirname(os.path.abspath(out)), exist_ok=True)
        with open(out, "w", encoding="utf-8") as f:
            f.write(text)
        print("wrote", out)
    print(text[:200])


if __name__ == "__main__":
    main()
