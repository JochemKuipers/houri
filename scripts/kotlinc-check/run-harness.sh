#!/usr/bin/env bash
# Runs the pure-JVM assertion harnesses in ./harness against real project sources.
#
# Separate from check.sh: check.sh answers "does this compile", this answers "does it compute the
# right thing". Pure logic only - anything needing the Android SDK or a native library is out of
# scope here, and belongs in a Gradle unit test instead.
#
# USAGE
#   scripts/kotlinc-check/run-harness.sh            # all harnesses
#   scripts/kotlinc-check/run-harness.sh path        # only the one whose file matches "path"
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
HARNESS_DIR="$REPO_ROOT/scripts/kotlinc-check/harness"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

die() { echo "run-harness: $*" >&2; exit 1; }

find_jar() {
    local jar
    jar=$(find "$CACHE/$1/$2" -name '*.jar' 2>/dev/null | grep -v -- '-sources' | sort -V | tail -1)
    [ -n "$jar" ] || die "could not find $1:$2 in the Gradle cache"
    echo "$jar"
}

COMPILER=$(find_jar org.jetbrains.kotlin kotlin-compiler-embeddable)
STDLIB=$(find_jar org.jetbrains.kotlin kotlin-stdlib)
REFLECT=$(find_jar org.jetbrains.kotlin kotlin-reflect)
COROUTINES=$(find_jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm)
ANNOTATIONS=$(find_jar org.jetbrains annotations)
COMPILER_CP="$COMPILER:$STDLIB:$REFLECT:$COROUTINES:$ANNOTATIONS"

FILTER="${1:-}"
FAILED=0
RAN=0

for MAIN in "$HARNESS_DIR"/*Harness.kt; do
    [ -e "$MAIN" ] || continue
    NAME=$(basename "$MAIN" .kt)
    if [ -n "$FILTER" ] && [[ "$NAME" != *"$FILTER"* ]]; then continue; fi

    # Project sources the harnesses link against. Every one of these files is deliberately free of
    # Android imports so it compiles and runs on a plain JVM.
    SOURCES=()
    while IFS= read -r f; do SOURCES+=("$f"); done < <(
        find "$REPO_ROOT/core/archive/src/main/kotlin/mihon/core/archive" \
             "$REPO_ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/novel" \
             -name 'EpubPaths.kt' -o -name 'EpubBook.kt' \
             -o -name 'NovelTypography.kt' -o -name 'NovelTypographyCss.kt' -o -name 'NovelProgress.kt' \
        2>/dev/null | sort -u
    )
    SOURCES+=("$MAIN")

    OUT=$(mktemp -d)
    echo "== $NAME =="
    # A hard compile error must fail the run, not fall through to "class not found".
    if ! java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
            -no-stdlib -nowarn -cp "$STDLIB" -d "$OUT/classes" "${SOURCES[@]}" 2>&1 \
            | grep -E 'error:' | grep -vE 'unresolved reference'; then
        :
    else
        echo "  !! $NAME failed to compile"
        FAILED=$((FAILED + 1))
        rm -rf "$OUT"
        continue
    fi

    # Kotlin file-facade class for a top-level main() is the file name plus "Kt".
    if java -cp "$OUT/classes:$STDLIB" "${NAME}Kt" 2>/dev/null; then
        RAN=$((RAN + 1))
    else
        echo "  !! $NAME exited non-zero"
        FAILED=$((FAILED + 1))
    fi
    rm -rf "$OUT"
done

if [ "$RAN" -eq 0 ]; then
    die "no harness matched '${FILTER}' in $HARNESS_DIR"
fi

echo
if [ "$FAILED" -eq 0 ]; then
    echo "OK — $RAN harness(es) passed"
else
    echo "FAIL — $FAILED of $RAN harness(es) failed"
    exit 1
fi