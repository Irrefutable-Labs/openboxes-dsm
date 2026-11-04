#!/usr/bin/env python3
"""Chrome on a connected phone, driven the way the wallet is (DevTools over adb), for the door screen in the demo:

    phone_web.py SERIAL_PART open URL            one OpenBoxes tab at a time: the phone keeps one session per host
    phone_web.py SERIAL_PART eval 'javascript'   in the OpenBoxes tab
    phone_web.py SERIAL_PART front               bring Chrome back after the wallet

Nothing of the wallet: wallet_cdp.py is reused only for the wire protocol and adb."""
import base64
import importlib.util
import json
import os
import re
import socket
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("wcdp", HERE + "/wallet_cdp.py")
wcdp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wcdp)
PORT = 9333
SITE = "/openboxes"


def host():
    """The Mac's address on the phones' network."""
    return subprocess.run(["ipconfig", "getifaddr", "en0"], capture_output=True, text=True).stdout.strip()


def tabs(serial):
    """Chrome's tabs; Chrome answers only while it runs, so it is started when it does not."""
    wcdp.adb(serial, "forward", "tcp:%d" % PORT, "localabstract:chrome_devtools_remote")
    for attempt in range(3):
        try:
            pages = json.load(urllib.request.urlopen("http://127.0.0.1:%d/json" % PORT, timeout=8))
            return [p for p in pages if p.get("type") == "page"]
        except (OSError, ValueError):
            if attempt == 2:
                raise
            front(serial)
            time.sleep(3)


def close(serial, part=SITE):
    for page in tabs(serial):
        if part in page.get("url", ""):
            try:
                urllib.request.urlopen("http://127.0.0.1:%d/json/close/%s" % (PORT, page["id"]), timeout=10).read()
            except OSError:
                pass


def open_url(serial, url):
    close(serial)
    wcdp.adb(serial, "shell", "input", "keyevent", "KEYCODE_WAKEUP")
    wcdp.adb(serial, "shell", "am", "start", "-a", "android.intent.action.VIEW", "-d", url, "com.android.chrome")
    # Chrome may be cold or behind the wallet: the tab is waited for, not assumed.
    for _ in range(12):
        time.sleep(2)
        if any(SITE in page.get("url", "") for page in tabs(serial)):
            time.sleep(3)
            return
    front(serial)
    time.sleep(4)


def front(serial):
    wcdp.adb(serial, "shell", "am", "start", "-n", "com.android.chrome/com.google.android.apps.chrome.Main")
    time.sleep(2)


class Chrome(wcdp.Page):
    def __init__(self, serial, part=SITE):
        found = [p for p in tabs(serial) if part in p.get("url", "")]
        if not found:
            raise SystemExit("no Chrome tab at %s on %s" % (part, serial))
        self.sock = socket.create_connection(("127.0.0.1", PORT), timeout=120)
        path = re.sub(r"^ws://[^/]+", "", found[0]["webSocketDebuggerUrl"])
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall(("GET %s HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (path, key)).encode())
        reply = b""
        while b"\r\n\r\n" not in reply:
            reply += self.sock.recv(4096)
        self.next_id = 0


def evaluate(serial, script, part=SITE):
    return Chrome(serial, part).eval(script)


if __name__ == "__main__":
    serial, action = wcdp.serial_of(sys.argv[1]), sys.argv[2]
    if action == "open":
        open_url(serial, sys.argv[3])
    elif action == "eval":
        value = evaluate(serial, sys.argv[3])
        print(value if isinstance(value, str) else json.dumps(value))
    elif action == "front":
        front(serial)
