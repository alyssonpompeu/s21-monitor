#!/usr/bin/env python3
"""Build a compact, searchable offline PT-BR lexical memory database.

Inputs are intentionally kept separate from the neural model. The word list is
used as a modern-ish lexical membership source and the 1913 dictionary is kept
as historical reference text. FTS retrieval happens fully on-device.
"""

from __future__ import annotations

import argparse
import hashlib
import re
import sqlite3
import unicodedata
from pathlib import Path

TOKEN_RE = re.compile(r"[^a-z0-9]+")


def sha256(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for block in iter(lambda: f.read(1024 * 1024), b""):
            h.update(block)
    return h.hexdigest()


def normalize(text: str) -> str:
    text = unicodedata.normalize("NFKD", text)
    text = "".join(ch for ch in text if not unicodedata.combining(ch))
    text = text.lower()
    return TOKEN_RE.sub(" ", text).strip()


def build_db(lexicon_path: Path, dictionary_path: Path, output_path: Path) -> None:
    output_path.parent.mkdir(parents=True, exist_ok=True)
    if output_path.exists():
        output_path.unlink()

    db = sqlite3.connect(output_path)
    db.execute("PRAGMA journal_mode=OFF")
    db.execute("PRAGMA synchronous=OFF")
    db.execute("PRAGMA temp_store=MEMORY")
    db.execute("PRAGMA locking_mode=EXCLUSIVE")

    db.executescript(
        """
        CREATE TABLE meta(key TEXT PRIMARY KEY, value TEXT NOT NULL) WITHOUT ROWID;
        CREATE TABLE lexicon(word TEXT PRIMARY KEY) WITHOUT ROWID;
        CREATE TABLE dictionary(line TEXT NOT NULL, normalized TEXT NOT NULL);
        CREATE VIRTUAL TABLE dictionary_fts USING fts4(normalized, content='dictionary');
        """
    )

    lexicon_count = 0
    batch: list[tuple[str]] = []
    with lexicon_path.open("r", encoding="ascii", errors="strict") as source:
        for raw in source:
            word = raw.strip()
            if not word:
                continue
            batch.append((word,))
            lexicon_count += 1
            if len(batch) >= 5000:
                db.executemany("INSERT OR IGNORE INTO lexicon(word) VALUES (?)", batch)
                batch.clear()
    if batch:
        db.executemany("INSERT OR IGNORE INTO lexicon(word) VALUES (?)", batch)

    dictionary_count = 0
    batch2: list[tuple[str, str]] = []
    with dictionary_path.open("r", encoding="utf-8", errors="replace") as source:
        for raw in source:
            line = " ".join(raw.replace("\f", " ").split())
            if len(line) < 3:
                continue
            normalized = normalize(line)
            if len(normalized) < 3:
                continue
            batch2.append((line, normalized))
            dictionary_count += 1
            if len(batch2) >= 3000:
                db.executemany("INSERT INTO dictionary(line, normalized) VALUES (?, ?)", batch2)
                batch2.clear()
    if batch2:
        db.executemany("INSERT INTO dictionary(line, normalized) VALUES (?, ?)", batch2)

    db.execute("INSERT INTO dictionary_fts(dictionary_fts) VALUES ('rebuild')")

    metadata = {
        "schema": "1",
        "lexicon_sha256": sha256(lexicon_path),
        "dictionary_text_sha256": sha256(dictionary_path),
        "lexicon_rows": str(lexicon_count),
        "dictionary_rows": str(dictionary_count),
        "dictionary_role": "historical-reference-1913",
        "language": "pt-BR",
    }
    db.executemany("INSERT INTO meta(key, value) VALUES (?, ?)", metadata.items())
    db.commit()
    db.execute("VACUUM")
    db.close()


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--lexicon", required=True, type=Path)
    parser.add_argument("--dictionary", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    build_db(args.lexicon, args.dictionary, args.output)
