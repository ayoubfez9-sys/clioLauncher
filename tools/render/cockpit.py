"""trajectoire caméra : extérieur 3/4 arrière -> passage par la lunette -> poste de conduite (page Entretien)"""
import math, numpy as np
from mathutils import Vector
def sph(az, el, d, tgt=(0, 0, 0.62)):
    a, e = math.radians(az), math.radians(el)
    return np.array(tgt) + np.array([math.cos(a) * math.cos(e), math.sin(a) * math.cos(e), math.sin(e)]) * d
D0 = 6.4 * 85 / 50.0
# (t, position, cible, focale)
KEYS = [
    (0.00, sph(150, 16, D0 * 0.92), (0, 0, 0.62), 85),
    (0.30, sph(170, 14, 6.2, (0, 0, 0.8)), (0, 0, 0.8), 55),
    (0.55, (-3.3, 0.0, 1.30), (0.0, 0, 1.0), 32),
    (0.75, (-1.55, 0.0, 1.24), (0.6, 0, 0.88), 24),
    (1.00, (-0.15, 0.0, 1.32), (1.2, 0, 0.62), 20),
]
def ease(u): return u * u * (3 - 2 * u)            # départ et arrivée en douceur
def cr(p0, p1, p2, p3, t):
    t2, t3 = t * t, t * t * t
    return 0.5 * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
def at(s):
    """s in [0,1] (temps) -> (pos, cible, focale)"""
    s = ease(min(1, max(0, s)))
    ts = [k[0] for k in KEYS]; i = max(0, min(len(KEYS) - 2, int(np.searchsorted(ts, s, side='right')) - 1))
    u = (s - ts[i]) / (ts[i + 1] - ts[i])
    g = lambda j, f: np.array(KEYS[max(0, min(len(KEYS) - 1, j))][f], float)
    pos = cr(g(i - 1, 1), g(i, 1), g(i + 1, 1), g(i + 2, 1), u)
    tgt = cr(g(i - 1, 2), g(i, 2), g(i + 1, 2), g(i + 2, 2), u)
    lens = float(cr(g(i - 1, 3), g(i, 3), g(i + 1, 3), g(i + 2, 3), u))
    return pos, tgt, lens
def place(cam, s):
    pos, tgt, lens = at(s)
    cam.location = Vector(pos); cam.data.lens = lens; cam.data.shift_x = cam.data.shift_y = 0
    cam.rotation_euler = (Vector(tgt) - Vector(pos)).to_track_quat('-Z', 'Y').to_euler()
