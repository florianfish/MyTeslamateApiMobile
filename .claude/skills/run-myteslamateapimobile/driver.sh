#!/usr/bin/env bash
# Pilote de l'appli dans l'émulateur Android (Git Bash sous Windows).
# Usage : driver.sh <commande> [args]   — voir SKILL.md pour la liste.
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
SDK="${ANDROID_HOME:-${LOCALAPPDATA:-}/Android/Sdk}"
export ANDROID_HOME="$SDK" ANDROID_SDK_ROOT="$SDK"
AVD="${AVD:-teslamate}"
IMAGE="system-images;android-34;default;x86_64"
PKG="fr.florian.teslamate.debug"
APK="$ROOT/app/build/outputs/apk/debug/app-debug.apk"
OUT="${OUT:-$ROOT/app/build/run}"          # captures + logs (gitignoré)
EMU="$SDK/emulator/emulator.exe"
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager.bat"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager.bat"
mkdir -p "$OUT"

# Shells lancés avant l'install ne voient pas JAVA_HOME : relire la variable utilisateur.
if [ -z "${JAVA_HOME:-}" ]; then
  JAVA_HOME="$(powershell -NoProfile -Command "[Environment]::GetEnvironmentVariable('JAVA_HOME','User')" | tr -d '\r')"
  export JAVA_HOME
fi
# MSYS_NO_PATHCONV : sinon Git Bash réécrit /sdcard/x en C:/Program Files/Git/sdcard/x.
# Du coup les chemins locaux passés à adb doivent être convertis avec `cygpath -w`.
adb() { MSYS_NO_PATHCONV=1 "$SDK/platform-tools/adb.exe" "$@"; }

booted() { [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ]; }

# Dump uiautomator -> centre "x y" du premier nœud dont text ou content-desc contient $1.
find_node() {
  # Juste après un lancement, uiautomator répond souvent « null root node » : on réessaie.
  for _ in 1 2 3 4 5; do
    adb shell uiautomator dump /sdcard/ui.xml 2>&1 | grep -q "dumped to" && break; sleep 1
  done
  adb exec-out cat /sdcard/ui.xml > "$OUT/ui.xml"
  tr '>' '\n' < "$OUT/ui.xml" \
    | grep -E "(text|content-desc)=\"[^\"]*$1[^\"]*\"" | head -1 \
    | sed -E 's/.*bounds="\[([0-9]+),([0-9]+)\]\[([0-9]+),([0-9]+)\]".*/\1 \2 \3 \4/' \
    | awk '{ if (NF==4) print int(($1+$3)/2), int(($2+$4)/2) }' || true   # pas de match ≠ erreur (pipefail)
}

cmd="${1:-help}"; shift || true
case "$cmd" in
  setup)   # émulateur + image + AVD (une fois, ~2 Go)
    yes | "$SDKMANAGER" --sdk_root="$SDK" emulator "$IMAGE" | tail -2
    if ! "$EMU" -list-avds | tr -d '\r' | grep -qx "$AVD"; then
      echo no | "$AVDMANAGER" create avd -n "$AVD" -k "$IMAGE" -d pixel_6 | tail -1
    fi
    "$EMU" -list-avds ;;
  boot)    # démarre l'émulateur (fenêtre visible) et attend la fin du boot
    if ! booted; then
      nohup "$EMU" -avd "$AVD" -no-snapshot-save -no-audio -no-boot-anim > "$OUT/emulator.log" 2>&1 &
      adb wait-for-device
      for _ in $(seq 1 150); do booted && break; sleep 2; done
    fi
    booted && echo "booted" || { echo "boot échoué, voir $OUT/emulator.log"; exit 1; } ;;
  build)   (cd "$ROOT" && gradle assembleDebug -q) && ls -la "$APK" ;;
  install) adb install -r "$(cygpath -w "$APK")" ;;      # -r garde les données (accès saisis)
  launch)  adb shell am start -W -n "$PKG/fr.florian.teslamate.MainActivity" | grep -E "Status|Error" ;;
  ss)      # capture d'écran -> chemin affiché
    f="$OUT/${1:-screen}.png"; adb exec-out screencap -p > "$f"; echo "$f" ;;
  tap)     adb shell input tap "$1" "$2" ;;
  tapt)    # tape sur l'élément dont le texte contient $1 (ex. tapt Trajets)
    xy="$(find_node "$1")"; [ -n "$xy" ] || { echo "introuvable : $1"; exit 1; }
    adb shell input tap $xy; echo "tap $1 @ $xy" ;;
  scroll)  # scroll [down|up] : glisser vertical au centre de l'écran (1080x2400)
    if [ "${1:-down}" = up ]; then adb shell input swipe 540 700 540 1700 300
    else adb shell input swipe 540 1700 540 700 300; fi ;;
  text)    adb shell input text "$(printf '%s' "$1" | sed 's/ /%s/g')" ;;
  key)     adb shell input keyevent "$1" ;;    # BACK, ENTER, HOME…
  ui)      find_node "${1:-xx_aucun_xx}" >/dev/null || true
           tr '>' '\n' < "$OUT/ui.xml" | grep -oE '(text|content-desc)="[^"]+"' ;;
  logs)    adb logcat -d --pid="$(adb shell pidof "$PKG" | tr -d '\r')" | tail -n "${1:-50}" ;;
  stop)    adb emu kill ;;
  *)       sed -n '2,3p' "$0"; grep -oE '^  [a-z]+\)' "$0" | tr -d ' )' | tr '\n' ' '; echo ;;
esac
