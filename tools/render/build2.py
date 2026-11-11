"""Ibiza 5 portes — base propre à partir du FBX du vendeur (normales lissées d'origine).
Cycles + OpenImageDenoise, peinture Seat vert Amarillo, 3 néons de studio, roues avant braquées."""
import sys, math, numpy as np
sys.path.insert(0, '.')
import bpy
from mathutils import Vector, Matrix
from readfbx6 import readfbx
import build as B          # réutilise : srgb, to_bl, islands, charnières, caméra, plateau

FBX = 'model/car.fbx'
TEX = 'model/textures/'
RIMS = {'silver02', 'silver06', 'silver07', 'silver10'}
TEXTURED = {'dash', 'stwheel', 'door'}
STEER = math.radians(16)    # braquage des roues avant (vers la gauche du conducteur)
FRONT_WHEEL = {'silver07', 'silver10', 'gum177', 'gum178', 'gum173', 'gum174', 'gum175', 'gum176',
               'gum179', 'gum180', 'silver08', 'silver09'}
TIRES = {'gum164', 'gum169', 'gum177', 'gum178'}
DISCS = {'silver01', 'silver05', 'silver08', 'silver09'}
LAMPHOUSE = {'black2', 'black0', 'gum15', 'gum11', 'gum12'}
REFLECTOR = {'chrome3', 'chrome6', 'chrom01', 'chrome7', 'chrome12', 'chrom04', 'chroem', 'chrome2'}
GRILLE = {'gum3', 'gum13', 'gum8', 'gum4', 'gum7', 'gum14', 'gum0'}   # calandre, grilles de pare-chocs

# ---------------- matériaux ----------------
def P(m): return m.node_tree.nodes['Principled BSDF']

def micro_bump(m, scale, strength, kind='noise'):
    nt = m.node_tree; tc = nt.nodes.new('ShaderNodeTexCoord')
    tx = nt.nodes.new('ShaderNodeTexNoise' if kind == 'noise' else 'ShaderNodeTexVoronoi'); tx.inputs['Scale'].default_value = scale
    bp = nt.nodes.new('ShaderNodeBump'); bp.inputs['Strength'].default_value = strength; bp.inputs['Distance'].default_value = 0.001
    nt.links.new(tc.outputs['Object'], tx.inputs['Vector'])
    nt.links.new(tx.outputs['Fac' if kind == 'noise' else 'Distance'], bp.inputs['Height'])
    nt.links.new(bp.outputs['Normal'], P(m).inputs['Normal'])

def img(m, file, uv=True, proj=None, scale=1.0):
    nt = m.node_tree; t = nt.nodes.new('ShaderNodeTexImage'); t.image = bpy.data.images.load(TEX + file)
    t.image.colorspace_settings.name = 'Non-Color'
    if uv:
        u = nt.nodes.new('ShaderNodeUVMap'); nt.links.new(u.outputs['UV'], t.inputs['Vector'])
    else:
        t.projection = 'BOX'; t.projection_blend = 0.2
        tc = nt.nodes.new('ShaderNodeTexCoord'); mp = nt.nodes.new('ShaderNodeMapping'); mp.inputs['Scale'].default_value = (scale, scale, scale)
        nt.links.new(tc.outputs['Object'], mp.inputs['Vector']); nt.links.new(mp.outputs['Vector'], t.inputs['Vector'])
    return t

