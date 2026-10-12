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

  function sideOf(video, x, y) {
    var r = video.getBoundingClientRect();
    if (r.width < 100 || x < r.left || x > r.right || y < r.top || y > r.bottom) return 0;
    var rel = (x - r.left) / r.width;
    return rel < 0.35 ? -1 : (rel > 0.65 ? 1 : 0);
  }

  document.addEventListener('touchend', function (e) {
    if (e.changedTouches.length !== 1) return;
    var video = biggestVideo();
    if (!video || !video.duration) return;
    var t = e.changedTouches[0];
    var side = sideOf(video, t.clientX, t.clientY);
    var now = Date.now();
    var gap = now - lastTime;

    if (side !== 0 && side === lastSide && Math.abs(t.clientX - lastX) < 80 &&
        (gap < DOUBLE_MS || (chaining && gap < CHAIN_MS))) {
      seek(video, side);
      chaining = true;
      lastTime = now;
      // Cegah player menganggapnya klik / double-click (misalnya toggle fullscreen)
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
      return;
    }
    if (side !== lastSide) chaining = false;
    lastTime = now; lastX = t.clientX; lastSide = side;
  }, { capture: true, passive: false });

  // Matikan double-click bawaan player di zona kiri/kanan
  document.addEventListener('dblclick', function (e) {
    var video = biggestVideo();
    if (video && sideOf(video, e.clientX, e.clientY) !== 0) {
      e.preventDefault();
      e.stopPropagation();
      e.stopImmediatePropagation();
    }
  }, true);
})();
