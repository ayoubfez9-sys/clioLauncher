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
# v1.13 : vue de face plongeante (portes des deux côtés) + coffre et/ou capot ouverts
for extra, suf in ((('trunk',), 't'),):
    for L in ('fl', 'rl', 'flrl'):
        for R in ('fr', 'rr', 'frrr'):
            close_all()
            for k in (L[:2], L[2:], R[:2], R[2:]) + extra:
                if k: C.B.set_open(P, piv, k, 1)
            shot('door_hi_%s_%s_%s' % (L, R, suf), HI)
print('ALLDONE', flush=True)