def materials():
    mat = B.mat
    M = {}
    # Seat « vert Amarillo » #D1E01F : métal 0,88 / rugosité 0,10 / vernis 1,0 (rugosité 0,02)
    if STYLE == 'ever':   # métallisé lime verni, reflets doux (référence Evermotion)
        M['body'] = mat('paint', B.srgb((0xA6, 0xB4, 0x0E)), metal=0.7, rough=0.32, coat=1.0)
        P(M['body']).inputs['Coat Roughness'].default_value = 0.04
        nt = M['body'].node_tree; tc = nt.nodes.new('ShaderNodeTexCoord'); vo = nt.nodes.new('ShaderNodeTexVoronoi'); vo.inputs['Scale'].default_value = 7000
        cr = nt.nodes.new('ShaderNodeValToRGB'); cr.color_ramp.elements[0].color = (0.27, 0.27, 0.27, 1); cr.color_ramp.elements[1].color = (0.37, 0.37, 0.37, 1)
        nt.links.new(tc.outputs['Object'], vo.inputs['Vector']); nt.links.new(vo.outputs['Distance'], cr.inputs['Fac']); nt.links.new(cr.outputs['Color'], P(M['body']).inputs['Roughness'])
        # « flop » de peinture métallisée : profonde et saturée de face, plus claire et dorée en incidence rasante
        lw = nt.nodes.new('ShaderNodeLayerWeight'); lw.inputs['Blend'].default_value = 0.35
        mx = nt.nodes.new('ShaderNodeMix'); mx.data_type = 'RGBA'
        c = P(M['body']).inputs['Base Color'].default_value
        mx.inputs['A'].default_value = (c[0] * 0.55, c[1] * 0.62, c[2] * 0.3, 1)       # face : profond
        mx.inputs['B'].default_value = (min(1, c[0] * 1.25), min(1, c[1] * 1.2), c[2] * 1.1, 1)   # rasant : lumineux
        nt.links.new(lw.outputs['Facing'], mx.inputs['Factor']); nt.links.new(mx.outputs['Result'], P(M['body']).inputs['Base Color'])
        P(M['body']).inputs['Coat IOR'].default_value = 1.6                              # liserés clairs sur les arêtes
    else:
        M['body'] = mat('paint', B.srgb((0xD1, 0xE0, 0x1F)), metal=0.88, rough=0.15, coat=1.0)   # brillant légèrement satiné
        P(M['body']).inputs['Coat Roughness'].default_value = 0.02
    M['silver'] = mat('alloy', (0.52, 0.53, 0.55), metal=1.0 if STYLE == 'ever' else 0.55, rough=0.28)    # alu gris satiné (validé)
    t = img(M['silver'], 'HDM_03_felgi_ref.jpg'); nt = M['silver'].node_tree           # alu tourné (cercles concentriques)
    cr = nt.nodes.new('ShaderNodeMapRange'); cr.inputs['To Min'].default_value = 0.22; cr.inputs['To Max'].default_value = 0.36
    nt.links.new(t.outputs['Color'], cr.inputs['Value']); nt.links.new(cr.outputs['Result'], P(M['silver']).inputs['Roughness'])
    M['brake'] = mat('brake', (0.3, 0.3, 0.31), metal=1.0, rough=0.45)
    M['tire'] = mat('rubber', (0.010, 0.010, 0.011), rough=0.92)
    t = img(M['tire'], 'HDM_03_tyre_sidewall.png'); nt = M['tire'].node_tree
    mx = nt.nodes.new('ShaderNodeMix'); mx.data_type = 'RGBA'; mx.inputs['A'].default_value = (0.010, 0.010, 0.011, 1); mx.inputs['B'].default_value = (0.055, 0.055, 0.058, 1)
    nt.links.new(t.outputs['Color'], mx.inputs['Factor']); nt.links.new(mx.outputs['Result'], P(M['tire']).inputs['Base Color'])
    bp = nt.nodes.new('ShaderNodeBump'); bp.inputs['Strength'].default_value = 0.35; bp.inputs['Distance'].default_value = 0.002
    nt.links.new(t.outputs['Color'], bp.inputs['Height']); nt.links.new(bp.outputs['Normal'], P(M['tire']).inputs['Normal'])   # marquage en relief
    M['gum'] = mat('plastic', (0.018, 0.018, 0.02), rough=0.55)
    M['grille'] = mat('grille', (0.014, 0.014, 0.015), rough=0.72)       # plastique mat, relief nid d'abeille
    t = img(M['grille'], 'HDM_03_metal3 bump.jpg', uv=False, scale=22.0); nt = M['grille'].node_tree
    bp = nt.nodes.new('ShaderNodeBump'); bp.inputs['Strength'].default_value = 0.6; bp.inputs['Distance'].default_value = 0.004
    nt.links.new(t.outputs['Color'], bp.inputs['Height']); nt.links.new(bp.outputs['Normal'], P(M['grille']).inputs['Normal'])
    M['gum1'] = mat('interior', (0.11, 0.11, 0.115), rough=0.6)
    def texmat(name, file):
        m = mat(name, (0.1, 0.1, 0.1), rough=0.55); nt = m.node_tree
        t = nt.nodes.new('ShaderNodeTexImage'); t.image = bpy.data.images.load('model/textures/' + file)
        u = nt.nodes.new('ShaderNodeUVMap'); nt.links.new(u.outputs['UV'], t.inputs['Vector']); nt.links.new(t.outputs['Color'], P(m).inputs['Base Color'])
        return m
    M['dash'] = texmat('dash', 'DASH.png'); M['stwheel'] = texmat('stwheel', 'STWHEEL.png'); M['door'] = texmat('doorcard', 'DOOR.png')
    M['lens'] = mat('lens', (1.0, 1.0, 1.0), rough=0.0, trans=0.6, ior=1.5)               # lentilles des projecteurs
    M['reflector'] = mat('reflector', (0.92, 0.92, 0.94), metal=1.0, rough=0.06)        # réflecteurs de phares (chrome clair)
    M['lamphouse'] = mat('lamphouse', (0.09, 0.09, 0.1), metal=0.3, rough=0.25)         # fond de phare gris foncé (moins noir)
    M['chrome'] = mat('chrome', (0.96, 0.96, 0.97), metal=1.0, rough=0.02)
    M['glass'] = mat('crystal', (1.0, 1.0, 1.0), rough=0.0, trans=1.0, ior=1.5)          # verre de phare cristallin
    M['d glass'] = mat('tint', (0.13, 0.14, 0.15) if STYLE == 'ever' else (0.2, 0.215, 0.23), rough=0.02, trans=1.0, ior=1.3)    # vitres teintées (habitacle visible)
    P(M['d glass']).inputs['Specular IOR Level'].default_value = 0.3
    M['windscreen'] = mat('windscreen', (0.34, 0.36, 0.37), rough=0.02, trans=1.0, ior=1.3)   # pare-brise plus clair (latérales inchangées)
    P(M['windscreen']).inputs['Specular IOR Level'].default_value = 0.3
    if THIN_GLASS:
        for k in ('d glass', 'windscreen'): thin_glass(M[k])
    M['r glass'] = mat('redglass', (0.6, 0.02, 0.02), rough=0.02, trans=0.85, ior=1.5)
    M['red l'] = mat('redlamp', (0.4, 0.01, 0.01), rough=0.2)
    M['orange'] = mat('orange', (0.9, 0.35, 0.02), rough=0.15)
    M['black'] = mat('black', (0.006, 0.006, 0.007), rough=0.12, coat=0.5)
    M['plate'] = mat('plate', (0.85, 0.85, 0.85), rough=0.4)
    M['headlamp'] = mat('headlamp', (1, 1, 1), rough=0.0, emit=((1.0, 0.95, 0.85), 0.0))   # allumable plus tard
    return M

