#!/usr/bin/env python3
"""A clean demo, ready to record, on the live storage fleet and the two real wallets. Run it again to start over; each earlier
run is moved aside, never deleted.

Four phones. Marcus Hale owns Northgate (RFGYB0PQ8XK, which also stands in as the product registry). Priya Nair is
Northgate's warehouse manager (RFGYB0PQ9FF), connected to Northgate as a person. Dev Okafor drives for Meridian Freight
(R5CW620MQVL): a wallet only, connected to both organizations, never signed in anywhere. Amara Osei owns Lakeside
(RF8Y90PX5GN). Nothing is left to do but the story.

With DSM_DEMO_WALLETS=terminal there are no phones: the four people's wallets are terminal wallets (terminal_wallet.py,
each a dsm-app-host of its own, as DSM's `make dev-wallet` runs them), Marcus's wallet issues the run's objects itself,
and nothing touches adb.

Environment: DSM_CHECKOUT (the DSM code the hosts are built from; default ../dsm-main beside this checkout), DSM_BIN
(where its dsm-app-host is; default DSM_CHECKOUT/target/release), JAVA_HOME (a JDK 8), DSM_DEMO_CONTAINERS (the prefix
of this run's two MySQL containers; default openboxes-demo).
"""
import http.cookiejar
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("seed", HERE + "/seed.py")
seed = importlib.util.module_from_spec(spec)
spec.loader.exec_module(seed)
ROOT = os.path.abspath(os.path.join(HERE, "..", ".."))
D = os.path.abspath(os.path.join(ROOT, ".."))
# The phones by hardware serial: adb lists them by mDNS name (or IP when connected by address), and both hold the serial.
NORTHGATE, PRIYA, CARRIER, LAKESIDE = "RFGYB0PQ8XK", "RFGYB0PQ9FF", "R5CW620MQVL", "RF8Y90PX5GN"
CARRIER_NAME = "Dev Okafor, Meridian Freight"
SEAT_TICKER = "NGSEAT"
CUSTODY_TICKER = "NGCUST"
CUSTODY_UNITS = 1000
# NGCUST: Northgate's custody object, issued once in Marcus's wallet; one unit is one unit of goods in someone's hands.
CUSTODY = "9H16Q9S3XG2G3Z659QMGEZ5QJNWS0SJJ94DQXZMAP6N8F969SVYG"
AMX, ORS, SEATS = ("C4P4XGDK0SM8KYHN9EYB6BQ5MTZ29NQAB2P7AFHWKV09P8B0JMR0", "0PYXZVSMZ8Z27XXXSH0S9RNC20PJA5H2QQH4GVR4AXJ4RHDRBS5G",
                   "W0KWAVP5S6PXHMP6D134A2RFWETGR075348X5XFAX76WE8N4CGXG")
# The phones' objects above were issued on the beta fleet before its reprovisioning of 2026-11-11 and are not on it now:
# a phone run issues them again in Marcus's wallet (wallet_token.sh) and puts their anchors here. A terminal run issues
# its own every time.
TERMINAL = os.environ.get("DSM_DEMO_WALLETS") == "terminal"
# Docker names this run's two databases with this prefix, so another checkout's demo databases are never touched.
CONTAINERS = os.environ.get("DSM_DEMO_CONTAINERS") or "openboxes-demo"
DSM_CHECKOUT = os.environ.get("DSM_CHECKOUT") or D + "/dsm-main"
ENV = dict(os.environ, DSM_CHECKOUT=DSM_CHECKOUT, DSM_BIN=os.environ.get("DSM_BIN") or DSM_CHECKOUT + "/target/release",
           JAVA_HOME=os.environ.get("JAVA_HOME") or D + "/jdk8-zulu/zulu8.98.0.107-ca-jdk8.0.512-macosx_aarch64/Contents/Home",
           DSM_DEMO_CONTAINERS=CONTAINERS)


def say(text):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), text), flush=True)


def sh(*args, timeout=120):
    return subprocess.run(args, capture_output=True, text=True, timeout=timeout, env=ENV).stdout


def sql(org, statement):
    return sh("docker", "exec", CONTAINERS + "-" + org, "mysql", "-uopenboxes", "-popenboxes", "openboxes", "-Nse", statement)


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
        """Add an object to this wallet by its anchor (Tokens, Add Token), so the wallet can be sent it."""
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


