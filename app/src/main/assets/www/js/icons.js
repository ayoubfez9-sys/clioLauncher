/* Glyphes dessinés pour le lanceur (24x24, style trait arrondi). */
(function () {
  var S = 'fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"';
  var F = 'fill="currentColor"';
  var G = {
    nav: '<path ' + F + ' d="M20.5 3.5 3.8 10.6c-.9.4-.8 1.7.2 1.9l6.6 1.1 1.1 6.6c.2 1 1.5 1.1 1.9.2z"/>',
    music: '<path ' + S + ' d="M9 18V5.5l11-2.2v12.4"/><circle ' + F + ' cx="6.5" cy="18" r="3"/><circle ' + F + ' cx="17.5" cy="15.7" r="3"/>',
    phone: '<path ' + F + ' d="M6.6 3.2c.6-.3 1.3-.1 1.6.5l1.6 3.2c.3.6.1 1.2-.3 1.6L8.1 9.8a12 12 0 0 0 6.1 6.1l1.3-1.4c.4-.4 1.1-.6 1.6-.3l3.2 1.6c.6.3.8 1 .5 1.6l-.9 2c-.4.8-1.2 1.3-2.1 1.2C10.4 20 4 13.6 3.5 6.2c-.1-.9.4-1.7 1.2-2.1z"/>',
    link: '<rect ' + S + ' x="7" y="2.5" width="10" height="19" rx="2.6"/><path ' + S + ' d="M10.5 18.5h3"/><path ' + S + ' d="M3 8.5a6 6 0 0 0 0 7M21 8.5a6 6 0 0 1 0 7"/>',
    radio: '<circle ' + F + ' cx="12" cy="12" r="2.4"/><path ' + S + ' d="M8.2 8.2a5.4 5.4 0 0 0 0 7.6M15.8 8.2a5.4 5.4 0 0 1 0 7.6M5.2 5.2a9.6 9.6 0 0 0 0 13.6M18.8 5.2a9.6 9.6 0 0 1 0 13.6"/>',
    video: '<rect ' + S + ' x="2.5" y="5" width="19" height="14" rx="3"/><path ' + F + ' d="M10 9.2v5.6c0 .4.4.6.7.4l4.6-2.8c.3-.2.3-.6 0-.8l-4.6-2.8c-.3-.2-.7 0-.7.4z"/>',
    settings: '<circle ' + S + ' cx="12" cy="12" r="3.2"/><path ' + S + ' d="M12 2.8v2.4M12 18.8v2.4M21.2 12h-2.4M5.2 12H2.8M18.5 5.5l-1.7 1.7M7.2 16.8l-1.7 1.7M18.5 18.5l-1.7-1.7M7.2 7.2 5.5 5.5"/>',
    eq: '<path ' + S + ' d="M5 4v16M12 4v16M19 4v16"/><rect ' + F + ' x="2.5" y="13" width="5" height="3" rx="1.5"/><rect ' + F + ' x="9.5" y="7" width="5" height="3" rx="1.5"/><rect ' + F + ' x="16.5" y="11" width="5" height="3" rx="1.5"/>',
    car: '<path ' + S + ' d="M3 15.5v-2.2c0-.8.4-1.5 1.1-1.9L7 9.8l2-3c.4-.5 1-.8 1.6-.8h4.2c.6 0 1.1.3 1.5.7l2.4 3.1 1.6.6c.8.3 1.3 1 1.3 1.9v3.2"/><path ' + S + ' d="M3 15.5h1.5M9.5 15.5h5M19.5 15.5H21"/><circle ' + S + ' cx="7" cy="16" r="2.2"/><circle ' + S + ' cx="17" cy="16" r="2.2"/><path ' + S + ' d="M7.5 9.8h11"/>',
    files: '<path ' + S + ' d="M3 7.5c0-1.1.9-2 2-2h4l2 2.2h8c1.1 0 2 .9 2 2V17c0 1.1-.9 2-2 2H5c-1.1 0-2-.9-2-2z"/>',
    globe: '<circle ' + S + ' cx="12" cy="12" r="9"/><path ' + S + ' d="M3 12h18M12 3c2.5 2.6 3.6 5.6 3.6 9S14.5 18.4 12 21c-2.5-2.6-3.6-5.6-3.6-9S9.5 5.6 12 3z"/>',
    grid: '<rect ' + F + ' x="3.5" y="3.5" width="7" height="7" rx="2"/><rect ' + F + ' x="13.5" y="3.5" width="7" height="7" rx="2"/><rect ' + F + ' x="3.5" y="13.5" width="7" height="7" rx="2"/><rect ' + F + ' x="13.5" y="13.5" width="7" height="7" rx="2"/>',
    dash: '<rect ' + F + ' x="3" y="3.5" width="10.5" height="17" rx="2.5"/><rect ' + F + ' x="15.5" y="3.5" width="5.5" height="7.5" rx="2"/><rect ' + F + ' x="15.5" y="13" width="5.5" height="7.5" rx="2"/>',
    play: '<path ' + F + ' d="M7 4.6v14.8c0 .9 1 1.4 1.7.9l11.2-7.4c.7-.4.7-1.4 0-1.8L8.7 3.7C8 3.2 7 3.7 7 4.6z"/>',
    pause: '<rect ' + F + ' x="5.5" y="4" width="4.6" height="16" rx="1.4"/><rect ' + F + ' x="13.9" y="4" width="4.6" height="16" rx="1.4"/>',
    next: '<path ' + F + ' d="M3 6.2v11.6c0 .8.9 1.2 1.5.7L12 12.8v5c0 .8.9 1.2 1.5.7l7.3-5.8c.5-.4.5-1.1 0-1.5l-7.3-5.8c-.6-.5-1.5-.1-1.5.7v5L4.5 5.5C3.9 5 3 5.4 3 6.2z"/>',
    prev: '<path ' + F + ' d="M21 6.2v11.6c0 .8-.9 1.2-1.5.7L12 12.8v5c0 .8-.9 1.2-1.5.7l-7.3-5.8c-.5-.4-.5-1.1 0-1.5l7.3-5.8c.6-.5 1.5-.1 1.5.7v5l7.5-5.6c.6-.5 1.5-.1 1.5.7z"/>',
    bt: '<path ' + S + ' d="m7 7.5 10 9-5 4.5V3l5 4.5-10 9"/>',
    wifi: '<path ' + S + ' d="M2.5 9a14 14 0 0 1 19 0M5.8 12.5a9 9 0 0 1 12.4 0M9.1 16a4.2 4.2 0 0 1 5.8 0"/><circle ' + F + ' cx="12" cy="19.3" r="1.4"/>',
    door: '<path ' + S + ' d="M4 20h16M6 20V5.5C6 4.7 6.7 4 7.5 4H14l4 3.5V20"/><path ' + S + ' d="M14 4v16"/><circle ' + F + ' cx="11.5" cy="12.5" r="1.1"/>',
    back: '<path ' + S + ' d="M15 4.5 7.5 12l7.5 7.5"/>',
    chevron: '<path ' + S + ' d="m9 5 7 7-7 7"/>',
    radar: '<path ' + S + ' d="M4 10a11 11 0 0 1 16 0M7 13.2a6.6 6.6 0 0 1 10 0"/><circle ' + F + ' cx="12" cy="17" r="2.2"/>',
    check: '<path ' + S + ' d="m5 12.5 4.5 4.5L19 7.5"/>',
    plus: '<path ' + S + ' d="M12 5v14M5 12h14"/>',
    lock: '<rect ' + S + ' x="5" y="10.5" width="14" height="10" rx="2.5"/><path ' + S + ' d="M8 10.5V8a4 4 0 0 1 8 0v2.5"/>',
    hood: '<path ' + S + ' d="M3 16.5h18M5 16.5 7 10h10l2 6.5M7 10l2-4h6l2 4"/>',
    trunk: '<path ' + S + ' d="M4 18h16M5 18l1.5-8h11L19 18M6.5 10 9 5.5h6L17.5 10"/>',
    home: '<path ' + S + ' d="M3.5 11 12 4l8.5 7M6 9.5V20h12V9.5"/>',
    sun: '<circle ' + S + ' cx="12" cy="12" r="4"/><path ' + S + ' d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M5.3 18.7l1.4-1.4M17.3 6.7l1.4-1.4"/>',
    temp: '<path ' + S + ' d="M10 14.5V5a2 2 0 0 1 4 0v9.5a4 4 0 1 1-4 0z"/><circle ' + F + ' cx="12" cy="17.6" r="1.8"/>',
    wrench: '<path ' + S + ' d="M14.7 6.3a4.5 4.5 0 0 0-6 5.6L3.5 17.1a1.8 1.8 0 0 0 2.6 2.6l5.2-5.2a4.5 4.5 0 0 0 5.6-6l-2.8 2.8-2.3-.4-.4-2.3z"/>',
    fuel: '<path ' + S + ' d="M4.5 20.5V5.5c0-1.1.9-2 2-2h5c1.1 0 2 .9 2 2v15M3 20.5h12M4.5 10h9M13.5 8l3 2.5V17a1.5 1.5 0 0 0 3 0V8.5L17 6"/>',
    battery: '<rect ' + S + ' x="3" y="7" width="18" height="12" rx="2.5"/><path ' + S + ' d="M7 7V5M17 7V5M6.5 13h3M14.5 13h3M16 11.5v3"/>',
    gauge: '<path ' + S + ' d="M4 17a8.5 8.5 0 1 1 16 0"/><path ' + S + ' d="m12 14 4-5"/><circle ' + F + ' cx="12" cy="14.2" r="1.6"/>',
    qr: '<rect ' + S + ' x="3" y="3" width="7" height="7" rx="1.5"/><rect ' + S + ' x="14" y="3" width="7" height="7" rx="1.5"/><rect ' + S + ' x="3" y="14" width="7" height="7" rx="1.5"/><path ' + S + ' d="M14 14h3v3M21 14v.01M17 21h4v-4M14 18v3"/>',
    xmark: '<path ' + S + ' d="M6.5 6.5l11 11M17.5 6.5l-11 11"/>',
    brush: '<path ' + S + ' d="M19.5 3.5c-3.6 1.6-7 4.7-9.4 8.4l2 2c3.7-2.4 6.8-5.8 8.4-9.4.3-.7-.3-1.3-1-1z"/><path ' + S + ' d="M10.1 11.9c-2-.3-3.6 1.2-3.8 3.1-.2 1.9-1.3 3-3.3 3.5 3.2 1.6 7.6 1.2 8.9-1.6.6-1.3.3-2.8-1.8-5z"/>',
    info: '<circle ' + S + ' cx="12" cy="12" r="9"/><path ' + S + ' d="M12 11v5.5"/><circle ' + F + ' cx="12" cy="7.6" r="1.25"/>',
    bug: '<rect ' + S + ' x="7" y="7" width="10" height="13" rx="5"/><path ' + S + ' d="M12 7V20M3 13h4M17 13h4M4 7.5l3 2M20 7.5l-3 2M4 19l3-2M20 19l-3-2M9 4.5l1.5 2M15 4.5l-1.5 2"/>'
  };
  function svg(name, cls) {
    return '<svg class="ic ' + (cls || '') + '" viewBox="0 0 24 24" aria-hidden="true">' + (G[name] || G.grid) + '</svg>';
  }
  /* Thèmes d'icônes d'apps (squircles, dégradés originaux). */
  var THEMES = {
    nav:      { g: ['#3aa0ff', '#0a5cd8'], glyph: 'nav' },
    music:    { g: ['#ff6a88', '#e0218a'], glyph: 'music' },
    phone:    { g: ['#4ce07a', '#14a84a'], glyph: 'phone' },
    link:     { g: ['#3b3d44', '#16171b'], glyph: 'link' },
    radio:    { g: ['#ffb340', '#ff6a1a'], glyph: 'radio' },
    video:    { g: ['#6b7bff', '#3a3fd6'], glyph: 'video' },
    settings: { g: ['#9a9aa2', '#5b5b63'], glyph: 'settings' },
    eq:       { g: ['#c06bff', '#7b2fe0'], glyph: 'eq' },
    car:      { g: ['#c3d64a', '#7d9411'], glyph: 'car' },
    files:    { g: ['#5ec8ff', '#1f8fe0'], glyph: 'files' },
    globe:    { g: ['#5ad2c8', '#178f88'], glyph: 'globe' },
    launcher: { g: ['#2b2d33', '#121316'], glyph: 'dash' }
  };
  /* Reconnaissance des apps du poste par libellé / paquet. */
  var RULES = [
    ['link', /carplay|zlink|autokit|tlink|carlink|android ?auto|phonelink|easyconn|carbit|autolink|hicar|smartlink/i],
    ['nav', /maps|waze|navi|gps|igo|sygic|here we|osmand|magic ?earth/i],
    ['radio', /radio|\bfm\b|tuner/i],
    ['phone', /bluetooth|\bbt\b|phone|t[ée]l[ée]phone|dial|appel/i],
    // gestionnaire de fichiers AVANT musique : « com.mediatek.filemanager », « Media Manager »… ne sont pas des lecteurs
    ['files', /file|fichier|explorer|explorateur|gestionnaire|documents/i],
    ['music', /music|musique|spotify|deezer|lecteur audio|audio ?player|music ?player|\bmp3\b|poweramp|usb ?audio/i],
    ['video', /vid[ée]o|movie|film|youtube/i],
    ['eq', /\beq\b|[ée]galiseur|equalizer|dsp|sound|son\b/i],
    ['car', /\bcar\b|voiture|v[ée]hicule|vehicle|canbus|\bcan\b|ibiza/i],
    ['settings', /settings|param[èe]tres|r[ée]glages|setting/i],
    ['globe', /chrome|browser|navigateur|web/i]
  ];
  function themeFor(app) {
    var s = (app.label || '') + ' ' + (app.pkg || '');
    for (var i = 0; i < RULES.length; i++) if (RULES[i][1].test(s)) return RULES[i][0];
    return null;
  }
  function appIcon(app, size) {
    var t = app.theme ? THEMES[app.theme] : null;
    var st = 'width:' + size + 'px;height:' + size + 'px;';
    if (t) {
      return '<div class="aicon" style="' + st + 'background:linear-gradient(180deg,' + t.g[0] + ',' + t.g[1] + ')">' + svg(t.glyph, 'aicon-g') + '</div>';
    }
    var letter = (app.label || '?').charAt(0).toUpperCase();
    if (!app.icon) return '<div class="aicon aicon-raw noimg" style="' + st + '"><b>' + letter + '</b></div>';
    // v1.20 : icône perso (icons/<paquet>.png) affichée en pleine tuile, sans cadre gris
    return '<div class="aicon aicon-raw' + (app.custom ? ' aicon-custom' : '') + '" style="' + st + '"><img src="' + app.icon + '" alt="" onerror="this.parentNode.className+=\' noimg\'"><b>' + letter + '</b></div>';
  }
  window.Icons = { svg: svg, appIcon: appIcon, themeFor: themeFor, THEMES: THEMES };
})();