THIN_GLASS = True
def thin_glass(m, refl=0.75):
    """vitre fine : transparence teintée + reflet de Fresnel, sans réfraction (plus de déformation à travers)"""
    nt = m.node_tree; p = P(m); out = nt.nodes['Material Output']
    tint = tuple(p.inputs['Base Color'].default_value)
    tr = nt.nodes.new('ShaderNodeBsdfTransparent'); tr.inputs['Color'].default_value = tint
    gl = nt.nodes.new('ShaderNodeBsdfGlossy'); gl.inputs['Roughness'].default_value = 0.02; gl.inputs['Color'].default_value = (1, 1, 1, 1)
    fr = nt.nodes.new('ShaderNodeFresnel'); fr.inputs['IOR'].default_value = 1.52
    mu = nt.nodes.new('ShaderNodeMath'); mu.operation = 'MULTIPLY'; mu.inputs[1].default_value = refl
    nt.links.new(fr.outputs[0], mu.inputs[0])
    mx = nt.nodes.new('ShaderNodeMixShader')
    nt.links.new(mu.outputs[0], mx.inputs[0]); nt.links.new(tr.outputs[0], mx.inputs[1]); nt.links.new(gl.outputs[0], mx.inputs[2])
    nt.links.new(mx.outputs[0], out.inputs['Surface'])

# ---------------- géométrie ----------------
def load():
    obs, _ = readfbx(FBX)
    out = []
    for o in obs:
        tris, fm, tuv = [], [], []
        puv = o.get('puv')
        for pi, (poly, m) in enumerate(zip(o['polys'], o['fmat'])):
            for k in range(1, len(poly) - 1):
                tris.append((poly[0], poly[k], poly[k + 1])); fm.append(m)
                if puv is not None: u = puv[pi]; tuv.append((u[0], u[k], u[k + 1]))
        if not tris: continue
        v = o['verts']; uvv = None
        if o['name'] in TIRES or o['name'] in RIMS:
            lo, hi = v.min(0), v.max(0); c = (lo + hi) / 2; r = max(hi[0] - lo[0], hi[2] - lo[2]) / 2
            uvv = np.stack([(v[:, 0] - c[0]) / (2 * r) + 0.5, (v[:, 2] - c[2]) / (2 * r) + 0.5], 1)
            if v[:, 1].mean() > B.YMID: uvv[:, 0] = 1 - uvv[:, 0]          # texte lisible des deux côtés
        tuv = np.array(tuv, float) if (puv is not None and o['name'] in TEXTURED) else None
        if o['name'] in ('body02', 'chrome4', 'black02', 'd glass01', 'black1', 'd glass0'):   # rétroviseurs +25 %
            vv = o['verts'].copy(); ft = np.array(tris, np.int64)
            for fi, lo, hi in B.islands({'verts': vv, 'faces': ft}):
                if lo[0] > 3100 and hi[0] < 4900 and lo[2] > -3600 and (hi[1] < -4000 or lo[1] > 7100) and o['name'] in ('body02', 'chrome4'):
                    idx = np.unique(ft[fi].ravel()); left = lo[1] > B.YMID
                    piv = np.array([(lo[0] + hi[0]) / 2, lo[1] if left else hi[1], lo[2]])
                    vv[idx] = piv + (vv[idx] - piv) * 1.25
            o = dict(o); o['verts'] = vv
        out.append({'name': o['name'], 'verts': o['verts'], 'faces': np.array(tris, np.int64), 'uvv': uvv, 'tuv': tuv,
                    'fm': np.array(fm, dtype=object), 'normals': o['normals'] if o['nmap'] == 'ByVertice' else None})
    return out

