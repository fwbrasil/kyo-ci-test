# Comment-tightening brief (branch diff only)

Worktree (absolute): `/Users/fwbrasil/workspace/kyo/.claude/worktrees/gentle-purring-scroll`. All paths in your batch list are relative to it.

## Goal
Tighten the comments THIS BRANCH added or changed, to a strategic-communication standard. Code and behavior stay byte-identical. This is prose-only editing.

## Scope: only comments the branch introduced
For each file in your list, first run:
```
git diff df82fa46a6f29b5a4d9cdb7f84a34c7d6fa56fb8..HEAD -- <file>
```
Only comment text on ADDED lines (`+`) is in scope. A comment that appears as unchanged context (no `+`) is pre-existing: DO NOT touch it. If a file's diff added no prose comments (only code), leave the file untouched.

## Keep vs cut
A comment earns its place ONLY if it carries one of: an **invariant**, a **rationale** (why a non-obvious choice was made), or a **trap** (a gotcha a maintainer would otherwise hit). Keep those, but make them tight.

Cut (delete the line, or the clause):
- **Dev-log / change narration**: "previously…", "we changed…", "superseded by…", "added because…", "was X, now Y", validation counts ("validated 30/30 loops"), references to who/why it was edited. Code prose is not a changelog.
- **Tautology / restating the code**: a comment that says what the next line literally does.
- **Over-explanation**: the same point made twice, belaboring the obvious, narrating each step of a self-evident sequence.
- **Filler**: marketing adjectives, hedges, throat-clearing.
- **Em-dashes and en-dashes** (`—`, `–`): rewrite with commas, colons, parentheses, or separate sentences. Never substitute a dash.

## Hard constraints (violating any is a failure)
1. NEVER change code, identifiers, string/number literals, imports, or behavior. Only comment text.
2. NEVER touch fenced code blocks inside scaladoc (lines inside ```` ```scala ... ``` ````): they are compiled by doctest. Leave byte-identical.
3. A public type/method keeps its scaladoc (the codebase requires it); tighten a verbose one, but never delete a public declaration's doc entirely.
4. Preserve comment syntax exactly: `//` stays `//`, `/** */` stays `/** */`, `#` stays `#`. Do not convert between styles or merge/split blocks unless removing a purely-noise line.
5. Delete a whole comment only when it is pure noise (a tautology or a dev-log line). If it has any load-bearing fact, tighten instead of delete.
6. If a comment is already tight and strategic, leave it as is.
7. Do not run builds, formatters, or tests. Do not commit. Edit files in place only.

## Calibration example
Before (8 lines, dev-log + tautology + over-explain):
```
# scala-native #4992 (module-init publish race): a concurrent first-touch reader can observe a module's
# instance field before it is written and read null; when that null reaches a typed slot the native cast
# check reports "null cannot be cast to <type>". It is an intermittent upstream race, not a kyo defect,
# so re-run the failed tests (via --quick) exactly like the signal crash below. Narrow and
# self-validating: only a NULL cast matches (a genuine type mismatch reads "<Type> cannot be cast to
# <Other>", never "null", and the JVM never cast-checks null against a reference type, so the string is
# scala-native-only), and a deterministic occurrence still fails after MAX_RETRIES. Checked before the
# FAILED returns because the null surfaces as an ordinary test failure, not a process crash.
```
After (4 lines, invariant + rationale + trap only):
```
# scala-native #4992: a concurrent first-touch reader can read a module's instance field before it is
# written (null), surfacing as "null cannot be cast to <type>". Retry like a signal crash: intermittent
# upstream race, not a kyo defect. Narrow so a real mismatch ("<Type> cannot be cast to <Other>", never
# "null") is never masked; a deterministic hit still fails after MAX_RETRIES.
```

## Report back
When done, report: how many files you edited, and 2-3 representative before/after snippets. Keep your report short.
