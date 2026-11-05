#!/usr/bin/env python3
"""A clean demo, ready to record, on the live storage fleet and the two real wallets. Run it again to start over; each earlier
run is moved aside, never deleted.

Four phones. Marcus Hale owns Northgate (RFGYB0PQ8XK, which also stands in as the product registry). Priya Nair is
Northgate's warehouse manager (RFGYB0PQ9FF), connected to Northgate as a person. Dev Okafor drives for Meridian Freight
(R5CW620MQVL): a wallet only, connected to both organizations, never signed in anywhere. Amara Osei owns Lakeside
(RF8Y90PX5GN). Nothing is left to do but the story.
"""
import http.cookiejar
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
D = os.path.abspath(os.path.join(ROOT, ".."))
# The phones by hardware serial: adb lists them by mDNS name (or IP when connected by address), and both hold the serial.
NORTHGATE, PRIYA, CARRIER, LAKESIDE = "RFGYB0PQ8XK", "RFGYB0PQ9FF", "R5CW620MQVL", "RF8Y90PX5GN"
CARRIER_NAME = "Dev Okafor, Meridian Freight"
SEAT_TICKER = "NGSEAT"
CUSTODY_TICKER = "NGCUST"
CUSTODY_UNITS = 1000
# NGCUST: Northgate's custody token, made once in Marcus's wallet; one unit is one unit of goods in someone's hands.
CUSTODY = "9H16Q9S3XG2G3Z659QMGEZ5QJNWS0SJJ94DQXZMAP6N8F969SVYG"
AMX, ORS, SEATS = ("C4P4XGDK0SM8KYHN9EYB6BQ5MTZ29NQAB2P7AFHWKV09P8B0JMR0", "0PYXZVSMZ8Z27XXXSH0S9RNC20PJA5H2QQH4GVR4AXJ4RHDRBS5G",
                   "W0KWAVP5S6PXHMP6D134A2RFWETGR075348X5XFAX76WE8N4CGXG")
ENV = dict(os.environ, DSM_CHECKOUT=D + "/wt-fleet-reprovision", DSM_BIN=D + "/dsm-phone-host-target/release",
           JAVA_HOME=D + "/jdk8-zulu/zulu8.98.0.107-ca-jdk8.0.512-macosx_aarch64/Contents/Home")


def say(text):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), text), flush=True)


def sh(*args, timeout=120):
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout, env=ENV).stdout


def sql(org, statement):
    return sh("docker", "exec", "openboxes-demo-" + org, "mysql", "-uopenboxes", "-popenboxes", "openboxes", "-Nse", statement)