def make_obj(name, V, F, FM, N, M, coll, UV=None, CUV=None):
    used = np.unique(F.ravel())
    remap = -np.ones(len(V), np.int64); remap[used] = np.arange(len(used))
    vv = B.to_bl(V[used]); ff = remap[F]
    me = bpy.data.meshes.new(name); me.from_pydata(vv.tolist(), [], ff.tolist())
    names = sorted(set(FM))
    for n in names: me.materials.append(M.get(n, M['gum']))
    idx = {n: i for i, n in enumerate(names)}
    me.polygons.foreach_set('material_index', [idx[n] for n in FM])
    me.polygons.foreach_set('use_smooth', [True] * len(me.polygons))
    if CUV is not None and len(me.polygons) == len(F):
        uvl = me.uv_layers.new(name='UVMap')        # 3 coins par triangle, dans l'ordre des boucles
        uvl.data.foreach_set('uv', CUV.reshape(-1, 2).astype(np.float32).ravel())
    elif UV is not None:
        uvl = me.uv_layers.new(name='UVMap'); uvu = UV[used]
        loops = np.zeros(len(me.loops), np.int64); me.loops.foreach_get('vertex_index', loops)
        uvl.data.foreach_set('uv', uvu[loops].astype(np.float32).ravel())
    me.update()
    if N is not None:
        nn = N[used]; nn = nn / np.maximum(1e-9, np.linalg.norm(nn, axis=1))[:, None]
        try: me.normals_split_custom_set_from_vertices(nn.tolist())     # normales d'origine (lissage 3ds Max)
        except Exception as e: print('normals', e)
    ob = bpy.data.objects.new(name, me); coll.objects.link(ob)
    return ob

RAW = {}
def build(M):
    coll = bpy.context.scene.collection
    parts = {k: [] for k in ['static', 'fl', 'fr', 'rl', 'rr', 'hood', 'trunk', 'whFL', 'whFR']}
    for ob in load():
        RAW[ob['name']] = ob
        n = ob['name']; v = ob['verts']; f = ob['faces']; F = len(f)
        fm = ob['fm'].copy()
        if n in TIRES: fm[fm == 'gum'] = 'tire'
        if n in DISCS: fm[:] = 'brake'
        if n in GRILLE: fm[fm == 'gum'] = 'grille'
        if n in REFLECTOR: fm[fm == 'chrome'] = 'reflector'
        if n == 'd glass':
            for fi, lo, hi in B.islands({'verts': v, 'faces': f}):
                if lo[0] > 0: fm[fi] = 'windscreen'
        if n == 'glass0': fm[:] = 'lens'
        if n in LAMPHOUSE: fm[(fm == 'black') | (fm == 'gum')] = 'lamphouse'
        cen = v[f].mean(1); left = cen[:, 1] > B.YMID; X, Z = cen[:, 0], cen[:, 2]
        assigned = np.zeros(F, bool)
        def take(part, mask):
            m = mask & ~assigned
            if m.any(): parts[part].append((v, f[m], fm[m], ob['normals'], ob['uvv'], None if ob['tuv'] is None else ob['tuv'][m]))
            assigned[m] = True
        three = {'verts': v, 'faces': f}
        if n in FRONT_WHEEL: take('whFL', left); take('whFR', ~left)
        elif n in ('body02', 'chrome4'): take('fl', left); take('fr', ~left)
        elif n in ('black02', 'd glass01'): take('fr', np.ones(F, bool))
        elif n in ('black1', 'd glass0'): take('fl', np.ones(F, bool))
        elif n == 'body01':
            for fi, lo, hi in B.islands(three):
                m = np.zeros(F, bool); m[fi] = True
                if hi[0] < -2500 and lo[0] > -13000 and (hi[1] < -3000 or lo[1] > 6000) and hi[2] < -1500:
                    take('rl' if lo[1] > 6000 else 'rr', m)
                elif lo[0] < -18800 and hi[0] < -16500 and lo[2] > -6500:
                    take('trunk', m)
        elif n == 'body':
            for fi, lo, hi in B.islands(three):
                if lo[2] > -5700 and lo[0] > 7000 and hi[2] > -3500:
                    m = np.zeros(F, bool); m[fi] = True; take('hood', m)
        elif n in ('d glass', 'door'):
            if n == 'd glass':   # lunette arrière (jusqu'au becquet) = hayon
                for fi, lo, hi in B.islands(three):
                    if lo[0] < -18000: mm = np.zeros(F, bool); mm[fi] = True; take('trunk', mm)
            ws = fm == 'windscreen'; left_ = left & ~ws; right_ = ~left & ~ws
            take('fl', left_ & (X > -3900) & (X < 6300)); take('fr', right_ & (X > -3900) & (X < 6300))
            take('rl', left_ & (X > -12200) & (X < -3200) & (Z < 450)); take('rr', right_ & (X > -12200) & (X < -3200) & (Z < 450))
            if n == 'd glass': take('trunk', X < -16650)
        elif n == 'gum9':      # encadrements de vitres + joints de ceinture + montant B noir -> suivent les portes
            vb = B.to_bl(v)
            for fi, lo, hi in B.islands(three):
                pts = vb[np.unique(f[fi].ravel())]; l, h = pts.min(0), pts.max(0)
                if min(abs(l[1]), abs(h[1])) > 0.5 and l[2] > 0.85 and h[2] < 1.42 and l[0] > -1.25 and h[0] < 1.05:
                    cx = (l[0] + h[0]) / 2; side = 'l' if l[1] > 0 else 'r'
                    mm = np.zeros(F, bool); mm[fi] = True
                    take(('f' if cx > -0.25 else 'r') + side, mm)
        elif n == 'black01':
            for fi, lo, hi in B.islands(three):
                if lo[0] < -18000: mm = np.zeros(F, bool); mm[fi] = True; take('trunk', mm)
        elif n in ('red l2', 'r glass1'): take('trunk', np.ones(F, bool))      # 3e feu stop sur le becquet
        elif n in ('chrome0', 'gum02', 'chrome1', 'gum6', 'gum1'): take('trunk', np.ones(F, bool))
        take('static', np.ones(F, bool))
    out = {}
    for part, chunks in parts.items():
        if not chunks: continue
        Vs, Fs, FMs, Ns, UVs, CUV = [], [], [], [], [], []; off = 0; hasN = all(c[3] is not None for c in chunks)
        for v, f, fm, nrm, uvv, tuv in chunks:
            Vs.append(v); Fs.append(f + off); FMs.extend(fm.tolist()); off += len(v)
            if hasN: Ns.append(nrm)
            vu = uvv if uvv is not None else np.zeros((len(v), 2))
            UVs.append(vu)
            CUV.append(tuv if tuv is not None else vu[f])            # UV par coin de triangle
        out[part] = make_obj('car_' + part, np.concatenate(Vs), np.concatenate(Fs), FMs, np.concatenate(Ns) if hasN else None, M, coll, np.concatenate(UVs), np.concatenate(CUV))
    return out

