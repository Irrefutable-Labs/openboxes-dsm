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
     "This is the DSM version of OpenBoxes, the open-source warehouse system used in hospitals and supply chains. Same screens, same daily work: receiving, shipping, counting.",
     "DSM is our deterministic state tracker: immutable, forward-only, with post-quantum cryptographic authority. No intermediary server, no blockchain validators; it runs at the edge, and where there is no internet two phones over Bluetooth will do. Underneath OpenBoxes it decides who may act and who holds which goods. Every figure here is a real entry on its storage nodes.", None),
    ("No passwords. Marcus, Northgate's owner, signs in with his phone",
     "In the DSM version nobody has a password, and there is no way to set one. Nothing to phish, nothing to leak, nothing to reset.",
     "The login is a signature. Marcus's phone wallet shows him a sentence naming this browser's one-time code; approving it signs that sentence with his key. Only this browser gets in, and the signature cannot be replayed.", "northgate"),
    ("Marcus gives Priya a seat as warehouse manager",
     "Normally access is a role ticked in a user record: any administrator can change it, and nobody can audit it afterwards.",
     "Here access is a seat: one of five seat objects Northgate's owner issued, locked in a vault on DSM that names Priya as its holder. OpenBoxes only reads that vault. Priya cannot pass the seat on, and only Marcus can take it back.", None),
    ("500 boxes of amoxicillin arrive: $9,200 of stock",
     "OpenBoxes records the goods-received note and then the stock count, as it always has.",
     "Nothing counts until the product registry approves it on its own phone: 500 units of the amoxicillin object, one per box, move into Northgate's custody on DSM. Only then can the count be recorded. Had the registry said no, the books could not move.", "northgate"),
    ("Priya signs in. Her access comes from her seat, not from a database role",
     "OpenBoxes shows her the warehouse manager's screens.",
     "Her wallet signed her in the same way Marcus's did. On every click, OpenBoxes checks her seat against the storage nodes: still hers, not revoked.", "priya"),
    ("Priya ships 120 boxes to Lakeside from her phone: $2,208",
     "On her phone, OpenBoxes creates the shipment the normal way: picked from the stock on hand, packed, sent, paperwork and all.",
     "Before any of that is written, 120 amoxicillin units move from Northgate's account to Lakeside's on DSM. Had DSM refused the move, OpenBoxes would have recorded nothing.", "priya"),
    ("Priya hands the boxes to the driver, Dev Okafor of Meridian Freight",
     "Normally the carrier is a name on a manifest. Between the two warehouses, no system knows who actually holds the goods.",
     "Northgate's account sends 120 custody units to the driver's wallet, one unit per box. He has never logged in anywhere; he only has a wallet. He now provably holds the goods, and nothing else of value.", "priya"),
    ("At Lakeside's door, Amara asks the driver's wallet to hand over; he approves on his phone; she takes the boxes in",
     "Lakeside's own OpenBoxes knows nothing of this shipment yet. Its books say zero, and its door screen will not let anything be taken in.",
     "The door screen reads 0 of 120 handed over. Amara's request reaches the driver's phone; his approval sends the 120 units to Lakeside's account. Only now does the shipment exist for Lakeside, and the boxes can be taken in.", "lakeside"),
    ("Amara counts 130, then 120, on her phone",
     "Any warehouse system accepts whatever quantity is typed, and the error lives on in the books.",
     "DSM moved 120, so a count of 130 is refused on the spot, with the reason. 120 is received, and Lakeside's books match the custody record exactly.", "lakeside"),
    ("Marcus revokes Priya's seat from his phone",
     "Normally an administrator edits the user record or resets a password, and hopes every open session notices.",
     "Marcus's wallet takes the seat back in its vault; the decision is final on the storage nodes. Priya's very next click is refused, and the seat is Northgate's to give to someone else.", "northgate"),
    ("Northgate proves what Lakeside owns, without access to Lakeside's systems",
     "Normally this takes a phone call, a spreadsheet export or a shared login into the other company's system.",
     "Northgate's account asks Lakeside's account to prove what it owns. Lakeside's account answers against the storage nodes, and Northgate's checks the proof itself: 120 units.", None),
    ("Someone edits the warehouse database by hand: 40 boxes appear, $736",
     "The stock screens now show 40 boxes that do not exist. Nothing in an ordinary system can tell the difference.",
     "DSM never moved them. The continuous check compares the books with the custody record, finds the 40-box gap, and signs a finding that cannot be edited away.", None),
    ("The correction is a new entry, never a rewrite",
     "An adjustment transaction brings the books back to the truth, the same way a warehouse always corrects a count.",
     "The finding stays on the record, and the correction is signed with who made it and why. History is added to, never rewritten.", None),
    ("What DSM adds to the warehouse system people already use",
     "OpenBoxes keeps running the warehouse, unchanged for the people who use it.",
     "No passwords. Seats that can be counted and revoked. Custody two companies can verify without trusting each other. Books that cannot quietly drift. DSM did that here without changing how anyone works, with post-quantum authority and no server in the middle, reaching the end of a supply chain where there is no internet at all. It does the same for any system that keeps books on things of value.", None),
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