if TERMINAL:
    # The people's wallets in the terminal stand where the phones stood: same calls, same words to approve.
    spec = importlib.util.spec_from_file_location("terminal_wallet", HERE + "/terminal_wallet.py")
    terminal = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(terminal)
    Phone = terminal.TerminalWallet


def short_id(phone, fallback):
    """The first eight characters of a wallet's identity, as the DSM page lists it."""
    return phone.id()[:8] if TERMINAL else fallback


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

    def claim(self, phone):
        """The first run: this wallet connects through the sign-in page's code and signs the owner statement. The session
        this client holds is the owner's afterwards. There is no password anywhere: the page wants the one-time setup code
        the server wrote to its data root, which only whoever runs the server can read, as this script does here."""
        self.call("/dsmWalletSignIn/index", raw=True)
        # The gate, proven each reset: without the code a stranger's browser is offered nothing and the claim route refuses.
        page = self.call("/dsmWalletSignIn/index", raw=True)
        if 'id="setup"' not in page or "Scan code" in page:
            raise SystemExit("the sign-in page offered a claim without the setup code")
        for wrong in ("", "?setup=NOTTHECODE00"):
            try:
                urllib.request.urlopen(urllib.request.Request(self.base + "/dsmWalletSignIn/claim" + wrong, headers={"Accept": "application/json"}), timeout=60)
                raise SystemExit("the claim route answered without the setup code")
            except urllib.error.HTTPError as refused:
                if refused.code != 403:
                    raise SystemExit("the claim route answered %d without the setup code" % refused.code)
        say("  the claim is gated: refused without the setup code")
        setup_file = HERE + "/run/org-" + ("a" if self.base.endswith(":8081/openboxes") else "b") + "/setup-code"
        for _ in range(20):
            if os.path.exists(setup_file):
                break
            time.sleep(1)
        with open(setup_file) as f:
            setup = f.read().strip()
        code = self.call("/dsmWalletSignIn/claim?setup=" + urllib.parse.quote(setup))
        phone.connect(code["code"])
        asked = False
        for _ in range(60):
            step = self.call("/dsmWalletSignIn/claim", form={})
            if step.get("state") == "SIGNED_IN":
                return code["offerDigest"]
            if step.get("state") == "FAILED":
                raise SystemExit("the owner claim failed: " + str(step.get("reason")))
            if step.get("state") == "ASKED" and not asked:
                phone.approve("I own")
                asked = True
            time.sleep(4)
        raise SystemExit("the owner claim never finished")

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


def seeded(org, name, port):
    """seed.py's client over an organization's owner session."""
    instance = seed.Instance(name, port)
    instance.opener = org.open
    return instance


def keep_two_old_runs():
    """Earlier runs are moved aside, not deleted, so a take can be looked into afterwards; two are enough. Older ones go:
    their directories here and their two database containers with the containers' own volumes (2026-11-05, the owner's
    word, after sixty of them filled Docker's disk)."""
    olds = sorted((d for d in os.listdir(HERE) if d.startswith("run-before-")), key=lambda d: os.path.getmtime(os.path.join(HERE, d)))
    for old in olds[:-2]:
        stamp = old[len("run-before-"):]
        for org in ("a", "b"):
            sh("docker", "rm", "-fv", "%s-%s-before-%s" % (CONTAINERS, org, stamp))
        shutil.rmtree(os.path.join(HERE, old), ignore_errors=True)
        say("  an old run is gone: " + old)
    # containers whose run directory is already gone (earlier naming) go the same way
    listed = sh("docker", "ps", "-a", "--format", "{{.Names}}").split()
    kept = {"%s-%s-before-%s" % (CONTAINERS, org, d[len("run-before-"):]) for d in olds[-2:] for org in ("a", "b")}
    for name in listed:
        if re.match(r"^%s-(a|b)-before-" % re.escape(CONTAINERS), name) and name not in kept:
            sh("docker", "rm", "-fv", name)


