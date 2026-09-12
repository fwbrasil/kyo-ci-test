#!/usr/bin/env python3
"""Turns a diff into the edit list a live review applies.

    sequence_from_diff.py <base> <tip> [--context N] [--out sequence.json] -- <file>...

For each file, in the order given, the hunks of `git diff -U<N> <base>..<tip> -- <file>` become edits
`{"file": ..., "old": ..., "new": ...}` in top-to-bottom order: `old` is the hunk's context and removed
lines, `new` its context and added lines, both verbatim, so an Edit-tool call can replace one with the
other. A file absent at the base becomes one edit with an empty `old` and the tip's content as `new`.

The file order given is the walk order. `sequence.py --verify` checks the result reproduces the tip and
that every `old` is unique at the moment it is applied; if a hunk is not unique, rerun with a larger
`--context` for that file.
"""

import json
import subprocess
import sys


def git(*args: str) -> str:
    return subprocess.run(["git", *args], capture_output=True, text=True, check=True).stdout


def exists(rev: str, path: str) -> bool:
    return subprocess.run(["git", "cat-file", "-e", f"{rev}:{path}"], capture_output=True).returncode == 0


def hunks(base: str, tip: str, path: str, context: int) -> list[tuple[str, str]]:
    diff = git("diff", f"-U{context}", f"{base}..{tip}", "--", path)
    out: list[tuple[str, str]] = []
    old: list[str] = []
    new: list[str] = []
    in_hunk = False

    def flush() -> None:
        if in_hunk:
            out.append(("".join(old), "".join(new)))

    for line in diff.splitlines(keepends=True):
        if line.startswith("@@"):
            flush()
            old, new, in_hunk = [], [], True
            continue
        if not in_hunk:
            continue
        if line.startswith("\\ No newline"):
            continue
        tag, body = line[0], line[1:]
        if tag == " ":
            old.append(body)
            new.append(body)
        elif tag == "-":
            old.append(body)
        elif tag == "+":
            new.append(body)
    flush()
    return out


def main() -> int:
    args = sys.argv[1:]
    if "--" not in args or len(args) < 3:
        print(__doc__)
        return 2
    sep = args.index("--")
    head, files = args[:sep], args[sep + 1 :]
    base, tip = head[0], head[1]
    context = 3
    out_path = "reviews/robustness/sequence.json"
    i = 2
    while i < len(head):
        if head[i] == "--context":
            context = int(head[i + 1])
            i += 2
        elif head[i] == "--out":
            out_path = head[i + 1]
            i += 2
        else:
            print(f"unknown option {head[i]}")
            return 2

    edits = []
    for path in files:
        if not exists(base, path):
            edits.append({"file": path, "old": "", "new": git("show", f"{tip}:{path}")})
            continue
        for old, new in hunks(base, tip, path, context):
            edits.append({"file": path, "old": old, "new": new})

    with open(out_path, "w") as f:
        json.dump(edits, f, indent=2)
    print(f"{len(edits)} edits over {len(files)} files written to {out_path}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
