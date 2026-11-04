<!doctype html>
<html>
<head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, viewport-fit=cover"/>
    <meta name="theme-color" content="#0b3d7a"/>
    <title>${location?.name?.encodeAsHTML() ?: 'OpenBoxes'}</title>
    <style>
        :root { --bar: #0b3d7a; --primary: #0f5fbf; --bg: #eef2f6; --card: #fff; --ink: #16202b; --muted: #5f6b7a; --line: #e2e7ee;
                --ok: #138a4b; --ok-bg: #e4f5ea; --warn: #9a5b00; --warn-bg: #fff3dc; --bad: #c0392b; --bad-bg: #fde8e6; }
        * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
        html, body { margin: 0; background: var(--bg); color: var(--ink); font: 16px/1.4 -apple-system, "Segoe UI", Roboto, "Helvetica Neue", sans-serif; }
        .bar { position: sticky; top: 0; z-index: 10; background: var(--bar); color: #fff; padding: max(10px, env(safe-area-inset-top)) 16px 10px; box-shadow: 0 2px 6px rgba(0,0,0,.18); }
        .bar-in { display: flex; align-items: center; gap: 12px; }
        .logo { width: 38px; height: 38px; border-radius: 9px; background: #fff; color: var(--bar); font-weight: 800; display: flex; align-items: center; justify-content: center; font-size: 15px; letter-spacing: -.5px; flex: none; }
        .site { flex: 1; min-width: 0; }
        .site-name { font-weight: 700; font-size: 17px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .site-sub { font-size: 12.5px; opacity: .85; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
        .bar a { color: #fff; font-size: 13px; text-decoration: none; opacity: .9; padding: 8px 0 8px 8px; }
        main { padding: 14px 16px calc(84px + env(safe-area-inset-bottom)); max-width: 560px; margin: 0 auto; }
        section[hidden] { display: none; }
        .h { display: flex; align-items: baseline; justify-content: space-between; margin: 4px 0 12px; }
        .h h1 { font-size: 22px; margin: 0; }
        .h span { color: var(--muted); font-size: 13px; }
        .card { background: var(--card); border-radius: 14px; box-shadow: 0 1px 2px rgba(16,32,51,.08), 0 4px 14px rgba(16,32,51,.06); padding: 16px; margin-bottom: 14px; }
        .card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
        .doc { font-size: 20px; font-weight: 800; letter-spacing: .2px; }
        .route { color: var(--muted); font-size: 14px; margin-top: 2px; }
        .route b { color: var(--ink); font-weight: 600; }
        .pill { flex: none; font-size: 12px; font-weight: 700; padding: 5px 10px; border-radius: 999px; white-space: nowrap; }
        .pill.ok { background: var(--ok-bg); color: var(--ok); } .pill.wait { background: var(--warn-bg); color: var(--warn); } .pill.info { background: #e4eefb; color: var(--primary); }
        .items { list-style: none; margin: 14px 0 0; padding: 0; border-top: 1px solid var(--line); }
        .items li { display: flex; align-items: center; gap: 12px; padding: 10px 0; border-bottom: 1px solid var(--line); }
        .items .p { flex: 1; min-width: 0; } .items .p b { display: block; font-weight: 600; font-size: 15px; } .items .p small { color: var(--muted); font-size: 12.5px; }
        .items .q { font-size: 22px; font-weight: 800; text-align: right; line-height: 1; } .items .q small { display: block; font-size: 11px; font-weight: 600; color: var(--muted); margin-top: 3px; }
        .steps { list-style: none; display: flex; margin: 16px 0 0; padding: 0; counter-reset: s; }
        .steps li { flex: 1; position: relative; text-align: center; font-size: 10.5px; font-weight: 600; color: #9aa5b3; padding-top: 18px; }
        .steps li::before { content: ""; position: absolute; top: 5px; left: 50%; width: 12px; height: 12px; margin-left: -6px; border-radius: 50%; background: #fff; border: 2px solid #c9d2dd; z-index: 1; }
        .steps li::after { content: ""; position: absolute; top: 10px; left: -50%; right: 50%; height: 2px; background: #c9d2dd; }
        .steps li:first-child::after { display: none; }
        .steps li.done { color: var(--ok); } .steps li.done::before { background: var(--ok); border-color: var(--ok); } .steps li.done::after { background: var(--ok); }
        .steps li.now { color: var(--primary); } .steps li.now::before { border-color: var(--primary); box-shadow: 0 0 0 3px #dbe8fa; } .steps li.now::after { background: var(--ok); }
        .note { margin: 14px 0 0; padding: 10px 12px; border-radius: 10px; font-size: 14px; font-weight: 600; }
        .note.ok { background: var(--ok-bg); color: var(--ok); } .note.wait { background: var(--warn-bg); color: var(--warn); } .note.info { background: #e4eefb; color: var(--primary); }
        button { font: inherit; cursor: pointer; }
        .btn { display: block; width: 100%; min-height: 50px; margin-top: 12px; border: 0; border-radius: 12px; background: var(--primary); color: #fff; font-size: 16px; font-weight: 700; }
        .btn.secondary { background: #e6edf7; color: var(--primary); }
        .btn.ghost { background: transparent; color: var(--primary); min-height: 40px; margin-top: 4px; }
        .btn:disabled { opacity: .55; }
        select, input.text { width: 100%; min-height: 48px; margin-top: 10px; padding: 8px 12px; border-radius: 10px; border: 1px solid #c9d2dd; background: #fff; font: inherit; color: var(--ink); box-sizing: border-box; }
        .lines { margin-top: 6px; max-height: 46vh; overflow-y: auto; border-top: 1px solid var(--line); }
        .count[hidden] { display: none; }
        label { display: block; font-size: 13px; color: var(--muted); margin-top: 12px; font-weight: 600; }
        details summary { list-style: none; cursor: pointer; color: var(--primary); font-weight: 600; font-size: 14px; margin-top: 12px; }
        details summary::-webkit-details-marker { display: none; }
        .count { display: flex; align-items: center; gap: 10px; padding: 12px 0; border-bottom: 1px solid var(--line); }
        .count .p { flex: 1; min-width: 0; } .count .p b { display: block; font-weight: 600; font-size: 15px; } .count .p small { color: var(--muted); font-size: 12.5px; }
        .stepper { display: flex; align-items: center; border: 1px solid #c9d2dd; border-radius: 10px; overflow: hidden; }
        .stepper button { width: 42px; height: 44px; border: 0; background: #f2f5f9; color: var(--primary); font-size: 22px; font-weight: 700; }
        .stepper input { width: 58px; height: 44px; border: 0; text-align: center; font: inherit; font-size: 18px; font-weight: 700; color: var(--ink); -moz-appearance: textfield; }
        .stepper input::-webkit-outer-spin-button, .stepper input::-webkit-inner-spin-button { -webkit-appearance: none; margin: 0; }
        .empty { text-align: center; color: var(--muted); padding: 44px 20px; }
        .empty svg { width: 54px; height: 54px; stroke: #b7c2cf; margin-bottom: 10px; }
        .empty p { margin: 0; font-size: 15px; }
        .tabs { position: fixed; left: 0; right: 0; bottom: 0; z-index: 10; display: flex; background: #fff; border-top: 1px solid var(--line); padding-bottom: env(safe-area-inset-bottom); }
        .tabs button { flex: 1; border: 0; background: none; padding: 8px 2px 7px; color: #6b7684; font-size: 11px; font-weight: 600; position: relative; }
        .tabs button svg { display: block; width: 26px; height: 26px; margin: 0 auto 3px; stroke: currentColor; fill: none; stroke-width: 1.8; stroke-linecap: round; stroke-linejoin: round; }
        .tabs button.on { color: var(--primary); }
        .tabs .badge { position: absolute; top: 4px; left: calc(50% + 6px); min-width: 18px; height: 18px; padding: 0 5px; border-radius: 9px; background: var(--bad); color: #fff; font-size: 11px; line-height: 18px; font-weight: 700; }
        #toast { position: fixed; left: 16px; right: 16px; bottom: calc(76px + env(safe-area-inset-bottom)); z-index: 20; padding: 12px 14px; border-radius: 12px; font-size: 14.5px; font-weight: 600; box-shadow: 0 6px 20px rgba(16,32,51,.18); display: none; }
        #toast.ok { display: block; background: #16302a; color: #b9f0cf; } #toast.bad { display: block; background: #4a1410; color: #ffd0cb; } #toast.info { display: block; background: #16202b; color: #fff; }
    </style>
</head>
<body>
<div class="bar"><div class="bar-in">
    <div class="logo">OB</div>
    <div class="site"><div class="site-name" id="where">${location?.name?.encodeAsHTML() ?: 'OpenBoxes'}</div><div class="site-sub" id="who">Signing in</div></div>
    <a href="${createLink(controller: 'auth', action: 'logout')}">Sign out</a>
</div></div>
<main>
    <section data-tab="door"><div class="h"><h1>At the door</h1><span id="door-n"></span></div><div id="arriving"></div></section>
    <section data-tab="receive" hidden><div class="h"><h1>Receive</h1><span id="receive-n"></span></div><div id="receiving"></div></section>
    <section data-tab="send" hidden><div class="h"><h1>Send</h1><span id="send-n"></span></div><div id="leaving"></div></section>
    <section data-tab="carry" hidden><div class="h"><h1>Carrying</h1><span id="carry-n"></span></div><div id="carrying"></div></section>
</main>
<div id="toast"></div>
<nav class="tabs">
    <button data-go="door" class="on"><svg viewBox="0 0 24 24"><path d="M4 21V5a2 2 0 0 1 2-2h8a2 2 0 0 1 2 2v16"/><path d="M2 21h20"/><circle cx="12.5" cy="12" r="1"/><path d="M16 9h3a2 2 0 0 1 2 2v10"/></svg>Door<span class="badge" id="b-door" hidden></span></button>
    <button data-go="receive"><svg viewBox="0 0 24 24"><path d="M21 8 12 3 3 8v8l9 5 9-5z"/><path d="M3 8l9 5 9-5"/><path d="M12 13v8"/><path d="m9 17 2 2 4-4"/></svg>Receive<span class="badge" id="b-receive" hidden></span></button>
    <button data-go="send"><svg viewBox="0 0 24 24"><path d="M1 7h13v10H1z"/><path d="M14 10h4l3 3v4h-7z"/><circle cx="5" cy="18.5" r="1.8"/><circle cx="17" cy="18.5" r="1.8"/></svg>Send<span class="badge" id="b-send" hidden></span></button>
    <button data-go="carry"><svg viewBox="0 0 24 24"><rect x="4" y="7" width="16" height="14" rx="3"/><path d="M8 7V5a4 4 0 0 1 8 0v2"/><path d="M4 13h16"/><path d="M10 13v3h4v-3"/></svg>Carry<span class="badge" id="b-carry" hidden></span></button>
</nav>
<script>
    (function () {
        var urls = {
            state: '${createLink(action: 'state')}', dispatch: '${createLink(action: 'dispatch')}',
            handover: '${createLink(action: 'handover')}', takeIn: '${createLink(action: 'takeIn')}', receive: '${createLink(action: 'receive')}',
            send: '${createLink(action: 'send')}'
        };
        var state = { wallets: [], me: null, arriving: [], receiving: [], leaving: [], carrying: [], stock: [], partners: [] };
        var open = {}, busy = false, timer = null, toastTimer = null;
        function esc(s) { return String(s == null ? '' : s).replace(/[&<>"']/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]; }); }
        function toast(text, kind) {
            var t = document.getElementById('toast'); clearTimeout(toastTimer);
            t.textContent = text; t.className = kind || 'info';
            if (kind !== 'bad') toastTimer = setTimeout(function () { t.className = ''; }, 7000);
        }
        function call(name, form) {
            var init = { credentials: 'same-origin', headers: { Accept: 'application/json' } };
            if (form) { init.method = 'POST'; init.body = new URLSearchParams(form); }
            return fetch(urls[name], init).then(function (r) {
                return r.json().catch(function () { return {}; }).then(function (j) {
                    if (r.status === 401 || r.status === 403 || r.redirected) { window.location.reload(); throw new Error('Signed out.'); }
                    if (!r.ok) throw new Error(j.errorMessage || ('The server answered ' + r.status));
                    return j.data;
                });
            });
        }
        function items(list) {
            return '<ul class="items">' + list.map(function (l) {
                return '<li><div class="p"><b>' + esc(l.name || l.code) + '</b><small>' + esc(l.code) + '</small></div><div class="q">' + esc(l.quantity) + '<small>units</small></div></li>';
            }).join('') + '</ul>';
        }
        var STEPS = ['Sent', 'Carried', 'Handed over', 'Taken in', 'Received'];
        function steps(at, skipCarrier) {
            return '<ol class="steps">' + STEPS.map(function (s, i) {
                if (skipCarrier && (i === 1 || i === 2)) return '';
                return '<li class="' + (i < at ? 'done' : i === at ? 'now' : '') + '">' + s + '</li>';
            }).join('') + '</ol>';
        }
        function empty(text) {
            return '<div class="empty"><svg viewBox="0 0 24 24" fill="none" stroke-width="1.5"><path d="M21 8 12 3 3 8v8l9 5 9-5z"/><path d="M3 8l9 5 9-5"/><path d="M12 13v8"/></svg><p>' + esc(text) + '</p></div>';
        }
        function picker(id) {
            var mine = state.me ? state.me.id : null;
            return '<select id="' + id + '">' + state.wallets.map(function (w) {
                return '<option value="' + esc(w.id) + '"' + (w.id === mine ? ' selected' : '') + '>' + esc(w.label) + (w.id === mine ? ' (this phone)' : '') + '</option>';
            }).join('') + '</select>';
        }
        function arriving(list) {
            if (!list.length) return empty('Nothing is on its way to this door. Carriers hand over here, and what arrives without one is taken in on sight.');
            return list.map(function (d) {
                var take = '<button class="btn" data-take="' + esc(d.memo) + '" data-peer="' + esc(d.peerDeviceId) + '">Take in</button>';
                var h = '<article class="card"><div class="card-head"><div><div class="doc">' + esc(d.number) + '</div><div class="route">From <b>' + esc(d.from) + '</b></div></div>';
                if (!d.carrier) {
                    h += '<span class="pill ok">Arrived</span></div>' + items(d.lines) + steps(3, true) + '<p class="note ok">No carrier was needed. Take it in on sight.</p>' + take;
                } else if (d.carrier.arrived) {
                    h += '<span class="pill ok">Handed over</span></div>' + items(d.lines) + steps(3) + '<p class="note ok">Carrier ' + esc(d.carrier.by) + ' handed over all ' + esc(d.units) + ' units.</p>' + take;
                } else {
                    h += '<span class="pill wait">On its way</span></div>' + items(d.lines) + steps(2) + '<p class="note wait">' + esc(d.carrier.handedOver) + ' of ' + esc(d.units) + ' units handed over so far.</p>';
                    if (state.me) h += '<button class="btn" data-handover="' + esc(d.memo) + '" data-peer="' + esc(d.peerDeviceId) + '" data-units="' + esc(d.units) + '" data-mine="1">I am the carrier: hand over</button>';
                    h += '<details><summary>Ask another carrier\'s wallet</summary><label>Carrier</label>' + picker('carrier-' + d.number) + '<button class="btn secondary" data-handover="' + esc(d.memo) + '" data-peer="' + esc(d.peerDeviceId) + '" data-units="' + esc(d.units) + '" data-pick="carrier-' + esc(d.number) + '">Ask them to hand over</button></details>';
                }
                return h + '</article>';
            }).join('');
        }
        function receiving(list) {
            if (!list.length) return empty('Nothing taken in is waiting to be received.');
            return list.map(function (s) {
                var total = s.items.reduce(function (n, i) { return n + i.remaining; }, 0);
                var h = '<article class="card"><div class="card-head"><div><div class="doc">' + esc(s.document) + '</div><div class="route">From <b>' + esc(s.from) + '</b>, shipment ' + esc(s.number) + '</div></div><span class="pill info">Taken in</span></div>';
                if (!open[s.shipmentId]) {
                    h += items(s.items.map(function (i) { return { code: i.code, name: i.name, quantity: i.remaining }; })) + steps(4) + '<button class="btn" data-count="' + esc(s.shipmentId) + '">Count and receive</button>';
                } else {
                    h += '<form data-receive="' + esc(s.shipmentId) + '">' + s.items.map(function (i) {
                        return '<div class="count"><div class="p"><b>' + esc(i.name || i.code) + '</b><small>' + esc(i.code) + (i.lot ? ', lot ' + esc(i.lot) : '') + '. Sent ' + esc(i.remaining) + '</small></div><div class="stepper"><button type="button" data-step="-1">&minus;</button><input type="number" inputmode="numeric" min="0" name="quantity.' + esc(i.id) + '" value="' + esc(i.remaining) + '"/><button type="button" data-step="1">+</button></div></div>';
                    }).join('') + '<button type="submit" class="btn">Receive ' + esc(total) + ' units</button><button type="button" class="btn ghost" data-close="' + esc(s.shipmentId) + '">Not now</button></form>';
                }
                return h + '</article>';
            }).join('');
        }
        function sendForm() {
            if (!state.partners.length) return '<article class="card"><p class="note wait">This warehouse is not connected to another DSM warehouse yet, so nothing can be sent from here.</p></article>';
            if (!state.stock.length) return '<article class="card"><p class="note wait">Nothing is in stock here to send.</p></article>';
            var h = '<article class="card"><form data-send="1"><div class="card-head"><div><div class="doc">New shipment</div><div class="route">From stock on hand</div></div></div>';
            h += '<label for="send-to">To</label><select id="send-to" name="destinationId">' + state.partners.map(function (p) { return '<option value="' + esc(p.id) + '">' + esc(p.name) + '</option>'; }).join('') + '</select>';
            h += '<label for="send-filter">Find a product</label><input id="send-filter" class="text" type="search" placeholder="Name or code" autocomplete="off"/>';
            h += '<div class="lines">' + state.stock.map(function (i) {
                return '<div class="count" data-find="' + esc((i.name + ' ' + i.code).toLowerCase()) + '"><div class="p"><b>' + esc(i.name || i.code) + '</b><small>' + esc(i.code) + '. ' + esc(i.available) + ' available</small></div><div class="stepper"><button type="button" data-step="-1">&minus;</button><input type="number" inputmode="numeric" min="0" max="' + esc(i.available) + '" name="quantity.' + esc(i.id) + '" value="0"/><button type="button" data-step="1">+</button></div></div>';
            }).join('') + '</div>';
            h += '<label for="send-note">Note</label><input id="send-note" class="text" name="note" maxlength="255" placeholder="Optional"/>';
            h += '<button type="submit" class="btn" disabled>Count what goes</button><button type="button" class="btn ghost" data-close="send">Not now</button></form></article>';
            return h;
        }
        function sendTotal(form) {
            var n = 0;
            Array.prototype.forEach.call(form.querySelectorAll('.count input'), function (i) { n += parseInt(i.value, 10) || 0; });
            var to = form.querySelector('#send-to'), b = form.querySelector('button[type=submit]');
            b.disabled = !n; b.textContent = n ? 'Ship ' + n + ' units to ' + to.options[to.selectedIndex].text : 'Count what goes';
        }
        function leaving(list) {
            var top = open.send ? sendForm() : '<button class="btn" data-new="send">Send from stock</button>';
            if (!list.length) return top + empty('Nothing sent from here is waiting for a carrier.');
            return top + list.map(function (d) {
                var h = '<article class="card"><div class="card-head"><div><div class="doc">' + esc(d.number) + '</div><div class="route">To <b>' + esc(d.to) + '</b></div></div>';
                if (d.carrier) h += '<span class="pill ok">With carrier</span></div>' + items(d.lines) + steps(2) + '<p class="note ok">' + esc(d.carrier.label) + ' carries it: ' + esc(d.carrier.units) + ' ' + esc(d.carrier.ticker) + ' in their wallet.</p>';
                else h += '<span class="pill wait">Needs a carrier</span></div>' + items(d.lines) + steps(1) + '<label>Carrier</label>' + picker('dispatch-' + d.number) + '<button class="btn" data-dispatch="' + esc(d.memo) + '" data-pick="dispatch-' + esc(d.number) + '">Hand to carrier</button>';
                return h + '</article>';
            }).join('');
        }
        function carrying(list) {
            if (!state.me) return empty('Sign in with your wallet to see what you carry.');
            if (!list.length) return empty('This phone carries nothing from here.');
            return list.map(function (c) {
                return '<article class="card"><div class="card-head"><div><div class="doc">' + esc(c.number) + '</div><div class="route">To <b>' + esc(c.to || 'a partner') + '</b></div></div><span class="pill info">In your wallet</span></div>' + steps(1) + '<p class="note info">' + esc(c.units) + ' ' + esc(c.ticker) + ' of custody. Hand them over on the door screen when you arrive.</p></article>';
            }).join('');
        }
        function badge(id, n) { var b = document.getElementById('b-' + id); b.hidden = !n; b.textContent = n; }
        function render(s) {
            state = s;
            document.getElementById('where').textContent = s.location || 'OpenBoxes';
            document.getElementById('who').textContent = (s.user || '') + (s.me ? ' · wallet ' + s.me.deviceId : ' · no wallet on this session');
            document.getElementById('arriving').innerHTML = arriving(s.arriving);
            // a form being filled in is left alone until it is sent or closed
            if (!Object.keys(open).some(function (k) { return k !== 'send'; })) document.getElementById('receiving').innerHTML = receiving(s.receiving);
            if (!open.send) document.getElementById('leaving').innerHTML = leaving(s.leaving);
            document.getElementById('carrying').innerHTML = carrying(s.carrying);
            var waiting = s.leaving.filter(function (d) { return !d.carrier; }).length;
            badge('door', s.arriving.length); badge('receive', s.receiving.length); badge('send', waiting); badge('carry', s.carrying.length);
            document.getElementById('door-n').textContent = s.arriving.length ? s.arriving.length + ' on the way' : '';
            document.getElementById('receive-n').textContent = s.receiving.length ? s.receiving.length + ' to count' : '';
            document.getElementById('send-n').textContent = waiting ? waiting + ' need a carrier' : '';
            document.getElementById('carry-n').textContent = s.carrying.length ? s.carrying.length + ' in your wallet' : '';
        }
        function go(tab) {
            Array.prototype.forEach.call(document.querySelectorAll('section[data-tab]'), function (sec) { sec.hidden = sec.getAttribute('data-tab') !== tab; });
            Array.prototype.forEach.call(document.querySelectorAll('.tabs button'), function (b) { b.className = b.getAttribute('data-go') === tab ? 'on' : ''; });
            window.scrollTo(0, 0);
        }
        function refresh() {
            clearTimeout(timer);
            if (busy || (document.activeElement && document.activeElement.tagName === 'INPUT')) { timer = setTimeout(refresh, 3000); return; }
            return call('state').then(render).catch(function (x) { toast(x.message, 'bad'); }).then(function () { timer = setTimeout(refresh, 6000); });
        }
        function act(promise, done) {
            busy = true;
            return promise.then(function (r) { busy = false; toast(done(r), 'ok'); return refresh(); }).catch(function (x) { busy = false; toast(x.message, 'bad'); return refresh(); });
        }
        function picked(button) { var id = button.getAttribute('data-pick'); return id ? document.getElementById(id).value : ''; }
        document.addEventListener('click', function (e) {
            var b = e.target.closest ? e.target.closest('button') : null;
            if (!b) return;
            var tab = b.getAttribute('data-go');
            if (tab) return go(tab);
            var step = b.getAttribute('data-step');
            if (step) {
                var i = b.parentNode.querySelector('input'), top = i.max ? parseInt(i.max, 10) : Infinity;
                i.value = Math.min(top, Math.max(0, (parseInt(i.value, 10) || 0) + parseInt(step, 10)));
                var f = b.closest('form[data-send]'); if (f) sendTotal(f);
                return;
            }
            if (b.getAttribute('data-new') === 'send') { open.send = true; document.getElementById('leaving').innerHTML = leaving(state.leaving); document.getElementById('send-to').focus(); return; }
            if (b.getAttribute('data-close') === 'send') { delete open.send; document.getElementById('leaving').innerHTML = leaving(state.leaving); return; }
            var memo = b.getAttribute('data-take');
            if (memo) {
                b.disabled = true; toast('Taking in.');
                return act(call('takeIn', { peerDeviceId: b.getAttribute('data-peer'), memo: memo }), function (r) { go('receive'); return 'Taken in as shipment ' + r.number + '. Count it to receive it.'; });
            }
            memo = b.getAttribute('data-handover');
            if (memo) {
                b.disabled = true;
                var form = { memo: memo, peerDeviceId: b.getAttribute('data-peer'), units: b.getAttribute('data-units') };
                if (!b.getAttribute('data-mine')) form.walletId = picked(b);
                toast('Asking the wallet.');
                return act(call('handover', form), function () { return b.getAttribute('data-mine') ? 'Open your wallet: Apps, Waiting. Approve the payment to hand over.' : 'Asked. It waits on their phone under Apps, Waiting.'; });
            }
            memo = b.getAttribute('data-dispatch');
            if (memo) {
                b.disabled = true; toast('Paying the carrier\'s wallet.');
                return act(call('dispatch', { memo: memo, walletId: picked(b) }), function (r) { return 'Handed to carrier ' + r.wallet + ': ' + r.units + ' ' + r.ticker + '.'; });
            }
            var id = b.getAttribute('data-count');
            if (id) { open[id] = true; document.getElementById('receiving').innerHTML = receiving(state.receiving); }
            id = b.getAttribute('data-close');
            if (id) { delete open[id]; document.getElementById('receiving').innerHTML = receiving(state.receiving); }
        });
        document.addEventListener('input', function (e) {
            var form = e.target.closest ? e.target.closest('form[data-send]') : null;
            if (!form) return;
            if (e.target.id === 'send-filter') {
                var q = e.target.value.trim().toLowerCase();
                Array.prototype.forEach.call(form.querySelectorAll('.count'), function (row) { row.hidden = q && row.getAttribute('data-find').indexOf(q) < 0 && !(parseInt(row.querySelector('input').value, 10) > 0); });
            }
            sendTotal(form);
        });
        document.addEventListener('change', function (e) { var f = e.target.closest ? e.target.closest('form[data-send]') : null; if (f) sendTotal(f); });
        document.addEventListener('submit', function (e) {
            var form = e.target, id = form.getAttribute('data-receive');
            if (form.getAttribute('data-send')) {
                e.preventDefault();
                var fields = {};
                Array.prototype.forEach.call(form.querySelectorAll('input, select'), function (i) { if (i.name) fields[i.name] = i.value; });
                form.querySelector('button[type=submit]').disabled = true;
                toast('Shipping. DSM moves the custody now.');
                return act(call('send', fields), function (r) { delete open.send; return 'Shipment ' + r.number + ' sent: ' + r.units + ' units to ' + r.to + '. Hand it to a carrier.'; });
            }
            if (!id) return;
            e.preventDefault();
            var fields = { shipmentId: id };
            Array.prototype.forEach.call(form.querySelectorAll('input'), function (i) { fields[i.name] = i.value; });
            form.querySelector('button[type=submit]').disabled = true;
            toast('Receiving.');
            act(call('receive', fields), function (r) { delete open[id]; return 'Received shipment ' + r.number + '. It is in stock here now.'; });
        });
        refresh();
    })();
</script>
</body>
</html>
