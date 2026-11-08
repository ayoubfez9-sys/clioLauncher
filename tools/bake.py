"""Decoupe la carrosserie du modele Clio en pieces (noir, gris, chrome) en suivant la geometrie,
et ecrit un nouveau .glb avec de vraies matieres separees.
Usage : python3 -I bake.py entree.glb sortie.glb [--debug]"""
import sys, json, struct
import numpy as np
sys.path.insert(0, '/home/claude/scripts')
from glbload import parts

src, dst = sys.argv[1], sys.argv[2]
d, j, bin0, binlen, P = parts(src)
body = [p for p in P if p['mat'] == 'body' and 'BODY' in p['node']][0]
glass = [p for p in P if p['mat'] == 'glass'][0]
head = [p for p in P if p['mat'] == 'head'][0]

L = body['L']; Nv = body['N']; tri = body['idx'].reshape(-1, 3)
C = L[tri].mean(1)
Nf = Nv[tri].mean(1); Nf /= np.maximum(np.linalg.norm(Nf, axis=1, keepdims=True), 1e-9)

# --- ilots de triangles (pieces collees sur la carrosserie)
n = len(L); par = np.arange(n)
def f(x):
    r = x
    while par[r] != r: r = par[r]
    while par[x] != r: par[x], x = r, par[x]
    return r
def u(a, b):
    a, b = f(a), f(b)
    if a != b: par[a] = b
_, inv = np.unique(np.round(L, 5), axis=0, return_inverse=True)
first = {}
for i, k in enumerate(inv.reshape(-1)):
    if k in first: u(i, first[k])
    else: first[k] = i
for t in tri: u(t[0], t[1]); u(t[1], t[2])
root = np.array([f(i) for i in range(n)])
comp = root[tri[:, 0]]
cid, cnt = np.unique(comp, return_counts=True)
main = cid[np.argmax(cnt)]
box = {}
for c in cid:
    V = L[np.unique(tri[comp == c])]
    box[c] = (V.min(0), V.max(0), (comp == c).sum())

def island_kind(c):
    mn, mx, k = box[c]
    ax = np.maximum(abs(mn[0]), abs(mx[0]))
    cx = abs((mn[0] + mx[0]) / 2)
    if mn[2] > 1.85 and cx < 0.02 and mx[0] - mn[0] < 0.2 and 0.45 < mn[1] and mx[1] < 0.72: return 'logo'   # losange avant
    if mx[2] < -1.85 and cx < 0.02 and mx[0] - mn[0] < 0.15 and 0.80 < mn[1] and mx[1] < 1.0: return 'logo'  # losange arriere
    if ax > 0.78 and mx[1] < 0.40 and mn[1] > 0.25 and mx[2] - mn[2] > 1.0: return 'grey'                 # baguettes entre les roues
    if 0.85 < mn[1] and mx[1] < 1.0 and 0.9 < mn[2] and mx[2] < 1.15: return 'black'                       # essuie-glaces avant
    if 1.0 < mn[1] and mx[1] < 1.12 and mx[2] < -1.78: return 'black'                                       # essuie-glace arriere
    if cx < 0.02 and mx[0] - mn[0] > 0.4 and mn[2] > 1.95 and mx[1] < 0.32: return 'black'                # support plaque avant
    if cx < 0.02 and mx[0] - mn[0] > 0.4 and mx[2] < -1.9 and 0.38 < mn[1] and mx[1] < 0.55: return 'black' # support plaque arriere
    return None

kind = np.array(['body'] * len(tri), dtype=object)
for c in cid:
    if c == main: continue
    k = island_kind(c)
    if k: kind[comp == c] = k

