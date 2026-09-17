#!/usr/bin/env bash
# Driver for building, installing, launching, and driving trace-demo on a
# local Android emulator. Run from Git Bash on Windows (uses .exe tools
# directly). See ../../../SKILL.md (i.e. .claude/skills/run-trace-demo/SKILL.md)
# for the full walkthrough — this script is the harness it points at.
#
# Usage: driver.sh <subcommand> [args...]
#   boot                          start/attach the AVD (waits for full boot)
#   build                         ./gradlew.bat :trace-demo:assembleDebug with JDK 17
#   install                       adb install -r the built debug APK
#   launch                        am start dev.trace.demo/.HomeActivity
#   dump <local-path>             uiautomator dump, pulled to <local-path>
#   tap-id <resource-id-suffix>   dump UI, find the view, tap its center
#   type <text>                   adb shell input text
#   swipe <x1> <y1> <x2> <y2> [ms]
#   key <keycode>                 e.g. `key 4` = BACK
#   screenshot <local-path>       adb exec-out screencap -p > <local-path>
#   pull-last-session <local-path>  pull the newest .trace.jsonl from the app's
#                                    external files dir; prints its filename
#   stop                          kill the emulator
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
SDK="${ANDROID_SDK_ROOT:-$(grep '^sdk.dir=' "$REPO_ROOT/local.properties" | cut -d= -f2-)}"
ADB="$SDK/platform-tools/adb.exe"
EMULATOR="$SDK/emulator/emulator.exe"
AVD_NAME="${AVD_NAME:-Pixel_9}"
PKG="dev.trace.demo"
APK="$REPO_ROOT/trace-demo/build/outputs/apk/debug/trace-demo-debug.apk"
JDK17="$(find "$HOME/.gradle/jdks" -maxdepth 1 -iname 'eclipse_adoptium-17*' 2>/dev/null | head -1)"

cmd_boot() {
  if "$ADB" devices | grep -q "device$"; then
    echo "device already attached"
    return 0
  fi
  nohup "$EMULATOR" -avd "$AVD_NAME" -no-snapshot -no-boot-anim >/tmp/trace-demo-emulator.log 2>&1 &
  disown
  echo "booting $AVD_NAME..."
  "$ADB" wait-for-device
  until "$ADB" shell getprop sys.boot_completed 2>/dev/null | grep -q 1; do
    sleep 5
  done
  echo "boot complete"
}

cmd_build() {
  cd "$REPO_ROOT"
  if [ -z "$JDK17" ]; then
    echo "no JDK 17 found under ~/.gradle/jdks; run a build once so the" >&2
    echo "foojay toolchain resolver provisions one, or set JDK17 env var" >&2
    return 1
  fi
  ./gradlew.bat -Dorg.gradle.java.home="$JDK17" :trace-demo:assembleDebug --console=plain
}

cmd_install() {
  "$ADB" install -r "$APK"
}

cmd_launch() {
  "$ADB" shell am start -n "$PKG/.HomeActivity"
  sleep 2
}

cmd_dump() {
  local out="${1:?usage: dump <local-path>}"
  "$ADB" shell uiautomator dump //sdcard/window_dump.xml >/dev/null
  "$ADB" pull //sdcard/window_dump.xml "$out" >/dev/null
}

# Prints "cx cy" — center of the first element whose resource-id ends with
# $1, read from the dump file at $2.
bounds_center() {
  local id="$1" dumpfile="$2"
  grep -oE "resource-id=\"[^\"]*${id}\"[^>]*bounds=\"\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]\"" "$dumpfile" \
    | grep -oE '\[[0-9]+,[0-9]+\]\[[0-9]+,[0-9]+\]' | head -1 \
    | sed -E 's/\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]/\1 \2 \3 \4/' \
    | awk '{printf "%d %d", int(($1+$3)/2), int(($2+$4)/2)}'
}

cmd_tap_id() {
  local id="${1:?usage: tap-id <resource-id-suffix>}"
  local tmp
  tmp="$(mktemp)"
  cmd_dump "$tmp"
  local coords
  coords="$(bounds_center "$id" "$tmp")"
  rm -f "$tmp"
  [ -n "$coords" ] || { echo "resource-id not found on screen: $id" >&2; return 1; }
  "$ADB" shell input tap $coords
}

cmd_type() { "$ADB" shell input text "$1"; }
cmd_swipe() { "$ADB" shell input swipe "$@"; }
cmd_key() { "$ADB" shell input keyevent "$1"; }

cmd_screenshot() {
  local out="${1:?usage: screenshot <local-path>}"
  "$ADB" exec-out screencap -p > "$out"
}

cmd_pull_last_session() {
  local out="${1:?usage: pull-last-session <local-path>}"
  local remote_dir="/storage/emulated/0/Android/data/$PKG/files/traces"
  local latest
  latest="$("$ADB" shell "ls -t $remote_dir" | tr -d '\r' | head -1)"
  [ -n "$latest" ] || { echo "no session file found under $remote_dir" >&2; return 1; }
  "$ADB" pull "/${remote_dir#/}/$latest" "$out" 2>/dev/null || \
    "$ADB" pull "/$remote_dir/$latest" "$out" 2>/dev/null || \
    "$ADB" pull "$(printf '/%s' "$remote_dir")/$latest" "$out"
  echo "$latest"
}

cmd_stop() {
  "$ADB" emu kill || true
}

sub="${1:-}"
[ $# -gt 0 ] && shift
case "$sub" in
  boot) cmd_boot ;;
  build) cmd_build ;;
  install) cmd_install ;;
  launch) cmd_launch ;;
  dump) cmd_dump "$@" ;;
  tap-id) cmd_tap_id "$@" ;;
  type) cmd_type "$@" ;;
  swipe) cmd_swipe "$@" ;;
  key) cmd_key "$@" ;;
  screenshot) cmd_screenshot "$@" ;;
  pull-last-session) cmd_pull_last_session "$@" ;;
  stop) cmd_stop ;;
  *)
    echo "usage: driver.sh {boot|build|install|launch|dump|tap-id|type|swipe|key|screenshot|pull-last-session|stop}" >&2
    exit 1
    ;;
esac
