#!/usr/bin/env bash
# Release health check: install the ALREADY-DOWNLOADED AND VERIFIED published
# APK (published/ dir, fetched by the release workflow), launch it, and prove
# the process stays alive without a fatal crash. Single shell process so the
# test status can never be lost.
set -u

cd "$(dirname "$0")/.." || exit 2

status=0

adb install -r published/app-release.apk || status=1
adb logcat -c || true
adb shell am start -n io.evren.morsel/.FeedPopupActivity || status=1
sleep 8

PID="$(adb shell pidof io.evren.morsel | tr -d ' \r')"
if [ -z "$PID" ]; then
    echo "FAIL: Morsel process is not running after launch" >&2
    status=1
else
    echo "Morsel running with pid $PID"
fi

adb logcat -d > health-logcat.txt || true
if grep -q "Process: io\.evren\.morsel" health-logcat.txt; then
    echo "FAIL: crash record for io.evren.morsel found in logcat" >&2
    status=1
fi

adb exec-out screencap -p > published-launch.png || true

exit "$status"