def fresh():
    if not TERMINAL:
        say("connected wallets leave the old run")
        for ip in (NORTHGATE, PRIYA, CARRIER, LAKESIDE):
            Phone(ip).disconnect_all()
    say("rebuilding")
    built = subprocess.run(["./gradlew", "--no-daemon", "--console=plain", "war", "bootRepackage", "-x", "npm_run_bundle", "-x", "npmInstall"],
                           cwd=ROOT, env=ENV, capture_output=True, text=True)
    if built.returncode:
        raise SystemExit(built.stdout[-1500:])
    say("the old run moves aside")
    # Only this checkout's processes: each names this demo's directory (its script, or a data root under run/), so a
    # host or OpenBoxes another checkout or session runs on this machine is left alone.
    subprocess.run(["pkill", "-f", HERE + "/presenter.py"])
    subprocess.run(["pkill", "-f", HERE + "/run/"])
    subprocess.run(["pkill", "-f", HERE + "/network.sh"])
    time.sleep(1)
    subprocess.run(["pkill", "-f", HERE + "/run/"])
    for _ in range(90):
        if not sh("pgrep", "-f", HERE + "/run/").strip():
            break
        time.sleep(1)
    if TERMINAL:
        browser().stop_all()
    stamp = time.strftime("%Y%m%d-%H%M%S")
    if os.path.isdir(HERE + "/run"):
        os.rename(HERE + "/run", HERE + "/run-before-" + stamp)
    keep_two_old_runs()
    for org in ("a", "b"):
        sh("docker", "stop", CONTAINERS + "-" + org)
        sh("docker", "rename", CONTAINERS + "-" + org, "%s-%s-before-%s" % (CONTAINERS, org, stamp))
    sh("bash", HERE + "/mysql.sh", timeout=280)
    sh("python3", HERE + "/up.py", "network")
    # Each host holds its ingress until the network can find its account (up to two minutes): a run waits for all of them.
    for _ in range(180):
        if "READY" in open(HERE + "/logs/network.log").read():
            break
        time.sleep(2)
    else:
        raise SystemExit("the DSM hosts did not start: " + open(HERE + "/logs/network.log").read()[-1500:])
    sh("python3", HERE + "/up.py", "a", "b")
    say("OpenBoxes is starting")
    for port in (8081, 8082):
        seed.Instance("openboxes", port).wait_up()


def browser():
    """The desktop browser a terminal run drives (browser.py)."""
    spec = importlib.util.spec_from_file_location("browser", HERE + "/browser.py")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def terminal_objects(marcus, *others):
    """A terminal run's objects, issued in Marcus's wallet as he would on his phone (Tokens, Create token): the two
    products' objects, burnable because a stock decrease at a DSM warehouse burns its units; the seat object, whose supply
    is the most seats there can ever be; and the custody object. Their anchors are kept in run/objects.json."""
    say("the four wallets get ERA from the network's faucet, for their storage fees")
    for wallet in (marcus,) + others:
        say("  %s: %s" % (wallet.name, wallet.fund()))
    issued = {}
    for symbol, name, supply, burnable in (("AMX500", "Amoxicillin 500 mg capsules, box of 100", 1000000, True),
                                           ("ORS020", "Oral rehydration salts, pack of 20", 1000000, True),
                                           (SEAT_TICKER, "Northgate warehouse manager seat", 5, False),
                                           (CUSTODY_TICKER, "Northgate custody unit", 1000000, False)):
        issued[symbol] = marcus.issue(symbol, name, supply, burnable=burnable)
        say("  Marcus's wallet issued %s %s: %s" % (supply, symbol, issued[symbol]))
    with open(HERE + "/run/objects.json", "w") as out:
        json.dump(issued, out, indent=1)
    return issued["AMX500"], issued["ORS020"], issued[SEAT_TICKER], issued[CUSTODY_TICKER]


