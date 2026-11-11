#!/usr/bin/env python3
"""Crée un pack voiture Car Launcher (.zip) à installer sans recompiler (lanceur 1.23 ou plus).

Exemples
  python3 tools/pack/make_pack.py --www app/src/main/assets/www --id seat-ibiza-6j-blanc --name "Seat Ibiza 6J blanche"
  python3 tools/pack/make_pack.py --www mon_dossier_www --id golf-7-noir --name "Golf 7 noire" --car-name Golf --version 2

--www       dossier qui contient img/car (+ img/top, img/toph, img/cockpit si présents) et js/config.js
--config    réglages en JSON (clés de config.js) à la place de js/config.js — ex. travail/<id>/reglages.json
--id        identifiant unique : minuscules, chiffres et tirets (sert de nom de dossier sur l'autoradio)
--name      nom affiché dans les réglages du lanceur et dans l'espace client
--flows     dossier où prendre les flow_XX.png si ceux de img/car manquent (même voiture, autre couleur)
--flow-max  valeur flowMax correspondante (sinon celle de config.js)
Le zip contient pack.json, preview.jpg et les images, avec les mêmes noms que dans l'appli.
"""
import argparse, json, os, re, sys, zipfile, io

KEYS = ["frames", "doorViews", "doorSteps", "homeView", "hiView", "comboAnim", "topView", "topBox", "cockpit", "flowMax"]
EXT = (".webp", ".png", ".jpg", ".mp4")

def read_config(www, path=None):
    if path: return json.load(open(path, encoding="utf-8"))
    txt = open(os.path.join(www, "js", "config.js"), encoding="utf-8").read()
    m = re.search(r"IBZ_CONFIG\s*=\s*(\{.*\})\s*;?\s*$", txt, re.S)
    if not m: sys.exit("config.js illisible : " + os.path.join(www, "js", "config.js"))
    return json.loads(m.group(1))

def files_in(d):
    return sorted(f for f in os.listdir(d) if f.lower().endswith(EXT) and re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", f)) if os.path.isdir(d) else []

def preview(www, cfg):
    from PIL import Image
    i = int(cfg.get("homeView", 8)); car = os.path.join(www, "img", "car", "hero_%02d.webp" % i)
    if not os.path.isfile(car): car = os.path.join(www, "img", "car", "hero_00.webp")
    im = Image.open(car).convert("RGBA")
    floor = os.path.join(www, "img", "car", "floor.webp")
    bg = Image.new("RGBA", im.size, (24, 25, 29, 255))
    if os.path.isfile(floor): bg.alpha_composite(Image.open(floor).convert("RGBA").resize(im.size))
    bg.alpha_composite(im)
    bg = bg.convert("RGB"); bg.thumbnail((480, 480))
    out = io.BytesIO(); bg.save(out, "JPEG", quality=82, optimize=True); return out.getvalue()

def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--www", required=True); ap.add_argument("--id", required=True); ap.add_argument("--name", required=True)
    ap.add_argument("--version", default="1"); ap.add_argument("--car-name"); ap.add_argument("--car-model")
    ap.add_argument("--flows"); ap.add_argument("--flow-max", type=int); ap.add_argument("--out", default=".")
    ap.add_argument("--config")
    a = ap.parse_args()
    if not re.fullmatch(r"[a-z0-9][a-z0-9-]{1,60}", a.id): sys.exit("--id : minuscules, chiffres et tirets seulement (2 à 61 caractères)")
    cfg = read_config(a.www, a.config)
    img = os.path.join(a.www, "img")
    car = files_in(os.path.join(img, "car"))
    frames = int(cfg.get("frames", 48))
    missing = ["hero_%02d.webp" % i for i in range(frames) if "hero_%02d.webp" % i not in car]
    if missing: sys.exit("images manquantes dans img/car : " + ", ".join(missing[:5]) + ("…" if len(missing) > 5 else ""))

    entries = {"img/car/" + f: os.path.join(img, "car", f) for f in car}
    flow_max = a.flow_max or cfg.get("flowMax")
    has_flows = all(("img/car/flow_%02d.png" % i) in entries for i in range(frames))
    if not has_flows and a.flows:
        for i in range(frames):
            src = os.path.join(a.flows, "flow_%02d.png" % i)
            if not os.path.isfile(src): sys.exit("--flows : il manque " + src)
            entries["img/car/flow_%02d.png" % i] = src
        has_flows = True
    if has_flows and not flow_max: sys.exit("flux de rotation présents mais aucune valeur flowMax (config.js ou --flow-max)")

    top = files_in(os.path.join(img, "top")); toph = files_in(os.path.join(img, "toph"))
    top_ok = bool(cfg.get("topView")) and "closed.webp" in top          # img/toph : facultatif (ancien format)
    if top_ok:
        entries.update({"img/top/" + f: os.path.join(img, "top", f) for f in top})
        if "closed.webp" in toph: entries.update({"img/toph/" + f: os.path.join(img, "toph", f) for f in toph})
    ck = files_in(os.path.join(img, "cockpit"))
    ck_ok = bool(cfg.get("cockpit")) and len(ck) > 0
    if ck_ok: entries.update({"img/cockpit/" + f: os.path.join(img, "cockpit", f) for f in ck})

    pcfg = {k: cfg[k] for k in KEYS if k in cfg}
    pcfg["topView"] = top_ok; pcfg["cockpit"] = ck_ok
    pcfg["hiView"] = bool(cfg.get("hiView")) and any(f.startswith("door_hi_") for f in car)
    pcfg["comboAnim"] = bool(cfg.get("comboAnim")) and any(f.startswith("door_m") for f in car)
    if has_flows: pcfg["flowMax"] = int(flow_max)
    else: pcfg.pop("flowMax", None)
    if not top_ok: pcfg.pop("topBox", None)
    carinfo = {}
    if a.car_name or cfg.get("car", {}).get("name"): carinfo["name"] = a.car_name or cfg["car"]["name"]
    if a.car_model or cfg.get("car", {}).get("model"): carinfo["model"] = a.car_model or cfg["car"]["model"]
    if carinfo: pcfg["car"] = carinfo
    manifest = {"format": 1, "id": a.id, "name": a.name, "version": str(a.version), "config": pcfg}

    os.makedirs(a.out, exist_ok=True)
    dest = os.path.join(a.out, a.id + ".zip")
    with zipfile.ZipFile(dest, "w", zipfile.ZIP_DEFLATED) as z:
        z.writestr("pack.json", json.dumps(manifest, ensure_ascii=False, indent=1))
        try: z.writestr("preview.jpg", preview(a.www, cfg))
        except Exception as e: print("aperçu non créé :", e)
        for name, src in sorted(entries.items()):
            z.write(src, name, compress_type=zipfile.ZIP_STORED if name.endswith((".webp", ".jpg", ".mp4", ".png")) else zipfile.ZIP_DEFLATED)
    size = os.path.getsize(dest)
    print("Pack créé :", dest, "(%.1f Mo, %d images)" % (size / 1048576, len(entries)))
    print("  rotation fluide :", "oui (flowMax %s)" % pcfg.get("flowMax") if has_flows else "non (fondu)", "| vue du dessus :", "oui" if top_ok else "non",
          "| habitacle :", "oui" if ck_ok else "non", "| vue de face :", "oui" if pcfg["hiView"] else "non")

if __name__ == "__main__":
    main()
