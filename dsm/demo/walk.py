#!/usr/bin/env python3
"""The whole demo end to end without a phone: setup.py's reset, then every beat, the browser steps included, each ending
on its check. The people's wallets are terminal wallets (terminal_wallet.py) and each person's browser is a desktop
Chrome of their own (browser.py). Screens a reader should see are saved to run/shots/.

    walk.py              reset (setup.py), then beats 1 to 13
    walk.py beats        beats 1 to 13 on the run setup.py made
    walk.py N [N ...]    only those beats

Run it with a Python that has `protobuf` (DSM's Python client needs it), DSM_CHECKOUT naming the DSM code the hosts are
built from, and JAVA_HOME a JDK 8. What it leaves untested: the phones' wallet screens and Chrome on a phone.
"""
import importlib.util
import json
import os
import subprocess
import sys
import time

os.environ["DSM_DEMO_WALLETS"] = "terminal"
HERE = os.path.dirname(os.path.abspath(__file__))


def load(name):
    spec = importlib.util.spec_from_file_location(name, os.path.join(HERE, name + ".py"))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


setup = load("setup")
play = load("play")
web, one, need, wait, say = play.web, play.one, play.need, play.wait, setup.say
marcus, priya, carrier, amara = play.marcus, play.priya, play.carrier, play.amara
NORTHGATE, LAKESIDE = "http://a.localhost:8081/openboxes", "http://b.localhost:8082/openboxes"
SHOTS = os.path.join(HERE, "run", "shots")


def shot(who, name, top=None):
    """One screen of what the person sees, scrolled so the element `top` names is at the top."""
    os.makedirs(SHOTS, exist_ok=True)
    time.sleep(4)  # the side panel of what just happened reads the account every few seconds
    path = web.screenshot(who, os.path.join(SHOTS, name + ".png"), top=top)
    print("  screenshot:", os.path.relpath(path, HERE))


def page(who, script):
    return web.evaluate(who, script)


def until(who, script, what, seconds=90):
    wait(seconds, lambda: page(who, script), what)


def sign_in(who, base, username):
    """The sign-in page: the username, and the browser asks the person's wallet. Returns what the page then says."""
    web.open_url(who, base + "/dsmWalletSignIn/index")
    page(who, "document.getElementById('username').value = %s; document.getElementById('ask').dispatchEvent(new Event('submit', {cancelable: true})); 1"
         % json.dumps(username))
    until(who, "/code [0-9A-Z]{4}/.test(document.getElementById('asked').textContent)", "the page names this browser's code", 60)
    return page(who, "document.getElementById('asked').textContent")


def signed_in(who):
    until(who, "location.pathname.indexOf('/dsmWalletSignIn') < 0", "the browser is signed in", 90)


def dsm_page(who, base):
    web.open_url(who, base + "/dsmWallet/index")
    until(who, "document.getElementById('seat-wallet').options.length > 0 && document.getElementById('admit-product').options.length > 0",
          "the DSM page has read the account", 90)


def choose(who, select_id, words):
    """Pick the option of a select on the page whose text holds `words`; return its text."""
    chosen = page(who, "var s = document.getElementById(%s); var o = Array.prototype.filter.call(s.options, function (o) { return o.text.indexOf(%s) >= 0; })[0];"
                       " if (!o) throw new Error('not offered: ' + Array.prototype.map.call(s.options, function (o) { return o.text; }).join('|')); s.value = o.value; o.text"
                  % (json.dumps(select_id), json.dumps(words)))
    print("  chose:", chosen)
    return chosen


def record_stock(who, product_code, lot, quantity, note):
    """Record Stock, as the stock page's form posts it, from the person's own signed-in browser."""
    now = time.localtime()
    form = {"inventory.id": "1", "transactionDate": "date.struct",
            "transactionDate_day": str(now.tm_mday), "transactionDate_month": str(now.tm_mon), "transactionDate_year": str(now.tm_year),
            "transactionDate_hour": str(now.tm_hour), "transactionDate_minute": str(now.tm_min), "comment": note,
            "recordInventoryRows[0].lotNumber": lot, "recordInventoryRows[0].expirationDate": "date.struct",
            "recordInventoryRows[0].expirationDate_day": "31", "recordInventoryRows[0].expirationDate_month": "3",
            "recordInventoryRows[0].expirationDate_year": "2028", "recordInventoryRows[0].oldQuantity": "0",
            "recordInventoryRows[0].newQuantity": str(quantity), "save": "Save"}
    script = ("fetch('/openboxes/api/generic/product/?max=100', {credentials: 'same-origin', headers: {Accept: 'application/json'}})"
              ".then(function (r) { return r.json(); }).then(function (j) {"
              " var p = j.data.filter(function (p) { return p.productCode === %s; })[0]; var f = new URLSearchParams(%s); f.set('product.id', p.id);"
              " return fetch('/openboxes/inventoryItem/saveRecordInventory', {method: 'POST', credentials: 'same-origin', body: f}); })"
              ".then(function (r) { return r.status; })") % (json.dumps(product_code), json.dumps(form))
    print("  Record Stock answered", page(who, script))


