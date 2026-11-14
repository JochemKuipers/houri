#!/usr/bin/env bash
# bugsanitizer self-test — stub-gradle sandbox; no real gradle involved.
#
# Builds a throwaway git repo under /tmp/opencode, installs a stub ./gradlew
# whose behavior is driven by BSAN_STUB (ok/fail/oom/launch/hang/exit137/
# kill137), then runs run.sh in a matrix of modes and asserts console output,
# exit codes, file effects, the stall/timeout watchdog, interrupt cleanup, the
# single-sweep lock, colors and linked-worktree path resolution.
#
# Usage: scripts/bugsanitizer/selftest.sh        # prints PASS/FAIL per case
# Exit 0 when every case passes, 1 otherwise. Slow parts (hang/watchdog cases)
# use tiny --stall/--timeout values; the whole suite takes ~30–60s.
#
# Note on assertions: console lines pass through `sed 's/ /  /g'`, so every
# console-content check must be spacing-tolerant (grep -E with ' +').
set -u
base=/tmp/opencode/bsan-selftest.$$
mkdir -p "$base"
trap 'rm -rf "$base"' EXIT

here=$(cd "$(dirname "$0")" && pwd)
run="$here/run.sh"
pass=0
fail=0
ok()   { pass=$((pass+1)); echo "PASS: $1"; }
bad()  { fail=$((fail+1)); echo "FAIL: $1"; }
check() { if [ "$2" -eq 0 ]; then ok "$1"; else bad "$1"; fi; }

# mkrepo <n> — one fresh stub-gradle git repo; prints its path.
mkrepo() {
    local d="$base/repo$1"
    mkdir -p "$d/sub"
    ( cd "$d" && git init -q . && git config user.email t@t && git config user.name t )
    cat > "$d/gradlew" <<'STUB'
#!/usr/bin/env bash
case " $* " in *" --stop "*) echo "Stopping Daemon(s)"; echo "Gradle daemon stopped."; exit 0 ;; esac
case "${BSAN_STUB:-ok}" in
  ok)      echo "> Task :okTask"
           echo "[bsan] starting :okTask" >&2
           echo "[bsan] finished :okTask (250ms)" >&2
           echo "BUILD SUCCESSFUL in 1s"; exit 0 ;;
  fail)    echo "e: Test.kt:1:1 error: unresolved reference" >&2
           echo "FAILURE: Build failed with an exception." >&2
           echo "BUILD FAILED in 1s"; exit 1 ;;
  oom)     echo "e: K.kt:1:1 OOM" >&2
           echo "java.lang.OutOfMemoryError: Java heap space" >&2
           echo "Could not connect to Kotlin compile daemon" >&2
           echo "BUILD FAILED in 1s"; exit 1 ;;
  launch)  echo "FAILURE: Build failed with an exception." >&2
           echo "Daemon could not be launched from the daemon registry" >&2
           echo "BUILD FAILED in 1s"; exit 1 ;;
  slow)    echo "> Task :slowTask"
           echo "[bsan] finished :slowTask (12.4s)" >&2
           echo "BUILD SUCCESSFUL in 13s"; exit 0 ;;
  hang)    exec sleep 300 ;;
  exit137) exit 137 ;;
  kill137) kill -9 $$ ;;
esac
STUB
    chmod +x "$d/gradlew"
    cat > "$d/gradle.properties" <<'PROPS'
org.gradle.jvmargs=-Xmx6g
kotlin.daemon.jvmargs=-Xmx4g
org.gradle.workers.max=4
PROPS
    ( cd "$d" && git add -A && git commit -qm init )
    printf '%s' "$d"
}

# runcase <stub> <args...> — run run.sh with the given stub mode; sets out/rc.
d=""
out=""
rc=0
runcase() {
    local stub=$1; shift
    out=$(cd "$d" && BSAN_STUB="$stub" bash "$run" "$@" 2>&1)
    rc=$?
}

