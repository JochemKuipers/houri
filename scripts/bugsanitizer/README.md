# bugsanitizer

One-script gradle console wrapper. **Not part of the app build** — it just
wraps `./gradlew`. User-run only (agents never run gradle in this repo).

## Usage

```bash
scripts/bugsanitizer/run.sh                            # default sweep
scripts/bugsanitizer/run.sh :app:compileDebugKotlin    # any gradle args
scripts/bugsanitizer/run.sh --quiet :app:assembleDebug # errors only, no progress
scripts/bugsanitizer/run.sh --stall 15 --timeout 60    # tuned hang protection
scripts/bugsanitizer/run.sh --fresh                    # stop daemons, then sweep
scripts/bugsanitizer/run.sh --stop                     # only ./gradlew --stop
scripts/bugsanitizer/selftest.sh                       # stub-gradle test suite
```

Default sweep: `spotlessApply spotlessCheck compileDebugUnitTestKotlin app:assembleDebug`.

Flags (consumed by the script, never forwarded to gradle):

| Flag | Effect |
|------|--------|
| *(default)* | `[bsan]` task progress + error lines + final `BUILD SUCCESSFUL/FAILED` |
| `--quiet` | error lines only (plus a one-line run header and the final status) |
| `--verbose` | everything gradle prints, warnings included |
| `--stall <min>` | hang watchdog: kill the gradle client after `<min>` with no log output. Default **10**, `0` disables, decimals OK (`--stall 0.5` = 30s). While silent 3+ min it prints one `[bsan] still silent…` note per minute |
| `--timeout <min>` | hard wall-clock cap on the whole run. Default **off** (`0`) |
| `--stop` | only run `./gradlew --stop`, exit with its code (mirrors gradle's own flag; gradle's `--help` is reachable as `run.sh help`) |
| `--fresh` | `./gradlew --stop` first — which also covers kotlin compile daemons via `pkill -f KotlinCompileDaemon` — then the sweep. The recovery move after any hang/OOM |
| `-h, --help` | script usage |

## Hang & OOM handling

- **Mechanics:** gradle runs as a background job writing the raw log straight
  into `full.txt`; the console replays that file through the filter (`tail
  -F`). `gradlew` `exec`s java, so the background PID **is** the client JVM and
  the build can be killed cleanly at any moment (SIGTERM, SIGKILL 15s later).
- **Stall watchdog:** no new bytes in the log for `--stall` minutes → the
  client is killed, the last 12 log lines are printed, and the run exits with
  **124**. Known trigger in this repo: the `PackageAndroidArtifact` hang at 99%
  (42 minutes once; see the comment in `gradle.properties`).
- **Daemons survive the kill** — the client only disconnects, and a deadlocked
  task can keep the daemon wedged. Every kill message therefore says so and
  points at `./gradlew --stop` / rerun with `--fresh`.
- **Ctrl-C / external SIGTERM:** clean teardown (watchdog, console reader,
  client), status line, exit **130**.
- **Diagnosis:** after every run the full log is scanned for OOM/daemon
  signatures — `OutOfMemoryError`, `Java heap space`, `Metaspace`, `GC
  overhead limit`, kotlin daemon death, `Daemon could not be launched`,
  `Unable to start the daemon`, `build daemon disappeared`, OOM-killer /
  `SIGKILL` / rc 137. Matches print as a diagnosis with a fix ladder that
  quotes the **current** `gradle.properties` values (`org.gradle.jvmargs`,
  `kotlin.daemon.jvmargs`, `org.gradle.workers.max` — read live each run), and
  the same signatures land in `errors.log`. An abrupt **rc=137** (client
  SIGKILLed, no log signature) is called out as almost certainly the Linux
  OOM killer.
- **Single sweep per tree:** a non-blocking `flock` refuses a second
  concurrent build in the same worktree (exit 2). `--stop` stays lock-free on
  purpose — it is the tool for unwedging a hung run and must never queue
  behind one.

## Console

Colors are automatic when stdout+stderr are a terminal: `[bsan]` labels cyan,
task lines and secondary text dim, `e:`/`error:`/`FAILED`/`FAILURE:`/`Caused
by` red, task-failure and `[bsan] FAILED` lines bold red, warnings yellow,
`BUILD SUCCESSFUL` and a clean final status bold green. Set `NO_COLOR` to opt
out; piped output is always plain (no escape codes in files). Task lines carry
a per-task duration emitted by the generated init script.

After the run the console also shows, before the final status:

- a **diagnosis block** when OOM/daemon signatures matched (see above),
- the **slowest tasks this run** (top 8 by wall clock, parsed from the init
  script's `[bsan] finished/FAILED` lines) — where a hang shows up first.

The final status line is `✓ rc=0 — clean run, no errors (Nm Ns)` or
`✗ rc=N — errors.log has N lines (Nm Ns)` / `✗ rc=N — failed; no error lines
captured`, followed by the full-log path. A `✓` is only printed when gradle
actually succeeded (rc 0).

Console lines go through `sed 's/ /  /g'` (git-log look); remove that `sed`
from the script if you want plain lines.

## Files

| Path | Behavior |
|------|----------|
| `errors.log` | error lines only (incl. OOM/daemon signatures); cleared every run; deleted when the run is clean; auto-added to `.gitignore` once (skipped when a `*.log` pattern already covers it) |
| `<git-dir>/bugsanitizer/full.txt` | full raw stdout+stderr of gradle, uncolored; cleared every run — debugging context the console filter drops. `<git-dir>` is resolved with `git rev-parse --git-path` at run time: `.git` in a plain checkout, `<main>/.git/worktrees/<name>` in a linked worktree (there `.git` is a file, so a hardcoded `.git/…` path breaks) |
| `<git-dir>/bugsanitizer/last-run.txt` | plain record of each run: start banner, watchdog config, final status or interrupt/kill note |
| `<git-dir>/bugsanitizer/init.settings.gradle.kts` | generated each run; logs `[bsan] starting/finished/FAILED :task` via a `TaskExecutionListener` inside an init script injected with `-I` |
| `<git-dir>/bugsanitizer/kill-reason` | written only when the watchdog kills the build (`stall: …` / `timeout: …`) |

## Exit codes

| Code | Meaning |
|------|---------|
| gradle's own | the sweep's result |
| `124` | the stall/timeout watchdog killed the build |
| `130` | interrupted (Ctrl-C / SIGTERM) |
| `2` | usage error, not in a git tree, or another sweep holds the lock |

## Notes

- The script always `cd`s to the repo root first (`git rev-parse --show-toplevel`), so it works from any subdirectory and `errors.log` always lands at the root.
- Error matching (`errors.log` + console): `e: …`, `error: …`, `ERROR`, `FAILED`,
  `FAILURE:`, `Caused by`, `Execution failed`, `BUILD FAILED`, plus the
  OOM/daemon signatures listed above. Warnings (`w: …`) never match. Console
  regexes are spacing-tolerant (`Caused +by`) because the console stream is spaced.
- `.git/` files never appear in `git status`.
- Invoke it directly or via bash; don't run it through `sh` (needs arrays,
  `8>&-` fd juggling and bash traps).
- `scripts/bugsanitizer/selftest.sh` verifies all of the above offline against
  a stub `./gradlew` in a throwaway git repo (watchdog, interrupt cleanup,
  lock, diagnosis, colors, linked-worktree paths). No real gradle involved.
