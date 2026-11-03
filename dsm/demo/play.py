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
import subprocess
import sys
import time
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
spec = importlib.util.spec_from_file_location("setup", HERE + "/setup.py")
setup = importlib.util.module_from_spec(spec)
spec.loader.exec_module(setup)
Phone, sql, say = setup.Phone, setup.sql, setup.say
marcus, priya = Phone(setup.NORTHGATE), Phone(setup.LAKESIDE)


def scene(n, focus=""):
    urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8799/scene/%s" % n, data=b"", method="POST"), timeout=5)
    urllib.request.urlopen(urllib.request.Request("http://127.0.0.1:8799/focus", data=focus.encode(), method="POST"), timeout=5)


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
    7: lambda: need(one("b", "select count(*) from shipment") == "1", "Lakeside made its shipment record"),
    8: lambda: need(one("b", "select quantity_received from dsm_custody_transfer where kind='INBOUND'") == "120", "Lakeside received 120"),
    9: lambda: print("  ok: the verdict is final on the storage nodes; the seat row comes back when Marcus next opens the DSM page"),
    10: lambda: [need(one("a", "select status from dsm_seat where number=1") == "RETURNED", "seat #1 returned"),
                 need(one("a", "select proven_amount from dsm_custody_transfer where kind='SHIPMENT'") == "120", "Lakeside's holding proven: 120")],
    11: lambda: need(one("a", "select count(*) from dsm_reconcile_event where kind='GAP' and gap=40") == "1", "the 40-box gap found and signed"),
    12: lambda: need(one("a", "select count(*) from dsm_reconcile_event where kind='CORRECTED'") == "1", "corrected by a new entry"),
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
    elif n == 7:
        priya.show("Sign in to Lakeside")
        time.sleep(6)
        priya.approve("Sign in to Lakeside")
        wait(60, lambda: one("b", "select count(*) from dsm_authority_record where kind='SIGN_IN'") >= "1", "Priya signed in at Lakeside")
    elif n == 9:
        cell = one("a", "select verdict_cell from dsm_seat where number=1")
        marcus.front()
        out = subprocess.run(["bash", HERE + "/wallet_revoke.sh", setup.NORTHGATE, cell], capture_output=True, text=True, timeout=280).stdout
        need("Final: revoke" in out, "revoked on Marcus's phone")
    elif n == 10:
        for _ in range(6):
            subprocess.run([sys.executable, "-I", HERE + "/partner_answers.py"], capture_output=True, text=True, timeout=280)
            if one("a", "select proven_amount from dsm_custody_transfer where kind='SHIPMENT'") == "120":
                break
            time.sleep(5)
    elif n == 11:
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
