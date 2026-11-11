"""Rotation continue (v1.22) : flux optique entre vues voisines de la rotation (hero_i -> hero_i+1), réduit et encodé en
PNG RGB pour le lanceur. R,G = déplacement horizontal / vertical en pixels de l'image d'origine : (valeur - 128) / 127 * MAXPX.

Usage (Python 3, pip install opencv-python-headless pillow numpy) :
    python3 flows.py app/src/main/assets/www/img/car app/src/main/assets/www/img/car 240
Écrit flow_00.png … flow_NN.png à côté des vues hero_XX.webp et affiche MAXPX : le reporter dans js/config.js -> "flowMax".
Sans ces fichiers (ou sans "flowMax"), le lanceur garde le fondu enchaîné entre deux vues."""
import sys, os, glob, numpy as np, cv2
from PIL import Image
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from interp import load, feat, warp

DIS = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
DIS.setFinestScale(0)

def fwd(a, b):
    f = DIS.calc(feat(a), feat(b), None)
    # flux lissé (les petites erreurs locales donneraient des déchirures) ; nul hors de la voiture
    m = np.maximum(a[..., 3], b[..., 3])
    m = cv2.dilate(m, np.ones((25, 25), np.uint8))
    f = f * m[..., None]
    f = cv2.GaussianBlur(f, (0, 0), 3)
    return f

def mid_single(a, b, f, t):
    """image à la fraction t avec un seul flux (celui que le lanceur utilise) : A(x - t f) et B(x + (1-t) f)"""
    wa = warp(a, f, t); wb = warp(b, f, -(1 - t))
    m = wa * (1 - t) + wb * t
    al = np.clip(m[..., 3:4], 0, 1)
    rgb = np.where(al > 1e-4, m[..., :3] / np.maximum(al, 1e-4), 0)
    return np.concatenate([np.clip(rgb, 0, 1), al], -1)

if __name__ == '__main__':
    src, dst, W = sys.argv[1], sys.argv[2], int(sys.argv[3])
    files = sorted(glob.glob(os.path.join(src, 'hero_*.webp')))
    n = len(files); mx = 0
    flows = []
    for i in range(n):
        a, b = load(files[i]), load(files[(i + 1) % n])
        f = fwd(a, b); flows.append(f); mx = max(mx, float(np.abs(f).max()))
    print('vues', n, 'déplacement max (px)', round(mx, 1))
    MAXPX = float(sys.argv[4]) if len(sys.argv) > 4 else np.ceil(mx / 8) * 8
    H0, W0 = flows[0].shape[:2]; H = round(W * H0 / W0)
    for i, f in enumerate(flows):
        s = cv2.resize(f, (W, H), interpolation=cv2.INTER_AREA)
        enc = np.clip(np.round(128 + s / MAXPX * 127), 0, 255).astype(np.uint8)
        rgb = np.concatenate([enc, np.full((H, W, 1), 128, np.uint8)], -1)
        Image.fromarray(rgb, 'RGB').save(os.path.join(dst, 'flow_%02d.png' % i), optimize=True)
    print('MAXPX', MAXPX, 'taille', W, 'x', H)
