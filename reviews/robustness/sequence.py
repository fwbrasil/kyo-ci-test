#!/usr/bin/env python3
"""Verifies that a live-review walk exists as data and reproduces the tip.

    sequence.py --verify <base> <tip> [sequence.json]

`sequence.json` is a list of edits in application order, each `{"file": ..., "old": ..., "new": ...}`
with the exact text an Edit-tool call will replace. For every file the sequence touches, the base
content is taken from `git show <base>:<file>`, the edits are applied in order (each `old` must occur
exactly once at the moment it is applied), and the result is compared byte for byte with
`git show <tip>:<file>`. Files changed in the range but absent from the sequence are reported too, so
an edit the package forgot is visible before the walk starts. No worktree is touched.

Exit status is nonzero on any mismatch, ambiguity, or omission.
"""

import json
import subprocess
import sys


def show(rev: str, path: str) -> str | None:
    r = subprocess.run(["git", "show", f"{rev}:{path}"], capture_output=True, text=True)
    return r.stdout if r.returncode == 0 else None


def changed(base: str, tip: str) -> set[str]:
    r = subprocess.run(["git", "diff", "--name-only", f"{base}..{tip}"], capture_output=True, text=True, check=True)
    return {line for line in r.stdout.splitlines() if line}


def main() -> int:
    if len(sys.argv) < 4 or sys.argv[1] != "--verify":
        print(__doc__)
        return 2
    base, tip = sys.argv[2], sys.argv[3]
    path = sys.argv[4] if len(sys.argv) > 4 else "reviews/robustness/sequence.json"
    with open(path) as f:
        edits = json.load(f)

    status = 0
    contents: dict[str, str] = {}
    order: list[str] = []
    for i, e in enumerate(edits, 1):
        file, old, new = e["file"], e["old"], e["new"]
        if file not in contents:
            current = show(base, file)
            contents[file] = "" if current is None else current
            order.append(file)
        text = contents[file]
        n = text.count(old)
        if n != 1:
            print(f"STALE  edit {i} on {file}: `old` occurs {n} times at the moment it is applied, must be exactly 1")
            status = 1
            continue
        contents[file] = text.replace(old, new, 1)
        print(f"OK     edit {i} on {file} applies")

    for file in order:
        expected = show(tip, file)
        if expected is None:
            print(f"STALE  {file}: not present at the tip")
            status = 1
        elif expected != contents[file]:
            print(f"STALE  {file}: the sequence does not reproduce the tip")
            status = 1
        else:
            print(f"OK     {file}: sequence reproduces the tip")

    for file in sorted(changed(base, tip) - set(order)):
        print(f"STALE  {file}: changed in the range but absent from the sequence")
        status = 1

    print("VERIFIED" if status == 0 else "FAILED")
    return status


if __name__ == "__main__":
    sys.exit(main())
