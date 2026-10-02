#!/usr/bin/env bash
# CI emulator driver for Morsel.
#
# Phase 1: full instrumented suite via gradle (AGP uninstalls the app
#          afterwards, so nothing after this phase may assume gradle state;
#          in-app screenshots survive via the /data/local/tmp mirror that
#          Screenshots.kt writes during every capture).
# Phase 2: visual evidence (light/dark/large-font/Dutch/IME/reduced-motion)
#          via adb, on a demo-mode card. Setup failures fail the phase —
#          screenshots of the launcher are never accepted as evidence.
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

# The Morsel card must own the foreground before any screenshot is evidence;
# a capture of the launcher means a setup step failed and is reported as such.
wait_morsel_foreground() {
    for _ in $(seq 1 30); do
        if adb shell dumpsys window 2>/dev/null | tr -d '\r' | grep -q "mCurrentFocus=.*io\.evren\.morsel"; then
            return 0
        fi
        sleep 0.5
    done
    echo "FAIL: io.evren.morsel never reached the foreground" >&2
    return 1
}

# A screenshot that produced no data must never pass as evidence.
screenshot() {
    local out="$1"
    adb exec-out screencap -p > "$out"
    if [ ! -s "$out" ]; then
        echo "FAIL: screencap produced no data for $out" >&2
        return 1
    fi
    return 0
}

# The logcat carries the MORSEL_SHOT screenshot mirror (see Screenshots.kt);
# the default ~256KB buffer would rotate it away within seconds, and even 8MB
# can rotate during a long run, so go big and ALSO dump incrementally per
# phase (the decoder deduplicates overlapping entries).
adb logcat -G 16M || overall=1
# Stale evidence from earlier runs on the same emulator must never mix in.
adb shell rm -rf /data/local/tmp/morsel-screens || true

# Phase 1: full suite (process-death pair, visual seed and visual-only
# classes run in their own phases below so a real restart is never masked by
# a gradle reinstall).
if [ -n "${MORSEL_TEST_CMD:-}" ]; then
    if ! bash -c "$MORSEL_TEST_CMD"; then
        overall=1
    fi
else
    if ! ./gradlew --no-daemon connectedDebugAndroidTest \
        -Pandroid.testInstrumentationRunnerArguments.notClass=io.evren.morsel.ProcessDeathVerifyTest,io.evren.morsel.ProcessDeathSetupTest,io.evren.morsel.VisualSetupTest,io.evren.morsel.ImeVisualTest,io.evren.morsel.SelectionVisualTest,io.evren.morsel.DutchVisualTest; then
        overall=1
    fi
fi

# The APKs are needed for every adb-driven phase; gradle may have uninstalled
# them after its run.
adb install -r "$APP_APK" || overall=1
adb install -r "$TEST_APK" || overall=1

# Snapshot the logcat now: phase 1's mirror entries must survive later phases.
adb logcat -d > connected-logcat-phase1.txt || overall=1

# Phase 2: visual evidence (opt-in, API 36 job). The Dutch locale, nonzero
# selection and IME evidence come from instrumentation classes that ASSERT the
# expected screen before capturing; the shell shots cover light/dark/font/
# reduced-motion with a foreground check before each capture.
if [ "${MORSEL_VISUAL:-0}" = "1" ]; then
    mkdir -p morsel-screens-host
    visual_ok=1
    if ! run_class io.evren.morsel.VisualSetupTest instrument-visual.log; then
        visual_ok=0
    fi
    if ! run_class io.evren.morsel.ImeVisualTest instrument-ime.log; then
        visual_ok=0
    fi
    if ! run_class io.evren.morsel.SelectionVisualTest instrument-selection.log; then
        visual_ok=0
    fi
    if ! run_class io.evren.morsel.DutchVisualTest instrument-dutch.log; then
        visual_ok=0
    fi

    if ! adb shell am start -n io.evren.morsel/.FeedPopupActivity; then
        echo "FAIL: am start for the visual block" >&2
        visual_ok=0
    fi
    if ! wait_morsel_foreground; then
        visual_ok=0
    fi

    screenshot morsel-screens-host/light-card.png || visual_ok=0

    if ! adb shell cmd uimode night yes; then
        echo "FAIL: could not switch to dark mode" >&2
        visual_ok=0
    fi
    sleep 2
    wait_morsel_foreground || visual_ok=0
    screenshot morsel-screens-host/dark-card.png || visual_ok=0

    if ! adb shell cmd uimode night no; then
        echo "FAIL: could not switch back to light mode" >&2
        visual_ok=0
    fi
    if ! adb shell settings put system font_scale 2.0; then
        echo "FAIL: could not set 2x font scale" >&2
        visual_ok=0
    fi
    sleep 2
    wait_morsel_foreground || visual_ok=0
    screenshot morsel-screens-host/large-font-card.png || visual_ok=0

    if ! adb shell settings put system font_scale 1.0; then
        echo "FAIL: could not reset font scale" >&2
        visual_ok=0
    fi
    if ! adb shell settings put global animator_duration_scale 0; then
        echo "FAIL: could not disable animations" >&2
        visual_ok=0
    fi
    sleep 1
    wait_morsel_foreground || visual_ok=0
    screenshot morsel-screens-host/reduced-motion-card.png || visual_ok=0
    if ! adb shell settings put global animator_duration_scale 1.0; then
        echo "FAIL: could not re-enable animations" >&2
        visual_ok=0
    fi
    if [ "$visual_ok" = "0" ]; then
        overall=1
    fi