# bgcase <stub> <outfile> <args...> — background run for interrupt/lock tests.
bgcase() {
    local stub=$1 out_f=$2; shift 2
    ( cd "$d" && BSAN_STUB="$stub" exec bash "$run" "$@" > "$out_f" 2>&1 ) &
}

# leftovers <repo-dir> — 0 when no bsan-spawned process is still alive.
leftovers() {
    sleep 1
    if pgrep -f "$1/.git/bugsanitizer" >/dev/null 2>&1; then return 1; fi
    return 0
}

ESC=$'\033'

# ── 1. clean run ─────────────────────────────────────────────────────────────
d=$(mkrepo 1)
runcase ok
check "clean run rc=0"                       $([ "$rc" -eq 0 ]; echo $?)
check "clean run status line"                $(printf '%s' "$out" | grep -qF '✓ rc=0 — clean run'; echo $?)
check "clean run deletes errors.log"         $([ ! -f "$d/errors.log" ]; echo $?)
check "clean run full.txt has result"        $(grep -qa 'BUILD SUCCESSFUL' "$d/.git/bugsanitizer/full.txt"; echo $?)
check "clean run task progress shown"        $(printf '%s' "$out" | grep -qE '\[bsan\] +finished +:okTask'; echo $?)
check "clean run slowest-tasks section"      $(printf '%s' "$out" | grep -q 'slowest tasks'; echo $?)
check "clean run duration shown"             $(printf '%s' "$out" | grep -qE 'no errors \([0-9]+m[0-9]+s\)'; echo $?)
check "clean run gitignore created"          $(grep -qF 'errors.log' "$d/.gitignore"; echo $?)
check "clean run last-run.txt final status"  $(grep -qF '✓ rc=0' "$d/.git/bugsanitizer/last-run.txt"; echo $?)
check "clean run no leftovers"               $(leftovers "$d"; echo $?)

# ── 2. failing run ───────────────────────────────────────────────────────────
d=$(mkrepo 2)
runcase fail fail
check "fail run rc=1"                        $([ "$rc" -eq 1 ]; echo $?)
check "fail run status line"                 $(printf '%s' "$out" | grep -qF '✗ rc=1'; echo $?)
check "fail run errors.log has e: line"      $(grep -q '^e: Test.kt' "$d/errors.log"; echo $?)
check "fail run no leftover"                 $(leftovers "$d"; echo $?)

# ── 3. OOM diagnosis + gradle.properties-quoting fix ladder ─────────────────
d=$(mkrepo 3)
runcase oom
check "oom run rc=1"                         $([ "$rc" -eq 1 ]; echo $?)
check "oom diagnosis: java heap OOM"         $(printf '%s' "$out" | grep -qF 'java heap OOM'; echo $?)
check "oom diagnosis quotes daemon -Xmx6g"   $(printf '%s' "$out" | grep -qF -- '-Xmx6g'; echo $?)
check "oom diagnosis: kotlin daemon died"    $(printf '%s' "$out" | grep -qF 'kotlin compile daemon died'; echo $?)
check "oom diagnosis quotes kotlin -Xmx4g"   $(printf '%s' "$out" | grep -qF -- '-Xmx4g'; echo $?)
check "oom recovery first-step line"         $(printf '%s' "$out" | grep -qF 'recovery first-step'; echo $?)
check "oom signatures land in errors.log"    $(grep -qF 'OutOfMemoryError' "$d/errors.log"; echo $?)

# ── 4. daemon-launch diagnosis ───────────────────────────────────────────────
d=$(mkrepo 4)
runcase launch
check "launch diagnosis shown"               $(printf '%s' "$out" | grep -qF 'gradle daemon could not start'; echo $?)

