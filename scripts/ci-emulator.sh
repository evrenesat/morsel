#!/usr/bin/env bash
# CI emulator driver for Morsel.
#
# Phase 1: full instrumented suite via gradle (AGP uninstalls the app
#          afterwards, so nothing after this phase may assume gradle state).
# Phase 2: visual evidence (light/dark/large-font/Dutch/reduced-motion) via
#          adb, on a demo-mode card.
# Phases 3-5: process death — seed a persisted pending operation through the
#          app's own journal, force-stop the app, relaunch it, and verify in
#          the fresh process that the restored UNKNOWN state blocks resending.
#          These phases use direct `adb shell am instrument` because gradle
#          would reinstall/reseed and mask a real restart.
# Finally: logcat, screenshots and in-app captures are collected ALWAYS, and
# the script exits nonzero if ANY phase failed.
#
# This script must be invoked as the ONE emulator-runner script command, e.g.
#   bash scripts/ci-emulator.sh
# All state (exit statuses) lives inside this single shell process.
#
# MORSEL_TEST_CMD overrides the phase-1 test command for local shell testing
# of the wrapper itself; it is never set in CI.
set -u

cd "$(dirname "$0")/.." || exit 2

overall=0
APP_APK="app/build/outputs/apk/debug/app-debug.apk"
TEST_APK="app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
RUNNER="io.evren.morsel.test/androidx.test.runner.AndroidJUnitRunner"

run_class() {
    # Runs one instrumentation class directly on the device. Success is the
    # "OK (N tests)" summary am instrument prints; anything else fails.
    local class_name="$1" log_name="$2"
    adb shell am instrument -w -e class "$class_name" "$RUNNER" > "$log_name" 2>&1
    if ! grep -q "^OK (" "$log_name"; then
        echo "FAIL: instrumentation $class_name did not pass:" >&2
        tail -5 "$log_name" >&2
        return 1
    fi
    return 0
}

# Phase 1: full suite (process-death pair and visual seed run in their own
# phases below so a real restart is never masked by a gradle reinstall).
if [ -n "${MORSEL_TEST_CMD:-}" ]; then
    if ! bash -c "$MORSEL_TEST_CMD"; then
        overall=1
    fi
else
    if ! ./gradlew --no-daemon connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.notClass=io.evren.morsel.ProcessDeathVerifyTest,io.evren.morsel.ProcessDeathSetupTest,io.evren.morsel.VisualSetupTest; then
        overall=1
    fi
fi

# The APKs are needed for every adb-driven phase; gradle may have uninstalled
# them after its run.
adb install -r "$APP_APK" || overall=1
adb install -r "$TEST_APK" || overall=1

# Phase 2: visual evidence on a demo-mode card (opt-in, API 36 job).
if [ "${MORSEL_VISUAL:-0}" = "1" ]; then
    mkdir -p morsel-screens-host
    shot() {
        adb exec-out screencap -p > "morsel-screens-host/$1.png" || true
    }
    if run_class io.evren.morsel.VisualSetupTest instrument-visual.log; then
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
    else
        overall=1
    fi
fi

# Phases 3-5: process death with a REAL restart.
if run_class io.evren.morsel.ProcessDeathSetupTest instrument-setup.log; then
    adb shell am force-stop io.evren.morsel || true
    adb shell am start -n io.evren.morsel/.FeedPopupActivity || true
    sleep 3
    adb exec-out screencap -p > morsel-screens-host/process-death-relaunch.png 2>/dev/null ||
        true
    if ! run_class io.evren.morsel.ProcessDeathVerifyTest instrument-verify.log; then
        overall=1
    fi
else
    overall=1
fi

# Evidence collection (always; never masks the test result).
adb logcat -d > connected-logcat.txt || true
adb exec-out screencap -p > emulator-final.png || true

# In-app screenshots (success and failure captures, see Screenshots.kt) live
# in the app's internal files dir; pull them file-by-file with run-as.
mkdir -p morsel-screens
if adb shell run-as io.evren.morsel ls files/morsel-screens > shots.list 2> shots-err.txt; then
    tr -d '\r' < shots.list | while IFS= read -r f; do
        [ -n "$f" ] || continue
        adb shell "run-as io.evren.morsel cat 'files/morsel-screens/$f'" > "morsel-screens/$f" || true
    done
else
    echo "note: no in-app screenshot listing (run-as): $(cat shots-err.txt)" || true
fi

exit "$overall"