class Phone:
    def __init__(self, ip):
        self.ip = ip

    @property
    def serial(self):
        # a serial may hold spaces (an mDNS name): the line is "serial<TAB>device". A phone re-registering on the
        # network can be missing from the list for a few seconds; that is waited out, not failed on.
        for attempt in range(10):
            found = [l.rsplit("\t", 1)[0] for l in sh("adb", "devices").splitlines()[1:]
                     if "\t" in l and self.ip in l.rsplit("\t", 1)[0] and l.rsplit("\t", 1)[1].strip() == "device"]
            if found:
                return found[0]
            time.sleep(3)
        raise SystemExit("no connected phone matches " + self.ip)

    def adb(self, *args):
        return sh("adb", "-s", self.serial, *args)

    def cdp(self, script):
        return sh("python3", HERE + "/wallet_cdp.py", self.ip, script, timeout=40)

    def screen(self):
        return sh("bash", HERE + "/phone.sh", self.ip, "screen", timeout=60)

    def press(self, text, wait=4):
        return sh("bash", HERE + "/phone.sh", self.ip, "press", text, str(wait), timeout=90)

    def front(self):
        self.adb("shell", "input", "keyevent", "KEYCODE_WAKEUP")
        focus = self.adb("shell", "dumpsys", "window")
        focus = next((l for l in focus.splitlines() if "mCurrentFocus" in l), "")
        if "NotificationShade" in focus:
            self.adb("shell", "input", "swipe", "540", "1900", "540", "600", "250")
            time.sleep(2)
            focus = next((l for l in self.adb("shell", "dumpsys", "window").splitlines() if "mCurrentFocus" in l), "")
        if "com.dsm.wallet" not in focus:
            self.adb("shell", "am", "start", "-n", "com.dsm.wallet/.ui.MainActivity")
            time.sleep(4)

    def apps(self):
        self.front()
        for _ in range(6):
            text = self.screen()
            if "PRESS A" in text:
                sh("bash", HERE + "/phone.sh", self.ip, "click", ".button-a", "4", timeout=60)
                continue
            if "LATER" in text:
                self.press("LATER", 2)
                continue
            if '"CHECK"' in text:
                return
            if self.cdp("document.querySelectorAll('.dsm-menu-item').length").strip() not in ("", "0"):
                self.cdp("(function(){var e=[].slice.call(document.querySelectorAll('.dsm-menu-item')).filter(function(x){return x.textContent.trim()=='APPS'})[0];if(e)e.click();return 1})()")
                time.sleep(3)
                continue
            self.cdp("(function(){var b=document.querySelector('.button-b');if(b)b.click();return 1})()")
            time.sleep(2)

    def connect(self, code):
        self.apps()
        sh("bash", HERE + "/phone.sh", self.ip, "open", code, timeout=60)
        self.press("READ", 5)
        self.press("APPROVE", 10)

    def disconnect_all(self):
        self.apps()
        self.cdp("(function(){var b=[].slice.call(document.querySelectorAll('button')).filter(function(x){return x.innerText.trim()=='APPS'})[0];b&&b.click();return 1})()")
        time.sleep(2)
        for _ in range(8):
            if '"DISCONNECT"' not in self.screen():
                return
            self.press("DISCONNECT", 10)

    def adopt(self, anchor, ticker):
        """Add a token to this wallet by its anchor (Tokens, Add Token), so the wallet can be paid it."""
        self.front()
        for _ in range(4):
            if self.cdp("document.querySelectorAll('.dsm-menu-item').length").strip() not in ("", "0"):
                break
            self.cdp("(function(){var b=document.querySelector('.button-b');if(b)b.click();return 1})()")
            time.sleep(2)
        self.cdp("(function(){var e=[].slice.call(document.querySelectorAll('.dsm-menu-item')).filter(function(x){return (x.textContent||'').trim()==='TOKENS'})[0];if(e)e.click();return 1})()")
        time.sleep(4)
        if ticker in self.screen():
            return
        self.press("+ Add Token (CPTA)", 3)
        sh("python3", HERE + "/wallet_fill.py", self.ip, "add-token-anchor", anchor, timeout=60)
        time.sleep(1)
        self.press("Add", 6)
        for _ in range(15):
            if ticker in self.screen():
                return
            time.sleep(3)
        raise SystemExit("the wallet never showed " + ticker + " after adopting it")

    def show(self, words):
        """Open the Waiting tab until the request naming these words is on screen, and leave it there to be read."""
        self.apps()
        for _ in range(20):
            self.front()
            self.cdp("(function(){var b=[].slice.call(document.querySelectorAll('button')).filter(function(x){return /^WAITING/i.test(x.innerText.trim())})[0];b&&b.click();return 1})()")
            time.sleep(1)
            text = self.screen()
            if '"DECLINE"' in text and words in text:
                return
            self.press("CHECK", 8)
        raise SystemExit("the phone never showed: " + words)

    def approve(self, words):
        """Approve the waiting request naming these words, and return what the wallet said it did with it."""
        self.apps()
        for _ in range(20):
            self.front()
            self.cdp("(function(){var b=[].slice.call(document.querySelectorAll('button')).filter(function(x){return /^WAITING/i.test(x.innerText.trim())})[0];b&&b.click();return 1})()")
            time.sleep(1)
            text = self.screen()
            if '"DECLINE"' in text and words in text:
                self.press("APPROVE", 10)
                for _ in range(12):
                    said = self.screen()
                    if "Approving" in said and "\u2026" in said:
                        time.sleep(4)
                        continue
                    if "failed" in said and words in said:
                        raise SystemExit("the phone could not carry it out: " + said[said.find("Approving failed"):][:260])
                    return said
                return self.screen()
            self.press("CHECK", 8)
        raise SystemExit("the phone never showed: " + words)


