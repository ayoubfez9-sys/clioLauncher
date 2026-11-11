"""Construit la scène Blender de l'Ibiza 5 portes à partir du .3DS : pièces mobiles séparées (portes, capot, hayon)."""
import sys, math, numpy as np
sys.path.insert(0, '.')
from read3ds import read3ds
from islands import islands
import bpy
from mathutils import Vector

SRC = 'model/car.3ds'
S = 4.05 / 36540.0          # unités 3DS -> mètres (longueur Ibiza 6J ≈ 4,05 m)
CX, CY, Z0 = -1456.0, 1590.0, -11974.8
YMID = 1590.0

def srgb(c):
    return tuple(((x / 255.0 + 0.055) / 1.055) ** 2.4 if x / 255.0 > 0.04045 else x / 255.0 / 12.92 for x in c)

def mat(name, base, metal=0.0, rough=0.5, coat=0.0, trans=0.0, alpha=1.0, emit=None, ior=1.45):
    m = bpy.data.materials.new(name); m.use_nodes = True
    p = m.node_tree.nodes['Principled BSDF']
    p.inputs['Base Color'].default_value = (*base, 1)
    p.inputs['Metallic'].default_value = metal
    p.inputs['Roughness'].default_value = rough
    p.inputs['Coat Weight'].default_value = coat
    p.inputs['Coat Roughness'].default_value = 0.03
    p.inputs['Transmission Weight'].default_value = trans
    p.inputs['IOR'].default_value = ior
    p.inputs['Alpha'].default_value = alpha
    if emit:
        p.inputs['Emission Color'].default_value = (*emit[0], 1); p.inputs['Emission Strength'].default_value = emit[1]
    return m

LOOK = {'name': 'A'}   # 'A' = version préférée (sombre, bandes fines) ; 'B' = Amarillo Citrus + showroom

def flakes_v1(m):
    """version préférée : micro-relief Voronoi sous le vernis + « flop » (teinte plus sombre en incidence rasante)"""
    nt = m.node_tree; p = nt.nodes['Principled BSDF']
    tc = nt.nodes.new('ShaderNodeTexCoord'); vo = nt.nodes.new('ShaderNodeTexVoronoi'); vo.inputs['Scale'].default_value = 2500
    bump = nt.nodes.new('ShaderNodeBump'); bump.inputs['Strength'].default_value = 0.12; bump.inputs['Distance'].default_value = 0.0005
    nt.links.new(tc.outputs['Object'], vo.inputs['Vector']); nt.links.new(vo.outputs['Distance'], bump.inputs['Height'])
    nt.links.new(bump.outputs['Normal'], p.inputs['Normal'])
    lw = nt.nodes.new('ShaderNodeLayerWeight'); lw.inputs['Blend'].default_value = 0.45
    mix = nt.nodes.new('ShaderNodeMix'); mix.data_type = 'RGBA'
    c = p.inputs['Base Color'].default_value; mix.inputs['A'].default_value = c[:]
    mix.inputs['B'].default_value = (c[0] * 0.35, c[1] * 0.42, c[2] * 0.2, 1)
    nt.links.new(lw.outputs['Facing'], mix.inputs['Factor']); nt.links.new(mix.outputs['Result'], p.inputs['Base Color'])

