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
# Which phone each name is: Marcus (Northgate's owner, and the registry), Priya (Northgate's manager), the carrier, Amara (Lakeside).
PHONES = {"northgate": "RFGYB0PQ8XK", "priya": "RFGYB0PQ9FF", "carrier": "R5CW620MQVL", "lakeside": "RF8Y90PX5GN"}

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
     "Her wallet signs in, and her vault is read from the storage nodes on every single click.", "priya"),
    ("Priya ships 120 boxes to Lakeside from her phone: $2,208",
     "OpenBoxes creates the shipment and its paperwork from the stock on hand.",
     "Custody moves on DSM before the books change. If DSM refuses, nothing is recorded.", "priya"),
    ("Priya hands the boxes to the driver, Dev Okafor of Meridian Freight",
     "OpenBoxes has no idea who is carrying what; a paper manifest at best.",
     "Northgate's account pays 120 custody units to the driver's wallet. He holds the goods now, and nothing else of value.", "priya"),
    ("At Lakeside's door, Amara asks the driver's wallet to hand over; he approves on his phone; she takes the boxes in",
     "Lakeside's own OpenBoxes knows nothing yet: its books still say zero, and nothing can be taken in.",
     "Lakeside's door screen shows 0 of 120 handed over. His wallet pays the units to Lakeside's account; only then can the boxes be taken in.", "lakeside"),
    ("Amara counts 130, then 120, on her phone",
     "OpenBoxes would normally accept whatever someone types.",
     "DSM moved 120, so 130 is refused on the spot. Receiving 120 brings the books into line.", "lakeside"),
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

# Scenes that happen on the phone: its screen is shown large, the page behind it dimmed.
ON_THE_PHONE = {6, 7, 8, 9}

state = {"scene": 0, "focus": [], "phone": None}
images = {}


def capture(name, part):
    while True:
        try:
            serial = next((line.rsplit("\t", 1)[0] for line in subprocess.run(["adb", "devices"], capture_output=True, text=True, timeout=10).stdout.splitlines()[1:]
                           if "\t" in line and part in line.rsplit("\t", 1)[0] and line.rsplit("\t", 1)[1].strip() == "device"), None)
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
        self.answer(json.dumps({"n": n + 1, "of": len(SCENES), "title": title, "web2": web2, "dsm": dsm, "phone": state["phone"] or phone,
                                "stage": "phone" if n + 1 in ON_THE_PHONE else "page", "focus": state["focus"]}).encode(), "application/json")

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
        if self.path == "/phone":
            # Which phone to show for the rest of this scene, when the scene's own is not the one acting: a name from PHONES, or nothing.
            state["phone"] = body if body in PHONES else None
        elif self.path == "/focus":
            # What to spotlight now, separated by |: a CSS selector, or card:<selector>, text:<words> or row:<words>.
            state["focus"] = [item for item in body.split("|") if item]
        elif self.path == "/next":
            state["scene"], state["focus"], state["phone"] = min(state["scene"] + 1, len(SCENES) - 1), [], None
        elif self.path == "/prev":
            state["scene"], state["focus"], state["phone"] = max(state["scene"] - 1, 0), [], None
        elif self.path.startswith("/scene/"):
            state["scene"], state["focus"], state["phone"] = max(0, min(int(self.path.split("/")[-1]) - 1, len(SCENES) - 1)), [], None
        self.scene()


for name, part in PHONES.items():
    threading.Thread(target=capture, args=(name, part), daemon=True).start()
ThreadingHTTPServer(("127.0.0.1", PORT), Handler).serve_forever()
