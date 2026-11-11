(function () {
  'use strict';
  var $ = function (s, r) { return (r || document).querySelector(s); };
  var $$ = function (s, r) { return Array.prototype.slice.call((r || document).querySelectorAll(s)); };
  var svg = Icons.svg, Spring = Motion.Spring;
  var store = {
    get: function (k, d) { try { var v = localStorage.getItem('ibz.' + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem('ibz.' + k, JSON.stringify(v)); } catch (e) {} Bridge.setPref(k, JSON.stringify(v)); }
  };
  var CFG = window.IBZ_CONFIG || { frames: 48, topBox: null };

  /* ======================= matériau verre ======================= */
  var root = document.documentElement;
  var lite = false;
  function applyLite() { lite = !!store.get('lite', false); root.classList.toggle('lite', lite); if (typeof drawAngle === 'function' && typeof angle !== 'undefined' && angle) { drawn.i = -1; drawAngle(angle.x); } }
  /* Mise à l'échelle : maquette pensée en 1024×600, adaptée à toute taille d'écran */
  var SC = 1, VW = 1024, VH = 600, PER_PAGE = 18;
  /* Taille de l'interface : 1 = maquette d'origine ; 1,12 « Grand » (défaut v1.5) ; 1,25 « Très grand » */
  var ZOOMS = { normal: 1, grand: 1.12, xl: 1.25 };
  function fit() {
    var w = window.innerWidth, h = window.innerHeight, portrait = h > w * 1.05;
    var Z = ZOOMS[store.get('zoom', 'grand')] || 1.12;
    if (portrait) { SC = w / (600 / Z); }
    else { SC = Math.max(0.6, h / (600 / Z)); if (w / SC < 860) SC = w / 860; }
    VW = Math.round(w / SC); VH = Math.round(h / SC);
    var app = $('#app'); app.style.width = VW + 'px'; app.style.height = VH + 'px'; app.style.transform = 'scale(' + SC + ')';
    root.classList.toggle('portrait', portrait);
    root.classList.toggle('wide', !portrait && VW > 1400);
    // v1.20 : bibliothèque d'apps sur 2 lignes (icônes plus grandes, par défaut) ou 3 lignes (d'origine)
    var big = store.get('appsRows', 2) === 2;
    root.classList.toggle('apps2', big && !portrait);
    PER_PAGE = portrait ? 20 : big ? (VW > 1400 ? 12 : 10) : (VW > 1400 ? 24 : 18);
  }
  function alignGlass() {
    root.style.setProperty('--vw', VW + 'px');
    root.style.setProperty('--vh', VH + 'px');
    $$('.glass').forEach(function (el) {
      var r = el.getBoundingClientRect();
      el.style.setProperty('--gx', (-r.left / SC) + 'px');
      el.style.setProperty('--gy', (-r.top / SC) + 'px');
    });
  }
  function applyGlassMode() {
    var m = store.get('glass', 'sim');
    document.body.classList.toggle('real-blur', m === 'real');
    document.body.classList.toggle('no-glass', m === 'off');
  }

  /* ======================= thème (sombre / clair / auto) ======================= */
  var isLight = false, sig = { lights: null, night: null };
  function applyTheme() {
    var t = store.get('theme', 'auto'), light;
    if (t === 'light') light = true;
    else if (t === 'dark') light = false;
    else if (sig.lights !== null) light = !sig.lights;          // phares allumés -> sombre
    else if (sig.night !== null) light = !sig.night;            // mode nuit Android
    else { var h = new Date().getHours(); light = h >= 7 && h < 20; }
    if (light === isLight && document.documentElement.classList.contains('themed')) return;
    isLight = light;
    root.classList.add('themed'); root.classList.toggle('light', light);
    wallNow = ''; applyWall();
  }
  Bridge.on('night', function (d) { sig.night = !!(d && d.night); applyTheme(); });
  Bridge.on('lights', function (d) { sig.lights = !!(d && d.on); applyTheme(); });

  /* ======================= fond d'écran ======================= */
  var WALLS = [['match', 'Suit le thème'], ['dark', 'Sombre'], ['light', 'Clair'], ['day', 'Jour'], ['evening', 'Soir'], ['sunset', 'Couchant'], ['night', 'Nuit']];
  function wallFor(sel) {
    if (sel === 'match') return isLight ? 'light' : 'dark';
    if (sel === 'auto') { var h = new Date().getHours(); return h >= 7 && h < 17 ? 'day' : h >= 17 && h < 19 ? 'evening' : h >= 19 && h < 21 ? 'sunset' : 'night'; }
    return sel;
  }
  var wallNow = '';
  function abs(u) { try { return new URL(u, location.href).href; } catch (e) { return u; } }
  function applyWall() {
    var sel = store.get('wall', 'match'), w = wallFor(sel), a, b;
    if (sel === 'custom') {
      try { a = localStorage.getItem('ibz.wallimg'); b = localStorage.getItem('ibz.wallblur'); } catch (e) {}
      if (!a) { sel = 'dark'; w = 'dark'; }
    }
    var key = sel === 'custom' ? 'custom' : w;
    if (key === wallNow) return; wallNow = key;
    measureWall(key === 'custom' ? b : abs('img/walls/' + w + '_t.jpg'));
    if (key === 'custom') { a = 'url(' + a + ')'; b = 'url(' + b + ')'; }
    else { a = 'url(' + abs('img/walls/' + w + '.jpg') + ')'; b = 'url(' + abs('img/walls/' + w + '_blur.jpg') + ')'; }
    root.style.setProperty('--wall', a); root.style.setProperty('--wall-blur', b);
  }
  /* fond clair (gris, blanc) -> libellés d'apps et points de page en sombre */
  function measureWall(url) {
    var im = new Image();
    im.onload = function () {
      try {
        var c = document.createElement('canvas'); c.width = 24; c.height = 14;
        var x = c.getContext('2d'); x.drawImage(im, 0, 0, 24, 14);
        var d = x.getImageData(0, 0, 24, 14).data, s = 0;
        for (var i = 0; i < d.length; i += 4) s += 0.2126 * d[i] + 0.7152 * d[i + 1] + 0.0722 * d[i + 2];
        root.classList.toggle('wall-bright', s / (d.length / 4) > 125);
      } catch (e) { root.classList.remove('wall-bright'); }
    };
    im.src = url;
  }
  function wallSheet() {
    var sel = store.get('wall', 'match');
    var h = WALLS.map(function (w) { var th = w[0] === 'match' ? 'dark' : w[0]; return '<div class="wthumb press' + (sel === w[0] ? ' on' : '') + '" data-w="' + w[0] + '" style="background-image:' + (w[0] === 'match' ? 'linear-gradient(90deg,rgba(0,0,0,0) 50%,rgba(255,255,255,.0) 50%),url(img/walls/light_t.jpg),url(img/walls/dark_t.jpg)' : 'url(img/walls/' + th + '_t.jpg)') + ';background-size:50% 100%,50% 100%,cover;background-position:0 0,100% 0,0 0;background-repeat:no-repeat"><span>' + w[1] + '</span></div>'; }).join('');
    h += '<div class="wthumb auto press' + (sel === 'auto' ? ' on' : '') + '" data-w="auto"><span>Selon l\'heure</span></div>';
    h += '<div class="wthumb usb press' + (sel === 'custom' ? ' on' : '') + '" data-w="usb">' + svg('files') + '<span>Clé USB…</span></div>';
    openSheet('<div class="t-headline">Fond d\'écran</div><div class="t-sub">« Selon l\'heure » passe du jour à la nuit tout seul.</div><div class="walls">' + h + '</div>', function (s) {
      $$('[data-w]', s).forEach(function (el) {
        tappable(el, function () {
          var w = el.getAttribute('data-w');
          if (w === 'usb') { closeSheet(); $('#wall-file').click(); return; }
          store.set('wall', w); applyWall(); closeSheet(); if (current === 'settings') renderSettings();
        });
      });
    });
  }
  /* v1.20 : disposition de l'accueil (Réglages). Les mêmes cartes sont seulement replacées par le CSS : aucune carte en
     plus en mémoire. « Voiture » = d'origine ; « Voiture XL » = voiture en grand, fine barre en bas ; « Conduite » =
     vitesse et ordinateur de bord en grand, voiture à droite, barre musique en bas. */
  var LAYOUTS = [
    ['car', 'Voiture', 'La voiture, la musique, l\'heure et les raccourcis.'],
    ['xl', 'Voiture XL', 'La voiture en grand, une fine barre en bas.'],
    ['drive', 'Conduite', 'Vitesse et ordinateur de bord en grand.']
  ];
  function layoutName() { var l = store.get('layout', 'car'); for (var i = 0; i < LAYOUTS.length; i++) if (LAYOUTS[i][0] === l) return LAYOUTS[i][1]; return LAYOUTS[0][1]; }
  function applyLayout(animate) {
    var l = store.get('layout', 'car');
    root.classList.toggle('lay-xl', l === 'xl');
    root.classList.toggle('lay-drive', l === 'drive');
    var g = $('.home-grid');
    if (animate && g && !lite && !(window.matchMedia && matchMedia('(prefers-reduced-motion: reduce)').matches)) {
      g.classList.remove('lay-in'); void g.offsetWidth; g.classList.add('lay-in');
    }
    drawn.i = -1; if (typeof angle !== 'undefined' && angle) drawAngle(angle.x);
    alignGlass();
    setTimeout(refitFrames, 400);        // après la mise en place : redécode les vues à la nouvelle taille si besoin
  }
  function layoutSheet() {
    var sel = store.get('layout', 'car');
    var h = LAYOUTS.map(function (L) {
      return '<button class="lthumb press' + (sel === L[0] ? ' on' : '') + '" data-l="' + L[0] + '">' +
        '<span class="lmini lm-' + L[0] + '"><i class="m-rail"></i><i class="m-car"></i><i class="m-a"></i><i class="m-b"></i><i class="m-c"></i></span>' +
        '<b>' + L[1] + '</b><small>' + L[2] + '</small></button>';
    }).join('');
    openSheet('<div class="t-headline">Disposition de l\'accueil</div><div class="t-sub">Les mêmes cartes, placées autrement.</div><div class="layouts">' + h + '</div>', function (s) {
      $$('[data-l]', s).forEach(function (el) {
        tappable(el, function () {
          var l = el.getAttribute('data-l');
          $$('[data-l]', s).forEach(function (o) { o.classList.toggle('on', o === el); });
          store.set('layout', l); closeSheet();
          if (current !== 'home') showPage('home');
          setTimeout(function () { applyLayout(true); if (current === 'settings') renderSettings(); }, 120);
        });
      });
    });
  }
  function loadCustomWall(file) {
    var rd = new FileReader();
    rd.onload = function () {
      var img = new Image();
      img.onload = function () {
        var W = 1024, H = 600, c = document.createElement('canvas'); c.width = W; c.height = H;
        var x = c.getContext('2d'), sc = Math.max(W / img.width, H / img.height), w = img.width * sc, h = img.height * sc;
        x.drawImage(img, (W - w) / 2, (H - h) / 2, w, h);
        var full = c.toDataURL('image/jpeg', 0.86);
        var b = document.createElement('canvas'); b.width = W; b.height = H; var bx = b.getContext('2d');
        if ('filter' in bx) { bx.filter = 'blur(28px)'; bx.drawImage(c, -40, -40, W + 80, H + 80); }
        else { var t = document.createElement('canvas'); t.width = 48; t.height = 28; t.getContext('2d').drawImage(c, 0, 0, 48, 28); bx.imageSmoothingEnabled = true; bx.drawImage(t, 0, 0, W, H); }
        try { localStorage.setItem('ibz.wallimg', full); localStorage.setItem('ibz.wallblur', b.toDataURL('image/jpeg', 0.8)); }
        catch (e) { toast('Image trop lourde'); return; }
        store.set('wall', 'custom'); wallNow = ''; applyWall(); toast('Fond d\'écran appliqué'); if (current === 'custom') renderCustom();
      };
      img.src = rd.result;
    };
    rd.readAsDataURL(file);
  }

  /* ======================= appui instantané ======================= */
  document.addEventListener('pointerdown', function (e) {
    var p = e.target.closest ? e.target.closest('.press, .press-soft, .app, .dock-slot, .row.btn, .qbtn') : null;
    if (!p) return;
    p.classList.add('down');
    var up = function () { p.classList.remove('down'); document.removeEventListener('pointerup', up); document.removeEventListener('pointercancel', up); };
    document.addEventListener('pointerup', up); document.addEventListener('pointercancel', up);
  }, true);

  /* tap = appui puis relâche sans déplacement (> 10 px annule) ; appui long = 550 ms */
  function tappable(el, onTap, onLong) {
    var sx, sy, t, moved, longFired, down = false;
    el.addEventListener('pointerdown', function (e) {
      sx = e.clientX; sy = e.clientY; moved = false; longFired = false; down = true;
      clearTimeout(t);
      if (onLong) t = setTimeout(function () { if (!moved) { longFired = true; el.classList.remove('down'); onLong(e); } }, 550);
    });
    el.addEventListener('pointermove', function (e) {
      if (Math.abs(e.clientX - sx) > 10 || Math.abs(e.clientY - sy) > 10) { moved = true; clearTimeout(t); }
    });
    el.addEventListener('pointerup', function (e) { clearTimeout(t); var ok = down && !moved && !longFired; down = false; if (ok) onTap(e); });
    el.addEventListener('pointercancel', function () { clearTimeout(t); moved = true; down = false; });
    el.addEventListener('pointerleave', function () { down = false; clearTimeout(t); });
  }

  /* ======================= horloge ======================= */
  var JOURS = ['dimanche', 'lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi'];
  var MOIS = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];
  var JOURS_C = ['dim.', 'lun.', 'mar.', 'mer.', 'jeu.', 'ven.', 'sam.'];
  var MOIS_C = ['janv.', 'févr.', 'mars', 'avr.', 'mai', 'juin', 'juil.', 'août', 'sept.', 'oct.', 'nov.', 'déc.'];
  /* v1.21 : aucun texte ne déborde de sa boîte, sans faire ramer le poste.
     (La 1.20 remesurait tous les textes à chaque changement de l'accueil, donc chaque seconde avec la musique, en forçant des
     dizaines de recalculs de mise en page : sur l'autoradio, l'interface ramait.)
     Maintenant : seuls les textes ajustables dont le contenu ou la place a changé sont mesurés ; toutes les mesures d'abord,
     puis toutes les écritures (un seul recalcul de mise en page) ; la bonne taille est calculée d'un coup, sans boucle.
     Tout est remesuré seulement si la disposition, la taille d'interface, le thème ou l'écran changent. */
  var FIT = [['#c-time', 0.6], ['.cd b', 0.68], ['.cd span', 0.8], ['.qbtn span', 0.72], ['.cchip b', 0.8], ['.cchip span', 0.76],
             ['.app-l', 0.76], ['#car-status span', 0.8], ['#m-title', 0.74, true]];
  var FIT_WATCH = '#car-chips, #quick, #apps-track, .cd-grid, .cd-main, #car-status, #m-title';
  var fitEpoch = 1, fitDone = 0, fitQueued = false, dateShort = false;
  function dateText(d, short) { return short ? JOURS_C[d.getDay()] + ' ' + d.getDate() + ' ' + MOIS_C[d.getMonth()] : JOURS[d.getDay()] + ' ' + d.getDate() + ' ' + MOIS[d.getMonth()]; }
  function fitTexts(all) {
    if (all === true) fitEpoch++;
    if (fitQueued) return; fitQueued = true;
    requestAnimationFrame(runFit);
  }
  function runFit() {
    fitQueued = false;
    var compact = root.classList.contains('lay-xl') || root.classList.contains('lay-drive'), fresh = fitDone !== fitEpoch, d = new Date();
    var items = [];
    FIT.forEach(function (f) { $$(f[0]).forEach(function (el) { items.push([el, f[1], !!f[2]]); }); });
    var dt = $('#c-date');
    // 0. écritures préalables, seulement quand tout doit être remesuré
    if (fresh) {
      items.forEach(function (it) { if (it[0].style.fontSize) it[0].style.fontSize = ''; it[0]._fs0 = null; it[0]._fitKey = null; });
      if (dt) dt.textContent = dateText(d, false);
    }
    // 1. lectures (un seul recalcul de mise en page pour toutes)
    var jobs = [], again = false;
    items.forEach(function (it) {
      var el = it[0];
      if (it[2] && !compact) { if (el.style.fontSize) jobs.push([el, '']); return; }
      var w = el.clientWidth; if (!w) return;
      var key = el.textContent + '|' + w;
      if (el._fitKey === key) return;
      if (el._fs0 == null) el._fs0 = parseFloat(getComputedStyle(el).fontSize) || 14;
      var fs0 = el._fs0, cur = parseFloat(el.style.fontSize) || fs0, need = el.scrollWidth * fs0 / cur;   // largeur à la taille d'origine
      var min = Math.max(10, fs0 * it[1]);
      var t = need <= w ? fs0 : Math.max(min, Math.floor(fs0 * (w - 1) / need * 4) / 4);
      if (need * t / fs0 > w && /^Dans /.test(el.textContent)) { jobs.push([el, null, el.textContent.replace(/^Dans /, '')]); again = true; return; }   // puce Entretien
      jobs.push([el, t >= fs0 ? '' : t + 'px']);
      el._fitKey = key;
    });
    if (dt && dt.clientWidth && fresh) dateShort = dt.scrollWidth > dt.clientWidth;
    // 2. écritures
    jobs.forEach(function (j) {
      if (j[1] === null) { j[0].textContent = j[2]; j[0]._fitKey = null; return; }
      if (j[0].style.fontSize !== j[1]) j[0].style.fontSize = j[1];
    });
    if (dt && fresh && dateShort) dt.textContent = dateText(d, true);
    fitDone = fitEpoch;
    if (again) fitTexts();                 // texte raccourci : remesuré au prochain passage
  }
  function pad(n) { return (n < 10 ? '0' : '') + n; }
  function setText(sel, v) { var el = $(sel); if (el && el.textContent !== v) el.textContent = v; }
  function tick() {
    var d = new Date(), t = pad(d.getHours()) + ':' + pad(d.getMinutes());
    setText('#rail-time', t); setText('#c-time', t);
    var dtxt = dateText(d, dateShort), cdt = $('#c-date'); if (cdt.textContent !== dtxt) cdt.textContent = dtxt;   // v1.21 : forme choisie par l'ajustement
  }

  /* ======================= apps & dock ======================= */
  var apps = [], appByKey = {};
  var VIRTUAL = [
    { pkg: ':vehicle', cls: '', label: 'Véhicule', theme: 'car', virtual: 'vehicle' },
    { pkg: ':settings', cls: '', label: 'Réglages lanceur', theme: 'launcher', virtual: 'settings' }
  ];
  function key(a) { return a.pkg + '/' + a.cls; }
  function loadApps() {
    var list = Bridge.apps() || [];
    list.forEach(function (a) { a.theme = a.custom ? null : Icons.themeFor(a); });
    list.sort(function (a, b) { return a.label.localeCompare(b.label, 'fr'); });
    apps = VIRTUAL.concat(list);
    appByKey = {}; apps.forEach(function (a) { appByKey[key(a)] = a; });
  }
  function openApp(a, originEl) {
    if (a.virtual) { showPage(a.virtual, originEl); return; }
    Bridge.launch(a);
  }
  function defaultDock() {
    var want = ['nav', 'music', 'phone', 'link'], out = [];
    want.forEach(function (th) {
      var f = null;
      var ra = roleApp(th); if (ra && out.indexOf(key(ra)) < 0) f = ra;
      if (!f) for (var i = 0; i < apps.length; i++) if (apps[i].theme === th && !apps[i].virtual && out.indexOf(key(apps[i])) < 0) { f = apps[i]; break; }
      out.push(f ? key(f) : null);
    });
    return out;
  }
  function renderDock() {
    var dock = store.get('dock', null);
    if (!dock || dock.length !== 4) { dock = defaultDock(); store.set('dock', dock); }
    if (!store.get('dockfix15', false)) { // v1.5 : le gestionnaire de fichiers avait été pris pour la musique
      var mus = findTheme('music');
      dock = dock.map(function (k) { var a = k && appByKey[k]; return a && a.theme === 'files' && mus ? key(mus) : k; });
      store.set('dock', dock); store.set('dockfix15', true);
    }
    var el = $('#dock'); el.innerHTML = '';
    dock.forEach(function (k, i) {
      var a = k ? appByKey[k] : null;
      var slot = document.createElement('div'); slot.className = 'dock-slot press';
      slot.innerHTML = a ? Icons.appIcon(a, 60) : '<div class="dock-empty">' + svg('plus') + '</div>';
      tappable(slot, function () { if (a) openApp(a, slot); else { showPage('apps', $('#btn-pages')); toast('Appui long sur une app pour la mettre dans le dock'); } },
        function () { dockSheet(i); });
      el.appendChild(slot);
    });
  }
  function setDock(i, a) { var d = store.get('dock', defaultDock()); d[i] = a ? key(a) : null; store.set('dock', d); renderDock(); alignGlass(); }

  /* ======================= feuilles (menus) ======================= */
  var sheetSpring = new Spring(0, { onUpdate: function (v) {
    var s = $('#sheet'); s.style.opacity = Math.max(0, Math.min(1, v));
    s.style.transform = 'translateY(-50%) scale(' + (0.92 + 0.08 * v) + ')';
    s.style.visibility = v > 0.01 ? 'visible' : 'hidden';
  } });
  function openSheet(html, bind) {
    var s = $('#sheet'); s.classList.remove('sheet-ready'); s.classList.remove('sheet-qr'); s.classList.remove('sheet-help'); s.classList.remove('sheet-acc'); s.classList.remove('sheet-learn'); s.removeAttribute('data-lk'); s.innerHTML = html; if (bind) bind(s);
    $('#sheet-scrim').classList.add('on'); alignGlass();
    sheetSpring.to(1, { response: 0.32, damping: 1 });
  }
  function closeSheet() { if (typeof SU !== 'undefined' && SU) SU.open = false; if (typeof VF !== 'undefined' && VF) VF.open = false; $('#sheet').style.top = ''; if (document.activeElement && document.activeElement.blur) document.activeElement.blur(); $('#sheet-scrim').classList.remove('on'); sheetSpring.to(0, { response: 0.25, damping: 1 }); }
  $('#sheet-scrim').addEventListener('click', function () {
    if (window.LB && LB.busy) return;
    if (typeof SU !== 'undefined' && SU && SU.open) store.set('setupDone', true);   // v1.26.1 : assistant fermé en touchant à côté = « Plus tard » (pas de retour à chaque démarrage)
    closeSheet();
  });   // v1.25 : pas de fermeture par erreur pendant l'apprentissage
  function appSheet(a) {
    var btns = '';
    for (var i = 0; i < 4; i++) btns += '<button class="sbtn press" data-slot="' + i + '">Dock · place ' + (i + 1) + '</button>';
    openSheet('<div class="t-headline">' + esc(a.label) + '</div><div class="t-sub">Choisis où l\'épingler dans la barre latérale.</div>' +
      '<div class="t-cap" style="margin-top:6px">Icône perso : icons/' + esc(a.pkg) + '.png</div>' +
      '<div class="sheet-actions">' + btns + '<button class="sbtn wide press" data-open="1">Ouvrir</button></div>', function (s) {
      $$('[data-slot]', s).forEach(function (b) { b.onclick = function () { setDock(+b.getAttribute('data-slot'), a); closeSheet(); toast('Ajouté au dock'); }; });
      $('[data-open]', s).onclick = function () { closeSheet(); openApp(a); };
    });
  }
  function dockSheet(i) {
    openSheet('<div class="t-headline">Place ' + (i + 1) + ' du dock</div><div class="t-sub">Pour changer d\'app : appui long sur une app dans la bibliothèque.</div>' +
      '<div class="sheet-actions"><button class="sbtn press" data-lib="1">Bibliothèque</button><button class="sbtn danger press" data-clear="1">Retirer</button></div>', function (s) {
      $('[data-lib]', s).onclick = function () { closeSheet(); showPage('apps', $('#btn-pages')); };
      $('[data-clear]', s).onclick = function () { setDock(i, null); closeSheet(); };
    });
  }
  function esc(t) { return String(t).replace(/[&<>"]/g, function (c) { return { '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]; }); }

  /* ======================= toast ======================= */
  var toastT;
  function toast(t) { var el = $('#toast'); el.textContent = t; alignGlass(); el.classList.add('on'); clearTimeout(toastT); toastT = setTimeout(function () { el.classList.remove('on'); }, 2400); }
  Bridge.on('toast', toast);

  /* ======================= navigation entre pages ======================= */
  var current = 'home';
  var pageSprings = {};
  function pageEl(n) { return $('#page-' + n); }
  function showPage(name, originEl) {
    if (name === current) return;
    var from = pageEl(current), to = pageEl(name);
    var st = $('#stage').getBoundingClientRect();
    var ox = '50%', oy = '50%';
    if (originEl) { var r = originEl.getBoundingClientRect(); ox = ((r.left + r.width / 2 - st.left) / SC) + 'px'; oy = ((r.top + r.height / 2 - st.top) / SC) + 'px'; }
    to.style.transformOrigin = ox + ' ' + oy; from.style.transformOrigin = ox + ' ' + oy;
    animatePage(from, 0, 1.04); animatePage(to, 1, 0.9);
    current = name;
    $('#btn-pages').innerHTML = svg(name === 'home' ? 'grid' : 'dash');
    if (name === 'vehicle' || name === 'home') hideLive(true);
    if (name === 'vehicle') renderVehicle();
    if (name === 'settings') renderSettings();
    if (name === 'custom') renderCustom();
    if (name === 'apps') setAppsPage(appsPageIdx, 0, true);
  }
  function animatePage(el, target, startScale) {
    var id = el.id, sp = pageSprings[id];
    if (!sp) {
      sp = pageSprings[id] = new Spring(el.classList.contains('is-active') ? 1 : 0, { onUpdate: function (v, s) {
        var sc = s.dir ? (s.from + (1 - s.from) * v) : (1 + (s.from - 1) * (1 - v));
        el.style.opacity = Math.max(0, Math.min(1, v));
        el.style.transform = 'scale(' + sc + ')';
        el.style.visibility = v > 0.005 ? 'visible' : 'hidden';
        el.style.pointerEvents = v > 0.5 ? 'auto' : 'none';
      } });
    }
    sp.dir = target === 1; sp.from = startScale;
    el.classList.toggle('is-active', target === 1);
    el.classList.add('anim');
    if (lite) { sp.set(target); el.style.transform = ''; el.classList.remove('anim'); if (target === 1) alignGlass(); return; }
    sp.to(target, { response: 0.38, damping: 1, onRest: function () { el.classList.remove('anim'); if (target === 1) { el.style.transform = ''; alignGlass(); } } });
  }
  tappable($('#btn-pages'), function () { showPage(current === 'home' ? 'apps' : 'home', $('#btn-pages')); });

  /* ======================= bibliothèque (pages + élan) ======================= */
  var appsPageIdx = 0, appsPages = 1, trackX = new Spring(0, { onUpdate: function (v) { $('#apps-track').style.transform = 'translate3d(' + v + 'px,0,0)'; } });
  function renderApps() {
    var per = PER_PAGE, vp = $('#apps-viewport'), w = vp.clientWidth || 900;
    var list = apps; appsPages = Math.max(1, Math.ceil(list.length / per));
    var html = '';
    for (var p = 0; p < appsPages; p++) {
      html += '<div class="apps-page" style="width:' + w + 'px">';
      list.slice(p * per, p * per + per).forEach(function (a, i) {
        html += '<div class="app" data-i="' + (p * per + i) + '">' + Icons.appIcon(a, root.classList.contains('apps2') ? 112 : 76) + '<div class="app-l">' + esc(a.label) + '</div></div>';
      });
      html += '</div>';
    }
    $('#apps-track').innerHTML = html;
    $$('.app', $('#apps-track')).forEach(function (el) {
      var a = list[+el.getAttribute('data-i')];
      tappable(el, function () { if (!dragging) openApp(a, el); }, function () { if (!a.virtual) appSheet(a); });
    });
    var dots = ''; for (var d = 0; d < appsPages; d++) dots += '<i></i>';
    $('#apps-dots').innerHTML = appsPages > 1 ? dots : '';
    setAppsPage(Math.min(appsPageIdx, appsPages - 1), 0, true);
  }
  function setAppsPage(i, vel, instant) {
    appsPageIdx = i;
    var w = $('#apps-viewport').clientWidth;
    if (instant) trackX.set(-i * w); else trackX.to(-i * w, { response: 0.4, damping: vel ? 0.86 : 1, velocity: vel || 0 });
    $$('#apps-dots i').forEach(function (d, k) { d.classList.toggle('on', k === i); });
  }
  var dragging = false;
  (function () {
    var vp = $('#apps-viewport'), tr = new Motion.Tracker(), sx = 0, sy = 0, base = 0, locked = null, id = null;
    vp.addEventListener('pointerdown', function (e) { id = e.pointerId; sx = e.clientX; sy = e.clientY; base = trackX.x; trackX.stop(); locked = null; dragging = false; tr = new Motion.Tracker(); tr.add(e.clientX / SC, 0); });
    vp.addEventListener('pointermove', function (e) {
      if (e.pointerId !== id) return;
      var dx = (e.clientX - sx) / SC, dy = (e.clientY - sy) / SC;
      if (locked === null && (Math.abs(dx) > 10 || Math.abs(dy) > 10)) { locked = Math.abs(dx) > Math.abs(dy) ? 'x' : 'y'; if (locked === 'x') { dragging = true; try { vp.setPointerCapture(id); } catch (er) {} } }
      if (locked !== 'x') return;
      tr.add(e.clientX / SC, 0);
      var w = vp.clientWidth, min = -(appsPages - 1) * w, x = base + dx;
      if (x > 0) x = Motion.rubber(x, w); else if (x < min) x = min - Motion.rubber(min - x, w);
      trackX.set(x);
    });
    function end(e) {
      if (e.pointerId !== id) return; id = null;
      if (locked !== 'x') return;
      var w = vp.clientWidth, v = tr.velocity().x;
      var projected = trackX.x + Motion.project(v, 0.99);
      var i = Math.round(-projected / w); i = Math.max(appsPageIdx - 1, Math.min(appsPageIdx + 1, i)); i = Math.max(0, Math.min(appsPages - 1, i));
      setAppsPage(i, v);
      setTimeout(function () { dragging = false; }, 50);
    }
    vp.addEventListener('pointerup', end); vp.addEventListener('pointercancel', end);
  })();

  /* ======================= plateau tournant 3D ======================= */
  /* Les 48 vues sont décodées UNE fois en mémoire (ImageBitmap) puis dessinées dans un <canvas>.
     Avant : on changeait le src de deux <img> à chaque cran de 7,5° -> le WebView redécodait le WebP
     au moment de l'affichage (à-coup), et le fondu « B par-dessus A opaque » laissait dépasser la
     silhouette de l'ancienne vue jusqu'au changement de cran (petit saut visible à chaque cran). */
  var N = CFG.frames, STEP = 360 / N, bitmaps = new Array(N), loaded = 0;
  var ttCanvas = $('#tt-canvas');
  /* v1.22 : rotation continue. Entre deux vues (7,5° d'écart, jusqu'à 80 px de déplacement), le fondu enchaîné montrait deux
     voitures superposées : la rotation semblait sauter d'une image à l'autre. Désormais chaque pixel suit le mouvement réel
     de la voiture (flux optique précalculé entre vues voisines : img/car/flow_XX.png, 240×134, quelques Ko chacun) et la
     carte graphique dessine la position intermédiaire exacte. Mémoire : les 48 vues passent dans des textures et leurs copies
     sont libérées ; les flux ajoutent environ 6 Mo. Sans WebGL ou sans fichiers de flux : ancien fondu enchaîné. */
  var GLR = (CFG.flowMax && CFG.carPack !== false) ? makeGL(ttCanvas) : null;
  var ttCtx = GLR ? null : ttCanvas.getContext('2d');
  function makeGL(cv) {
    var gl = null, o = { alpha: true, premultipliedAlpha: true, antialias: false, depth: false, stencil: false, preserveDrawingBuffer: false, powerPreference: 'low-power' };
    try { gl = cv.getContext('webgl', o) || cv.getContext('experimental-webgl', o); } catch (e) { gl = null; }
    if (!gl) return null;
    function sh(type, src) { var x = gl.createShader(type); gl.shaderSource(x, src); gl.compileShader(x); return gl.getShaderParameter(x, gl.COMPILE_STATUS) ? x : null; }
    var vs = sh(gl.VERTEX_SHADER, 'attribute vec2 p; varying vec2 uv; void main(){ uv = vec2(p.x * 0.5 + 0.5, 0.5 - p.y * 0.5); gl_Position = vec4(p, 0.0, 1.0); }');
    var fs = sh(gl.FRAGMENT_SHADER, '#ifdef GL_FRAGMENT_PRECISION_HIGH\nprecision highp float;\n#else\nprecision mediump float;\n#endif\n' +
      'uniform sampler2D A; uniform sampler2D B; uniform sampler2D F; uniform float t; uniform vec2 k; varying vec2 uv;\n' +
      'void main(){ vec2 f = (texture2D(F, uv).rg - 0.50196) * k; vec4 a = texture2D(A, uv - t * f); vec4 b = texture2D(B, uv + (1.0 - t) * f); gl_FragColor = mix(a, b, t); }');
    if (!vs || !fs) return null;
    var pr = gl.createProgram(); gl.attachShader(pr, vs); gl.attachShader(pr, fs); gl.linkProgram(pr);
    if (!gl.getProgramParameter(pr, gl.LINK_STATUS)) return null;
    gl.useProgram(pr);
    var buf = gl.createBuffer(); gl.bindBuffer(gl.ARRAY_BUFFER, buf); gl.bufferData(gl.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), gl.STATIC_DRAW);
    var lp = gl.getAttribLocation(pr, 'p'); gl.enableVertexAttribArray(lp); gl.vertexAttribPointer(lp, 2, gl.FLOAT, false, 0, 0);
    gl.uniform1i(gl.getUniformLocation(pr, 'A'), 0); gl.uniform1i(gl.getUniformLocation(pr, 'B'), 1); gl.uniform1i(gl.getUniformLocation(pr, 'F'), 2);
    var R = { gl: gl, uT: gl.getUniformLocation(pr, 't'), uK: gl.getUniformLocation(pr, 'k'), tex: new Array(N), flow: new Array(N), zero: null };
    R.make = function (src, isFlow) {
      var tx = gl.createTexture(); gl.bindTexture(gl.TEXTURE_2D, tx);
      gl.pixelStorei(gl.UNPACK_PREMULTIPLY_ALPHA_WEBGL, !isFlow);
      gl.pixelStorei(gl.UNPACK_COLORSPACE_CONVERSION_WEBGL, isFlow ? gl.NONE : gl.BROWSER_DEFAULT_WEBGL);
      if (src instanceof Uint8Array) gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, 1, 1, 0, gl.RGBA, gl.UNSIGNED_BYTE, src);
      else gl.texImage2D(gl.TEXTURE_2D, 0, gl.RGBA, gl.RGBA, gl.UNSIGNED_BYTE, src);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR);
      gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_S, gl.CLAMP_TO_EDGE); gl.texParameteri(gl.TEXTURE_2D, gl.TEXTURE_WRAP_T, gl.CLAMP_TO_EDGE);
      return tx;
    };
    R.zero = R.make(new Uint8Array([128, 128, 128, 255]), true);          // flux nul (fondu simple) tant qu'un flux manque
    R.draw = function (i, j, t) {
      var w = cv.width, h = cv.height; gl.viewport(0, 0, w, h);
      gl.clearColor(0, 0, 0, 0); gl.clear(gl.COLOR_BUFFER_BIT);
      var a = R.tex[i] || R.tex[j], b = R.tex[j] || R.tex[i], both = !!(R.tex[i] && R.tex[j]);
      if (!a) return;
      var K = 255 / 127 * CFG.flowMax;
      gl.uniform2f(R.uK, K / (natW || 720), K / (natH || 402)); gl.uniform1f(R.uT, both ? t : 0);
      gl.activeTexture(gl.TEXTURE0); gl.bindTexture(gl.TEXTURE_2D, a);
      gl.activeTexture(gl.TEXTURE1); gl.bindTexture(gl.TEXTURE_2D, b);
      gl.activeTexture(gl.TEXTURE2); gl.bindTexture(gl.TEXTURE_2D, (both && R.flow[i]) || R.zero);
      gl.drawArrays(gl.TRIANGLE_STRIP, 0, 4);
    };
    R.loadFlows = function () {
      for (var i = 0; i < N; i++) (function (i) {
        var im = new Image();
        im.onload = function () { try { R.flow[i] = R.make(im, true); if (drawn.i === i) { drawn.i = -1; drawAngle(angle.x); } } catch (e) {} };
        im.src = 'img/car/flow_' + pad(i) + '.png';
      })(i);
    };
    cv.addEventListener('webglcontextlost', function (e) { e.preventDefault(); });
    cv.addEventListener('webglcontextrestored', function () { location.reload(); });    // rarissime : on repart proprement
    return R;
  }
  var drawn = { i: -1, j: -1, t: -1 };
  /* v1.16 : véhicule configurable (js/config.js -> car) ; sans pack 3D, la carte affiche un message au lieu de la voiture */
  var CAR = CFG.car || { name: 'Ibiza', model: 'Seat Ibiza', fuel: 'diesel' };
  /* v1.24.1 : la page doit montrer le pack actif. Si le WebView a resservi un ancien js/config.js (nom et vue du dessus de
     la voiture précédente), Android recharge la page sans cache (2 essais au plus, pour ne jamais boucler). */
  (function () {
    var want = Bridge.packShown(), have = (window.IBZ_PACK && IBZ_PACK.id) || '', k = 'ibz.packfix', n = 9;
    if (want == null) return;
    try {
      if (want === have) { sessionStorage.removeItem(k); return; }
      n = +sessionStorage.getItem(k) || 0; sessionStorage.setItem(k, String(n + 1));
    } catch (e) {}
    if (n < 2) { console.error('pack affiché « ' + have + ' » au lieu de « ' + want + ' » : rechargement sans cache'); Bridge.packReload(); }
  })();
  document.title = CAR.name;
  $$('.car-name').forEach(function (e) { e.textContent = CAR.name; });
  $$('.car-model').forEach(function (e) { e.textContent = CAR.model; });
  if (CFG.carPack === false) root.classList.add('nocar');
  if (CFG.topView === false) root.classList.add('notop');
  /* v1.20 (autoradio à 2 Go de RAM) : les 48 vues sont décodées à la taille où elles s'affichent, jamais plus grandes que
     l'image d'origine (720×402). Disposition « Voiture » : environ un quart de mémoire en moins ; « Conduite » : près de
     moitié moins ; « Voiture XL » : taille d'origine. Redécodées seulement si la disposition ou la taille de l'interface
     change nettement, chaque ancienne vue étant libérée (close) dès que la nouvelle est prête. */
  var frameW = 0, natW = 0, natH = 0, frameGen = 0;
  function wantFrameW(nw) {
    var w = Math.ceil(ttCanvas.offsetWidth * SC * (window.devicePixelRatio || 1) / 16) * 16;
    return (!w || w >= nw) ? nw : Math.max(320, w);
  }
  function setFrameSize(w) {
    frameW = w; ttCanvas.width = w; ttCanvas.height = Math.round(w * natH / natW);
    drawn.i = -1; if (typeof angle !== 'undefined' && angle) drawAngle(angle.x);
  }
  function loadFrame(f) {
    if (CFG.carPack === false) return;
    var gen = frameGen, im = new Image();
    im.src = 'img/car/hero_' + pad(f) + '.webp';
    var done = function (bm) {
      if (gen !== frameGen) { if (bm && bm.close) try { bm.close(); } catch (e) {} return; }   // redécodage plus récent en cours
      if (GLR) {                                                                                // v1.22 : vue -> texture, copie libérée
        var ot = GLR.tex[f]; GLR.tex[f] = GLR.make(bm, false); if (ot) GLR.gl.deleteTexture(ot);
        if (bm && bm.close) try { bm.close(); } catch (e) {}
        bitmaps[f] = true;
      } else {
        var old = bitmaps[f]; bitmaps[f] = bm;
        if (old && old !== bm && old.close) try { old.close(); } catch (e) {}
      }
      loaded++;
      drawn.i = -1; drawAngle(angle.x);
    };
    var keep = function () {
      if (!natW && im.naturalWidth) { natW = im.naturalWidth; natH = im.naturalHeight; setFrameSize(wantFrameW(natW)); }
      if (!window.createImageBitmap) { done(im); return; }
      var small = frameW && frameW < im.naturalWidth, p;
      var pm = GLR ? { premultiplyAlpha: 'premultiply' } : {};
      if (small) { pm.resizeWidth = frameW; pm.resizeHeight = ttCanvas.height; pm.resizeQuality = 'medium'; }
      try { p = createImageBitmap(im, pm); }
      catch (e) { p = createImageBitmap(im); }
      p.then(done, function () { createImageBitmap(im).then(done, function () { done(im); }); });
    };
    if (im.decode) im.decode().then(keep, function () { im.onload = keep; if (im.complete && im.naturalWidth) keep(); });
    else { im.onload = keep; }
  }
  function frameOrder() {
    var c = Math.round((((angle.x / STEP) % N) + N) % N) % N, order = [c];
    for (var k = 1; k <= N / 2; k++) { order.push((c + k) % N); if (k < N / 2) order.push((c - k + N) % N); }
    return order;
  }
  function refitFrames() {
    if (CFG.carPack === false || !natW || !frameW) return;
    var w = wantFrameW(natW);
    if (w <= frameW * 1.05 && w >= frameW * 0.8) return;   // plus grand : netteté ; nettement plus petit : mémoire
    frameGen++; setFrameSize(w);          // les anciennes vues restent affichées (mises à l'échelle) jusqu'à leur remplacement
    frameOrder().forEach(loadFrame);
  }
  ttCanvas.width = 600; ttCanvas.height = 335;
  /* v1.12 : position par défaut = 3/4 avant droit (vue 8) — au démarrage, au réveil et quand tout se referme */
  var HOME = (CFG.homeView != null ? CFG.homeView : 8) * STEP;
  function homeTarget() { return angle.x + ((((HOME - angle.x) % 360) + 540) % 360 - 180); }
  var angle = new Spring(HOME, { precision: 0.05, onUpdate: function (d) { drawAngle(d); } });
  // la vue courante d'abord, puis le reste en s'éloignant (le plateau s'affiche tout de suite)
  frameOrder().forEach(loadFrame);
  if (GLR) setTimeout(GLR.loadFlows, 1500);          // v1.22 : les flux après les vues (la voiture s'affiche d'abord)
  function drawAngle(deg) {
    var p = ((deg / STEP) % N + N) % N, i = Math.floor(p) % N, j = (i + 1) % N, t = p - Math.floor(p);
    t = lite ? (t < 0.5 ? 0 : 1) : GLR ? Math.round(t * 256) / 256 : Math.round(t * 64) / 64;   // WebGL : 256 positions entre deux vues
    if (GLR) {
      if (t === 1) { i = j; j = (i + 1) % N; t = 0; }
      if (!GLR.tex[i] && !GLR.tex[j]) return;
      var fk = GLR.flow[i] ? 1 : 0;
      if (drawn.i === i && drawn.j === j && drawn.t === t && drawn.f === fk) return;
      drawn.i = i; drawn.j = j; drawn.t = t; drawn.f = fk;
      GLR.draw(i, j, t);
      return;
    }
    if (t === 1) { i = j; j = (i + 1) % N; t = 0; }
    var A = bitmaps[i], B = bitmaps[j];
    if (!A && !B) return;
    if (!A) { A = B; t = 0; } else if (!B) { t = 0; }
    if (drawn.i === i && drawn.j === j && drawn.t === t) return; // rien n'a changé : pas de redessin
    drawn.i = i; drawn.j = j; drawn.t = t;
    var w = ttCanvas.width, h = ttCanvas.height;
    ttCtx.globalCompositeOperation = 'source-over'; ttCtx.globalAlpha = 1;
    ttCtx.clearRect(0, 0, w, h);
    if (t === 0) { ttCtx.drawImage(A, 0, 0, w, h); return; }
    // vrai fondu enchaîné : A·(1−t) + B·t (mode additif « lighter » en alpha prémultiplié)
    // -> ni silhouette fantôme qui saute au changement de cran, ni voiture qui devient translucide.
    ttCtx.globalAlpha = 1 - t; ttCtx.drawImage(A, 0, 0, w, h);
    ttCtx.globalCompositeOperation = 'lighter'; ttCtx.globalAlpha = t; ttCtx.drawImage(B, 0, 0, w, h);
    ttCtx.globalCompositeOperation = 'source-over'; ttCtx.globalAlpha = 1;
  }
  /* portes animées en 3D : séquences pré-rendues à l'angle qui montre le mieux chaque ouvrant */
  // v1.22.1 : sans pack voiture, aucune image de porte n'est chargée (avant : demandes inutiles, erreurs dans le journal)
  var DV = CFG.carPack === false ? {} : (CFG.doorViews || {}), DSTEPS = CFG.doorSteps || 6, doorK = {}, doorImgs = {}, doorAnim = {};
  Object.keys(DV).forEach(function (d) {
    doorK[d] = 0;
    var im = document.createElement('img'); im.className = 'tt-img'; im.alt = ''; $('#tt-doors').appendChild(im); doorImgs[d] = im;
    for (var k = 1; k <= DSTEPS; k++) { var pre = new Image(); pre.src = 'img/car/door_' + d + '_' + k + '.webp'; }
    if (d === 'fl' || d === 'fr') { var pc = new Image(); pc.src = 'img/car/door_' + (d === 'fl' ? 'flrl' : 'frrr') + '_' + DSTEPS + '.webp'; }
  });
  function viewIndex() {
    var p = ((angle.x / STEP) % N + N) % N, i = Math.round(p);
    return Math.abs(p - i) < 0.04 ? i % N : -1;
  }
  /* 5 portes : les deux portes d'un même côté partagent une vue ; ouvertes ensemble -> image combinée */
  var PAIRS = [['fl', 'rl', 'flrl', 'L'], ['fr', 'rr', 'frrr', 'R']];
  /* v1.13 : à la vue de 3/4 arrière d'un côté, les deux portes de ce côté et le hayon forment un seul ensemble :
     un ouvrant s'anime pendant que les autres restent ouverts (images door_m<qui bouge>_s<ouverts>_<étape>). */
  var SIDEV = {}; SIDEV[DV.fl] = PAIRS[0]; SIDEV[DV.fr] = PAIRS[1];
  function sideFinal(p, S) {
    var a = S.indexOf(p[0]) >= 0, b = S.indexOf(p[1]) >= 0, t = S.indexOf('trunk') >= 0;
    if (!a && !b) return t ? 'door_trunk' + p[3] : null;
    var key = a && b ? p[2] : a ? p[0] : p[1];
    return t ? 'door_' + key + '_trunk' : 'door_' + key + '_' + DSTEPS;
  }
  function sideName(p) {
    var parts = [p[0], p[1], 'trunk'], mv = [], st = [];
    parts.forEach(function (d) { var k = doorK[d] || 0; if (k >= DSTEPS) st.push(d); else if (k > 0) mv.push(d); });
    if (!mv.length) return sideFinal(p, st);
    var m = mv[0];
    for (var i = 1; i < mv.length; i++) if (doorK[mv[i]] >= DSTEPS / 2) st.push(mv[i]);   // deux qui bougent : rare, on fige le second
    st = parts.filter(function (d) { return st.indexOf(d) >= 0; });
    if (!st.length) return m === 'trunk' ? null : 'door_' + m + '_' + doorK[m];
    return 'door_m' + m + '_s' + st.join('') + '_' + doorK[m];
  }
  function preloadCombos(d3) {   // les images qui pourront servir au prochain mouvement de ce côté
    PAIRS.forEach(function (p) {
      var parts = [p[0], p[1], 'trunk'], open = parts.filter(function (d) { return d3[d]; });
      if (!d3[p[0]] && !d3[p[1]]) return;
      parts.forEach(function (m) {
        var st = open.filter(function (d) { return d !== m; }); if (!st.length) return;
        for (var k = 1; k < DSTEPS; k++) { var im = new Image(); im.src = 'img/car/door_m' + m + '_s' + st.join('') + '_' + k + '.webp'; }
      });
    });
  }
  function drawDoors() {
    var vi = viewIndex(), src = {}, p = CFG.comboAnim ? SIDEV[vi] : null;
    if (p) {
      var nm = sideName(p);
      if (nm) src[p[0]] = 'img/car/' + nm + '.webp';
    } else {
      Object.keys(DV).forEach(function (d) { if (vi === DV[d] && doorK[d] > 0) src[d] = 'img/car/door_' + d + '_' + doorK[d] + '.webp'; });
      PAIRS.forEach(function (q) {
        if (src[q[0]] && src[q[1]] && doorK[q[0]] === DSTEPS && doorK[q[1]] === DSTEPS) { src[q[0]] = 'img/car/door_' + q[2] + '_' + DSTEPS + '.webp'; delete src[q[1]]; }
        else if (src[q[0]] && src[q[1]]) { if (doorK[q[0]] === DSTEPS) delete src[q[0]]; else delete src[q[1]]; }
      });
    }
    Object.keys(DV).forEach(function (d) {
      var im = doorImgs[d], show = !!src[d];
      if (show && im.getAttribute('src') !== src[d]) im.setAttribute('src', src[d]);
      im.classList.toggle('on', show);
    });
  }
  function rotateTo(v, cb) {
    var target = v * STEP, cur = angle.x, diff = ((target - cur) % 360 + 540) % 360 - 180;
    if (Math.abs(diff) < 0.5 || Motion.reduced) { angle.set(cur + diff); cb(); }
    else angle.to(cur + diff, { response: 0.7, damping: 1, onRest: cb });
  }
  function playDoor(d, to, done) {
    cancelAnimationFrame(doorAnim[d]);
    var from = doorK[d], t0 = performance.now(), dur = Motion.reduced ? 1 : 650;
    (function step(t) {
      var u = Math.min(1, (t - t0) / dur), e = 1 - Math.pow(1 - u, 3);
      doorK[d] = Math.round(from + (to - from) * e); drawDoors();
      if (u < 1) doorAnim[d] = requestAnimationFrame(step); else if (done) done();
    })(t0);
  }
  /* v1.12 : un ouvrant se referme alors qu'un autre reste ouvert -> la voiture revient sur la vue de celui resté ouvert
     (avant : elle restait sur la vue du coffre, et la porte encore ouverte n'apparaissait plus) */
  var homeT = null;
  function goHome() {          // tout est refermé : retour en douceur sur la position par défaut
    clearTimeout(homeT);
    homeT = setTimeout(function () {
      if (anyOpen3D()) return;
      var t = homeTarget(); if (Math.abs(t - angle.x) < 0.5) return;
      angle.to(t, { response: 0.9, damping: 1, onRest: function () { store.set('angle', angle.x % 360); } });
    }, 900);
  }
  function backToOpen() {
    var d3 = doors3D(veh.doors);
    var rest = ['fl', 'rl', 'fr', 'rr', 'trunk', 'hood'].filter(function (k) { return d3[k] && DV[k] != null; })[0];
    if (hiKey) return;
    if (!rest) { goHome(); return; }
    doorK[rest] = DSTEPS;
    var target = DV[rest] * STEP, cur = angle.x, diff = ((target - cur) % 360 + 540) % 360 - 180;
    if (Math.abs(diff) < 0.5 || Motion.reduced) { angle.set(cur + diff); drawDoors(); }
    else angle.to(cur + diff, { response: 0.7, damping: 1, onRest: drawDoors });
  }
  /* portes ouvertes des deux côtés : une seule vue de face légèrement plongeante (9 combinaisons pré-rendues),
     la voiture du plateau s'efface en fondu derrière */
  var HI = !!CFG.hiView, hiKey = null;
  function hiName(d3) {
    var L = d3.fl && d3.rl ? 'flrl' : d3.fl ? 'fl' : d3.rl ? 'rl' : '';
    var R = d3.fr && d3.rr ? 'frrr' : d3.fr ? 'fr' : d3.rr ? 'rr' : '';
    if (!(HI && L && R)) return null;
    return L + '_' + R + (d3.trunk ? '_t' : '');      // v1.13 : coffre ouvert visible dans la vue de face (le capot n'est pas détecté sur l'Ibiza)
  }
  if (HI) setTimeout(function () {
    ['fl', 'rl', 'flrl'].forEach(function (l) { ['fr', 'rr', 'frrr'].forEach(function (r) { var p = new Image(); p.src = 'img/car/door_hi_' + l + '_' + r + '.webp'; }); });
    var f = new Image(); f.src = 'img/car/floor_hi.webp';
  }, 4000);
  function applyHi(k) {
    hiKey = k;
    if (k) { $('#tt-floor-hi').setAttribute('src', 'img/car/floor_hi.webp'); $('#tt-hi').setAttribute('src', 'img/car/door_hi_' + k + '.webp'); }
    $('#turntable').classList.toggle('hi', !!k);
  }
  /* 5 portes : chaque ouvrant a sa propre animation, vue de 3/4 arrière du côté concerné */
  function doors3D(nd) { nd = nd || {}; var o = {}; Object.keys(DV).forEach(function (k) { o[k] = !!nd[k]; }); return o; }
  function showDoor3D(d) { // fait pivoter la voiture vers le bon angle puis ouvre la porte
    var target = DV[d] * STEP, cur = angle.x, diff = ((target - cur) % 360 + 540) % 360 - 180;
    var go = function () { playDoor(d, doors3D(veh.doors)[d] ? DSTEPS : 0); };
    if (Math.abs(diff) < 0.5 || Motion.reduced) { angle.set(cur + diff); go(); }
    else angle.to(cur + diff, { response: 0.7, damping: 1, onRest: go });
  }
  var drawAngle0 = drawAngle;
  drawAngle = function (deg) { drawAngle0(deg); drawDoors(); };
  angle.onUpdate = drawAngle;
  drawAngle(angle.x);
  (function () {
    var el = $('#turntable'), tr, sx, base, id = null, moved = false;
    el.addEventListener('pointerdown', function (e) {
      id = e.pointerId; sx = e.clientX; base = angle.x; moved = false; lockHinted = false;
      if (!anyOpen3D()) angle.stop();
      tr = new Motion.Tracker(); tr.add(e.clientX, 0); try { el.setPointerCapture(id); } catch (er) {}
    });
    el.addEventListener('pointermove', function (e) {
      if (e.pointerId !== id) return; tr.add(e.clientX, 0);
      var dx = e.clientX - sx; if (Math.abs(dx) > 6) moved = true;
      if (anyOpen3D()) { if (moved && !lockHinted) { lockHinted = true; toast('Ferme les ouvrants pour faire tourner la voiture'); } return; }
      angle.set(base - dx * 0.5);
    });
    function end(e) {
      if (e.pointerId !== id) return; id = null;
      if (!moved) { if (root.classList.contains('nocar')) showPage('custom', $('#card-car')); else showPage('vehicle', $('#card-car')); return; }
      if (anyOpen3D()) return;   // voiture verrouillée sur la vue de l'ouvrant
      var v = -tr.velocity().x * 0.5; // deg/s
      var proj = angle.x + Motion.project(v, 0.995);
      var target = Math.round(proj / STEP) * STEP;
      angle.to(target, { response: 0.5, damping: 1, velocity: v, onRest: function () { store.set('angle', angle.x % 360); } });
      $('#tt-hint').style.opacity = 0;
    }
    el.addEventListener('pointerup', end); el.addEventListener('pointercancel', end);
  })();
  /* Les portes animées n'existent qu'à l'angle exact de leur vue : tant qu'un ouvrant est ouvert, on bloque la rotation. */
  var lockHinted = false;
  function anyOpen3D() { var d = doors3D(veh.doors); return Object.keys(DV).some(function (k) { return d[k]; }); }
  function introSpin() { // petit tour d'accueil au réveil (désactivé si mouvement réduit ou mode léger)
    if (Motion.reduced || lite || anyOpen3D()) return;
    var a = Math.round(homeTarget() / STEP) * STEP;
    angle.set(a - 60); angle.to(a, { response: 1.3, damping: 1 });
  }

  /* ======================= média ======================= */
  var media = {}, playGuessT = null;
  function renderMedia(m) {
    media = m || {};
    if (playGuessT && media.playing === undefined) media.playing = playGuessT.v;
    var has = !!(media.title), isRadio = media.kind === 'radio';
    setText('#m-title', has ? media.title : 'Aucune lecture');                 // v1.21 : n'écrit que ce qui change
    setText('#m-artist', has ? (media.artist || '') : 'Touchez une source pour commencer');
    setText('#m-source', media.source || '');
    var pb = $('#m-play'), pi = media.playing ? 'pause' : 'play'; if (pb._ic !== pi) { pb.innerHTML = svg(pi); pb._ic = pi; }   // v1.21
    var art = $('#m-art');
    var bg = $('#m-bg');
    if (media.art) {
      if (art._src !== media.art) { art._src = media.art; art.style.backgroundImage = 'url(' + media.art + ')'; bg.style.backgroundImage = 'url(' + media.art + ')'; }
      if (art._ic) { art.innerHTML = ''; art._ic = null; } bg.classList.add('on');
    } else {
      if (art._src) { art._src = null; art.style.backgroundImage = ''; }
      var ai = isRadio ? 'radio' : 'music'; if (art._ic !== ai) { art.innerHTML = svg(ai); art._ic = ai; } bg.classList.remove('on');
    }
    art.classList.toggle('is-radio', isRadio);
    $('#card-media').classList.toggle('no-progress', !media.dur);
    var p = media.dur ? Math.max(0, Math.min(1, media.pos / media.dur)) : 0;
    $('#m-bar').style.transform = 'scaleX(' + p + ')'; $('#m-bar').style.width = '100%';
    setText('#m-t0', media.dur ? mmss(media.pos) : '');
    setText('#m-t1', media.dur ? '-' + mmss(media.dur - media.pos) : '');
  }
  $('#m-prev').innerHTML = svg('prev'); $('#m-next').innerHTML = svg('next');
  /* Lecture / pause : toujours une bascule. L'icône change tout de suite ; l'état réel (s'il est connu) la corrige. */
  tappable($('#m-play'), function () {
    var nowPlaying = !media.playing;
    playGuessT = { v: nowPlaying }; setTimeout(function () { playGuessT = null; }, 2500);
    $('#m-play').innerHTML = svg(nowPlaying ? 'pause' : 'play'); $('#m-play')._ic = nowPlaying ? 'pause' : 'play'; media.playing = nowPlaying;
    Bridge.media('toggle');
  });
  tappable($('#m-prev'), function () { Bridge.media('prev'); });
  tappable($('#m-next'), function () { Bridge.media('next'); });
  // pochette : ouvre l'appli qui joue (radio, Spotify…), sinon l'appli Musique choisie
  tappable($('#m-art'), function () {
    if (media.pkg) { var p = appByPkg(media.pkg); if (p) { openApp(p); return; } Bridge.launchPkg(media.pkg); return; }
    var a = findTheme(media.kind === 'radio' ? 'radio' : 'music'); if (a) openApp(a); else toast('Choisis ton appli Musique dans les réglages');
  });
  Bridge.on('media', renderMedia);
  function mmss(t) { t = Math.max(0, Math.round(t || 0)); return Math.floor(t / 60) + ':' + pad(t % 60); }
  /* applis par défaut choisies à la main (Réglages > Applis par défaut) ; sinon reconnaissance par le nom */
  function roleApp(th) { var r = store.get('roles', {}) || {}; return r[th] && appByKey[r[th]] ? appByKey[r[th]] : null; }
  function findTheme(th) {
    var ra = roleApp(th); if (ra) return ra;
    for (var i = 0; i < apps.length; i++) if (apps[i].theme === th && !apps[i].virtual) return apps[i];
    return null;
  }
  function appByPkg(pkg) { for (var i = 0; i < apps.length; i++) if (apps[i].pkg === pkg) return apps[i]; return null; }

  function renderQuick() {
    var q = $('#quick'); q.innerHTML = '';
    ['radio', 'eq', 'car', 'settings'].forEach(function (th) {
      var a = th === 'car' ? VIRTUAL[0] : findTheme(th); if (!a) a = th === 'settings' ? VIRTUAL[1] : null;
      var b = document.createElement('button'); b.className = 'qbtn press';
      var lbl = { radio: 'Radio', eq: 'Son', car: 'Véhicule', settings: 'Réglages' }[th];
      b.innerHTML = (a ? Icons.appIcon(a, 50) : Icons.appIcon({ theme: th }, 50)) + '<span>' + lbl + '</span>';
      tappable(b, function () { if (a) openApp(a, b); else toast('App introuvable sur le poste'); });
      q.appendChild(b);
    });
  }

  /* ======================= véhicule ======================= */
  var DOORS = [
    ['fl', 'Porte avant gauche', 'door'], ['fr', 'Porte avant droite', 'door'],
    ['rl', 'Porte arrière gauche', 'door'], ['rr', 'Porte arrière droite', 'door'],
    ['trunk', 'Coffre', 'trunk'], ['hood', 'Capot', 'hood']
  ];
  var veh = { doors: {}, radar: null, reverse: false, steer: null };
  var vehView = 'doors';
  function openList() { return DOORS.filter(function (d) { return veh.doors[d[0]]; }); }
  function statusText() {
    var o = openList();
    if (!o.length) return veh.doors.any ? { t: 'Un ouvrant est ouvert', c: 'warn' } : { t: 'Tout est fermé', c: 'ok' };
    if (o.length === 1) return { t: o[0][1] + ' ouvert' + (o[0][0] === 'trunk' || o[0][0] === 'hood' ? '' : 'e'), c: 'warn' };
    return { t: o.length + ' ouvrants ouverts', c: 'warn' };
  }
  function layersHtml() {
    return DOORS.map(function (d) { return '<img class="tv-layer" data-d="' + d[0] + '" src="img/top/' + d[0] + '.webp" alt="">'; }).join('');
  }
  $('#tv-layers').innerHTML = layersHtml(); $('#live-layers').innerHTML = layersHtml();
  function applyLayers(scope) {
    $$('.tv-layer', scope).forEach(function (l) { l.classList.toggle('on', !!veh.doors[l.getAttribute('data-d')]); });
  }
  function renderVehicleSummary() {
    var s = statusText();
    [$('#car-status'), $('#veh-status')].forEach(function (el) { el.innerHTML = '<i class="dot ' + s.c + '"></i><span>' + s.t + '</span>'; });
    var o = openList(), sv = serviceText();
    var chips = [
      { ic: 'door', b: 'Ouvrants', s: o.length ? (o.length === 1 ? o[0][1].replace('Porte ', '') : o.length + ' ouverts') : veh.doors.any ? 'Ouvert' : 'Fermés', a: o.length > 0 || !!veh.doors.any },
      (sv.t === '—' && car.fuel != null && !store.get('maintChip', false)) ? { ic: 'fuel', b: 'Carburant', s: fmtN(car.fuel) + ' L', a: car.fuel < 7, page: 'trip' } : { ic: 'wrench', b: 'Entretien', s: sv.t === '—' ? 'À renseigner' : sv.label ? sv.label + ' · ' + sv.t.replace('Dans ', '').replace('Dépassé de ', 'retard ').split(' · ')[0] : sv.t.split(' · ')[0], a: sv.warn, page: 'maint' },   // v1.20 : échéance la plus courte seulement (le détail est dans Entretien)
      { ic: 'radar', b: 'Radars', s: veh.radar ? minDist(veh.radar) : 'En veille', a: !!veh.radar }
    ];
    $('#car-chips').innerHTML = chips.map(function (c) {
      return '<div class="cchip' + (c.a ? ' alert' : '') + '">' + svg(c.ic) + '<div><b>' + c.b + '</b><span>' + c.s + '</span></div></div>';
    }).join('');
    $$('.cchip').forEach(function (c, i) { tappable(c, function () { if (chips[i].page === 'maint' && CFG.cockpit) { openCockpit(); return; } if (chips[i].page) setVehView(chips[i].page); showPage('vehicle', c); }); });
  }
  function renderVehicle() {
    applyLayers($('#topview'));
    var list = DOORS.map(function (d) {
      var o = !!veh.doors[d[0]];
      return '<div class="row">' + svg(d[2]) + '<div class="grow">' + d[1] + '</div><div class="val' + (o ? ' warn' : '') + '">' + (o ? 'Ouvert' : 'Fermé') + '</div></div>';
    }).join('');
    list += '<div class="hdr">Radars de recul</div><div class="row">' + svg('radar') + '<div class="grow">Distance mini</div><div class="val">' + (veh.radar ? minDist(veh.radar) : '—') + '</div></div>';
    if (veh.steer != null) list += '<div class="row">' + svg('car') + '<div class="grow">Angle du volant</div><div class="val" id="veh-steer">' + steerText() + '</div></div>';
    // v1.26 : apprentissage proposé là où l'on voit qu'il manque quelque chose
    var learned = Bridge.learnSupported() && Bridge.learnHasMap();
    if (Bridge.learnSupported() && !learned && !store.get('doorsSeen', false))
      list = '<div class="veh-learn"><div class="vl-top">' + svg('door') + '<b>Portes pas encore reconnues</b></div><span>Le lanceur peut apprendre ta voiture en 3 minutes, à l\'arrêt.</span><button class="sbtn press lrn-go" data-act="learn">Apprendre ma voiture</button></div>' + list;
    else if (Bridge.learnSupported()) list += '<div class="row btn veh-relearn" data-act="learn">' + svg('door') + '<div class="grow">' + (learned ? 'Réapprendre ma voiture' : 'Apprendre ma voiture') + '</div><div class="val">3 min</div></div>';
    $('#veh-list').innerHTML = list;
    $$('#veh-list [data-act]').forEach(function (el) { tappable(el, function () { act(el.getAttribute('data-act'), el); }); });
    drawRadar($('#radar-svg'));
    $('#radar-svg').classList.toggle('on', vehView === 'radar' || !!veh.radar);
    $('#topview').style.display = vehView === 'trip' || vehView === 'maint' ? 'none' : '';
    $('#tripview').style.display = vehView === 'trip' ? '' : 'none';
    $('#maintview').style.display = vehView === 'maint' ? '' : 'none';
    if (vehView === 'trip') renderTrip();
    if (vehView === 'maint') renderMaint();
  }
  function setVehView(v) {
    if (v === 'maint' && CFG.cockpit) { openCockpit(); return; }
    vehView = v;
    $$('#veh-seg .seg-b').forEach(function (x) { x.classList.toggle('is-on', x.getAttribute('data-v') === v); });
    if (current === 'vehicle') renderVehicle();
  }
  $$('#veh-seg .seg-b').forEach(function (b) { tappable(b, function () { setVehView(b.getAttribute('data-v')); renderVehicle(); }); });

  /* ======================= ordinateur de bord (CAN, profil VW) ======================= */
  var car = store.get('car', {}) || {};   // dernières valeurs connues (gardées au redémarrage)
  function fmtN(v, dec) { if (v == null || v === '' || isNaN(v)) return null; return Number(v).toFixed(dec || 0).replace('.', ',').replace(/\B(?=(\d{3})+(?!\d))/g, '\u202f'); }
  function serviceText() {
    var nx = maintNext();      // entretien saisi à la main (prioritaire : la voiture ne le fournit pas)
    if (nx) return { t: nx.short, warn: nx.level > 0, label: nx.name };
    var km = car.insp_km, d = car.insp_days;
    if (km == null && d == null) { km = car.oil_km; d = car.oil_days; }
    if (km == null && d == null) return { t: '—', warn: false };
    var parts = []; if (km != null) parts.push(fmtN(km) + ' km'); if (d != null) parts.push(fmtN(d) + ' j');
    return { t: 'Dans ' + parts.join(' · '), warn: (km != null && km < 1000) || (d != null && d < 30) };
  }
  var TRIP = [
    ['outside', 'Extérieur', 'temp', function (v) { return fmtN(v, 1) + '°'; }, function (v) { return v <= 3; }],
    ['coolant', 'Liquide de refroid.', 'temp', function (v) { return fmtN(v) + '°'; }, function (v) { return v > 110; }],
    ['oil', 'Huile moteur', 'temp', function (v) { return fmtN(v) + '°'; }, function (v) { return v > 130; }],
    ['battery', 'Batterie', 'battery', function (v) { return fmtN(v, 1) + ' V'; }, function (v) { return v < 11.8; }],
    ['insp', 'Prochaine révision', 'wrench', null, null],
    ['oilsvc', 'Prochaine vidange', 'wrench', null, null],
    ['range', 'Autonomie', 'fuel', function (v) { return fmtN(v) + ' km'; }, function (v) { return v < 60; }],
    ['cons', 'Consommation moy.', 'gauge', function (v) { return fmtN(v, 1) + ' L/100'; }, null],
    ['avg', 'Vitesse moyenne', 'gauge', function (v) { return fmtN(v) + ' km/h'; }, null],
    ['odo', 'Kilométrage', 'car', function (v) { return fmtN(v) + ' km'; }, null],
    ['fuel', 'Carburant restant', 'fuel', function (v) { return fmtN(v) + ' L'; }, function (v) { return v < 7; }],
    ['fuel_pct', 'Carburant restant', 'fuel', function (v) { return fmtN(v) + ' %'; }, function (v) { return v < 12; }],   // v1.27 : applis CAN qui donnent le niveau en %
    ['rpm', 'Régime moteur', 'gauge', function (v) { return fmtN(v) + ' tr/min'; }, null],
    ['speed', 'Vitesse', 'gauge', function (v) { return fmtN(v) + ' km/h'; }, null],
    // frein à main retiré : l'Ibiza ne l'envoie pas sur le CAN (reste « Pas de freins » même dans CarCan)
    ['belt', 'Ceinture conducteur', 'check', function (v) { return /ne pas|non|pas attach/i.test(v) ? 'Non bouclée' : v; }, function (v) { return /ne pas|non|pas attach/i.test(v); }],
    ['washer', 'Lave-glace', 'check', function (v) { return v; }, function (v) { return !/normal|ok/i.test(v); }]
  ];
  var TRIP_CORE = ['outside', 'battery', 'fuel', 'odo'];
  function svcVal(kk, dk) {
    var p = []; if (car[kk] != null) p.push(fmtN(car[kk]) + ' km'); if (car[dk] != null) p.push(fmtN(car[dk]) + ' j');
    return p.length ? { v: p.join(' · '), w: (car[kk] != null && car[kk] < 1000) || (car[dk] != null && car[dk] < 30) } : null;
  }
  function renderTrip() {
    var h = '<div class="trip-grid">';
    TRIP.forEach(function (t) {
      var val = null, warn = false;
      var has = t[0] === 'insp' ? (car.insp_km != null || car.insp_days != null) : t[0] === 'oilsvc' ? (car.oil_km != null || car.oil_days != null) : car[t[0]] != null;
      if (!has && TRIP_CORE.indexOf(t[0]) < 0) return;   // ce que le boîtier ne fournit pas n'est pas affiché
      if (t[0] === 'fuel' && !has && car.fuel_pct != null) return;   // niveau connu en % : pas de case « litres » vide
      if (t[0] === 'insp') { var a = svcVal('insp_km', 'insp_days'); if (a) { val = a.v; warn = a.w; } }
      else if (t[0] === 'oilsvc') { var b = svcVal('oil_km', 'oil_days'); if (b) { val = b.v; warn = b.w; } }
      else if (car[t[0]] != null) { val = t[3](car[t[0]]); warn = t[4] ? t[4](car[t[0]]) : false; }
      h += '<div class="trip' + (warn ? ' warn' : '') + (val ? '' : ' empty') + (t[0] === 'insp' || t[0] === 'oilsvc' ? ' wide' : '') + '">' + svg(t[2]) + '<div><span>' + t[1] + '</span><b>' + (val || '—') + '</b></div></div>';
    });
    h += '</div>';
    var when = car.at ? new Date(car.at) : null;
    var ci = Bridge.canAppSupported() ? (Bridge.canAppInfo() || {}) : { hcn: true };
    // v1.27 : poste dont l'appli CAN n'est pas encore connue : l'apprentissage est proposé là où les cases sont vides
    if (!ci.has && !ci.hcn && !Bridge.canLive()) h = '<div class="veh-learn"><div class="vl-top">' + svg('gauge') + '<b>Ordinateur de bord pas encore lu</b></div><span>Montre une fois au lanceur l\'appli « voiture » de ton autoradio : il lira ensuite carburant, batterie, kilométrage tout seul. 2 minutes.</span><button class="sbtn press lrn-go" id="trip-learn">Apprendre l\'ordinateur de bord</button></div>' + h;
    h += '<div class="trip-foot"><span>' + (when ? 'Mis à jour ' + pad(when.getHours()) + ':' + pad(when.getMinutes()) + (car.src ? ' · ' + esc(car.src) : '') : 'Aucune donnée CAN reçue pour l\'instant.') + '</span>' +
      '<span class="trip-btns"><button class="chip-btn press" id="trip-refresh">' + svg('check') + 'Actualiser</button><button class="chip-btn press" id="trip-open">' + svg('car') + esc(ci.has && ci.label ? String(ci.label).slice(0, 14) : ci.hcn ? 'CarCan' : 'Appli CAN') + '</button></span></div>';
    $('#tripview').innerHTML = h;
    if ($('#trip-learn')) tappable($('#trip-learn'), function () { canAppSheet(false); });
    tappable($('#trip-refresh'), function () {
      if (!Bridge.canRefreshPossible()) {
        if (Bridge.canAppSupported() && !ci.has && !ci.hcn) { canAppSheet(false); return; }
        toast('Active l\'accessibilité du lanceur pour lire l\'appli CAN'); return;
      }
      Bridge.canRefresh();
    });
    tappable($('#trip-open'), function () {
      var a = roleApp('can'); if (a) { openApp(a); return; }
      toast('Choisis l\'appli CAN dans Réglages > Applis par défaut'); showPage('settings', $('#trip-open'));
    });
  }
  tappable($('#btn-vehicle'), function () { showPage('vehicle', $('#btn-vehicle')); });
  $('#btn-vehicle').innerHTML = svg('car') + 'Véhicule';

  /* radars : capteurs arrière (4) et avant (4) en cm ; null = inconnu */
  function minDist(r) {
    var all = (r.rear || []).concat(r.front || []).filter(function (x) { return x != null; });
    if (!all.length) return 'Actifs';
    var m = Math.min.apply(null, all); return m >= 150 ? '> 1,5 m' : m + ' cm';
  }
  function zoneColor(d) { return d == null ? 'rgba(255,255,255,.10)' : d < 40 ? '#ff4245' : d < 80 ? '#ff9230' : d < 150 ? '#ffd600' : 'rgba(48,209,88,.55)'; }
  /* v1.23.1 : anneaux des radars resserrés quand la place manque derrière (ou devant) la voiture dans l'image du pack */
  var RING = { gap: 34, step: 20, thick: 15 }, RING_LEN = 34 + 3 * 20 + 15;
  function ringK(B, rear) {
    var room = rear ? (B.h || 750) - B.y1 : B.y0;
    return Math.max(0.55, Math.min(1, (room - 6) / RING_LEN));
  }
  // cadre occupé par les arcs (repère de topBox, 680 x 750)
  function radarBox(B, rad) {
    var cx = (B.x0 + B.x1) / 2, w = B.x1 - B.x0, box = null;
    function add(rear) {
      var k = ringK(B, rear), R = w * 0.5 + RING_LEN * k, cy = rear ? B.y1 - w * 0.5 : B.y0 + w * 0.5;
      var b = { x0: cx - R * 0.72, x1: cx + R * 0.72, y0: rear ? cy + w * 0.33 : cy - R, y1: rear ? cy + R : cy - w * 0.33 };
      box = box ? { x0: Math.min(box.x0, b.x0), y0: Math.min(box.y0, b.y0), x1: Math.max(box.x1, b.x1), y1: Math.max(box.y1, b.y1) } : b;
    }
    add(true);
    if (rad && rad.front && rad.front.some(function (x) { return x != null; })) add(false);
    if (veh.steer != null) { var pxm = (B.y1 - B.y0) / (CFG.carLen || 4.05); box.y1 = Math.max(box.y1, B.y1 + (GUIDE_M + 0.15) * pxm); box.x0 = Math.min(box.x0, cx - w * 0.9); box.x1 = Math.max(box.x1, cx + w * 0.9); }   // v1.26 : lignes de recul
    return box;
  }
  function drawRadar(el, noText) {
    var B = CFG.topBox || { x0: 219, x1: 460, y0: 49, y1: 633, w: 680, h: 750 }; // vue de dessus verticale, avant en haut
    el.setAttribute('viewBox', '0 0 ' + (B.w || 680) + ' ' + (B.h || 750));
    var cx = (B.x0 + B.x1) / 2, w = B.x1 - B.x0, r = veh.radar || { rear: [null, null, null, null] };
    var out = '';
    function sector(cy, r0, r1, a0, a1, col) {
      function pt(rr, a) { return (cx + rr * Math.cos(a)).toFixed(1) + ',' + (cy + rr * Math.sin(a)).toFixed(1); }
      return '<path fill="' + col + '" d="M' + pt(r0, a0) + 'L' + pt(r1, a0) + 'A' + r1 + ',' + r1 + ' 0 0 1 ' + pt(r1, a1) + 'L' + pt(r0, a1) + 'A' + r0 + ',' + r0 + ' 0 0 0 ' + pt(r0, a0) + 'Z"/>';
    }
    function arcs(side, vals) {
      var rear = side === 'rear', cy = rear ? B.y1 - w * 0.5 : B.y0 + w * 0.5, mid = rear ? Math.PI / 2 : -Math.PI / 2, k = ringK(B, rear);
      var span = Math.PI * 0.5;
      for (var s = 0; s < 4; s++) {
        var d = vals ? vals[s] : null; // capteurs de gauche à droite vus du dessus
        var a0 = mid - span / 2 + s * span / 4, a1 = a0 + span / 4;
        if (rear) { a0 = mid + span / 2 - (s + 1) * span / 4; a1 = a0 + span / 4; }
        for (var ring = 0; ring < 4; ring++) {
          var zi = d == null ? -1 : d < 40 ? 0 : d < 80 ? 1 : d < 150 ? 2 : d < 250 ? 3 : -1, lit = ring === zi;
          var r0 = w * 0.5 + (RING.gap + ring * RING.step) * k, r1 = r0 + RING.thick * k;
          out += sector(cy, r0, r1, a0 + 0.025, a1 - 0.025, lit ? zoneColor(d) : 'rgba(255,255,255,.08)');
        }
      }
    }
    arcs('rear', r.rear);
    if (r.front && r.front.some(function (x) { return x != null; })) arcs('front', r.front);
    var guides = veh.steer != null && (veh.radar || veh.reverse || vehView === 'radar');
    if (guides) out += steerGuides(B);
    if (el.id === 'radar-svg') $('#topview').classList.toggle('tv-guides', guides && current === 'vehicle');   // la voiture remonte pour laisser voir les lignes
    if (veh.radar && !noText) out += '<text x="' + (B.x1 + 46) + '" y="' + (B.y1 - w * 0.45) + '" fill="#fff" font-size="40" font-weight="700" text-anchor="start" font-family="UI">' + minDist(veh.radar) + '</text>';
    el.innerHTML = out;
  }

  /* v1.26 : lignes de trajectoire en marche arrière selon l'angle du volant (persist.vendor.cem.angle sur les postes hcn).
     Modèle « bicyclette » : rayon = empattement / tan(angle des roues) ; angle des roues = angle du volant / démultiplication.
     Réglages possibles dans js/config.js : steerMax (angle du volant en butée, 540°), wheelMax (roues en butée, 35°),
     carLen (4,05 m), wheelbase (2,47 m) ; sens inversable dans Réglages du lanceur. */
  function steerWheelDeg() {
    var s = veh.steer || 0, max = CFG.steerMax || 540;
    if (store.get('steerInv', false)) s = -s;
    return Math.max(-1, Math.min(1, s / max)) * (CFG.wheelMax || 35);
  }
  var GUIDE_M = 1.8;   // longueur des lignes derrière la voiture (m)
  function steerGuides(B) {
    var len = B.y1 - B.y0, pxm = len / (CFG.carLen || 4.05), w = B.x1 - B.x0, ax = (B.x0 + B.x1) / 2;
    var ay = B.y1 - 0.66 * pxm, hx = Math.min(0.72 * pxm, w * 0.42), L = (CFG.wheelbase || 2.47) * pxm, run = GUIDE_M * pxm;
    var d = steerWheelDeg() * Math.PI / 180, out = '', tracks = [];
    [-hx, hx].forEach(function (ox) {
      var pts = [];
      if (Math.abs(d) < 0.009) { for (var i = 0; i <= 12; i++) pts.push([ax + ox, B.y1 + 4 + run * i / 12]); }
      else {
        var s = d > 0 ? 1 : -1, R = L / Math.tan(Math.abs(d)), cx = ax + s * R, r = Math.abs(cx - (ax + ox));
        var sin0 = (B.y1 + 4 - ay) / r; if (sin0 >= 1) return;
        var t0 = Math.asin(sin0), t1 = Math.min(t0 + run / r, Math.PI * 0.75);
        for (var j = 0; j <= 24; j++) { var t = t0 + (t1 - t0) * j / 24; pts.push([cx - s * r * Math.cos(t), ay + r * Math.sin(t)]); }
      }
      tracks.push(pts);
    });
    if (tracks.length === 2) {
      var poly = tracks[0].concat(tracks[1].slice().reverse()).map(function (p) { return p[0].toFixed(1) + ',' + p[1].toFixed(1); }).join(' ');
      out += '<polygon points="' + poly + '" fill="rgba(255,214,0,.13)"/>';
    }
    tracks.forEach(function (pts) { out += '<polyline points="' + pts.map(function (p) { return p[0].toFixed(1) + ',' + p[1].toFixed(1); }).join(' ') + '" fill="none" stroke="#ffd600" stroke-width="6" stroke-linecap="round" stroke-linejoin="round" opacity=".92"/>'; });
    var deg = Math.round(Math.abs(store.get('steerInv', false) ? -veh.steer : veh.steer));
    out += '<text x="' + (B.x0 - 46) + '" y="' + (B.y1 - w * 0.45) + '" fill="#ffd600" font-size="30" font-weight="700" text-anchor="end" font-family="UI">' + (deg < 3 ? 'Droit' : deg + '° ' + ((store.get('steerInv', false) ? -veh.steer : veh.steer) > 0 ? '▸' : '◂')) + '</text>';
    return out;
  }
  function steerText() {
    if (veh.steer == null) return '—';
    var v = store.get('steerInv', false) ? -veh.steer : veh.steer, a = Math.round(Math.abs(v));
    return a < 3 ? 'Droit' : a + '° à ' + (v > 0 ? 'droite' : 'gauche');
  }
  var steerT = 0;
  Bridge.on('steer', function (d) {
    if (!d || d.deg == null) return;
    veh.steer = d.deg;
    if (steerT) return;
    steerT = setTimeout(function () {       // au plus 10 rafraîchissements par seconde
      steerT = 0;
      if (current === 'vehicle' && (vehView === 'radar' || veh.radar)) { drawRadar($('#radar-svg')); var sv = $('#veh-steer'); if (sv) sv.textContent = steerText(); }
      if (liveS.target && LIVE.radar) drawRadar($('#live-radar'), true);
    }, 100);
  });

  /* ======================= activité en direct ======================= */
  /* panneau couché : la plus grande taille possible. v1.23.1 : le cadrage suit le contenu réel des images du pack
     (voiture, ouvrants ouverts avec leur halo, arcs des radars) : plus rien de coupé, quels que soient le pack et l'écran */
  var LIVE = { W: 560, H: 420, s: 1, radar: false };
  var TOPB = {};                                   // partie non transparente de chaque image img/top (repère 544 x 600)
  function measureTop(k) {
    if (TOPB.hasOwnProperty(k)) return;
    TOPB[k] = null;
    var im = new Image();
    im.onload = function () {
      try {
        var W = 136, H = 150, c = document.createElement('canvas'); c.width = W; c.height = H;
        var g = c.getContext('2d'); g.drawImage(im, 0, 0, W, H);
        var d = g.getImageData(0, 0, W, H).data, lim = k === 'closed' ? 60 : 24, x0 = W, y0 = H, x1 = -1, y1 = -1;
        for (var y = 0; y < H; y++) for (var x = 0; x < W; x++) {
          if (d[(y * W + x) * 4 + 3] > lim) { if (x < x0) x0 = x; if (x > x1) x1 = x; if (y < y0) y0 = y; if (y > y1) y1 = y; }
        }
        if (x1 >= 0) TOPB[k] = { x0: x0 * 544 / W, y0: y0 * 600 / H, x1: (x1 + 1) * 544 / W, y1: (y1 + 1) * 600 / H };
      } catch (e) {}
      if (liveS.target) liveSize(LIVE.radar);      // fenêtre ouverte : on recadre avec la vraie mesure
    };
    im.src = 'img/top/' + k + '.webp';
  }
  function measureAllTop() { measureTop('closed'); DOORS.forEach(function (d) { measureTop(d[0]); }); }
  if (CFG.topView !== false) setTimeout(measureAllTop, 3500);
  function liveRegion(radar) {
    var B = CFG.topBox || { x0: 219, x1: 460, y0: 49, y1: 633, w: 680, h: 750 }, f = 544 / (B.w || 680);
    var R = TOPB.closed ? { x0: TOPB.closed.x0, y0: TOPB.closed.y0, x1: TOPB.closed.x1, y1: TOPB.closed.y1 }
                        : { x0: B.x0 * f - 26, y0: B.y0 * f - 10, x1: B.x1 * f + 26, y1: B.y1 * f + 10 };
    function add(b) { if (!b) return; R.x0 = Math.min(R.x0, b.x0); R.y0 = Math.min(R.y0, b.y0); R.x1 = Math.max(R.x1, b.x1); R.y1 = Math.max(R.y1, b.y1); }
    DOORS.forEach(function (d) { if (veh.doors[d[0]]) { measureTop(d[0]); add(TOPB[d[0]]); } });
    if (radar) { var rb = radarBox(B, veh.radar); add({ x0: rb.x0 * f, y0: rb.y0 * f, x1: rb.x1 * f, y1: rb.y1 * f }); }
    R.x0 -= 6; R.y0 -= 6; R.x1 += 6; R.y1 += 6;
    return R;
  }
  function liveSize(radar) {
    var head = 56, padd = 14;
    var carW = Math.min(VW * 0.62 - 2 * padd, (VH * 0.80 - head - padd) * 1.242), carH = carW / 1.242;
    LIVE.W = Math.round(carW + 2 * padd); LIVE.H = Math.round(carH + head + padd); LIVE.radar = !!radar;
    measureTop('closed');
    // image tournée de -90° : sa hauteur (avant -> arrière) devient la largeur de la fenêtre
    var R = liveRegion(radar), rw = R.x1 - R.x0, rh = R.y1 - R.y0;
    LIVE.s = Math.min(carW / rh, carH / rw);
    var tx = 272 - (R.x0 + R.x1) / 2, ty = 300 - (R.y0 + R.y1) / 2;
    var r = $('#live-rot'); if (r) r.style.transform = 'rotate(-90deg) scale(' + LIVE.s.toFixed(4) + ') translate(' + tx.toFixed(1) + 'px,' + ty.toFixed(1) + 'px)';
  }
  var liveS = new Spring(0, { onUpdate: function (v) {
    var el = $('#live'), W0 = 340, H0 = 56, W1 = LIVE.W, H1 = LIVE.H;
    var open = Math.max(0, liveS.expand.x);
    var w = W0 + (W1 - W0) * open, h = H0 + (H1 - H0) * open;
    el.style.width = w + 'px'; el.style.height = h + 'px'; el.style.marginLeft = (-w / 2) + 'px';
    el.style.opacity = Math.max(0, Math.min(1, v));
    el.style.transform = 'translateY(' + ((1 - v) * -60) + 'px) scale(' + (0.9 + 0.1 * v) + ')';
    el.style.pointerEvents = v > 0.5 ? 'auto' : 'none';
    el.style.borderRadius = (28 + 4 * open) + 'px';
    $('.live-body').style.opacity = open;
  } });
  liveS.expand = new Spring(0, { onUpdate: function () { liveS._emit(); } });
  var liveTimer, dismissed = null;
  function openSig() { return DOORS.filter(function (d) { return veh.doors[d[0]]; }).map(function (d) { return d[0]; }).join(',') + (veh.doors.any ? ',any' : '') + (veh.radar ? ',radar' : ''); }
  function showLive(text, radar) {
    // accueil (voiture 3D) et page Véhicule montrent déjà les ouvrants en direct : pas de fenêtre en plus
    if (current === 'vehicle' || current === 'home' || store.get('live', true) === false) return;
    if (dismissed !== null) {
      // fermé à la main : on ne revient que si un nouvel ouvrant s'ouvre
      var now = openSig().split(','), was = dismissed.split(','), fresh = now.some(function (k) { return k && was.indexOf(k) < 0; });
      if (!fresh) return;
      dismissed = null;
    }
    $('#live-text').textContent = text;
    liveSize(radar); applyLayers($('#live')); drawRadar($('#live-radar'), true); $('#live-radar').classList.toggle('on', !!radar);
    alignGlass();
    liveS.to(1, { response: 0.4, damping: 0.85 });
    liveS.expand.to(1, { response: 0.45, damping: 0.82 });
    clearTimeout(liveTimer);
  }
  function hideLive(now) { clearTimeout(liveTimer); liveS.expand.to(0, { response: 0.35, damping: 1 }); liveS.to(0, { response: now ? 0.25 : 0.45, damping: 1 }); }
  tappable($('#live'), function (e) { if (e.target.closest && e.target.closest('#live-close')) return; hideLive(true); showPage('vehicle', $('#live')); });
  $('#live-close').innerHTML = svg('plus').replace('class="ic ', 'style="transform:rotate(45deg)" class="ic ');
  tappable($('#live-close'), function () { dismissed = openSig(); hideLive(true); });

  var prevOpen = 0;
  var last3D = {};
  function onVehicle(v) {
    if (!v) return;
    var nd = v.doors || veh.doors, d3 = doors3D(nd), changed = null;
    if (v.doors && !store.get('doorsSeen', false) && DOORS.some(function (x) { return v.doors[x[0]]; }) && Date.now() > (CanBridgeSimUntil || 0)) store.set('doorsSeen', true);   // v1.26
    if (VF.open) verifyRender(v.doors || {});
    Object.keys(DV).forEach(function (d) { if (!!d3[d] !== !!last3D[d]) changed = d; });
    last3D = d3;
    veh.doors = nd;
    var hk = changed ? hiName(d3) : hiKey;
    if (changed && (hk || hiKey)) {
      // portes des deux côtés : vue de face plongeante (états figés, pas d'animation de porte)
      Object.keys(DV).forEach(function (d) { cancelAnimationFrame(doorAnim[d]); doorK[d] = d3[d] ? DSTEPS : 0; });
      if (!hk) { // retour à un seul côté : la voiture se cale directement sur la vue de l'ouvrant resté ouvert
        var rest = ['fl', 'rl', 'fr', 'rr', 'trunk', 'hood'].filter(function (d) { return d3[d] && DV[d] != null; })[0];
        if (rest) { var tg = DV[rest] * STEP, df = ((tg - angle.x) % 360 + 540) % 360 - 180; angle.stop(); angle.set(angle.x + df); }
        else goHome();
      }
      applyHi(hk); drawDoors();
    } else if (changed === 'trunk' && CFG.comboAnim && (d3.fl || d3.rl || d3.fr || d3.rr)) {
      // hayon avec porte(s) ouverte(s) : il s'anime à la vue de 3/4 arrière du côté des portes, elles restent ouvertes
      Object.keys(DV).forEach(function (d) { if (d !== 'trunk') { cancelAnimationFrame(doorAnim[d]); doorK[d] = d3[d] ? DSTEPS : 0; } });
      rotateTo(d3.fl || d3.rl ? DV.fl : DV.fr, function () { playDoor('trunk', d3.trunk ? DSTEPS : 0); });
    } else if (changed) {
      if (d3[changed]) showDoor3D(changed);
      else if (viewIndex() === DV[changed]) playDoor(changed, 0, backToOpen); else { doorK[changed] = 0; backToOpen(); }
      Object.keys(DV).forEach(function (d) { if (d !== changed && !d3[d]) doorK[d] = 0; if (d !== changed && d3[d]) doorK[d] = DSTEPS; });
    }
    if (changed && CFG.comboAnim) preloadCombos(d3);
    veh.radar = v.radar || null; veh.reverse = !!v.reverse;
    renderVehicleSummary(); if (current === 'vehicle') renderVehicle();
    var o = openList(), s = statusText();
    if (veh.radar && veh.reverse) showLive('Radars · ' + minDist(veh.radar), true);
    else if (o.length || veh.doors.any) showLive(s.t, false);
    else if (!o.length && !veh.doors.any && !veh.radar) dismissed = null;
    if (!o.length && !veh.doors.any && !veh.radar && (prevOpen || true)) { if (liveS.target) { $('#live-text').textContent = 'Tout est fermé'; applyLayers($('#live')); liveTimer = setTimeout(function () { hideLive(); }, 1200); } }
    prevOpen = o.length;
  }
  Bridge.on('vehicle', onVehicle);

  /* ======================= réglages du lanceur ======================= */
  function renderSettings() {
    var st = Bridge.state(), p = st.perms || {};
    var glass = store.get('glass', 'sim');
    var rows = [
      '<div class="hdr">Installation</div>',
      row('check', 'Assistant de démarrage', store.get('setupDone', false) ? '' : 'À faire', 'setup'),
      row('home', 'Lanceur par défaut', p.home ? 'Actif' : 'Choisir', 'sys:home'),
      row('music', 'Accès aux notifications (média)', p.notif ? 'Autorisé' : 'À autoriser', 'sys:notif'),
      row('car', 'Lecture CAN (accessibilité)', p.access ? 'Autorisé' : 'À autoriser', 'access'),
      row('door', 'Fenêtre portes par-dessus les apps', p.overlay ? 'Autorisé' : 'À autoriser', 'sys:overlay'),
      row('home', 'Revenir au lanceur d\'origine / désinstaller', '', 'sys:leave'),
      Bridge.packsSupported() ? '<div class="hdr">Voiture 3D</div>' : '',
      Bridge.packsSupported() ? row('car', 'Pack voiture', esc(packLabel()), 'pack:sheet') : '',
      '<div class="hdr">Affichage</div>',
      row('brush', 'Personnaliser (voiture, fond, couleurs)', '', 'custom'),
      row('brush', 'Couleur de l\'interface', accentName(), 'custom'),
      row('grid', 'Disposition de l\'accueil', layoutName(), 'layout'),
      row('grid', 'Bibliothèque d\'apps', store.get('appsRows', 2) === 2 ? '2 lignes · grandes icônes' : '3 lignes', 'appsrows'),
      row('grid', 'Taille de l\'interface', { normal: 'Normale', grand: 'Grande', xl: 'Très grande' }[store.get('zoom', 'grand')], 'zoom'),
      row('sun', 'Thème', { auto: 'Auto (phares / nuit)', dark: 'Sombre', light: 'Clair' }[store.get('theme', 'auto')], 'theme'),
      row('home', 'Fond d\'écran', { match: 'Suit le thème', dark: 'Sombre', light: 'Clair', day: 'Jour', evening: 'Soir', sunset: 'Couchant', night: 'Nuit', auto: 'Selon l\'heure', custom: 'Perso' }[store.get('wall', 'match')] || '', 'wall'),
      row('sun', 'Effet verre', { sim: 'Rapide', real: 'Flou réel', off: 'Désactivé' }[glass], 'glass'),
      toggleRow('door', 'Activité en direct (portes, radars)', store.get('live', true), 'live'),
      toggleRow('door', 'Alerte ouvrant ouvert en roulant', store.get('moveAlert', true), 'moveAlert'),
      toggleRow('sun', 'Carte « Prêt à partir » au démarrage', store.get('ready', true), 'ready'),
      row('gauge', 'Lecture auto de l\'ordinateur de bord', { off: 'Désactivée', boot: 'Au démarrage', '15': 'Démarrage + 15 min' }[store.get('autoread', 'boot')], 'autoread'),
      toggleRow('sun', 'Mode léger (processeur lent)', store.get('lite', false), 'lite'),
      toggleRow('dash', 'Masquer la barre système de l\'autoradio', store.get('immersive', true), 'immersive'),
      row('files', 'Liste des applis pour les icônes perso', '', 'icons:list'),
      row('grid', 'Recharger les icônes perso', '', 'icons:reload'),
      '<div class="t-cap" style="padding:0 10px 6px">Dossier : ' + esc(Bridge.iconDir()) + '/&lt;paquet&gt;.png</div>',
      '<div class="hdr">Applis par défaut</div>',
      roleRow('music', 'Musique'), roleRow('radio', 'Radio'), roleRow('nav', 'Navigation'), roleRow('phone', 'Téléphone'), roleRow('eq', 'Son / égaliseur'), roleRow('can', 'Appli CAN (infos véhicule)'),
      '<div class="hdr">Données CAN du poste</div>',
      row('car', 'Analyser les applis CAN du poste', '', 'can:scan'),
      row('files', 'Copier les APK CAN (pour analyse)', '', 'can:export'),
      row('bug', 'Ordinateur de bord en direct (journal)', !Bridge.canLogs() ? 'Non autorisé' : Bridge.canLive() ? 'Actif' : 'Autorisé · en attente', 'can:logs'),
      Bridge.learnSupported() ? row('car', 'Apprendre ma voiture (portes, marche arrière, feux)', Bridge.learnHasMap() ? 'Appris' : '3 min', 'learn') : '',
      Bridge.canAppSupported() ? row('gauge', 'Apprendre l\'ordinateur de bord (appli CAN du poste)', (Bridge.canAppInfo() || {}).has ? 'Appris' : '2 min', 'canapp') : '',
      Bridge.isTs10() ? row('car', 'Sonder le CAN du poste TopWay (3 min)', 'Diagnostic', 'can:ts10') : '',
      '<div class="hdr">Tester sans la voiture</div>',
      row('door', 'Simuler : porte conducteur', '', 'sim:fl'),
      row('door', 'Simuler : porte arrière gauche', '', 'sim:rl'),
      row('trunk', 'Simuler : coffre', '', 'sim:trunk'),
      row('radar', 'Simuler : marche arrière + radars', '', 'sim:radar'),
      toggleRow('radar', 'Inverser le sens du volant (lignes de recul)', store.get('steerInv', false), 'steerInv'),
      row('check', 'Tout fermer', '', 'sim:none'),
      row('door', 'Tester l\'alerte « porte ouverte en roulant »', '', 'test:moving'),
      row('sun', 'Afficher la carte « Prêt à partir »', '', 'test:ready'),
      '<div class="hdr">Diagnostic CAN</div>',
      row('qr', 'Rapport de compatibilité (QR code)', 'Pour le téléphone', 'report:qr'),
      Bridge.learnSupported() ? row('qr', 'Envoyer le journal au téléphone (QR codes)', 'Sans clé USB', 'qr:journal') : '',
      row('bug', 'Exporter le journal (fichier ibiza_diag.txt)', '', 'diag:export'),
      row('bug', 'Vider le journal', '', 'diag:clear'),
      '<div class="diag" id="diag"></div>',
      '<div class="hdr">À propos</div>',
      row('settings', 'Paramètres Android', '', 'sys:android')
    ];
    $('#settings-list').innerHTML = rows.join('');
    $('#diag').textContent = Bridge.diag() || '';
    $$('#settings-list [data-act]').forEach(function (el) { tappable(el, function () { act(el.getAttribute('data-act'), el); }); });
  }
  function roleRow(th, t) {
    var a = roleApp(th), auto = a ? null : (th === 'can' ? null : findTheme(th));
    return row(th === 'can' ? 'car' : th === 'eq' ? 'eq' : th, t, a ? esc(a.label) : auto ? 'Auto : ' + esc(auto.label) : 'Choisir', 'role:' + th);
  }
  function roleSheet(th, label) {
    var list = apps.filter(function (a) { return !a.virtual; });
    var r = store.get('roles', {}) || {};
    var h = '<div class="t-headline">' + label + '</div><div class="t-sub">Touche l\'appli à utiliser.</div><div class="pick-list">' +
      '<div class="pick press" data-k=""><div class="aicon aicon-raw noimg" style="width:44px;height:44px"><b>A</b></div><span>Automatique</span></div>' +
      list.map(function (a) { return '<div class="pick press' + (r[th] === key(a) ? ' on' : '') + '" data-k="' + esc(key(a)) + '">' + Icons.appIcon(a, 44) + '<span>' + esc(a.label) + '</span></div>'; }).join('') + '</div>';
    openSheet(h, function (s) {
      $$('.pick', s).forEach(function (el) { tappable(el, function () {
        var rr = store.get('roles', {}) || {}, k = el.getAttribute('data-k');
        if (k) rr[th] = k; else delete rr[th];
        store.set('roles', rr); closeSheet(); renderDock(); renderQuick(); renderSettings(); alignGlass();
      }); });
    });
  }
  function row(ic, t, val, act) { return '<div class="row btn" data-act="' + act + '">' + svg(ic) + '<div class="grow">' + t + '</div><div class="val">' + val + '</div>' + (val !== '' || true ? '' : '') + '</div>'; }
  /* ---------- v1.24 : rapport de compatibilité en QR code. Le téléphone le scanne et ouvre la page de l'espace
     Car Launcher, qui décode le rapport : rien n'est envoyé depuis la voiture, pas besoin d'internet sur l'autoradio ---------- */
  function loadQr(cb) {
    if (window.qrcode) { cb(); return; }
    var sc = document.createElement('script'); sc.src = 'js/qrcode.js'; sc.onload = cb;
    sc.onerror = function () { toast('Générateur de QR code absent (js/qrcode.js)'); };
    document.head.appendChild(sc);
  }
  function qrSvg(text) {
    var q = qrcode(0, 'L'); q.addData(text, 'Byte'); q.make();
    var n = q.getModuleCount(), m = 4, d = '';
    for (var y = 0; y < n; y++) for (var x = 0; x < n; x++) if (q.isDark(y, x)) d += 'M' + (x + m) + ' ' + (y + m) + 'h1v1h-1z';
    return '<svg class="qr-svg" viewBox="0 0 ' + (n + 2 * m) + ' ' + (n + 2 * m) + '" shape-rendering="crispEdges" role="img" aria-label="QR code du rapport">' +
      '<rect width="100%" height="100%" fill="#fff"/><path fill="#000" d="' + d + '"/></svg>';
  }
  function hasGL() { try { var c = document.createElement('canvas'); return !!(c.getContext('webgl') || c.getContext('experimental-webgl')); } catch (e) { return false; } }
  function qrFact(k, v) { return '<div class="qr-fact"><span>' + k + '</span><b>' + esc(v == null || v === '' ? '—' : String(v)) + '</b></div>'; }
  function reportSheet() {
    var js = { g: hasGL() ? 1 : 0, w: (navigator.userAgent.match(/Chrome\/(\d+)/) || [])[1] || '', v: (CFG.car && (CFG.car.model || CFG.car.name)) || '' };
    var rep = Bridge.report(JSON.stringify(js));
    if (!rep || !rep.z) { toast('Rapport impossible' + (rep && rep.error ? ' : ' + rep.error : '')); return; }
    var url = (CFG.reportUrl || 'https://reality-design.fr/carlauncher/') + '?r=' + rep.z + '#rapport';
    var r = rep.r || {}, d = String(r.d || '').split('|'), n = r.n || {};
    var can = (r.c || []).map(function (x) { return x[0]; }).join(', ');
    loadQr(function () {
      var q; try { q = qrSvg(url); } catch (e) { toast('Rapport trop long pour un QR code : exporte le journal à la place'); return; }
      openSheet('<div class="qr-wrap"><div class="qr-box">' + q + '</div><div class="qr-text">' +
        '<div class="t-headline">Rapport de compatibilité</div>' +
        '<div class="t-sub">Scanne ce code avec l\'appareil photo de ton téléphone : la page Car Launcher s\'ouvre avec les infos de ton autoradio, et tu les envoies en un geste. ' +
        'Rien n\'est envoyé depuis la voiture, pas besoin d\'internet ici.</div><div class="qr-facts">' +
        qrFact('Autoradio', [d[0], d[1]].filter(Boolean).join(' ')) + qrFact('Android', String(r.a || '').split('/')[0]) + qrFact('Puce', r.s) +
        qrFact('Écran', r.e) + qrFact('Appli CAN', can || 'aucune trouvée') +
        qrFact('Données CAN', n.fr ? n.fr + ' trames' + (n.live ? ', en direct' : '') : 'aucune pour l\'instant') + '</div>' +
        '<div class="t-cap">Moteur allumé, ouvre une porte avant de scanner : le rapport dira si elle est bien lue.</div>' +
        '<div class="sheet-actions"><button class="sbtn wide press" data-x="1">Fermer</button></div></div></div>', function (sh) {
          sh.classList.add('sheet-qr'); $('[data-x]', sh).onclick = closeSheet;
        });
    });
  }

  /* ---------- v1.23 : packs voiture (installés sans recompiler, depuis une clé USB) ---------- */
  function packLabel() {
    if (window.IBZ_PACK && IBZ_PACK.name) return IBZ_PACK.name;
    return CFG.carPack === false ? 'Aucun' : 'Voiture de l\'appli';
  }
  function fmtMo(n) { return n > 1048576 ? (n / 1048576).toFixed(1).replace('.', ',') + ' Mo' : Math.max(1, Math.round(n / 1024)) + ' Ko'; }
  function packSheet() {
    var info = Bridge.packList() || {}, list = info.packs || [];
    var thumb = function (p) {
      if (!p && info.builtin) return '<div class="aicon aicon-raw" style="width:64px;height:40px;border-radius:9px;overflow:hidden;background:#000"><img src="/builtin/car.webp" alt="" style="width:100%;height:100%;object-fit:contain"></div>';
      return p && p.preview ? '<div class="aicon aicon-raw" style="width:64px;height:40px;border-radius:9px;overflow:hidden;background:#000"><img src="/packs/' + esc(p.id) + '/preview.jpg" alt="" style="width:100%;height:100%;object-fit:cover"></div>'
        : '<div class="aicon aicon-raw noimg" style="width:64px;height:40px;border-radius:9px">' + svg('car') + '</div>';
    };
    var rows = '<div class="pick press' + (!info.active ? ' on' : '') + '" data-pk="">' + thumb(null) + '<span>' + (info.builtin ? 'Voiture de l\'appli' : 'Aucune voiture 3D') + '<small>' + (info.builtin ? 'intégrée au lanceur' : 'message à la place de la voiture') + '</small></span></div>' +
      list.map(function (p) { return '<div class="pick press' + (p.active ? ' on' : '') + '" data-pk="' + esc(p.id) + '">' + thumb(p) + '<span>' + esc(p.name) + '<small>' + (p.version ? 'version ' + esc(p.version) + ' · ' : '') + fmtMo(p.size) + '</small></span></div>'; }).join('');
    openSheet('<div class="t-headline">Pack voiture</div>' +
      '<div class="t-sub">Télécharge le pack dans ton espace client, copie le .zip sur une clé USB, branche-la, puis touche « Clé USB ».' +
      (list.length ? ' Appui long sur un pack pour le supprimer.' : '') + '</div>' +
      '<div class="pick-list packs">' + rows + '</div>' +
      '<div class="sheet-actions"><button class="sbtn press" data-imp="usb">Clé USB</button>' +
      '<button class="sbtn press" data-imp="dir">Dossier du lanceur' + (info.waiting ? ' (' + info.waiting + ')' : '') + '</button></div>' +
      '<div class="t-cap" style="margin-top:8px">Sans sélecteur de fichiers : copie le .zip dans ' + esc(String(info.folder || '').replace(/^.*\/Android\//, 'Android/')) + '</div>', function (s) {
        $$('[data-pk]', s).forEach(function (el) {
          var id = el.getAttribute('data-pk');
          tappable(el, function () {
            if (el.classList.contains('on')) { closeSheet(); return; }
            closeSheet(); toast('Changement de voiture…'); Bridge.packUse(id);
          }, id ? function () { packDeleteSheet(id, $('span', el).textContent); } : null);
        });
        $$('[data-imp]', s).forEach(function (b) { b.onclick = function () {
          closeSheet();
          if (b.getAttribute('data-imp') === 'usb') { toast('Choisis le fichier .zip du pack'); Bridge.packImport(); }
          else { toast('Import en cours…'); Bridge.packImportFolder(); }
        }; });
      });
  }
  function packDeleteSheet(id, name) {
    openSheet('<div class="t-headline">Supprimer ce pack ?</div><div class="t-sub">' + esc(name) + '<br><br>Tu pourras le réimporter depuis ton espace client.</div>' +
      '<div class="sheet-actions"><button class="sbtn danger press" data-ok="1">Supprimer</button><button class="sbtn press" data-no="1">Annuler</button></div>', function (s) {
        $('[data-no]', s).onclick = closeSheet;
        $('[data-ok]', s).onclick = function () { closeSheet(); if (Bridge.packDelete(id)) toast('Pack supprimé'); else toast('Suppression impossible'); };
      });
  }
  Bridge.on('pack', function (r) {
    if (!r || r.cancel) return;
    if (r.ok) toast('Pack « ' + (r.name || r.id || '') + ' » installé : la voiture se recharge…');
    else toast('Import impossible : ' + (r.error || 'erreur inconnue'));
  });

  /* ---------- v1.24.3 : page « Personnaliser » (bouton de l'accueil, à côté de « Véhicule ») ----------
     Voiture 3D (packs installés, dont le modèle par défaut fourni), aide « ma propre voiture », fond d'écran, couleur de
     l'interface, thème et icônes perso, réunis sur une seule page. Les réglages sont les mêmes que dans « Réglages ». */
  var ACCENTS = [['', 'Par défaut'], ['#0091ff', 'Bleu'], ['#00d2e0', 'Turquoise'], ['#30d158', 'Vert'], ['#b7cc3c', 'Lime'],
    ['#ffd600', 'Jaune'], ['#ff9230', 'Orange'], ['#ff4245', 'Rouge'], ['#ff375f', 'Rose'], ['#6d7cff', 'Violet']];
  function applyAccent() {
    var a = store.get('accent', '');
    if (!/^#[0-9a-f]{6}$/i.test(a || '')) a = '';
    root.classList.toggle('accent', !!a);
    if (a) root.style.setProperty('--accent', a); else root.style.removeProperty('--accent');
  }
  function accentName() { var a = store.get('accent', ''); for (var i = 0; i < ACCENTS.length; i++) if (ACCENTS[i][0] === a) return ACCENTS[i][1]; return 'Par défaut'; }
  /* adresse de la page de commande d'un pack sur mesure : "packUrl" dans js/config.js, sinon l'espace Car Launcher */
  function packUrl() { return CFG.packUrl || (String(CFG.reportUrl || 'https://reality-design.fr/carlauncher/').replace(/[?#].*$/, '') + '#pack-sur-mesure'); }
  function siteName() { return String(CFG.reportUrl || 'https://reality-design.fr/carlauncher/').replace(/^https?:\/\//, '').replace(/\/$/, ''); }

  function renderCustom() {
    var info = Bridge.packList() || {}, list = info.packs || [];
    var tile = function (id, name, sub, img, on) {
      return '<div class="ptile press' + (on ? ' on' : '') + '" data-pk="' + esc(id) + '">' +
        '<div class="ptile-img">' + (img ? '<img src="' + img + '" alt="">' : svg('car')) + (on ? '<i class="ptile-ok">' + svg('check') + '</i>' : '') + '</div>' +
        '<div class="ptile-t"><b>' + esc(name) + '</b><small>' + sub + '</small></div></div>';
    };
    var h = tile('', info.builtin ? 'Voiture de l\'appli' : 'Sans voiture', info.builtin ? 'intégrée au lanceur' : 'pas de 3D à l\'accueil', info.builtin ? '/builtin/car.webp' : '', !info.active);
    list.slice().sort(function (a, b) { return (b.bundled ? 1 : 0) - (a.bundled ? 1 : 0); }).forEach(function (p) {
      h += tile(p.id, p.bundled ? 'Modèle par défaut' : p.name, (p.bundled ? 'fourni avec le lanceur · ' : p.version ? 'version ' + esc(p.version) + ' · ' : '') + fmtMo(p.size), p.preview ? '/packs/' + esc(p.id) + '/preview.jpg' : '', p.active);
    });
    var box = $('#cust-packs');
    box.innerHTML = h;
    $$('[data-pk]', box).forEach(function (el) {
      var id = el.getAttribute('data-pk');
      tappable(el, function () {
        if (el.classList.contains('on')) return;
        $$('.ptile', box).forEach(function (o) { o.classList.toggle('on', o === el); });
        toast('Changement de voiture…'); Bridge.packUse(id);
      }, id ? function () { packDeleteSheet(id, $('b', el).textContent); } : null);
    });
    $('#cust-help').innerHTML = svg('info') + '<span>Comment utiliser ma propre voiture ?</span>';
    $('#cust-import').innerHTML = svg('plus') + 'Importer' + (info.waiting ? ' (' + info.waiting + ')' : '');

    /* colonne de droite : apparence */
    var sel = store.get('wall', 'match');
    var walls = WALLS.map(function (w) { var th = w[0] === 'match' ? 'dark' : w[0]; return '<div class="wthumb press' + (sel === w[0] ? ' on' : '') + '" data-w="' + w[0] + '" style="background-image:' + (w[0] === 'match' ? 'url(img/walls/light_t.jpg),url(img/walls/dark_t.jpg)' : 'url(img/walls/' + th + '_t.jpg)') + ';background-size:50% 100%,50% 100%,cover;background-position:0 0,100% 0,0 0;background-repeat:no-repeat"><span>' + w[1] + '</span></div>'; }).join('') +
      '<div class="wthumb auto press' + (sel === 'auto' ? ' on' : '') + '" data-w="auto"><span>Selon l\'heure</span></div>' +
      '<div class="wthumb usb press' + (sel === 'custom' ? ' on' : '') + '" data-w="usb">' + svg('files') + '<span>Clé USB…</span></div>';
    var acc = store.get('accent', '');
    var sw = ACCENTS.map(function (a) { return '<button class="swatch press' + (acc === a[0] ? ' on' : '') + (a[0] ? '' : ' sw-def') + '" data-ac="' + a[0] + '" aria-label="' + a[1] + '"' + (a[0] ? ' style="background:' + a[0] + '"' : '') + '></button>'; }).join('');
    var th = store.get('theme', 'auto');
    var seg = [['auto', 'Auto'], ['dark', 'Sombre'], ['light', 'Clair']].map(function (t) { return '<button class="seg-b' + (th === t[0] ? ' is-on' : '') + '" data-th="' + t[0] + '">' + t[1] + '</button>'; }).join('');
    $('#cust-look').innerHTML =
      '<div class="hdr">Fond d\'écran</div><div class="walls">' + walls + '</div>' +
      '<div class="hdr">Couleur de l\'interface <span class="hdr-val">' + accentName() + '</span></div><div class="swatches">' + sw + '</div>' +
      '<div class="hdr">Thème</div><div class="seg cust-seg">' + seg + '</div>' +
      '<div class="t-cap cust-note">Auto : clair le jour, sombre quand les phares sont allumés ou la nuit.</div>' +
      '<div class="hdr">Icônes perso</div>' +
      '<div class="t-sub cust-note">Tu peux remplacer l\'icône de chaque appli par ta propre image (PNG carré). ' +
      '« Liste des applis » te donne le nom de fichier de chaque icône et le dossier où les déposer.</div>' +
      '<div class="cust-btns"><button class="chip-btn press" data-ic="list">' + svg('files') + 'Liste des applis</button>' +
      '<button class="chip-btn press" data-ic="reload">' + svg('grid') + 'Recharger</button></div>';
    var look = $('#cust-look');
    $$('[data-w]', look).forEach(function (el) { tappable(el, function () {
      var w = el.getAttribute('data-w');
      if (w === 'usb') { $('#wall-file').click(); return; }
      store.set('wall', w); applyWall();
      $$('[data-w]', look).forEach(function (o) { o.classList.toggle('on', o === el); });
    }); });
    $$('[data-ac]', look).forEach(function (el) { tappable(el, function () {
      store.set('accent', el.getAttribute('data-ac')); applyAccent();
      $$('[data-ac]', look).forEach(function (o) { o.classList.toggle('on', o === el); });
      $('.hdr-val', look).textContent = accentName();
    }); });
    $$('[data-th]', look).forEach(function (el) { tappable(el, function () {
      store.set('theme', el.getAttribute('data-th')); applyTheme();
      $$('[data-th]', look).forEach(function (o) { o.classList.toggle('is-on', o === el); });
    }); });
    $$('[data-ic]', look).forEach(function (el) { tappable(el, function () { act('icons:' + el.getAttribute('data-ic'), el); }); });
    alignGlass();
  }
  function ownCarSheet() {
    var step = function (n, title, body) { return '<div class="hstep"><div class="hstep-h"><i class="hnum">' + n + '</i><b>' + title + '</b></div>' + body + '</div>'; };
    openSheet('<div class="t-headline">Ajouter ma propre voiture</div>' +
      '<div class="t-sub">Ta voiture en 3D arrive dans un <b>pack voiture</b> (un fichier .zip). Il se fait en 3 étapes.</div>' +
      '<div class="hsteps">' +
      step(1, 'Le modèle 3D', '<p>Trouve le modèle 3D de ta voiture : même génération, même nombre de portes.</p>' +
        '<ul class="hlist"><li>Fichier .fbx, .obj, .glb ou .blend</li><li>Portes, coffre et capot séparés</li><li>Licence qui autorise l\'usage</li><li>Note ta couleur et sa finition</li></ul>') +
      step(2, 'Le pack', '<div class="hchoice"><b>Je le fais moi-même</b><span>Donne le dossier source du lanceur (outils inclus) et ton modèle à une IA comme Claude.</span></div>' +
        '<div class="hchoice hchoice-qr"><div><b>Sur mesure, sur devis</b><span>Scanne pour commander.</span></div><div class="help-code" id="help-code"></div></div>') +
      step(3, 'L\'installer', '<p>Copie le .zip sur une clé USB, branche-la sur l\'autoradio, puis touche « Importer ».</p>' +
        '<button class="sbtn press help-imp" data-imp="1">' + svg('plus') + 'Importer</button>') +
      '</div>' +
      '<div class="hfoot"><span>En attendant, essaie le modèle par défaut.</span><button class="sbtn press" data-x="1">Fermer</button></div>', function (s) {
        s.classList.add('sheet-help');
        tappable($('[data-x]', s), closeSheet);
        tappable($('[data-imp]', s), function () { closeSheet(); setTimeout(importSheet, 260); });
        // v1.24.3 : l'autoradio est souvent hors ligne -> le lien de commande passe par le téléphone (QR code)
        loadQr(function () { var c = $('#help-code', s); if (c) try { c.innerHTML = qrSvg(packUrl()); } catch (e) { c.textContent = siteName(); } });
      });
  }
  function importSheet() {
    var info = Bridge.packList() || {};
    openSheet('<div class="t-headline">Importer un pack voiture</div>' +
      '<div class="t-sub">Copie le .zip du pack sur une clé USB, branche-la sur l\'autoradio, puis touche « Clé USB ».</div>' +
      '<div class="sheet-actions"><button class="sbtn press" data-imp="usb">Clé USB</button>' +
      '<button class="sbtn press" data-imp="dir">Dossier du lanceur' + (info.waiting ? ' (' + info.waiting + ')' : '') + '</button></div>' +
      '<div class="t-cap" style="margin-top:8px">Sans sélecteur de fichiers : copie le .zip dans ' + esc(String(info.folder || '').replace(/^.*\/Android\//, 'Android/')) + ', puis touche « Dossier du lanceur ».</div>', function (s) {
        $$('[data-imp]', s).forEach(function (b) { b.onclick = function () {
          closeSheet();
          if (b.getAttribute('data-imp') === 'usb') { toast('Choisis le fichier .zip du pack'); Bridge.packImport(); }
          else { toast('Import en cours…'); Bridge.packImportFolder(); }
        }; });
      });
  }
  tappable($('#cust-import'), importSheet);
  tappable($('#cust-help'), ownCarSheet);
  tappable($('#btn-custom'), function () { showPage('custom', $('#btn-custom')); });
  $('#btn-custom').innerHTML = svg('brush') + '<span>Personnaliser</span>';
  applyAccent();

  /* v1.24.3 : autorisation « Lecture CAN ». Certains postes (ex. Autochips 825X_Pro, Android 9) n'ont plus de page
     Accessibilité : on explique la solution par adb (une seule fois) au lieu d'ouvrir l'accueil des Paramètres. */
  /* v1.27 : Android 13 et plus. Une appli installée depuis un fichier APK (clé USB) n'a pas le droit d'activer
     l'accessibilité : Android affiche « Paramètre restreint » et l'interrupteur reste grisé. Le déblocage est caché dans
     la fiche de l'appli (menu ⋮), et n'y apparaît qu'APRÈS un premier essai refusé : on guide le client pas à pas. */
  var AH = { open: false };
  function accessHintSheet() {
    openSheet('<div class="t-headline">Autoriser la lecture CAN</div>' +
      '<div class="t-sub">Sur Android 13 et plus, cette autorisation est bloquée pour les applis installées depuis une clé USB. Trois gestes pour la débloquer :</div>' +
      '<div class="lrn-list">' + [
        'Ouvre <b>Accessibilité</b> et touche « Car Launcher ». Si Android répond « Paramètre restreint », touche OK et reviens ici.',
        'Ouvre la <b>fiche de l\'appli</b>, touche les trois points <b>⋮</b> en haut à droite, puis « Autoriser les paramètres restreints ».',
        'Rouvre <b>Accessibilité</b> : « Car Launcher » peut maintenant être activé.'
      ].map(function (t, i) { return '<div class="cm-row"><div class="cm-num">' + (i + 1) + '</div><div class="cm-l cm-wrap"><span>' + t + '</span></div></div>'; }).join('') + '</div>' +
      '<div class="t-cap">Pas de message « Paramètre restreint » ? Alors active simplement « Car Launcher » à l\'étape 1. Le même blocage peut toucher l\'autorisation Musique (notifications) : même solution.</div>' +
      '<div class="sheet-actions lrn-act"><button class="sbtn press lrn-go" data-a="acc">Accessibilité</button><button class="sbtn press" data-a="app">Fiche de l\'appli</button><button class="sbtn press" data-a="x">Fermer</button></div>', function (s) {
        s.classList.add('sheet-learn'); AH.open = true;
        $$('[data-a]', s).forEach(function (b) { b.onclick = function () {
          var k = b.getAttribute('data-a');
          if (k === 'acc') askAccess(true);
          else if (k === 'app') Bridge.openSystem('appinfo');
          else { AH.open = false; closeSheet(); if (!setupBack() && current === 'settings') setTimeout(renderSettings, 300); }
        }; });
      });
  }
  Bridge.on('resume', function () {          // retour des Paramètres : si c'est autorisé, la fiche d'aide se ferme toute seule
    if (!AH.open) return;
    if (!$('#sheet-scrim').classList.contains('on') || !$('#sheet [data-a="acc"]')) { AH.open = false; return; }
    if (((Bridge.state() || {}).perms || {}).access) { AH.open = false; closeSheet(); toast('Lecture CAN autorisée'); if (!setupBack() && current === 'settings') setTimeout(renderSettings, 300); }
  });
  function askAccess(skipHint) {
    if (skipHint !== true && Bridge.accessHint()) { accessHintSheet(); return; }
    var r = Bridge.openAccess();
    if (r === 'on') { toast('Lecture CAN déjà autorisée'); return; }
    if (r === 'enabled') { toast('Lecture CAN autorisée'); setTimeout(function () { if (current === 'settings') renderSettings(); }, 800); setupBack(); return; }
    if (r !== 'missing') return;
    openSheet('<div class="t-headline">Autoriser la lecture CAN</div>' +
      '<div class="t-sub">Le fabricant de ce poste a retiré la page <b>Accessibilité</b> des Paramètres Android. ' +
      'Sans cette autorisation, le lanceur ne peut pas voir les portes ni les infos de la voiture.</div>' +
      '<div class="hchoice acc-opt"><b>Avec un ordinateur, une seule fois</b>' +
      '<span>Active le débogage USB du poste, branche-le à l\'ordinateur, puis tape :</span>' +
      '<code class="acc-cmd">' + esc(Bridge.accessCommand()) + '</code>' +
      '<span>Touche ensuite « Réessayer » : le lanceur s\'autorise tout seul, même après un redémarrage. Sans root, et ça s\'annule en désinstallant le lanceur.</span></div>' +
      '<div class="hchoice acc-opt"><b>Sans ordinateur</b><span>Dans les Paramètres Android, cherche « Accessibilité » avec la loupe (si elle existe), puis active « Car Launcher ».</span></div>' +
      '<div class="sheet-actions"><button class="sbtn press" data-a="retry">Réessayer</button><button class="sbtn press" data-a="set">Paramètres</button><button class="sbtn press" data-a="x">Fermer</button></div>', function (s) {
        s.classList.add('sheet-acc');
        $$('[data-a]', s).forEach(function (b) { b.onclick = function () {
          var k = b.getAttribute('data-a'); closeSheet();
          if (k === 'retry') setTimeout(askAccess, 300);
          else if (k === 'set') Bridge.openSystem('settings');
          else setupBack();
        }; });
      });
  }
  /* ---------- v1.26 : assistant de démarrage (première installation) ----------
     Écran d'accueil, musique, lecture CAN, fenêtre par-dessus les apps, puis « Ma voiture » : Apprendre (3 min) ou
     Vérifier (postes qui donnent déjà les portes). Chaque étape peut être passée ; rouvrable dans Réglages du lanceur. */
  var CanBridgeSimUntil = 0;
  var SU = { open: false };
  function setupSheet() {
    var st = Bridge.state() || {}, p = st.perms || {}, di = Bridge.doorInfo ? Bridge.doorInfo() : {};
    var carOk = !!di.learned || store.get('doorsOk', false);
    // v1.27 : ordinateur de bord. CarCan (postes hcn) est lu sans rien faire ; ailleurs, le client montre son appli CAN une fois
    var ci = Bridge.canAppSupported() ? (Bridge.canAppInfo() || {}) : null, tripOk = !!ci && (ci.has || ci.hcn || Bridge.canLive());
    var steps = [
      ['home', 'Écran d\'accueil', 'Car Launcher s\'ouvre au démarrage et avec la touche Accueil.', !!p.home, 'sys:home', 'Choisir'],
      ['music', 'Musique', 'Titre, pochette et commandes de ce qui joue (Bluetooth, Spotify…).', !!p.notif, 'sys:notif', 'Autoriser'],
      ['car', 'Lecture CAN', 'Portes, radars et ordinateur de bord lus sur l\'écran de l\'appli CAN.', !!p.access, 'access', 'Autoriser'],
      ['door', 'Fenêtre par-dessus les apps', 'Portes ouvertes et radars visibles même dans Waze ou CarPlay.', !!p.overlay, 'sys:overlay', 'Autoriser'],
      ['radar', 'Ma voiture', di.known ? 'Ouvre une portière pour vérifier qu\'elle s\'affiche (' + esc(di.src || 'détectée') + ').' : 'Le lanceur apprend comment ton autoradio annonce les portes, en 3 minutes, voiture à l\'arrêt.', carOk, di.known ? 'verify' : 'learn', di.known ? 'Vérifier' : 'Apprendre']
    ];
    if (ci) steps.push(['gauge', 'Ordinateur de bord', ci.has ? 'Carburant, batterie, kilométrage lus dans « ' + esc(ci.label) + ' ».' : ci.hcn ? 'Carburant, batterie, kilométrage lus dans CarCan, automatiquement.'
      : 'Montre une fois l\'appli « voiture » de ton autoradio : carburant, batterie et kilométrage seront lus tout seuls. 2 minutes, contact mis.', tripOk, 'canapp', 'Apprendre']);
    var done = steps.filter(function (s) { return s[3]; }).length;
    openSheet('<div class="su-head"><div><div class="t-headline">Bienvenue dans Car Launcher</div>' +
      '<div class="t-sub">' + (done === steps.length ? 'Tout est prêt.' : 'Quelques réglages pour que tout marche dans ta voiture. Tu peux passer une étape et y revenir plus tard dans les réglages.') + '</div></div>' +
      '<div class="su-count">' + done + '/' + steps.length + '</div></div>' +
      '<div class="su-list">' + steps.map(function (s, i) {
        return '<div class="su-row' + (s[3] ? ' ok' : '') + '"><i class="su-n">' + (s[3] ? svg('check') : (i + 1)) + '</i><div class="grow"><b>' + s[1] + '</b><span>' + s[2] + '</span></div>' +
          (s[3] ? '<em>' + (s[0] === 'radar' ? 'Vérifié' : 'Fait') + '</em>' : '<button class="chip-btn press" data-su="' + s[4] + '">' + s[5] + '</button>') + '</div>';
      }).join('') + '</div>' +
      '<div class="sheet-actions"><button class="sbtn press' + (done === steps.length ? ' lrn-go' : '') + '" data-su="done">' + (done === steps.length ? 'C\'est parti' : 'Terminer') + '</button>' +
      '<button class="sbtn press" data-su="later">Plus tard</button></div>', function (s) {
        s.classList.add('sheet-setup'); SU.open = true;
        $$('[data-su]', s).forEach(function (b) { b.onclick = function () {
          var a = b.getAttribute('data-su');
          if (a === 'done' || a === 'later') { SU.open = false; store.set('setupDone', true); closeSheet(); if (a === 'later') toast('Assistant : Réglages du lanceur › Installation'); return; }
          if (a === 'learn') { SU.back = true; SU.open = false; learnSheet(); return; }
          if (a === 'verify') { SU.back = true; SU.open = false; verifySheet(); return; }
          if (a === 'canapp') { SU.back = true; SU.open = false; canAppSheet(false); return; }
          if (a === 'access' && Bridge.accessHint()) { SU.back = true; SU.open = false; accessHintSheet(); return; }
          if (a === 'access') { var r = Bridge.openAccess(); if (r === 'enabled' || r === 'on') { toast('Lecture CAN autorisée'); setupSheet(); } else if (r === 'missing') { SU.back = true; SU.open = false; askAccess(); } return; }
          act(a, b);
        }; });
      });
  }
  Bridge.on('resume', function () { if (SU.open) setTimeout(setupSheet, 350); });    // retour des Paramètres Android : coches à jour
  function setupBack() { if (SU.back && !store.get('setupDone', false)) { SU.back = false; setTimeout(setupSheet, 400); return true; } SU.back = false; return false; }

  /* « Vérifier mes portes » : les ouvrants s'allument en direct quand on les ouvre */
  var VF = { open: false, seen: {} };
  function verifySheet() {
    VF.open = true; VF.seen = {};
    openSheet('<div class="t-headline">Vérifier mes portes</div>' +
      '<div class="t-sub">Ouvre une portière ou le coffre : il doit s\'allumer ici dans la seconde.</div>' +
      '<div class="vf-grid" id="vf-grid"></div>' +
      '<div class="sheet-actions"><button class="sbtn press lrn-go" data-vf="ok">Ça marche</button><button class="sbtn press" data-vf="ko">Ça ne marche pas</button></div>', function (s) {
        s.classList.add('sheet-learn');
        $$('[data-vf]', s).forEach(function (b) { b.onclick = function () {
          VF.open = false;
          if (b.getAttribute('data-vf') === 'ok') { store.set('doorsOk', true); closeSheet(); toast('Parfait'); if (!setupBack() && current === 'vehicle') renderVehicle(); }
          else learnSheet();
        }; });
        verifyRender(veh.doors || {});
      });
  }
  function verifyRender(d) {
    var g = $('#vf-grid'); if (!g) { VF.open = false; return; }
    DOORS.forEach(function (x) { if (d[x[0]]) VF.seen[x[0]] = true; });
    g.innerHTML = DOORS.map(function (x) {
      var on = !!d[x[0]], was = VF.seen[x[0]];
      return '<div class="vf' + (on ? ' on' : was ? ' was' : '') + '">' + svg(x[2]) + '<b>' + x[1].replace('Porte ', '') + '</b><span>' + (on ? 'Ouvert' : was ? 'Vu ✓' : 'Fermé') + '</span></div>';
    }).join('');
  }

  /* ---------- v1.25 : « Apprendre ma voiture » ----------
     Sondage guidé sans adb : le client suit les étapes, le lanceur trouve tout seul quel signal du poste change pour
     chaque ouvrant, la marche arrière et les feux, puis s'en sert en direct (Learn.java). */
  var LB = window.LB = { busy: false, timer: 0, i: 0, on: true, doors5: true, t0: 0, done: false, adv: 0 };
  var LSTEPS = [
    { ev: 'base', t: 'Prépare la voiture', d: 'Contact mis ou moteur allumé. Portes, coffre et capot fermés, feux éteints, point mort. Ne touche à rien.', auto: 8000 },
    { ev: 'fl', t: 'Ouvre la porte avant gauche', off: 'Referme la porte avant gauche' },
    { ev: 'fr', t: 'Ouvre la porte avant droite', off: 'Referme la porte avant droite' },
    { ev: 'rl', t: 'Ouvre la porte arrière gauche', off: 'Referme la porte arrière gauche', rear: true },
    { ev: 'rr', t: 'Ouvre la porte arrière droite', off: 'Referme la porte arrière droite', rear: true },
    { ev: 'trunk', t: 'Ouvre le coffre', off: 'Referme le coffre', d: 'Tu peux ouvrir ta portière pour sortir : les portes déjà apprises sont ignorées.' },
    { ev: 'hood', t: 'Ouvre le capot', off: 'Referme le capot', d: 'Ta portière peut rester ouverte, elle est ignorée. Le capot n\'est pas remonté sur toutes les voitures : « Passer » si rien ne se passe.' },
    { ev: 'reverse', t: 'Pied sur le frein, passe la marche arrière', off: 'Repasse au point mort', d: 'Si la caméra de recul s\'affiche, attends 3 secondes puis repasse au point mort : l\'écran revient tout seul.' },
    { ev: 'lights', t: 'Allume les feux de croisement', off: 'Éteins les feux', d: 'Les feux de position suffisent sur certaines voitures.' }
  ];
  var LNAMES = { fl: 'Porte avant gauche', fr: 'Porte avant droite', rl: 'Porte arrière gauche', rr: 'Porte arrière droite', trunk: 'Coffre', hood: 'Capot', reverse: 'Marche arrière', lights: 'Feux' };
  function learnSteps() { return LSTEPS.filter(function (s) { return LB.doors5 || !s.rear; }); }
  function learnStop() { clearInterval(LB.timer); LB.timer = 0; LB.busy = false; }

  function learnSheet() {
    var has = Bridge.learnHasMap();
    openSheet('<div class="t-headline">Apprendre ma voiture</div>' +
      '<div class="t-sub">En 3 minutes, le lanceur trouve tout seul comment ton autoradio annonce les portes, la marche arrière et les feux. ' +
      'Tu suis les étapes à l\'écran ; rien n\'est modifié dans le poste ni dans la voiture, pas besoin d\'ordinateur.</div>' +
      '<div class="lrn-q"><span>Ta voiture a</span><div class="seg"><button class="seg-b' + (LB.doors5 ? ' is-on' : '') + '" data-d="5">5 portes</button><button class="seg-b' + (LB.doors5 ? '' : ' is-on') + '" data-d="3">3 portes</button></div></div>' +
      '<div class="t-cap">À faire à l\'arrêt, frein à main serré. ' + (has ? 'Un apprentissage existe déjà : il sera remplacé si tu enregistres le nouveau.' : '') + '</div>' +
      '<div class="sheet-actions"><button class="sbtn press lrn-go" data-go="1">Commencer</button><button class="sbtn press" data-x="1">' + (has ? 'Oublier l\'ancien' : 'Annuler') + '</button></div>', function (s) {
        s.classList.add('sheet-learn');
        $$('[data-d]', s).forEach(function (b) { b.onclick = function () { LB.doors5 = b.getAttribute('data-d') === '5'; $$('[data-d]', s).forEach(function (o) { o.classList.toggle('is-on', o === b); }); }; });
        $('[data-go]', s).onclick = learnBegin;
        $('[data-x]', s).onclick = function () { if (has) { Bridge.learnClear(); toast('Apprentissage oublié : détection automatique'); } closeSheet(); if (setupBack()) return; if (current === 'settings') renderSettings(); if (current === 'vehicle') renderVehicle(); };
      });
  }
  function learnBegin() {
    var r = Bridge.learnStart();
    if (!r || !r.ok) { toast('Apprentissage impossible' + (r && r.error ? ' : ' + r.error : '')); return; }
    LB.access = r.access; LB.busy = true; LB.i = 0; LB.on = true; LB.res = null;
    learnPhase();
    clearInterval(LB.timer); LB.timer = setInterval(learnTick, 400);
  }
  function learnPhase() {
    var st = learnSteps()[LB.i];
    LB.t0 = Date.now(); LB.adv = 0;
    if (st.ev === 'base') setTimeout(function () { if (LB.busy && learnSteps()[LB.i] === st) Bridge.learnStep('base', true); }, 1500);
    else Bridge.learnStep(st.ev, LB.on);
    learnRender(null);
  }
  function learnRender(s) {
    var steps = learnSteps(), st = steps[LB.i], sh = $('#sheet'), key = LB.i + '/' + LB.on;
    if (sh.getAttribute('data-lk') !== key || !$('#lrn-state', sh)) {
      sh.setAttribute('data-lk', key);
      var dots = steps.map(function (x, i) { return '<i class="' + (i < LB.i ? 'ok' : i === LB.i ? 'cur' : '') + '"></i>'; }).join('');
      var title = st.ev === 'base' ? st.t : LB.on ? st.t : st.off;
      sh.innerHTML = '<div class="lrn-top"><div class="t-headline">Apprendre ma voiture</div><div class="lrn-dots">' + dots + '</div></div>' +
        '<div class="lrn-step"><div class="lrn-n">Étape ' + (LB.i + 1) + ' sur ' + steps.length + (st.ev !== 'base' ? ' · ' + (LB.on ? '1/2' : '2/2') : '') + '</div><div class="lrn-t">' + esc(title) + '</div>' +
        (st.d && (LB.on || st.ev === 'base') ? '<div class="t-sub">' + esc(st.d) + '</div>' : '') +
        '<div class="lrn-state" id="lrn-state"></div><div class="lrn-sig" id="lrn-sig"></div><div class="t-cap" id="lrn-hint"></div></div>' +
        '<div class="sheet-actions lrn-act' + (st.ev === 'base' ? ' lrn-one' : '') + '">' + (st.ev === 'base' ? '' : '<button class="sbtn press" data-a="next">C\'est fait</button><button class="sbtn press" data-a="skip">Passer</button>') +
        '<button class="sbtn press" data-a="stop">Arrêter</button></div>';
      $$('[data-a]', sh).forEach(function (b) { b.onclick = function () {
        var a = b.getAttribute('data-a');
        if (a === 'next') learnNext(false);
        else if (a === 'skip') learnNext(true);
        else { learnStop(); Bridge.learnFinish(); closeSheet(); toast('Apprentissage arrêté'); setupBack(); }
      }; });
    }
    var found = s && s.count > 0, back = s && !s.on && s.total > 0 && s.reverted >= s.total, good = st.ev !== 'base' && (LB.on ? found : back);
    var txt = st.ev === 'base' ? 'Relevé au repos… ' + Math.max(0, Math.ceil((st.auto + 1500 - (Date.now() - LB.t0)) / 1000)) + ' s'
      : good ? (LB.on ? 'Détecté' : 'C\'est revenu') : 'J\'attends que tu le fasses…';
    var el = $('#lrn-state', sh); el.className = 'lrn-state' + (good ? ' ok' : '');
    el.innerHTML = (good ? svg('check') : '<span class="lrn-spin"></span>') + '<span>' + txt + '</span>';
    $('#lrn-sig', sh).innerHTML = s && s.changes && s.changes.length && LB.on && st.ev !== 'base' ? s.changes.slice(0, 2).map(esc).join('<br>') : '';
    $('#lrn-hint', sh).textContent = st.ev !== 'base' && !good && Date.now() - LB.t0 > 12000 ? 'Rien ne bouge ? Touche « C\'est fait » pour continuer quand même, ou « Passer ».' : '';
  }
  function learnNext(skip) {
    var steps = learnSteps(), st = steps[LB.i];
    if (!skip && st.ev !== 'base' && LB.on) { LB.on = false; learnPhase(); return; }
    if (skip && st.ev !== 'base' && !LB.on) { /* « Passer » pendant la fermeture : on garde ce qui a été vu */ }
    LB.i++; LB.on = true;
    if (LB.i >= steps.length) { learnDone(); return; }
    learnPhase();
  }
  function learnTick() {
    if (!LB.busy) return;
    var steps = learnSteps(), st = steps[LB.i], s = Bridge.learnStatus() || {};
    if (st.ev === 'base') { if (Date.now() - LB.t0 >= st.auto + 1500) { learnNext(false); return; } learnRender(s); return; }
    if (s.ev !== st.ev) { learnRender(null); return; }
    learnRender(s);
    // avance toute seule : signal trouvé et stable 1,2 s (ouvert) ; tous les signaux revenus (fermé)
    var ready = LB.on ? (s.count > 0 && s.quiet >= 1500 && s.ms >= 2500) : (s.total > 0 && s.reverted >= s.total && s.ms >= 1500);
    if (ready) { if (!LB.adv) LB.adv = Date.now(); else if (Date.now() - LB.adv > 700) learnNext(false); }
    else LB.adv = 0;
  }
  function learnDone() {
    learnStop();
    var r = Bridge.learnFinish() || {}; LB.res = r;
    var lab = r.labels || {}, tried = r.tried || [], n = 0;
    var rows = tried.map(function (ev) { var ok = !!lab[ev]; if (ok) n++;
      return '<div class="lrn-res' + (ok ? ' ok' : '') + '">' + svg(ok ? 'check' : 'xmark') + '<b>' + (LNAMES[ev] || ev) + '</b><span>' + (ok ? esc(lab[ev]) : 'pas trouvé') + '</span></div>'; }).join('');
    $('#sheet').innerHTML = '<div class="t-headline">' + (n ? n + ' élément' + (n > 1 ? 's' : '') + ' trouvé' + (n > 1 ? 's' : '') : 'Rien de lisible trouvé') + '</div>' +
      '<div class="t-sub">' + (n ? 'Enregistre pour que le lanceur s\'en serve tout de suite, en direct.' : 'Ce poste ne publie pas ces infos aux autres applis. Envoie le résultat au téléphone : il nous aidera à ajouter ton poste.') + '</div>' +
      '<div class="lrn-list">' + rows + '</div>' +
      (!LB.access ? '<div class="t-cap">Astuce : autorise « Lecture CAN (accessibilité) » puis recommence : le lanceur verra aussi les fenêtres de l\'appli CAN du poste.</div>' : '') +
      '<div class="sheet-actions lrn-act">' + (n ? '<button class="sbtn press lrn-go" data-a="save">Enregistrer</button>' : '') +
      '<button class="sbtn press" data-a="qr">Envoyer au téléphone</button><button class="sbtn press" data-a="again">Recommencer</button>' + (n ? '' : '<button class="sbtn press" data-a="x">Fermer</button>') + '</div>';
    $$('#sheet [data-a]').forEach(function (b) { b.onclick = function () {
      var a = b.getAttribute('data-a');
      if (a === 'save') { var ok = Bridge.learnSave(); closeSheet(); toast(ok === 'ok' ? 'Enregistré : le lanceur utilise ce qu\'il a appris' : 'Enregistrement impossible : ' + ok); if (ok === 'ok') store.set('doorsOk', true); if (setupBack()) return; if (current === 'settings') setTimeout(renderSettings, 300); if (current === 'vehicle') setTimeout(renderVehicle, 300); }
      else if (a === 'qr') qrSendSheet('learn');
      else if (a === 'again') learnSheet();
      else { closeSheet(); setupBack(); }
    }; });
  }

  /* ---------- v1.27 : « Apprendre l'ordinateur de bord » ----------
     Chaque autoradio a sa propre appli CAN. Le client la choisit, le lanceur l'ouvre avec une barre « Capturer » ;
     de retour ici, il confirme quelle valeur est le carburant, la batterie… (CanApp.java / ScreenRead.java). */
  var CKEYS = [['fuel', 'Carburant (L)'], ['fuel_pct', 'Carburant (%)'], ['range', 'Autonomie'], ['battery', 'Batterie'], ['outside', 'Temp. extérieure'],
    ['coolant', 'Temp. d\'eau'], ['oil', 'Temp. d\'huile'], ['odo', 'Kilométrage'], ['cons', 'Conso. moyenne'], ['avg', 'Vitesse moyenne'], ['rpm', 'Régime moteur'],
    ['speed', 'Vitesse'], ['insp_km', 'Révision (km)'], ['insp_days', 'Révision (jours)'], ['oil_km', 'Vidange (km)'], ['oil_days', 'Vidange (jours)'],
    ['belt', 'Ceinture'], ['washer', 'Lave-glace'], ['handbrake', 'Frein à main']];
  var CKNAME = {}; CKEYS.forEach(function (k) { CKNAME[k[0]] = k[1]; });
  var CM = { res: null, map: [], open: '' };
  function canAppBack() { closeSheet(); if (setupBack()) return; if (current === 'settings') setTimeout(renderSettings, 300); if (current === 'vehicle') setTimeout(renderVehicle, 300); }
  function canAppSheet(all) {
    var ci = Bridge.canAppInfo() || {};
    if (!ci.access) {
      openSheet('<div class="t-headline">Apprendre l\'ordinateur de bord</div>' +
        '<div class="t-sub">Pour lire l\'appli CAN de ton autoradio (carburant, batterie, kilométrage…), le lanceur a besoin de l\'autorisation « Lecture CAN (accessibilité) ».</div>' +
        '<div class="sheet-actions"><button class="sbtn press lrn-go" data-a="go">Autoriser</button><button class="sbtn press" data-a="x">Annuler</button></div>', function (s) {
          s.classList.add('sheet-learn');
          $('[data-a="go"]', s).onclick = function () { closeSheet(); setTimeout(askAccess, 300); };
          $('[data-a="x"]', s).onclick = closeSheet;
        });
      return;
    }
    var list = Bridge.canAppList() || [], sug = list.filter(function (a) { return a.sug; });
    var shown = all || !sug.length ? list : sug;
    var rows = shown.map(function (a) { return '<div class="cm-row cm-app press" data-pkg="' + esc(a.pkg) + '"><div class="cm-l"><b>' + esc(a.label) + '</b><span>' + esc(a.pkg) + '</span></div>' + (a.sug ? '<div class="cm-key">Probable</div>' : '') + '</div>'; }).join('');
    openSheet('<div class="t-headline">Apprendre l\'ordinateur de bord</div>' +
      '<div class="t-sub">Chaque autoradio a sa propre appli « voiture » (CAN). Montre une fois au lanceur où elle affiche le carburant, la batterie ou le kilométrage : il les lira ensuite tout seul. 2 minutes, contact mis, sans ordinateur.</div>' +
      (ci.has ? '<div class="t-cap">Déjà appris : ' + esc(ci.label) + ' (' + ci.fields + ' donnée' + (ci.fields > 1 ? 's' : '') + ')' + (ci.builtin ? ', profil fourni avec le lanceur' : '') + '. Un nouvel apprentissage le remplace.</div>'
        : ci.hcn ? '<div class="t-cap">CarCan est déjà lu automatiquement sur ce poste. Apprends-le seulement s\'il manque des données.</div>' : '') +
      '<div class="hdr cm-hdr">Quelle est l\'appli de la voiture ?</div><div class="cm-list">' + (rows || '<div class="t-cap">Aucune appli trouvée.</div>') + '</div>' +
      '<div class="sheet-actions lrn-act">' + (all || !sug.length ? '' : '<button class="sbtn press" data-a="all">Toutes les applis</button>') +
      (ci.has ? '<button class="sbtn press danger" data-a="forget">Oublier</button>' : '') + '<button class="sbtn press" data-a="x">Annuler</button></div>', function (s) {
        s.classList.add('sheet-learn');
        $$('.cm-app', s).forEach(function (el) { el.onclick = function () { var p = el.getAttribute('data-pkg'), a = shown.filter(function (x) { return x.pkg === p; })[0]; if (a) canAppIntro(a); }; });
        $$('[data-a]', s).forEach(function (b) { b.onclick = function () {
          var a = b.getAttribute('data-a');
          if (a === 'all') canAppSheet(true);
          else if (a === 'forget') { Bridge.canAppClear(); toast('Appli CAN oubliée'); canAppBack(); }
          else canAppBack();
        }; });
      });
  }
  function canAppIntro(a) {
    var ci = Bridge.canAppInfo() || {};
    var how = ci.gestures === false
      ? ['Le lanceur ouvre « ' + esc(a.label) + ' » avec une barre en haut de l\'écran.', 'Va jusqu\'à la page qui affiche les infos de la voiture, comme d\'habitude.', 'Touche « Capturer » sur la barre, puis « Terminer ».']
      : ['Le lanceur ouvre « ' + esc(a.label) + ' » avec une barre en haut de l\'écran.', 'Va jusqu\'à la page qui affiche les infos de la voiture, en touchant <b>lentement</b> (un toucher à la fois) : le lanceur retient le chemin. Si l\'appli ne réagit pas, touche « Main libre » sur la barre et navigue toi-même.', 'Touche « Capturer » sur la barre. S\'il y a d\'autres pages d\'infos, va dessus et capture-les aussi. Puis « Terminer ».'];
    openSheet('<div class="t-headline">' + esc(a.label) + '</div>' +
      '<div class="lrn-list">' + how.map(function (t, i) { return '<div class="cm-row"><div class="cm-num">' + (i + 1) + '</div><div class="cm-l cm-wrap"><span>' + t + '</span></div></div>'; }).join('') + '</div>' +
      '<div class="t-cap">Contact mis ou moteur allumé, pour que les valeurs s\'affichent. Rien n\'est modifié dans le poste.</div>' +
      '<div class="sheet-actions"><button class="sbtn press lrn-go" data-a="go">Commencer</button><button class="sbtn press" data-a="x">Retour</button></div>', function (s) {
        s.classList.add('sheet-learn');
        $('[data-a="x"]', s).onclick = function () { canAppSheet(false); };
        $('[data-a="go"]', s).onclick = function () {
          var r = Bridge.canAppStart(a.pkg) || {};
          if (!r.ok) { toast(r.msg || 'Apprentissage impossible'); if (r.error === 'access') { closeSheet(); setTimeout(askAccess, 300); } return; }
          closeSheet();
        };
      });
  }
  function canMapRows() {
    var res = CM.res, h = '';
    res.pages.forEach(function (pg, p) {
      if (res.pages.length > 1) h += '<div class="cm-page">Page ' + (p + 1) + '</div>';
      pg.cands.forEach(function (c) {
        var k = CM.map[p][c.i] || '', id = p + ':' + c.i;
        h += '<div class="cm-row press' + (k ? ' on' : '') + '" data-id="' + id + '"><div class="cm-l"><b>' + esc(c.value) + (c.unit ? ' ' + esc(c.unit) : '') + '</b><span>' + (c.label ? esc(c.label) : 'sans nom à l\'écran') + '</span></div>' +
          '<div class="cm-key">' + (k ? esc(CKNAME[k] || k) : 'Ignorer') + '</div></div>';
        if (CM.open === id) h += '<div class="cm-chips" data-for="' + id + '">' + CKEYS.map(function (x) { return '<button class="press' + (x[0] === k ? ' is-on' : '') + '" data-k="' + x[0] + '">' + esc(x[1]) + '</button>'; }).join('') +
          '<button class="press' + (k ? '' : ' is-on') + '" data-k="">Ignorer</button></div>';
      });
    });
    return h;
  }
  function canMapCount() { var n = 0; CM.map.forEach(function (m) { for (var i in m) if (m[i]) n++; }); return n; }
  function canMapBind() {
    var sh = $('#sheet'), box = $('.cm-list', sh), top = box.scrollTop;
    box.innerHTML = canMapRows(); box.scrollTop = top;
    $$('.cm-row', box).forEach(function (el) { el.onclick = function () { var id = el.getAttribute('data-id'); CM.open = CM.open === id ? '' : id; canMapBind(); }; });
    $$('.cm-chips button', box).forEach(function (b) { b.onclick = function () {
      var id = b.parentNode.getAttribute('data-for').split(':'), p = +id[0], i = id[1], k = b.getAttribute('data-k');
      if (k) CM.map.forEach(function (m) { for (var j in m) if (m[j] === k) delete m[j]; });      // une donnée = une seule valeur
      if (k) CM.map[p][i] = k; else delete CM.map[p][i];
      CM.open = ''; canMapBind();
    }; });
    var n = canMapCount(), sv = $('[data-a="save"]', sh);
    if (sv) { sv.textContent = n ? 'Enregistrer (' + n + ')' : 'Enregistrer'; sv.style.opacity = n ? '' : '.4'; }
  }
  function canMapSheet(res) {
    if (!res || !res.pkg) return;
    if (!res.pages || !res.pages.length) {
      var stuck = res.taps > 0 && !res.fx && !res.free;      // v1.27.1 : touchers jamais arrivés à l'appli
      openSheet('<div class="t-headline">Aucune page capturée</div>' +
        '<div class="t-sub">' + (stuck ? 'Tes touchers ne sont pas arrivés jusqu\'à « ' + esc(res.label || res.pkg) + ' » : l\'appli est restée sur son premier écran. ' +
          'Recommence et touche <b>« Main libre »</b> sur la barre : tu navigues toi-même jusqu\'à la page des infos, puis « Capturer ». Le lanceur retrouvera le chemin tout seul.'
          : 'Dans « ' + esc(res.label || res.pkg) + ' », va sur la page qui affiche les infos de la voiture puis touche « Capturer » avant « Terminer ». ' +
          'Si la barre disait « aucun texte lisible », cette appli dessine ses chiffres en image : envoie le journal au téléphone, on regardera ton poste.') + '</div>' +
        '<div class="sheet-actions lrn-act"><button class="sbtn press lrn-go" data-a="again">Recommencer</button><button class="sbtn press" data-a="qr">Envoyer le journal</button><button class="sbtn press" data-a="x">Fermer</button></div>', function (s) {
          s.classList.add('sheet-learn');
          $('[data-a="again"]', s).onclick = function () { canAppSheet(false); };
          $('[data-a="qr"]', s).onclick = function () { qrSendSheet('journal'); };
          $('[data-a="x"]', s).onclick = canAppBack;
        });
      return;
    }
    CM.res = res; CM.open = '';
    CM.map = res.pages.map(function (pg) { var m = {}; pg.cands.forEach(function (c) { if (c.key) m[c.i] = c.key; }); return m; });
    var n = canMapCount();
    openSheet('<div class="t-headline">' + (n ? n + ' donnée' + (n > 1 ? 's' : '') + ' reconnue' + (n > 1 ? 's' : '') : 'À toi d\'indiquer les données') + ' dans ' + esc(res.label || res.pkg) + '</div>' +
      '<div class="t-sub">Vérifie chaque ligne : touche-la pour dire à quoi correspond la valeur, ou « Ignorer ». Les valeurs sont celles affichées au moment de la capture.</div>' +
      '<div class="cm-list"></div>' +
      '<div class="sheet-actions lrn-act"><button class="sbtn press lrn-go" data-a="save">Enregistrer</button><button class="sbtn press" data-a="again">Recommencer</button><button class="sbtn press" data-a="x">Annuler</button></div>', function (s) {
        s.classList.add('sheet-learn');
        $$('[data-a]', s).forEach(function (b) { b.onclick = function () {
          var a = b.getAttribute('data-a');
          if (a === 'again') { canAppSheet(false); return; }
          if (a === 'x') { canAppBack(); return; }
          if (!canMapCount()) { toast('Choisis au moins une donnée'); return; }
          var ok = Bridge.canAppSave({ pages: CM.map });
          if (ok !== 'ok') { toast('Enregistrement impossible : ' + ok); return; }
          var ap = apps.filter(function (x) { return x.pkg === res.pkg; })[0];
          if (ap) { var rr = store.get('roles', {}) || {}; rr.can = key(ap); store.set('roles', rr); }      // bouton de la page Véhicule
          toast('Enregistré : lecture de vérification…');
          canAppBack();
          setTimeout(function () { if (Bridge.canRefreshPossible()) { lastAutoRead = Date.now(); Bridge.canRefresh(); } }, 1200);
        }; });
      });
    canMapBind();
  }
  Bridge.on('canlearn', function (d) { var p = Bridge.mock ? d : Bridge.canAppPending(); if (p) canMapSheet(p); });
  Bridge.on('resume', function () { var p = Bridge.canAppPending(); if (p) canMapSheet(p); });

  /* ---------- v1.25 : envoi au téléphone par une suite de QR codes (journal ou apprentissage) ----------
     Le premier code scanné ouvre la page Car Launcher sur le téléphone ; elle lit ensuite les autres en continu
     avec l'appareil photo. Rien ne passe par internet depuis l'autoradio. */
  var QS = { t: 0, i: 0, parts: [], paused: false };
  function journalUrl() { return CFG.journalUrl || (String(CFG.reportUrl || 'https://reality-design.fr/carlauncher/').replace(/[?#].*$/, '').replace(/\/?$/, '/') + 'journal.html'); }
  function qrSendSheet(kind) {
    var r = Bridge.qrExport(kind);
    if (!r || !r.parts || !r.parts.length) { toast('Export impossible' + (r && r.error ? ' : ' + r.error : '')); return; }
    QS.parts = r.parts; QS.i = 0; QS.paused = false;
    loadQr(function () {
      var n = QS.parts.length;
      openSheet('<div class="qr-wrap"><div class="qr-box" id="qs-box"></div><div class="qr-text">' +
        '<div class="t-headline">' + (kind === 'learn' ? 'Envoyer l\'apprentissage' : 'Envoyer le journal') + '</div>' +
        '<ol class="steps qs-steps"><li>Scanne le code avec l\'appareil photo de ton téléphone.</li>' +
        (n > 1 ? '<li>Sur la page qui s\'ouvre, touche « Scanner la suite » et vise cet écran : les ' + n + ' codes défilent et sont lus tout seuls.</li>' : '') +
        '<li>Quand c\'est complet, envoie le fichier depuis le téléphone.</li></ol>' +
        '<div class="qs-bar"><b id="qs-n">1/' + n + '</b><div class="qs-track"><i id="qs-fill"></i></div></div>' +
        '<div class="t-cap">Rien n\'est envoyé depuis la voiture, pas besoin d\'internet ici.</div>' +
        '<div class="sheet-actions">' + (n > 1 ? '<button class="sbtn press" data-q="pause">Pause</button>' : '') + '<button class="sbtn press' + (n > 1 ? '' : ' wide') + '" data-q="x">Fermer</button></div></div></div>', function (sh) {
          sh.classList.add('sheet-qr');
          $('[data-q="x"]', sh).onclick = function () { clearInterval(QS.t); closeSheet(); };
          var p = $('[data-q="pause"]', sh); if (p) p.onclick = function () { QS.paused = !QS.paused; p.textContent = QS.paused ? 'Reprendre' : 'Pause'; };
          qsShow();
          clearInterval(QS.t);
          if (n > 1) QS.t = setInterval(function () { if (!$('#qs-box')) { clearInterval(QS.t); return; } if (!QS.paused) { QS.i = (QS.i + 1) % n; qsShow(); } }, 900);
        });
    });
  }
  function qsShow() {
    var box = $('#qs-box'); if (!box) return;
    try { box.innerHTML = qrSvg(journalUrl() + '#' + QS.parts[QS.i]); } catch (e) { box.textContent = 'QR impossible'; }
    $('#qs-n').textContent = (QS.i + 1) + '/' + QS.parts.length;
    $('#qs-fill').style.width = ((QS.i + 1) / QS.parts.length * 100) + '%';
  }

  function toggleRow(ic, t, on, act) { return '<div class="row btn" data-act="toggle:' + act + '">' + svg(ic) + '<div class="grow">' + t + '</div><div class="switch' + (on ? ' on' : '') + '"></div></div>'; }
  function act(a, el) {
    var p = a.split(':');
    if (a === 'sys:leave') {
      openSheet('<div class="t-headline">Revenir au lanceur d\'origine</div><div class="t-sub">Rien n\'est modifié dans le système : CarPlay, la radio et les autres applis restent intactes.<br><br>' +
        '<b>1.</b> Choisis l\'écran d\'accueil d\'origine de l\'autoradio (souvent « Launcher3 »).<br><b>2.</b> Pour supprimer ce lanceur, reviens ici et touche « Désinstaller ». ' +
        'Les données du lanceur (entretiens, réglages) seront effacées.</div>' +
        '<div class="sheet-actions"><button class="sbtn press" data-l="home" style="background:rgba(255,255,255,.18)">1. Écran d\'accueil</button><button class="sbtn danger press" data-l="uninstall">2. Désinstaller</button></div>', function (sh) {
        $$('[data-l]', sh).forEach(function (b) { b.onclick = function () { closeSheet(); Bridge.openSystem(b.getAttribute('data-l')); }; });
      });
    }
    else if (a === 'access') askAccess();
    else if (p[0] === 'sys') Bridge.openSystem(p[1]);
    else if (a === 'wall') wallSheet();
    else if (a === 'theme') { var th = store.get('theme', 'auto'); th = th === 'auto' ? 'dark' : th === 'dark' ? 'light' : 'auto'; store.set('theme', th); applyTheme(); renderSettings(); }
    else if (p[0] === 'role') roleSheet(p[1], $('.grow', el).textContent);
    else if (a === 'autoread') { var ar = store.get('autoread', 'boot'); ar = ar === 'off' ? 'boot' : ar === 'boot' ? '15' : 'off'; store.set('autoread', ar); renderSettings(); }
    else if (a === 'zoom') { var z = store.get('zoom', 'grand'); z = z === 'normal' ? 'grand' : z === 'grand' ? 'xl' : 'normal'; store.set('zoom', z); fit(); renderApps(); renderSettings(); alignGlass(); setTimeout(refitFrames, 400); }
    else if (a === 'layout') layoutSheet();
    else if (a === 'appsrows') { store.set('appsRows', store.get('appsRows', 2) === 2 ? 3 : 2); appsPageIdx = 0; fit(); renderApps(); renderSettings(); alignGlass(); }
    else if (a === 'can:scan') { toast('Analyse en cours…'); setTimeout(function () { var r = Bridge.canScan(); renderSettings(); toast((r.split('\n')[0] || 'Fait').replace(/^CAN : /, '')); }, 60); }
    else if (a === 'can:ts10') {   // v1.22.1 : TopWay TS10 / TS18, trames du boîtier CAN notées dans le journal
      openSheet('<div class="t-headline">Sonder le CAN du poste</div><div class="t-sub">Pendant 3 minutes, le lanceur note dans le journal ce que le boîtier CAN envoie au poste. Il ne fait que lire.<br><br>' +
        '<b>Moteur allumé</b>, pendant ces 3 minutes : ouvre puis ferme chaque porte, le coffre et le capot, mets le clignotant gauche puis droit, allume les feux, passe la marche arrière. Si tu peux, roule un peu (sans toucher à l\'écran).<br><br>' +
        'Ensuite : Diagnostic CAN › Exporter le journal, et envoie le fichier.</div>' +
        '<div class="sheet-actions"><button class="sbtn wide press" data-go="1">Lancer le sondage</button><button class="sbtn wide press" data-x="1">Annuler</button></div>', function (sh) {
          $('[data-x]', sh).onclick = closeSheet;
          $('[data-go]', sh).onclick = function () { closeSheet(); var r = Bridge.ts10Probe(); toast(r || 'Sondage impossible'); setTimeout(renderSettings, 300); };
        }); }
    else if (a === 'pack:sheet') packSheet();
    else if (a === 'custom') showPage('custom', el);
    else if (a === 'report:qr') reportSheet();
    else if (a === 'learn') learnSheet();
    else if (a === 'canapp') canAppSheet(false);
    else if (a === 'setup') setupSheet();
    else if (a === 'qr:journal') qrSendSheet('journal');
    else if (a === 'can:export') { var f2 = Bridge.canExport(); toast(f2 ? 'Copié : ' + f2 : 'Copie impossible'); }
    else if (a === 'can:logs') { openSheet('<div class="t-headline">Ordinateur de bord en direct</div><div class="t-sub" style="-webkit-user-select:text;user-select:text">' +
      (Bridge.canLive() ? '<b>Actif</b> : carburant, batterie, température, kilométrage et régime arrivent en direct, CarCan n\'est plus ouvert.' :
       Bridge.canLogs() ? 'Autorisé, en attente des premières données du boîtier CAN (moteur allumé, CarCan installé).' :
       'Une seule fois, depuis un ordinateur relié en USB (débogage USB activé) :<br><br><b>adb shell pm grant fr.ibiza.launcher android.permission.READ_LOGS</b><br><br>puis redémarre l\'autoradio. Le lanceur lira alors les données du CAN en direct, sans ouvrir CarCan.') +
      '</div><div class="sheet-actions">' + (Bridge.canLogs() ? '' : '<button class="sbtn wide press" data-r="1">Essayer l\'autorisation automatique (root)</button>') + '<button class="sbtn wide press" data-x="1">OK</button></div>', function (sh) {
        $('[data-x]', sh).onclick = closeSheet;
        var rb = $('[data-r]', sh); if (rb) rb.onclick = function () { closeSheet(); toast('Essai en cours… accepte la fenêtre « root » si elle apparaît'); Bridge.tryRoot(); };
      }); }
    else if (a === 'icons:reload') { Bridge.reloadIcons(); toast('Icônes rechargées'); }
    else if (a === 'icons:list') {
      var lf = Bridge.appList();
      if (!lf) { toast('Écriture de la liste impossible'); return; }
      openSheet('<div class="t-headline">Icônes perso</div><div class="t-sub" style="-webkit-user-select:text;user-select:text">' +
        'La liste de tes applis, avec le nom de fichier de chaque icône, est dans :<br><b>' + esc(lf.replace(/^.*\/Android\//, 'Android/')) + '</b><br><br>' +
        'Mets tes images (PNG carré, 192 × 192 px ou plus) dans ce même dossier avec ces noms, puis touche « Recharger les icônes perso ». ' +
        'Elles s\'affichent en pleine tuile.</div><div class="sheet-actions"><button class="sbtn wide press" data-ok="1">OK</button></div>', function (s) {
        tappable($('[data-ok]', s), closeSheet);
      });
    }
    else if (a === 'glass') { var m = store.get('glass', 'sim'); m = m === 'sim' ? 'real' : m === 'real' ? 'off' : 'sim'; store.set('glass', m); applyGlassMode(); renderSettings(); }
    else if (p[0] === 'toggle') { var dflt = p[1] !== 'lite' && p[1] !== 'steerInv'; var v = !store.get(p[1], dflt); store.set(p[1], v); $('.switch', el).classList.toggle('on', v); if (p[1] === 'lite') applyLite(); if (p[1] === 'steerInv' && veh.steer != null) drawRadar($('#radar-svg')); }
    else if (a === 'test:moving') Bridge.testMoveAlert();
    else if (a === 'test:ready') { showPage('home'); setTimeout(function () { showReady(true); }, 350); }
    else if (p[0] === 'sim') {
      var d = { fl: false, fr: false, rl: false, rr: false, trunk: false, hood: false };
      var v2 = { doors: d, radar: null, reverse: false };
      if (p[1] === 'fl' || p[1] === 'rl' || p[1] === 'trunk') d[p[1]] = true;
      if (p[1] === 'radar') { v2.reverse = true; v2.radar = { rear: [120, 64, 38, 150], front: [null, null, null, null] }; }
      CanBridgeSimUntil = Date.now() + 15000;
      Bridge.simulate(v2);
      if (p[1] !== 'none') setTimeout(function () { showPage('home', $('#btn-pages')); }, 150);
    }
    else if (a === 'diag:export') { var f = Bridge.diagExport(); toast(f ? 'Enregistré : ' + f : 'Export impossible'); }
    else if (a === 'diag:clear') { Bridge.diagClear(); renderSettings(); }
  }
  tappable($('#card-clock'), function () {}, function () { showPage('settings', $('#card-clock')); });

  /* ======================= démarrage ======================= */
  function boot() {
    fit(); applyLite(); applyLayout(false); setTimeout(function () { fitTexts(true); }, 300);
    applyGlassMode(); applyTheme();
    loadApps(); renderDock(); renderQuick(); renderApps();
    $('#btn-pages').innerHTML = svg('grid');
    $('#st-bt').innerHTML = svg('bt'); $('#st-wifi').innerHTML = svg('wifi');
    var st = Bridge.state();
    renderMedia(st.media); onVehicle(st.vehicle); renderCd();
    tick(); setInterval(function () { tick(); applyTheme(); applyWall(); }, 1000 * 5);
    $('#wall-file').addEventListener('change', function (e) { var f = e.target.files && e.target.files[0]; if (f) loadCustomWall(f); e.target.value = ''; });
    alignGlass();
    Object.keys(pageSprings).forEach(function () {});
    setTimeout(introSpin, 250);
  }
  /* données véhicule (CAN) : huile, eau, extérieur, batterie */
  function setCd(id, v, unit, warnIf) {
    var el = $(id); if (!el) return;
    if (v == null || v === '' || isNaN(v)) { el.textContent = '—'; el.classList.remove('warn'); return; }
    el.textContent = (unit === 'V' ? Number(v).toFixed(1).replace('.', ',') : Math.round(v)) + (unit === 'V' ? ' V' : '°');
    el.classList.toggle('warn', !!(warnIf && warnIf(v)));
  }

  /* ======================= v1.13 : écran « Prêt à partir » (plein écran) =======================
     Au démarrage de l'autoradio (et au réveil après plus de 15 min de veille), l'écran s'affiche tout de suite en
     « lecture… » (aucune valeur périmée), puis la lecture CarCan se fait sous le rideau : c'est cet écran qu'on voit
     pendant la lecture, jamais CarCan. Les vraies valeurs s'affichent au retour ; « C'est parti » ramène à l'accueil. */
  var RD = { tick: Date.now(), state: 'off', mv: 0, t: null, wait: null };
  function washerLow(w) { return !!w && !/^\s*(normal|ok|plein|full|bon)\s*$/i.test(w) && /anormal|bas|faible|low|insuf|vide|empty|manque|ajout|rempli|add|niveau/i.test(w); }
  function readyAlerts() {
    var a = [], c = car || {};
    if (c.battery != null && c.battery < 12) a.push({ lv: 2, ic: 'battery', t: 'Batterie faible', s: fmtN(c.battery, 1) + ' V : à faire contrôler' });
    if (washerLow(c.washer)) a.push({ lv: 1, ic: 'gauge', t: 'Lave-glace à remplir', s: String(c.washer) });
    if (c.outside != null && c.outside <= 3) a.push({ lv: 1, ic: 'temp', t: 'Risque de verglas', s: fmtN(c.outside, 1) + ' °C dehors : prudence' });
    if ((c.range != null && c.range < 60) || (c.fuel != null && c.fuel < 7)) a.push({ lv: 1, ic: 'fuel', t: 'Réserve de carburant', s: c.range != null ? 'Autonomie ' + fmtN(c.range) + ' km' : fmtN(c.fuel) + ' L restants' });
    var m = store.get('maint', null);
    if (m) maintDefs(m).forEach(function (d) { var st = maintState(m[d[0]] || {}); if (st && st.level >= 1) a.push({ lv: st.level, ic: 'wrench', t: d[1], s: (st.level === 2 ? '' : 'Bientôt : ') + leftText(st) }); });
    var o = openList(); if (o.length) a.push({ lv: 1, ic: 'door', t: o.length === 1 ? o[0][1] + ' ouvert' + (o[0][0] === 'trunk' || o[0][0] === 'hood' ? '' : 'e') : o.length + ' ouvrants ouverts', s: 'À fermer avant de partir' });
    a.sort(function (x, y) { return y.lv - x.lv; });
    return a;
  }
  function readyRender() {
    var el = $('#ready'), c = car || {}, now = new Date(), h = now.getHours(), loading = RD.state === 'loading';
    var hello = h >= 5 && h < 12 ? 'Bonjour' : h >= 12 && h < 18 ? 'Bon après-midi' : 'Bonsoir';
    var days = ['dimanche', 'lundi', 'mardi', 'mercredi', 'jeudi', 'vendredi', 'samedi'], months = ['janvier', 'février', 'mars', 'avril', 'mai', 'juin', 'juillet', 'août', 'septembre', 'octobre', 'novembre', 'décembre'];
    var nx = maintNext();
    var tile = function (ic, label, val, warn) { return '<div class="rdf-tile' + (warn ? ' warn' : '') + '">' + svg(ic) + '<span>' + label + '</span><b>' + (loading ? '<i class="sk"></i>' : val) + '</b></div>'; };
    var lowF = (c.range != null && c.range < 60) || (c.fuel != null && c.fuel < 7);
    var tiles = tile('fuel', c.range != null || loading ? 'Autonomie' : 'Carburant', c.range != null ? fmtN(c.range) + ' km' : c.fuel != null ? fmtN(c.fuel) + ' L' : '—', !loading && lowF) +
      tile('temp', 'Extérieur', c.outside != null ? fmtN(c.outside, 1) + ' °C' : '—', !loading && c.outside != null && c.outside <= 3) +
      tile('battery', 'Batterie', c.battery != null ? fmtN(c.battery, 1) + ' V' : '—', !loading && c.battery != null && c.battery < 12) +
      tile('wrench', 'Entretien', nx ? esc(nx.name) + '<em>' + esc(nx.short) + '</em>' : 'À jour', !loading && nx && nx.level >= 1);
    var body;
    if (loading) body = '<div class="rdf-load"><i class="spin"></i>Lecture des données du véhicule…</div>';
    else {
      var al = readyAlerts();
      body = !al.length ? '<div class="rdf-ok">' + svg('check') + '<div><b>Tout est en ordre</b><span>Bonne route !</span></div></div>'
        : '<div class="rdf-list">' + al.slice(0, 4).map(function (x) { return '<div class="rdf-al lv' + x.lv + '">' + svg(x.ic) + '<div><b>' + esc(x.t) + '</b><span>' + esc(x.s) + '</span></div></div>'; }).join('') + '</div>' +
          (al.length > 4 ? '<div class="rdf-more">+ ' + esc(al.slice(4).map(function (x) { return x.t; }).join(', ')) + '</div>' : '');
      if (RD.state === 'stale') body += '<div class="rdf-note">Lecture de l\'ordinateur de bord impossible pour l\'instant : dernières valeurs connues' + (car.at ? ' (' + frDate(new Date(car.at)) + ')' : '') + '.</div>';
    }
    el.innerHTML = '<div class="rdf-glow"></div><div class="rdf-in">' +
      '<div class="rdf-head"><div><div class="rdf-hello">' + hello + '</div><div class="rdf-sub">Prêt à partir · ' + days[now.getDay()] + ' ' + now.getDate() + ' ' + months[now.getMonth()] + (!loading && c.odo != null ? ' · ' + fmtN(Math.round(c.odo)) + ' km' : '') + '</div></div>' +
      '<div class="rdf-time">' + pad(h) + ':' + pad(now.getMinutes()) + '</div></div>' +
      '<div class="rdf-tiles">' + tiles + '</div><div class="rdf-body">' + body + '</div>' +
      '<button class="rdf-go press" id="rdf-go">C\'est parti</button></div>';
    $('#rdf-go').onclick = hideReady;
  }
  function readyOpen() { return RD.state !== 'off'; }
  function showReady(force) {
    if (!force && (!store.get('ready', true) || CK.on || (window.LB && LB.busy) || (!Bridge.mock && !store.get('setupDone', false)))) return false;
    var canRead = !Bridge.mock && Bridge.canRefreshPossible() && store.get('autoread', 'boot') !== 'off';
    if (current !== 'home') showPage('home');
    if ($('#sheet-scrim').classList.contains('on')) closeSheet();
    RD.state = canRead ? 'loading' : (Bridge.mock ? 'fresh' : 'stale'); RD.mv = 0;
    readyRender();
    var el = $('#ready'); el.classList.add('on'); el.setAttribute('aria-hidden', 'false');
    clearTimeout(RD.wait); clearTimeout(RD.t);
    if (canRead) {
      // la lecture CarCan démarre une fois l'écran affiché : le rideau en fait une image figée, CarCan n'apparaît jamais
      setTimeout(readyRead, Bridge.canLive() ? 300 : RD.boot ? 5000 : 1500);   // v1.15 : données déjà en direct -> immédiat
    }
    RD.boot = false;
    RD.t = setTimeout(function () { if (readyOpen()) hideReady(); }, 5 * 60000);
    return true;
  }
  function readyRead() {          // jamais par-dessus CarPlay ou une autre appli : on attend que le lanceur soit affiché
    if (RD.state !== 'loading') return;
    if (!visible) { RD.pending = true; return; }
    RD.pending = false; lastAutoRead = Date.now(); Bridge.canRefresh();
    clearTimeout(RD.wait); RD.wait = setTimeout(function () { if (RD.state === 'loading') { RD.state = 'stale'; readyRender(); } }, 12000);
  }
  function hideReady() {
    if (!readyOpen()) return;
    RD.state = 'off'; clearTimeout(RD.wait); clearTimeout(RD.t);
    var el = $('#ready'); el.classList.remove('on'); el.setAttribute('aria-hidden', 'true');
    if (current !== 'home') showPage('home');
    renderVehicleSummary(); renderCd();
  }
  Bridge.on('car', function (d) {          // valeurs fraîches : l'écran passe de « lecture… » aux vraies valeurs
    if (!readyOpen() || !d || Object.keys(d).length < 2) return;
    RD.state = 'fresh'; clearTimeout(RD.wait);
    setTimeout(function () { readyRender(); $('#ready').classList.add('pop'); setTimeout(function () { $('#ready').classList.remove('pop'); }, 500); }, 30);
  });
  Bridge.on('resume', function (d) {        // retour de la lecture masquée sans valeurs : on montre les dernières connues
    if (d && d.silent && RD.state === 'loading') setTimeout(function () { if (RD.state === 'loading') { RD.state = 'stale'; readyRender(); } }, 2500);
    else if (!(d && d.silent) && RD.pending) setTimeout(readyRead, 1200);
  });
  Bridge.on('vehicle', function () { if (readyOpen() && RD.state !== 'loading') setTimeout(readyRender, 50); });
  Bridge.on('speed', function (d) {          // on roule depuis 3 s : l'écran s'efface
    var k = d && d.kmh ? d.kmh : 0;
    if (!readyOpen()) return;
    if (k >= 10) { if (!RD.mv) RD.mv = Date.now(); else if (Date.now() - RD.mv > 3000) hideReady(); } else RD.mv = 0;
  });
  RD.boot = true;
  setTimeout(function () { if (!showReady(false)) bootRead = true; }, 300);
  if (!Bridge.mock && !store.get('setupDone', false)) setTimeout(function () { if (!$('#sheet-scrim').classList.contains('on')) setupSheet(); }, 1500);   // v1.26 : première installation      // démarrage de l'autoradio
  setInterval(function () {                                                          // réveil après une veille
    var now = Date.now();
    if (now - RD.tick > 15 * 60000) setTimeout(function () { showReady(false); }, 800);
    RD.tick = now;
  }, 20000);

  /* ======================= Entretien : vue « poste de conduite » =======================
     Vidéo pré-rendue (la caméra tourne autour de la voiture, passe par la lunette arrière et s'installe
     au milieu de l'habitacle), qui se fond dans la photo du tableau de bord ; la carte Entretien glisse ensuite à droite. */
  var CK = { on: false, timer: null };
  function ckLayout() { // fond flou de la carte aligné au pixel sur la photo (object-fit: cover)
    var ov = $('#cockpit'), card = $('#ck-card'), s = Math.max(VW / 1024, VH / 600), bw = 1024 * s, bh = 600 * s;
    ov.style.setProperty('--ck-bw', bw + 'px'); ov.style.setProperty('--ck-bh', bh + 'px');
    ov.style.setProperty('--ck-bx', ((VW - bw) / 2 - card.offsetLeft) + 'px'); ov.style.setProperty('--ck-by', ((VH - bh) / 2 - card.offsetTop) + 'px');
  }
  function openCockpit() {
    if (CK.on) return; CK.on = true;
    var ov = $('#cockpit'), v = $('#ck-video'), ph = $('#ck-photo');
    if (!ph.getAttribute('src')) ph.setAttribute('src', 'img/cockpit/dash.webp');
    renderMaint(); ckLayout(); hideLive(true);
    ov.classList.remove('card-in'); v.classList.remove('done');
    ov.classList.add('on'); ov.setAttribute('aria-hidden', 'false');
    var finish = function () { if (!CK.on) return; clearTimeout(CK.timer); v.classList.add('done'); ov.classList.add('card-in'); };
    if (Motion.reduced || lite) { v.classList.add('done'); CK.timer = setTimeout(finish, 150); return; }
    if (!v.getAttribute('src')) v.setAttribute('src', 'img/cockpit/intro.mp4');
    v.onended = finish; v.onerror = finish;
    try { v.currentTime = 0; } catch (e) {}
    var p = v.play(); if (p && p.catch) p.catch(finish);
    CK.timer = setTimeout(finish, 5000);   // filet de sécurité si la vidéo ne démarre pas
  }
  function closeCockpit() {
    if (!CK.on) return; CK.on = false; clearTimeout(CK.timer);
    var ov = $('#cockpit'), v = $('#ck-video');
    ov.classList.remove('card-in');
    setTimeout(function () { ov.classList.remove('on'); ov.setAttribute('aria-hidden', 'true'); try { v.pause(); } catch (e) {} }, 200);
  }
  $('#ck-close').innerHTML = svg('back');
  tappable($('#ck-close'), closeCockpit);
  window.addEventListener('resize', function () { if (CK.on) setTimeout(ckLayout, 60); });

  /* ======================= entretien saisi à la main =======================
     Chaque opération : dernière fois (date + km) et intervalle (km et/ou mois), « au premier des deux termes ».
     Valeurs par défaut = plan d'entretien fixe habituel Seat Ibiza (à vérifier dans le carnet, modifiables). */
  var MAINT_DEF = [
    ['oil', 'Vidange + filtre à huile', 15000, 12, 'fuel', 'Vidange'],
    ['insp', 'Révision (inspection)', 30000, 24, 'wrench', 'Révision'],
    ['air', 'Filtre à air', 60000, 48, 'gauge', 'Filtre à air'],
    ['cabin', 'Filtre d\'habitacle', 30000, 24, 'gauge', 'Filtre habitacle'],
    ['fuelf', 'Filtre à gasoil', 60000, 0, 'fuel', 'Filtre gasoil', 'diesel'],
    ['plugs', 'Bougies d\'allumage', 60000, 48, 'gauge', 'Bougies', 'essence'],
    ['brakefl', 'Liquide de frein', 0, 24, 'car', 'Liquide frein'],
    ['belt', 'Courroie de distribution', 120000, 60, 'gauge', 'Distribution'],
    ['ct', 'Contrôle technique', 0, 24, 'check', 'Contrôle tech.']
  ];
  /* v1.11 : motorisation (diesel par défaut : filtre à gasoil, pas de bougies) + entretiens libres ajoutés à la main */
  function engine() { return store.get('engine', CAR.fuel || 'diesel'); }
  function maintDefs(m) {
    m = m || store.get('maint', null) || {};
    var e = engine(), out = MAINT_DEF.filter(function (d) { return !d[6] || d[6] === e; });
    Object.keys(m).filter(function (k) { return m[k] && m[k].custom; }).sort(function (x, y) { return (m[x].created || 0) - (m[y].created || 0); })
      .forEach(function (k) { out.push([k, m[k].name || 'Entretien', m[k].km, m[k].months, 'wrench', m[k].name || 'Entretien']); });
    return out;
  }
  function maintData() {
    var m = store.get('maint', null) || {};
    MAINT_DEF.forEach(function (d) {
      if (!m[d[0]]) m[d[0]] = { km: d[2], months: d[3], on: true, last: null, hist: [] };
    });
    return m;
  }
  function maintSave(m) { store.set('maint', m); renderVehicleSummary(); if (CK.on || (current === 'vehicle' && vehView === 'maint')) renderMaint(); }
  function curKm() { return car.odo != null ? Math.round(car.odo) : null; }
  function addMonths(iso, n) { var d = new Date(iso + 'T12:00:00'); d.setMonth(d.getMonth() + n); return d; }
  function frDate(d) { if (typeof d === 'string') d = new Date(d + 'T12:00:00'); return pad(d.getDate()) + '/' + pad(d.getMonth() + 1) + '/' + d.getFullYear(); }
  function todayIso() { var d = new Date(); return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()); }
  /* état d'une opération : km et jours restants, niveau 0 ok / 1 bientôt / 2 dépassé */
  function maintState(it) {
    if (!it.on || !it.last) return null;
    var odo = curKm(), kmLeft = null, daysLeft = null;
    if (it.km > 0 && it.last.km != null && odo != null) kmLeft = it.last.km + it.km - odo;
    if (it.months > 0 && it.last.date) daysLeft = Math.round((addMonths(it.last.date, it.months) - Date.now()) / 864e5);
    if (kmLeft == null && daysLeft == null) return null;
    var level = (kmLeft != null && kmLeft < 0) || (daysLeft != null && daysLeft < 0) ? 2 : (kmLeft != null && kmLeft < 1500) || (daysLeft != null && daysLeft < 45) ? 1 : 0;
    // part déjà « consommée » de l'intervalle (pour la barre)
    var pk = kmLeft != null ? 1 - kmLeft / it.km : 0, pd = daysLeft != null ? 1 - daysLeft / (it.months * 30.44) : 0;
    return { kmLeft: kmLeft, daysLeft: daysLeft, level: level, used: Math.max(0, Math.min(1, Math.max(pk, pd))) };
  }
  function leftText(st) {
    if (!st) return '';
    if (st.level === 2) {
      var o = []; if (st.kmLeft != null && st.kmLeft < 0) o.push(fmtN(-st.kmLeft) + ' km'); if (st.daysLeft != null && st.daysLeft < 0) o.push(fmtN(-st.daysLeft) + ' j');
      return 'Dépassé de ' + o.join(' · ');
    }
    var p = []; if (st.kmLeft != null) p.push(fmtN(st.kmLeft) + ' km');
    if (st.daysLeft != null) p.push(st.daysLeft > 60 ? Math.round(st.daysLeft / 30.44) + ' mois' : st.daysLeft + ' j');
    return 'Dans ' + p.join(' · ');
  }
  function ivText(it) {
    var mo = it.months > 0 ? (it.months % 12 === 0 ? it.months / 12 + ' an' + (it.months > 12 ? 's' : '') : it.months + ' mois') : '';
    return (it.km > 0 ? fmtN(it.km) + ' km' : '') + (it.km > 0 && it.months > 0 ? ' / ' : '') + mo;
  }
  /* la prochaine échéance (pour l'accueil) */
  function maintNext() {
    var m = store.get('maint', null); if (!m) return null;
    var best = null;
    maintDefs(m).forEach(function (d) {
      var st = maintState(m[d[0]] || {}); if (!st) return;
      var score = Math.min(st.kmLeft != null ? st.kmLeft / 15000 : 9, st.daysLeft != null ? st.daysLeft / 365 : 9);
      if (!best || score < best.score) best = { score: score, name: d[5], st: st, level: st.level };
    });
    if (!best) return null;
    best.short = leftText(best.st);
    return best;
  }
  function renderMaint() {
    var m = maintData(), odo = curKm();
    var h = '<div class="maint-head"><span>' + (odo != null ? 'Kilométrage : <b>' + fmtN(odo) + ' km</b>' : 'Kilométrage inconnu : ouvre l\'écran ordinateur de bord de CarCan') + '</span>' +
      '<span class="maint-tools"><button class="chip-btn press" id="m-engine">' + (engine() === 'diesel' ? 'Diesel' : 'Essence') + '</button>' +
      '<button class="chip-btn press" id="m-pdf">' + svg('files') + 'PDF</button></span></div><div class="maint-list">';
    maintDefs(m).forEach(function (d) {
      var it = m[d[0]], st = maintState(it), iv = ivText(it);
      var sub = !it.on ? 'Désactivé' : it.last ? 'Fait le ' + frDate(it.last.date) + (it.last.km != null ? ' à ' + fmtN(it.last.km) + ' km' : '') : 'Pas encore renseigné';
      h += '<div class="mrow press-soft' + (st ? ' lv' + st.level : '') + (it.on ? '' : ' off') + '" data-m="' + d[0] + '">' + svg(d[4]) +
        '<div class="mmain"><b>' + esc(d[1]) + '</b><span>' + sub + (iv ? ' · tous les ' + iv : '') + '</span>' + (st ? '<i class="mbar"><i style="width:' + Math.round(st.used * 100) + '%"></i></i>' : '') + '</div>' +
        '<div class="mleft">' + (st ? leftText(st) : it.on ? '—' : '') + '</div></div>';
    });
    h += '<div class="mrow madd press-soft" data-m="+">' + svg('plus') + '<div class="mmain"><b>Ajouter un entretien</b><span>Pneus, amortisseurs, climatisation, batterie…</span></div></div>';
    h += '</div>';
    var box = CK.on ? $('#ck-body') : $('#maintview');
    box.innerHTML = h;
    $$('.mrow', box).forEach(function (r) { tappable(r, function () { var k = r.getAttribute('data-m'); if (k === '+') maintSheet(null); else maintSheet(k); }); });
    tappable($('#m-pdf', box), maintPdf);
    tappable($('#m-engine', box), function () {
      openSheet('<div class="t-headline">Motorisation</div><div class="t-sub">Adapte la liste : filtre à gasoil pour un diesel, bougies pour un essence.</div>' +
        '<div class="sheet-actions"><button class="sbtn press" data-e="diesel">Diesel</button><button class="sbtn press" data-e="essence">Essence</button></div>', function (sh) {
        $$('[data-e]', sh).forEach(function (b) { if (b.getAttribute('data-e') === engine()) b.style.background = 'rgba(255,255,255,.22)'; b.onclick = function () { store.set('engine', b.getAttribute('data-e')); closeSheet(); renderMaint(); renderVehicleSummary(); }; });
      });
    });
  }
  /* fiche d'une opération (id = null : nouvel entretien libre) */
  function maintSheet(id) {
    var m = maintData(), isNew = id == null, it = isNew ? { km: 0, months: 12, on: true, last: null, hist: [], custom: true } : m[id];
    var def = isNew ? [null, '', 0, 12, 'wrench', ''] : maintDefs(m).filter(function (d) { return d[0] === id; })[0] || [id, it.name || id];
    var custom = !!it.custom, odo = curKm(), last = it.last || {};
    var hist = (it.hist || []).slice(-4).reverse().map(function (e) { return '<div class="mh">' + frDate(e.date) + (e.km != null ? ' · ' + fmtN(e.km) + ' km' : '') + '</div>'; }).join('');
    var h = '<div class="t-headline">' + (isNew ? 'Nouvel entretien' : esc(def[1])) + '</div>' +
      '<div class="t-sub">' + (custom ? 'Laisse un intervalle à 0 s\'il ne s\'applique pas.' : 'Au premier des deux termes atteint. Valeurs à vérifier dans ton carnet d\'entretien.') + '</div>' +
      (custom ? '<div class="mform"><label style="grid-column:1 / span 2">Nom<input type="text" id="m-name" maxlength="40" placeholder="Ex. : pneus avant, amortisseurs, recharge clim" value="' + esc(it.name || '') + '"></label></div>' : '') +
      (odo != null ? '<button class="sbtn wide press" id="m-today" style="margin-top:12px;width:100%;background:var(--green);color:#fff">Fait aujourd\'hui à ' + fmtN(odo) + ' km</button>' : '') +
      '<div class="mform">' +
      '<label>Fait le<input type="date" id="m-date" value="' + (last.date || todayIso()) + '"></label>' +
      '<label>À (km)<input type="number" inputmode="numeric" id="m-km" value="' + (last.km != null ? last.km : odo != null ? odo : '') + '"></label>' +
      '<label>Tous les (km, 0 = aucun)<input type="number" inputmode="numeric" id="m-ikm" value="' + it.km + '"></label>' +
      '<label>Ou tous les (mois)<input type="number" inputmode="numeric" id="m-imo" value="' + it.months + '"></label>' +
      (custom ? '<label style="grid-column:1 / span 2">Note (pièce, garage, prix…)<input type="text" id="m-note" maxlength="80" value="' + esc(it.note || '') + '"></label>' : '') +
      '</div>' + (hist ? '<div class="hdr" style="padding-left:0">Historique</div>' + hist : '') +
      '<div class="sheet-actions"><button class="sbtn press" data-act="save" style="background:rgba(255,255,255,.18)">Enregistrer</button>' +
      (isNew ? '<button class="sbtn press" data-act="cancel">Annuler</button>' : custom ? '<button class="sbtn danger press" data-act="del">Supprimer</button>' : '<button class="sbtn press" data-act="toggle">' + (it.on ? 'Désactiver' : 'Activer') + '</button>') + '</div>';
    openSheet(h, function (sh) {
      // le clavier de l'autoradio couvre le bas de l'écran : on remonte la feuille pendant la saisie
      $$('input', sh).forEach(function (el) {
        el.addEventListener('focus', function () { sh.style.top = (sh.offsetHeight / 2 + 8) + 'px'; });
        el.addEventListener('blur', function () { setTimeout(function () { if (!sh.contains(document.activeElement)) sh.style.top = ''; }, 50); });
      });
      function record(date, km, done) {
        var mm = maintData(), key = id, x;
        if (custom) {
          var nm = ($('#m-name', sh).value || '').trim();
          if (!nm) { toast('Donne un nom à cet entretien'); $('#m-name', sh).focus(); return; }
          if (isNew) { key = 'x' + Date.now(); x = { custom: true, created: Date.now(), on: true, last: null, hist: [] }; } else x = mm[key];
          x.name = nm; x.note = ($('#m-note', sh).value || '').trim();
        } else x = mm[key];
        if (done) {
          x.last = { date: date, km: km };
          x.hist = (x.hist || []).filter(function (e) { return !(e.date === date && e.km === km); }); x.hist.push({ date: date, km: km });
        }
        x.km = Math.max(0, parseInt($('#m-ikm', sh).value, 10) || 0); x.months = Math.max(0, parseInt($('#m-imo', sh).value, 10) || 0);
        x.on = true; mm[key] = x; maintSave(mm); closeSheet(); toast((x.name || def[1]) + ' enregistré');
      }
      var t = $('#m-today', sh); if (t) t.onclick = function () { record(todayIso(), odo, true); };
      $('[data-act="save"]', sh).onclick = function () {
        var date = $('#m-date', sh).value || todayIso(), km = parseInt($('#m-km', sh).value, 10);
        record(date, isNaN(km) ? null : km, true);
      };
      var tg = $('[data-act="toggle"]', sh); if (tg) tg.onclick = function () { var mm = maintData(); mm[id].on = !mm[id].on; maintSave(mm); closeSheet(); };
      var cc = $('[data-act="cancel"]', sh); if (cc) cc.onclick = closeSheet;
      var dl = $('[data-act="del"]', sh); if (dl) dl.onclick = function () {
        if (dl.getAttribute('data-sure')) { var mm = maintData(); var nm = mm[id].name; delete mm[id]; maintSave(mm); closeSheet(); toast(nm + ' supprimé'); }
        else { dl.setAttribute('data-sure', '1'); dl.textContent = 'Confirmer la suppression'; }
      };
    });
  }
  /* export PDF du carnet : le lanceur prépare les données, Android dessine le PDF (A4) et l'enregistre dans Téléchargements/Ibiza */
  function maintPdf() {
    var m = maintData(), odo = curKm(), items = [];
    maintDefs(m).forEach(function (d) {
      var it = m[d[0]], st = maintState(it), next = [];
      if (it.on && it.last) {
        if (it.km > 0 && it.last.km != null) next.push(fmtN(it.last.km + it.km) + ' km');
        if (it.months > 0 && it.last.date) next.push(frDate(addMonths(it.last.date, it.months)));
      }
      items.push({ name: d[1], on: !!it.on, custom: !!it.custom, note: it.note || '',
        last: it.last ? frDate(it.last.date) + (it.last.km != null ? ' · ' + fmtN(it.last.km) + ' km' : '') : '',
        interval: ivText(it), next: next.join(' ou '), left: st ? leftText(st) : '', level: st ? st.level : -1,
        hist: (it.hist || []).slice().reverse().map(function (e) { return frDate(e.date) + (e.km != null ? ' · ' + fmtN(e.km) + ' km' : ''); }) });
    });
    var rep = { title: 'Carnet d\'entretien', car: CAR.model, engine: engine() === 'diesel' ? 'Diesel' : 'Essence', date: frDate(new Date()),
      km: odo != null ? fmtN(odo) + ' km' : '', items: items, file: 'Entretien-' + String(CAR.name).replace(/[^A-Za-z0-9-]+/g, '-') + '-' + todayIso() + '.pdf' };
    if (!Bridge.exportPdf(JSON.stringify(rep))) toast('Export PDF indisponible');
  }
  Bridge.on('pdf', function (r) {
    if (!r || !r.ok) { toast('PDF : échec de l\'enregistrement' + (r && r.err ? ' (' + r.err + ')' : '')); return; }
    openSheet('<div class="t-headline">PDF enregistré</div><div class="t-sub">' + esc(r.where || '') + '</div>' +
      '<div class="sheet-actions"><button class="sbtn press" data-a="open" style="background:rgba(255,255,255,.18)">Ouvrir</button><button class="sbtn press" data-a="share">Partager</button></div>', function (sh) {
      $('[data-a="open"]', sh).onclick = function () { closeSheet(); Bridge.openPdf(); };
      $('[data-a="share"]', sh).onclick = function () { closeSheet(); Bridge.sharePdf(); };
    });
  });

  /* carte horloge : les 4 premières données disponibles (selon ce que le boîtier CAN fournit) */
  var CD = [
    ['outside', 'Ext.', function (v) { return fmtN(v, 1) + '°'; }, function (v) { return v <= 3; }],
    ['coolant', 'Eau', function (v) { return fmtN(v) + '°'; }, function (v) { return v > 110; }],
    ['oil', 'Huile', function (v) { return fmtN(v) + '°'; }, function (v) { return v > 130; }],
    ['battery', 'Batt.', function (v) { return fmtN(v, 1) + ' V'; }, function (v) { return v < 11.8; }],
    ['fuel', 'Carbu.', function (v) { return fmtN(v) + ' L'; }, function (v) { return v < 7; }],
    ['range', 'Auton.', function (v) { return fmtN(v) + ' km'; }, function (v) { return v < 60; }],
    ['rpm', 'Régime', function (v) { return fmtN(v); }, null],
    ['odo', 'Km', function (v) { return fmtN(v); }, null]
  ];
  function renderCd() {
    var have = CD.filter(function (c) { return car[c[0]] != null; });
    var rest = CD.filter(function (c) { return car[c[0]] == null && ['outside', 'battery', 'fuel', 'rpm'].indexOf(c[0]) >= 0; });
    var pick = have.concat(rest).slice(0, 4);
    pick.forEach(function (c, i) {
      var l = $('#cdl-' + i), b = $('#cd-' + i); if (!l || !b) return;
      l.textContent = c[1];
      var v = car[c[0]];
      b.textContent = v == null ? '—' : c[2](v);
      b.classList.toggle('warn', v != null && !!(c[3] && c[3](v)));
    });
  }
  Bridge.on('car', function (d) {
    d = d || {};
    Object.keys(d).forEach(function (k) { car[k] = d[k]; }); if (!car.at) car.at = Date.now();
    store.set('car', car);
    d = car;
    renderVehicleSummary(); if (current === 'vehicle' && vehView === 'trip') renderTrip(); if (CK.on || (current === 'vehicle' && vehView === 'maint')) renderMaint();
    renderCd();
  });
  var stopT;
  Bridge.on('speed', function (d) {
    var k = d && d.kmh ? d.kmh : 0, card = $('#card-clock');
    $('#c-kmh').textContent = k;
    if (k >= 4) { clearTimeout(stopT); card.classList.add('moving'); }
    else { clearTimeout(stopT); stopT = setTimeout(function () { card.classList.remove('moving'); }, 4000); }
  });
  /* lecture automatique de CarCan : ~8 s après le démarrage, puis toutes les 15 min si on est sur l'accueil
     (jamais par-dessus CarPlay ou une autre appli). v1.10 : CarCan s'ouvre sous un rideau (image figée du lanceur), on ne le voit plus. */
  var visible = true, lastAutoRead = 0, bootRead = false;
  Bridge.on('pause', function () { visible = false; });
  Bridge.on('resume', function () { visible = true; });
  function autoRead(force) {
    var mode = store.get('autoread', 'boot');
    if (mode === 'off' || Bridge.mock || !Bridge.canRefreshPossible()) return;
    if (!visible || current !== 'home' || $('#sheet-scrim').classList.contains('on')) return;
    if (!force && (mode !== '15' || Date.now() - lastAutoRead < 15 * 60000)) return;
    lastAutoRead = Date.now(); Bridge.canRefresh();
  }
  setTimeout(function () { if (bootRead) autoRead(true); }, 8000);   // sinon c'est l'écran « Prêt à partir » qui lance la lecture
  setInterval(function () { autoRead(false); }, 60000);
  Bridge.on('turn', function (t) { var el = $('#turn'); el.classList.toggle('l', !!(t && t.left)); el.classList.toggle('r', !!(t && t.right)); });
  Bridge.on('lights', function (t) { $('#turn').classList.toggle('lights', !!(t && t.on)); });   // Clio : témoin des feux
  Bridge.on('apps', function () { loadApps(); renderDock(); renderQuick(); renderApps(); alignGlass(); });
  Bridge.on('status', function (s) { $('#st-bt').classList.toggle('on', !!s.bt); $('#st-wifi').classList.toggle('on', !!s.wifi); });
  Bridge.on('resume', function (d) { if (d && d.silent) return; if (current !== 'home') showPage('home'); introSpin(); });   // retour de la lecture CarCan masquée : on reste où l'on était
  Bridge.on('home', function () { if (current !== 'home') showPage('home'); else introSpin(); });
  window.addEventListener('resize', function () { fit(); renderApps(); alignGlass(); setTimeout(refitFrames, 400); fitTexts(true); });
  (function () {
    // seuls les changements qui touchent un texte ajustable relancent l'ajustement (pas la barre de progression, ni les boutons)
    var mo = new MutationObserver(function (recs) {
      for (var i = 0; i < recs.length; i++) {
        var t = recs[i].target; if (t && t.nodeType === 3) t = t.parentNode;
        if (t && t.closest && t.closest(FIT_WATCH)) { fitTexts(); return; }
      }
    });
    ['#page-home', '#apps-track'].forEach(function (sel) { var el = $(sel); if (el) mo.observe(el, { childList: true, characterData: true, subtree: true }); });
    var lastClass = root.className;
    new MutationObserver(function () { if (root.className !== lastClass) { lastClass = root.className; fitTexts(true); } }).observe(root, { attributes: true, attributeFilter: ['class'] });
  })();
  boot();
  /* v1.19 : signe de vie pour le chien de garde Android, cadencé par l'affichage (s'arrête si l'écran se fige) */
  (function () {
    if (!window.Native || typeof window.Native.alive !== 'function') return;
    // v1.22 : plus aucune minuterie ici : c'est Android qui interroge la page (au retour sur le lanceur, puis toutes les 5 min)
  })();
})();
