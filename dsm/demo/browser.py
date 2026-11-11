#!/usr/bin/env python3
"""A desktop Chrome per person, driven over the DevTools protocol, for a run without phones (DSM_DEMO_WALLETS=terminal).

The same calls as phone_web.py, keyed by the person's name instead of a phone's serial: open_url, evaluate, front. Each
person has a Chrome of their own (its own profile), so two people signed in to the same organization keep their own
sessions, as two phones would. Headless unless DSM_DEMO_HEADLESS=0.

    browser.py NAME open URL | eval 'javascript' | shot FILE [WIDTH] | stop
"""
import base64
import importlib.util
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(HERE, "run")
spec = importlib.util.spec_from_file_location("wcdp", HERE + "/wallet_cdp.py")
wcdp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wcdp)
CHROME = os.environ.get("CHROME_BIN", "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome")
PORTS = {"marcus": 9341, "priya": 9342, "amara": 9343, "dev": 9344}
# Each organization at its own hostname: cookies are not separated by port.
SITES = {8081: "a.localhost", 8082: "b.localhost"}
WIDTH, HEIGHT = 1400, 900


def host(port=8081):
    return SITES[port]


def _port(name):
    return PORTS[name]


def _pages(name):
    return [p for p in json.load(urllib.request.urlopen("http://127.0.0.1:%d/json" % _port(name), timeout=5))
            if p.get("type") == "page"]


def start(name):
    """This person's Chrome, started when it is not running."""
    try:
        if _pages(name):
            return
    except OSError:
        pass
    profile = os.path.join(RUN, "browsers", name)
    os.makedirs(profile, exist_ok=True)
    args = [CHROME, "--remote-debugging-port=%d" % _port(name), "--user-data-dir=" + profile, "--no-first-run",
            "--no-default-browser-check", "--disable-extensions", "--window-size=%d,%d" % (WIDTH, HEIGHT), "about:blank"]
    if os.environ.get("DSM_DEMO_HEADLESS", "1") != "0":
        args.insert(1, "--headless=new")
    log = open(os.path.join(RUN, "browsers", name + ".log"), "ab")
    subprocess.Popen(args, stdout=log, stderr=subprocess.STDOUT, stdin=subprocess.DEVNULL, start_new_session=True)
    for _ in range(60):
        try:
            if _pages(name):
                return
        except OSError:
            time.sleep(0.5)
    raise SystemExit("%s's Chrome did not start" % name)


class Tab(wcdp.Page):
    """The person's one tab, over its DevTools socket."""

    def __init__(self, name):
        start(name)
        page = _pages(name)[0]
        self.sock = socket.create_connection(("127.0.0.1", _port(name)), timeout=120)
        path = re.sub(r"^ws://[^/]+", "", page["webSocketDebuggerUrl"])
        key = base64.b64encode(os.urandom(16)).decode()
        self.sock.sendall(("GET %s HTTP/1.1\r\nHost: 127.0.0.1\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                           "Sec-WebSocket-Key: %s\r\nSec-WebSocket-Version: 13\r\n\r\n" % (path, key)).encode())
        reply = b""
        while b"\r\n\r\n" not in reply:
            reply += self.sock.recv(4096)
        self.next_id = 0

    def call(self, method, params=None):
        self.next_id += 1
        self._send(json.dumps({"id": self.next_id, "method": method, "params": params or {}}))
        while True:
            message = json.loads(self._recv())
            if message.get("id") == self.next_id:
                if "error" in message:
                    raise SystemExit("%s: %s" % (method, message["error"]))
                return message.get("result", {})


def open_url(name, url):
    tab = Tab(name)
    tab.call("Emulation.setDeviceMetricsOverride", {"width": WIDTH, "height": HEIGHT, "deviceScaleFactor": 1, "mobile": False})
    tab.call("Page.navigate", {"url": url})
    for _ in range(120):
        time.sleep(0.5)
        try:
            if Tab(name).eval("document.readyState") == "complete":
                time.sleep(1)
                return
        except (SystemExit, OSError):
            continue
    raise SystemExit("%s's page did not load: %s" % (name, url))


def evaluate(name, script):
    return Tab(name).eval(script)


def front(name):
    """A phone's Chrome is brought back after its wallet; a desktop Chrome never left."""


def screenshot(name, path, width=WIDTH, full=False, top=None):
    """What the person sees: one screen, `width` pixels wide, scrolled so the element `top` names (a CSS selector) is at
    the top when given; with `full`, the whole page as tall as its content (at most four screens)."""
    tab = Tab(name)
    height = HEIGHT
    if full:
        height = tab.eval("Math.max(document.documentElement.scrollHeight, document.body ? document.body.scrollHeight : 0)") or HEIGHT
        height = max(HEIGHT, min(int(height), 4 * HEIGHT))
    tab.call("Emulation.setDeviceMetricsOverride", {"width": width, "height": height, "deviceScaleFactor": 1, "mobile": False})
    if top:
        tab.eval("var e = document.querySelector(%s); if (e) { e.scrollIntoView(); window.scrollBy(0, -12); } 1" % json.dumps(top))
    time.sleep(1)
    shot = tab.call("Page.captureScreenshot", {"format": "png"})
    with open(path, "wb") as out:
        out.write(base64.b64decode(shot["data"]))
    tab.call("Emulation.setDeviceMetricsOverride", {"width": WIDTH, "height": HEIGHT, "deviceScaleFactor": 1, "mobile": False})
    return path


def stop(name):
    subprocess.run(["pkill", "-f", "--", "--remote-debugging-port=%d" % _port(name)])


def stop_all():
    for name in PORTS:
        stop(name)
    shutil.rmtree(os.path.join(RUN, "browsers"), ignore_errors=True)


if __name__ == "__main__":
    who, action = sys.argv[1], sys.argv[2]
    if action == "open":
        open_url(who, sys.argv[3])
    elif action == "eval":
        value = evaluate(who, sys.argv[3])
        print(value if isinstance(value, str) else json.dumps(value))
    elif action == "shot":
        print(screenshot(who, sys.argv[3], int(sys.argv[4]) if len(sys.argv) > 4 else WIDTH))
    elif action == "stop":
        stop(who)
