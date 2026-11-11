"""Vue du dessus du pack voiture : img/top (verticale, avant en haut, 544×600) et img/toph (couchée, avant à gauche, 528×425).

  python3 render_top.py [dossier_sortie]        (défaut : out/top)

Produit, avec le même modèle et les mêmes matériaux que batch3.py (build2.py) :
  closed.webp            la voiture fermée, ombre douce
  fl fr rl rr trunk hood .webp   un calque par ouvrant : uniquement la pièce OUVERTE, entourée d'un halo orange
                                 (posé par-dessus closed.webp quand l'ouvrant est ouvert)
et affiche la valeur « topBox » à recopier dans js/config.js (cadre de la carrosserie, pour placer les arcs des radars).
"""
import sys, os, math
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import bpy
from mathutils import Vector
bpy.ops.wm.read_factory_settings(use_empty=True)
import build2 as C
C.PLATE_STYLE = 'black'; C.CABIN_GAIN = 2.5; C.TRUNK_W = 14      # mêmes réglages que batch3.py
from PIL import Image, ImageFilter

OUT = sys.argv[1] if len(sys.argv) > 1 else 'out/top'
W, H = 544, 600                      # taille de img/top (cadre « radar » 680×750 à l'échelle 0,8)
HW, HH = 528, 425                    # taille de img/toph (fenêtre « activité en direct »)
GLOW = (255, 146, 48)                # halo orange des ouvrants ouverts
MARGIN = 0.80                        # part de la hauteur occupée par la longueur de la voiture
os.makedirs(OUT + '/top', exist_ok=True); os.makedirs(OUT + '/toph', exist_ok=True)

M = C.materials(); P = C.build(M); piv = C.setup_pivots(P); C.hub_caps(P, M); C.front_plate(M); C.steer(P)
sc = C.scene_setup(samples=int(os.environ.get('SAMP', '96')), w=W, h=H); sc.view_settings.exposure = -0.9

# encombrement de la voiture (sans les rétros pour le cadre radar)
lo = Vector((1e9, 1e9, 1e9)); hi = -lo
for o in P.values():
    for c in o.bound_box:
        p = o.matrix_world @ Vector(c); lo = Vector(map(min, lo, p)); hi = Vector(map(max, hi, p))
length = hi.x - lo.x; cx, cy = (hi.x + lo.x) / 2, (hi.y + lo.y) / 2

# caméra orthographique au-dessus, avant (+x) vers le haut de l'image
cam = sc.camera
if cam is None:
    cd = bpy.data.cameras.new('cam'); cam = bpy.data.objects.new('cam', cd); sc.collection.objects.link(cam); sc.camera = cam
cam.data.type = 'ORTHO'; cam.data.ortho_scale = length / MARGIN       # l'échelle porte sur le plus grand côté (la hauteur)
cam.data.shift_x = cam.data.shift_y = 0
cam.location = (cx, cy, 10); cam.rotation_euler = (0, 0, math.radians(-90))
px = H / cam.data.ortho_scale                                          # pixels par mètre

def to_px(x, y):  # monde -> pixels dans img/top (avant en haut, gauche du véhicule à gauche de l'image)
    return (W / 2 - (y - cy) * px, H / 2 - (x - cx) * px)

def close_all():
    for k in piv: C.B.set_open(P, piv, k, 0)

def render(path):
    sc.render.filepath = path; bpy.ops.render.render(write_still=True)

def glow(png):
    im = Image.open(png).convert('RGBA'); a = im.getchannel('A')
    halo = Image.new('RGBA', im.size, GLOW + (0,)); halo.putalpha(a.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(6)).point(lambda v: min(255, int(v * 1.4))))
    halo.alpha_composite(im); return halo

def save_pair(im, name):
    im.save(OUT + '/top/%s.webp' % name, 'WEBP', quality=88, alpha_quality=92, method=6)
    r = im.rotate(90, expand=True)                                     # avant à gauche
    s = HW / r.width; r = r.resize((HW, round(r.height * s)), Image.LANCZOS)
    top = (r.height - HH) // 2
    r.crop((0, top, HW, top + HH)).save(OUT + '/toph/%s.webp' % name, 'WEBP', quality=88, alpha_quality=92, method=6)

# 1) voiture fermée
close_all(); render(OUT + '/_closed.png'); save_pair(Image.open(OUT + '/_closed.png').convert('RGBA'), 'closed')

# 2) un calque par ouvrant : la pièce ouverte seule (le reste caché), avec halo
shadow = bpy.data.objects.get('shadow')
for k in ('fl', 'fr', 'rl', 'rr', 'trunk', 'hood'):
    if k not in P: continue
    close_all(); C.B.set_open(P, piv, k, 1)
    for n, o in P.items(): o.hide_render = (n != k)
    for o in bpy.data.objects:                                         # caches de roue, plaques… cachés aussi
        if o.type == 'MESH' and o not in P.values(): o.hide_render = True
    if shadow: shadow.hide_render = True
    render(OUT + '/_%s.png' % k); save_pair(glow(OUT + '/_%s.png' % k), k)
    for o in bpy.data.objects: o.hide_render = False

# 3) cadre de la carrosserie dans le repère 680×750 des radars (img/top × 1,25)
body = P['static']; blo = Vector((1e9, 1e9, 1e9)); bhi = -blo
for c in body.bound_box:
    p = body.matrix_world @ Vector(c); blo = Vector(map(min, blo, p)); bhi = Vector(map(max, bhi, p))
left, top = to_px(bhi.x, bhi.y); right, bottom = to_px(blo.x, blo.y)
k = 1.25
print('topBox = {"x0": %d, "x1": %d, "y0": %d, "y1": %d, "w": 680, "h": 750}' % (left * k, right * k, top * k, bottom * k), flush=True)
for f in os.listdir(OUT):
    if f.startswith('_') and f.endswith('.png'): os.remove(os.path.join(OUT, f))
print('TOPDONE', flush=True)
