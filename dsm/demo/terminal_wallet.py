#!/usr/bin/env python3
"""The demo's four people as terminal wallets, for a run without phones (DSM_DEMO_WALLETS=terminal).

Each person's wallet is a dsm-app-host of its own on the organizations' network: one real DSM identity on the same SDK
path a phone runs, which is what DSM's `make dev-wallet` is. `make dev-wallet` and `make dev-wallet2` run two such
wallets; the story has four people, so network.sh runs four the same way, and this answers them through DSM's own Python
client (clients/python in the DSM checkout): the routes `make wallet ARGS=...` uses.

    terminal_wallet.py NAME id | owned | pending | connections      (NAME: marcus, priya, dev, amara)

What a person does on a phone, a TerminalWallet does through the wallet's routes: read a connect code and approve it,
approve what waits, add an object by its anchor, issue an object, decide a match. What it leaves untested: the phone's
screens, the person reading them, and Android's or iOS's own storage of the keys.

Needs the DSM checkout (DSM_CHECKOUT, default ../dsm-main beside this OpenBoxes checkout) with its Python client's
generated module (`make clients` there, or protoc as its Makefile does) and the `protobuf` package installed.
"""
import os
import sys
import time

HERE = os.path.dirname(os.path.abspath(__file__))
RUN = os.path.join(HERE, "run")
DSM = os.environ.get("DSM_CHECKOUT") or os.path.abspath(os.path.join(HERE, "..", "..", "..", "dsm-main"))
sys.path.insert(0, os.path.join(DSM, "clients", "python"))
import dsm_app_client as client  # noqa: E402  (DSM's own client, from the checkout above)
from dsm_app_client import pb  # noqa: E402
from dsm_app_client.b32 import decode_id  # noqa: E402
from dsm_app_client.ingress import payload  # noqa: E402

# Each person's wallet: its ingress (what this script calls) and its relay (what the organizations' hosts reach). The same
# names and ports are in network.sh.
PORTS = {"marcus": (8801, 8811), "priya": (8802, 8812), "dev": (8803, 8813), "amara": (8804, 8814)}
# Who stands where the phones stood, by the phone serials setup.py names.
BY_PHONE = {"RFGYB0PQ8XK": "marcus", "RFGYB0PQ9FF": "priya", "R5CW620MQVL": "dev", "RF8Y90PX5GN": "amara"}


def say(text):
    print("[%s] %s" % (time.strftime("%H:%M:%S"), text), flush=True)


