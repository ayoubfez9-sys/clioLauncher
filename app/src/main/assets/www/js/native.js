/* Pont vers l'app Android (window.Native). Hors Android : données de démo. */
(function () {
  var N = window.Native || null;
  var listeners = {};
  function on(type, fn) { (listeners[type] = listeners[type] || []).push(fn); }
  function emit(type, data) { (listeners[type] || []).forEach(function (f) { try { f(data); } catch (e) { console.error(e); } }); }
  /* appelé par Android : window.onNative('media', '{...}') */
  window.onNative = function (type, json) {
    var d = json; try { if (typeof json === 'string') d = JSON.parse(json); } catch (e) {}
    emit(type, d);
  };
  function parse(s, dflt) { try { return JSON.parse(s); } catch (e) { return dflt; } }

  var mock = !N;
  var demoApps = [
    ['com.ex.navi', 'Navigation'], ['com.ex.music', 'Musique'], ['com.ex.bt', 'Bluetooth'], ['com.ex.zlink', 'CarPlay'],
    ['com.ex.radio', 'Radio'], ['com.ex.video', 'Vidéo'], ['com.android.settings', 'Paramètres'], ['com.ex.eq', 'Égaliseur'],
    ['com.ex.canbus', 'Voiture'], ['com.ex.files', 'Fichiers'], ['com.android.chrome', 'Chrome'], ['com.waze', 'Waze'],
    ['com.spotify.music', 'Spotify'], ['com.ex.aux', 'AUX'], ['com.ex.usb', 'USB'], ['com.ex.gallery', 'Galerie'],
    ['com.ex.calc', 'Calculatrice'], ['com.ex.clock', 'Horloge'], ['com.ex.weather', 'Météo'], ['com.ex.store', 'Play Store']
  ].map(function (a) { return { pkg: a[0], cls: a[0] + '.Main', label: a[1], icon: '' }; });

  var demo = {
    media: { playing: true, title: 'Midnight City', artist: 'M83', source: 'Bluetooth', pos: 72, dur: 243 },
    vehicle: { doors: { fl: false, fr: false, rl: false, rr: false, trunk: false, hood: false }, radar: null, reverse: false },
    perms: { notif: false, access: false, overlay: false, home: false }
  };

  var demoCar = { oil: 92, coolant: 89, outside: 11, battery: 14.2, insp_km: 12400, insp_days: 210, oil_km: 8300, oil_days: 140, range: 520, cons: 5.8, avg: 47, odo: 84213, src: 'démo' };
  var demoLearn = { ev: 'base', on: false, t: Date.now() };
  /* v1.27 : démo de « Apprendre l'ordinateur de bord » (ce que renvoie Android après la capture d'une page) */
  var demoCanApp = { has: false, pkg: '', label: '', fields: 0, access: true, hcn: false, gestures: true };
  var demoCanLearn = { pkg: 'com.ex.canbus', label: 'Voiture', taps: 1, pages: [{ cands: [
    { i: 0, label: 'Voltage de batterie', value: '14.3', unit: 'V', key: 'battery' }, { i: 1, label: 'Huile résiduelle', value: '38', unit: 'L', key: 'fuel' },
    { i: 2, label: 'Kilométrage', value: '84213', unit: 'KM', key: 'odo' }, { i: 3, label: 'Temp. extérieure', value: '11.5', unit: '℃', key: 'outside' },
    { i: 4, label: 'La vitesse du moteur', value: '831', unit: 'RPM', key: 'rpm' }, { i: 5, label: 'Trip A', value: '12.4', unit: 'km', key: null },
    { i: 6, label: '', value: '3', unit: '', key: null }] }] };
  var API = {
    mock: mock,
    on: on,
    apps: function () {
      if (mock) return demoApps;
      var list = parse(N.getApps(), []);
      var v = Date.now();
      list.forEach(function (a) { a.icon = 'https://appassets.androidplatform.net/icons/' + encodeURIComponent(a.pkg) + '/' + encodeURIComponent(a.cls) + '.png?v=' + v; });
      return list;
    },
    launch: function (a) { if (mock) { API.toast('Ouverture : ' + a.label); return; } N.launch(a.pkg, a.cls); },
    state: function () { return mock ? demo : parse(N.getState(), demo); },
    media: function (cmd) {
      if (mock) {
        if (cmd === 'toggle') demo.media.playing = !demo.media.playing;
        if (cmd === 'next' || cmd === 'prev') demo.media.pos = 0;
        emit('media', demo.media); return;
      }
      N.media(cmd);
    },
    openAccess: function () { if (mock) { API.toast('Réglage système : access'); return 'opened'; } return N.openAccess ? N.openAccess() : (N.openSystem('access'), 'opened'); },
    accessHint: function () { return mock ? !!window.__a13 : (N.accessHint ? N.accessHint() : false); },   // v1.27 : Android 13+, « paramètre restreint »
    accessCommand: function () { return mock || !N.accessCommand ? 'adb shell pm grant fr.ibiza.launcher android.permission.WRITE_SECURE_SETTINGS' : N.accessCommand(); },
    openSystem: function (which) { if (mock) { API.toast('Réglage système : ' + which); return; } N.openSystem(which); },
    setPref: function (k, v) { if (!mock && N.setPref) N.setPref(k, String(v)); },
    simulate: function (vehicle) { if (mock) { emit('vehicle', vehicle); return; } N.simulate(JSON.stringify(vehicle)); },
    diag: function () { return mock ? 'Mode démo : pas de journal.' : N.getDiag(); },
    diagExport: function () { return mock ? '' : N.exportDiag(); },
    diagClear: function () { if (!mock) N.clearDiag(); },
    toast: function (t) { emit('toast', t); },
    iconDir: function () { return mock ? '/sdcard/Android/data/fr.ibiza.launcher/files/icons' : N.iconDir(); },
    reloadIcons: function () { if (mock) emit('apps', {}); else N.reloadIcons(); },
    appList: function () { return mock ? '/sdcard/Android/data/fr.ibiza.launcher/files/icons/_liste_des_applis.txt' : (N.exportAppList ? N.exportAppList() : ''); },
    launchPkg: function (pkg) { if (mock) { API.toast('Ouverture : ' + pkg); return; } if (N.launchPkg) N.launchPkg(pkg); },
    canScan: function () { return mock ? 'Mode démo : pas d\'analyse.' : (N.canScan ? N.canScan() : ''); },
    canExport: function () { return mock ? '' : (N.canExport ? N.canExport() : ''); },
    canLogs: function () { return mock ? false : (N.canLogs ? N.canLogs() : false); },
    canLive: function () { return mock ? false : (N.canLive ? N.canLive() : false); },
    // v1.23 : packs voiture
    packsSupported: function () { return mock || !!N.packList; },
    packList: function () { return mock ? (window.__demoPacks || { packs: [], active: '', waiting: 0, builtin: true, folder: '/sdcard/Android/data/fr.ibiza.launcher/files/packs' }) : parse(N.packList ? N.packList() : '{}', { packs: [] }); },
    packUse: function (id) { if (mock) { API.toast('Pack affiché : ' + (id || 'voiture de l\'appli')); return; } if (N.packUse) N.packUse(id || ''); },
    packDelete: function (id) { return mock ? true : (N.packDelete ? N.packDelete(id) : false); },
    // v1.24.1 : pack réellement actif côté Android (null = lanceur trop ancien / démo) et rechargement sans cache
    packShown: function () { return mock || !N.packShown ? null : String(N.packShown() || ''); },
    packReload: function () { if (!mock && N.packReload) N.packReload(); },
    packImport: function () { if (mock) { emit('pack', { ok: false, cancel: true }); return; } if (N.packImport) N.packImport(); },
    packImportFolder: function () { if (mock) { emit('pack', { ok: false, error: 'aucun fichier .zip dans le dossier (démo)' }); return; } if (N.packImportFolder) N.packImportFolder(); },
    // v1.24 : rapport de compatibilité pour le QR code ({z: rapport compressé pour l'adresse, r: rapport lisible})
    report: function (js) {
      if (mock) {
        var r = { f: 1, l: 'démo/0/fr.ibiza.launcher', d: 'Démo|Navigateur|Démo|demo|demo|demo', a: '14/34', b: 'navigateur', s: 'navigateur', k: navigator.hardwareConcurrency || 4,
                  e: screen.width + 'x' + screen.height + '@' + Math.round(96 * (window.devicePixelRatio || 1)), c: [['CarCan', 'com.hcn.autocan', '2.3']], n: { fr: 0, live: 0, logs: 0 }, p: {} };
        var x = parse(js, {}); for (var k in x) if (!(k in r)) r[k] = x[k];
        var b = btoa(unescape(encodeURIComponent(JSON.stringify(r)))).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
        return { z: 'j' + b, r: r };
      }
      return parse(N.report ? N.report(js) : '{}', null);
    },
    /* v1.25 : apprentissage du véhicule ; en démo, une fausse détection pour voir l'écran */
    learnStart: function () { if (mock) { demoLearn.t = Date.now(); return { ok: true, access: true }; } return parse(N.learnStart(), {}); },
    learnStep: function (ev, on) { if (mock) { demoLearn.ev = ev; demoLearn.on = on; demoLearn.t = Date.now(); return; } N.learnStep(ev, !!on); },
    learnStatus: function () {
      if (mock) { var ms = Date.now() - demoLearn.t, det = demoLearn.ev !== 'base' && ms > 1500;
        return { learning: true, ev: demoLearn.ev, on: demoLearn.on, ms: ms, count: det && demoLearn.on ? 1 : 0, quiet: det ? ms - 1500 : 0, changes: det && demoLearn.on ? ['propriété persist.vendor.cem.cardoor1 : 1 → 2'] : [], reverted: det && !demoLearn.on ? 1 : 0, total: demoLearn.on ? 0 : 1 }; }
      return parse(N.learnStatus(), {});
    },
    learnFinish: function () { if (mock) return { labels: { fl: 'propriété persist.vendor.cem.cardoor1 (1 → 2)', fr: 'propriété persist.vendor.cem.cardoor2 (1 → 2)' }, tried: ['fl', 'fr', 'rl', 'reverse'], alts: {}, sources: { P: 1200, S: 40 } }; return parse(N.learnFinish(), {}); },
    learnSave: function () { return mock ? 'ok' : N.learnSave(); },
    learnClear: function () { if (!mock) N.learnClear(); },
    learnHasMap: function () { return mock ? false : (N.learnHasMap ? N.learnHasMap() : false); },
    doorInfo: function () { if (mock) return { known: false, learned: false, src: '' }; return N.doorInfo ? parse(N.doorInfo(), {}) : {}; },
    learnSupported: function () { return mock || !!(N && N.learnStart); },
    qrExport: function (kind) {
      if (mock) { var p = []; for (var i = 1; i <= (kind === 'learn' ? 1 : 6); i++) p.push('j=demo1.' + i + '.' + (kind === 'learn' ? 1 : 6) + '.' + 'AAAA'.repeat(150)); return { parts: p, bytes: 9000 }; }
      return parse(N.qrExport(kind), {});
    },
    /* v1.27 : « Apprendre l'ordinateur de bord » (appli CAN de n'importe quel poste) */
    canAppSupported: function () { return mock || !!(N && N.canAppStart); },
    canAppInfo: function () { if (mock) return demoCanApp; return N.canAppInfo ? parse(N.canAppInfo(), {}) : { has: false, hcn: true }; },
    canAppList: function () { if (mock) return demoApps.map(function (a) { return { pkg: a.pkg, label: a.label, sug: a.pkg === 'com.ex.canbus' }; }).sort(function (a, b) { return (b.sug ? 1 : 0) - (a.sug ? 1 : 0); }); return parse(N.canAppList(), []); },
    canAppStart: function (pkg) { if (mock) { setTimeout(function () { emit('canlearn', demoCanLearn); }, 1200); return { ok: true }; } return parse(N.canAppStart(pkg), {}); },
    canAppPending: function () { if (mock || !N.canAppPending) return null; var s = N.canAppPending(); return s ? parse(s, null) : null; },
    canAppSave: function (m) { if (mock) { var n = 0; (m.pages || []).forEach(function (p) { for (var k in p) n++; }); demoCanApp = { has: true, pkg: demoCanLearn.pkg, label: demoCanLearn.label, fields: n, access: true, hcn: false, gestures: true }; window.__lastCanMap = m; return 'ok'; } return N.canAppSave(JSON.stringify(m)); },
    canAppClear: function () { if (mock) { demoCanApp = { has: false, pkg: '', label: '', fields: 0, access: true, hcn: false, gestures: true }; return; } if (N.canAppClear) N.canAppClear(); },
    isTs10: function () { return mock ? false : (N.isTs10 ? N.isTs10() : false); },
    ts10Probe: function () { return mock ? 'Mode démo : pas de sondage.' : (N.ts10Probe ? N.ts10Probe() : ''); },
    tryRoot: function () { if (mock) { API.toast('Essai root (démo)'); return; } if (N.tryRoot) N.tryRoot(); },
    canRefresh: function () { if (mock) { API.toast('Lecture CarCan (démo)'); return; } if (N.canRefresh) N.canRefresh(); },
    canRefreshPossible: function () { return mock ? true : (N.canRefreshPossible ? N.canRefreshPossible() : false); },
    car: function () { return mock ? demoCar : parse(N.getCar ? N.getCar() : '{}', {}); },
    /* v1.11 : carnet d'entretien en PDF (dessiné par Android, résultat renvoyé par l'événement « pdf ») */
    exportPdf: function (json) {
      if (mock) { window.__lastPdf = json; setTimeout(function () { emit('pdf', { ok: true, where: 'Téléchargements › Ibiza › ' + JSON.parse(json).file }); }, 300); return true; }
      if (!N.exportPdf) return false; N.exportPdf(json); return true;
    },
    testMoveAlert: function () { if (mock) { API.toast('Alerte en roulant (démo) : visible sur l\'autoradio'); return; } if (N.testMoveAlert) N.testMoveAlert(); },
    openPdf: function () { if (mock) { API.toast('Ouverture du PDF (démo)'); return; } if (N.openPdf) N.openPdf(); },
    sharePdf: function () { if (mock) { API.toast('Partage du PDF (démo)'); return; } if (N.sharePdf) N.sharePdf(); }
  };
  if (mock) {
    setTimeout(function () { emit('car', demoCar); }, 300);
    setInterval(function () {
      if (demo.media.playing) { demo.media.pos = (demo.media.pos + 1) % demo.media.dur; emit('media', demo.media); }
    }, 1000);
  }
  window.Bridge = API;
})();