# --- dans la carrosserie principale
m = comp == main
# 1. bandeau noir entre les phares (meme hauteur que les vrais phares, entre leurs bords interieurs)
hx = np.abs(head['L'][:, 0]).min()            # bord interieur des phares
hy0, hy1 = head['L'][:, 1].min(), head['L'][:, 1].max()
Vt = L[tri]                                   # sommets de chaque triangle (n, 3, 3)
inband = (Vt[:, :, 2] > 1.68) & (np.abs(Vt[:, :, 0]) < hx + 0.02) & (Vt[:, :, 1] > hy0 - 0.04) & (Vt[:, :, 1] < hy1 + 0.01)
band = m & inband.all(1) & (Nf[:, 2] > 0.25)
kind[band] = 'black'

# 2. encadrements de vitres : triangles colles aux vraies vitres, sur les cotes
G = glass['L']; gt = glass['idx'].reshape(-1, 3)
gpts = np.vstack([G, G[gt].mean(1), (G[gt[:, 0]] + G[gt[:, 1]]) / 2, (G[gt[:, 1]] + G[gt[:, 2]]) / 2, (G[gt[:, 0]] + G[gt[:, 2]]) / 2])
side = gpts[np.abs(gpts[:, 0]) > 0.45]          # vitres laterales seulement (pas pare-brise ni lunette)
def nearest(pts, ref, chunk=2000):
    out = np.empty(len(pts))
    for i in range(0, len(pts), chunk):
        dd = ((pts[i:i + chunk, None, :] - ref[None, :, :]) ** 2).sum(-1)
        out[i:i + chunk] = np.sqrt(dd.min(1))
    return out
cand = m & (C[:, 1] > 0.84) & (np.abs(Nf[:, 0]) > 0.30) & (np.abs(C[:, 0]) > 0.45)
THR = 0.06
vsel = np.unique(tri[cand])
vd = np.full(len(L), 9.0); vd[vsel] = nearest(L[vsel], side)
frame = cand & (vd[tri] < THR).all(1)
kind[frame] = 'black'

# 3. bas du pare-chocs arriere (diffuseur)
diff = m & (C[:, 2] < -1.80) & (C[:, 1] < 0.40) & (Nf[:, 2] < -0.2)
kind[diff] = 'grey'

for k in ['body', 'black', 'grey', 'logo']:
    print(k, (kind == k).sum())

# --- ecriture du nouveau glb : la primitive carrosserie devient 4 primitives
binb = bytearray(d[bin0:bin0 + binlen])
mesh = j['meshes'][body['mesh']]
prim = mesh['primitives'][body['prim']]
names = {'body': None, 'black': 'trim_black', 'grey': 'trim_grey', 'logo': 'logo_chrome'}
debug = '--debug' in sys.argv
newprims = []
for k in ['body', 'black', 'grey', 'logo']:
    sel = tri[kind == k].astype('<u2').reshape(-1)
    if len(sel) == 0: continue
    while len(binb) % 4: binb.append(0)
    off = len(binb); binb += sel.tobytes()
    j['bufferViews'].append({'buffer': 0, 'byteOffset': off, 'byteLength': len(sel) * 2, 'target': 34963})
    j['accessors'].append({'bufferView': len(j['bufferViews']) - 1, 'componentType': 5123, 'count': int(len(sel)), 'type': 'SCALAR'})
    np_ = dict(prim); np_['indices'] = len(j['accessors']) - 1
    if names[k]:
        j['materials'].append({'name': names[k], 'pbrMetallicRoughness': {'baseColorFactor': [0.02, 0.02, 0.02, 1]}})
        np_['material'] = len(j['materials']) - 1
    newprims.append(np_)
mesh['primitives'][body['prim']:body['prim'] + 1] = newprims
while len(binb) % 4: binb.append(0)
j['buffers'][0]['byteLength'] = len(binb)
js = json.dumps(j, separators=(',', ':')).encode()
while len(js) % 4: js += b' '
out = struct.pack('<4sII', b'glTF', 2, 12 + 8 + len(js) + 8 + len(binb))
out += struct.pack('<I4s', len(js), b'JSON') + js + struct.pack('<I4s', len(binb), b'BIN\0') + bytes(binb)
open(dst, 'wb').write(out)
print('ecrit', dst, len(out))