def hub_caps(P, M):
    """cache central anthracite + logo SEAT (repris du badge de calandre du modèle) sur chaque jante"""
    coll = bpy.context.scene.collection
    M['cap'] = B.mat('hubcap', (0.12, 0.12, 0.13), metal=0.6, rough=0.3, coat=0.5)
    M['nut'] = B.mat('nut', (0.55, 0.56, 0.58), metal=1.0, rough=0.3)
    badge = RAW.get('chrome')                       # logo SEAT de la calandre (face vers l'avant, +x)
    bv = B.to_bl(badge['verts']); bc = (bv.min(0) + bv.max(0)) / 2; bv = bv - bc
    bsize = max(bv[:, 1].max() - bv[:, 1].min(), bv[:, 2].max() - bv[:, 2].min())
    rx = bv[:, 0].max()                             # avant du badge (épaisseur)
    for rim in ('silver07', 'silver10', 'silver02', 'silver06'):
        o = RAW[rim]; v = B.to_bl(o['verts']); lo, hi = v.min(0), v.max(0); c = (lo + hi) / 2
        left = c[1] > 0; sd = 1 if left else -1
        rr = np.hypot(v[:, 0] - c[0], v[:, 2] - c[2])
        near = rr < 0.025
        y0 = v[near, 1].max() if left else v[near, 1].min()      # face du moyeu (centre de la jante)
        R = 0.031
        bpy.ops.mesh.primitive_cylinder_add(vertices=64, radius=R, depth=0.006, location=(c[0], y0 - sd * 0.002, c[2]), rotation=(math.radians(90), 0, 0))   # à fleur du moyeu
        cap = bpy.context.active_object; cap.name = 'cap_' + rim; cap.data.materials.append(M['cap'])
        bpy.ops.object.shade_smooth()
        bev = cap.modifiers.new('bev', 'BEVEL'); bev.width = 0.0012; bev.segments = 3
        # logo : badge tourné de 90° pour regarder vers l'extérieur de la roue (rotation, donc non inversé)
        k = (2 * R * 0.62) / bsize; ang = math.radians(90) if left else math.radians(-90)
        ca, sa = math.cos(ang), math.sin(ang)
        pts = bv * k; pts[:, 0] = (bv[:, 0] - bv[:, 0].min()) * k * 0.02     # logo « imprimé » : quasi sans épaisseur
        X = pts[:, 0] * ca - pts[:, 1] * sa; Y = pts[:, 0] * sa + pts[:, 1] * ca
        pts = np.stack([X + c[0], Y + y0 + sd * 0.0013, pts[:, 2] + c[2]], 1)
        me = bpy.data.meshes.new('logo_' + rim); me.from_pydata(pts.tolist(), [], badge['faces'].tolist())
        me.materials.append(M['chrome']); me.polygons.foreach_set('use_smooth', [True] * len(me.polygons)); me.update()
        lg = bpy.data.objects.new('logo_' + rim, me); coll.objects.link(lg)
        # écrous : 5 perçages à 50 mm (entraxe 5x100), phase trouvée dans la géométrie de la jante
        rec = (rr > 0.04) & (rr < 0.062) & ((v[:, 1] < y0 - 0.004) if left else (v[:, 1] > y0 + 0.004))
        ang = np.degrees(np.arctan2(v[rec, 2] - c[2], v[rec, 0] - c[0])) % 72
        hist, edges = np.histogram(ang, bins=36, range=(0, 72)); ph = math.radians(edges[np.argmax(hist)] + 1.0)
        nuts = []
        for k5 in range(5):
            a = ph + k5 * 2 * math.pi / 5
            bpy.ops.mesh.primitive_cylinder_add(vertices=6, radius=0.0095, depth=0.013,
                location=(c[0] + 0.05 * math.cos(a), y0 - sd * 0.0035, c[2] + 0.05 * math.sin(a)), rotation=(math.radians(90), 0, 0))
            nu = bpy.context.active_object; nu.name = 'nut_%s_%d' % (rim, k5); nu.data.materials.append(M['nut'])
            bv2 = nu.modifiers.new('bev', 'BEVEL'); bv2.width = 0.0012; bv2.segments = 2
            nuts.append(nu)
        wheel = P.get('whFL' if rim == 'silver07' else 'whFR' if rim == 'silver10' else None) if rim in ('silver07', 'silver10') else None
        if wheel is not None:
            for ch in [cap, lg] + nuts: ch.parent = wheel; ch.matrix_parent_inverse = wheel.matrix_world.inverted()

