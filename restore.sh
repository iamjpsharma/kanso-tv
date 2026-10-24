#!/bin/sh
# Undo: re-enables every package in the profile and gives Home back to the stock launcher.
# Usage: ./restore.sh profiles/<your-tv>.txt [--uninstall]
#   --uninstall also removes the custom launcher.
# Override the detected stock Home activity with:
#   HOME_ACTIVITY=com.example.launcher/.MainActivity ./restore.sh profiles/<your-tv>.txt
set -e
cd "$(dirname "$0")"
PROFILE="${1:?Usage: ./restore.sh profiles/<your-tv>.txt [--uninstall]}"
UNINSTALL=0
case "${2:-}" in
  --uninstall) UNINSTALL=1 ;;
  "") ;;
  *) echo "Unknown option: $2" >&2; exit 1 ;;
esac

for p in $(sed -e 's/#.*//' "$PROFILE"); do
  if tools/tv shell "pm list packages $p" | grep -q "package:$p"; then
    printf '%s: ' "$p"
    tools/tv shell "pm enable $p"   # one pm command per shell call
  else
    echo "$p: not installed, skipping"
  fi
done

# TCL/Google TV uses launcherx; older Android TV uses tvlauncher.
STOCK_HOME="${HOME_ACTIVITY:-}"
if [ -z "$STOCK_HOME" ]; then
  if tools/tv shell "pm list packages com.google.android.apps.tv.launcherx" | grep -q "package:com.google.android.apps.tv.launcherx"; then
    STOCK_HOME="com.google.android.apps.tv.launcherx/.home.HomeActivity"
  elif tools/tv shell "pm list packages com.google.android.tvlauncher" | grep -q "package:com.google.android.tvlauncher"; then
    STOCK_HOME="com.google.android.tvlauncher/.MainActivity"
  fi
fi

if [ -n "$STOCK_HOME" ]; then
  tools/tv shell "cmd package set-home-activity --user 0 $STOCK_HOME" || true
else
  echo "Warning: no stock Home activity found; Home was left unchanged."
fi

if [ "$UNINSTALL" -eq 1 ]; then
  if [ -z "$STOCK_HOME" ]; then
    echo "Refusing to uninstall the custom launcher without a stock Home." >&2
    exit 1
  fi
  tools/tv shell "pm uninstall com.kansotv.launcher" || true
fi

tools/tv shell "input keyevent KEYCODE_HOME"
echo "Restored."