def flakes_v2(m):
    """recette Amarillo Citrus : Voronoi F1 (échelle 7000) -> ColorRamp -> rugosité ; vernis net 0,03 ; + flop léger"""
    nt = m.node_tree; p = nt.nodes['Principled BSDF']
    p.inputs['Coat Roughness'].default_value = 0.03
    tc = nt.nodes.new('ShaderNodeTexCoord'); vo = nt.nodes.new('ShaderNodeTexVoronoi')
    vo.feature = 'F1'; vo.distance = 'EUCLIDEAN'; vo.inputs['Scale'].default_value = 7000
    nt.links.new(tc.outputs['Object'], vo.inputs['Vector'])
    cr = nt.nodes.new('ShaderNodeValToRGB')
    cr.color_ramp.elements[0].position = 0.0; cr.color_ramp.elements[0].color = (0.30, 0.30, 0.30, 1)
    cr.color_ramp.elements[1].position = 0.8; cr.color_ramp.elements[1].color = (0.40, 0.40, 0.40, 1)
    nt.links.new(vo.outputs['Distance'], cr.inputs['Fac']); nt.links.new(cr.outputs['Color'], p.inputs['Roughness'])
    bump = nt.nodes.new('ShaderNodeBump'); bump.inputs['Strength'].default_value = 0.12; bump.inputs['Distance'].default_value = 0.0003
    nt.links.new(vo.outputs['Distance'], bump.inputs['Height']); nt.links.new(bump.outputs['Normal'], p.inputs['Normal'])
    lw = nt.nodes.new('ShaderNodeLayerWeight'); lw.inputs['Blend'].default_value = 0.4
    mix = nt.nodes.new('ShaderNodeMix'); mix.data_type = 'RGBA'
    c = p.inputs['Base Color'].default_value; mix.inputs['A'].default_value = c[:]
    mix.inputs['B'].default_value = (c[0] * 0.45, c[1] * 0.5, c[2] * 0.25, 1)
    nt.links.new(lw.outputs['Facing'], mix.inputs['Factor']); nt.links.new(mix.outputs['Result'], p.inputs['Base Color'])

def materials():
    B = LOOK['name'] == 'B'
    M = {}
    if B:
        M['body'] = mat('paint', srgb((0xC4, 0xCC, 0x24)), metal=1.0, rough=0.35, coat=1.0); flakes_v2(M['body'])   # Seat S1T Amarillo Citrus
    else:
        M['body'] = mat('paint', srgb((146, 160, 0)), metal=0.85, rough=0.36, coat=1.0); flakes_v1(M['body'])
    M['gum'] = mat('plastic', (0.015, 0.015, 0.016), rough=0.55)
    M['gum1'] = mat('interior', (0.11, 0.11, 0.115), rough=0.6)
    M['door'] = M['dash'] = M['stwheel'] = M['gum1']
    M['silver'] = mat('rim', (0.40, 0.41, 0.43), metal=1.0, rough=0.38)   # jantes alu satiné
    M['tire'] = mat('tire', (0.018, 0.018, 0.019), rough=0.85)
    M['brake'] = mat('brake', (0.28, 0.28, 0.29), metal=1.0, rough=0.45)
    M['chrome'] = mat('chrome', (0.95, 0.95, 0.96), metal=1.0, rough=0.04)
    M['glass'] = mat('glass', (0.95, 0.97, 1.0), rough=0.0, trans=1.0)
    M['d glass'] = mat('tint', (0.2, 0.215, 0.23), rough=0.02, trans=1.0, ior=1.3)
    M['d glass'].node_tree.nodes['Principled BSDF'].inputs['Specular IOR Level'].default_value = 0.3 if not B else 0.2
    M['r glass'] = mat('redglass', (0.55, 0.02, 0.02), rough=0.08, trans=0.7)
    M['red l'] = mat('redlamp', (0.35, 0.01, 0.01), rough=0.25)
    M['orange'] = mat('orange', (0.85, 0.32, 0.02), rough=0.2)
    M['black'] = mat('black', (0.008, 0.008, 0.009), rough=0.25)
    M['plate'] = mat('plate', (0.8, 0.8, 0.8), rough=0.4)
    return M

def to_bl(v):
    v = np.asarray(v, dtype=np.float64)
    return np.stack([(v[:, 0] - CX) * S, (v[:, 1] - CY) * S, (v[:, 2] - Z0) * S], 1)

