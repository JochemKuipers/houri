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
MATCHED=0
FAILED=0
RAN=0

for MAIN in "$HARNESS_DIR"/*Harness.kt; do
    [ -e "$MAIN" ] || continue
    NAME=$(basename "$MAIN" .kt)
    if [ -n "$FILTER" ] && [[ "$NAME" != *"$FILTER"* ]]; then continue; fi
    MATCHED=$((MATCHED + 1))

    # Project sources the harnesses link against. Every one of these files is deliberately free of
    # Android imports so it compiles and runs on a plain JVM.
    SOURCES=()
    while IFS= read -r f; do SOURCES+=("$f"); done < <(
        find "$REPO_ROOT/core/archive/src/main/kotlin/mihon/core/archive" \
             "$REPO_ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/novel" \
             -name 'EpubPaths.kt' -o -name 'EpubBook.kt' \
             -o -name 'EpubFootnotes.kt' \
             -o -name 'NovelTypography.kt' -o -name 'NovelTypographyCss.kt' -o -name 'NovelProgress.kt' \
             -o -name 'NovelBuiltinFonts.kt' \
             -o -name 'NovelDocument.kt' -o -name 'NovelScrollBridge.kt' \
             -o -name 'NovelNavigation.kt' -o -name 'NovelReaderTheme.kt' \
             -o -name 'NovelContinuous.kt' -o -name 'NovelInline.kt' \
             -o -name 'NovelPageLayout.kt' \
             -o -name 'NovelPlainText.kt' -o -name 'NovelStatusLine.kt' -o -name 'NovelAutoScroll.kt' \
        2>/dev/null | sort -u
    )
    # EpubDoctype carries the XXE defence that EpubXmlReader applies, and EpubArchiveUrls is the URL
    # space two libraries now share. Both live in their own files precisely so they can be compiled
    # here directly rather than stubbed: a stub would be a *copy*, and a test that asserts the copy
    # proves nothing about the thing that runs.
    SOURCES+=("$REPO_ROOT/core/archive/src/main/kotlin/mihon/core/archive/EpubDoctype.kt")
    SOURCES+=("$REPO_ROOT/core/archive/src/main/kotlin/mihon/core/archive/EpubArchiveUrls.kt")
    # EpubMimeSniff is the second half of the resource-type answer: the extension table covers what a
    # publisher named correctly, and this covers the rest. Compiled directly, same reasoning as above.
    SOURCES+=("$REPO_ROOT/core/archive/src/main/kotlin/mihon/core/archive/EpubMimeSniff.kt")
    # EpubMarkup is the pure half of the book pipeline: text stripping, image stripping and the search
    # that runs over them. Deliberately its own file with no archive dependency, so it can be compiled
    # here directly. EpubTextSource cannot be - it needs EpubReader, and therefore the whole archive
    # chain - which is exactly why these transforms were split out of it.
    SOURCES+=("$REPO_ROOT/core/archive/src/main/kotlin/mihon/core/archive/EpubMarkup.kt")
    # LocalBookFolder is pure string handling on a name that is about to become a path segment, which
    # is exactly the kind of rule that is cheap to assert and expensive to discover on a device.
    SOURCES+=("$REPO_ROOT/source-local/src/commonMain/kotlin/tachiyomi/source/local/io/LocalBookFolder.kt")
    # ChapterCacheRetention is the pure math behind the chapter cache's sliding expiry and the
    # discovery screen's countdown. No Android imports, compiled directly for the same reason as
    # EpubDoctype above.
    SOURCES+=("$REPO_ROOT/app/src/main/java/eu/kanade/tachiyomi/data/cache/ChapterCacheRetention.kt")

    # The OPF metadata harness drives the real pull parser — kxml2, the library Android ships
    # behind `Xml.newPullParser` — so `EpubXmlReader`/`EpubOpfParser`/`EpubTocParser` can run on a
    # plain JVM. `OpfPlatformParser` stands in for `android.util.Xml` inside this harness only; the
    # offline stub in ../stubs throws and is never compiled here. The parser jar is needed both
    # when compiling (the org.xmlpull interfaces) and at runtime.
    OPF_SOURCES=()
    KXML_CP=""
    if [ "$NAME" = "OpfMetadataHarness" ]; then
        OPF_SOURCES+=("$REPO_ROOT/core/archive/src/main/kotlin/mihon/core/archive/EpubXmlReader.kt")
        OPF_SOURCES+=("$REPO_ROOT/core/archive/src/main/kotlin/mihon/core/archive/EpubOpfParser.kt")
        OPF_SOURCES+=("$REPO_ROOT/core/archive/src/main/kotlin/mihon/core/archive/EpubTocParser.kt")
        OPF_SOURCES+=("$HARNESS_DIR/OpfPlatformParser.kt")
        KXML_CP=":$(find_jar net.sf.kxml kxml2)"
    fi
    # The highlights model is pure but carries the framework's `@JavascriptInterface` marker on
    # its bridge; the harness gets the one-line stub rather than dragging Android in.
    if [ "$NAME" = "PlainTextHarness" ]; then
        SOURCES+=("$REPO_ROOT/scripts/kotlinc-check/stubs/android/webkit/JavascriptInterface.kt")
        SOURCES+=("$REPO_ROOT/app/src/main/java/eu/kanade/tachiyomi/ui/reader/viewer/novel/NovelHighlights.kt")
    fi
    # Only TextSearchHit: it is a plain data class with no serialization plugin and no SChapter, which
    # is what lets an offline check assert on a hit at all.
    SOURCES+=("$REPO_ROOT/source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source/text/TextSearchHit.kt")
    # NovelNavigation models the contents/search results, so it needs the two extension-API models it
    # is built from. Both are plain data classes: no serialization plugin, no Android, no native.
    # TextSearchHit deliberately lives in its own file rather than beside TextSearchSource - the
    # interface imports SChapter, which would drag the serialization plugin into every offline check
    # that wants to assert on a search hit.
    while IFS= read -r f; do SOURCES+=("$f"); done < <(
        find "$REPO_ROOT/source-api/src/commonMain/kotlin/eu/kanade/tachiyomi/source" \
             -name 'TocEntry.kt' -o -name 'TextSearchHit.kt' \
        2>/dev/null | sort -u
    )
    SOURCES+=("$MAIN")

    # The MTL spine harness compiles the mtl.* spine and needs kotlinx-coroutines on both
    # the compile and runtime classpaths — the spine's scheduler, lanes and flows are
    # coroutine-native, and runBlocking drives the suspend scenarios.
    # mtl/provider/** is excluded: those files need okhttp + serialization-json and are
    # covered by check.sh, not by this run-only harness.
    # BitmapPageImage (mtl/core) touches android.graphics.Bitmap, so the graphics stub rides
    # along — compiled but never instantiated; the harness scenarios use their own fake images.
    MTL_CP=""
    if [ "$NAME" = "MtlSpineHarness" ]; then
        while IFS= read -r f; do SOURCES+=("$f"); done < <(
            find "$REPO_ROOT/mtl-spine/src/main/kotlin/mtl" -name '*.kt' -not -path '*/provider/*' 2>/dev/null | sort -u
            find "$REPO_ROOT/scripts/kotlinc-check/stubs/android/graphics" -name '*.kt' 2>/dev/null
        )
        MTL_CP=":$COROUTINES"
    fi

    # The upscaling spine harness compiles the pure half of upscale.* — identity hashing, the
    # policy planner, the status hub — and drives its suspend scenarios with runBlocking.
    # Those four dirs import nothing outside kotlinx/java/upscale, so no stubs ride along.
    # native/** is excluded (JNI, Bitmap, probe), as are the store/sched files that need Android
    # types; those are covered by check.sh.
    UPSCALE_CP=""
    if [ "$NAME" = "UpscaleSpineHarness" ]; then
        while IFS= read -r f; do SOURCES+=("$f"); done < <(
            find "$REPO_ROOT/upscale/src/main/kotlin/upscale/core" \
                 "$REPO_ROOT/upscale/src/main/kotlin/upscale/identity" \
                 "$REPO_ROOT/upscale/src/main/kotlin/upscale/policy" \
                 "$REPO_ROOT/upscale/src/main/kotlin/upscale/status" \
                 -name '*.kt' 2>/dev/null | sort -u
        )
        UPSCALE_CP=":$COROUTINES"
    fi

    OUT=$(mktemp -d)
    echo "== $NAME =="
    # A hard compile error must fail the run, not fall through to "class not found".
    if ! java -cp "$COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
            -no-stdlib -nowarn -cp "$STDLIB$KXML_CP$MTL_CP$UPSCALE_CP" -d "$OUT/classes" "${SOURCES[@]}" "${OPF_SOURCES[@]}" "$MAIN" 2>&1 \
            | grep -E 'error:' | grep -vE 'unresolved reference'; then
        :
    else
        echo "  !! $NAME failed to compile"
        FAILED=$((FAILED + 1))
        rm -rf "$OUT"
        continue
    fi

    # Kotlin file-facade class for a top-level main() is the file name plus "Kt".
    if java -cp "$OUT/classes:$STDLIB$KXML_CP$MTL_CP$UPSCALE_CP" "${NAME}Kt" 2> "$OUT/run.err"; then
        RAN=$((RAN + 1))
    else
        # A runtime crash must name the crash: everything above succeeds and passes silently
        # until some environment the desktop JVM does not give us surfaces here (a parser
        # feature, a missing class), so the hidden stderr said "exited non-zero" and nothing else.
        echo "  !! $NAME exited non-zero"
        sed -n '1,20p' "$OUT/run.err"
        FAILED=$((FAILED + 1))
    fi
    rm -rf "$OUT"
done

if [ "$MATCHED" -eq 0 ]; then
    die "no harness matched '${FILTER}' in $HARNESS_DIR"
elif [ "$RAN" -eq 0 ]; then
    die "$MATCHED harness(es) matched '${FILTER}' but all failed — see FAIL lines above"
fi

echo
if [ "$FAILED" -eq 0 ]; then
    echo "OK — $RAN harness(es) passed"
else
    echo "FAIL — $FAILED of $RAN harness(es) failed"
    exit 1
fi
