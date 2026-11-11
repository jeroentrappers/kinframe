#!/usr/bin/env python3
"""Seeds a dev-auth kinframe server with a made-up family for the App Store screenshots.

    python3 seed.py http://localhost:8110 en     # or nl
    → prints the signed-in user and the frame ids as JSON
    python3 seed.py keepalive http://localhost:8110   # keeps the "online" frames connected until stopped
    python3 seed.py backdate-sql | docker exec -i <postgres> psql -U kinframe -d kinframe   # spread the sends over time

Frame credentials go to $SEED_STATE (default ./.seed-state.json, ignored by git) for keepalive.

Everything is fictional: users, frames and captions. Photos come from photos/ (public domain / CC0, see
photos/CREDITS.md). Frames are fake devices enrolled through the device API; they acknowledge, react and reply
like real frames, so History shows delivered/seen states, hearts and replies. Run it against a fresh database:
each run adds another family.
"""
import json
import base64
import os
import sys
import time
import uuid
import urllib.error
import urllib.request
from datetime import datetime, timedelta, timezone

HERE = os.path.dirname(os.path.abspath(__file__))

FAMILY = {
    "en": {
        "me": ("emma@example.org", "Emma"),
        "others": [("lisa@example.org", "Lisa"), ("tom@example.org", "Tom"), ("grandpa@example.org", "Grandpa Joe")],
        "frames": [
            {"name": "Grandma’s frame", "location": "Living room", "screen": (1280, 800), "online": True, "owner": "me"},
            {"name": "The cabin", "location": "By the lake", "screen": (1280, 800), "online": False, "owner": "me"},
            {"name": "Lisa’s kitchen", "location": "Kitchen", "screen": (800, 1280), "online": True, "owner": "Lisa"},
        ],
        "sends": [
            # (frames, [(photo, caption)], reaction per item, reply per item, minutes ago, seen)
            ([0, 2], [("birthday-cake", "Happy birthday Grandma! 🎂"), ("strawberries", None)], ["love", None], ["miss_you", None], 6, True),
            ([0], [("tulip-field", "The tulips are out ❤️"), ("tulips", None), ("garden", "Sunday walk in the park")], ["love", "wow", None], ["beautiful", None, None], 95, True),
            ([1], [("mountain-lake", "Made it to the lake!"), ("snowy-mountains", None), ("autumn-forest", None)], [None, None, None], [None, None, None], 300, False),
            ([0, 2], [("pancakes", "Pancake Saturday 🥞"), ("sailboat", "Out on the water"), ("sunset", "What a sunset")], ["laugh", None, "love"], [None, None, "thanks"], 1500, True),
        ],
        "album": "Summer holiday",
    },
    "nl": {
        "me": ("emma.nl@example.org", "Emma"),
        "others": [("lisa.nl@example.org", "Lisa"), ("tom.nl@example.org", "Tom"), ("opa.nl@example.org", "Opa Jan")],
        "frames": [
            {"name": "Kader van oma", "location": "Woonkamer", "screen": (1280, 800), "online": True, "owner": "me"},
            {"name": "Het huisje", "location": "Aan het meer", "screen": (1280, 800), "online": False, "owner": "me"},
            {"name": "Keuken van Lisa", "location": "Keuken", "screen": (800, 1280), "online": True, "owner": "Lisa"},
        ],
        "sends": [
            ([0, 2], [("birthday-cake", "Gelukkige verjaardag, oma! 🎂"), ("strawberries", None)], ["love", None], ["miss_you", None], 6, True),
            ([0], [("tulip-field", "De tulpen staan in bloei ❤️"), ("tulips", None), ("garden", "Zondagse wandeling in het park")], ["love", "wow", None], ["beautiful", None, None], 95, True),
            ([1], [("mountain-lake", "We zijn bij het meer!"), ("snowy-mountains", None), ("autumn-forest", None)], [None, None, None], [None, None, None], 300, False),
            ([0, 2], [("pancakes", "Pannenkoekenzaterdag 🥞"), ("sailboat", "Op het water"), ("sunset", "Wat een zonsondergang")], ["laugh", None, "love"], [None, None, "thanks"], 1500, True),
        ],
        "album": "Zomervakantie",
    },
}


