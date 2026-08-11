#!/usr/bin/env python3
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open('rb') as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b''):
            h.update(chunk)
    return h.hexdigest()


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument('--root', required=True, help='Directory that contains payload/')
    ap.add_argument('--id', required=True)
    ap.add_argument('--name', required=True)
    ap.add_argument('--version', required=True)
    ap.add_argument('--type', required=True)
    ap.add_argument('--description', default='')
    ap.add_argument('--key', required=True, help='Ed25519 PEM private key')
    ap.add_argument('--output', required=True)
    args = ap.parse_args()

    root = Path(args.root)
    payload = root / 'payload'
    if not payload.is_dir():
        raise SystemExit('payload/ not found')

    files = []
    for path in sorted(p for p in payload.rglob('*') if p.is_file()):
        rel = path.relative_to(root).as_posix()
        files.append({'path': rel, 'sha256': sha256(path), 'size': path.stat().st_size})

    manifest = {
        'schema': 1,
        'id': args.id,
        'name': args.name,
        'version': args.version,
        'type': args.type,
        'description': args.description,
        'files': files,
    }
    manifest_bytes = json.dumps(manifest, ensure_ascii=False, sort_keys=True, separators=(',', ':')).encode('utf-8')

    with tempfile.TemporaryDirectory() as td:
        m = Path(td) / 'manifest.json'
        sig = Path(td) / 'signature.bin'
        m.write_bytes(manifest_bytes)
        subprocess.run([
            'openssl', 'pkeyutl', '-sign', '-rawin',
            '-inkey', args.key, '-in', str(m), '-out', str(sig)
        ], check=True)

        out = Path(args.output)
        out.parent.mkdir(parents=True, exist_ok=True)
        if out.exists():
            out.unlink()
        with zipfile.ZipFile(out, 'w', compression=zipfile.ZIP_STORED, allowZip64=True) as z:
            z.writestr('manifest.json', manifest_bytes)
            z.writestr('signature.bin', sig.read_bytes())
            for meta in files:
                z.write(root / meta['path'], meta['path'])

    print(json.dumps({'output': str(args.output), 'sha256': sha256(Path(args.output)), 'files': len(files)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