def make_obj(name, verts, faces, fmats, M, coll):
    """verts: Nx3 (3DS), faces: Fx3, fmats: list of material name per face"""
    used = np.unique(faces.ravel())
    remap = -np.ones(len(verts), dtype=np.int64); remap[used] = np.arange(len(used))
    vv = to_bl(verts[used]); ff = remap[faces]
    me = bpy.data.meshes.new(name)
    me.from_pydata(vv.tolist(), [], ff.tolist())
    names = sorted(set(fmats))
    for n in names: me.materials.append(M.get(n, M['gum']))
    idx = {n: i for i, n in enumerate(names)}
    me.polygons.foreach_set('material_index', [idx[n] for n in fmats])
    me.validate(); me.update()
    me.polygons.foreach_set('use_smooth', [True] * len(me.polygons))
    try: me.set_sharp_from_angle(angle=math.radians(35))
    except Exception: pass
    ob = bpy.data.objects.new(name, me); coll.objects.link(ob)
    return ob

def side_of(pts):  # 'L' (+y) ou 'R'
    return 'L' if pts[:, 1].mean() > YMID else 'R'

TIRES = {'gum164', 'gum169', 'gum177', 'gum178'}
DISCS = {'silver01', 'silver05', 'silver08', 'silver09'}
def build(M):
    obs, _ = read3ds(SRC)
    coll = bpy.context.scene.collection
    parts = {k: [] for k in ['static', 'fl', 'fr', 'rl', 'rr', 'hood', 'trunk']}   # (verts, faces, fmats)

    def add(part, ob, fidx):
        if len(fidx) == 0: return
        v = np.array(ob['verts']); f = np.array(ob['faces'], dtype=np.int64)[fidx]
        fm = facemats(ob)[fidx].copy()
        if ob['name'] in TIRES: fm[fm == 'gum'] = 'tire'
        if ob['name'] in DISCS: fm[:] = 'brake'
        parts[part].append((v, f, fm))

    cache = {}
    def facemats(ob):
        k = id(ob)
        if k not in cache:
            fm = np.array([ob['fmat'][0][0] if ob['fmat'] else 'gum'] * len(ob['faces']), dtype=object)
            for mname, idx in ob['fmat']: fm[list(idx)] = mname
            cache[k] = fm
        return cache[k]

    for ob in obs:
        n = ob['name']; F = len(ob['faces'])
        if F == 0: continue
        v = np.array(ob['verts']); f = np.array(ob['faces'], dtype=np.int64)
        cen = v[f].mean(1)                       # centres des faces
        left = cen[:, 1] > YMID
        assigned = np.zeros(F, bool)
        def take(part, mask):
            m = mask & ~assigned
            add(part, ob, np.where(m)[0]); assigned[m] = True
        X, Z = cen[:, 0], cen[:, 2]
        if n == 'body02' or n == 'chrome4':                        # portes avant + rétros
            take('fl', left); take('fr', ~left)
        elif n in ('black02', 'd glass01'): take('fr', np.ones(F, bool))     # vitres triangle avant
        elif n in ('black1', 'd glass0'): take('fl', np.ones(F, bool))
        elif n == 'body01':
            for fi, lo, hi in islands(ob):
                m = np.zeros(F, bool); m[fi] = True
                if hi[0] < -2500 and lo[0] > -13000 and (hi[1] < -3000 or lo[1] > 6000) and hi[2] < -1500:
                    take('rl' if lo[1] > 6000 else 'rr', m)        # portes arrière + poignées
                elif lo[0] < -18800 and hi[0] < -16500 and lo[2] > -6500:
                    take('trunk', m)                               # hayon
        elif n == 'body':
            for fi, lo, hi in islands(ob):
                if lo[2] > -5700 and lo[0] > 7000 and hi[2] > -3500:
                    m = np.zeros(F, bool); m[fi] = True; take('hood', m)
        elif n == 'd glass' or n == 'door':
            take('fl', left & (X > -3900) & (X < 6300)); take('fr', ~left & (X > -3900) & (X < 6300))
            take('rl', left & (X > -12200) & (X < -3200) & (Z < 450)); take('rr', ~left & (X > -12200) & (X < -3200) & (Z < 450))
            if n == 'd glass': take('trunk', X < -16650)
        elif n == 'black01':
            take('trunk', X < -16650)
        elif n in ('chrome0', 'gum02', 'chrome1', 'gum6', 'gum1'):
            take('trunk', np.ones(F, bool))
        take('static', np.ones(F, bool))

    out = {}
    for part, chunks in parts.items():
        if not chunks: continue
        V = []; Fs = []; FM = []; off = 0
        for v, f, fm in chunks:
            V.append(v); Fs.append(f + off); FM.extend(fm.tolist()); off += len(v)
        out[part] = make_obj('car_' + part, np.concatenate(V), np.concatenate(Fs), FM, M, coll)
    return out

