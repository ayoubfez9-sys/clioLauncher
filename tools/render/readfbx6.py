"""Lecture d'un FBX binaire version 6 (refusé par l'importeur Blender) : maillages, normales, UV, matériaux."""
import math, numpy as np
import bpy, addon_utils
addon_utils.enable('io_scene_fbx', default_set=True)
from io_scene_fbx import parse_fbx

def _nm(b):
    return b.split(b'\x00')[0].decode('latin-1') if isinstance(b, bytes) else str(b)

def _child(e, key):
    for c in e.elems:
        if c.id == key: return c
    return None

def _arr(e):
    """FBX 6 : les tableaux sont souvent stockés en propriétés scalaires successives"""
    if len(e.props) == 1 and hasattr(e.props[0], '__len__') and not isinstance(e.props[0], (bytes, str)): return list(e.props[0])
    return list(e.props)

def _props60(e):
    out = {}
    p = _child(e, b'Properties60')
    if p is None: return out
    for c in p.elems:
        out[_nm(c.props[0])] = c.props[3:]
    return out

def _rot(rx, ry, rz):  # Euler XYZ (degrés), ordre FBX par défaut : R = Rz * Ry * Rx
    rx, ry, rz = map(math.radians, (rx, ry, rz))
    X = np.array([[1, 0, 0], [0, math.cos(rx), -math.sin(rx)], [0, math.sin(rx), math.cos(rx)]])
    Y = np.array([[math.cos(ry), 0, math.sin(ry)], [0, 1, 0], [-math.sin(ry), 0, math.cos(ry)]])
    Z = np.array([[math.cos(rz), -math.sin(rz), 0], [math.sin(rz), math.cos(rz), 0], [0, 0, 1]])
    return Z @ Y @ X

def readfbx(path):
    root, ver = parse_fbx.parse(path)
    objs = _child(root, b'Objects')
    mats = {}
    for c in objs.elems:
        if c.id == b'Material':
            p = _props60(c)
            mats[_nm(c.props[0])] = {k: tuple(v) for k, v in p.items() if k in ('Diffuse', 'Specular', 'Opacity', 'Shininess', 'Emissive')}
    # liens matériau -> modèle (ordre = index de matériau du modèle)
    model_mats = {}
    con = _child(root, b'Connections')
    for c in con.elems:
        if len(c.props) >= 3 and _nm(c.props[0]) == 'OO':
            a, b = _nm(c.props[1]), _nm(c.props[2])
            if a in mats: model_mats.setdefault(b, []).append(a)
    out = []
    for c in objs.elems:
        if c.id != b'Model' or _nm(c.props[1]) != 'Mesh': continue
        name = _nm(c.props[0])
        V = _child(c, b'Vertices'); P = _child(c, b'PolygonVertexIndex')
        if V is None or P is None: continue
        v = np.array(_arr(V), dtype=np.float64).reshape(-1, 3)
        pvi = np.array(_arr(P), dtype=np.int64)
        pr = _props60(c)
        T = np.array(pr.get('Lcl Translation', (0, 0, 0)), float); R = _rot(*pr.get('Lcl Rotation', (0, 0, 0)))
        S = np.array(pr.get('Lcl Scaling', (1, 1, 1)), float)
        GT = np.array(pr.get('GeometricTranslation', (0, 0, 0)), float)
        vw = ((v + GT) * S) @ R.T + T
        # polygones
        polys = []; cur = []
        for i in pvi:
            if i < 0: cur.append(-i - 1); polys.append(cur); cur = []
            else: cur.append(int(i))
        # normales
        nrm = None; nmap = None
        ln = _child(c, b'LayerElementNormal')
        if ln is not None:
            nrm = np.array(_arr(_child(ln, b'Normals')), float).reshape(-1, 3) @ R.T
            nmap = _nm(_child(ln, b'MappingInformationType').props[0])
        # matériaux par face
        lm = _child(c, b'LayerElementMaterial'); fmat = None
        mlist = model_mats.get(name, [])
        if lm is not None:
            mi = [int(x) for x in _arr(_child(lm, b'Materials'))]; mt = _nm(_child(lm, b'MappingInformationType').props[0])
            if mt == 'AllSame' or len(mi) != len(polys): fmat = [mi[0] if mi else 0] * len(polys)
            else: fmat = mi
        else: fmat = [0] * len(polys)
        fm_names = [(mlist[i] if i < len(mlist) else (mlist[0] if mlist else 'gum')) for i in fmat]
        # UV (par sommet de polygone)
        puv = None
        lu = _child(c, b'LayerElementUV')
        if lu is not None:
            uv = np.array(_arr(_child(lu, b'UV')), float).reshape(-1, 2)
            ref = _nm(_child(lu, b'ReferenceInformationType').props[0]); mp = _nm(_child(lu, b'MappingInformationType').props[0])
            idx = np.array(_arr(_child(lu, b'UVIndex')), np.int64) if _child(lu, b'UVIndex') is not None and ref == 'IndexToDirect' else None
            if mp == 'ByPolygonVertex':
                per = uv[idx] if idx is not None else uv
                puv = []; k = 0
                for poly in polys: puv.append(per[k:k + len(poly)]); k += len(poly)
            elif mp in ('ByVertice', 'ByVertex'):
                per = uv[idx] if idx is not None else uv
                puv = [per[poly] for poly in polys]
        out.append({'name': name, 'verts': vw, 'polys': polys, 'normals': nrm, 'nmap': nmap, 'fmat': fm_names, 'puv': puv})
    return out, mats

if __name__ == '__main__':
    import sys
    obs, mats = readfbx(sys.argv[1])
    print(len(obs), 'meshes', len(mats), 'materials')
    for o in obs:
        v = o['verts']; lo, hi = v.min(0), v.max(0)
        print('%-14s v=%6d p=%6d n=%s/%s x[%7.0f,%7.0f] y[%7.0f,%7.0f] z[%7.0f,%7.0f] %s' % (o['name'], len(v), len(o['polys']), None if o['normals'] is None else len(o['normals']), o['nmap'], lo[0], hi[0], lo[1], hi[1], lo[2], hi[2], sorted(set(o['fmat']))[:3]))
