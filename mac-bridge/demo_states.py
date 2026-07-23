#!/usr/bin/env python3
import json
import os
import time
import urllib.error
import urllib.request
from pathlib import Path

PORT_START = 8766
PORT_END = 8775


def is_halolink_bridge(port: int) -> bool:
    """Accept only a real HaloLink Bridge health response."""
    try:
        with urllib.request.urlopen(
            f"http://127.0.0.1:{port}/health", timeout=0.45
        ) as response:
            if response.status != 200:
                return False
            payload = json.loads(response.read().decode("utf-8"))
    except (OSError, urllib.error.URLError, json.JSONDecodeError, UnicodeDecodeError):
        return False

    return (
        payload.get("ok") is True
        and payload.get("product") == "HaloLink"
        and isinstance(payload.get("version"), str)
        and isinstance(payload.get("pid"), int)
        and payload.get("port") == port
        and isinstance(payload.get("state"), dict)
        and "browserClients" in payload
        and "phoneClients" in payload
    )


def discover_port() -> int:
    configured = os.environ.get("HALOLINK_PORT")
    candidates: list[int] = []
    if configured:
        try:
            candidates.append(int(configured))
        except ValueError:
            pass

    port_file = Path(__file__).with_name(".halolink_port")
    if port_file.exists():
        try:
            candidates.append(int(port_file.read_text(encoding="utf-8").strip()))
        except ValueError:
            pass

    candidates.extend(range(PORT_START, PORT_END + 1))
    seen: set[int] = set()
    for port in candidates:
        if port in seen:
            continue
        seen.add(port)
        if is_halolink_bridge(port):
            return port

    raise SystemExit(
        "HaloLink Bridge is not running on ports 8766-8775. "
        "Start run_bridge.command first."
    )


port = discover_port()
print(f"Using verified HaloLink Bridge on port {port}")
states = [
    ("READY", 1.5),
    ("THINKING", 3),
    ("WORKING", 3),
    ("STREAMING", 4),
    ("COMPLETED", 5),
    ("ERROR", 3),
    ("READY", 2),
]
for state, seconds in states:
    url = f"http://127.0.0.1:{port}/api/test/{state}"
    print("Sending", state)
    try:
        with urllib.request.urlopen(url, timeout=2) as response:
            if response.status != 200:
                raise RuntimeError(f"HTTP {response.status}")
            payload = json.loads(response.read().decode("utf-8"))
            delivery = payload.get("delivery", {})
            print(
                "  Phone delivery: "
                f"{delivery.get('sent', 0)}/{delivery.get('attempted', 0)} sent"
                f", {delivery.get('failed', 0)} failed"
            )
    except Exception as exc:
        raise SystemExit(
            f"HaloLink on port {port} did not accept test state {state}: {exc}"
        )
    time.sleep(seconds)