# ── 5. stall watchdog ────────────────────────────────────────────────────────
d=$(mkrepo 5)
runcase hang hang --stall 0.05
check "stall kill rc=124"                    $([ "$rc" -eq 124 ]; echo $?)
check "stall reason line"                    $(printf '%s' "$out" | grep -qF 'watchdog killed the build'; echo $?)
check "stall reason mentions stall"          $(printf '%s' "$out" | grep -qF 'stall: no output'; echo $?)
check "stall prints last log lines"          $(printf '%s' "$out" | grep -qF 'last lines of the log'; echo $?)
check "stall recovery advice"                $(printf '%s' "$out" | grep -qF './gradlew --stop'; echo $?)
check "stall no leftovers (console+client)"  $(leftovers "$d"; echo $?)

# ── 6. wall-clock timeout ────────────────────────────────────────────────────
d=$(mkrepo 6)
runcase hang hang --timeout 0.05 --stall 0
check "timeout kill rc=124"                  $([ "$rc" -eq 124 ]; echo $?)
check "timeout reason line"                  $(printf '%s' "$out" | grep -qF 'timeout: wall clock exceeded'; echo $?)
check "timeout no leftovers"                 $(leftovers "$d"; echo $?)

# ── 7. Ctrl-C teardown ───────────────────────────────────────────────────────
d=$(mkrepo 7)
bgcase hang "$base/int.out" hang --stall 1
intpid=$!
sleep 2
kill -INT "$intpid" 2>/dev/null
wait "$intpid"; rc=$?
check "interrupt rc=130"                     $([ "$rc" -eq 130 ]; echo $?)
check "interrupt status line"                $(grep -qF 'interrupted (SIGINT)' "$base/int.out"; echo $?)
check "interrupt no leftovers"               $(leftovers "$d"; echo $?)

# ── 8. SIGKILL'd client (rc 137) ─────────────────────────────────────────────
d=$(mkrepo 8)
runcase exit137
check "exit137 rc=137"                       $([ "$rc" -eq 137 ]; echo $?)
check "exit137 OOM-killer note"              $(printf '%s' "$out" | grep -qF 'rc=137: the build client was SIGKILLed'; echo $?)
d=$(mkrepo 9)
runcase kill137
check "kill137 rc=137"                       $([ "$rc" -eq 137 ]; echo $?)
check "kill137 OOM-killer note"              $(printf '%s' "$out" | grep -qF 'rc=137: the build client was SIGKILLed'; echo $?)

# ── 9. --quiet mode ──────────────────────────────────────────────────────────
d=$(mkrepo 10)
runcase oom --quiet
check "quiet rc=1"                           $([ "$rc" -eq 1 ]; echo $?)
check "quiet hides banner"                   $(printf '%s' "$out" | grep -qvF 'console:'; echo $?)
check "quiet keeps final status"             $(printf '%s' "$out" | grep -qF '✗ rc=1'; echo $?)
check "quiet keeps diagnosis"                $(printf '%s' "$out" | grep -qF 'java heap OOM'; echo $?)
check "quiet keeps sample error lines"       $(printf '%s' "$out" | grep -qF 'OutOfMemoryError'; echo $?)

# ── 10. --verbose mode ───────────────────────────────────────────────────────
d=$(mkrepo 11)
runcase ok ok --verbose
check "verbose shows everything (rc=0)"      $([ "$rc" -eq 0 ]; echo $?)
check "verbose shows task lines"             $(printf '%s' "$out" | grep -qE 'Task +:okTask'; echo $?)

# ── 11. run from a subdirectory ──────────────────────────────────────────────
d=$(mkrepo 12)
out=$(cd "$d/sub" && BSAN_STUB=slow bash "$run" slow 2>&1); rc=$?
check "subdir run rc=0"                      $([ "$rc" -eq 0 ]; echo $?)
check "subdir run full.txt at root"          $(grep -qa 'BUILD SUCCESSFUL' "$d/.git/bugsanitizer/full.txt"; echo $?)
check "subdir run errors.log at root"        $([ ! -f "$d/sub/errors.log" ]; echo $?)

