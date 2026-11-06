#!/usr/bin/env python3
"""Lakeside's account checks its connection with Northgate and answers what waits, as it would on its own schedule."""
import re
import time
import urllib.request

import os
ROOT = os.path.dirname(os.path.abspath(__file__)) + "/run/org-b"
P = os.path.dirname(os.path.abspath(__file__)) + "/../../src/main/proto/dsm_app.proto"
ENV = int(re.search(r"ConnectReplyV1\s+connect_reply\s*=\s*(\d+)", open(P).read()).group(1))
token = open(ROOT + "/ingress/ingress-token").read().strip()


def varint(n):
    out = b""
    while n > 127:
        out += bytes([n & 127 | 128])
        n >>= 7
    return out + bytes([n])


def field(number, data):
    return varint(number << 3 | 2) + varint(len(data)) + data


def rv(d, i):
    v = s = 0
    while True:
        b = d[i]
        i += 1
        v |= (b & 127) << s
        s += 7
        if b < 128:
            return v, i


def parse(d):
    i, out = 0, []
    while i < len(d):
        k, i = rv(d, i)
        n, t = k >> 3, k & 7
        if t == 0:
            v, i = rv(d, i)
        elif t == 2:
            l, i = rv(d, i)
            v = d[i:i + l]
            i += l
        elif t == 1:
            v = d[i:i + 8]
            i += 8
        else:
            v = d[i:i + 4]
            i += 4
        out.append((n, v))
    return out


def router(op, method, body):
    pack = varint(2 << 3) + varint(1) + field(3, body) if body else varint(2 << 3) + varint(1)
    req = urllib.request.Request("http://127.0.0.1:8782/ingress/quiet", data=field(op, field(1, method.encode()) + field(2, pack)),
                                 headers={"Authorization": "Bearer " + token, "Content-Type": "application/x-protobuf"})
    answer = dict(parse(urllib.request.urlopen(req, timeout=60).read()))
    if 2 in answer:
        raise SystemExit(method + " refused: " + str(dict(parse(answer[2])).get(2, answer[2]))[:300])
    return dict(parse(dict(parse(answer[1][1:]))[ENV]))


router(2, "connect.sync", b"")
pending = []
for _ in range(50):
    reply = router(1, "connect.pending", b"")
    pending = [dict(parse(v)) for n, v in parse(reply[4]) if n == 1] if 4 in reply else []
    if pending:
        break
    time.sleep(1)
    router(2, "connect.sync", b"")
print("waiting at the hospital:", [(p.get(3, b"").decode(), p.get(4, b"").decode()[:70]) for p in pending])
for p in pending:
    body = field(1, p[1]) + varint(2 << 3) + varint(p[2]) + varint(3 << 3) + varint(1)
    decided = router(2, "connect.respond", body)
    print("decided:", dict(parse(decided[10])).get(3, b"").decode()[:160])
