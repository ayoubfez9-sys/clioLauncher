import sys, numpy as np
sys.path.insert(0, '.')
from read3ds import read3ds

def islands(ob, tol=2.0):
    v = np.array(ob['verts']); f = np.array(ob['faces'], dtype=np.int64)
    key = np.round(v / tol).astype(np.int64)
    _, inv = np.unique(key, axis=0, return_inverse=True)
    inv = inv.ravel()
    parent = np.arange(inv.max() + 1)
    def find(a):
        while parent[a] != a:
            parent[a] = parent[parent[a]]; a = parent[a]
        return a
    for a, b, c in inv[f]:
        ra, rb, rc = find(a), find(b), find(c)
        parent[rb] = ra; parent[find(rc)] = ra
    roots = np.array([find(x) for x in inv[f[:, 0]]])
    out = []
    for r in np.unique(roots):
        fi = np.where(roots == r)[0]
        pts = v[f[fi].ravel()]
        out.append((fi, pts.min(0), pts.max(0)))
    out.sort(key=lambda t: -len(t[0]))
    return out

if __name__ == '__main__':
    obs, mats = read3ds(sys.argv[1])
    for ob in obs:
        if ob['name'] not in sys.argv[2:]: continue
        isl = islands(ob)
        print('==', ob['name'], len(isl), 'islands')
        for fi, lo, hi in isl[:40]:
            print('  f=%6d x[%7.0f,%7.0f] y[%7.0f,%7.0f] z[%7.0f,%7.0f]' % (len(fi), lo[0], hi[0], lo[1], hi[1], lo[2], hi[2]))
