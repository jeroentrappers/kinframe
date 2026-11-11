#!/usr/bin/env python3
"""A pretend kinframe frame for demos and App Review: it enrolls like a real frame, stays online on the device
websocket, confirms everything sent to it as delivered and seen, and reacts with a heart.

    KINFRAME_URL=https://kinframe.example.org DEMO_STATE=/data/state.json python3 demo_frame.py

On the first run it enrolls and logs the 8-digit code; claim the frame with that code in the app. The device
credentials are kept in DEMO_STATE. Standard library only (runs in python:3-alpine).
"""
import base64
import json
import os
import socket
import ssl
import sys
import threading
import time
import urllib.error
import urllib.request
import uuid
from datetime import datetime, timezone
from urllib.parse import urlparse

BASE = os.environ.get("KINFRAME_URL", "http://localhost:8110").rstrip("/")
STATE = os.environ.get("DEMO_STATE", "state.json")
REACT = os.environ.get("DEMO_REACTION", "love")  # love, laugh, wow, sad or empty for none


def log(*a):
    print(datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"), *a, flush=True)


def call(method, path, body=None, token=None):
    h = {"Accept": "application/json"}
    data = None
    if body is not None:
        data = json.dumps(body).encode()
        h["Content-Type"] = "application/json"
    if token:
        h["Authorization"] = f"Bearer {token}"
    if method in ("POST", "PUT", "PATCH"):
        h["Idempotency-Key"] = str(uuid.uuid4())
    req = urllib.request.Request(BASE + path, data=data, method=method, headers=h)
    with urllib.request.urlopen(req, timeout=60) as r:
        text = r.read()
        return json.loads(text) if text else None


def load_state():
    try:
        return json.load(open(STATE))
    except FileNotFoundError:
        return {}


def save_state(s):
    tmp = STATE + ".tmp"
    json.dump(s, open(tmp, "w"))
    os.replace(tmp, STATE)


def token(state):
    return call("POST", "/v1/devices/token", {"device_id": state["device_id"], "device_secret": state["device_secret"]})["access_token"]


def heartbeat(tok, started):
    call("POST", "/v1/device/heartbeat", {
        "app_version": "demo", "uptime_s": int(time.time() - started), "screen_state": "on",
        "timezone": "Europe/Brussels", "local_time": datetime.now(timezone.utc).isoformat()}, tok)


def ws_frame(opcode, payload=b""):
    mask = os.urandom(4)
    return bytes([0x80 | opcode, 0x80 | len(payload)]) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload))


def websocket(state, wake):
    """Stays connected to /v1/device/ws (that is "online"), answers pings, wakes the sync loop on any message."""
    u = urlparse(BASE)
    secure = u.scheme == "https"
    while True:
        try:
            tok = token(state)
            raw = socket.create_connection((u.hostname, u.port or (443 if secure else 80)), timeout=90)
            sock = ssl.create_default_context().wrap_socket(raw, server_hostname=u.hostname) if secure else raw
            key = base64.b64encode(os.urandom(16)).decode()
            sock.sendall((f"GET /v1/device/ws HTTP/1.1\r\nHost: {u.netloc}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                          f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer {tok}\r\n\r\n").encode())
            buf = b""
            while b"\r\n\r\n" not in buf:
                chunk = sock.recv(4096)
                if not chunk:
                    raise OSError("closed during handshake")
                buf += chunk
            head, buf = buf.split(b"\r\n\r\n", 1)
            if b" 101 " not in head.split(b"\r\n")[0]:
                raise OSError(head.split(b"\r\n")[0].decode(errors="replace"))
            log("online")
            wake.set()
            while True:
                while len(buf) < 2:
                    chunk = sock.recv(65536)
                    if not chunk:
                        raise OSError("closed")
                    buf += chunk
                op, n, off = buf[0] & 0x0F, buf[1] & 0x7F, 2
                off = 4 if n == 126 else 10 if n == 127 else 2
                while len(buf) < off:
                    buf += sock.recv(65536)
                if n >= 126:
                    n = int.from_bytes(buf[2:off], "big")
                while len(buf) < off + n:
                    buf += sock.recv(65536)
                payload, buf = buf[off:off + n], buf[off + n:]
                if op == 0x9:
                    sock.sendall(ws_frame(0xA, payload))
                elif op == 0x8:
                    raise OSError("server closed")
                else:
                    wake.set()
        except Exception as e:
            log("websocket:", e)
            time.sleep(5)


def sync(state, wake):
    """Pulls the change feed; new photos are acknowledged as delivered and seen, and get a reaction."""
    started = time.time()
    last_beat = 0
    while True:
        wake.wait(timeout=60)
        wake.clear()
        try:
            tok = token(state)
            if time.time() - last_beat > 300:
                heartbeat(tok, started)
                last_beat = time.time()
            if not state.get("claimed"):
                st = call("GET", "/v1/device/state", None, tok)
                if st.get("claimed"):
                    state["claimed"] = True
                    save_state(state)
                    log("claimed")
                else:
                    log("code:", call("POST", "/v1/device/code", None, tok).get("code"))
                    continue
            seq = state.get("seq", 0)
            while True:
                ch = call("GET", f"/v1/device/changes?since={seq}&limit=500", None, tok)
                fresh = []
                for c in ch["changes"]:
                    p = c.get("placement") or {}
                    if c["type"] == "reset":
                        seq = 0
                    if c["type"] == "placement.upsert" and p.get("status") == "ready":
                        fresh.append(p["id"])
                if fresh:
                    now = datetime.now(timezone.utc).isoformat()
                    call("POST", "/v1/device/acks", {"delivered": fresh, "seen": [{"placement_id": i, "at": now} for i in fresh]}, tok)
                    for i in fresh:
                        if REACT:
                            call("PUT", f"/v1/device/placements/{i}/reaction", {"reaction": REACT}, tok)
                    log(f"showed {len(fresh)} new item(s)")
                seq = ch["next_seq"]
                state["seq"] = seq
                save_state(state)
                if not ch["has_more"]:
                    break
        except urllib.error.HTTPError as e:
            log("sync:", e.code, e.read()[:200])
            time.sleep(10)
        except Exception as e:
            log("sync:", e)
            time.sleep(10)


def main():
    state = load_state()
    if "device_id" not in state:
        e = call("POST", "/v1/devices/enroll", {"hardware": {"model": "kinframe demo"}, "screen": {"width": 1280, "height": 800},
                                                 "app": {"version": "demo", "version_code": 1}})
        state = {"device_id": e["device_id"], "device_secret": e["device_secret"]}
        save_state(state)
        log("enrolled", state["device_id"])
    wake = threading.Event()
    wake.set()  # first sync right away
    threading.Thread(target=websocket, args=(state, wake), daemon=True).start()
    sync(state, wake)


if __name__ == "__main__":
    sys.exit(main())