# ---------- charnières ----------
def setup_pivots(P):
    """place l'origine de chaque pièce mobile sur sa charnière ; renvoie {part: (axe, signe, angle max)}"""
    def bb(ob):
        c = np.array([ob.matrix_world @ Vector(x) for x in ob.bound_box]); return c.min(0), c.max(0)
    piv = {}
    for k in ('fl', 'fr', 'rl', 'rr'):
        if k not in P: continue
        lo, hi = bb(P[k])
        y = hi[1] - 0.04 if k in ('fl', 'rl') else lo[1] + 0.04
        hinge = Vector((hi[0] - 0.05, y, (lo[2] + hi[2]) / 2))
        piv[k] = (hinge, 'Z', (-1 if k in ('fl', 'rl') else 1), math.radians(62))
    if 'hood' in P:
        lo, hi = bb(P['hood'])
        piv['hood'] = (Vector((lo[0] + 0.03, 0, hi[2] - 0.01)), 'Y', -1, math.radians(48))
    if 'trunk' in P:
        lo, hi = bb(P['trunk'])
        piv['trunk'] = (Vector((hi[0] - 0.02, 0, hi[2] - 0.01)), 'Y', 1, math.radians(62))
    for k, (h, ax, sg, mx) in piv.items():
        ob = P[k]
        ob.data.transform(__import__('mathutils').Matrix.Translation(-h)); ob.location = h
    return piv

def set_open(P, piv, k, t, ease=False):
    h, ax, sg, mx = piv[k]
    e = 1 - (1 - t) ** 2 if ease else t
    r = [0, 0, 0]; r['XYZ'.index(ax)] = sg * mx * e
    P[k].rotation_euler = r

