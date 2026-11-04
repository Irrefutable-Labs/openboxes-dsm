#!/usr/bin/env python3
"""The demo, beat by beat, with a check before and after each one.

    play.py check N          what beat N needs is in place, or say what is missing
    play.py do N             the phone and account work of beat N, then its check
    play.py scene N 'focus'  the story bar and spotlight only

The browser steps a viewer should see (typing a name, sending, receiving) are done in the browser; everything a phone or an
account does, and every check, is here, so a beat never starts on a wrong footing. Checks read the databases and the
storage nodes, never a screen.
"""
import importlib.util
import json
import os
import re
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("setup", HERE + "/setup.py")
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)
Phone, sql, say = setup.Phone, setup.sql, setup.say
marcus, priya, carrier, amara = Phone(setup.NORTHGATE), Phone(setup.PRIYA), Phone(setup.CARRIER), Phone(setup.LAKESIDE)
spec = importlib.util.spec_from_file_location("phone_web", HERE + "/phone_web.py")
web = importlib.util.module_from_spec(spec)
spec.loader.exec_module(web)


def scene(n, focus=""):
    urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8799/scene/%s" % n, data=b"", method="POST"), timeout=5)
    urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8799/focus", data=focus.encode(), method="POST"), timeout=5)


def show_phone(name):
    """Which phone the recording shows for the rest of the scene: northgate, priya, carrier, lakeside, or '' for the scene's own."""
    urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8799/phone", data=name.encode(), method="POST"), timeout=5)


def one(org, statement):
    return sql(org, statement).strip()


def records(org="a"):
    return one(org, "select group_concat(kind order by date_created) from dsm_authority_record")


def need(ok, what):
    if not ok:
        raise SystemExit("NOT READY: " + what)
    print("  ok:", what)


def wait(seconds, test, what):
    for _ in range(int(seconds / 3)):
        if test():
            print("  ok:", what)
            return
        time.sleep(3)
    raise SystemExit("NOT DONE in %d s: %s" % (seconds, what))


def on_phone(phone, script):
    return web.evaluate(phone.serial, script)


def tap(phone, selector):
    on_phone(phone, "document.querySelector(%s).click(); 1" % json.dumps(selector))


def pick(phone, selector, words):
    """Choose the option naming `words` in the select(s) `selector` on the door screen, and say what was chosen."""
    return on_phone(phone, "Array.prototype.map.call(document.querySelectorAll(%s), function (s) { var o = Array.prototype.filter.call(s.options, function (o) { return o.text.indexOf(%s) >= 0; })[0]; if (o) s.value = o.value; return s.options[s.selectedIndex].text; }).join('|')"
                           % (json.dumps(selector), json.dumps(words)))


def pick_and_tap(phone, selector, words, button):
    """Choose, then tap, in one step on the phone: nothing redraws the screen between the two."""
    chosen = on_phone(phone, "var chosen = Array.prototype.map.call(document.querySelectorAll(%s), function (s) { var o = Array.prototype.filter.call(s.options, function (o) { return o.text.indexOf(%s) >= 0; })[0]; if (o) s.value = o.value; return s.options[s.selectedIndex].text; }).join('|');"
                             " if (chosen.indexOf(%s) < 0) throw new Error('not offered: ' + chosen); document.querySelector(%s).click(); chosen"
                             % (json.dumps(selector), json.dumps(words), json.dumps(words), json.dumps(button)))
    print("  chose:", chosen)


def wallet_history(phone):
    """The wallet's own screen, Wallet then History: what it has just been paid."""
    phone.front()
    phone.cdp("(function(){var e=[].slice.call(document.querySelectorAll('.dsm-menu-item')).filter(function(x){return x.textContent.trim()=='WALLET'})[0];if(e)e.click();return 1})()")
    time.sleep(2)
    phone.press("HISTORY", 2)


def door_screen(phone, port, username):
    """Open an organization's door screen in Chrome on the phone, signing in with the wallet on the same phone."""
    web.open_url(phone.serial, "http://%s:%d/openboxes/dsmPhone" % (web.host(), port))
    if on_phone(phone, "location.pathname").endswith("/dsmPhone"):
        return
    asked = on_phone(phone, "document.getElementById('username').value=%s; document.getElementById('ask').dispatchEvent(new Event('submit', {cancelable: true}));"
                            " new Promise(function (r) { setTimeout(function () { r(document.getElementById('asked').textContent); }, 3000); })" % json.dumps(username))
    code = re.search(r"code ([0-9A-Z]{4})", asked).group(1)
    time.sleep(3)
    phone.approve("Browser code " + code)
    web.front(phone.serial)
    wait(60, lambda: on_phone(phone, "location.pathname").endswith("/dsmPhone"), "the phone is on the door screen")
    time.sleep(2)