LIGHTS_ON = False
def lights_on(M):
    """phares + feux arrière allumés (pour la série « phares allumés »)"""
    def glow(m, col, strength):
        p = P(m); p.inputs['Emission Color'].default_value = (*col, 1); p.inputs['Emission Strength'].default_value = strength
    glow(M['lens'], (1.0, 0.93, 0.8), 40.0)          # lentilles de projecteurs
    glow(M['reflector'], (1.0, 0.92, 0.78), 1.5)     # halo dans les réflecteurs
    glow(M['r glass'], (1.0, 0.05, 0.03), 6.0)       # feux arrière
    glow(M['red l'], (1.0, 0.04, 0.02), 3.0)

PLATE_STYLE = 'white'    # 'white' = blanche logo noir ; 'black' = noire logo blanc
def front_plate(M):
    """plaques avant et arrière identiques (logo SEAT fourni), posées sur les zones planes des pare-chocs"""
    m = B.mat('plateSEAT', (1, 1, 1), rough=0.35, coat=0.6); nt = m.node_tree
    t = nt.nodes.new('ShaderNodeTexImage'); t.image = bpy.data.images.load('model/plate_%s.png' % PLATE_STYLE)
    u = nt.nodes.new('ShaderNodeUVMap'); nt.links.new(u.outputs['UV'], t.inputs['Vector']); nt.links.new(t.outputs['Color'], P(m).inputs['Base Color'])
    for name, loc, rz in (('front_plate', (2.027, 0.0, 0.455), 90), ('rear_plate', (-2.016, 0.0, 0.46), -90)):
        bpy.ops.mesh.primitive_plane_add(size=1, location=loc, rotation=(math.radians(90), 0, math.radians(rz)))
        pl = bpy.context.active_object; pl.name = name; pl.scale = (0.43, 0.091, 1); pl.data.materials.append(m)
        sol = pl.modifiers.new('sol', 'SOLIDIFY'); sol.thickness = 0.004; sol.offset = -1

def steer(P):
    """braque les roues avant autour de l'axe vertical passant par le centre de chaque roue"""
    for k in ('whFL', 'whFR'):
        ob = P.get(k)
        if not ob: continue
        c = np.array([ob.matrix_world @ Vector(x) for x in ob.bound_box]); ctr = Vector(((c[:, 0].min() + c[:, 0].max()) / 2, (c[:, 1].min() + c[:, 1].max()) / 2, 0))
        ob.data.transform(Matrix.Translation(-ctr)); ob.location = ctr
        for ch in ob.children: ch.matrix_parent_inverse = Matrix.Translation(-ctr) @ ch.matrix_parent_inverse   # caches de moyeu suivent
        ob.rotation_euler = (0, 0, STEER)