# ── 12. linked worktree path resolution ──────────────────────────────────────
d=$(mkrepo 13)
( cd "$d" && git worktree add -q "$base/wt" -b wtbr ) 2>/dev/null
out=$(cd "$base/wt" && BSAN_STUB=ok bash "$run" 2>&1); rc=$?
check "worktree run rc=0"                    $([ "$rc" -eq 0 ]; echo $?)
check "worktree full.txt under main .git"    $(grep -qa 'BUILD SUCCESSFUL' "$d/.git/worktrees/wt/bugsanitizer/full.txt"; echo $?)
check "worktree path shown in console"       $(printf '%s' "$out" | grep -qF '.git/worktrees/wt/bugsanitizer/full.txt'; echo $?)

# ── 13. colors on a PTY; files stay escape-free ──────────────────────────────
d=$(mkrepo 14)
( cd "$d" && BSAN_STUB=fail script -qec "bash '$run' fail" "$base/ts.out" >/dev/null 2>&1 )
check "PTY console colorized"                $(grep -qa "$ESC" "$base/ts.out"; echo $?)
check "full.txt stays escape-free"           $(! grep -qa "$ESC" "$d/.git/bugsanitizer/full.txt"; echo $?)

# ── 14. single-sweep lock ────────────────────────────────────────────────────
d=$(mkrepo 15)
bgcase hang "$base/lock.out" hang --stall 0.2
lockpid=$!
sleep 2
out=$(cd "$d" && BSAN_STUB=ok bash "$run" ok 2>&1); rc=$?
check "concurrent sweep refused rc=2"        $([ "$rc" -eq 2 ]; echo $?)
check "concurrent sweep message"             $(printf '%s' "$out" | grep -qF 'another bugsanitizer sweep'; echo $?)
kill -INT "$lockpid" 2>/dev/null
wait "$lockpid" 2>/dev/null
check "locked hang cleaned up"               $(leftovers "$d"; echo $?)

# ── 15. --stop only ──────────────────────────────────────────────────────────
d=$(mkrepo 16)
out=$(cd "$d" && bash "$run" --stop 2>&1); rc=$?
check "--stop rc=0"                          $([ "$rc" -eq 0 ]; echo $?)
check "--stop passthrough output"            $(printf '%s' "$out" | grep -qF 'Gradle daemon stopped.'; echo $?)
check "--stop done line"                     $(printf '%s' "$out" | grep -qF -- '--stop done (rc=0)'; echo $?)

# ── 16. --fresh clears daemons then sweeps ───────────────────────────────────
d=$(mkrepo 17)
out=$(cd "$d" && BSAN_STUB=slow bash "$run" --fresh slow 2>&1); rc=$?
check "--fresh rc=0"                         $([ "$rc" -eq 0 ]; echo $?)
check "--fresh announces the stop"           $(printf '%s' "$out" | grep -qF -- '--fresh: ./gradlew --stop first'; echo $?)
check "--fresh really invoked --stop"        $(printf '%s' "$out" | grep -qF 'Stopping Daemon(s)'; echo $?)

# ── 17. bad flag values rejected ─────────────────────────────────────────────
d=$(mkrepo 18)
out=$(cd "$d" && bash "$run" --stall abc ok 2>&1); rc=$?
check "--stall abc rejected rc=2"            $([ "$rc" -eq 2 ]; echo $?)
out=$(cd "$d" && bash "$run" --stall 2>&1); rc=$?
check "--stall without value rejected"       $([ "$rc" -eq 2 ]; echo $?)
out=$(cd "$d" && bash "$run" --help 2>&1); rc=$?
check "--help prints usage rc=0"             $([ "$rc" -eq 0 ] && printf '%s' "$out" | grep -qF 'bsan flags'; echo $?)

echo "----"
echo "selftest: $pass passed, $fail failed"
[ "$fail" -eq 0 ]
