#!/usr/bin/env bash
# bugsanitizer — a friendlier gradle console for this repo.
#
# Usage:
#   scripts/bugsanitizer/run.sh                          # default sweep (spotlessApply
#                                                        #  spotlessCheck compileDebugUnitTestKotlin
#                                                        #  app:assembleDebug)
#   scripts/bugsanitizer/run.sh <gradle args>            # any task set / flags (--stacktrace etc.)
#   scripts/bugsanitizer/run.sh --fresh <gradle args>    # stop daemons first, then the sweep
#   scripts/bugsanitizer/run.sh --stop                   # ONLY ./gradlew --stop (no build)
#
# Flags consumed here (never forwarded to gradle):
#   --verbose           show everything gradle prints
#   --quiet             show ONLY error lines (no banner, no task progress)
#   --stall <minutes>   hang watchdog: kill the gradle client when the log has
#                       not grown for <minutes> (default 10, 0 = off; decimals OK)
#   --timeout <minutes> hard wall-clock cap on the whole run (default 0 = off)
#   --stop              run ./gradlew --stop and exit (mirrors gradle's own flag)
#   --fresh             ./gradlew --stop first (kills kotlin compile daemons
#                       too), then the sweep
#   -h, --help          this help (gradle's own --help: run.sh help)
#
# Console: [bsan] task progress + error lines + final build result.
# - --verbose: everything gradle prints; --quiet: strictly errors + final status.
# - When stdout+stderr are a terminal (and NO_COLOR is unset, TERM != dumb),
#   lines are colorized and class-colored; through a pipe every line is plain,
#   so redirecting/teeing never embeds escape codes in files.
#
# Hang & OOM handling:
# - gradle runs as a background job writing the raw log straight into full.txt;
#   the console replays that file through the filter (tail -F). The client PID
#   is therefore known exactly (gradlew execs java, so $! IS the client JVM)
#   and the build can be killed cleanly at any moment.
# - stall watchdog: no new bytes in full.txt for <stall> minutes -> SIGTERM the
#   client (SIGKILL 15s later), print the tail of the log, exit 124. While the
#   log is silent for 3+ minutes it prints one gentle "[bsan] still silent…"
#   note per minute, so a genuinely hung build is visible instead of a frozen
#   console. Known trigger in this repo: the PackageAndroidArtifact hang at 99%
#   (42m, see gradle.properties).
# - killing the client only disconnects it; the daemon keeps the build (and may
#   stay wedged on a deadlocked task). Every kill therefore prints the recovery
#   step: ./gradlew --stop, or rerun with --fresh.
# - after every run the full log is scanned for OOM/daemon signatures
#   (OutOfMemoryError, Java heap space, kotlin daemon died, daemon could not be
#   launched, OOM-killer/SIGKILL, rc=137). Matches print as a diagnosis with a
#   fix ladder that quotes the CURRENT gradle.properties heap/worker settings
#   (read live, so edits stay accurate); the signatures also land in errors.log.
# - Ctrl-C / SIGTERM: clean teardown (watchdog, console, client) and exit 130.
#
# Files:
#   errors.log                                  error lines only; cleared every run;
#                                               deleted when the run is clean; auto-added
#                                               to .gitignore once
#   <git-dir>/bugsanitizer/full.txt             full RAW (uncolored) stdout+stderr of gradle;
#                                               cleared every run (<git-dir>/.git in a plain
#                                               checkout; the worktree's admin dir under the
#                                               main checkout's .git/worktrees/<name> in a
#                                               linked worktree — never in git status)
#   <git-dir>/bugsanitizer/last-run.txt         start banner + final status, plain record
#   <git-dir>/bugsanitizer/init.settings.gradle.kts  generated each run, injected via -I;
#                                                   task listener prints [bsan] start/finish
#                                                   lines WITH per-task duration
#
# Exit code is gradle's own; 124 when the stall/timeout watchdog killed the
# build; 130 when interrupted. Always runs at the repo root (cds there first).
# One sweep per tree: a non-blocking lock refuses concurrent builds in the same
# worktree (--stop stays lock-free — it is the unwedge tool).