class Org:
    def __init__(self, port):
        self.base = "http://127.0.0.1:%d/openboxes" % port
        self.open = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, path, form=None, body=None, raw=False):
        data, headers = None, {"Accept": "application/json"}
        if body is not None:
            data, headers["Content-Type"] = json.dumps(body).encode(), "application/json"
        elif form is not None:
            data = urllib.parse.urlencode(form).encode()
        for attempt in range(6):
            try:
                with self.open.open(urllib.request.Request(self.base + path, data=data, headers=headers), timeout=280) as r:
                    text = r.read().decode(errors="replace")
                break
            except urllib.error.HTTPError as refused:
                said = refused.read().decode(errors="replace")[:500]
                # DSM could not be read this moment: the storage nodes are asked again, never worked around.
                if refused.code == 503 and attempt < 5:
                    say("  DSM could not be read, asking again: " + said[-120:])
                    time.sleep(6)
                    continue
                raise SystemExit("%s answered %d: %s" % (path, refused.code, said))
        if raw:
            return text
        try:
            answer = json.loads(text)
        except ValueError:
            return text
        return answer.get("data", answer)

    def login(self, username):
        self.call("/api/login", body={"username": username, "password": "password", "location": "1"})

    def account(self):
        return [a for a in self.call("/api/dsm/accounts") if a.get("locationId")][0]["id"]

    def product(self, code):
        return next(p["id"] for p in self.call("/api/generic/product/?max=100") if p.get("productCode") == code)

    def wallet(self, purpose, phone, name=None):
        offer = self.call("/dsmWallet/offer", form={})
        phone.connect(offer["code"])
        form = {"offerDigest": offer["offerDigest"], "purpose": purpose}
        if name:
            form["name"] = name
        asked = False
        for _ in range(60):
            step = self.call("/dsmWallet/answered", form=form)
            if step.get("answered"):
                return offer["offerDigest"], step
            if step.get("waiting") and not asked:
                phone.approve("I own")
                asked = True
            time.sleep(4)
        raise SystemExit("no answer from the wallet for " + purpose)


def fresh():
    say("connected wallets leave the old run")
    for ip in (NORTHGATE, PRIYA, CARRIER, LAKESIDE):
        Phone(ip).disconnect_all()
    say("rebuilding")
    built = subprocess.run(["./gradlew", "--no-daemon", "--console=plain", "war", "bootRepackage", "-x", "npm_run_bundle", "-x", "npmInstall"],
                           cwd=ROOT, env=ENV, capture_output=True, text=True)
    if built.returncode:
        raise SystemExit(built.stdout[-1500:])
    say("the old run moves aside")
    subprocess.run(["pkill", "-f", "dsm/demo/presenter.py"])
    subprocess.run(["pkill", "-f", "openboxes.war"])
    subprocess.run(["pkill", "-f", "demo/network.sh"])
    time.sleep(1)
    subprocess.run(["pkill", "-f", "dsm-app-host"])
    for _ in range(90):
        if not sh("pgrep", "-f", "openboxes.war|dsm-app-host").strip():
            break
        time.sleep(1)
    stamp = time.strftime("%H%M%S")
    os.rename(HERE + "/run", HERE + "/run-before-" + stamp)
    for org in ("a", "b"):
        sh("docker", "stop", "openboxes-demo-" + org)
        sh("docker", "rename", "openboxes-demo-" + org, "openboxes-demo-%s-before-%s" % (org, stamp))
    sh("bash", HERE + "/mysql.sh", timeout=280)
    sh("python3", HERE + "/up.py", "network")
    for _ in range(90):
        if "READY" in open(HERE + "/logs/network.log").read():
            break
        time.sleep(2)
    sh("python3", HERE + "/up.py", "a", "b", "seed")
    say("OpenBoxes is starting, and the sample data goes in")
    for _ in range(150):
        if "READY" in open(HERE + "/logs/seed.log").read():
            return
        time.sleep(4)
    raise SystemExit("the seed never finished: " + open(HERE + "/logs/seed.log").read()[-500:])


