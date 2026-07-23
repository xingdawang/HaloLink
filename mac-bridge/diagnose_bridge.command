#!/bin/bash
cd "$(dirname "$0")" || exit 1

echo "HaloLink Bridge diagnostics"
echo "==========================="
echo

echo "Processes listening on HaloLink ports:"
found_listener=0
for port in $(seq 8765 8775); do
  line=$(lsof -nP -iTCP:"$port" -sTCP:LISTEN 2>/dev/null | tail -n +2)
  if [ -n "$line" ]; then
    found_listener=1
    echo "Port $port:"
    echo "$line"
  fi
done
if [ "$found_listener" -eq 0 ]; then
  echo "No process is listening on ports 8765-8775."
fi

echo
echo "HaloLink health check:"
found_bridge=0
for port in $(seq 8766 8775); do
  result=$(curl -fsS --max-time 0.4 "http://127.0.0.1:$port/health" 2>/dev/null)
  if echo "$result" | grep -q '"product": "HaloLink"'; then
    found_bridge=1
    echo "HaloLink is responding on port $port"
    echo "$result"
  fi
done
if [ "$found_bridge" -eq 0 ]; then
  echo "HaloLink Bridge is not responding. Start run_bridge.command and keep its Terminal window open."
fi

echo
echo "Possible Mac LAN addresses:"
for iface in en0 en1 en2; do
  ip=$(ipconfig getifaddr "$iface" 2>/dev/null)
  [ -n "$ip" ] && echo "$iface: $ip"
done

echo
read -r -p "Press Return to close this window..." _
