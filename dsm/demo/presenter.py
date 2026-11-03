#!/usr/bin/env python3
"""What the screen says during the demo, what it spotlights, and what each phone shows now.

Serves the current scene and live pictures of the two wallets to the story bar in OpenBoxes (views/common/_dsmDemo.gsp), so
both organizations' tabs stay in step. Change scene with the N and P keys in the page, or POST /next, /prev or /scene/<n>.
Start this only after the wallets are set up: it photographs their screens.
"""
import json
import subprocess
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

PORT = 8799
PHONES = {"northgate": "192.168.4.39", "lakeside": "192.168.4.40"}

# title, what the ordinary system does, what DSM does, which phone to show
SCENES = [
    ("Northgate Medical ships medicines to Lakeside District Hospital",
     "OpenBoxes stays the warehouse system people already use.",
     "DSM takes over who may act and who holds what. Every amount here is a real state change on live storage nodes.", None),
    ("No passwords. Marcus, Northgate's owner, signs in with his phone",
     "OpenBoxes has no password to check, and refuses them all.",
     "His wallet signs the words on its screen, naming the code this browser shows. Only that browser gets in.", "northgate"),
    ("Marcus gives Priya a seat as warehouse manager",
     "OpenBoxes just reads what the seat allows.",
     "Seats are capped, and locked in a vault naming her. She holds it, and only Marcus can take it back.", None),
    ("500 boxes of amoxicillin arrive: $9,200 of stock",
     "OpenBoxes records the goods-received note.",
     "Nothing counts until the product registry approves it on its own phone, and units enter custody.", "northgate"),
    ("Priya signs in. Her access comes from her seat, not from a database role",
     "OpenBoxes shows her manager's screens.",
     "Her wallet signs in, and her vault is read from the storage nodes on every single click.", "lakeside"),
    ("Priya ships 120 boxes to Lakeside: $2,208",
     "OpenBoxes creates the shipment and its paperwork.",
     "Custody moves on DSM before the books change. If DSM refuses, nothing is recorded.", None),
    ("Lakeside already holds the goods, before anyone there records them",
     "Lakeside's own OpenBoxes knows nothing yet: its books still say zero.",
     "Lakeside's own DSM account holds 120 boxes. Two organizations, no shared system.", "lakeside"),
    ("Lakeside tries to receive too many, then receives the right amount",
     "OpenBoxes would normally accept whatever someone types.",
     "DSM moved 120, so 130 is refused. Receiving 120 brings the books into line.", None),
    ("Marcus revokes Priya's seat from his phone",
     "Nobody edits a user, resets a password or waits for a nightly job.",
     "He decides it in his wallet. Her very next click is refused, and the seat is back with Northgate.", "northgate"),
    ("Northgate proves what Lakeside holds, without access to Lakeside's systems",
     "No login, no export, no shared database.",
     "Lakeside's account proves its holding against the storage nodes, and Northgate checks the proof itself.", None),
    ("Someone edits the warehouse database by hand: 40 boxes appear, $736",
     "The stock screens now show 40 boxes that do not exist.",
     "DSM never moved them. The check finds the gap and signs a finding that cannot be edited away.", None),
    ("The correction is a new entry, never a rewrite",
     "A new adjustment brings the books back to the truth.",
     "The finding stays on the record, and the correction names who made it and why.", None),
    ("What DSM adds to the warehouse system people already use",
     "OpenBoxes keeps running the warehouse.",
     "No passwords. Seats that can be counted and revoked. Custody two companies can verify. Books that cannot quietly drift.", None),
]

state = {"scene": 0, "focus": []}
images = {}


def capture(name, part):
    while True:
        try:
            serial = next((line.split()[0] for line in subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=10).stdout.splitlines()
                           if line.startswith(part) and line.split()[-1] == "device"), None)
            if serial:
                png = subprocess.run(["adb", "-s", serial, "exec-out", "screencap", "-p"], capture_output=True, timeout=15).stdout
                if png.startswith(b"\x89PNG"):
                    images[name] = png
        except (subprocess.SubprocessError, OSError):
            pass
        time.sleep(0.7)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def answer(self, body, kind):
        self.send_response(200)
        self.send_header("Content-Type", kind)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def scene(self):
        n = state["scene"]
        title, web2, dsm, phone = SCENES[n]
        self.answer(json.dumps({"n": n + 1, "of": len(SCENES), "title": title, "web2": web2, "dsm": dsm, "phone": phone, "focus": state["focus"]}).encode(), "application/json")

    def do_GET(self):
        if self.path.startswith("/phone/"):
            png = images.get(self.path[len("/phone/"):].split(".")[0].split("?")[0])
            if png:
                self.answer(png, "image/png")
            else:
                self.send_error(404)
        else:
            self.scene()

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        body = self.rfile.read(length).decode() if length else ""
        if self.path == "/focus":
            # What to spotlight now, separated by |: a CSS selector, or card:<selector>, text:<words> or row:<words>.
            state["focus"] = [item for item in body.split("|") if item]
        elif self.path == "/next":
            state["scene"], state["focus"] = min(state["scene"] + 1, len(SCENES) - 1), []
        elif self.path == "/prev":
            state["scene"], state["focus"] = max(state["scene"] - 1, 0), []
        elif self.path.startswith("/scene/"):
            state["scene"], state["focus"] = max(0, min(int(self.path.split("/")[-1]) - 1, len(SCENES) - 1)), []
        self.scene()


for name, part in PHONES.items():
    threading.Thread(target=capture, args=(name, part), daemon=True).start()
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