def main():
    fresh()
    north, lake = Org(8081), Org(8082)
    marcus, priya, carrier, amara = Phone(NORTHGATE), Phone(PRIYA), Phone(CARRIER), Phone(LAKESIDE)
    for org, first, last, username, email in (("a", "Marcus", "Hale", "marcus.hale", "marcus.hale@northgate.example"),
                                              ("b", "Amara", "Osei", "amara.osei", "amara.osei@lakeside.example")):
        sql(org, "update person p join user u on u.id=p.id set p.first_name='%s', p.last_name='%s', p.email='%s' where u.username='admin'; "
                 "update user set username='%s' where username='admin'" % (first, last, email, username))
    north.login("marcus.hale")
    lake.login("amara.osei")

    say("products on DSM, and the two organizations connect, while each still has its setup login")
    north_account, lake_account = north.account(), lake.account()
    for org, code, anchor in ((north, "AMX500", AMX), (north, "ORS020", ORS), (lake, "PH-10412", AMX), (lake, "PH-30145", ORS)):
        org.call("/api/dsm/tokens/adoptions", form={"productId": org.product(code), "policyAnchor": anchor})
    offer = north.call("/api/dsm/connections/offers", form={"accountId": north_account})
    lake.call("/api/dsm/connections/accepts", form={"accountId": lake_account, "code": offer["code"], "peerLocationId": "2"})
    north.call("/api/dsm/connections/confirmations", form={"accountId": north_account, "offerDigest": offer["offerDigest"], "peerLocationId": "2"})

    say("Marcus claims Northgate on his phone")
    digest, _ = north.wallet("owner", marcus)
    north.call("/dsmWallet/answered", form={"offerDigest": digest, "purpose": "registry"})
    say("Amara claims Lakeside on her phone")
    lake.wallet("owner", amara)
    for org, who in ((north, "Marcus Hale"), (lake, "Amara Osei")):
        owners = [w["label"] for w in org.call("/dsmWallet/state")["wallets"] if w["owner"]]
        if owners != [who]:
            raise SystemExit("the owner claim did not land: %s owns %s" % (owners, who))

    say("seats: the level and five released")
    me = north.call("/dsmWallet/state")["me"]["id"]
    level = north.call("/dsmWallet/addLevel", form={"name": "Warehouse manager", "roleType": "ROLE_MANAGER", "policyAnchor": SEATS, "creatorId": me})
    if level["ticker"] != SEAT_TICKER:
        raise SystemExit("the seat token's anchor names %s, not %s" % (level["ticker"], SEAT_TICKER))
    north.call("/dsmWallet/requestSeats", form={"levelId": level["id"], "quantity": "5"})
    marcus.approve("Pay 5 " + SEAT_TICKER)
    for _ in range(40):
        if int(north.call("/dsmWallet/state")["levels"][0]["spare"]) >= 5:
            break
        time.sleep(4)
    else:
        raise SystemExit("the seats never reached the account: spare is %s" % north.call("/dsmWallet/state")["levels"][0]["spare"])

    say("Priya's wallet connects to Northgate")
    north.wallet("person", priya)
    say("the driver's wallet connects to Northgate and to Lakeside, named, never signed in")
    north.wallet("person", carrier, CARRIER_NAME)
    lake.wallet("person", carrier, CARRIER_NAME)

    say("custody: Northgate's custody token, %d units for its account, Lakeside roots it, and the driver's wallet adopts it" % CUSTODY_UNITS)
    custody = north.call("/dsmWallet/addCustodyToken", form={"policyAnchor": CUSTODY, "creatorId": me})
    if custody["ticker"] != CUSTODY_TICKER:
        raise SystemExit("the custody token's anchor names %s, not %s" % (custody["ticker"], CUSTODY_TICKER))
    north.call("/dsmWallet/requestCustodyUnits", form={"quantity": str(CUSTODY_UNITS)})
    marcus.approve("Pay %d %s" % (CUSTODY_UNITS, CUSTODY_TICKER))
    for _ in range(40):
        if int(north.call("/dsmWallet/state")["custody"]["own"]["spare"]) >= CUSTODY_UNITS:
            break
        time.sleep(4)
    else:
        raise SystemExit("the custody units never reached the account: spare is %s" % north.call("/dsmWallet/state")["custody"]["own"]["spare"])
    connection = lake.call("/dsmWallet/state")["custody"]["connections"][0]["id"]
    lake.call("/dsmWallet/addCustodyToken", form={"policyAnchor": CUSTODY, "connectionId": connection})
    carrier.adopt(CUSTODY, CUSTODY_TICKER)

    say("opening stock, so the warehouse is not empty: 200 packs of oral rehydration salts")
    ors = north.product("ORS020")
    north.call("/api/dsm/admissions", form={"accountId": north_account, "productId": ors, "quantity": "200", "reference": "GRN-77102"})
    marcus.approve("Pay 200")
    for _ in range(60):
        if any(a.get("status") == "ESTABLISHED" for a in north.call("/api/dsm/admissions")):
            break
        time.sleep(5)
    else:
        raise SystemExit("the 200 units were never released: " + json.dumps(north.call("/api/dsm/admissions"))[:300])
    now = time.localtime()
    north.call("/inventoryItem/saveRecordInventory", form={
        "product.id": ors, "inventory.id": "1", "transactionDate": "date.struct",
        "transactionDate_day": str(now.tm_mday), "transactionDate_month": str(now.tm_mon), "transactionDate_year": str(now.tm_year),
        "transactionDate_hour": str(now.tm_hour), "transactionDate_minute": str(now.tm_min), "comment": "Goods received, GRN-77102",
        "recordInventoryRows[0].lotNumber": "ORS-2611", "recordInventoryRows[0].expirationDate": "date.struct",
        "recordInventoryRows[0].expirationDate_day": "30", "recordInventoryRows[0].expirationDate_month": "6",
        "recordInventoryRows[0].expirationDate_year": "2028", "recordInventoryRows[0].oldQuantity": "0",
        "recordInventoryRows[0].newQuantity": "200", "save": "Save"}, raw=True)

    subprocess.Popen(["python3", HERE + "/presenter.py"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, start_new_session=True)
    # The last look before anyone records: the figures the first scenes rest on.
    state = north.call("/dsmWallet/state")
    check = [("Northgate owner", [w["label"] for w in state["wallets"] if w["owner"]] == ["Marcus Hale"]),
             ("Priya's wallet connected", any(w["deviceId"] == "P58TPCQX" for w in state["wallets"])),
             ("the driver's wallet connected at Northgate", any(w["label"] == CARRIER_NAME for w in state["wallets"])),
             ("the driver's wallet connected at Lakeside", any(w["label"] == CARRIER_NAME for w in lake.call("/dsmWallet/state")["wallets"])),
             ("Amara owns Lakeside", [w["label"] for w in lake.call("/dsmWallet/state")["wallets"] if w["owner"]] == ["Amara Osei"]),
             ("5 spare seats", int(state["levels"][0]["spare"]) >= 5),
             ("%d custody units" % CUSTODY_UNITS, int(state["custody"]["own"]["spare"]) >= CUSTODY_UNITS),
             ("Lakeside takes Northgate's hand-overs", len(lake.call("/dsmWallet/state")["custody"]["partners"]) == 1),
             ("no seat yet", not state["seats"]),
             ("200 ORS on the books", any(c["code"] == "ORS020" and c["onHand"] == 200 and c["dsmUnits"] == 200
                                         for c in north.call("/dsmWallet/timeline?after=0&web2After=0")["custody"]))]
    failed = [name for name, ok in check if not ok]
    if failed:
        raise SystemExit("not ready: " + ", ".join(failed))
    say("READY: " + ", ".join(name for name, _ in check))


if __name__ == "__main__":
    sys.exit(main())
