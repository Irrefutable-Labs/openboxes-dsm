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

ADMIN = ("admin", "password")

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
        deadline = time.time() + 900
        while True:
            try:
                self.call("POST", "/api/login", json_body={"username": ADMIN[0], "password": ADMIN[1], "location": "1"})
                return
            except (urllib.error.URLError, ConnectionError, OSError):
                if time.time() > deadline:
                    raise SystemExit(self.name + " did not come up")
                time.sleep(3)


def seed(instance, warehouse, partner, products, category_name):
    print("--", instance.name, flush=True)
    instance.wait_up()
    instance.call("POST", "/api/generic/location/1", json_body={"name": warehouse})
    instance.call("POST", "/api/generic/location/2", json_body={"name": partner})
    category = instance.call("POST", "/api/generic/category/", json_body={"name": category_name})
    kind = instance.call("GET", "/api/generic/productType/")[0]
    for code, name, price in products:
        instance.call("POST", "/api/generic/product/", json_body={
            "name": name, "productCode": code, "category": {"id": category["id"]}, "productType": {"id": kind["id"]},
            "pricePerUnit": price, "unitOfMeasure": "each"})
    instance.call("POST", "/dsmWallet/attachAccount", form={})
    account = instance.call("GET", "/api/dsm/accounts")[0]
    instance.call("POST", "/api/dsm/accounts/fees", form={"accountId": account["id"]})
    print("   account", account["deviceId"][:12], "attached and funded", flush=True)


def main():
    seed(Instance("Northgate Medical Distribution (sample)", 8081), "Northgate Central Warehouse",
         "Lakeside District Hospital", [(p[0], p[2], p[4]) for p in PRODUCTS], "Pharmaceuticals")
    seed(Instance("Lakeside District Hospital (sample)", 8082), "Lakeside Hospital Pharmacy",
         "Northgate Medical Distribution", [(p[1], p[3], p[5]) for p in PRODUCTS], "Medicines")
    print("READY")


if __name__ == "__main__":
    sys.exit(main())
