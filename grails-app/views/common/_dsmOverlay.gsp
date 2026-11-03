<%-- The WEB2/DSM overlay, as Wildstate has it: every step on one timeline, read from this organization's own host and
     process. Press D, or use the button. --%>
<g:if test="${session.user}">
<style>
    #dsm-ov-btn { position: fixed; right: 16px; bottom: 16px; z-index: 99998; padding: 9px 16px; border: 0; border-radius: 20px; background: #b7791f; color: #fff; font: 700 14px -apple-system, "Segoe UI", sans-serif; cursor: pointer; box-shadow: 0 3px 12px rgba(0,0,0,.35); }
    #dsm-ov { position: fixed; top: 0; right: 0; bottom: 0; width: min(460px, 100vw); z-index: 99999; display: none; flex-direction: column; background: #10131a; color: #eef1f6; font: 13px/1.4 -apple-system, "Segoe UI", sans-serif; box-shadow: -6px 0 24px rgba(0,0,0,.45); }
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
    #dsm-ov-custody th, #dsm-ov-custody td { padding: 3px 4px; text-align: right; }
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
    <header><strong>What just happened</strong><button id="dsm-ov-close" type="button">Close</button></header>
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
        function esc(v) { var d = document.createElement('div'); d.textContent = v == null ? '' : v; return d.innerHTML; }
        function usd(v) { return Number(v).toLocaleString('en-US', { style: 'currency', currency: 'USD' }); }
        function add(html) { var d = document.createElement('div'); d.className = 'dsm-ev dsm-flash'; d.innerHTML = html; list.insertBefore(d, list.firstChild); }
        function poll() {
            fetch(url + '?after=' + after + '&web2After=' + web2After, { credentials: 'same-origin', headers: { Accept: 'application/json' } })
                .then(function (r) { return r.ok ? r.json() : null; })
                .then(function (j) {
                    if (!j || !j.data) return;
                    var s = j.data;
                    after = s.next;
                    s.web2.forEach(function (e) { web2After = Math.max(web2After, e.seq); add('<span class="dsm-tag web2">WEB2</span>' + esc(e.what) + (e.who ? '<div class="meta">' + esc(e.who) + '</div>' : '')); });
                    s.dsm.forEach(function (e) {
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