# ---------------- studio ----------------
def scene_setup(w=720, h=402, samples=96):
    sc = bpy.context.scene
    sc.render.engine = 'CYCLES'; sc.cycles.device = 'CPU'; sc.cycles.samples = samples
    sc.cycles.use_denoising = True; sc.cycles.denoiser = 'OPENIMAGEDENOISE'
    sc.cycles.max_bounces = 8; sc.cycles.glossy_bounces = 6; sc.cycles.transmission_bounces = 8
    sc.cycles.caustics_reflective = False; sc.cycles.caustics_refractive = False
    sc.render.resolution_x, sc.render.resolution_y = w, h; sc.render.resolution_percentage = 100
    sc.render.film_transparent = True
    sc.render.image_settings.file_format = 'PNG'; sc.render.image_settings.color_mode = 'RGBA'
    sc.view_settings.view_transform = 'AgX'; sc.view_settings.look = 'AgX - Medium High Contrast'; sc.view_settings.exposure = -1.1
    # fond noir, très légère ambiance pour ne pas boucher les ombres
    # fond rendu transparent (noir dans le lanceur) ; dans les reflets : dégradé gris sombre type studio vendeur
    wd = bpy.data.worlds.new('studio'); sc.world = wd; wd.use_nodes = True; nt = wd.node_tree
    bg = nt.nodes['Background']; tc = nt.nodes.new('ShaderNodeTexCoord'); sep = nt.nodes.new('ShaderNodeSeparateXYZ')
    mr = nt.nodes.new('ShaderNodeMapRange'); mr.inputs['From Min'].default_value = 0.12; mr.inputs['From Max'].default_value = 0.9
    ramp = nt.nodes.new('ShaderNodeValToRGB')
    ramp.color_ramp.elements[0].color = (0.0, 0.0, 0.0, 1); ramp.color_ramp.elements[1].color = (0.22, 0.225, 0.235, 1)   # gris neutre en haut, noir en bas
    nt.links.new(tc.outputs['Normal'], sep.inputs[0]); nt.links.new(sep.outputs['Z'], mr.inputs['Value'])
    nt.links.new(mr.outputs['Result'], ramp.inputs['Fac']); nt.links.new(ramp.outputs['Color'], bg.inputs['Color'])
    bg.inputs['Strength'].default_value = 1.0
    if STYLE == 'ever':
        # cyclorama blanc : lumière enveloppante + grande boîte à lumière au plafond (ombre de contact douce)
        ramp.color_ramp.elements[0].color = (0.62, 0.62, 0.62, 1); ramp.color_ramp.elements[1].color = (1.0, 1.0, 1.0, 1)
        mr.inputs['From Min'].default_value = -0.2; mr.inputs['From Max'].default_value = 0.6
        sc.view_settings.look = 'AgX - Medium High Contrast'
        L = bpy.data.lights.new('softbox', 'AREA'); L.shape = 'RECTANGLE'; L.size = 6.0; L.size_y = 4.0; L.energy = 900
        o = bpy.data.objects.new('softbox', L); o.location = (0.3, 0.6, 5.5); sc.collection.objects.link(o)
        o.visible_camera = False; o.visible_transmission = False
        for nm, loc, en in (('cabinF', (0.35, 0, 1.15), 15 * CABIN_GAIN), ('cabinR', (-0.55, 0, 1.15), 15 * CABIN_GAIN), ('boot', (-1.45, 0, 0.72), TRUNK_W)):
            if en <= 0: continue
            L = bpy.data.lights.new(nm, 'POINT'); L.energy = en; L.shadow_soft_size = 0.4 if nm != 'boot' else 0.3
            o = bpy.data.objects.new(nm, L); o.location = loc; sc.collection.objects.link(o)
            o.visible_glossy = False; o.visible_camera = False; o.visible_transmission = False
        bpy.ops.mesh.primitive_plane_add(size=30, location=(0, 0, 0.001))
        pl = bpy.context.active_object; pl.name = 'shadow'; pl.is_shadow_catcher = True
        pl.data.materials.append(B.mat('floor', (0.8, 0.8, 0.8), rough=0.6))      # sol blanc du studio dans les reflets
        return sc
    def neon(name, loc, rot, length, width, energy):
        L = bpy.data.lights.new(name, 'AREA'); L.shape = 'RECTANGLE'; L.size = length; L.size_y = width
        L.energy = energy * TUBE_GAIN; L.color = TUBE_COLOR
        o = bpy.data.objects.new(name, L); o.location = loc; o.rotation_euler = rot; sc.collection.objects.link(o)
        o.visible_camera = False; o.visible_transmission = False
        return o
    # cadre de 4 tubes néon AU-DESSUS de la voiture (un par côté), orientés vers le bas : la lumière vient d'en haut,
    # le bas de caisse reste sombre et la ligne d'arête ressort
    Hn = 3.3
    neon('neon_L', (0.0, 1.75, Hn), (math.radians(-18), 0, 0), 6.0, 0.10, 900)            # côté gauche (long)
    neon('neon_R', (0.0, -1.75, Hn), (math.radians(18), 0, 0), 6.0, 0.10, 900)            # côté droit (long)
    neon('neon_F', (3.0, 0.0, Hn), (0, math.radians(18), math.radians(90)), 3.6, 0.10, 450)   # avant (court)
    neon('neon_B', (-3.0, 0.0, Hn), (0, math.radians(-18), math.radians(90)), 3.6, 0.10, 450) # arrière (court)
    neon('neon_top', (0.0, 0.0, Hn + 0.6), (0, 0, 0), 4.0, 0.08, 300)                      # fine ligne de toit
    # spots d'accent (depuis le haut, côté) : jantes et optiques, qui sinon ne reflètent que du noir
    def spot(name, loc, tgt, energy, angle=20):
        L = bpy.data.lights.new(name, 'SPOT'); L.energy = energy; L.spot_size = math.radians(angle); L.spot_blend = 0.7
        L.shadow_soft_size = 0.15; L.color = (1.0, 0.98, 0.95)
        o = bpy.data.objects.new(name, L); o.location = loc; sc.collection.objects.link(o)
        o.rotation_euler = (Vector(tgt) - Vector(loc)).to_track_quat('-Z', 'Y').to_euler()
        o.visible_camera = False; o.visible_transmission = False
    for wx in (1.24, -1.28):
        for sy in (1, -1):
            spot('rim_%d_%d' % (wx > 0, sy > 0), (wx + 0.4, sy * 2.6, 2.4), (wx, sy * 0.75, 0.33), 220)
    for sy in (1, -1):
        spot('lamp_%d' % (sy > 0), (3.8, sy * 1.6, 2.2), (1.95, sy * 0.55, 0.68), 160, 16)
    # éclairage d'habitacle (diffus seulement) : sièges et planche de bord visibles derrière les vitres teintées
    for nm, loc in (('cabinF', (0.35, 0, 1.15)), ('cabinR', (-0.55, 0, 1.15))):
        L = bpy.data.lights.new(nm, 'POINT'); L.energy = 30; L.shadow_soft_size = 0.4; L.color = (1.0, 0.95, 0.88)
        o = bpy.data.objects.new(nm, L); o.location = loc; sc.collection.objects.link(o)
        o.visible_glossy = False; o.visible_camera = False; o.visible_transmission = False
    bpy.ops.mesh.primitive_plane_add(size=16, location=(0, 0, 0.001))
    pl = bpy.context.active_object; pl.name = 'shadow'; pl.is_shadow_catcher = True
    # sol noir dans les reflets (sinon le bas de caisse reflète un sol blanc = « lumière par le bas »)
    pl.data.materials.append(B.mat('floor', (0.004, 0.004, 0.005), rough=0.35))
    return sc