def scene_setup(engine='CYCLES', w=600, h=335, samples=48):
    sc = bpy.context.scene
    sc.render.engine = engine
    sc.render.resolution_x, sc.render.resolution_y = w, h
    sc.render.resolution_percentage = 100
    sc.render.film_transparent = True
    sc.render.image_settings.file_format = 'PNG'; sc.render.image_settings.color_mode = 'RGBA'
    B = LOOK['name'] == 'B'
    sc.view_settings.view_transform = 'AgX'; sc.view_settings.look = 'AgX - Punchy'; sc.view_settings.exposure = -0.6 if not B else -0.75
    if engine == 'CYCLES':
        sc.cycles.samples = samples; sc.cycles.use_denoising = True
        sc.cycles.device = 'CPU'; sc.cycles.max_bounces = 6; sc.cycles.transmission_bounces = 6
        sc.cycles.caustics_reflective = False; sc.cycles.caustics_refractive = False
    # monde : dégradé studio (reflets de la carrosserie)
    wd = bpy.data.worlds.new('studio'); sc.world = wd; wd.use_nodes = True
    nt = wd.node_tree; nt.nodes.clear()
    out = nt.nodes.new('ShaderNodeOutputWorld'); bg = nt.nodes.new('ShaderNodeBackground')
    tc = nt.nodes.new('ShaderNodeTexCoord'); sep = nt.nodes.new('ShaderNodeSeparateXYZ'); ramp = nt.nodes.new('ShaderNodeValToRGB')
    nt.links.new(tc.outputs['Generated'], sep.inputs[0])
    mp = nt.nodes.new('ShaderNodeMapRange'); mp.inputs['From Min'].default_value = 0.35; mp.inputs['From Max'].default_value = 0.75
    nt.links.new(sep.outputs['Z'], mp.inputs['Value']); nt.links.new(mp.outputs['Result'], ramp.inputs['Fac'])
    ramp.color_ramp.elements[0].color = (0.003, 0.004, 0.006, 1); ramp.color_ramp.elements[1].color = (0.16, 0.18, 0.22, 1)
    e = ramp.color_ramp.elements.new(0.5); e.color = (0.035, 0.04, 0.05, 1)
    nt.links.new(ramp.outputs['Color'], bg.inputs['Color']); bg.inputs['Strength'].default_value = 1.0
    nt.links.new(bg.outputs[0], out.inputs[0])
    # éclairage : grande boîte à lumière au plafond + deux bandes latérales
    def area(name, loc, rot, size, energy, sx=None, col=(1, 1, 1)):
        L = bpy.data.lights.new(name, 'AREA'); L.energy = energy; L.shape = 'RECTANGLE'; L.size = size; L.size_y = sx or size; L.color = col
        o = bpy.data.objects.new(name, L); o.location = loc; o.rotation_euler = rot; sc.collection.objects.link(o)
        o.visible_camera = False; o.visible_transmission = False     # jamais vues à travers les vitres
        return o
    if not B:
        # A — version préférée : studio sombre, bandes fines qui dessinent les lignes de caisse
        top = area('top', (0, 0, 5.0), (0, 0, 0), 4.0, 650, 2.2, (1.0, 0.96, 0.9)); top.visible_glossy = False
        area('stripL1', (0.0, 4.2, 2.8), (math.radians(-55), 0, 0), 8.0, 260, 0.22, (0.85, 0.92, 1.0))
        area('stripL2', (0.0, 4.6, 1.1), (math.radians(-80), 0, 0), 8.0, 140, 0.12, (1.0, 0.85, 0.62))
        area('stripR1', (0.0, -4.2, 2.8), (math.radians(55), 0, 0), 8.0, 260, 0.22, (0.85, 0.92, 1.0))
        area('stripR2', (0.0, -4.6, 1.1), (math.radians(80), 0, 0), 8.0, 140, 0.12, (1.0, 0.85, 0.62))
        area('stripF', (4.8, 0, 2.6), (0, math.radians(58), 0), 0.25, 220, 5.0, (0.9, 0.95, 1.0))
        area('stripB', (-4.8, 0, 2.6), (0, math.radians(-58), 0), 0.25, 220, 5.0, (0.9, 0.95, 1.0))
        area('roof', (0.4, 0, 4.2), (0, 0, 0), 0.35, 260, 6.0, (0.9, 0.95, 1.0))
    else:
        # B — showroom : grands diffuseurs (reflets longs et homogènes), blanc neutre ~5000 K, spots d'accent
        W = (1.0, 0.97, 0.93)
        top = area('top', (0, 0, 4.6), (0, 0, 0), 5.5, 900, 2.6, W); top.visible_glossy = False
        area('ceilL', (0.0, 1.2, 4.4), (math.radians(-12), 0, 0), 7.0, 220, 0.9, W)       # deux cadres lumineux au plafond
        area('ceilR', (0.0, -1.2, 4.4), (math.radians(12), 0, 0), 7.0, 220, 0.9, W)
        area('wallL', (0.0, 4.4, 1.9), (math.radians(-75), 0, 0), 8.5, 330, 1.4, W)       # grands diffuseurs latéraux
        area('wallR', (0.0, -4.4, 1.9), (math.radians(75), 0, 0), 8.5, 330, 1.4, W)
        area('grazeL', (0.0, 4.6, 0.45), (math.radians(-88), 0, 0), 8.5, 90, 0.08, (1.0, 0.86, 0.66))   # rubans LED rasants
        area('grazeR', (0.0, -4.6, 0.45), (math.radians(88), 0, 0), 8.5, 90, 0.08, (1.0, 0.86, 0.66))
        area('front', (5.0, 0, 2.2), (0, math.radians(62), 0), 1.8, 260, 3.2, W).visible_glossy = False
        area('back', (-5.0, 0, 2.2), (0, math.radians(-62), 0), 1.8, 220, 3.2, W)
        def spot(name, loc, tgt, energy, angle=18):
            L = bpy.data.lights.new(name, 'SPOT'); L.energy = energy; L.spot_size = math.radians(angle); L.spot_blend = 0.6
            L.shadow_soft_size = 0.05; L.color = (1.0, 0.98, 0.95)
            o = bpy.data.objects.new(name, L); o.location = loc; sc.collection.objects.link(o)
            o.rotation_euler = (Vector(tgt) - Vector(loc)).to_track_quat('-Z', 'Y').to_euler()
            o.visible_camera = False; o.visible_transmission = False
        for sx in (1.24, -1.25):                      # accent jantes (avant / arrière, des deux côtés)
            for sy in (1, -1):
                spot('rim%.0f%d' % (sx, sy), (sx + 0.6, sy * 3.2, 1.6), (sx, sy * 0.75, 0.32), 160)
        spot('lampL', (4.2, 1.8, 1.6), (1.95, 0.55, 0.65), 120, 14)     # accent phares
        spot('lampR', (4.2, -1.8, 1.6), (1.95, -0.55, 0.65), 120, 14)
    # éclairage d'habitacle (diffus seulement) pour deviner sièges et planche de bord derrière les vitres teintées
    for nm, loc in (('cabinF', (0.35, 0, 1.15)), ('cabinR', (-0.55, 0, 1.15))):
        L = bpy.data.lights.new(nm, 'POINT'); L.energy = 30; L.shadow_soft_size = 0.4; L.color = (1.0, 0.95, 0.88)
        o = bpy.data.objects.new(nm, L); o.location = loc; sc.collection.objects.link(o)
        o.visible_glossy = False; o.visible_camera = False; o.visible_transmission = False
    # attrape-ombres
    bpy.ops.mesh.primitive_plane_add(size=14, location=(0, 0, 0.002))
    pl = bpy.context.active_object; pl.name = 'shadow'; pl.is_shadow_catcher = True
    return sc