fi

# Phases 3-5: process death with a REAL restart.
mkdir -p morsel-screens-host
if run_class io.evren.morsel.ProcessDeathSetupTest instrument-setup.log; then
    if ! adb shell am force-stop io.evren.morsel; then
        echo "FAIL: could not force-stop the app" >&2
        overall=1
    fi
    if ! adb shell am start -n io.evren.morsel/.FeedPopupActivity; then
        echo "FAIL: could not relaunch the app after force-stop" >&2
        overall=1
    fi
    sleep 3
    wait_morsel_foreground || overall=1
    screenshot morsel-screens-host/process-death-relaunch.png || overall=1
    if ! run_class io.evren.morsel.ProcessDeathVerifyTest instrument-verify.log; then
        overall=1
    fi
else
    overall=1
fi

# Evidence collection (always; never masks the test result).
adb logcat -d > connected-logcat.txt || overall=1
screenshot emulator-final.png || overall=1

# In-app screenshots arrive through the MORSEL_SHOT logcat mirror (works even
# for the gradle phase, whose APK is uninstalled and wiped afterwards); the
# run-as pull covers anything that only exists after a later reinstall.
mkdir -p morsel-screens
for name in connected-logcat.txt connected-logcat-phase1.txt; do
    [ -f "$name" ] || continue
    python3 - "$name" <<'PYEOF' || overall=1
import base64
import pathlib
import re
import sys

shots = {}
begin = re.compile(r"BEGIN:([A-Za-z0-9._-]+):(\d+)$")
chunk = re.compile(r"CHUNK:([A-Za-z0-9._-]+):(\d+):([A-Za-z0-9+/=]+)$")
for line in open(sys.argv[1], encoding="utf-8", errors="replace"):
    if "MORSEL_SHOT:" not in line:
        continue
    body = line.split("MORSEL_SHOT:", 1)[1].strip()
    b = begin.match(body)
    c = chunk.match(body)
    if b:
        shots[b.group(1)] = [""] * int(b.group(2))
    elif c and c.group(1) in shots:
        i = int(c.group(2))
        if i < len(shots[c.group(1)]):
            shots[c.group(1)][i] = c.group(3)
    # END markers need no action: the shot is complete once all chunks arrived.
out = pathlib.Path("morsel-screens")
written = 0
incomplete = 0
for name, parts in shots.items():
    target = out / f"{name}.png"
    if not parts or any(p == "" for p in parts):
        if not target.exists():
            incomplete += 1
            print(f"FAIL: incomplete logcat mirror for {name}", file=sys.stderr)
        continue
    data = base64.b64decode("".join(parts))
    if not target.exists() or target.stat().st_size != len(data):
        target.write_bytes(data)
    written += 1
print(f"logcat mirror [{sys.argv[1]}]: {written} complete, {incomplete} new incomplete")
if incomplete:
    sys.exit(1)
if not shots:
    print(f"FAIL: no MORSEL_SHOT mirror entries in {sys.argv[1]}", file=sys.stderr)
    sys.exit(1)
sys.exit(0)
PYEOF
done
if adb shell run-as io.evren.morsel ls files/morsel-screens > shots.list 2> shots-err.txt; then
    tr -d '\r' < shots.list | while IFS= read -r f; do
        [ -n "$f" ] || continue
        [ -s "morsel-screens/$f" ] && continue
        adb shell "run-as io.evren.morsel cat 'files/morsel-screens/$f'" > "morsel-screens/$f" || true
    done
else
    echo "note: no in-app screenshot listing (run-as): $(cat shots-err.txt)" >&2
fi
ls -la morsel-screens/ morsel-screens-host/ >&2 || true

exit "$overall"
