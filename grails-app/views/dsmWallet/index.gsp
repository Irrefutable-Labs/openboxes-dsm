<!doctype html>
<html>
<head>
    <meta name="layout" content="custom"/>
    <title>DSM</title>
    <style>
        .dsmp { max-width: 1120px; margin: 0 auto; padding: 16px; color: #1f2733; }
        .dsmp h1 { font-size: 22px; margin: 0 0 4px; }
        .dsmp h2 { font-size: 16px; margin: 0 0 8px; }
        .dsmp .card { background: #fff; border: 1px solid #dfe3ea; border-radius: 10px; padding: 16px; margin: 12px 0; }
        .dsmp .hint { color: #5c6675; font-size: 13px; margin: 4px 0 10px; }
        .dsmp table { width: 100%; border-collapse: collapse; font-size: 13px; margin-top: 8px; }
        .dsmp th, .dsmp td { text-align: left; padding: 6px 8px; border-bottom: 1px solid #eef0f4; }
        .dsmp input, .dsmp select { padding: 6px 8px; border: 1px solid #c8ced8; border-radius: 6px; margin: 2px 6px 2px 0; }
        .dsmp button { padding: 7px 12px; border: 0; border-radius: 6px; background: #b7791f; color: #fff; font-weight: 600; cursor: pointer; margin: 2px 6px 2px 0; }
        .dsmp button.plain { background: #4a5568; }
        .dsmp code { background: #f3f4f7; padding: 2px 5px; border-radius: 4px; font-size: 12px; word-break: break-all; }
        .dsmp .qr { margin-top: 10px; }
        .dsmp .qr img { width: 220px; height: 220px; display: block; }
        .dsmp #dsm-message { min-height: 22px; font-weight: 600; }
        .dsmp .bad { color: #b42318; } .dsmp .good { color: #05603a; }
    </style>
</head>
<body>
<div class="dsmp">
    <h1>DSM</h1>
    <div class="hint">People connect with their own wallets. What this page shows is read from this organization's DSM account and the storage nodes.</div>
    <div id="dsm-message"></div>

    <div class="card" id="dsm-account"></div>

    <div class="card">
        <h2>Connect a wallet</h2>
        <div class="hint">Show this code and scan it in the wallet under Apps, Scan code, then approve it on the phone.</div>
        <select id="purpose">
            <option value="person">A person</option>
            <option value="owner">The owner of this organization</option>
            <option value="registry">The product registry's wallet</option>
        </select>
        <button id="make-offer">Show a connect code</button>
        <div class="qr" id="offer"></div>
        <table id="wallets"></table>
    </div>

    <div class="card">
        <h2>Levels of access</h2>
        <div class="hint">Issue the seat object in the creator's wallet (Tokens, Create token). Its supply is the most seats there can ever be. Then paste its anchor here.</div>
        <input id="level-name" placeholder="Name, such as Warehouse manager"/>
        <select id="level-role">
            <option value="ROLE_ADMIN">Admin</option><option value="ROLE_MANAGER">Manager</option>
            <option value="ROLE_ASSISTANT">Assistant</option><option value="ROLE_BROWSER">Browser</option>
        </select>
        <input id="level-anchor" placeholder="The object's anchor" size="44"/>
        <select id="level-creator"></select>
        <button id="add-level">Add level</button>
        <table id="levels"></table>
    </div>

    <div class="card">
        <h2>Assign a seat</h2>
        <div class="hint">Locks one seat in a vault naming this person, which only you can revoke, from your own wallet.</div>
        <select id="seat-level"></select>
        <select id="seat-wallet"></select>
        <select id="seat-location">
            <g:each in="${locations}" var="location"><option value="${location.id}">${location.name}</option></g:each>
        </select>
        <input id="seat-first" placeholder="First name"/><input id="seat-last" placeholder="Last name"/>
        <button id="assign">Assign seat</button>
        <table id="seats"></table>
    </div>

    <div class="card">
        <h2>Partner organizations</h2>
        <div class="hint">One side shows a code, the other enters it. Each chooses the location that stands for the other in its own records.</div>
        <select id="partner-location">
            <g:each in="${locations}" var="location"><option value="${location.id}">${location.name}</option></g:each>
        </select>
        <button id="partner-offer">Show our code</button>
        <input id="partner-code" placeholder="Or enter their code" size="50"/>
        <button id="partner-accept">Connect to them</button>
        <div id="partner-out" class="hint"></div>
        <table id="partners"></table>
    </div>

    <div class="card">
        <h2>Products on DSM</h2>
        <div class="hint">The registry issues each product's object in its wallet. Paste its anchor here against your own product.</div>
        <select id="token-product"></select>
        <input id="token-anchor" placeholder="The object's anchor" size="44"/>
        <button id="adopt">Adopt object</button>
        <table id="tokens"></table>
    </div>

    <div class="card" id="custody-card">
        <h2>Custody on the way</h2>
        <div class="hint">One custody unit is one unit of goods in someone's hands. The organization's account sends them to the carrier who takes a shipment, and the carrier's wallet sends them to the receiving organization at the door. Ownership never moves with them.</div>
        <div id="custody-own"></div>
        <div class="row" id="custody-add-own">
            <input id="custody-anchor" placeholder="The custody object's anchor, issued in a wallet here" size="44"/>
            <select id="custody-creator"></select>
            <button id="custody-add">Use as our custody object</button>
        </div>
        <div class="row">
            <select id="custody-connection"></select>
            <input id="custody-partner-anchor" placeholder="A partner's custody object anchor" size="44"/>
            <button class="plain" id="custody-add-partner">Their carriers hand over here</button>
        </div>
        <table id="custody-partners"></table>
        <table id="carriages"></table>
    </div>

    <div class="card">
        <h2>Admit stock</h2>
        <div class="hint">Stock enters custody only after the registry approves it on its own phone. Name the document that says the goods exist.</div>
        <select id="admit-product"></select>
        <input id="admit-quantity" placeholder="Units" size="6"/>
        <input id="admit-reference" placeholder="Goods-received note" size="22"/>
        <button id="admit">Ask the registry</button>
        <table id="admissions"></table>
    </div>

    <div class="card">
        <h2>Arrived from partners</h2>
        <div class="hint">What this organization's own DSM account holds from partners, before anyone here has recorded it.</div>
        <button class="plain" id="inbound-check">Check now</button>
        <table id="inbound"></table>
    </div>

    <div class="card">
        <h2>DSM and the books</h2>
        <div class="hint">Books on hand should equal what the account owns on DSM, less admitted units not yet recorded and units here not yet received. The books are added up from the stock transactions, every minute.</div>
        <button class="plain" id="check-now">Check now</button>
        <table id="reconcile"></table>
        <h2 style="margin-top:14px">Findings, newest first. Nothing here is ever changed.</h2>
        <table id="findings"></table>
    </div>

    <div class="card">
        <h2>Authority record</h2>
        <div class="hint">Who owns this organization and every change to who may act, read from the storage nodes, each entry signed by this organization's account. Nothing in this application's database can add to it.</div>
        <table id="authority"></table>
    </div>

    <div class="card">
        <h2>Custody on record</h2>
        <div class="hint">Verify asks the partner's account to prove what it owns. No access to their systems is involved.</div>
        <table id="transfers"></table>
    </div>
</div>
<script>
    (function () {
        var base = '${request.contextPath}/dsmWallet/';
        var message = document.getElementById('dsm-message');
        function say(text, bad) { message.textContent = text || ''; message.className = bad ? 'bad' : 'good'; }
        function esc(v) { var d = document.createElement('div'); d.textContent = v == null ? '' : v; return d.innerHTML; }
        function call(action, form) {
            var init = { credentials: 'same-origin', headers: { Accept: 'application/json' } };
            if (form) { init.method = 'POST'; init.body = new URLSearchParams(form); }
            return fetch(base + action, init).then(function (r) {
                return r.json().catch(function () { return {}; }).then(function (j) {
                    if (!r.ok) throw new Error(j.errorMessage || ('The server answered ' + r.status));
                    return j.data;
                });
            });
        }
        function val(id) { return document.getElementById(id).value; }
        function options(id, rows, label) {
            var el = document.getElementById(id), kept = el.value;
            el.innerHTML = rows.map(function (r) { return '<option value="' + esc(r.id) + '">' + esc(label(r)) + '</option>'; }).join('');
            if (kept) el.value = kept;
        }
        var wallets = [];
        function showCustody(c) {
            var own = document.getElementById('custody-own');
            if (c.own) {
                own.innerHTML = '<div><b>' + esc(c.own.ticker) + '</b>, issued by ' + esc(c.own.creator) + '. The account can hand out <b>' + esc(c.own.spare) + '</b> units. <input size="5" id="custody-units" value="500"/> <button class="plain" id="custody-request">Ask ' + esc(c.own.creator) + ' for more</button></div>';
                document.getElementById('custody-add-own').style.display = 'none';
                document.getElementById('custody-request').onclick = function () {
                    call('requestCustodyUnits', { quantity: val('custody-units') }).then(function () { say('Asked. It waits on their phone under Apps, Waiting.'); }).catch(fail);
                };
            } else {
                own.innerHTML = '<div class="hint">No custody object yet. Issue one in the owner\'s wallet (Tokens, Create token, 0 decimals, a large supply) and paste its anchor here.</div>';
                document.getElementById('custody-add-own').style.display = '';
            }
            options('custody-creator', wallets, function (r) { return r.label; });
            options('custody-connection', c.connections, function (r) { return r.name; });
            document.getElementById('custody-partners').innerHTML = c.partners.length ? '<tr><th>Partner</th><th>Their custody object</th></tr>' + c.partners.map(function (p) { return '<tr><td>' + esc(p.peer) + '</td><td>' + esc(p.ticker) + ' <code>' + esc(p.anchor.slice(0, 10)) + '</code></td></tr>'; }).join('') : '';
        }
        function refresh() {
            return call('state').then(function (s) {
                wallets = s.wallets || [];
                showCustody(s.custody);
                document.getElementById('carriages').innerHTML = s.carriages.length ? '<tr><th>When</th><th>What</th><th>Shipment</th><th>Units</th><th>Wallet</th></tr>' + s.carriages.map(function (c) { return '<tr><td>' + esc(c.at) + '</td><td>' + esc(c.kind === 'DISPATCHED' ? 'Handed to a carrier' : 'Handed over at the door') + '</td><td>' + esc(c.memo) + '</td><td>' + esc(c.units) + ' ' + esc(c.ticker) + '</td><td>' + esc(c.wallet) + '</td></tr>'; }).join('') : '';
                var account = document.getElementById('dsm-account');
                if (s.account) {
                    account.innerHTML = '<h2>This organization\'s DSM account</h2><div class="hint">' + esc(s.account.location) + ', device <code>' + esc(s.account.deviceId) + '</code></div><button class="plain" id="fees">Get network fees</button>';
                    document.getElementById('fees').onclick = function () {
                        say('Claiming network fees from the live network.');
                        accountId().then(function (id) { return apiCall('dsm/accounts/fees', { accountId: id }); })
                            .then(function () { say('Network fees claimed.'); }).catch(fail);
                    };
                } else {
                    account.innerHTML = '<h2>This organization\'s DSM account</h2><div class="hint">Not attached yet.</div>' + (s.canAttach ? '<button id="attach">Attach this server\'s DSM account</button>' : '');
                    var attach = document.getElementById('attach');
                    if (attach) attach.onclick = function () { call('attachAccount', {}).then(refresh).catch(function (e) { say(e.message, true); }); };
                }
                document.getElementById('authority').innerHTML = '<tr><th>What</th><th>Wallet</th><th>What was signed</th><th>When</th><th>Published at</th></tr>' + s.authority.map(function (a) {
                    return '<tr><td>' + esc(a.kind) + '</td><td><code>' + esc(a.wallet || '') + '</code></td><td>' + esc(a.statement || '') + '</td><td>' + esc(new Date(Number(a.at)).toLocaleString()) + '</td><td><code>' + esc(a.address) + '</code></td></tr>';
                }).join('');
                document.getElementById('wallets').innerHTML = '<tr><th>Wallet</th><th>Device</th><th>Seats</th><th></th></tr>' + s.wallets.map(function (w) {
                    return '<tr><td>' + esc(w.label) + '</td><td><code>' + esc(w.deviceId) + '</code></td><td>' + esc(w.seats) + '</td><td>' + (w.owner ? 'Owner' : '') + '</td></tr>';
                }).join('');
                options('level-creator', s.wallets, function (w) { return 'Issued by ' + w.label; });
                options('seat-wallet', s.wallets, function (w) { return 'For ' + w.label; });
                options('seat-level', s.levels, function (l) { return l.name; });
                document.getElementById('levels').innerHTML = '<tr><th>Level</th><th>Object</th><th>Cap</th><th>Assigned</th><th>Spare</th><th>Release seats</th></tr>' + s.levels.map(function (l) {
                    return '<tr><td>' + esc(l.name) + '</td><td>' + esc(l.ticker) + '</td><td>' + esc(l.cap) + '</td><td>' + esc(l.assigned) + '</td><td>' + esc(l.spare) + '</td><td><input size="4" id="release-' + esc(l.id) + '" value="1"/><button class="plain" data-release="' + esc(l.id) + '">Ask ' + esc(l.creator) + '</button></td></tr>';
                }).join('');
                document.getElementById('seats').innerHTML = '<tr><th>Seat</th><th>Holder</th><th>Where</th><th>Assigned by</th><th>State</th><th>Revoke</th></tr>' + s.seats.map(function (x) {
                    return '<tr><td>' + esc(x.level) + ' #' + esc(x.number) + '</td><td>' + esc(x.holder) + '</td><td>' + esc(x.location) + '</td><td>' + esc(x.authority) + '</td><td>' + esc(x.status) + '</td><td>' + (x.status === 'ASSIGNED'
                        ? 'In your wallet: SoFi, Escrow, find <code>' + esc(x.cell) + '</code> and decide revoke. <button class="plain" data-refresh="' + esc(x.id) + '">Check</button>'
                        : '') + '</td></tr>';
                }).join('');
            }).catch(function (e) { say(e.message, true); });
        }
        document.addEventListener('click', function (e) {
            var release = e.target.getAttribute && e.target.getAttribute('data-release');
            if (release) {
                call('requestSeats', { levelId: release, quantity: val('release-' + release) })
                    .then(function () { say('Asked. It waits on their phone under Apps, Waiting.'); })
                    .catch(function (x) { say(x.message, true); });
            }
            var check = e.target.getAttribute && e.target.getAttribute('data-refresh');
            if (check) {
                call('refreshSeat', { id: check }).then(function (r) { say('The seat\'s verdict cell: ' + r.state + (r.outcome ? ', ' + r.outcome : '')); return refresh(); })
                    .catch(function (x) { say(x.message, true); });
            }
        });
        var pollingOffer = null;
        document.getElementById('make-offer').onclick = function () {
            var purpose = val('purpose');
            call('offer', {}).then(function (o) {
                document.getElementById('offer').innerHTML = '<img src="${createLink(controller: 'dsmWalletSignIn', action: 'qr')}?code=' + encodeURIComponent(o.code) + '" alt="Connect code"/><div class="hint">Waiting for the wallet. On a phone, copy this: <code>' + esc(o.code) + '</code></div>';
                clearInterval(pollingOffer);
                pollingOffer = setInterval(function () {
                    call('answered', { offerDigest: o.offerDigest, purpose: purpose }).then(function (a) {
                        if (a.waiting) say('Approve the claim on your phone: Apps, Waiting.');
                        if (!a.answered) return;
                        clearInterval(pollingOffer);
                        document.getElementById('offer').innerHTML = '';
                        say('Wallet ' + a.deviceId + ' connected.');
                        refresh();
                    }).catch(function (x) { clearInterval(pollingOffer); say(x.message, true); });
                }, 2500);
            }).catch(function (x) { say(x.message, true); });
        };
        var api = '${request.contextPath}/api/';
        function apiCall(path, form) {
            var init = { credentials: 'same-origin', headers: { Accept: 'application/json' } };
            if (form) { init.method = 'POST'; init.body = new URLSearchParams(form); }
            return fetch(api + path, init).then(function (r) {
                return r.json().catch(function () { return {}; }).then(function (j) {
                    if (!r.ok) throw new Error(j.errorMessage || ('The server answered ' + r.status));
                    return j.data;
                });
            });
        }
        function fail(x) { say(x.message, true); }
        function accountId() { return apiCall('dsm/accounts').then(function (a) { return a.filter(function (x) { return x.locationId; })[0].id; }); }
        function business() {
            apiCall('dsm/connections').then(function (rows) {
                document.getElementById('partners').innerHTML = '<tr><th>Partner</th><th>Device</th><th>Side</th></tr>' + rows.filter(function (c) { return !c.peerRegistry; }).map(function (c) {
                    return '<tr><td>' + esc(c.peerName) + '</td><td><code>' + esc(c.peerDeviceId.slice(0, 8)) + '</code></td><td>' + esc(c.side) + '</td></tr>';
                }).join('');
            }).catch(fail);
            apiCall('generic/product/?max=100').then(function (rows) {
                var list = rows.map(function (p) { return { id: p.id, label: p.productCode + ' ' + p.name }; });
                options('token-product', list, function (p) { return p.label; });
                options('admit-product', list, function (p) { return p.label; });
            }).catch(fail);
            apiCall('dsm/tokens').then(function (rows) {
                document.getElementById('tokens').innerHTML = '<tr><th>Product</th><th>Object</th><th>Owned here</th></tr>' + rows.map(function (t) {
                    return '<tr><td>' + esc(t.productCode) + '</td><td>' + esc(t.ticker) + '</td><td>' + esc(t.holdings.map(function (h) { return h.available; }).join(', ')) + '</td></tr>';
                }).join('');
            }).catch(fail);
            apiCall('dsm/admissions').then(function (rows) {
                document.getElementById('admissions').innerHTML = '<tr><th>Document</th><th>Units</th><th>Recorded</th><th>Result</th></tr>' + rows.map(function (a) {
                    return '<tr><td>' + esc(a.reference) + '</td><td>' + esc(a.quantity) + '</td><td>' + esc(a.quantityDrawn) + '</td><td>' + esc(a.status) + '</td></tr>';
                }).join('');
            }).catch(fail);
            apiCall('dsm/transfers').then(function (rows) {
                var seen = {};
                document.getElementById('transfers').innerHTML = '<tr><th>What</th><th>Product</th><th>Units</th><th>Document</th><th>Carrier</th><th>Proven</th><th></th></tr>' + rows.map(function (t) {
                    var carrier = '';
                    if (t.kind === 'SHIPMENT') {
                        if (t.carrier) carrier = 'With ' + esc(t.carrier);
                        else if (!seen[t.memo]) carrier = '<select id="carrier-' + esc(t.memo) + '">' + wallets.map(function (w) { return '<option value="' + esc(w.id) + '">' + esc(w.label) + '</option>'; }).join('') + '</select> <button class="plain" data-dispatch="' + esc(t.memo) + '">Hand to carrier</button>';
                        seen[t.memo] = true;
                    }
                    return '<tr><td>' + esc(t.kind) + '</td><td>' + esc(t.productCode) + '</td><td>' + esc(t.quantity) + '</td><td>' + esc(t.memo) + '</td><td>' + carrier + '</td><td>' + esc(t.provenAmount) + '</td><td>' + (t.kind === 'SHIPMENT' ? '<button class="plain" data-prove="' + esc(t.id) + '">Verify partner</button>' : '') + '</td></tr>';
                }).join('');
            }).catch(fail);
        }
        function inbound() {
            return apiCall('dsm/inbound').then(function (rows) {
                document.getElementById('inbound').innerHTML = '<tr><th>From</th><th>Their document</th><th>Lines</th><th>At the door</th><th></th></tr>' + rows.map(function (d) {
                    var door = 'No carrier: taken in on sight';
                    if (d.carriage.required && d.carriage.arrived) door = 'Handed over by ' + esc(d.carriage.by) + ': ' + esc(d.carriage.handedOver) + ' of ' + esc(d.units);
                    else if (d.carriage.required) door = '<span class="bad">On its way: ' + esc(d.carriage.handedOver) + ' of ' + esc(d.units) + ' units handed over</span><br/><select id="handover-' + esc(d.memo) + '">' + wallets.map(function (w) { return '<option value="' + esc(w.id) + '">' + esc(w.label) + '</option>'; }).join('') + '</select> <button class="plain" data-handover="' + esc(d.memo) + '" data-peer="' + esc(d.peerDeviceId) + '" data-units="' + esc(d.units) + '">Ask the carrier to hand over</button>';
                    return '<tr><td>' + esc(d.originName) + '</td><td>' + esc(d.memo) + '</td><td>' + esc(d.lines.map(function (l) { return l.quantity + ' ' + l.productCode; }).join(', ')) + '</td><td>' + door + '</td><td><button data-take="' + esc(d.peerDeviceId) + '" data-memo="' + esc(d.memo) + '">Take in</button></td></tr>';
                }).join('');
            }).catch(fail);
        }
        document.addEventListener('click', function (e) {
            if (!e.target.getAttribute) return;
            var prove = e.target.getAttribute('data-prove');
            if (prove) {
                say('Asking the partner\'s account to prove what it owns.');
                apiCall('dsm/transfers/' + prove + '/proof', {}).then(function (t) { say('Proven: they own ' + t.provenAmount + ' units.'); business(); }).catch(fail);
            }
            var take = e.target.getAttribute('data-take');
            if (take) {
                apiCall('dsm/inbound', { peerDeviceId: take, memo: e.target.getAttribute('data-memo') }).then(function (s) {
                    say('Taken in as shipment ' + s.shipmentNumber + '.');
                    window.location = '${request.contextPath}/partialReceiving/create/' + s.shipmentId;
                }).catch(fail);
            }
            var dispatch = e.target.getAttribute('data-dispatch');
            if (dispatch) {
                say('Handing the shipment to the carrier: the account sends them its custody units.');
                call('dispatch', { memo: dispatch, walletId: val('carrier-' + dispatch) }).then(function (c) {
                    say('Handed over: ' + c.units + ' ' + c.ticker + ' are in ' + c.wallet + '\'s wallet. They hold the goods now.'); business(); refresh();
                }).catch(fail);
            }
            var handover = e.target.getAttribute('data-handover');
            if (handover) {
                call('askHandover', { memo: handover, peerDeviceId: e.target.getAttribute('data-peer'), units: e.target.getAttribute('data-units'), walletId: val('handover-' + handover) })
                    .then(function () { say('Asked. The carrier approves it on their phone under Apps, Waiting; then check again.'); }).catch(fail);
            }
        });
        document.getElementById('partner-offer').onclick = function () {
            var location = val('partner-location');
            accountId().then(function (id) {
                return apiCall('dsm/connections/offers', { accountId: id }).then(function (o) {
                    document.getElementById('partner-out').innerHTML = 'Give them this code: <code>' + esc(o.code) + '</code>';
                    say('Waiting for them to connect.');
                    return apiCall('dsm/connections/confirmations', { accountId: id, offerDigest: o.offerDigest, peerLocationId: location }).then(function (c) {
                        say('Connected to ' + c.peerDeviceId.slice(0, 8) + '.'); business();
                    });
                });
            }).catch(fail);
        };
        document.getElementById('partner-accept').onclick = function () {
            accountId().then(function (id) {
                return apiCall('dsm/connections/accepts', { accountId: id, code: val('partner-code').trim(), peerLocationId: val('partner-location') });
            }).then(function () { say('Connected.'); business(); }).catch(fail);
        };
        document.getElementById('adopt').onclick = function () {
            apiCall('dsm/tokens/adoptions', { productId: val('token-product'), policyAnchor: val('token-anchor').trim() })
                .then(function (t) { say('Adopted ' + t.ticker + '.'); business(); }).catch(fail);
        };
        document.getElementById('admit').onclick = function () {
            accountId().then(function (id) {
                return apiCall('dsm/admissions', { accountId: id, productId: val('admit-product'), quantity: val('admit-quantity'), reference: val('admit-reference') });
            }).then(function () { say('Asked. It waits on the registry\'s phone under Apps, Waiting.'); business(); }).catch(fail);
        };
        document.getElementById('inbound-check').onclick = inbound;
        function showReconcile(data) {
            document.getElementById('reconcile').innerHTML = '<tr><th>Where</th><th>Product</th><th>DSM</th><th>Admitted, unrecorded</th><th>In transit</th><th>Books</th><th>Gap</th><th>Value of gap</th><th></th></tr>' + data.rows.map(function (r) {
                return '<tr' + (r.gap ? ' class="bad"' : '') + '><td>' + esc(r.location) + '</td><td>' + esc(r.productCode) + '</td><td>' + esc(r.dsmHolding) + '</td><td>' + esc(r.undrawn) + '</td><td>' + esc(r.inTransit) + '</td><td>' + esc(r.books) + '</td><td>' + (r.gap > 0 ? '+' : '') + esc(r.gap) + '</td><td>' + (r.gap ? esc(Number(r.value).toLocaleString('en-US', { style: 'currency', currency: 'USD' })) : '') + '</td><td>' + (r.gap ? '<input size="26" placeholder="Why" id="why-' + esc(r.productId) + '"/><button class="plain" data-correct="' + esc(r.productId) + '" data-account="' + esc(r.accountId) + '">Match the books to DSM</button>' : 'Agrees') + '</td></tr>';
            }).join('');
            document.getElementById('findings').innerHTML = '<tr><th>When</th><th>What</th><th>Where</th><th>Product</th><th>Gap</th><th>By</th><th>Why, or its record</th><th>Signed</th></tr>' + data.events.map(function (e) {
                return '<tr><td>' + esc(e.at) + '</td><td>' + esc(e.kind) + '</td><td>' + esc(e.location) + '</td><td>' + esc(e.productCode) + '</td><td>' + esc(e.gap) + '</td><td>' + esc(e.by) + '</td><td>' + esc([e.note, e.transactionNumber].filter(Boolean).join(', ')) + '</td><td>' + '<code>' + esc(e.signedAddress.slice(0, 10)) + '</code>' + '</td></tr>';
            }).join('');
        }
        function reconcile() { call('reconcile').then(showReconcile).catch(fail); }
        document.getElementById('check-now').onclick = function () {
            say('Comparing the books with DSM.');
            call('checkNow', {}).then(function (d) { say('Compared.'); showReconcile(d); }).catch(fail);
        };
        document.addEventListener('click', function (e) {
            var product = e.target.getAttribute && e.target.getAttribute('data-correct');
            if (!product) return;
            call('correctBooks', { accountId: e.target.getAttribute('data-account'), productId: product, reason: val('why-' + product) })
                .then(function (r) { say('Corrected by adjustment ' + r.transactionNumber + '.'); reconcile(); }).catch(fail);
        });
        reconcile();
        business();
        setInterval(function () { refresh(); business(); }, 15000);
        document.getElementById('custody-add').onclick = function () {
            call('addCustodyToken', { policyAnchor: val('custody-anchor'), creatorId: val('custody-creator') })
                .then(function (t) { say('Our custody object is ' + t.ticker + '.'); refresh(); }).catch(fail);
        };
        document.getElementById('custody-add-partner').onclick = function () {
            call('addCustodyToken', { policyAnchor: val('custody-partner-anchor'), connectionId: val('custody-connection') })
                .then(function (t) { say('Their carriers hand over ' + t.ticker + ' here.'); refresh(); }).catch(fail);
        };
        document.getElementById('add-level').onclick = function () {
            call('addLevel', { name: val('level-name'), roleType: val('level-role'), policyAnchor: val('level-anchor').trim(), creatorId: val('level-creator') })
                .then(function () { say('Level added.'); return refresh(); }).catch(function (x) { say(x.message, true); });
        };
        document.getElementById('assign').onclick = function () {
            say('Locking the seat in a vault on the storage nodes.');
            call('assignSeat', { levelId: val('seat-level'), walletId: val('seat-wallet'), locationId: val('seat-location'), firstName: val('seat-first'), lastName: val('seat-last') })
                .then(function () { say('Seat assigned.'); return refresh(); }).catch(function (x) { say(x.message, true); });
        };
        refresh();
    })();
</script>
</body>
</html>