class Client:
    def __init__(self, base, token=None):
        self.base, self.token = base.rstrip("/"), token

    def call(self, method, path, body=None, headers=None, raw=None):
        h = {"Accept": "application/json", **(headers or {})}
        data = raw
        if body is not None:
            data = json.dumps(body).encode()
            h["Content-Type"] = "application/json"
        if self.token:
            h["Authorization"] = f"Bearer {self.token}"
        if method in ("POST", "PUT", "PATCH") and "Idempotency-Key" not in h:
            h["Idempotency-Key"] = str(uuid.uuid4())
        url = path if path.startswith("http") else self.base + path
        req = urllib.request.Request(url, data=data, method=method, headers=h)
        try:
            with urllib.request.urlopen(req, timeout=60) as r:
                text = r.read()
                return (json.loads(text) if text and r.headers.get_content_type().endswith("json") else None), r.headers
        except urllib.error.HTTPError as e:
            raise SystemExit(f"{method} {path}: {e.code} {e.read()[:400]!r}")


def dev_user(base, email, name):
    body, _ = Client(base).call("POST", "/dev/token", {"email": email, "name": name})
    c = Client(base, body["access_token"])
    c.call("PATCH", "/v1/me", {"display_name": name})
    return c


def fake_frame(base, w, h):
    anon = Client(base)
    e, _ = anon.call("POST", "/v1/devices/enroll", {
        "hardware": {"model": "kinframe 10.1"}, "screen": {"width": w, "height": h},
        "app": {"version": "1.4.0", "version_code": 140}})
    t, _ = anon.call("POST", "/v1/devices/token", {"device_id": e["device_id"], "device_secret": e["device_secret"]})
    dev = Client(base, t["access_token"])
    dev.creds = {"device_id": e["device_id"], "device_secret": e["device_secret"]}
    code, _ = dev.call("POST", "/v1/device/code")
    return dev, code["code"]


def heartbeat(dev):
    dev.call("POST", "/v1/device/heartbeat", {
        "app_version": "1.4.0", "app_version_code": 140, "android_sdk": 30, "uptime_s": 86400 * 3,
        "wifi_ssid": None, "wifi_rssi_dbm": -52, "wifi_link_mbps": 144, "storage_free_bytes": 9_800_000_000,
        "storage_total_bytes": 16_000_000_000, "screen_state": "on", "timezone": "Europe/Brussels",
        "local_time": datetime.now(timezone.utc).isoformat()})


def upload(user, photo):
    path = os.path.join(HERE, "photos", f"{photo}.jpg")
    data = open(path, "rb").read()
    meta = ",".join(f"{k} {base64.b64encode(v.encode()).decode()}" for k, v in
                    {"filename": f"{photo}.jpg", "filetype": "image/jpeg"}.items())
    _, hdr = user.call("POST", "/v1/uploads", headers={
        "Tus-Resumable": "1.0.0", "Upload-Length": str(len(data)), "Upload-Metadata": meta}, raw=b"")
    loc = hdr["Location"]
    if not loc.startswith("http"):
        loc = user.base + loc if loc.startswith("/") else user.base + "/v1/uploads/" + loc
    user.call("PATCH", loc, headers={"Tus-Resumable": "1.0.0", "Upload-Offset": "0",
                                     "Content-Type": "application/offset+octet-stream"}, raw=data)
    return loc.rstrip("/").rsplit("/", 1)[-1]


