"""Images intermédiaires de la rotation (vue n + ½) par flux optique bidirectionnel (OpenCV DIS)."""
import sys, numpy as np, cv2
from PIL import Image

def load(p):
    a = np.asarray(Image.open(p).convert('RGBA')).astype(np.float32) / 255.0
    return a

def feat(a):
    # image de travail pour le flux : couleur prémultipliée sur fond gris moyen
    rgb = a[..., :3] * a[..., 3:4] + 0.35 * (1 - a[..., 3:4])
    g = cv2.cvtColor((rgb * 255).astype(np.uint8), cv2.COLOR_RGB2GRAY)
    return g

DIS = cv2.DISOpticalFlow_create(cv2.DISOPTICAL_FLOW_PRESET_MEDIUM)
DIS.setFinestScale(0)
def flow(a, b):
    return DIS.calc(feat(a), feat(b), None)

def warp(img, f, t):
    h, w = img.shape[:2]
    gx, gy = np.meshgrid(np.arange(w, dtype=np.float32), np.arange(h, dtype=np.float32))
    mx = gx - t * f[..., 0]; my = gy - t * f[..., 1]
    pm = img.copy(); pm[..., :3] *= pm[..., 3:4]          # prémultiplié pour un rééchantillonnage propre des bords
    out = cv2.remap(pm, mx, my, cv2.INTER_LINEAR, borderMode=cv2.BORDER_CONSTANT, borderValue=0)
    return out

def mid(a, b, t=0.5):
    fab = flow(a, b); fba = flow(b, a)
    wa = warp(a, fab, t); wb = warp(b, fba, 1 - t)
    m = wa * (1 - t) + wb * t
    al = np.clip(m[..., 3:4], 0, 1)
    rgb = np.where(al > 1e-4, m[..., :3] / np.maximum(al, 1e-4), 0)
    return np.concatenate([np.clip(rgb, 0, 1), al], -1)

def save(a, p, q=86):
    im = Image.fromarray((np.clip(a, 0, 1) * 255 + 0.5).astype(np.uint8), 'RGBA')
    if p.endswith('.webp'): im.save(p, 'WEBP', quality=q, method=4)
    else: im.save(p)

if __name__ == '__main__':
    a, b = load(sys.argv[1]), load(sys.argv[2])
    save(mid(a, b), sys.argv[3])
