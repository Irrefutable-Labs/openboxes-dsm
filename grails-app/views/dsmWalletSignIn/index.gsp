<!doctype html>
<html>
<head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"/>
    <meta name="theme-color" content="#0b3d7a"/>
    <title>Sign in with your wallet</title>
    <style>
        :root { --bar: #0b3d7a; --primary: #0f5fbf; --bg: #eef2f6; --ink: #16202b; --muted: #5f6b7a; --line: #e2e7ee; --warn: #9a5b00; --warn-bg: #fff3dc; --bad: #c0392b; --bad-bg: #fde8e6; }
        * { box-sizing: border-box; }
        html, body { margin: 0; min-height: 100%; background: var(--bg); color: var(--ink); font: 16px/1.45 -apple-system, "Segoe UI", Roboto, "Helvetica Neue", sans-serif; }
        .bar { background: var(--bar); color: #fff; padding: max(10px, env(safe-area-inset-top)) 16px 10px; box-shadow: 0 2px 6px rgba(0,0,0,.18); }
        .bar-in { display: flex; align-items: center; gap: 12px; max-width: 560px; margin: 0 auto; }
        .logo { width: 38px; height: 38px; border-radius: 9px; background: #fff; color: var(--bar); font-weight: 800; display: flex; align-items: center; justify-content: center; font-size: 15px; flex: none; }
        .bar b { display: block; font-size: 17px; } .bar span { font-size: 12.5px; opacity: .85; }
        main { max-width: 560px; margin: 0 auto; padding: 16px 16px 40px; }
        .card { background: #fff; border-radius: 14px; box-shadow: 0 1px 2px rgba(16,32,51,.08), 0 4px 14px rgba(16,32,51,.06); padding: 18px 16px; margin-bottom: 14px; }
        h1 { font-size: 22px; margin: 0 0 4px; }
        p { color: var(--muted); margin: 6px 0; }
        label { display: block; font-size: 13px; font-weight: 600; color: var(--muted); margin-top: 14px; }
        input { width: 100%; min-height: 50px; margin-top: 6px; padding: 10px 12px; border-radius: 10px; border: 1px solid #c9d2dd; background: #fff; color: var(--ink); font: inherit; font-size: 17px; }
        button { display: block; width: 100%; min-height: 50px; margin-top: 12px; border: 0; border-radius: 12px; background: var(--primary); color: #fff; font: inherit; font-size: 16px; font-weight: 700; cursor: pointer; }
        #asked, #state { margin-top: 12px; padding: 10px 12px; border-radius: 10px; background: var(--warn-bg); color: var(--warn); font-weight: 600; font-size: 14.5px; }
        #asked:empty { display: none; }
        hr { border: 0; height: 0; margin: 0; }
        .refused { padding: 12px 14px; border-radius: 10px; background: var(--bad-bg); color: var(--bad); font-weight: 600; margin-bottom: 14px; }
        .qr { text-align: center; }
        .qr img { width: 240px; height: 240px; background: #fff; border: 1px solid var(--line); border-radius: 10px; padding: 8px; margin: 12px 0 6px; }
        ol { color: var(--ink); padding-left: 22px; margin: 10px 0; text-align: left; } ol li { margin: 4px 0; }
        .failed { color: var(--bad); font-weight: 600; }
        a { color: var(--primary); text-decoration: none; font-weight: 600; }
    </style>
</head>
<body>
<div class="bar"><div class="bar-in"><div class="logo">OB</div><div><b>OpenBoxes</b><span>Sign in with your wallet</span></div></div></div>
<main>
    <g:if test="${params.refused == 'seat'}">
        <div class="refused">Refused. Your seat is no longer standing on DSM. Nobody edited your account: the owner revoked it from their own wallet.</div>
    </g:if>
    <g:if test="${noOwner}">
    <div class="card qr" id="claim">
        <h1>Claim this organization</h1>
        <p>Nobody owns it yet. The first wallet to connect and sign becomes its owner. There are no passwords here, and no way to set one.</p>
        <g:if test="${code}">
            <img src="${createLink(action: 'qr')}" alt="Connect code"/>
            <ol>
                <li>Open your DSM wallet and go to <strong>Apps</strong>.</li>
                <li>Tap <strong>Scan code</strong> and scan this.</li>
                <li>Read what it asks for, then <strong>Approve</strong>, and approve the owner statement it asks you to sign next.</li>
            </ol>
        </g:if>
        <div id="claim-state">Waiting for a wallet to connect.</div>
    </div>
    </g:if>
    <div class="card" ${noOwner ? 'hidden' : ''}>
        <h1>Sign in</h1>
        <p>Your wallet is already connected here. Enter your username, then approve the request on your phone.</p>
        <form id="ask">
            <label for="username">Username</label>
            <input id="username" autocomplete="username" autocapitalize="none" placeholder="first.last" />
            <button type="submit">Ask my phone</button>
        </form>
        <div id="asked"></div>
    </div>
    <hr/>
    <div class="card qr">
        <h1>New here?</h1>
        <p>Connect your wallet to this organization.</p>
        <g:if test="${code}">
            <img src="${createLink(action: 'qr')}" alt="Connect code"/>
            <ol>
                <li>Open your DSM wallet and go to <strong>Apps</strong>.</li>
                <li>Tap <strong>Scan code</strong> and scan this.</li>
                <li>Read what it asks for, then <strong>Approve</strong>.</li>
            </ol>
            <div id="state">Waiting for your wallet</div>
        </g:if>
        <g:else>
            <p class="failed">${failure}</p>
        </g:else>
    </div>
</main>
<script>
    (function () {
        var claimState = document.getElementById('claim-state');
        if (claimState) {
            var claimUrl = '${createLink(action: 'claim')}';
            function pollClaim() {
                fetch(claimUrl, { method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' } })
                    .then(function (r) { return r.json(); })
                    .then(function (s) {
                        if (s.state === 'SIGNED_IN') { claimState.textContent = 'You own this organization now. Signing you in.'; window.location = s.redirect; return; }
                        if (s.state === 'OWNED') { claimState.textContent = 'Someone else just claimed it.'; setTimeout(function () { window.location.reload(); }, 3000); return; }
                        if (s.state === 'ASKED') claimState.textContent = 'Your wallet is connected. Approve the owner statement on your phone: Apps, Waiting.';
                        if (s.state === 'FAILED') claimState.textContent = s.reason;
                        setTimeout(pollClaim, 3000);
                    })
                    .catch(function () { setTimeout(pollClaim, 4000); });
            }
            pollClaim();
        }
        var asked = document.getElementById('asked');
        var next = '${(next ?: '').encodeAsJavaScript()}';
        var answerUrl = '${createLink(action: 'answer')}' + (next ? '?next=' + encodeURIComponent(next) : '');
        function pollAnswer() {
            fetch(answerUrl, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
                .then(function (r) { return r.json(); })
                .then(function (s) {
                    if (s.state === 'SIGNED_IN') { asked.textContent = 'Signed by your wallet. Signing you in.'; window.location = s.redirect; return; }
                    if (s.state === 'DECLINED') { asked.textContent = s.reason ? 'Your wallet could not answer: ' + s.reason : 'Declined on the phone.'; return; }
                    if (s.state === 'NO_SEAT') { asked.textContent = 'That wallet holds no seat here any more.'; return; }
                    if (s.state === 'FAILED') { asked.textContent = s.reason; return; }
                    setTimeout(pollAnswer, 2500);
                })
                .catch(function () { setTimeout(pollAnswer, 4000); });
        }
        document.getElementById('ask').onsubmit = function (e) {
            e.preventDefault();
            asked.textContent = 'Asking your phone.';
            fetch('${createLink(action: 'ask')}', {
                method: 'POST', credentials: 'same-origin', headers: { Accept: 'application/json' },
                body: new URLSearchParams({ username: document.getElementById('username').value })
            }).then(function (r) { return r.json(); }).then(function (s) {
                if (s.state === 'FAILED') { asked.textContent = s.reason; return; }
                asked.textContent = 'If that is your account, open your wallet: Apps, Waiting. Approve only if it says code ' + s.code + '.';
                pollAnswer();
            }).catch(function (x) { asked.textContent = x.message; });
        };
        var state = document.getElementById('state');
        if (!state) return;
        var url = '${createLink(action: 'status')}' + (next ? '?next=' + encodeURIComponent(next) : '');
        function poll() {
            fetch(url, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
                .then(function (r) { return r.json(); })
                .then(function (s) {
                    if (s.state === 'SIGNED_IN') { state.textContent = 'Seat verified. Signing you in.'; window.location = s.redirect; return; }
                    if (s.state === 'NO_SEAT') state.textContent = 'Wallet ' + s.deviceId + ' connected. It holds no seat here yet: ask your manager to assign one.';
                    if (s.state === 'FAILED') state.textContent = s.reason;
                    setTimeout(poll, 2000);
                })
                .catch(function () { setTimeout(poll, 3000); });
        }
        poll();
    })();
</script>
<g:render template="/common/dsmDemo"/>
</body>
</html>