CHECKS = {
    1: lambda: [need(one("a", "select count(*) from dsm_seat") == "0", "no seat yet"),
                need(one("a", "select count(*) from shipment") == "0", "no shipment yet"),
                need("OWNER" in records(), "Marcus owns Northgate on the nodes")],
    2: lambda: need(one("a", "select count(*) from dsm_authority_record where kind='SIGN_IN'") >= "1", "Marcus signed in"),
    3: lambda: need(one("a", "select status from dsm_seat where number=1") == "ASSIGNED", "seat #1 assigned to Priya"),
    4: lambda: [need(one("a", "select status from dsm_admission where reference='GRN-77114'") == "ESTABLISHED", "500 boxes released"),
                need(one("a", "select quantity_drawn from dsm_admission where reference='GRN-77114'") == "500", "500 boxes recorded")],
    5: lambda: need(one("a", "select count(*) from dsm_authority_record where kind='SIGN_IN' and wallet like 'P58T%'") >= "1", "Priya signed in"),
    6: lambda: need(one("a", "select quantity from dsm_custody_transfer where kind='SHIPMENT'") == "120", "120 boxes moved on DSM"),
    7: lambda: need(one("a", "select units from dsm_carriage where kind='DISPATCHED'") == "120", "120 custody units in the driver's wallet"),
    8: lambda: [need(one("b", "select units from dsm_carriage where kind='HANDED_OVER'") == "120", "120 custody units handed over at Lakeside"),
                need(one("b", "select count(*) from shipment") == "1", "Lakeside made its shipment record")],
    9: lambda: need(one("b", "select quantity_received from dsm_custody_transfer where kind='INBOUND'") == "120", "Lakeside received 120"),
    10: lambda: print("  ok: the verdict is final on the storage nodes; the seat row comes back when Marcus next opens the DSM page"),
    11: lambda: [need(one("a", "select status from dsm_seat where number=1") == "RETURNED", "seat #1 returned"),
                 need(one("a", "select proven_amount from dsm_custody_transfer where kind='SHIPMENT'") == "120", "Lakeside's holding proven: 120")],
    12: lambda: need(one("a", "select count(*) from dsm_reconcile_event where kind='GAP' and gap=40") == "1", "the 40-box gap found and signed"),
    13: lambda: need(one("a", "select count(*) from dsm_reconcile_event where kind='CORRECTED'") == "1", "corrected by a new entry"),
}


