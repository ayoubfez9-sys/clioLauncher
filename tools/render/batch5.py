import sys, os, time; sys.path.insert(0, '.')
import bpy
bpy.ops.wm.read_factory_settings(use_empty=True)
import build2 as C
C.PLATE_STYLE = 'black'; C.CABIN_GAIN = 2.5; C.TRUNK_W = 14
OUT = 'out/frames3/'; os.makedirs(OUT, exist_ok=True)
M = C.materials(); P = C.build(M); piv = C.setup_pivots(P); C.hub_caps(P, M); C.front_plate(M); C.steer(P)
sc = C.scene_setup(samples=int(os.environ.get('SAMP', '160')), w=720, h=402); sc.view_settings.exposure = -0.9
sc.cycles.use_adaptive_sampling = True; sc.cycles.adaptive_threshold = float(os.environ.get('THR', '0.015')); sc.cycles.adaptive_min_samples = 24
def close_all():
    for k in piv: C.B.set_open(P, piv, k, 0)
def shot(name, cam):
    f = OUT + name + '.png'
    if os.path.exists(f): return
    t = time.time(); cam(); sc.render.filepath = f
    bpy.ops.render.render(write_still=True); print('OK', name, round(time.time() - t), flush=True)
V = lambda i: (lambda: C.view_cam(sc, i))
HI = lambda: C.free_cam(sc, *C.FRONT_HI)
# plateaux (vue normale + vue de face plongeante)
if not (os.path.exists(OUT + 'floor.png') and os.path.exists(OUT + 'floor_hi.png')):
    disc = C.floor_disc()
    hid = [o for o in bpy.data.objects if o.type == 'MESH' and o.name != 'disc' and not o.hide_render]
    for o in hid: o.hide_render = True
    shot('floor', V(0)); shot('floor_hi', HI)
    for o in hid: o.hide_render = False
    bpy.data.objects.remove(disc)
# v1.13 C : animations combinées (un ouvrant bouge, les autres du même côté + hayon restent ouverts)
def frame(moving, k, statics, v):
    close_all()
    for st in statics: C.B.set_open(P, piv, st, 1)
    C.B.set_open(P, piv, moving, k / 6.0)
    shot('door_m%s_s%s_%d' % (moving, ''.join(statics), k), V(v))
JOBS = []
for a_, b_, v in (('fl', 'rl', 34), ('fr', 'rr', 22)):
    JOBS += [('trunk', (a_,), v), ('trunk', (b_,), v), ('trunk', (a_, b_), v),
             (a_, ('trunk',), v), (a_, (b_,), v), (a_, (b_, 'trunk'), v),
             (b_, ('trunk',), v), (b_, (a_,), v), (b_, (a_, 'trunk'), v)]
TEST = len(sys.argv) > 1 and sys.argv[1] == 'test'
if TEST:
    frame('trunk', 3, ('fl',), 34); frame('fl', 3, ('trunk',), 34); frame('rl', 3, ('fl', 'trunk'), 34); frame('fr', 3, ('rr',), 22)
else:
    for side, v in (('L', 34), ('R', 22)):      # hayon seul vu de 3/4 arrière (fin d'une fermeture de porte)
        close_all(); C.B.set_open(P, piv, 'trunk', 1); shot('door_trunk%s' % side, V(v))
    for m, st, v in JOBS:
        for k in range(1, 6): frame(m, k, st, v)
print('ALLDONE', flush=True)
