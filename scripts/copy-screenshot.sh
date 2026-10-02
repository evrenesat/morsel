#!/bin/sh
# Durable screenshot copy for Morsel instrumented evidence.
#
# Pushed to /data/local/tmp/morsel-copy-screenshot.sh by scripts/ci-emulator.sh
# and executed from the app via UiAutomation.executeShellCommand as
#   sh /data/local/tmp/morsel-copy-screenshot.sh PACKAGE SAFE EXPECTED
# UiAutomationConnection on API 30 runs the command through Runtime.exec —
# whitespace tokenization, NO shell parsing — so quotes/redirects in an inline
# command are unusable; this script file IS the shell logic. Running as the
# shell uid it owns /data/local/tmp, while `run-as` reads the app-private
# capture: the copy survives gradle's post-suite uninstall.
#
# Validates the copy at the exact nonzero byte count, records it in
# sizes.list for the host gate, and prints the verified size (empty output
# on any failure — the caller treats anything but EXPECTED as a failure).
set -u

pkg=$1
safe=$2
expected=$3

dir=/data/local/tmp/morsel-screens
mkdir -p "$dir" || exit 1
run-as "$pkg" cat "files/morsel-screens/$safe.png" > "$dir/$safe.png" || exit 1
size=$(wc -c < "$dir/$safe.png" | tr -d '[:space:]')
if [ -z "$size" ] || [ "$size" -ne "$expected" ] || [ "$size" -eq 0 ]; then
    rm -f "$dir/$safe.png"
    exit 1
fi
echo "$safe.png $size" >> "$dir/sizes.list"
echo "$size"
