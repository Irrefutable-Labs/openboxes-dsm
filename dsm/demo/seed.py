#!/usr/bin/env python3
"""Set the demo up through each instance's own API, as each organization's administrator would: names, a product in
each catalog, DSM accounts and fees, the registry's token, each organization's adoption of it, the connections, a
second registry operator, and stock at organization A through a real two-operator admission.

The default admin login is the one the project's own install data creates (grails-app/migrations/install). The second
operator's password is generated here and kept in run/registry/second-operator.txt, readable by this user alone.
"""
import base64
import hashlib
import http.cookiejar
import json
import os
import secrets
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(HERE, "run")
ADMIN = ("admin", "password")


class Instance:
    def __init__(self, name, port, root, ingress):
        self.name, self.base, self.root, self.ingress = name, f"http://127.0.0.1:{port}/openboxes/api", root, ingress
        self.opener = None

    def login(self, username, password, location="1"):
        self.opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))
        self.call("POST", "/login", json_body={"username": username, "password": password, "location": location})
        return self

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
            raise SystemExit(f"{self.name}: {method} {path} answered {refused.code}: {refused.read().decode()[:600]}")
        try:
            return json.loads(text).get("data")
        except ValueError:
            return text


def step(message):
    print(f"-- {message}", flush=True)


def wait_up(instance):
    deadline = time.time() + 900
    while True:
        try:
            return instance.login(*ADMIN)
        except (urllib.error.URLError, ConnectionError, OSError, SystemExit):
            if time.time() > deadline:
                raise SystemExit(f"{instance.name} did not come up")
            time.sleep(3)


def until(what, probe, seconds=300):
    deadline = time.time() + seconds
    while True:
        found = probe()
        if found:
            return found
        if time.time() > deadline:
            raise SystemExit(f"{what}: not within {seconds} s")
        time.sleep(0.5)


def rename(instance, location_id, name):
    instance.call("POST", f"/generic/location/{location_id}", json_body={"name": name})


def product(instance, name, code):
    category = instance.call("POST", "/generic/category/", json_body={"name": "Medicines"})
    kind = instance.call("GET", "/generic/productType/")[0]
    return instance.call("POST", "/generic/product/", json_body={
        "name": name, "productCode": code, "category": {"id": category["id"]}, "productType": {"id": kind["id"]}})


def connect(offering, offering_account, accepting, accepting_account, offering_peer, accepting_peer):
    offer = offering.call("POST", "/dsm/connections/offers", form={
        "accountId": offering_account["id"], "toRegistry": str(offering_peer is None).lower()})
    accept = {"accountId": accepting_account["id"], "code": offer["code"]}
    if accepting_peer:
        accept["peerLocationId"] = accepting_peer
    accepting.call("POST", "/dsm/connections/accepts", form=accept)
    confirm = {"accountId": offering_account["id"], "offerDigest": offer["offerDigest"]}
    if offering_peer:
        confirm["peerLocationId"] = offering_peer
    else:
        confirm["peerRegistry"] = "true"
    offering.call("POST", "/dsm/connections/confirmations", form=confirm)


