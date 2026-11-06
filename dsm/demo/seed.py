#!/usr/bin/env python3
"""The sample business data for the demo, put in through each organization's own OpenBoxes API: names, each catalog's
products under its own codes, prices in USD, and each organization's DSM account attached with network fees.

The companies, products and prices are made up. Everything on DSM is real and is done by people in their wallets: the
owners claiming their organizations, the registry creating product tokens and approving every release, seats, shipments
and proofs. Nothing here touches a wallet, a token or a seat.

The default admin login is the one the project's own install data creates, used here only until an owner's wallet takes
over (grails-app/migrations/install).
"""
import http.cookiejar
import json
import sys
import time
import urllib.error
import urllib.parse
import urllib.request


# Code at the distributor, code at the hospital, name at each, unit price in USD at each.
PRODUCTS = [
    ("AMX500", "PH-10412", "Amoxicillin 500 mg capsules, box of 100", "Amoxicillin 500mg caps (100s)", 18.40, 21.15),
    ("INS100", "PH-20877", "Insulin glargine 100 IU/mL, 10 mL vial", "Insulin glargine 10mL vial", 62.75, 71.90),
    ("ORS020", "PH-30145", "Oral rehydration salts, pack of 20", "ORS sachets (20s)", 7.20, 8.25),
]


class Instance:
    def __init__(self, name, port):
        self.name = name
        self.base = "http://127.0.0.1:%d/openboxes" % port
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def call(self, method, path, form=None, json_body=None):
        data, headers = None, {"Accept": "application/json"}
        if json_body is not None:
            data, headers["Content-Type"] = json.dumps(json_body).encode(), "application/json"
        elif form is not None:
            data, headers["Content-Type"] = urllib.parse.urlencode(form).encode(), "application/x-www-form-urlencoded"
        request = urllib.request.Request(self.base + path, data=data, headers=headers, method=method)
        try:
            with self.opener.open(request, timeout=300) as response:
                text = response.read().decode()
        except urllib.error.HTTPError as refused:
            raise SystemExit("%s: %s %s answered %d: %s" % (self.name, method, path, refused.code, refused.read().decode()[:600]))
        try:
            return json.loads(text).get("data")
        except ValueError:
            return text

    def wait_up(self):
        """Until the server answers. Signing in is the caller's: there are no passwords, the owner's wallet claims the organization."""
        deadline = time.time() + 900
        while True:
            try:
                self.call("GET", "/health")
                return True
            except SystemExit:
                return True
            except (urllib.error.URLError, ConnectionError, OSError):
                if time.time() > deadline:
                    raise SystemExit(self.name + " did not come up")
                time.sleep(3)


def seed(instance, warehouse, partner, products, category_name):
    """Sample data through `instance`, whose cookie jar must already hold the owner's session."""
    print("--", instance.name, flush=True)
    instance.call("POST", "/api/generic/location/1", json_body={"name": warehouse})
    instance.call("POST", "/api/generic/location/2", json_body={"name": partner})
    held = {p.get("productCode") for p in instance.call("GET", "/api/generic/product/?max=100")}
    if not all(code in held for code, _, _ in products):
        category = instance.call("POST", "/api/generic/category/", json_body={"name": category_name})
        kind = instance.call("GET", "/api/generic/productType/")[0]
        for code, name, price in products:
            if code not in held:
                instance.call("POST", "/api/generic/product/", json_body={
                    "name": name, "productCode": code, "category": {"id": category["id"]},
                    "productType": {"id": kind["id"]}, "pricePerUnit": price, "unitOfMeasure": "each"})
    if not instance.call("GET", "/api/dsm/accounts"):
        instance.call("POST", "/dsmWallet/attachAccount", form={})
    account = instance.call("GET", "/api/dsm/accounts")[0]
    # The faucet is the live network's, and answers "retry later" while others are claiming.
    for attempt in range(12):
        try:
            instance.call("POST", "/api/dsm/accounts/fees", form={"accountId": account["id"]})
            break
        except SystemExit as busy:
            print("   the faucet is busy, trying again:", str(busy)[-90:], flush=True)
            time.sleep(20)
    else:
        raise SystemExit(instance.name + ": the faucet never answered")
    print("   account", account["deviceId"][:12], "attached and funded", flush=True)


def main():
    raise SystemExit("seed.py is run by setup.py, after each organization's owner has claimed it: there is no login to seed with on its own")


if __name__ == "__main__":
    sys.exit(main())
