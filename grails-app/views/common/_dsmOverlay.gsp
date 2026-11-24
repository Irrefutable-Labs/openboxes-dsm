<%-- The WEB2/DSM overlay, as Wildstate has it: every step on one timeline, read from this organization's own host and
     process. Press D, or use the button. --%>
<g:render template="/common/dsmDemo"/>
<g:if test="${session.user}">
<style>
    #dsm-ov-btn { position: fixed; right: 16px; bottom: 16px; z-index: 99998; padding: 9px 16px; border: 0; border-radius: 20px; background: #b7791f; color: #fff; font: 700 14px -apple-system, "Segoe UI", sans-serif; cursor: pointer; box-shadow: 0 3px 12px rgba(0,0,0,.35); }
    #dsm-ov { position: fixed; top: 0; right: 0; bottom: 96px; width: min(290px, 100vw); z-index: 99999; display: none; flex-direction: column; background: #10131a; color: #eef1f6; font: 13px/1.4 -apple-system, "Segoe UI", sans-serif; box-shadow: -6px 0 24px rgba(0,0,0,.45); }
    #dsm-ov.open { display: flex; }
    #dsm-ov header { padding: 12px 14px; border-bottom: 1px solid #2c3444; display: flex; justify-content: space-between; align-items: center; }
    #dsm-ov header strong { font-size: 15px; }
    #dsm-ov button { background: none; border: 1px solid #3c4557; color: #eef1f6; border-radius: 6px; padding: 3px 9px; cursor: pointer; }
    .dsm-ov-key { padding: 8px 14px; color: #aeb7c6; border-bottom: 1px solid #2c3444; }
    .dsm-tag { display: inline-block; padding: 1px 7px; border-radius: 4px; font-weight: 700; font-size: 11px; margin-right: 6px; }
    .dsm-tag.dsm { background: #b7791f; color: #fff; }
    .dsm-tag.web2 { background: #2f6fc4; color: #fff; }
    #dsm-ov-custody { padding: 10px 14px; border-bottom: 1px solid #2c3444; }
    #dsm-ov-custody table { width: 100%; border-collapse: collapse; }
    #dsm-ov-custody th, #dsm-ov-custody td { padding: 3px 4px; text-align: right; color: #eef1f6; font-size: 12px; }
    #dsm-ov-custody th:first-child, #dsm-ov-custody td:first-child { text-align: left; }
    #dsm-ov-custody .dsmc { color: #f0b429; font-weight: 700; } #dsm-ov-custody .webc { color: #7fb3ff; font-weight: 700; }
    #dsm-ov-list { flex: 1; overflow-y: auto; padding: 8px 14px; }
    .dsm-ev { padding: 7px 0; border-bottom: 1px solid #222938; }
    .dsm-ev .meta { color: #8b95a7; font-size: 11px; word-break: break-all; }
    .dsm-ev .err { color: #ff8a80; }
    .dsm-flash { animation: dsmflash 1.6s ease-out; }
    @keyframes dsmflash { from { background: #5c4410; } to { background: transparent; } }
    .dsm-ov-foot { padding: 8px 14px; border-top: 1px solid #2c3444; color: #8b95a7; font-size: 11px; word-break: break-all; }
</style>
<button id="dsm-ov-btn" type="button">DSM</button>
<div id="dsm-ov" aria-label="DSM overlay">
    <header><strong>What just happened, newest first</strong><button id="dsm-ov-close" type="button">Close</button></header>
    <div class="dsm-ov-key"><span class="dsm-tag web2">WEB2</span>this organization's own server and database <br/><span class="dsm-tag dsm">DSM</span>cryptographic custody, on the storage nodes</div>
    <div id="dsm-ov-custody"></div>
    <div id="dsm-ov-list"></div>
    <div class="dsm-ov-foot" id="dsm-ov-foot"></div>
</div>
<script>
    (function () {
        var url = '${createLink(controller: 'dsmWallet', action: 'timeline')}';
        var panel = document.getElementById('dsm-ov'), list = document.getElementById('dsm-ov-list');
        var after = 0, web2After = 0, timer = null, last = {};
        // The same request repeated (a page's tiles loading, say) is one line with a count, so every call is accounted for
        // without burying the ones that matter.
        var lastWeb2 = null;
        function addWeb2(e) {
            var key = e.what + '|' + (e.who || '');
            if (lastWeb2 && lastWeb2.key === key && lastWeb2.el && lastWeb2.el.parentNode) {
                lastWeb2.n += 1;
                lastWeb2.el.querySelector('.meta').textContent = [e.who, e.request, '\u00d7' + lastWeb2.n].filter(Boolean).join(' \u00b7 ');
                return;
            }
            add('<span class="dsm-tag web2">WEB2</span>' + esc(e.what) + '<div class="meta">' + esc([e.who, e.request].filter(Boolean).join(' \u00b7 ')) + '</div>');
            lastWeb2 = { key: key, n: 1, el: list.firstElementChild };
        }
        function esc(v) { var d = document.createElement('div'); d.textContent = v == null ? '' : v; return d.innerHTML; }
        function usd(v) { return Number(v).toLocaleString('en-US', { style: 'currency', currency: 'USD' }); }
        function add(html) { var d = document.createElement('div'); d.className = 'dsm-ev dsm-flash'; d.innerHTML = html; list.insertBefore(d, list.firstChild); list.scrollTop = 0; }
        // What each step means, for someone watching. Empty: nothing to translate. Null: a read or housekeeping, not worth showing.
        function plain(e) {
            var n = e.name || '', t = e.summary || '';
            if (e.error) return '';
            if (n === 'connect.app.request') {
                var sign = t.match(/sign: (.*)/);
                if (sign) return 'Asked the phone to sign: "' + sign[1] + '"';
                var pay = t.match(/pay (\d+) of \S+ \(openboxes:([a-z]+)/);
                if (pay) return pay[2] === 'seats' ? 'Asked the owner\'s phone to release ' + pay[1] + ' seats' : 'Asked the registry\'s phone to approve ' + pay[1] + ' units';
                if (/prove|holdings/i.test(t)) return 'Asked the partner\'s account to prove what it owns';
                return 'Asked the phone';
            }
            if (n === 'relay: answer') {
                if (/AwaitingApproval/.test(t)) return 'Now waiting for the person to decide on their phone';
                if (/CarriedOut/.test(t)) return 'The phone answered: approved and carried out';
                if (/Declined/.test(t)) return 'Declined on the phone';
                return null;
            }
            if (n === 'relay: accept') return 'A wallet connected';
            if (n.indexOf('relay:') === 0 || n === 'connect.app.offer' || n === 'faucet.claim') return null;
            if (n === 'authored.publish') return 'Published a permanent record, signed by this organization\'s account';
            if (n === 'escrow.lock') return 'Locked one seat in a vault naming the person';
            if (n === 'escrow.release') return 'The revoked seat came back to the organization';
            if (/fact|status|sync|read|list|verdict|locked/i.test(n)) return null;
            return '';
        }
        function poll() {
            fetch(url + '?after=' + after + '&web2After=' + web2After, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
                .then(function (r) {
                    // The person's seat no longer stands: their screen goes to the sign-in page, which says so.
                    if (r.status === 403) { window.location = '${request.contextPath}/dsmWalletSignIn/index?refused=seat'; return null; }
                    return r.ok ? r.json().catch(function () { return null; }) : null;
                })
                .then(function (j) {
                    if (!j || !j.data) return;
                    var s = j.data;
                    after = s.next;
                    s.web2.forEach(function (e) { web2After = Math.max(web2After, e.seq); addWeb2(e); });
                    s.dsm.forEach(function (e) {
                        var words = plain(e);
                        if (words === null) return;
                        if (words) { add('<span class="dsm-tag dsm">DSM</span>' + esc(words)); return; }
                        add('<span class="dsm-tag dsm">DSM</span>' + esc(e.name) + ' ' + esc(e.summary) + (e.error ? '<div class="err">' + esc(e.error) + '</div>' : '<div>' + esc(e.result) + '</div>')
                            + '<div class="meta">' + (e.after != null ? 'position ' + esc(e.before) + ' to ' + esc(e.after) : '') + (e.root ? ', root ' + esc(e.root) : '') + (e.deviceTree ? ', device tree ' + esc(e.deviceTree) : '') + '</div>');
                    });
                    var rows = s.custody.map(function (c) {
                        var key = c.code + c.location, moved = last[key] !== undefined && last[key] !== c.dsmUnits; last[key] = c.dsmUnits;
                        return '<tr' + (moved ? ' class="dsm-flash"' : '') + '><td>' + esc(c.code) + ' at ' + esc(c.location) + '</td><td class="dsmc">' + esc(c.dsmUnits) + '</td><td class="webc">' + esc(c.onHand) + '</td><td>' + usd(c.dsmValue) + '</td></tr>';
                    }).join('');
                    document.getElementById('dsm-ov-custody').innerHTML = rows ? '<table><tr><th>Here</th><th>DSM</th><th>Books</th><th>Value (sample)</th></tr>' + rows + '</table>' : '';
                    document.getElementById('dsm-ov-foot').textContent = 'Account ' + s.deviceId + '. Storage set ' + s.storageSetId + '. One signature is ' + s.signatureBytes + ' bytes.';
                });
        }
        function open() { panel.className = 'open'; poll(); clearInterval(timer); timer = setInterval(poll, 2500); }
        function close() { panel.className = ''; clearInterval(timer); }
        <g:if test="${grailsApplication.config.getProperty('openboxes.dsm.demo', Boolean, false)}">
        open();
        panel.style.top = '96px';
        document.body.style.paddingRight = '290px';
        document.body.style.paddingBottom = '100px';
        </g:if>
        document.getElementById('dsm-ov-btn').onclick = open;
        document.getElementById('dsm-ov-close').onclick = close;
        document.addEventListener('keydown', function (e) {
            var t = e.target && e.target.tagName;
            if ((e.key === 'd' || e.key === 'D') && t !== 'INPUT' && t !== 'TEXTAREA' && t !== 'SELECT' && !(e.target && e.target.isContentEditable)) {
                if (panel.className === 'open') close(); else open();
            }
        });
    })();
</script>
</g:if>
