<%-- The story bar for presenting: what is happening now, which part is the ordinary system and which is DSM, and the phone
     that matters. Only when the server was started with openboxes.dsm.demo=true, and read from dsm/demo/presenter.py. --%>
<g:if test="${grailsApplication.config.getProperty('openboxes.dsm.demo', Boolean, false)}">
<style>
    main > hr, main > hr ~ * { display: none !important; }
    #dsm-shade { position: fixed; inset: 0; width: 100vw; height: 100vh; z-index: 100001; pointer-events: none; display: none; }
    .dsm-ring { position: fixed; z-index: 100002; border: 3px solid #f0b429; border-radius: 8px; box-shadow: 0 0 0 2px rgba(0, 0, 0, .6), 0 0 18px rgba(240, 180, 41, .75); pointer-events: none; animation: dsm-pulse 1.6s ease-in-out infinite; box-sizing: border-box; transition: left .25s, top .25s, width .25s, height .25s; }
    .dsm-arrow { position: fixed; z-index: 100002; width: 0; height: 0; border-left: 11px solid transparent; border-right: 11px solid transparent; border-top: 16px solid #f0b429; pointer-events: none; animation: dsm-bob .9s ease-in-out infinite; }
    @keyframes dsm-pulse { 0%, 100% { opacity: 1; } 50% { opacity: .55; } }
    @keyframes dsm-bob { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-7px); } }
    #dsm-story { position: fixed; left: 0; right: 0; bottom: 0; z-index: 100003; display: none; align-items: center; gap: 18px; padding: 10px 18px; background: #10141c; color: #eef1f6; font: 15px/1.35 -apple-system, "Segoe UI", sans-serif; border-top: 3px solid #b7791f; }
    #dsm-story .n { color: #f0b429; font-weight: 700; white-space: nowrap; font-size: 14px; }
    #dsm-story .t { font-size: 19px; font-weight: 700; margin-bottom: 3px; }
    #dsm-story .tag { display: inline-block; padding: 0 7px; border-radius: 4px; font-weight: 700; font-size: 11px; margin-right: 6px; color: #fff; }
    #dsm-story .web2 { background: #2f6fc4; } #dsm-story .dsm { background: #b7791f; }
    #dsm-phone { position: fixed; left: 14px; bottom: 104px; box-sizing: border-box; pointer-events: none; z-index: 100002; display: none; background: #10141c; border: 3px solid #b7791f; border-radius: 16px; padding: 6px; color: #eef1f6; font: 12px -apple-system, "Segoe UI", sans-serif; text-align: center; }
    #dsm-phone .crop { aspect-ratio: 330 / 470; overflow: hidden; border-radius: 10px; background: #000; }
    /* Other pages style their images: the phone keeps its own shape everywhere. */
    #dsm-phone img { width: 100% !important; height: auto !important; max-width: none !important; max-height: none !important; display: block; margin: -12% 0 0 0 !important; padding: 0 !important; border: 0 !important; }
    #dsm-phone div { padding: 3px 0 5px; font-weight: 700; }
</style>
<svg id="dsm-shade" aria-hidden="true"><path id="dsm-shade-path" fill="rgba(0,0,0,0.62)" fill-rule="evenodd"></path></svg>
<div id="dsm-rings"></div>
<div id="dsm-phone"><div id="dsm-phone-who"></div><div class="crop"><img id="dsm-phone-img" alt="The wallet on this phone, live"/></div></div>
<div id="dsm-story">
    <div class="n" id="dsm-story-n"></div>
    <div>
        <div class="t" id="dsm-story-t"></div>
        <div><span class="tag web2">WEB2</span><span id="dsm-story-web2"></span></div>
        <div><span class="tag dsm">DSM</span><span id="dsm-story-dsm"></span></div>
    </div>
