#!/usr/bin/env bash
# CI emulator driver for Morsel.
#
# Runs the full instrumented suite, then the two-phase process-death check
# (seed a persisted pending operation, force-stop the app, relaunch, verify
# the restored UNKNOWN blocks resending), then always collects logcat and
# screenshots. Exits nonzero if ANY phase failed.
#
# This script must be invoked as the ONE emulator-runner script command, e.g.
#   bash scripts/ci-emulator.sh
# All state (gradle exit status) lives inside this single shell process.
#
# MORSEL_TEST_CMD overrides the test command for local shell testing of the
# wrapper itself (must be removed-free: unset in CI).
set -u

cd "$(dirname "$0")/.." || exit 2

overall=0

run_tests() {
    # Runs one connected-test invocation; never lets evidence collection mask
    # the test result.
    if [ -n "${MORSEL_TEST_CMD:-}" ]; then
        bash -c "$MORSEL_TEST_CMD"
    else
        ./gradlew --no-daemon connectedDebugAndroidTest "$@"
    fi
}

# Phase 1: full suite (process-death pair runs in its own phases below).
run_tests -Pandroid.testInstrumentationRunnerArguments.notClass=io.evren.morsel.ProcessDeathVerifyTest,io.evren.morsel.ProcessDeathSetupTest ||
    overall=1

# Visual evidence block (opt-in for the API 36 job): capture the card in
# light, dark, large-font, reduced-motion and Dutch using the state the main
# suite left behind. Every step is best-effort; failures never mask tests.
if [ "${MORSEL_VISUAL:-0}" = "1" ]; then
    mkdir -p morsel-screens-host
    shot() {
        adb exec-out screencap -p > "morsel-screens-host/$1.png" || true
    }
    adb shell am force-stop io.evren.morsel || true
    adb shell am start -n io.evren.morsel/.FeedPopupActivity || true
    sleep 3
    shot light-card
    adb shell cmd uimode night yes || true
    sleep 2
    shot dark-card
    adb shell cmd uimode night no || true
    sleep 2
    adb shell settings put system font_scale 2.0 || true
    sleep 2
    shot large-font-card
    adb shell settings put system font_scale 1.0 || true
    sleep 2
    adb shell settings put global animator_duration_scale 0 || true
    sleep 1
    shot reduced-motion-card
    adb shell settings put global animator_duration_scale 1.0 || true
    adb shell cmd locale set-locales nl-NL || true
    sleep 3
    shot dutch-card
    adb shell cmd locale set-locales en-US || true
    sleep 1
fi

# Phases 2-4: process death. Seed through the app's own journal, force-stop,
# relaunch, verify the restored UNKNOWN state in the fresh process.
run_tests -Pandroid.testInstrumentationRunnerArguments.class=io.evren.morsel.ProcessDeathSetupTest ||
    overall=1
adb shell am force-stop io.evren.morsel || true
adb shell am start -n io.evren.morsel/.FeedPopupActivity || true
sleep 3
run_tests -Pandroid.testInstrumentationRunnerArguments.class=io.evren.morsel.ProcessDeathVerifyTest ||
    overall=1

# Evidence collection (always; never masks the test result).
adb logcat -d > connected-logcat.txt || true
adb exec-out screencap -p > emulator-final.png || true

# Screenshots live in the app's internal files dir (see Screenshots.kt);
# run-as works for debug builds on all supported API levels.
mkdir -p morsel-screens
adb exec-out run-as io.evren.morsel tar -cf - files/morsel-screens 2>/dev/null |
    tar -xf - -C morsel-screens --strip-components=2 || true
# Fallback for external-files screenshots from older versions.
adb pull /sdcard/Android/data/io.evren.morsel/files/morsel-screens morsel-screens-external >/dev/null 2>&1 || true

exit "$overall"