def placements(dev, want):
    """Wait until the frame has `want` ready placements; returns {item_id: placement_id}."""
    for _ in range(120):
        ch, _ = dev.call("GET", "/v1/device/changes?since=0&limit=1000")
        found = {}
        for c in ch["changes"]:
            p = c.get("placement")
            if c["type"] == "placement.upsert" and p and p.get("status") in ("ready", "delivered", "seen"):
                found[p["item"]["id"] if isinstance(p.get("item"), dict) else p.get("item_id")] = p["id"]
        if len(found) >= want:
            return found
        time.sleep(1)
    kinds = [(c["type"], (c.get("placement") or {}).get("status"), sorted((c.get("placement") or {}).keys())) for c in ch["changes"]]
    raise SystemExit(f"frame has {len(found)} of {want} ready placements: {kinds}")


STATE = os.environ.get("SEED_STATE", os.path.join(HERE, ".seed-state.json"))


def _ws_frame(opcode, payload=b""):
    """A masked client websocket frame (payloads here are tiny)."""
    mask = os.urandom(4)
    return bytes([0x80 | opcode, 0x80 | len(payload)]) + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(payload))


def _device_socket(base, creds):
    """Keeps one fake frame connected to /v1/device/ws (that is what makes it "online"), answering pings."""
    import socket
    from urllib.parse import urlparse
    u = urlparse(base)
    while True:
        try:
            t, _ = Client(base).call("POST", "/v1/devices/token", creds)
            sock = socket.create_connection((u.hostname, u.port or 80), timeout=90)
            key = base64.b64encode(os.urandom(16)).decode()
            sock.sendall((f"GET /v1/device/ws HTTP/1.1\r\nHost: {u.netloc}\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                          f"Sec-WebSocket-Key: {key}\r\nSec-WebSocket-Version: 13\r\nAuthorization: Bearer {t['access_token']}\r\n\r\n").encode())
            buf = b""
            while b"\r\n\r\n" not in buf:
                buf += sock.recv(4096)
            head, buf = buf.split(b"\r\n\r\n", 1)
            if b" 101 " not in head.split(b"\r\n")[0]:
                raise OSError(head.split(b"\r\n")[0].decode())
            heartbeat(Client(base, t["access_token"]))
            while True:
                while len(buf) < 2:
                    chunk = sock.recv(65536)
                    if not chunk:
                        raise OSError("closed")
                    buf += chunk
                op, n, off = buf[0] & 0x0F, buf[1] & 0x7F, 2
                if n == 126:
                    off = 4
                elif n == 127:
                    off = 10
                while len(buf) < off:
                    buf += sock.recv(65536)
                if n >= 126:
                    n = int.from_bytes(buf[2:off], "big")
                while len(buf) < off + n:
                    buf += sock.recv(65536)
                payload, buf = buf[off:off + n], buf[off + n:]
                if op == 0x9:
                    sock.sendall(_ws_frame(0xA, payload))
                elif op == 0x8:
                    raise OSError("server closed")
        except Exception as e:  # reconnect after a pause
            print(f"device socket: {e}", file=sys.stderr)
            time.sleep(3)


def keepalive(base):
    import threading
    for c in json.load(open(STATE))["online"]:
        threading.Thread(target=_device_socket, args=(base, c), daemon=True).start()
    while True:
        time.sleep(3600)


def backdate_sql():
    """SQL that moves each seeded send (and its items, deliveries, reactions and replies) back to its story time:
    the API stamps everything "now". For a throwaway dev database only."""
    out = ["BEGIN;"]
    for send_id, ago in json.load(open(STATE)).get("sends", []):
        d = f"interval '{int(ago)} minutes'"
        out += [f"UPDATE sends SET created_at = created_at - {d} WHERE id = '{send_id}';",
                f"UPDATE items SET created_at = created_at - {d} WHERE id IN (SELECT item_id FROM send_items WHERE send_id = '{send_id}');",
                f"UPDATE placement_replies SET at = at - {d} WHERE placement_id IN (SELECT id FROM placements WHERE send_id = '{send_id}');",
                f"UPDATE placements SET received_at = received_at - {d}, delivered_at = delivered_at - {d}, seen_at = seen_at - {d}, "
                f"reaction_at = reaction_at - {d} WHERE send_id = '{send_id}';"]
    out.append("COMMIT;")
    return "\n".join(out)


def main():
    if sys.argv[1] == "keepalive":
        return keepalive(sys.argv[2])
    if sys.argv[1] == "backdate-sql":
        return print(backdate_sql())
    base, lang = sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else "en"
    fam = FAMILY[lang]
    me = dev_user(base, *fam["me"])
    others = {name: dev_user(base, email, name) for email, name in fam["others"]}
    users = {"me": me, **others}

    frames = []
    for spec in fam["frames"]:
        dev, code = fake_frame(base, *spec["screen"])
        owner = users[spec["owner"]]
        r, _ = owner.call("POST", "/v1/frames/redeem", {"code": code})
        fid = r["frame"]["id"]
        owner.call("PATCH", f"/v1/frames/{fid}", {"name": spec["name"], "location": spec["location"],
                                                  "invite_requires_approval": False, "approve_joins_on_frame": False})
        # everyone else joins through an invite
        inv, _ = owner.call("POST", f"/v1/frames/{fid}/invites", {"max_uses": None})
        for name, u in users.items():
            if u is not owner:
                u.call("POST", f"/v1/invites/{inv['token']}/accept")
        reqs, _ = owner.call("GET", f"/v1/frames/{fid}/join-requests")
        for jr in reqs or []:
            owner.call("POST", f"/v1/frames/{fid}/join-requests/{jr['id']}/approve")
        if spec["online"]:
            heartbeat(dev)
        frames.append({"id": fid, "dev": dev, "spec": spec})

    now = datetime.now(timezone.utc)
    per_frame = {i: 0 for i in range(len(frames))}
    sent_at = []
    for targets, items, reactions, replies, ago, seen in reversed(fam["sends"]):
        body = {"frame_ids": [frames[i]["id"] for i in targets],
                "items": [{"upload_id": upload(me, p), "caption": cap,
                           "captured_at": (now - timedelta(minutes=ago + 60)).isoformat()} for p, cap in items]}
        send, _ = me.call("POST", "/v1/sends", body)
        sent_at.append((send["id"], ago))
        item_ids = [it["id"] for it in send["items"]]
        for i in targets:
            per_frame[i] += len(items)
            f = frames[i]
            if not f["spec"]["online"]:
                continue
            pl = placements(f["dev"], per_frame[i])
            ids = [pl[x] for x in item_ids if x in pl]
            f["dev"].call("POST", "/v1/device/acks", {
                "delivered": ids,
                "seen": [{"placement_id": p, "at": (now - timedelta(minutes=max(ago - 2, 1))).isoformat()} for p in ids] if seen else []})
            for pid, re_, rp in zip(ids, reactions, replies):
                if re_:
                    f["dev"].call("PUT", f"/v1/device/placements/{pid}/reaction", {"reaction": re_})
                if rp:
                    f["dev"].call("POST", f"/v1/device/placements/{pid}/replies", {"preset": rp})

    me.call("POST", f"/v1/frames/{frames[0]['id']}/albums", {"name": fam["album"]})
    for f in frames:
        if f["spec"]["online"]:
            heartbeat(f["dev"])
    state = json.load(open(STATE)) if os.path.exists(STATE) else {"online": []}
    state["online"] += [f["dev"].creds for f in frames if f["spec"]["online"]]
    state.setdefault("sends", []).extend(sent_at)
    json.dump(state, open(STATE, "w"))
    print(json.dumps({"server": base, "email": fam["me"][0], "name": fam["me"][1],
                      "frames": [f["id"] for f in frames]}))


if __name__ == "__main__":
    main()