</div>
<script>
    (function () {
        var server = 'http://127.0.0.1:8799';
        var names = { northgate: "Marcus's phone, Northgate", lakeside: "Priya's phone" };
        var bar = document.getElementById('dsm-story'), box = document.getElementById('dsm-phone'), img = document.getElementById('dsm-phone-img');
        var phone = null;
        function set(id, v) { document.getElementById(id).textContent = v; }
        function show(s) {
            bar.style.display = 'flex';
            set('dsm-story-n', s.n + ' of ' + s.of); set('dsm-story-t', s.title); set('dsm-story-web2', s.web2); set('dsm-story-dsm', s.dsm);
            setFocus(s.focus || []);
            phone = s.phone;
            box.style.display = phone ? 'block' : 'none';
            if (phone) set('dsm-phone-who', names[phone]);
        }
        // The spotlight, as the wallet's tutorial draws it: everything shaded but what matters now, with a ring and an arrow.
        var focus = [], focusKey = '', focusAt = 0, handScrollAt = 0, autoScrollAt = 0;
        ['wheel', 'touchmove'].forEach(function (name) { window.addEventListener(name, function () { handScrollAt = Date.now(); }, { passive: true }); });
        window.addEventListener('keydown', function (e) { if (/^(Arrow(Up|Down)|Page(Up|Down)|Home|End| )$/.test(e.key)) handScrollAt = Date.now(); });
        var shade = document.getElementById('dsm-shade'), path = document.getElementById('dsm-shade-path'), rings = document.getElementById('dsm-rings');
        function setFocus(list) { var key = list.join('|'); if (key !== focusKey) { focusKey = key; focus = list; focusAt = Date.now(); } }
        function seen(e) { var r = e.getBoundingClientRect(); return r.width > 0 && r.height > 0 && getComputedStyle(e).visibility !== 'hidden'; }
        function byWords(selector, words, whole) {
            var found = [].slice.call(document.querySelectorAll(selector)).filter(function (e) {
                if (!seen(e) || e.closest('#dsm-story, #dsm-rings')) return false;
                var t = (e.innerText || e.value || e.placeholder || '').trim();
                return whole ? t === words : t.indexOf(words) >= 0;
            });
            found.sort(function (a, b) { var x = a.getBoundingClientRect(), y = b.getBoundingClientRect(); return x.width * x.height - y.width * y.height; });
            return found[0] || null;
        }
        function target(spec) {
            try {
                if (spec.indexOf('text:') === 0) return byWords('button, a, input, th, td, label, h1, h2, h3, p, span, div', spec.slice(5), true) || byWords('button, a, td, label, h2, p, span, div', spec.slice(5), false);
                if (spec.indexOf('row:') === 0) return byWords('tr', spec.slice(4), false);
                if (spec.indexOf('card:') === 0) { var inside = document.querySelector(spec.slice(5)); return inside ? inside.closest('.card, .box, form') || inside : null; }
                return document.querySelector(spec);
            } catch (e) { return null; }
        }
        function draw() {
            var barTop = bar.style.display === 'none' ? window.innerHeight : bar.getBoundingClientRect().top;
            var boxes = focus.map(target).filter(function (e) { return e && seen(e); });
            // The first target stays inside the band between the top of the window and the bar, for as long as it is the
            // spotlight, whatever the page does: it is scrolled into view (its own scrolling box included), then the window
            // is nudged clear of the bar. A viewer who scrolls by hand is left alone for a few seconds.
            if (boxes.length && Date.now() - handScrollAt > 6000 && Date.now() - autoScrollAt > 600) {
                var first = boxes[0], r = first.getBoundingClientRect(), band = barTop - 10 - 60;
                if (!first.closest('#dsm-phone, #dsm-ov') && (r.top < 60 || r.bottom > barTop - 10)) {
                    autoScrollAt = Date.now();
                    first.scrollIntoView({ block: r.height <= band ? 'center' : 'start' });
                    r = first.getBoundingClientRect();
                    if (r.top < 60 || r.bottom > barTop - 10) window.scrollBy(0, r.height <= band ? (r.top + r.height / 2) - (60 + band / 2) : r.top - 70);
                }
            }
            if (!boxes.length) { shade.style.display = 'none'; rings.innerHTML = ''; return; }
            var W = window.innerWidth, H = window.innerHeight, d = 'M0 0H' + W + 'V' + H + 'H0Z', pad = 7, html = '';
            boxes.forEach(function (e) {
                var r = e.getBoundingClientRect();
                var x = Math.max(2, r.left - pad), y = Math.max(2, r.top - pad);
                var w = Math.min(W - 4 - x, r.width + 2 * pad), h = Math.min(barTop - 4 - y, r.height + 2 * pad);
                if (h <= 0) return;
                d += 'M' + x + ' ' + y + 'h' + w + 'v' + h + 'h' + (-w) + 'Z';
                html += '<div class="dsm-ring" style="left:' + x + 'px;top:' + y + 'px;width:' + w + 'px;height:' + h + 'px"></div>';
                html += '<div class="dsm-arrow" style="left:' + (x + w / 2 - 11) + 'px;top:' + Math.max(2, y - 24) + 'px"></div>';
            });
            path.setAttribute('d', d);
            shade.setAttribute('viewBox', '0 0 ' + W + ' ' + H);
            shade.style.display = 'block';
            if (rings.getAttribute('data-html') !== html) { rings.innerHTML = html; rings.setAttribute('data-html', html); }
        }
        setInterval(draw, 200);
        // Nothing of the page sits under the side panel, whatever the window's width: a fixed or sticky element that spans
        // the width (the header, a modal's backdrop) gets the panel's width as padding, and one anchored to the top right
        // (a message, a toast) is moved left of it. The page's own layout already leaves the room below.
        function clearPanel() {
            var panel = document.getElementById('dsm-ov');
            if (!panel || panel.className !== 'open') return;
            var width = panel.offsetWidth + 10, W = window.innerWidth;
            var all = document.querySelectorAll('body > *, body > * > *, body > * > * > *, body > * > * > * > *');
            for (var i = 0; i < all.length; i++) {
                var e = all[i];
                if (e.id && e.id.indexOf('dsm-') === 0) continue;
                if (e.closest('#dsm-ov, #dsm-story, #dsm-phone, #dsm-rings, #dsm-shade')) continue;
                var cs = getComputedStyle(e);
                if (cs.position !== 'fixed' && cs.position !== 'sticky' && cs.position !== 'absolute') continue;
                var r = e.getBoundingClientRect();
                if (r.width === 0 || r.height === 0 || r.right <= W - width + 2) continue;
                if (r.width >= W * 0.8) {
                    if (e.style.getPropertyValue('padding-right') !== width + 'px') e.style.setProperty('padding-right', width + 'px', 'important');
                } else if (cs.position !== 'absolute' || e.offsetParent === null || e.offsetParent === document.body) {
                    var right = Math.max(width, W - r.right + width);
                    if (e.style.getPropertyValue('right') !== right + 'px') { e.style.setProperty('right', right + 'px', 'important'); e.style.setProperty('left', 'auto', 'important'); }
                }
            }
        }
        setInterval(clearPanel, 300);
        // The phone sits wholly above the bar, whatever the window's size or however many lines the bar takes.
        function fit() {
            var barHeight = bar.style.display === 'none' ? 0 : bar.offsetHeight;
            var room = window.innerHeight - barHeight - 60;
            box.style.bottom = (barHeight + 12) + 'px';
            var width = Math.max(180, Math.min(340, Math.round(room * 330 / 470), Math.round(window.innerWidth * 0.34)));
            box.style.width = width + 'px';
            // On the sign-in page the card stands clear of the phone.
            var card = document.querySelector('main');
            if (card) {
                var phoneShown = box.style.display !== 'none';
                card.style.marginLeft = phoneShown ? (width + 40) + 'px' : 'auto';
                card.style.marginRight = phoneShown ? '16px' : 'auto';
                card.style.maxWidth = phoneShown ? 'calc(100vw - ' + (width + 80) + 'px)' : '';
            }
        }
        window.addEventListener('resize', fit);
        setInterval(fit, 1000);
        function poll() { fetch(server + '/scene').then(function (r) { return r.json(); }).then(show).catch(function () { bar.style.display = 'none'; box.style.display = 'none'; }); }
        function move(path) { fetch(server + path, { method: 'POST' }).then(function (r) { return r.json(); }).then(show).catch(function () {}); }
        setInterval(poll, 500); poll();
        img.onload = function () { setTimeout(next, 300); };
        img.onerror = function () { setTimeout(next, 1500); };
        function next() { if (phone) { img.src = server + '/phone/' + phone + '.png?' + Date.now(); } else { setTimeout(next, 500); } }
        next();
        document.addEventListener('keydown', function (e) {
            var t = e.target && e.target.tagName;
            if (t === 'INPUT' || t === 'TEXTAREA' || t === 'SELECT' || (e.target && e.target.isContentEditable)) return;
            if (e.key === 'n' || e.key === 'N') move('/next');
            if (e.key === 'p' || e.key === 'P') move('/prev');
        });
    })();
</script>
</g:if>
