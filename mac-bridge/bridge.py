#!/usr/bin/env python3
"""HaloLink local bridge.

Receives status events from the Chrome extension and broadcasts them to Android
clients. It deliberately forwards status metadata only, never conversation text.
"""
from __future__ import annotations

import argparse
import asyncio
import ipaddress
import json
import os
import platform
import re
import signal
import socket
import sys
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from aiohttp import WSMsgType, web
try:
    from zeroconf import IPVersion, ServiceInfo, Zeroconf
    ZEROCONF_AVAILABLE = True
except ImportError:
    IPVersion = ServiceInfo = Zeroconf = None  # type: ignore[assignment]
    ZEROCONF_AVAILABLE = False

DEFAULT_PORT = int(os.environ.get("HALOLINK_PORT", "8765"))
PORT_SCAN_END = int(os.environ.get("HALOLINK_PORT_END", "8775"))
ACTIVE_PORT = DEFAULT_PORT
SERVICE_TYPE = "_halolink._tcp.local."
VERSION = "0.1.3"

PHONE_CLIENTS: set[web.WebSocketResponse] = set()
BROWSER_CLIENTS: set[web.WebSocketResponse] = set()
LAST_DELIVERY: dict[str, Any] = {
    "attempted": 0,
    "sent": 0,
    "failed": 0,
    "timestamp": None,
}
CURRENT_STATE: dict[str, Any] = {
    "type": "status",
    "state": "READY",
    "label": "Ready",
    "timestamp": datetime.now(timezone.utc).isoformat(),
    "source": "bridge",
}


def now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


def sanitize_hostname(value: str) -> str:
    cleaned = re.sub(r"[^a-zA-Z0-9-]", "-", value).strip("-")
    return cleaned[:50] or "Mac"


def candidate_ipv4_addresses() -> list[str]:
    found: list[str] = []
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            addr = item[4][0]
            if addr not in found:
                found.append(addr)
    except OSError:
        pass

    # This does not transmit application data; it asks the OS which interface
    # it would use for an outbound route.
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as sock:
            sock.connect(("1.1.1.1", 80))
            addr = sock.getsockname()[0]
            if addr not in found:
                found.insert(0, addr)
    except OSError:
        pass

    valid: list[str] = []
    for addr in found:
        try:
            ip = ipaddress.ip_address(addr)
        except ValueError:
            continue
        if ip.is_loopback or ip.is_link_local or ip.is_multicast or ip.is_unspecified:
            continue
        valid.append(addr)
    return valid or ["127.0.0.1"]


async def broadcast_to_phones(payload: dict[str, Any]) -> dict[str, int]:
    """Send a status to every currently connected phone WebSocket.

    ``sent`` means aiohttp accepted the message for that connection's socket;
    it is deliberately reported separately from the number of connections so a
    demo cannot look successful when there are no phone displays connected.
    """
    global LAST_DELIVERY
    message = json.dumps(payload, ensure_ascii=False)
    stale: list[web.WebSocketResponse] = []
    attempted = len(PHONE_CLIENTS)
    sent = 0
    for ws in list(PHONE_CLIENTS):
        try:
            await ws.send_str(message)
            sent += 1
        except (ConnectionResetError, RuntimeError):
            stale.append(ws)
    for ws in stale:
        PHONE_CLIENTS.discard(ws)
    result = {"attempted": attempted, "sent": sent, "failed": len(stale)}
    LAST_DELIVERY = {**result, "timestamp": now_iso()}
    print(
        f"Phone delivery: {sent}/{attempted} sent"
        + (f" ({len(stale)} stale connection removed)" if stale else ""),
        flush=True,
    )
    return result


async def set_state(payload: dict[str, Any], source: str) -> dict[str, Any]:
    global CURRENT_STATE
    state = str(payload.get("state", "READY")).upper()
    allowed = {
        "READY", "THINKING", "WORKING", "STREAMING", "LISTENING",
        "COMPLETED", "ERROR", "DISCONNECTED", "CONNECTING", "SEARCHING"
    }
    if state not in allowed:
        state = "READY"
    label = str(payload.get("label") or state.title())[:80]
    CURRENT_STATE = {
        "type": "status",
        "state": state,
        "label": label,
        "timestamp": str(payload.get("timestamp") or now_iso()),
        "source": source,
    }
    # Useful debugging metadata, but never conversation content.
    for key in ("url", "tabId", "reason"):
        if key in payload:
            CURRENT_STATE[key] = payload[key]
    print(f"[{datetime.now().strftime('%H:%M:%S')}] {source:8s} -> {state:10s} {label}", flush=True)
    delivery = await broadcast_to_phones(CURRENT_STATE)
    return {**CURRENT_STATE, "delivery": delivery}


