#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# kotlinc-check — fast offline Kotlin syntax/reference check.
#
# WHAT THIS IS FOR
#   The root AGENTS.md forbids agents from running ./gradlew. That rule is
#   right for anything that needs the Android SDK, R8, or the full dependency
#   graph, but it leaves a real gap: a few hundred lines of new Kotlin with no
#   compiler feedback at all. Typos, JVM signature clashes, `children::add` on
#   an immutable list, and a receiver type mismatch all reach CI otherwise.
#
#   This script closes that gap without Gradle. It drives the Kotlin compiler
#   straight out of the Gradle dependency cache, so it needs no network and no
#   SDK, and it compiles the files you point it at in about a second.
#
# WHAT IT IS NOT
#   Not a replacement for the build. It has no Android SDK, no Compose compiler
#   plugin, and no project classpath, so anything it says about unresolved
#   references is noise. It also does NOT run spotless or ktlint.
#   It catches: syntax errors, JVM declaration clashes, intra-module type
#   errors, and anything a pure function can be asserted against.
#   Final verification is still `./gradlew spotlessApply spotlessCheck
#   assembleDebug`, and that stays the user's to run.
#
# USAGE
#   scripts/kotlinc-check/check.sh <file-or-dir>...
#   scripts/kotlinc-check/check.sh core/archive/src/main/kotlin/mihon/core/archive/Epub*.kt
#
# SIBLING TOOLS IN THIS DIRECTORY
#   run-harness.sh    asserts pure logic against real project sources
#   check_imports.py  catches a used-but-unimported project symbol — the one class of
#                     mistake nothing here can see without a full Gradle build
#
#   Compiles with stubs for dependencies that are not in the local Gradle cache
#   (jsoup, libarchive, android.util.Xml, org.xmlpull). Add a stub under
#   stubs/ when a new external class is needed — keep stubs minimal and
#   obviously fake; they are never compiled into the app.
#
# WHY THE STUBS EXIST
#   jsoup and libarchive come from jitpack/Maven and are not always cached, and
#   android.util.Xml does not exist off-device. Without stubs the real files
#   cannot resolve their own imports and every error cascades into noise that
#   hides the genuine one.
# ---------------------------------------------------------------------------
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STUB_DIR="$REPO_ROOT/scripts/kotlinc-check/stubs"
CACHE="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"

die() { echo "kotlinc-check: $*" >&2; exit 1; }

# --- locate the compiler in the Gradle cache --------------------------------
find_jar() {
    # $1 = group, $2 = artifact prefix, $3 = optional version filter
    local jar
    jar=$(find "$CACHE/$1/$2" -name '*.jar' 2>/dev/null \
          | grep -v -- '-sources' | grep -v -- '-javadoc' \
          | { if [ -n "${3:-}" ]; then grep -- "$3"; else cat; fi; } \
          | sort -V | tail -1)
    [ -n "$jar" ] || die "could not find $1:$2 in the Gradle cache at $CACHE"
    echo "$jar"
}

COMPILER=$(find_jar org.jetbrains.kotlin kotlin-compiler-embeddable)
STDLIB=$(find_jar org.jetbrains.kotlin kotlin-stdlib)
REFLECT=$(find_jar org.jetbrains.kotlin kotlin-reflect)
COROUTINES=$(find_jar org.jetbrains.kotlinx kotlinx-coroutines-core-jvm)
ANNOTATIONS=$(find_jar org.jetbrains annotations)

# kotlin-compiler-embeddable needs these on its OWN classpath, not the -cp of
# the code being compiled. Missing reflect/coroutines makes the CLI die with an
# opaque NoClassDefFoundError before it reads a single file.
COMPILER_CP="$COMPILER:$STDLIB:$REFLECT:$COROUTINES:$ANNOTATIONS"

