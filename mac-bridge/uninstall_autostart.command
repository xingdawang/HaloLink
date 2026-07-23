#!/bin/bash
PLIST="$HOME/Library/LaunchAgents/com.halolink.bridge.plist"
launchctl bootout gui/$(id -u) "$PLIST" 2>/dev/null || true
rm -f "$PLIST"
echo "HaloLink auto-start removed."