def main():
    fresh()
    north, lake = Org(8081), Org(8082)
    marcus, priya, carrier, amara = Phone(NORTHGATE), Phone(PRIYA), Phone(CARRIER), Phone(LAKESIDE)
    amx, ors_anchor, seats, custody_anchor = terminal_objects(marcus, priya, carrier, amara) if TERMINAL else (AMX, ORS, SEATS, CUSTODY)
    for org, first, last, username, email in (("a", "Marcus", "Hale", "marcus.hale", "marcus.hale@northgate.example"),
                                              ("b", "Amara", "Osei", "amara.osei", "amara.osei@lakeside.example")):
        sql(org, "update person p join user u on u.id=p.id set p.first_name='%s', p.last_name='%s', p.email='%s' where u.username='admin'; "
                 "update user set username='%s' where username='admin'" % (first, last, email, username))
    # The warehouses are named before any wallet connects: a wallet keeps the name it was shown at connect time.
    sql("a", "update location set name='Northgate Central Warehouse' where id='1'; update location set name='Lakeside District Hospital' where id='2'")
    sql("b", "update location set name='Lakeside Hospital Pharmacy' where id='1'; update location set name='Northgate Medical Distribution' where id='2'")

    say("Marcus claims Northgate with his wallet: the first run, no password anywhere")
    digest = north.claim(marcus)
    say("Amara claims Lakeside with hers")
    lake.claim(amara)
    for org, who in ((north, "Marcus Hale"), (lake, "Amara Osei")):
        owners = [w["label"] for w in org.call("/dsmWallet/state")["wallets"] if w["owner"]]
        if owners != [who]:
            raise SystemExit("the owner claim did not land: %s owns %s" % (owners, who))

    say("the sample data goes in, as each owner")
    seed.seed(seeded(north, "Northgate Medical Distribution (sample)", 8081), "Northgate Central Warehouse",
              "Lakeside District Hospital", [(p[0], p[2], p[4]) for p in seed.PRODUCTS], "Pharmaceuticals")
    seed.seed(seeded(lake, "Lakeside District Hospital (sample)", 8082), "Lakeside Hospital Pharmacy",
              "Northgate Medical Distribution", [(p[1], p[3], p[5]) for p in seed.PRODUCTS], "Medicines")

    say("products on DSM, and the two organizations connect")
    north_account, lake_account = north.account(), lake.account()
    for org, code, anchor in ((north, "AMX500", amx), (north, "ORS020", ors_anchor), (lake, "PH-10412", amx), (lake, "PH-30145", ors_anchor)):
        org.call("/api/dsm/tokens/adoptions", form={"productId": org.product(code), "policyAnchor": anchor})
    offer = north.call("/api/dsm/connections/offers", form={"accountId": north_account})
    lake.call("/api/dsm/connections/accepts", form={"accountId": lake_account, "code": offer["code"], "peerLocationId": "2"})
    north.call("/api/dsm/connections/confirmations", form={"accountId": north_account, "offerDigest": offer["offerDigest"], "peerLocationId": "2"})

    say("Marcus's wallet also stands in as the product registry")
    north.call("/dsmWallet/answered", form={"offerDigest": digest, "purpose": "registry"})

    say("seats: the level and five released")
    me = north.call("/dsmWallet/state")["me"]["id"]
    level = north.call("/dsmWallet/addLevel", form={"name": "Warehouse manager", "roleType": "ROLE_MANAGER", "policyAnchor": seats, "creatorId": me})
    if level["ticker"] != SEAT_TICKER:
        raise SystemExit("the seat object's anchor names %s, not %s" % (level["ticker"], SEAT_TICKER))
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

    say("custody: Northgate's custody object, %d units for its account, Lakeside roots it, and the driver's wallet adds it" % CUSTODY_UNITS)
    custody = north.call("/dsmWallet/addCustodyToken", form={"policyAnchor": custody_anchor, "creatorId": me})
    if custody["ticker"] != CUSTODY_TICKER:
        raise SystemExit("the custody object's anchor names %s, not %s" % (custody["ticker"], CUSTODY_TICKER))
    north.call("/dsmWallet/requestCustodyUnits", form={"quantity": str(CUSTODY_UNITS)})
    marcus.approve("Pay %d %s" % (CUSTODY_UNITS, CUSTODY_TICKER))
    for _ in range(40):
        if int(north.call("/dsmWallet/state")["custody"]["own"]["spare"]) >= CUSTODY_UNITS:
            break
        time.sleep(4)
    else:
        raise SystemExit("the custody units never reached the account: spare is %s" % north.call("/dsmWallet/state")["custody"]["own"]["spare"])
    connection = lake.call("/dsmWallet/state")["custody"]["connections"][0]["id"]
    lake.call("/dsmWallet/addCustodyToken", form={"policyAnchor": custody_anchor, "connectionId": connection})
    carrier.adopt(custody_anchor, CUSTODY_TICKER)

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

    if not TERMINAL:
        # The story bar shows the phones' screens; a terminal run has none to show.
        subprocess.Popen(["python3", HERE + "/presenter.py"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, stdin=subprocess.DEVNULL, start_new_session=True)
    # The last look before anyone records: the figures the first scenes rest on.
    state = north.call("/dsmWallet/state")
    check = [("Northgate owner", [w["label"] for w in state["wallets"] if w["owner"]] == ["Marcus Hale"]),
             ("Priya's wallet connected", any(w["deviceId"] == short_id(priya, "P58TPCQX") for w in state["wallets"])),
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