# Always resolve and cd to the repo root, and derive the per-tree git-internal
# store via rev-parse: in a plain checkout GIT_DIR is `.git`; in a linked
# worktree (`.git` is a FILE) GIT_DIR is `<main>/.git/worktrees/<name>`. Both
# are invisible to `git status`. Hardcoding `.git/bugsanitizer` breaks linked
# worktrees (mkdir on a file) — that is why nothing is hardcoded.
root=$(git rev-parse --show-toplevel 2>/dev/null) || {
    echo "[bsan] run this inside the repo (needs a git work tree)" >&2
    exit 2
}
cd "$root" || exit 2
hidden=$(git rev-parse --git-path bugsanitizer 2>/dev/null) || {
    echo "[bsan] cannot resolve the per-tree git dir (rev-parse --git-path)" >&2
    exit 2
}
mkdir -p "$hidden" || exit 2

default_sweep=(spotlessApply spotlessCheck compileDebugUnitTestKotlin app:assembleDebug)
log=errors.log
full=$hidden/full.txt
init=$hidden/init.settings.gradle.kts
lastrun=$hidden/last-run.txt
reason_file=$hidden/kill-reason

usage() {
    cat >&2 <<'BSAN_USAGE'
[bsan] usage: scripts/bugsanitizer/run.sh [bsan-flags] [gradle args...]

  bsan flags (never forwarded to gradle):
    --verbose           everything gradle prints
    --quiet             errors only (+ header, final status)
    --stall <min>       kill the build after <min> of no output   (default 10, 0=off)
    --timeout <min>     hard wall-clock cap on the whole run      (default 0=off)
    --stop              only run ./gradlew --stop, no build
    --fresh             ./gradlew --stop first, then the sweep
    -h, --help          this help (gradle's own --help: run.sh help)

  default sweep: spotlessApply spotlessCheck compileDebugUnitTestKotlin app:assembleDebug
BSAN_USAGE
}

verbose=0 quiet=0 stop_only=0 fresh=0
stall_min=10 timeout_min=0
gradle_args=()
while [ $# -gt 0 ]; do
    case "$1" in
        --verbose)   verbose=1; shift ;;
        --quiet)     quiet=1; shift ;;
        --stop)      stop_only=1; shift ;;
        --fresh)     fresh=1; shift ;;
        -h|--help)   usage; exit 0 ;;
        --stall)
            [ $# -ge 2 ] || { echo "[bsan] --stall needs a value (minutes, 0=off)" >&2; exit 2; }
            stall_min=$2; shift 2 ;;
        --stall=*)   stall_min=${1#*=}; shift ;;
        --timeout)
            [ $# -ge 2 ] || { echo "[bsan] --timeout needs a value (minutes, 0=off)" >&2; exit 2; }
            timeout_min=$2; shift 2 ;;
        --timeout=*) timeout_min=${1#*=}; shift ;;
        *)           gradle_args+=( "$1" ); shift ;;
    esac
done
num_ok() { awk -v v="$1" 'BEGIN{ exit (v ~ /^[0-9]+(\.[0-9]+)?$/ && v+0 >= 0) ? 0 : 1 }'; }
num_ok "$stall_min"   || { echo "[bsan] --stall must be minutes (number ≥ 0, e.g. 10 or 0.5)" >&2; exit 2; }
num_ok "$timeout_min" || { echo "[bsan] --timeout must be minutes (number ≥ 0, e.g. 30 or 0.5)" >&2; exit 2; }
stall_sec=$(awk -v v="$stall_min"     'BEGIN{ printf "%d", v*60 }')
timeout_sec=$(awk -v v="$timeout_min" 'BEGIN{ printf "%d", v*60 }')
if [ "$verbose" = 1 ] && [ "$quiet" = 1 ]; then
    echo "[bsan] --quiet and --verbose are mutually exclusive" >&2
    exit 2
fi
[ "${#gradle_args[@]}" -eq 0 ] && gradle_args=( "${default_sweep[@]}" )