def main():
    org_a = Instance("organization A", 8081, os.path.join(RUN, "org-a"), "http://127.0.0.1:8781")
    org_b = Instance("organization B", 8082, os.path.join(RUN, "org-b"), "http://127.0.0.1:8782")
    registry = Instance("the registry", 8083, os.path.join(RUN, "registry"), "http://127.0.0.1:8783")
    for instance in (org_a, org_b, registry):
        step(f"waiting for {instance.name}")
        wait_up(instance)

    step("naming each organization's locations in its own records")
    rename(org_a, "1", "Warehouse A")
    rename(org_a, "2", "Organization B (Warehouse B)")
    rename(org_b, "1", "Warehouse B")
    rename(org_b, "2", "Organization A (Warehouse A)")
    rename(registry, "1", "Registry office")

    step("a product in each catalog, under each organization's own code")
    registered = product(registry, "Oral rehydration salts (registered)", "ORS1")
    at_a = product(org_a, "Oral rehydration salts", "ORSA")
    at_b = product(org_b, "ORS sachets", "ORSB")

    step("each organization registers only its own host, and claims network fees")
    account_registry = registry.call("POST", "/dsm/accounts/registries", form={
        "label": "Product registry", "ingressOrigin": registry.ingress, "dataDir": os.path.join(registry.root, "host")})
    account_a = org_a.call("POST", "/dsm/accounts/warehouses", form={
        "locationId": "1", "ingressOrigin": org_a.ingress, "dataDir": os.path.join(org_a.root, "host")})
    account_b = org_b.call("POST", "/dsm/accounts/warehouses", form={
        "locationId": "1", "ingressOrigin": org_b.ingress, "dataDir": os.path.join(org_b.root, "host")})
    registry.call("POST", "/dsm/accounts/fees", form={"accountId": account_registry["id"]})
    org_a.call("POST", "/dsm/accounts/fees", form={"accountId": account_a["id"]})
    org_b.call("POST", "/dsm/accounts/fees", form={"accountId": account_b["id"]})

    step("the registry issues the token; each organization adopts it by anchor")
    token = registry.call("POST", "/dsm/tokens", form={"productId": registered["id"]})
    org_a.call("POST", "/dsm/tokens/adoptions", form={"productId": at_a["id"], "policyAnchor": token["policyAnchor"]})
    org_b.call("POST", "/dsm/tokens/adoptions", form={"productId": at_b["id"], "policyAnchor": token["policyAnchor"]})

    step("connections: each organization to the registry, and A to B")
    connect(org_a, account_a, registry, account_registry, None, None)
    connect(org_b, account_b, registry, account_registry, None, None)
    connect(org_a, account_a, org_b, account_b, "2", "2")

    step("a second registry operator")
    password = secrets.token_urlsafe(18)
    digest = base64.b64encode(hashlib.sha1(password.encode()).digest()).decode()
    registry.call("POST", "/generic/user/", json_body={
        "username": "registry-operator-2", "password": digest, "firstName": "Second", "lastName": "Operator",
        "active": True, "roles": [{"id": "ff80818162d9f9cc0162d9fc1c220003"}, {"id": "5"}]})
    path = os.path.join(registry.root, "second-operator.txt")
    with open(os.open(path, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600), "w") as kept:
        kept.write(f"registry-operator-2\n{password}\n")
    second = Instance("the registry", 8083, registry.root, registry.ingress).login("registry-operator-2", password)

    step("organization A asks to admit 100 units against GRN-DEMO-0001; two registry operators approve")
    admission = org_a.call("POST", "/dsm/admissions", form={
        "accountId": account_a["id"], "productId": at_a["id"], "quantity": 100, "reference": "GRN-DEMO-0001"})
    waiting = until("the registry shows the request", lambda: next(
        (r for r in registry.call("GET", "/dsm/releases") if "GRN-DEMO-0001" in r["summary"]), None))
    decide = {"sessionId": waiting["sessionId"], "seq": waiting["seq"]}
    registry.call("POST", "/dsm/releases/approvals", form=decide)
    second.call("POST", "/dsm/releases/approvals", form=decide)
    until("organization A's account establishes the admission", lambda: next(
        (a for a in org_a.call("GET", "/dsm/admissions")
         if a["id"] == admission["id"] and a["status"] == "ESTABLISHED"), None))

    step("organization A records the 100 units as stock")
    inventory = "1"  # The install data's inventory of its default warehouse.
    now = time.strftime("%m/%d/%Y %H:%M:%S %z", time.localtime(time.time() - 5))
    org_a.call("POST", "/facilities/1/inventory/record-stock/save", json_body={
        "product": {"id": at_a["id"]}, "inventory": {"id": inventory}, "transactionDate": now,
        "comment": "Admitted against GRN-DEMO-0001",
        "recordInventoryRows": [{"oldQuantity": 0, "newQuantity": 100}]})
    held = org_a.call("GET", "/dsm/tokens")[0]["holdings"][0]["available"]
    print(f"organization A's account holds {held} units; the registry has released "
          f"{registry.call('GET', '/dsm/tokens')[0]['released']}")
    print("READY")


if __name__ == "__main__":
    sys.exit(main())
