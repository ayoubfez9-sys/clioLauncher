/* Ressorts « à la Apple » : réponse (s) + amortissement, interruptibles,
   démarrent toujours de la valeur affichée et héritent de la vitesse. */
(function () {
  var active = [];
  var running = false;
  var last = 0;
  var reduce = false;
  try { reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches; } catch (e) {}

  function Spring(value, opts) {
    opts = opts || {};
    this.x = value; this.v = 0; this.target = value;
    this.response = opts.response || 0.35;
    this.damping = opts.damping == null ? 1 : opts.damping;
    this.onUpdate = opts.onUpdate || null;
    this.onRest = null;
    this.precision = opts.precision || 0.01;
    this.live = false;
  }
  Spring.prototype.to = function (target, o) {
    o = o || {};
    if (o.response) this.response = o.response;
    if (o.damping != null) this.damping = o.damping;
    if (o.velocity != null) this.v = o.velocity;
    this.onRest = o.onRest || null;
    this.target = target;
    if (reduce) { this.x = target; this.v = 0; this._emit(); if (this.onRest) this.onRest(); return this; }
    if (!this.live) { this.live = true; active.push(this); }
    kick();
    return this;
  };
  Spring.prototype.set = function (value) {
    this.stop(); this.x = value; this.target = value; this.v = 0; this._emit(); return this;
  };
  Spring.prototype.stop = function () {
    if (this.live) { this.live = false; var i = active.indexOf(this); if (i >= 0) active.splice(i, 1); }
    return this;
  };
  Spring.prototype._emit = function () { if (this.onUpdate) this.onUpdate(this.x, this); };
  Spring.prototype.step = function (dt) {
    var k = Math.pow(2 * Math.PI / this.response, 2);
    var c = 4 * Math.PI * this.damping / this.response;
    var n = Math.max(1, Math.ceil(dt / 0.004)), h = dt / n;
    for (var i = 0; i < n; i++) {
      var a = -k * (this.x - this.target) - c * this.v;
      this.v += a * h; this.x += this.v * h;
    }
    if (Math.abs(this.v) < this.precision && Math.abs(this.x - this.target) < this.precision) {
      this.x = this.target; this.v = 0; this._emit(); this.stop();
      var r = this.onRest; this.onRest = null; if (r) r();
      return;
    }
    this._emit();
  };
  function frame(t) {
    var dt = last ? Math.min(0.05, (t - last) / 1000) : 1 / 60;
    last = t;
    var list = active.slice();
    for (var i = 0; i < list.length; i++) list[i].step(dt);
    if (active.length) requestAnimationFrame(frame); else { running = false; last = 0; }
  }
  function kick() { if (!running) { running = true; last = 0; requestAnimationFrame(frame); } }

  /* Projection de l'élan (fonction utilisée par Apple). */
  function project(v, rate) { rate = rate || 0.998; return (v / 1000) * rate / (1 - rate); }
  function rubber(over, dim, c) { c = c || 0.55; return (over * dim * c) / (dim + c * Math.abs(over)); }

  /* Suivi de vitesse sur les derniers événements de pointeur. */
  function Tracker() { this.h = []; }
  Tracker.prototype.add = function (x, y) {
    var t = performance.now(); this.h.push({ x: x, y: y, t: t });
    while (this.h.length > 2 && t - this.h[0].t > 100) this.h.shift();
  };
  Tracker.prototype.velocity = function () {
    var h = this.h; if (h.length < 2) return { x: 0, y: 0 };
    var a = h[0], b = h[h.length - 1], dt = (b.t - a.t) / 1000;
    if (dt <= 0) return { x: 0, y: 0 };
    if (performance.now() - b.t > 80) return { x: 0, y: 0 };
    return { x: (b.x - a.x) / dt, y: (b.y - a.y) / dt };
  };
  window.Motion = { Spring: Spring, project: project, rubber: rubber, Tracker: Tracker, reduced: reduce };
})();