async def ws_browser(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse(heartbeat=25)
    await ws.prepare(request)
    BROWSER_CLIENTS.add(ws)
    print(f"Browser connected ({len(BROWSER_CLIENTS)} total)", flush=True)
    await ws.send_json({"type": "hello", "role": "bridge", "version": VERSION})
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    payload = json.loads(msg.data)
                except json.JSONDecodeError:
                    continue
                if payload.get("type") == "ping":
                    await ws.send_json({"type": "pong", "timestamp": now_iso()})
                elif payload.get("type") == "status":
                    state = await set_state(payload, "browser")
                    await ws.send_json({"type": "ack", "state": state["state"]})
            elif msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                break
    finally:
        BROWSER_CLIENTS.discard(ws)
        print(f"Browser disconnected ({len(BROWSER_CLIENTS)} total)", flush=True)
    return ws


async def ws_phone(request: web.Request) -> web.WebSocketResponse:
    ws = web.WebSocketResponse(heartbeat=25)
    await ws.prepare(request)
    PHONE_CLIENTS.add(ws)
    peer = request.transport.get_extra_info("peername") if request.transport else None
    print(f"Phone connected from {peer} ({len(PHONE_CLIENTS)} total)", flush=True)
    await ws.send_json({"type": "hello", "role": "bridge", "version": VERSION})
    await ws.send_json(CURRENT_STATE)
    try:
        async for msg in ws:
            if msg.type == WSMsgType.TEXT:
                try:
                    payload = json.loads(msg.data)
                except json.JSONDecodeError:
                    continue
                if payload.get("type") == "ping":
                    await ws.send_json({"type": "pong", "timestamp": now_iso()})
            elif msg.type in (WSMsgType.ERROR, WSMsgType.CLOSE):
                break
    finally:
        PHONE_CLIENTS.discard(ws)
        print(f"Phone disconnected ({len(PHONE_CLIENTS)} total)", flush=True)
    return ws


async def health(_: web.Request) -> web.Response:
    return web.json_response({
        "ok": True,
        "product": "HaloLink",
        "version": VERSION,
        "state": CURRENT_STATE,
        "browserClients": len(BROWSER_CLIENTS),
        "phoneClients": len(PHONE_CLIENTS),
        "lastDelivery": LAST_DELIVERY,
    })


async def current_state(_: web.Request) -> web.Response:
    """Small HTTP fallback for displays when WebSocket is unavailable."""
    return web.json_response({
        "ok": True,
        "product": "HaloLink",
        "version": VERSION,
        "state": CURRENT_STATE,
        "phoneClients": len(PHONE_CLIENTS),
        "lastDelivery": LAST_DELIVERY,
    })


async def test_state(request: web.Request) -> web.Response:
    state = request.match_info["state"].upper()
    labels = {
        "READY": "Ready",
        "THINKING": "Thinking...",
        "WORKING": "Working...",
        "STREAMING": "Responding...",
        "LISTENING": "Listening...",
        "COMPLETED": "Completed!",
        "ERROR": "Error",
    }
    payload = await set_state({"state": state, "label": labels.get(state, state.title())}, "test")
    return web.json_response(payload)


async def display_page(_: web.Request) -> web.Response:
    page = Path(__file__).with_name("display.html")
    return web.Response(text=page.read_text(encoding="utf-8"), content_type="text/html")


async def index(_: web.Request) -> web.Response:
    html = f"""<!doctype html><meta charset='utf-8'><title>HaloLink Bridge</title>
    <style>body{{font:16px -apple-system,sans-serif;background:#080a0c;color:#eee;padding:36px}}
    code{{background:#171a1f;padding:3px 7px;border-radius:6px}}button{{margin:5px;padding:10px 14px}}</style>
    <h1>HaloLink Bridge {VERSION}</h1><p>Current state: <code>{CURRENT_STATE['state']}</code></p>
    <p>Browser clients: {len(BROWSER_CLIENTS)} · Phone clients: {len(PHONE_CLIENTS)}</p>
    <p>Test:</p><div id='buttons'></div><script>
    for(const s of ['READY','THINKING','WORKING','STREAMING','LISTENING','COMPLETED','ERROR']){{
      const b=document.createElement('button');b.textContent=s;b.onclick=()=>fetch('/api/test/'+s);buttons.appendChild(b);
    }}</script>"""
    return web.Response(text=html, content_type="text/html")


def port_is_available(port: int) -> bool:
    """Return True only when no IPv4 listener already owns this port.

    On macOS, a process bound to 127.0.0.1 can coexist with another process
    bound to 0.0.0.0 when socket reuse is enabled.  The phone would then reach
    the latter via the LAN address while ``demo_states.py`` reaches the former
    via 127.0.0.1.  Probe both bindings without SO_REUSEADDR so a selected
    HaloLink port is unambiguous for the phone, health checks, and demo.
    """
    for address in ("127.0.0.1", "0.0.0.0"):
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            try:
                sock.bind((address, port))
            except OSError:
                return False
    return True


def choose_port(preferred: int, scan_end: int, strict: bool = False) -> int:
    if strict:
        if not port_is_available(preferred):
            raise OSError(f"Port {preferred} is already in use")
        return preferred
    for candidate in range(preferred, max(preferred, scan_end) + 1):
        if port_is_available(candidate):
            return candidate
    raise OSError(f"No free HaloLink port found in {preferred}-{scan_end}")


def register_mdns(ip: str, port: int):
    if not ZEROCONF_AVAILABLE:
        raise RuntimeError("zeroconf package is not installed")
    host = sanitize_hostname(platform.node() or "Mac")
    service_name = f"HaloLink-{host}.{SERVICE_TYPE}"
    server_name = f"{host}.local."
    info = ServiceInfo(
        SERVICE_TYPE,
        service_name,
        addresses=[socket.inet_aton(ip)],
        port=port,
        properties={
            b"version": VERSION.encode(),
            b"path": b"/ws/phone",
            b"name": host.encode(),
        },
        server=server_name,
    )
    zc = Zeroconf(ip_version=IPVersion.V4Only)
    zc.register_service(info, allow_name_change=True)
    return zc, info


async def main() -> None:
    global ACTIVE_PORT
    parser = argparse.ArgumentParser(description="HaloLink local status bridge")
    parser.add_argument("--port", type=int, default=DEFAULT_PORT, help="preferred TCP port")
    parser.add_argument("--port-end", type=int, default=PORT_SCAN_END, help="last port to try")
    parser.add_argument("--strict-port", action="store_true", help="fail instead of selecting a fallback port")
    args = parser.parse_args()
    ACTIVE_PORT = choose_port(args.port, args.port_end, args.strict_port)

    addresses = candidate_ipv4_addresses()
    advertise_ip = addresses[0]
    app = web.Application()
    app.add_routes([
        web.get("/", index),
        web.get("/display", display_page),
        web.get("/health", health),
        web.get("/api/state", current_state),
        web.get("/api/test/{state}", test_state),
        web.get("/ws/browser", ws_browser),
        web.get("/ws/phone", ws_phone),
    ])

    runner = web.AppRunner(app)
    await runner.setup()
    site = web.TCPSite(runner, "0.0.0.0", ACTIVE_PORT)
    await site.start()

    zc = None
    info = None
    try:
        zc, info = register_mdns(advertise_ip, ACTIVE_PORT)
    except Exception as exc:  # mDNS failure should not prevent manual-IP testing.
        print(f"Warning: mDNS registration failed: {exc}", file=sys.stderr, flush=True)

    print("\nHaloLink Bridge", VERSION, flush=True)
    port_file = Path(__file__).with_name(".halolink_port")
    port_file.write_text(str(ACTIVE_PORT), encoding="utf-8")
    if ACTIVE_PORT != args.port:
        print(f"Port {args.port} was unavailable; using {ACTIVE_PORT} instead.", flush=True)
    print(f"Listening: http://127.0.0.1:{ACTIVE_PORT}", flush=True)
    print(f"LAN address: http://{advertise_ip}:{ACTIVE_PORT}", flush=True)
    print(f"Phone display: http://{advertise_ip}:{ACTIVE_PORT}/display", flush=True)
    print(f"mDNS service: {SERVICE_TYPE}", flush=True)
    print("Press Ctrl+C to stop.\n", flush=True)

    stop_event = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGINT, signal.SIGTERM):
        try:
            loop.add_signal_handler(sig, stop_event.set)
        except NotImplementedError:
            pass
    await stop_event.wait()

    if zc and info:
        zc.unregister_service(info)
        zc.close()
    for ws in list(PHONE_CLIENTS | BROWSER_CLIENTS):
        await ws.close(code=1001, message=b"Bridge shutting down")
    await runner.cleanup()


if __name__ == "__main__":
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        pass
    except OSError as exc:
        print(f"\nHaloLink could not start: {exc}", file=sys.stderr, flush=True)
        raise SystemExit(2)