def count(units):
    """Amara's count on the Receive tab, typed as a person types it: the button names the count."""
    play.on_phone(amara, "var i = document.querySelector('form[data-receive] input'); i.value = '%d';"
                         " i.dispatchEvent(new Event('input', {bubbles: true})); 1" % units)
    time.sleep(1)


def beat(n, title):
    print(flush=True)
    say("BEAT %d: %s" % (n, title))


def run(n):
    if n == 1:
        beat(1, "the setup: Northgate's sign-in page, no seat, no shipment, Marcus owns Northgate on the nodes")
        web.open_url("marcus", NORTHGATE + "/dsmWalletSignIn/index")
        play.CHECKS[1]()
    elif n == 2:
        beat(2, "Marcus signs in with his wallet: a username, then a signature, no password")
        print("  the page says:", sign_in("marcus", NORTHGATE, "marcus.hale"))
        shot("marcus", "01-sign-in")
        play.do(2)
        signed_in("marcus")
        print("  Marcus's browser is at", page("marcus", "location.pathname"))
    elif n == 3:
        beat(3, "Marcus gives Priya a seat as warehouse manager")
        need(int(one("a", "select count(*) from dsm_seat where status='ASSIGNED'")) == 0, "no seat held yet")
        dsm_page("marcus", NORTHGATE)
        choose("marcus", "seat-level", "Warehouse manager")
        page("marcus", "document.getElementById('seat-wallet').value = %s; 1" % json.dumps(str(priya_wallet_id())))
        page("marcus", "document.getElementById('seat-location').value = '1'; document.getElementById('seat-first').value = 'Priya';"
                       " document.getElementById('seat-last').value = 'Nair'; document.getElementById('assign').click(); 1")
        wait(120, lambda: one("a", "select status from dsm_seat where number=1") == "ASSIGNED", "seat #1 locked naming Priya")
        until("marcus", "document.getElementById('seats').innerText.indexOf('Priya') >= 0", "the seats table shows Priya", 60)
        shot("marcus", "02-seats", top=".card:has(#levels)")
        play.CHECKS[3]()
    elif n == 4:
        beat(4, "500 boxes of amoxicillin: the registry's wallet releases them, then the count is recorded")
        dsm_page("marcus", NORTHGATE)
        choose("marcus", "admit-product", "AMX500")
        page("marcus", "document.getElementById('admit-quantity').value = '500'; document.getElementById('admit-reference').value = 'GRN-77114';"
                       " document.getElementById('admit').click(); 1")
        marcus.show("Pay 500")
        marcus.approve("Pay 500")
        wait(240, lambda: one("a", "select status from dsm_admission where reference='GRN-77114'") == "ESTABLISHED", "500 boxes released")
        record_stock("marcus", "AMX500", "AMX-2611", 500, "Goods received, GRN-77114")
        play.CHECKS[4]()
    elif n == 5:
        beat(5, "Priya signs in with her wallet; her access is her seat")
        print("  the page says:", sign_in("priya", NORTHGATE, "priya.nair"))
        play.do(5)
        signed_in("priya")
    elif n == 6:
        beat(6, "Priya ships 120 boxes to Lakeside from the door screen: custody moves on DSM first")
        play.do(6)
        shot("priya", "03-door-send")
    elif n == 7:
        beat(7, "Priya hands the boxes to the driver: 120 custody units go to his wallet")
        play.do(7)
        shot("priya", "04-door-with-carrier")
    elif n == 8:
        beat(8, "Lakeside's door: Amara asks the driver's wallet to hand over, he approves, she takes it in")
        play.do(8)
    elif n == 9:
        beat(9, "counting at Lakeside: 130 is refused, 120 is received")
        play.tap(amara, "[data-go=receive]")
        time.sleep(3)
        play.tap(amara, "[data-count]")
        time.sleep(3)
        count(130)
        play.tap(amara, "form[data-receive] button[type=submit]")
        wait(60, lambda: "DSM moved 120" in play.on_phone(amara, "document.getElementById('toast').textContent"), "130 refused")
        print("  the door screen says:", play.on_phone(amara, "document.getElementById('toast').textContent"))
        shot("amara", "05-receive-refused")
        count(120)
        play.tap(amara, "form[data-receive] button[type=submit]")
        wait(90, lambda: one("b", "select quantity_received from dsm_custody_transfer where kind='INBOUND'") == "120", "120 received")
        print("  the door screen says:", play.on_phone(amara, "document.getElementById('toast').textContent"))
        play.CHECKS[9]()
    elif n == 10:
        beat(10, "Marcus revokes the seat from his wallet; Priya's next request is refused")
        play.do(10)
        web.open_url("priya", NORTHGATE + "/dashboard/index")
        until("priya", "location.pathname.indexOf('/dsmWalletSignIn') >= 0", "Priya's browser is back at the sign-in page", 90)
        print("  Priya's browser is at", page("priya", "location.pathname + location.search"))
        print("  it says:", page("priya", "(document.querySelector('.refused') || {}).textContent || ''").strip())
    elif n == 11:
        beat(11, "Northgate asks Lakeside's account to prove what it owns of the shipment")
        dsm_page("marcus", NORTHGATE)
        until("marcus", "document.querySelector('[data-prove]') !== null", "the transfers table offers Verify partner", 60)
        page("marcus", "document.querySelector('[data-prove]').click(); 1")
        play.do(11)
        print("  the page says:", page("marcus", "document.getElementById('dsm-message').textContent"))
        dsm_page("marcus", NORTHGATE)
        until("marcus", "document.getElementById('transfers').innerText.indexOf('120') >= 0", "the proof shows on the page", 60)
        shot("marcus", "06-custody-on-record", top=".card:has(#transfers)")
    elif n == 12:
        beat(12, "someone edits Northgate's database by hand: 40 boxes; the reconciler finds the gap and signs it")
        print(" ", subprocess.run(["bash", os.path.join(HERE, "tamper.sh")], capture_output=True, text=True, timeout=60,
                                  env=setup.ENV).stdout.strip().replace("\n", "\n  "))
        dsm_page("marcus", NORTHGATE)
        for _ in range(2):
            page("marcus", "document.getElementById('check-now').click(); 1")
            time.sleep(6)
        wait(120, lambda: one("a", "select count(*) from dsm_reconcile_event where kind='GAP' and gap=40") == "1", "the 40-box gap found and signed")
        play.CHECKS[12]()
    elif n == 13:
        beat(13, "corrected forward: a new entry, and the finding stays")
        until("marcus", "document.querySelector('[data-correct]') !== null", "the books card offers the correction", 90)
        page("marcus", "var b = document.querySelector('[data-correct]'); document.getElementById('why-' + b.getAttribute('data-correct')).value = "
                       "'Books edited by hand; matched to DSM'; b.click(); 1")
        wait(120, lambda: one("a", "select count(*) from dsm_reconcile_event where kind='CORRECTED'") == "1", "corrected by a new entry")
        play.CHECKS[13]()
        dsm_page("marcus", NORTHGATE)
        until("marcus", "document.getElementById('findings').innerText.indexOf('CORRECTED') >= 0", "the findings show the correction", 60)
        shot("marcus", "07-books-corrected", top=".card:has(#reconcile)")


def priya_wallet_id():
    """The DSM page's record of Priya's wallet, found by the start of her wallet's identity."""
    prefix = priya.id()[:8]
    state = json.loads(page("marcus", "fetch('/openboxes/dsmWallet/state', {credentials: 'same-origin', headers: {Accept: 'application/json'}})"
                                      ".then(function (r) { return r.text(); })"))
    return next(w["id"] for w in state["data"]["wallets"] if w["deviceId"] == prefix)


def main(args):
    started = time.time()
    if not args:
        setup.main()
        args = ["beats"]
    beats = list(range(1, 14)) if args == ["beats"] else [int(a) for a in args]
    for n in beats:
        run(n)
    print(flush=True)
    say("WALKED: beats %s in %d s, every check ok" % (", ".join(map(str, beats)), time.time() - started))


if __name__ == "__main__":
    main(sys.argv[1:])
