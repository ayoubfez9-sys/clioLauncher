import glob, os, subprocess
from PIL import Image, ImageFilter
SRC = 'out/cockpit/'; TMP = 'out/ckv/'; os.makedirs(TMP, exist_ok=True)
DST = '../../app/src/main/assets/www/img/cockpit/'
photo = Image.open('model/dash_1024.png').convert('RGB')
fr = sorted(glob.glob(SRC + 'ck_*.png')); k = 0
def comp(f):
    im = Image.open(f).convert('RGBA'); bg = Image.new('RGBA', im.size, (10, 11, 14, 255)); bg.alpha_composite(im); return bg.convert('RGB')
last = None
for f in fr:
    last = comp(f); last.save(TMP + 'f_%03d.png' % k); k += 1
comp(fr[0]).save(DST + 'start.webp', quality=85)
XF = 16                                    # fondu enchaîné rendu 3D -> photo (≈0,5 s) avec léger zoom arrière de la photo
for j in range(1, XF + 1):
    u = j / XF; e = u * u * (3 - 2 * u); z = 1.0
    w, h = round(1024 * z), round(600 * z); p = photo.resize((w, h), Image.LANCZOS).crop(((w - 1024) // 2, (h - 600) // 2, (w - 1024) // 2 + 1024, (h - 600) // 2 + 600))
    import math
    a = last.filter(ImageFilter.GaussianBlur(10 * math.sin(e * math.pi / 2)))       # fondu flouté : masque l'écart rendu / photo
    b = p.filter(ImageFilter.GaussianBlur(10 * math.cos(e * math.pi / 2))) if e < 1 else p
    Image.blend(a, b, e).save(TMP + 'f_%03d.png' % k); k += 1
for j in range(4): photo.save(TMP + 'f_%03d.png' % k); k += 1
subprocess.run(['ffmpeg', '-y', '-loglevel', 'error', '-framerate', '30', '-i', TMP + 'f_%03d.png', '-c:v', 'libx264', '-preset', 'slow', '-crf', '19',
                '-profile:v', 'main', '-level', '4.0', '-pix_fmt', 'yuv420p', '-movflags', '+faststart', DST + 'intro.mp4'], check=True)
print(k, 'frames', os.path.getsize(DST + 'intro.mp4') // 1024, 'KB')
