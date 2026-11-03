<!doctype html>
<html>
<head>
    <meta charset="utf-8"/>
    <meta name="viewport" content="width=device-width, initial-scale=1"/>
    <title>Sign in with your DSM wallet</title>
    <style>
        body { margin: 0; min-height: 100vh; display: flex; align-items: center; justify-content: center; background: #10131a; color: #eef1f6; font: 16px/1.45 -apple-system, "Segoe UI", Roboto, sans-serif; }
        main { width: min(440px, calc(100vw - 32px)); background: #1a1f2b; border: 1px solid #2c3444; border-radius: 12px; padding: 28px; text-align: center; }
        h1 { font-size: 22px; margin: 0 0 6px; }
        p { color: #aeb7c6; margin: 6px 0; }
        img { width: 280px; height: 280px; background: #fff; border-radius: 8px; padding: 10px; margin: 16px 0 10px; }
        ol { text-align: left; color: #cbd2de; padding-left: 22px; margin: 12px 0; }
        input { width: 62%; padding: 8px; border-radius: 6px; border: 1px solid #3c4557; background: #10131a; color: #eef1f6; }
        button { padding: 9px 12px; border: 0; border-radius: 6px; background: #b7791f; color: #fff; font-weight: 700; cursor: pointer; }
        hr { border: 0; border-top: 1px solid #2c3444; margin: 18px 0 12px; }
        #asked { margin-top: 10px; font-weight: 600; color: #f0b429; min-height: 22px; }
        #state { margin-top: 12px; font-weight: 600; color: #f0b429; min-height: 24px; }
        a { color: #7fb3ff; }
        .failed { color: #ff8a80; }
    </style>
</head>
<body>
<main>
    <h1>Sign in with your DSM wallet</h1>
    <g:if test="${params.refused == 'seat'}">
        <p style="margin:0 0 14px;padding:12px 14px;border-radius:8px;background:#5c1f1a;color:#fff;font-weight:600;">Refused. Your seat is no longer standing on DSM. Nobody edited your account: the owner revoked it from their own wallet.</p>
    </g:if>
    <form id="ask">
        <p>Already connected? Enter your username, then approve on your phone.</p>
        <input id="username" autocomplete="username" placeholder="Username" />
        <button type="submit">Ask my phone</button>
    </form>
    <div id="asked"></div>
    <hr/>
    <p>New here? Connect your wallet.</p>
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
    <p><g:link controller="auth" action="login">Back to sign in</g:link></p>
</main>
<script>
    (function () {
        var asked = document.getElementById('asked');
        var answerUrl = '${createLink(action: 'answer')}';
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
        var url = '${createLink(action: 'status')}';
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
