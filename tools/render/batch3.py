import sys, os, time; sys.path.insert(0, '.')
import bpy
bpy.ops.wm.read_factory_settings(use_empty=True)
import build2 as C
C.PLATE_STYLE = 'black'; C.CABIN_GAIN = 2.5; C.TRUNK_W = 14
OUT = 'out/frames3/'; os.makedirs(OUT, exist_ok=True)
M = C.materials(); P = C.build(M); piv = C.setup_pivots(P); C.hub_caps(P, M); C.front_plate(M); C.steer(P)
sc = C.scene_setup(samples=160, w=720, h=402); sc.view_settings.exposure = -0.9
sc.cycles.use_adaptive_sampling = True; sc.cycles.adaptive_threshold = 0.015; sc.cycles.adaptive_min_samples = 24
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
DV = {'fl': 34, 'rl': 34, 'fr': 22, 'rr': 22, 'hood': 0, 'trunk': 24}
for k, v in DV.items():
    for st in range(1, 7):
        close_all(); C.B.set_open(P, piv, k, st / 6.0); shot('door_%s_%d' % (k, st), V(v))
for pair, v in (('flrl', 34), ('frrr', 22)):
    close_all(); C.B.set_open(P, piv, pair[:2], 1); C.B.set_open(P, piv, pair[2:], 1); shot('door_%s_6' % pair, V(v))
for L in ('fl', 'rl', 'flrl'):
    for R in ('fr', 'rr', 'frrr'):
        close_all()
        for k in (L[:2], L[2:], R[:2], R[2:]):
            if k: C.B.set_open(P, piv, k, 1)
        shot('door_hi_%s_%s' % (L, R), HI)
close_all()
for i in range(48): shot('hero_%02d' % i, V(i))
print('ALLDONE', flush=True)
