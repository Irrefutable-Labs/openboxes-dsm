<%-- The story bar for presenting: what is happening now, which part is the ordinary system and which is DSM, and the phone
     that matters. Only when the server was started with openboxes.dsm.demo=true, and read from dsm/demo/presenter.py. --%>
<g:if test="${grailsApplication.config.getProperty('openboxes.dsm.demo', Boolean, false)}">
<style>
    main > hr, main > hr ~ * { display: none !important; }
    #dsm-story { position: fixed; left: 0; right: 0; bottom: 0; z-index: 100000; display: none; align-items: center; gap: 18px; padding: 10px 18px; background: #10141c; color: #eef1f6; font: 15px/1.35 -apple-system, "Segoe UI", sans-serif; border-top: 3px solid #b7791f; }
    #dsm-story .n { color: #f0b429; font-weight: 700; white-space: nowrap; font-size: 14px; }
    #dsm-story .t { font-size: 19px; font-weight: 700; margin-bottom: 3px; }
    #dsm-story .tag { display: inline-block; padding: 0 7px; border-radius: 4px; font-weight: 700; font-size: 11px; margin-right: 6px; color: #fff; }
    #dsm-story .web2 { background: #2f6fc4; } #dsm-story .dsm { background: #b7791f; }
    #dsm-phone { position: fixed; left: 14px; bottom: 104px; box-sizing: border-box; pointer-events: none; z-index: 100000; display: none; background: #10141c; border: 3px solid #b7791f; border-radius: 16px; padding: 6px; color: #eef1f6; font: 12px -apple-system, "Segoe UI", sans-serif; text-align: center; }
    #dsm-phone .crop { aspect-ratio: 330 / 470; overflow: hidden; border-radius: 10px; background: #000; }
    /* Other pages style their images: the phone keeps its own shape everywhere. */
    #dsm-phone img { width: 100% !important; height: auto !important; max-width: none !important; max-height: none !important; display: block; margin: -12% 0 0 0 !important; padding: 0 !important; border: 0 !important; }
    #dsm-phone div { padding: 3px 0 5px; font-weight: 700; }
</style>
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
            phone = s.phone;
            box.style.display = phone ? 'block' : 'none';
            if (phone) set('dsm-phone-who', names[phone]);
        }
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
        setInterval(poll, 1000); poll();
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
