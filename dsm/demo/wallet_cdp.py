#!/usr/bin/env python3
"""Drive the DSM wallet's real screen on a connected phone: wallet_cdp.py SERIAL_PART 'javascript'.

It speaks to the debug wallet's WebView over adb, and refuses to run anything while a recovery-phrase screen is up, so a
phrase can never reach this tool's output. It presses the wallet's own buttons, and DSM does the rest.
"""
import base64
import json
import os
import re
import socket
import struct
import subprocess
import sys
import urllib.request

GUARD = "!!document.querySelector('[class*=\"recovery\" i],[class*=\"phrase\" i],[class*=\"mnemonic\" i],[class*=\"seed\" i]')"


def adb(serial, *args):
    return subprocess.run(["adb", "-s", serial, *args], capture_output=True, text=True, timeout=30).stdout


def serial_of(part):
    """The connected phone whose serial contains `part`: an IP, a hardware serial or an mDNS name (which may hold spaces)."""
    for line in subprocess.run(["adb", "devices"], capture_output=True, text=True).stdout.splitlines()[1:]:
        if "\t" in line:
            serial, state = line.rsplit("\t", 1)
            if part in serial and state.strip() == "device":
                return serial
    raise SystemExit("no connected phone matches " + part)


class Page:
    def __init__(self, serial):
        pid = adb(serial, "shell", "pidof", "com.dsm.wallet").strip().split()[0]
        port = 9300 + (sum(map(ord, serial)) % 200)
        adb(serial, "forward", "tcp:%d" % port, "localabstract:webview_devtools_remote_%s" % pid)
        pages = json.load(urllib.request.urlopen("http://127.0.0.1:%d/json" % port, timeout=10))
        target = [p for p in pages if p.get("type") == "page"][0]
        self.sock = socket.create_connection(("127.0.0.1", port), timeout=120)
        path = re.sub(r"^ws://[^/]+", "", target["webSocketDebuggerUrl"])
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall(("GET %s HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (path, key)).encode())
        reply = b""
        while b"\r\n\r\n" not in reply:
            reply += self.sock.recv(4096)
        self.next_id = 0

    def _send(self, text):
        data, mask = text.encode(), os.urandom(4)
        head = bytes([0x81])
        n = len(data)
        head += bytes([0x80 | n]) if n < 126 else bytes([0x80 | 126]) + struct.pack(">H", n) if n < 65536 else bytes([0x80 | 127]) + struct.pack(">Q", n)
        self.sock.sendall(head + mask + bytes(b ^ mask[i % 4] for i, b in enumerate(data)))

    def _read(self, n):
        out = b""
        while len(out) < n:
            out += self.sock.recv(n - len(out))
        return out

    def _recv(self):
        first, second = self._read(2)
        n = second & 0x7f
        if n == 126:
            n = struct.unpack(">H", self._read(2))[0]
        elif n == 127:
            n = struct.unpack(">Q", self._read(8))[0]
        return self._read(n).decode("utf-8", "replace")

    def eval(self, expression):
        self.next_id += 1
        self._send(json.dumps({"id": self.next_id, "method": "Runtime.evaluate",
                               "params": {"expression": expression, "returnByValue": True, "awaitPromise": True}}))
        while True:
            message = json.loads(self._recv())
            if message.get("id") == self.next_id:
                result = message["result"]
                if "exceptionDetails" in result:
                    raise SystemExit(json.dumps(result["exceptionDetails"])[:400])
                return result["result"].get("value")


def main():
    page = Page(serial_of(sys.argv[1]))
    if page.eval(GUARD):
        raise SystemExit("a recovery-phrase screen may be up: nothing was read")
    print(json.dumps(page.eval(sys.argv[2])))


if __name__ == "__main__":
    main()
