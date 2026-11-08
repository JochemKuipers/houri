# bugsanitizer

One-script gradle console wrapper. **Not part of the app build** — it just
wraps `./gradlew`.

## Usage

```bash
scripts/bugsanitizer/run.sh                            # default sweep
scripts/bugsanitizer/run.sh :app:compileDebugKotlin    # any gradle args
scripts/bugsanitizer/run.sh --quiet :app:assembleDebug # errors only, no progress
```

Default sweep: `spotlessApply spotlessCheck compileDebugUnitTestKotlin app:assembleDebug`.

Flags (consumed by the script, never forwarded to gradle):

- default — `[bsan]` task progress + error lines + final `BUILD SUCCESSFUL/FAILED`
- `--quiet` — error lines only (plus a one-line run header and the final status)
- `--verbose` — everything gradle prints, warnings included

Colors are automatic when stdout+stderr are a terminal: `[bsan]` labels cyan,
task lines and secondary text dim, `e:`/`error:`/`FAILED`/`FAILURE:`/`Caused by`
red, task-failure and `[bsan] FAILED` lines bold red, warnings yellow,
`BUILD SUCCESSFUL` and a clean final status bold green. Set `NO_COLOR` to opt
out; piped output is always plain (no escape codes in files). Task lines carry
a per-task duration emitted by the generated init script. The final status
line is `✓ rc=N — clean run, no errors` or `✗ rc=N — errors.log has N lines`,
followed by the full-log path.

Console lines go through `sed 's/ /  /g'` (git-log look); remove that `sed`
from the script if you want plain lines.

## Files

| Path | Behavior |
|------|----------|
| `errors.log` | error lines only; cleared every run; deleted when the run is clean; auto-added to `.gitignore` once (skipped when a `*.log` pattern already covers it) |
| `<git-dir>/bugsanitizer/full.txt` | full raw stdout+stderr; cleared every run — debugging context the console filter drops. `<git-dir>` is resolved with `git rev-parse --git-path` at run time: `.git` in a plain checkout, `<main>/.git/worktrees/<name>` in a linked worktree (there `.git` is a file, so a hardcoded `.git/…` path breaks) |
| `<git-dir>/bugsanitizer/init.settings.gradle.kts` | generated each run; logs `[bsan] starting/finished/FAILED :task` via a `TaskExecutionListener` inside an init script injected with `-I` |

## Notes

- The script always `cd`s to the repo root first (`git rev-parse --show-toplevel`), so it works from any subdirectory and `errors.log` always lands at the root.
- The script exits with gradle's own exit code.
- Error matching (`errors.log` + console): `e: …`, `error: …`, `ERROR`, `FAILED`,
  `FAILURE:`, `Caused by`, `Execution failed`, `BUILD FAILED`. Warnings (`w: …`)
  never match. Console regexes are spacing-tolerant (`Caused +by`) because the
  console stream is spaced.
- `.git/` files never appear in `git status`.
- Invoke it directly or via bash; don't run it through `sh` (needs `PIPESTATUS`).