STYLE = 'ever'     # 'ever' = studio blanc type Evermotion (validé) ; 'neon' = studio noir à néons
AZ0, ELEV, LENS = 30.0, 14.0, 85.0
TUBE_GAIN, TUBE_COLOR = 1.0, (1.0, 0.98, 0.95)
DIST = 6.4 * LENS / 50.0          # même cadrage qu'avant, sans déformation (85 mm)
CABIN_GAIN, TRUNK_W = 1.0, 0.0     # éclairage d'habitacle / de coffre (lumière diffuse seulement)
SHIFT_Y = -0.034                  # voiture remontée de ~6 % pour que le plateau tienne dans l'image
def view_cam(sc, i):
    return B.camera(sc, AZ0 - 7.5 * i, ELEV, DIST, LENS, shift=(0, SHIFT_Y))

FRONT_HI = (0.0, 24.0)            # azimut, élévation : vue de face légèrement plongeante (portes des deux côtés)
HI_ZOOM = 1.32                    # recul de la caméra pour que portes + toit tiennent dans l'image
def free_cam(sc, az, elev):
    return B.camera(sc, az, elev, DIST * HI_ZOOM, LENS, tgt=(0, 0, 0.55), shift=(0, 0))

def floor_disc(r=2.2):
    """plateau tournant sombre à liseré lumineux (rendu à part : floor.webp, sous la voiture dans le lanceur)"""
    bpy.ops.mesh.primitive_cylinder_add(vertices=180, radius=r, depth=0.06, location=(0, 0, -0.031))
    d = bpy.context.active_object; d.name = 'disc'
    bev = d.modifiers.new('bev', 'BEVEL'); bev.width = 0.02; bev.segments = 4
    top = B.mat('disc_top', (0.012, 0.013, 0.015), rough=0.42)
    rim = B.mat('disc_rim', (0.7, 0.7, 0.72), metal=1.0, rough=0.25, emit=((0.75, 0.77, 0.8), 0.5))
    d.data.materials.append(top); d.data.materials.append(rim)
    for p in d.data.polygons: p.material_index = 0 if abs(p.normal.z) > 0.9 else 1
    return d

def setup_pivots(P):
    """charnières réelles : bord avant de la porte mesuré à hauteur des charnières (0,45–0,85 m),
    légèrement en retrait du bord et 3 cm à l'intérieur de la peau (sans le rétro ni l'encadrement de vitre)"""
    from mathutils import Matrix
    piv = B.setup_pivots(P)          # capot / hayon inchangés
    for k in ('fl', 'fr', 'rl', 'rr'):
        if k not in P: continue
        ob = P[k]; h0 = piv[k][0]
        ob.data.transform(Matrix.Translation(h0)); ob.location = (0, 0, 0)       # on annule le pivot d'origine
        v = np.array([x.co[:] for x in ob.data.vertices])
        band = v[(v[:, 2] > 0.45) & (v[:, 2] < 0.85)]
        left = k in ('fl', 'rl')
        xe = band[:, 0].max(); near = band[band[:, 0] > xe - 0.12]
        y = (near[:, 1].max() - HINGE_IN) if left else (near[:, 1].min() + HINGE_IN)
        h = Vector((xe - HINGE_BACK, y, (band[:, 2].min() + band[:, 2].max()) / 2))
        ob.data.transform(Matrix.Translation(-h)); ob.location = h
        piv[k] = (h, 'Z', (-1 if left else 1), math.radians(DOOR_DEG))
    return piv
HINGE_IN, HINGE_BACK, DOOR_DEG = 0.03, 0.035, 62
