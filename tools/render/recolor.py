"""Exemple : repeindre un pack existant sans refaire les rendus (ici : vert lime -> blanc, utilisé pour une Ibiza blanche).
Usage : python3 recolor.py source.webp destination.webp  (bande de teinte H0..H3 et courbe GAMMA à adapter à ta peinture d'origine).


Principe : la peinture lime occupe une bande de teinte étroite (≈ 57–70°) que rien d'autre n'occupe dans les rendus
(feux rouges 0–20°, halos orange 25–40°, jantes / vitres / habitacle neutres). Pour chaque pixel :
  - poids de teinte hw (1 dans la bande, 0 en dehors, transitions douces) ;
  - poids de « couleur » cw (chroma) : la peinture est nettement colorée, les reflets gris ne le sont pas ;
  - l'ombrage du pixel t = max(R, G, B) est converti en blanc par une courbe fixe (identique pour toutes les vues,
    donc pas de saut entre deux images de la rotation), puis mélangé selon hw·cw ;
  - tout ce qui est dans la bande de teinte (même peu coloré : reflets de la carrosserie dans les jantes, les vitres)
    est en plus désaturé, pour qu'il ne reste aucune trace de vert.
"""
import sys
import numpy as np

# courbe « blanc » : ombrage de la peinture lime (0..1) -> luminosité de la peinture blanche (0..1)
GAMMA = 0.55      # < 1 : relève les tons moyens (blanc) tout en gardant les volumes
GAIN = 1.0
LIFT = 0.0
TINT_SHADOW = np.array([0.965, 0.98, 1.0])   # ombres très légèrement froides (comme un vrai blanc en studio)
TINT_LIGHT = np.array([1.0, 1.0, 1.0])

def smooth(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)

def hue(rgb):
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    mx = rgb.max(-1); mn = rgb.min(-1); c = mx - mn; cs = np.maximum(c, 1e-6)
    h = np.where(mx == r, ((g - b) / cs) % 6, np.where(mx == g, (b - r) / cs + 2, (r - g) / cs + 4)) * 60
    return h, mx, c

def white_curve(t):
    w = LIFT + GAIN * np.power(np.clip(t, 0, 1), GAMMA)
    return np.clip(w, 0, 1)

def recolor_rgb(rgb, params=None):
    """rgb : float 0..1 (H, W, 3) -> rgb repeint"""
    g = dict(GAMMA=GAMMA, GAIN=GAIN, LIFT=LIFT, H0=44, H1=52, H2=84, H3=96, C0=0.025, C1=0.09, SAT0=0.0)
    if params: g.update(params)
    h, mx, c = hue(rgb)
    hw = smooth(g['H0'], g['H1'], h) * (1 - smooth(g['H2'], g['H3'], h))
    hw = np.where(c < 1e-4, 0, hw)
    cw = smooth(g['C0'], g['C1'], c)
    w = (hw * cw)[..., None]
    t = mx
    W = np.clip(g['LIFT'] + g['GAIN'] * np.power(np.clip(t, 0, 1), g['GAMMA']), 0, 1)
    k = smooth(0.15, 0.85, W)[..., None]
    tint = TINT_SHADOW * (1 - k) + TINT_LIGHT * k
    white = W[..., None] * tint
    # désaturation de tout ce qui est dans la bande de teinte (reflets verts résiduels)
    lum = (rgb * np.array([0.2126, 0.7152, 0.0722])).sum(-1, keepdims=True)
    grey = np.repeat(lum, 3, -1)
    desat = rgb * (1 - hw[..., None]) + grey * hw[..., None]
    out = desat * (1 - w) + white * w
    return np.clip(out, 0, 1)

def process_file(src, dst, params=None, quality=90):
    from PIL import Image
    im = Image.open(src)
    mode = im.mode
    a = np.asarray(im.convert('RGBA' if 'A' in mode else 'RGB')).astype(np.float32) / 255.0
    rgb = a[..., :3]
    out = recolor_rgb(rgb, params)
    if a.shape[2] == 4:
        res = np.concatenate([out, a[..., 3:]], -1)
        img = Image.fromarray((res * 255 + 0.5).astype(np.uint8), 'RGBA')
    else:
        img = Image.fromarray((out * 255 + 0.5).astype(np.uint8), 'RGB')
    if dst.lower().endswith('.webp'):
        img.save(dst, 'WEBP', quality=quality, method=6)
    else:
        img.save(dst)
    return img

if __name__ == '__main__':
    process_file(sys.argv[1], sys.argv[2])
