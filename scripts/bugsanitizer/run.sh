#!/usr/bin/env bash
# bugsanitizer — a friendlier gradle console for this repo.
#
# Usage:
#   scripts/bugsanitizer/run.sh                # default sweep (spotlessApply
#                                              #  spotlessCheck compileDebugUnitTestKotlin
#                                              #  app:assembleDebug)
#   scripts/bugsanitizer/run.sh <gradle args>  # any task set / flags (--stacktrace etc.)
#
# Flags consumed here (never forwarded to gradle):
#   --verbose   show everything gradle prints
#   --quiet     show ONLY error lines (no banner, no task progress)
#
# Console: [bsan] task progress + error lines + final build result.
# - --verbose: everything gradle prints; --quiet: strictly errors + final status.
# - When stdout+stderr are a terminal (and NO_COLOR is unset, TERM != dumb),
#   lines are colorized and class-colored; through a pipe every line is plain,
#   so redirecting/teeing never embeds escape codes in files.
#
# Files:
#   errors.log                                  error lines only; cleared every run;
#                                               deleted when the run is clean; auto-added
#                                               to .gitignore once
#   <git-dir>/bugsanitizer/full.txt             full RAW (uncolored) stdout+stderr; cleared
#                                               every run (<git-dir>/.git in a plain checkout;
#                                               the worktree's admin dir under the main
#                                               checkout's .git/worktrees/<name> in a linked
#                                               worktree — never in git status)
#   <git-dir>/bugsanitizer/init.settings.gradle.kts  generated each run, injected via -I;
#                                                   task listener prints [bsan] start/finish
#                                                   lines WITH per-task duration
#
# Exit code is gradle's own. Always runs at the repo root (cds there first).

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

default_sweep=(spotlessApply spotlessCheck compileDebugUnitTestKotlin app:assembleDebug)
log=errors.log
full=$hidden/full.txt
init=$hidden/init.settings.gradle.kts

verbose=0
quiet=0
gradle_args=()
for arg in "$@"; do
    case "$arg" in
        --verbose) verbose=1 ;;
        --quiet)   quiet=1 ;;
        *) gradle_args+=( "$arg" ) ;;
    esac
done
[ "${#gradle_args[@]}" -eq 0 ] && gradle_args=( "${default_sweep[@]}" )
if [ "$verbose" = 1 ] && [ "$quiet" = 1 ]; then
    echo "[bsan] --quiet and --verbose are mutually exclusive" >&2
    exit 2
fi

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
# colored label + given text color (skipped for <visible>=banner in --quiet),
# and records the PLAIN copy in full.txt. full.txt therefore stays raw.
bs_line() {
    printf '%s\n' "[bsan] $2" >> "$full"
    [ "$quiet" = 1 ] && [ "$3" = banner ] && return 0
    if [ -n "$C_R" ]; then
        printf '%s[bsan]%s %s%s%s\n' "$C_L" "$C_R" "$1" "$2" "$C_R" >&2
    else
        printf '%s\n' "[bsan] $2" >&2
    fi
}

# errors.log must be gitignored (skipped when a *.log pattern already covers it).
if [ ! -f .gitignore ]; then
    printf 'errors.log\n' > .gitignore
elif ! grep -Fqx 'errors.log' .gitignore && ! grep -Eq '^\*\.log' .gitignore; then
    printf '\n# bugsanitizer console runner\nerrors.log\n' >> .gitignore
fi

# Error-line filter — also used to build errors.log from the raw stream.
errors_re='^e: |^error:|^ERROR|FAILED|^FAILURE|^Caused +by|Execution failed|BUILD FAILED'
console_re='\[bsan\]|BUILD +(SUCCESSFUL|FAILED)|'"$errors_re"
[ "$quiet" = 1 ] && console_re=$errors_re
[ "$verbose" = 1 ] && console_re='.*'
mode_desc='errors + [bsan] progress + result'
[ "$quiet" = 1 ] && mode_desc='errors only'
[ "$verbose" = 1 ] && mode_desc='everything'

mkdir -p "$hidden"
: > "$log"
: > "$full"

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

# Colorizer: runs last in the pipe (after tee→sed→grep), so full.txt and
# errors.log stay plain-ASCII. Classifies on a spacing-normalized copy of the
# line, because the console stream is sed-spaced. fflush("") keeps it live.
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

bs_line "" "start $(date '+%Y-%m-%d %H:%M:%S%z') · ./gradlew ${gradle_args[*]}" always
bs_line "$C_DIM" "console: $mode_desc · errors.log: $log · full: $(abspath "$full")" banner

if [ "$color" = 1 ]; then
    ./gradlew -I "$init" --console=plain "${gradle_args[@]}" 2>&1 |
        tee -a "$full" |
        sed -u 's/ /  /g' |
        grep --line-buffered -Ea "$console_re" |
        awk "$awkprog"
    rc=${PIPESTATUS[0]}
else
    ./gradlew -I "$init" --console=plain "${gradle_args[@]}" 2>&1 |
        tee -a "$full" |
        sed -u 's/ /  /g' |
        grep --line-buffered -Ea "$console_re"
    rc=${PIPESTATUS[0]}
fi

# $PWD/$path prefix is conditional — see abspath() near the top.
grep -Ea "$errors_re" "$full" > "$log"
if [ -s "$log" ]; then
    n=$(wc -l < "$log" | tr -d '[:space:]')
    bs_line "$C_BAD" "✗ rc=$rc — errors.log has $n lines · $(abspath "$log")" always
    bs_line "$C_DIM" "full log: $(abspath "$full") · --verbose to see everything" always
else
    rm -f "$log"
    bs_line "$C_OK" "✓ rc=$rc — clean run, no errors" always
    bs_line "$C_DIM" "full log: $(abspath "$full")" always
fi
exit "$rc"