def camera(sc, az_deg, elev_deg, dist, lens, tgt=(0, 0, 0.62), shift=(0, 0)):
    cam = sc.camera
    if cam is None:
        cd = bpy.data.cameras.new('cam'); cam = bpy.data.objects.new('cam', cd); sc.collection.objects.link(cam); sc.camera = cam
    cam.data.lens = lens; cam.data.shift_x, cam.data.shift_y = shift
    a, e = math.radians(az_deg), math.radians(elev_deg)
    t = Vector(tgt)
    cam.location = t + Vector((math.cos(a) * math.cos(e), math.sin(a) * math.cos(e), math.sin(e))) * dist
    d = t - cam.location
    cam.rotation_euler = d.to_track_quat('-Z', 'Y').to_euler()
    return cam

AZ0, ELEV, DIST, LENS = 30.0, 14.0, 6.4, 50.0
def view_cam(sc, i):
    return camera(sc, AZ0 - 7.5 * i, ELEV, DIST, LENS)

def turntable(sc):
    """plateau tournant (rendu à part : floor.webp)"""
    bpy.ops.mesh.primitive_cylinder_add(vertices=160, radius=2.35, depth=0.07, location=(0, 0, -0.035))
    d = bpy.context.active_object; d.name = 'disc'
    bev = d.modifiers.new('bev', 'BEVEL'); bev.width = 0.025; bev.segments = 4
    top = mat('disc_top', (0.035, 0.036, 0.04), rough=0.55)
    rim = mat('disc_rim', (0.7, 0.7, 0.72), rough=0.35, emit=((0.6, 0.62, 0.66), 0.35))
    d.data.materials.append(top); d.data.materials.append(rim)
    for p in d.data.polygons: p.material_index = 0 if abs(p.normal.z) > 0.9 else 1
    return d