# Colors: only when a real terminal is attached and the user hasn't opted out.
# Non-TTY output stays plain (grep-able, embeddable), NO_COLOR is the standard
# escape hatch. `color` drives the colorizer stage; C_R doubles as the
# has-palette test for bs_line.
color=0
C_L=''    C_OK='' C_BAD='' C_ERR='' C_WARN='' C_DIM='' C_R=''
if [ -t 1 ] && [ -t 2 ] && [ -z "${NO_COLOR:-}" ] && [ "${TERM:-}" != dumb ]; then
    color=1
    C_L=$'\033[36m'        # [bsan] label — cyan
    C_OK=$'\033[1;32m'     # clean-run status — bold green
    C_BAD=$'\033[1;31m'    # failing status / [bsan] FAILED — bold red
    C_ERR=$'\033[31m'      # error lines — red
    C_WARN=$'\033[33m'     # warnings (verbose) — yellow
    C_DIM=$'\033[2m'       # secondary text — dim
    C_R=$'\033[0m'
fi

# abspath <p> — usable in the done line and banner: the git-dir store is an
# absolute path in linked worktrees, so prefixing $PWD must be conditional.
abspath() {
    case "$1" in /*) printf '%s\n' "$1" ;; *) printf '%s%s\n' "$PWD/" "$1" ;; esac
}

# bs_line <color> <text> <visible> — prints "[bsan] <text>" to stderr with the
# colored label + given text color (skipped for <visible>=banner in --quiet).
# Run metadata (start/status) also goes to last-run.txt; full.txt stays pure
# gradle output, so the console replay never duplicates these lines.
bs_line() {
    [ "$quiet" = 1 ] && [ "$3" = banner ] && return 0
    if [ -n "$C_R" ]; then
        printf '%s[bsan]%s %s%s%s\n' "$C_L" "$C_R" "$1" "$2" "$C_R" >&2
    else
        printf '%s\n' "[bsan] $2" >&2
    fi
}

# w_note <text> — direct-to-terminal note from the watchdog (bypasses the
# console filter; not recorded). Suppressed in --quiet.
w_note() {
    [ "$quiet" = 1 ] && return 0
    if [ -n "$C_R" ]; then
        printf '%s[bsan]%s %s%s%s\n' "$C_L" "$C_R" "$C_DIM" "$1" "$C_R" >&2
    else
        printf '%s\n' "[bsan] $1" >&2
    fi
}

# errors.log must be gitignored (skipped when a *.log pattern already covers it).
if [ ! -f .gitignore ]; then
    printf 'errors.log\n' > .gitignore
elif ! grep -Fqx 'errors.log' .gitignore && ! grep -Eq '^\*\.log' .gitignore; then
    printf '\n# bugsanitizer console runner\nerrors.log\n' >> .gitignore
fi

# Error-line filter — also used to build errors.log from the raw stream.
# OOM/daemon signatures are included so a wedged/crashed build is captured in
# errors.log even when gradle never got to print an "e:"/"error:" line.
errors_re='^e: |^error:|^ERROR|FAILED|^FAILURE|^Caused +by|Execution failed|BUILD FAILED|OutOfMemoryError|Java heap space|GC overhead limit|Metaspace|Daemon could not be launched|Unable to start the daemon|build daemon .*disappeared|Could not connect to the Gradle daemon|Could not connect to Kotlin|Unable to connect to Kotlin|Failed to connect to Kotlin|[Kk]otlin (compile )?daemon .*(exited|died|crashed|terminated|failed|not responding)'
console_re='\[bsan\]|BUILD +(SUCCESSFUL|FAILED)|'"$errors_re"
[ "$quiet" = 1 ] && console_re=$errors_re
[ "$verbose" = 1 ] && console_re='.*'
mode_desc='errors + [bsan] progress + result'
[ "$quiet" = 1 ] && mode_desc='errors only'
[ "$verbose" = 1 ] && mode_desc='everything'

[ -x ./gradlew ] || { echo "[bsan] ./gradlew not found/executable at the repo root" >&2; exit 2; }

# --stop-only: mirrors gradle's own flag. Lock-free on purpose (see lock below).
if [ "$stop_only" = 1 ]; then
    bs_line "" "--stop: ./gradlew --stop" always
    ./gradlew --stop
    rc=$?
    bs_line "" "--stop done (rc=$rc)" always
    exit $rc
fi

# --fresh: clear wedged daemons first. Before the lock, so it can unwedge a
# hung sweep. gradle --stop covers gradle daemons; kotlin compile daemons are
# separate processes and need the explicit pkill.
if [ "$fresh" = 1 ]; then
    bs_line "" "--fresh: ./gradlew --stop first" always
    ./gradlew --stop || bs_line "$C_BAD" "--stop returned rc=$? — continuing with the sweep" always
    if pkill -f KotlinCompileDaemon 2>/dev/null; then
        bs_line "$C_DIM" "--fresh: kotlin compile daemon(s) killed too" banner
    fi
fi

# One sweep per tree (non-blocking). Children get the fd closed (8>&-) so an
# orphaned build can't keep the lock after the script dies. --stop is lock-free
# — it is the tool for unwedging a hung run and must never queue behind one.
if command -v flock >/dev/null 2>&1; then
    exec 8>"$hidden/bsan.lock" || exit 2
    if ! flock -n 8; then
        echo "[bsan] another bugsanitizer sweep is running in this tree — use --stop to clear it or wait" >&2
        exit 2
    fi
fi

# Regenerate the task-lifecycle logger every run (loaded with -I). Lines go to
# stderr (picked up by the console filter) and carry a per-task duration.
# Quoted heredoc on purpose: ${task.path} etc. must reach the .kts literally.
cat > "$init" <<'BSAN_INIT'
gradle.addListener(object : org.gradle.api.execution.TaskExecutionListener {
    private val starts = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun fmt(nanos: Long): String = if (nanos < 0) "-" else {  // "-" = start was never seen
        val ms = nanos / 1_000_000
        if (ms < 1000) "${ms}ms"
        else String.format(java.util.Locale.ROOT, "%.1fs", ms / 1000.0)
    }

    override fun beforeExecute(task: org.gradle.api.Task) {
        starts[task.path] = System.nanoTime()
        System.err.println("[bsan] starting :${task.path}")
    }

    override fun afterExecute(task: org.gradle.api.Task, state: org.gradle.api.tasks.TaskState) {
        val begin = starts.remove(task.path)
        val elapsed = if (begin == null) -1L else System.nanoTime() - begin
        val failure = state.failure
        if (failure != null) {
            System.err.println("[bsan] FAILED :${task.path} (${fmt(elapsed)}) -> ${failure.message ?: "(no message)"}")
        } else {
            System.err.println("[bsan] finished :${task.path} (${fmt(elapsed)})")
        }
    }
})
BSAN_INIT

# Colorizer: runs last in the console pipe, so full.txt and errors.log stay
# plain-ASCII. Classifies on a spacing-normalized copy of the line, because the
# console stream is sed-spaced. fflush("") keeps it live.
awkprog='{
    norm = $0; gsub(/ +/, " ", norm)
    if (norm ~ /^\[bsan\] FAILED/)                 printf "\033[1;31m%s\033[0m\n", $0
    else if (norm ~ /^\[bsan\]/)                   printf "\033[2m%s\033[0m\n", $0
    else if (norm ~ /BUILD +SUCCESSFUL/)           printf "\033[1;32m%s\033[0m\n", $0
    else if (norm ~ /BUILD +FAILED/)               printf "\033[1;31m%s\033[0m\n", $0
    else if (norm ~ /^e: |^error:/)                printf "\033[31m%s\033[0m\n", $0
    else if (norm ~ /^w: /)                        printf "\033[33m%s\033[0m\n", $0
    else if (norm ~ /^ERROR| FAILED|^FAILURE|FAILURE|Execution failed/)  printf "\033[31m%s\033[0m\n", $0
    else                                           printf "%s\n", $0
    fflush("")
}'

console_pid=""
gradle_pid=""
watch_pid=""

# start_console — tails full.txt through the same filter/colorizer as before.
# A subshell wrapper keeps every pipeline member killable as one unit
# (pkill -P + kill the subshell; killing tail cascades EOF through the rest).
start_console() {
    (
        if [ "$color" = 1 ]; then
            tail -n +1 -F -s 0.5 "$full" 2>/dev/null |
                sed -u 's/ /  /g' |
                grep --line-buffered -Ea "$console_re" |
                awk "$awkprog"
        else
            tail -n +1 -F -s 0.5 "$full" 2>/dev/null |
                sed -u 's/ /  /g' |
                grep --line-buffered -Ea "$console_re"
        fi
    ) 8>&- &
    console_pid=$!
}

kill_console() {
    [ -n "$console_pid" ] || return 0
    pkill -TERM -P "$console_pid" 2>/dev/null
    kill -TERM "$console_pid" 2>/dev/null
    console_pid=""
}

# drain_console — give the console reader one tail poll cycle past the last
# write (two equal size samples 0.5s apart) so the final lines reach the screen
# before it is killed.
drain_console() {
    local prev=-1 cur i
    for i in 1 2 3 4 5 6 7 8; do
        sleep 0.5
        cur=$(stat -c %s "$full" 2>/dev/null) || cur=$prev
        [ "$cur" = "$prev" ] && return 0
        prev=$cur
    done
}

# kill_gradle_client — TERM, then KILL after a 15s grace. gradlew execs java,
# so the background PID IS the client JVM (no orphaned wrapper shell). This
# only disconnects the client; the daemon keeps the build — recovery is
# ./gradlew --stop / --fresh, which every kill message states.
kill_gradle_client() {
    kill -TERM "$gradle_pid" 2>/dev/null || return 0
    local i
    for i in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
        kill -0 "$gradle_pid" 2>/dev/null || return 0
        sleep 1
    done
    kill -KILL "$gradle_pid" 2>/dev/null
}

# start_watchdog — no new bytes in full.txt for stall_sec -> kill the client
# and leave the reason for the parent (rc 124). An optional wall-clock cap
# applies regardless of output. Silent-for-3m+ prints one note per minute.
start_watchdog() {
    (
        started=$(date +%s)
        last_size=0
        last_grew=$started
        last_note=0
        while kill -0 "$gradle_pid" 2>/dev/null; do
            sleep 2
            kill -0 "$gradle_pid" 2>/dev/null || exit 0
            now=$(date +%s)
            size=$(stat -c %s "$full" 2>/dev/null) || size=0
            [ "$size" != "$last_size" ] && { last_size=$size; last_grew=$now; }
            silent=$(( now - last_grew ))
            total=$(( now - started ))
            if [ "$stall_sec" -gt 0 ]; then
                if [ "$silent" -ge "$stall_sec" ]; then
                    printf 'stall: no output for %sm (silent %ss)\n' "$stall_min" "$silent" > "$reason_file"
                    kill_gradle_client
                    exit 0
                fi
                if [ "$silent" -ge 180 ]; then
                    m=$(( silent / 60 ))
                    [ "$m" -gt "$last_note" ] && { last_note=$m; w_note "still silent: ${m}m without output (total $(( total / 60 ))m) — stall-watch kills at ${stall_min}m"; }
                fi
            fi
            if [ "$timeout_sec" -gt 0 ] && [ "$total" -ge "$timeout_sec" ]; then
                printf 'timeout: wall clock exceeded %sm\n' "$timeout_min" > "$reason_file"
                kill_gradle_client
                exit 0
            fi
        done
    ) 8>&- &
    watch_pid=$!
}

# on_interrupt <SIG> — clean teardown on Ctrl-C or external TERM: stop the
# watchdog, cancel the client (INT so gradle can report, KILL after a 10s
# grace), stop the console reader, report, exit 130.
on_interrupt() {
    trap '' INT TERM
    local sig=$1 i
    [ -n "$watch_pid" ] && kill "$watch_pid" 2>/dev/null
    if [ -n "$gradle_pid" ] && kill -0 "$gradle_pid" 2>/dev/null; then
        kill -INT "$gradle_pid" 2>/dev/null
        for i in 1 2 3 4 5 6 7 8 9 10; do
            kill -0 "$gradle_pid" 2>/dev/null || break
            sleep 1
        done
        kill -KILL "$gradle_pid" 2>/dev/null
    fi
    kill_console
    bs_line "$C_BAD" "interrupted (SIG$sig) — build client stopped; daemons may still run: ./gradlew --stop" always
    printf 'interrupted SIG%s\n' "$sig" >> "$lastrun" 2>/dev/null
    exit 130
}
trap 'on_interrupt INT' INT
trap 'on_interrupt TERM' TERM

# Fix-ladder input: read the CURRENT gradle.properties once, so the advice
# always quotes what is actually configured, not what was true when written.
gp_daemon=$(sed -n 's/^org\.gradle\.jvmargs=//p' gradle.properties 2>/dev/null)
gp_kotlin=$(sed -n 's/^kotlin\.daemon\.jvmargs=//p' gradle.properties 2>/dev/null)
gp_workers=$(sed -n 's/^org\.gradle\.workers\.max=//p' gradle.properties 2>/dev/null)
gp_val() { if [ -n "$1" ]; then printf '%s' "$1"; else printf '(unset)'; fi; }

# print_diag <title> <ERE> <fix-one-liner> — scan full.txt for one signature
# class; if found, show up to 3 sample lines + the fix. Sets diag_found=1.
diag_found=0
print_diag() {
    local title=$1 re=$2 fix=$3 hits line
    hits=$(grep -Eac "$re" "$full" 2>/dev/null) || hits=0
    [ "${hits:-0}" -gt 0 ] || return 0
    diag_found=1
    bs_line "$C_BAD" "$title — $hits match(es):" always
    grep -Ea "$re" "$full" 2>/dev/null | head -3 | while IFS= read -r line; do
        [ ${#line} -gt 200 ] && line="${line:0:200}…"
        bs_line "$C_ERR" "  · $line" always
    done
    bs_line "$C_DIM" "  $fix" always
}

# Prep files and the plain metadata record.
: > "$log"
: > "$full"
: > "$reason_file"
stall_txt=$([ "$stall_sec" -gt 0 ] && printf '%sm' "$stall_min" || echo off)
cap_txt=$([ "$timeout_sec" -gt 0 ] && printf '%sm' "$timeout_min" || echo off)
{
    printf 'start %s · ./gradlew %s\n' "$(date '+%Y-%m-%d %H:%M:%S%z')" "${gradle_args[*]}"
    printf 'console: %s · stall-watch: %s · wall-cap: %s\n' "$mode_desc" "$stall_txt" "$cap_txt"
} > "$lastrun"

bs_line "" "start $(date '+%Y-%m-%d %H:%M:%S%z') · ./gradlew ${gradle_args[*]}" always
bs_line "$C_DIM" "console: $mode_desc · stall-watch: $stall_txt · wall-cap: $cap_txt · errors.log: $log · full: $(abspath "$full")" banner

run_started=$(date +%s)

start_console
./gradlew -I "$init" --console=plain "${gradle_args[@]}" 8>&- >> "$full" 2>&1 &
gradle_pid=$!
start_watchdog

wait "$gradle_pid"
raw_rc=$?

# Stop the watchdog before anything else (it could fire during teardown).
[ -n "$watch_pid" ] && { kill "$watch_pid" 2>/dev/null; wait "$watch_pid" 2>/dev/null; }

drain_console
kill_console

rc=$raw_rc
reason=$(cat "$reason_file" 2>/dev/null)
if [ -n "$reason" ] && [ "$raw_rc" -ne 0 ]; then
    rc=124
    total=$(( $(date +%s) - run_started ))
    bs_line "$C_BAD" "watchdog killed the build after $(( total / 60 ))m total — $reason" always
    bs_line "$C_DIM" "the client was killed; the daemon keeps the build — recover with ./gradlew --stop, or rerun with --fresh" always
    bs_line "$C_DIM" "last lines of the log before the kill:" always
    tail -n 12 "$full" 2>/dev/null | while IFS= read -r line; do
        printf '%s\n' "[bsan]   | $line" >&2
    done
elif [ -n "$reason" ]; then
    bs_line "$C_DIM" "watchdog fired as the build finished (rc=0) — ignoring its kill" always
elif [ "$raw_rc" -eq 137 ]; then
    bs_line "$C_BAD" "rc=137: the build client was SIGKILLed — almost always the Linux OOM killer (check: dmesg | grep -i oom)" always
elif [ "$raw_rc" -eq 143 ]; then
    bs_line "$C_DIM" "rc=143: the gradle client was SIGTERMed externally — daemons may still run: ./gradlew --stop" always
fi

# Diagnosis: OOM / daemon signatures in full.txt, fix ladder quoting the
# CURRENT gradle.properties values (read live above).
print_diag "java heap OOM (gradle or kotlin daemon)" \
    'OutOfMemoryError|Java heap space|GC overhead limit|Metaspace' \
    "fix: ./gradlew --stop, then rerun (run.sh --fresh …); if recurring, raise -Xmx — org.gradle.jvmargs ($(gp_val "$gp_daemon"))"
print_diag "kotlin compile daemon died" \
    'Could not connect to Kotlin|Unable to connect to Kotlin|Failed to connect to Kotlin|[Kk]otlin (compile )?daemon .*(exited|died|crashed|terminated|failed|not responding)' \
    "fix: ./gradlew --stop; if recurring, raise kotlin.daemon.jvmargs ($(gp_val "$gp_kotlin")); stubborn: pkill -f KotlinCompileDaemon"
print_diag "gradle daemon could not start / lost" \
    'Daemon could not be launched|Unable to start the daemon|Could not connect to the Gradle daemon|build daemon .*disappeared' \
    "fix: ./gradlew --stop (or run.sh --fresh …); persistent: check ~/.gradle/daemon disk space and JAVA_HOME"
print_diag "OS SIGKILL / OOM-killer" \
    'oom-killer|Killed process|Out of memory: Kill|OOMKilled|exit value 137|exit code 137|SIGKILL' \
    "fix: the OS ran out of memory — check dmesg; lower parallelism: org.gradle.workers.max ($(gp_val "$gp_workers")) or pass --max-workers=2"
if [ "$diag_found" = 1 ]; then
    bs_line "$C_DIM" "recovery first-step for all daemon/OOM trouble: ./gradlew --stop, or rerun with --fresh" always
fi

# Slowest tasks this run, from the init script's [bsan] finished/FAILED lines.
# Wall-clock per task (including parallel overlaps) — the top of this list is
# where a hang would show first.
slow_tasks=$(awk '
    /^\[bsan\] (finished|FAILED) :/ {
        t=$0
        sub(/^\[bsan\] [A-Za-z]+ :/, "", t)
        sub(/ \(.*/, "", t)
        v=-1
        if (match($0, /\([0-9.]+(ms|s)\)/)) {
            p=substr($0, RSTART+1, RLENGTH-2)
            if (p ~ /ms$/) { sub(/ms$/, "", p); v=p+0 }
            else           { sub(/s$/, "", p);  v=p*1000 }
        }
        if (v>=0) printf "%.1f\t%s\n", v/1000, t
    }