[ $# -gt 0 ] || die "no input files given; pass at least one .kt file or directory"

OUT=$(mktemp -d)
trap 'rm -rf "$OUT"' EXIT

STUBS=$(find "$STUB_DIR" -name '*.kt' 2>/dev/null)
[ -n "$STUBS" ] || die "no stubs found in $STUB_DIR"

# Real jars that are cached and worth linking: kotlinx-serialization for @Serializable on the
# extension API models, metro-common for @Inject/@SingleIn on DI-annotated classes, okhttp/okio
# for the mtl.* providers' HTTP layer. Entries are dir:artifact[:kind]; kind "aar" extracts
# classes.jar out of the cached .aar (logcat for the spine's logcat{} calls, onnxruntime for
# upscale/native/OrtUpscaleSession.kt).
aar_classes() {
    local aar="$1"
    local out="$OUT/$(basename "${aar%.aar}").jar"
    unzip -o -q "$aar" classes.jar -d "$OUT" 2>/dev/null || return 0
    mv "$OUT/classes.jar" "$out" 2>/dev/null || return 0
    echo "$out"
}

EXTRA_CP=""
for spec in "org.jetbrains.kotlinx/kotlinx-serialization-core-jvm:kotlinx-serialization-core" \
            "org.jetbrains.kotlinx/kotlinx-serialization-json-jvm:kotlinx-serialization-json" \
            "dev.zacsweers.metro/metro-common:metro-common" \
            "dev.zacsweers.metro/runtime-jvm:metro-runtime" \
            "com.squareup.okhttp3/okhttp-jvm:okht" \
            "com.squareup.okio/okio-jvm:okio-jvm" \
            "com.microsoft.onnxruntime/onnxruntime-android:onnxruntime:aar" \
            "org.junit.jupiter/junit-jupiter-api:junit-jupiter-api" \
            "org.junit.platform/junit-platform-commons:junit-platform-commons" \
            "io.mockk/mockk-jvm:mockk-jvm" \
            "io.mockk/mockk-core-jvm:mockk-core" \
            "io.mockk/mockk-dsl-jvm:mockk-dsl" \
            "io.mockk/mockk-agent-jvm:mockk-agent" \
            "io.mockk/mockk-agent-api-jvm:mockk-agent-api" \
            "org.objenesis/objenesis:objenesis" \
            "io.kotest/kotest-assertions-core-jvm:kotest-assertions-core" \
            "io.kotest/kotest-assertions-shared-jvm:kotest-assertions-shared" \
            "io.kotest/kotest-common-jvm:kotest-common" \
            "org.jetbrains.kotlinx/kotlinx-coroutines-test-jvm:kotlinx-coroutines-test"; do
    dir="${spec%%:*}"; rest="${spec#*:}"; name="${rest%%:*}"; kind="${rest#*:}"
    if [ "$kind" = "aar" ]; then
        aar=$(find "$CACHE/$dir" -name "*.aar" 2>/dev/null | grep -v -- '-sources' | sort -V | tail -1)
        [ -n "$aar" ] || continue
        jar=$(aar_classes "$aar")
    else
        jar=$(find "$CACHE/$dir" -name "*.jar" 2>/dev/null | grep -v -- '-sources' | sort -V | tail -1)
    fi
    [ -n "$jar" ] && EXTRA_CP="$EXTRA_CP:$jar"
done

# -nowarn keeps the output readable; grep then drops the residual
# unresolved-reference cascade that comes from a missing project classpath.
# kotlinx-coroutines is on the compile classpath too: Flow/Semaphore/withTimeout are
# spine-level dependencies (mtl/*), and without it every suspend-shaped call in a checked
# file cascades into fake "suspension functions can only be called within coroutine body"
# errors that bury the real ones.
java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -nowarn \
    -cp "$STDLIB:$COROUTINES$EXTRA_CP" \
    -d "$OUT/classes" \
    $STUBS \
    "$@" 2>&1 \
  | grep -viE '^WARNING|^\s+at |terminally deprecated|sun\.misc|Unsafe::|^\s*$' \
  | grep -E 'error:' \
  | grep -vE 'unresolved reference|cannot access' \
  > "$OUT/errors.txt"

if [ -s "$OUT/errors.txt" ]; then
    echo "FAIL — errors:"
    cat "$OUT/errors.txt"
    exit 1
fi

# Surface unresolved references as a warning, never a failure: most of them are
# the absent project classpath, but a handful can be real (a renamed symbol in
# a sibling file). Counting them keeps that visible without blocking on it.
ALL=$(java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -nowarn -cp "$STDLIB$COROUTINES$EXTRA_CP" -d "$OUT/classes" $STUBS "$@" 2>&1 \
      | grep -cE 'error:')
UNRESOLVED=$(java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
        -no-stdlib -nowarn -cp "$STDLIB$COROUTINES$EXTRA_CP" -d "$OUT/classes" $STUBS "$@" 2>&1 \
      | grep -cE 'unresolved reference')

echo "OK — no hard errors (total diagnostics: $ALL, mostly unresolved refs from the absent project classpath: $UNRESOLVED)"