def do(n):
    if n == 2:
        marcus.show("Sign in to Northgate")
        time.sleep(6)
        marcus.approve("Sign in to Northgate")
        wait(60, lambda: one("a", "select count(*) from dsm_authority_record where kind='SIGN_IN'") >= "1", "signed in")
    elif n == 3:
        need(int(one("a", "select count(*) from dsm_seat where status='ASSIGNED'")) == 0, "no seat held yet")
    elif n == 4:
        marcus.show("Pay 500")
        time.sleep(6)
        marcus.approve("Pay 500")
        wait(240, lambda: one("a", "select status from dsm_admission where reference='GRN-77114'") == "ESTABLISHED", "500 boxes released")
    elif n == 5:
        priya.show("as priya.nair")
        time.sleep(6)
        priya.approve("as priya.nair")
        wait(60, lambda: one("a", "select count(*) from dsm_authority_record where kind='SIGN_IN' and wallet like 'P58T%'") >= "1", "Priya signed in")
    elif n == 6:
        # On her phone: Northgate's Send tab, a new shipment from stock: 120 AMX500 to Lakeside, shipped.
        scene(6)
        door_screen(priya, 8081, "priya.nair")
        tap(priya, "[data-go=send]")
        time.sleep(4)
        tap(priya, "[data-new=send]")
        time.sleep(5)
        on_phone(priya, "var row = Array.prototype.filter.call(document.querySelectorAll('form[data-send] .count'), function (r) { return r.getAttribute('data-find').indexOf('amx500') >= 0; })[0];"
                        " var i = row.querySelector('input'); i.value = '120'; i.dispatchEvent(new Event('input', {bubbles: true}));"
                        " document.getElementById('send-note').value = 'Lakeside monthly replenishment PO-77120'; 1")
        time.sleep(5)
        tap(priya, "form[data-send] button[type=submit]")
        wait(120, lambda: one("a", "select count(*) from dsm_custody_transfer where kind='SHIPMENT'") == "1", "shipped: custody moved on DSM")
        time.sleep(6)
    elif n == 7:
        # On Priya's phone: Northgate's Send tab, the driver chosen as carrier, Hand to carrier. Then the driver's wallet, which now holds the units.
        scene(7)
        door_screen(priya, 8081, "priya.nair")
        tap(priya, "[data-go=send]")
        time.sleep(5)
        print("  picker shows:", pick(priya, "select[id^=dispatch-]", "Dev Okafor"))
        time.sleep(4)
        pick_and_tap(priya, "select[id^=dispatch-]", "Dev Okafor", "[data-dispatch]")
        wait(90, lambda: one("a", "select count(*) from dsm_carriage where kind='DISPATCHED'") == "1", "handed to the driver")
        need("Dev Okafor" in one("a", "select coalesce(w.name, '') from dsm_carriage c join dsm_wallet w on w.device_id = c.wallet_device_id where c.kind='DISPATCHED'"),
             "the units went to the driver's wallet")
        time.sleep(5)
        show_phone("carrier")
        wallet_history(carrier)
        time.sleep(8)
        show_phone("")
    elif n == 8:
        # Amara's phone: Lakeside's door says 0 of 120 handed over; she asks the driver's wallet; he approves on his phone; she takes in.
        scene(8)
        door_screen(amara, 8082, "amara.osei")
        time.sleep(7)
        on_phone(amara, "var d = document.querySelector('#arriving details'); if (d) d.open = true; 1")
        time.sleep(3)
        print("  picker shows:", pick(amara, "select[id^=carrier-]", "Dev Okafor"))
        time.sleep(3)
        pick_and_tap(amara, "select[id^=carrier-]", "Dev Okafor", "[data-handover][data-pick]")
        time.sleep(4)
        show_phone("carrier")
        carrier.approve("Pay 120")
        time.sleep(3)
        show_phone("")
        wait(120, lambda: "Take in" in on_phone(amara, "document.getElementById('arriving').innerText"), "handed over on the door screen")
        time.sleep(6)
        tap(amara, "[data-take]")
        wait(90, lambda: one("b", "select count(*) from shipment") == "1", "taken in")
        time.sleep(4)
    elif n == 9:
        # Amara's phone: count 130, refused; count 120, received.
        scene(9)
        tap(amara, "[data-go=receive]")
        time.sleep(4)
        tap(amara, "[data-count]")
        time.sleep(4)
        on_phone(amara, "document.querySelector('form[data-receive] input').value = '130'; 1")
        time.sleep(3)
        tap(amara, "form[data-receive] button[type=submit]")
        wait(60, lambda: "DSM moved 120" in on_phone(amara, "document.getElementById('toast').textContent"), "130 refused on the phone")
        time.sleep(8)
        on_phone(amara, "document.querySelector('form[data-receive] input').value = '120'; 1")
        time.sleep(3)
        tap(amara, "form[data-receive] button[type=submit]")
        wait(90, lambda: one("b", "select quantity_received from dsm_custody_transfer where kind='INBOUND'") == "120", "120 received")
        time.sleep(4)
    elif n == 10:
        cell = one("a", "select verdict_cell from dsm_seat where number=1")
        marcus.front()
        out = subprocess.run(["bash", HERE + "/wallet_revoke.sh", setup.NORTHGATE, cell], capture_output=True, text=True, timeout=280).stdout
        need("Final: revoke" in out, "revoked on Marcus's phone")
    elif n == 11:
        for _ in range(6):
            subprocess.run([sys.executable, "-I", HERE + "/partner_answers.py"], capture_output=True, text=True, timeout=280)
            if one("a", "select proven_amount from dsm_custody_transfer where kind='SHIPMENT'") == "120":
                break
            time.sleep(5)
    elif n == 12:
        print(subprocess.run(["bash", HERE + "/tamper.sh"], capture_output=True, text=True, timeout=60).stdout.strip())
    CHECKS.get(n, lambda: None)()


if __name__ == "__main__":
    command, n = sys.argv[1], int(sys.argv[2])
    if command == "scene":
        scene(n, sys.argv[3] if len(sys.argv) > 3 else "")
    elif command == "check":
        CHECKS.get(n, lambda: print("  nothing to check"))()
    elif command == "do":
        do(n)