class TerminalWallet:
    """A person's wallet, answered from the terminal. Same methods as setup.Phone, so the demo's steps read the same."""

    def __init__(self, who):
        self.name = BY_PHONE.get(who, who)
        if self.name not in PORTS:
            raise SystemExit("no terminal wallet named " + who)
        self.url = "http://127.0.0.1:%d" % PORTS[self.name][0]
        self.secret_file = os.path.join(RUN, "wallets", self.name, "ingress", "ingress-token")

    @property
    def serial(self):
        """Where play.py would name a phone, a terminal wallet names its person (the browser module keys on it)."""
        return self.name

    def _ingress(self):
        # The secret changes with every launch of the host: it is read on every call.
        return client.Ingress.from_secret_file(self.url, self.secret_file)

    @property
    def wallet(self):
        return client.WalletAccount(self._ingress())

    @property
    def account(self):
        """The same account through the application-side calls: issuing an object is an account's, not a connection's."""
        return client.AppAccount(self._ingress())

    def id(self):
        return self.wallet.id()

    def front(self):
        """A phone is brought to the front before it is read; a terminal wallet has no front."""

    def fund(self):
        """ERA from the network's faucet, for the storage fees a wallet's own steps cost, unless it holds some."""
        era = self.wallet.owns("ERA")
        if era is not None and era.amount > 0:
            return era.display
        last = ""
        for _ in range(240):
            funded, last = self.wallet.fund()
            if funded:
                return last
            time.sleep(1)
        raise SystemExit("%s's wallet was not funded: %s" % (self.name, last))

    def connect(self, code):
        """Read a connect code and approve what it asks, as Apps, Scan code, Read, Approve does on a phone."""
        preview = self.wallet.preview(code)
        say("  %s's wallet reads: %s asks to: %s" % (self.name, preview.name, "; ".join(preview.asks)))
        connection = self.wallet.approve(preview)
        say("  %s's wallet approved the connection %s" % (self.name, connection.id))
        return connection

    def disconnect_all(self):
        for c in self.wallet.connections():
            if c.connected:
                self.wallet.disconnect(c.id)

    def _waiting(self, words):
        """The waiting request whose summary names these words, after fetching what the connected apps asked."""
        self.wallet.sync()
        for waiting in self.wallet.pending():
            if words in waiting.summary:
                return waiting
        return None

    def show(self, words, seconds=240):
        """Until a request naming these words waits for approval: what the Waiting tab would show."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            waiting = self._waiting(words)
            if waiting:
                say("  waiting in %s's wallet: %s: %s" % (self.name, waiting.from_, waiting.summary))
                return waiting.summary
            time.sleep(3)
        raise SystemExit("%s's wallet never showed: %s" % (self.name, words))

    def approve(self, words, seconds=240):
        """Approve the waiting request naming these words; the next fetch carries it out. Returns the wallet's own line."""
        deadline = time.time() + seconds
        while time.time() < deadline:
            waiting = self._waiting(words)
            if waiting:
                decided = self.wallet.respond(waiting.raw_connection, waiting.seq, "approve")
                said = "%s: %s" % (pb.ConnectOutcome.Name(decided.outcome), decided.line)
                say("  %s approved: %s -> %s" % (self.name, waiting.summary, said))
                for _ in range(40):
                    if not self._waiting(words):
                        return said
                    time.sleep(3)
                raise SystemExit("%s approved, but the request still waits: %s" % (self.name, waiting.summary))
            time.sleep(3)
        raise SystemExit("%s's wallet never showed: %s" % (self.name, words))

    def adopt(self, anchor, ticker):
        """Add an object to this wallet by its anchor, so it can be sent it: Tokens, Add Token on a phone."""
        if any(o.object.symbol == ticker for o in self.wallet.owned()):
            return
        raw_call(self._ingress(), "tokens.addByAnchor", anchor.strip().encode())
        for _ in range(40):
            if any(o.object.symbol == ticker for o in self.wallet.owned()):
                return
            time.sleep(3)
        raise SystemExit("%s's wallet never listed %s after adding it" % (self.name, ticker))

    def issue(self, symbol, name, supply, burnable=False, description=""):
        """Issue an object from this wallet, which holds its whole supply: the phone's Tokens, Create token. Returns its anchor."""
        info = self.account.issue(symbol, name, str(supply), decimals=0, transferable=True, burnable=burnable,
                                  description=description)
        return info.id

    def owns(self, ticker):
        held = self.wallet.owns(ticker)
        return held.display if held is not None else "0"

    def decide(self, vault_id, outcome):
        """Decide a match this wallet referees, by the outcome's own name: the phone's SoFi, Escrow, Decide <outcome>."""
        request = pb.EscrowOutcomeRequest(vault_id=decode_id(vault_id, "the vault id"), outcome=outcome.encode())
        envelope = self._ingress().call("invoke", "escrow.adjudicate", request)
        verdict = payload("escrow.adjudicate", envelope, "escrow_verdict_response")
        return pb.EscrowVerdictState.Name(verdict.state), verdict.outcome.decode("utf-8", "replace")


def raw_call(ingress, method, raw_args):
    """A route whose arguments are the bytes themselves, not an arg pack (`tokens.addByAnchor` takes the anchor's text)."""
    request = pb.IngressRequest()
    request.router_invoke.method = method
    request.router_invoke.args = raw_args
    response = pb.IngressResponse.FromString(ingress._send("/ingress/quiet", method, request.SerializeToString()))
    if response.WhichOneof("result") == "error":
        raise client.IngressError(method, response.error.code, response.error.message)
    envelope = pb.Envelope.FromString(response.ok_bytes[1:])
    if envelope.WhichOneof("payload") == "error":
        raise client.IngressError(method, envelope.error.code, envelope.error.message)
    return envelope


def main(args):
    if len(args) != 2:
        raise SystemExit(__doc__)
    w = TerminalWallet(args[0])
    if args[1] == "id":
        print(w.id())
    elif args[1] == "owned":
        for o in w.wallet.owned():
            print("%-8s %16s   %s" % (o.object.symbol, o.display, o.object.id))
    elif args[1] == "pending":
        w.wallet.sync()
        for p in w.wallet.pending():
            print("%s %d  %s: %s" % (p.connection, p.seq, p.from_, p.summary))
    elif args[1] == "connections":
        for c in w.wallet.connections():
            print("%s  %s  %s" % (c.id, c.name, "CONNECTED" if c.connected else "DISCONNECTED"))
    else:
        raise SystemExit(__doc__)


if __name__ == "__main__":
    main(sys.argv[1:])