' "$full" | sort -rn | head -8)
if [ -n "$slow_tasks" ]; then
    bs_line "$C_DIM" "slowest tasks this run:" banner
    printf '%s\n' "$slow_tasks" | while IFS=$'\t' read -r s t; do
        bs_line "" "  ${s}s  $t" banner
    done
fi

# $PWD/$path prefix is conditional — see abspath() near the top.
grep -Ea "$errors_re" "$full" > "$log"
dur=$(( $(date +%s) - run_started ))
dur_txt="$(( dur / 60 ))m$(( dur % 60 ))s"
if [ "$rc" -eq 0 ]; then
    rm -f "$log"
    bs_line "$C_OK" "✓ rc=0 — clean run, no errors ($dur_txt)" always
    bs_line "$C_DIM" "full log: $(abspath "$full")" always
    status="✓ rc=0 — clean run ($dur_txt)"
elif [ -s "$log" ]; then
    n=$(wc -l < "$log" | tr -d '[:space:]')
    bs_line "$C_BAD" "✗ rc=$rc — errors.log has $n lines ($dur_txt) · $(abspath "$log")" always
    bs_line "$C_DIM" "full log: $(abspath "$full") · --verbose to see everything" always
    status="✗ rc=$rc — errors.log has $n lines ($dur_txt)"
else
    rm -f "$log"
    bs_line "$C_BAD" "✗ rc=$rc — failed; no error lines captured ($dur_txt)" always
    bs_line "$C_DIM" "full log: $(abspath "$full")" always
    status="✗ rc=$rc — failed, no error lines captured ($dur_txt)"
fi
printf '%s · end %s\n' "$status" "$(date '+%H:%M:%S%z')" >> "$lastrun"
exit "$rc"
