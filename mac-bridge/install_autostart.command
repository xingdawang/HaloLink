#!/bin/bash
set -e
cd "$(dirname "$0")"
BASE="$(pwd)"
if [ ! -x "$BASE/.venv/bin/python" ]; then
  echo "Please run run_bridge.command once before installing auto-start."
  exit 1
fi
PLIST="$HOME/Library/LaunchAgents/com.halolink.bridge.plist"
mkdir -p "$HOME/Library/LaunchAgents" "$HOME/Library/Logs/HaloLink"
cat > "$PLIST" <<EOF
<?xml version="1.0" encoding="UTF-8"?>
<!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
<plist version="1.0"><dict>
<key>Label</key><string>com.halolink.bridge</string>
<key>ProgramArguments</key><array>
<string>$BASE/.venv/bin/python</string>
<string>$BASE/bridge.py</string>
<string>--port</string><string>8766</string>
<string>--port-end</string><string>8775</string>
</array>
<key>WorkingDirectory</key><string>$BASE</string>
<key>RunAtLoad</key><true/><key>KeepAlive</key><true/>
<key>StandardOutPath</key><string>$HOME/Library/Logs/HaloLink/bridge.log</string>
<key>StandardErrorPath</key><string>$HOME/Library/Logs/HaloLink/bridge-error.log</string>
</dict></plist>
EOF
launchctl bootout gui/$(id -u) "$PLIST" 2>/dev/null || true
launchctl bootstrap gui/$(id -u) "$PLIST"
echo "HaloLink Bridge will now start automatically on ports 8766-8775."
