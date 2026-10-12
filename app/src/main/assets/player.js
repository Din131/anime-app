// Ketuk 2x di sisi kiri video = mundur 10 detik, sisi kanan = maju 10 detik.
// Ketuk berulang dengan cepat untuk menambah lompatan (20, 30 detik, dst).
// Disuntikkan ke semua frame, termasuk iframe player dari domain lain.
(function () {
  if (window.__aniviewSeek) return;
  window.__aniviewSeek = true;

  var STEP = 10;          // detik per lompatan
  var DOUBLE_MS = 350;    // jarak maksimal antar ketukan
  var CHAIN_MS = 700;     // ketukan lanjutan setelah lompatan pertama
  var lastTime = 0, lastX = 0, lastSide = 0, chaining = false, total = 0, hideTimer = null;

  function biggestVideo() {
    var vids = document.getElementsByTagName('video'), best = null, area = 0;
    for (var i = 0; i < vids.length; i++) {
      var r = vids[i].getBoundingClientRect(), a = r.width * r.height;
      if (a > area) { area = a; best = vids[i]; }
    }
    return best;
  }

  function indicator(side) {
    var el = document.getElementById('__aniview_seek');
    if (!el) {
      el = document.createElement('div');
      el.id = '__aniview_seek';
      el.style.cssText = 'position:fixed;top:50%;transform:translateY(-50%);z-index:2147483647;' +
        'background:rgba(0,0,0,.6);color:#fff;font:600 15px sans-serif;padding:10px 18px;' +
        'border-radius:24px;pointer-events:none;transition:opacity .25s;opacity:0';
    }
    // Saat fullscreen, hanya isi elemen fullscreen yang terlihat
    var host = document.fullscreenElement || document.webkitFullscreenElement || document.body || document.documentElement;
    if (host.tagName === 'VIDEO') host = host.parentNode || document.body;
    if (el.parentNode !== host) host.appendChild(el);
    el.style.left = side < 0 ? '12%' : '';
    el.style.right = side > 0 ? '12%' : '';
    return el;
  }

  function seek(video, side) {
    var target = video.currentTime + side * STEP;
    if (isFinite(video.duration)) target = Math.min(target, video.duration - 0.5);
    video.currentTime = Math.max(0, target);

    total = chaining ? total + STEP : STEP;
    var el = indicator(side);
    el.textContent = (side < 0 ? '« ' : '') + (side < 0 ? '-' : '+') + total + ' dtk' + (side > 0 ? ' »' : '');
    el.style.opacity = '1';
    clearTimeout(hideTimer);
    hideTimer = setTimeout(function () { el.style.opacity = '0'; chaining = false; }, CHAIN_MS);
  }

  /** null = di luar video, -1 kiri, 0 tengah, 1 kanan */
  function zoneOf(video, x, y) {
    var r = video.getBoundingClientRect();
    if (r.width < 100 || x < r.left || x > r.right || y < r.top || y > r.bottom) return null;
    var rel = (x - r.left) / r.width;
    return rel < 0.35 ? -1 : (rel > 0.65 ? 1 : 0);
  }

  function kill(e) {
    if (e.cancelable) e.preventDefault();
    e.stopPropagation();
    e.stopImmediatePropagation();
  }

  // Ketukan kedua (dan seterusnya) diblokir sejak awal sentuhan, sebelum player sempat
  // melihatnya. Dengan begitu player tidak pernah mendeteksi "double tap" miliknya sendiri
  // (yang biasanya dipakai untuk masuk/keluar fullscreen).
  var blocking = false;

  window.addEventListener('pointerdown', function (e) {
    if (!e.isPrimary) { blocking = false; return; }
    var video = biggestVideo();
    var zone = video ? zoneOf(video, e.clientX, e.clientY) : null;
    if (zone === null) { blocking = false; chaining = false; lastSide = null; return; }

    var gap = Date.now() - lastTime;
    var isDouble = zone === lastSide && Math.abs(e.clientX - lastX) < 80 &&
      (gap < DOUBLE_MS || (chaining && zone !== 0 && gap < CHAIN_MS));

    if (isDouble) {
      blocking = true;
      kill(e);
      if (zone !== 0 && video.duration) {
        seek(video, zone);
        chaining = true;
      } else {
        chaining = false; // ketuk 2x di tengah: tidak melakukan apa-apa
      }
      return;
    }
    blocking = false;
    if (zone !== lastSide) chaining = false;
    lastX = e.clientX; lastSide = zone;
  }, { capture: true, passive: false });

  window.addEventListener('pointerup', function (e) {
    if (!e.isPrimary) return;
    lastTime = Date.now();
    if (blocking) kill(e);
  }, { capture: true, passive: false });

  // Event lanjutan dari ketukan yang diblokir juga ikut dibuang
  ['pointercancel', 'touchstart', 'touchend', 'mousedown', 'mouseup', 'click', 'dblclick'].forEach(function (type) {
    window.addEventListener(type, function (e) { if (blocking) kill(e); }, { capture: true, passive: false });
  });

  // ---------- Status video untuk Picture-in-Picture ----------
  // Hanya video yang benar-benar ditonton yang dihitung: sedang jalan, bersuara, dan
  // cukup besar. Trailer/preview yang diputar otomatis tanpa suara tidak dihitung.
  function watchedVideo() {
    var vids = document.getElementsByTagName('video');
    for (var i = 0; i < vids.length; i++) {
      var v = vids[i];
      if (v.paused || v.ended || v.muted || v.volume === 0 || v.readyState < 3) continue;
      if (v.getBoundingClientRect().width < 150) continue;
      return v;
    }
    return null;
  }
  function report() {
    if (!window.AniView) return;
    var v = watchedVideo();
    try { window.AniView.onVideoState(!!v, v ? v.videoWidth : 0, v ? v.videoHeight : 0); } catch (_) {}
  }
  ['playing', 'pause', 'ended', 'emptied', 'volumechange'].forEach(function (ev) {
    document.addEventListener(ev, report, true);
  });
  // Detak berkala: aplikasi menganggap video berhenti kalau detak ini tidak datang lagi
  // (misalnya halaman episode sudah ditinggalkan).
  setInterval(function () { if (watchedVideo()) report(); }, 2000);

  // ---------- Mode PiP: perbesar video (atau iframe player) memenuhi jendela ----------
  var PIP_STYLE = 'position:fixed!important;top:0!important;left:0!important;width:100vw!important;' +
    'height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;' +
    'z-index:2147483647!important;background:#000!important;object-fit:contain!important;transform:none!important';
  var pipEl = null, pipOld = null;

  function largestIframe() {
    var fr = document.getElementsByTagName('iframe'), best = null, area = 0;
    for (var i = 0; i < fr.length; i++) {
      var r = fr[i].getBoundingClientRect(), a = r.width * r.height;
      if (a > area) { area = a; best = fr[i]; }
    }
    return area > 20000 ? best : null;
  }

  function setPip(on) {
    if (on && !pipEl) {
      var v = biggestVideo();
      pipEl = (v && v.getBoundingClientRect().width > 100) ? v : largestIframe();
      if (pipEl) { pipOld = pipEl.getAttribute('style'); pipEl.setAttribute('style', (pipOld || '') + ';' + PIP_STYLE); }
    } else if (!on && pipEl) {
      if (pipOld === null) pipEl.removeAttribute('style'); else pipEl.setAttribute('style', pipOld);
      pipEl = null; pipOld = null;
    }
    // Teruskan ke semua iframe di dalamnya
    var fr = document.getElementsByTagName('iframe');
    for (var i = 0; i < fr.length; i++) {
      try { fr[i].contentWindow.postMessage({ __aniviewPip: on }, '*'); } catch (_) {}
    }
  }
  window.__aniviewPip = setPip;
  window.addEventListener('message', function (e) {
    if (e.data && typeof e.data === 'object' && '__aniviewPip' in e.data) setPip(!!e.data.__aniviewPip);
  });

  // Jaga-jaga: double-click di area video tidak pernah diteruskan ke player
  window.addEventListener('dblclick', function (e) {
    var video = biggestVideo();
    if (video && zoneOf(video, e.clientX, e.clientY) !== null) kill(e);
  }, true);
})();
