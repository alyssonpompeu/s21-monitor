#!/usr/bin/env python3
import argparse
import shutil
from pathlib import Path

from huggingface_hub import hf_hub_download


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument('repo')
    parser.add_argument('filename')
    parser.add_argument('--revision', default='main')
    parser.add_argument('--output', required=True)
    args = parser.parse_args()

    cached = hf_hub_download(
        repo_id=args.repo,
        filename=args.filename,
        revision=args.revision,
    )
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copyfile(cached, output)
    print(output)


if __name__ == '__main__':
    main()
