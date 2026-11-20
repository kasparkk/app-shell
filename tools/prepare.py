#!/usr/bin/env python3
"""Bereitet den Android-Build fuer eine App vor.

  python3 tools/prepare.py <app>      # liest apps/<app>.json

Holt das PWA-Manifest der Live-URL (Name, Farben, Icon) und schreibt:
  app/generated.properties                 Paketname fuer Gradle
  app/src/main/assets/config.json          URL, Hosts, Farben fuer die App
  app/src/main/res/values/generated.xml    App-Name und Farben
  app/src/main/res/mipmap-*/ic_fg.png      Vordergrund des Adaptive Icons
Werte in apps/<app>.json haben Vorrang vor dem Manifest.
"""
import io
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from html.parser import HTMLParser
from urllib.parse import urljoin
from xml.sax.saxutils import escape

from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
APP = os.path.join(ROOT, "app")
RES = os.path.join(APP, "src", "main", "res")
UA = "Mozilla/5.0 (Linux; Android 15) AppShellBuild/1"
DENSITIES = {"mdpi": 108, "hdpi": 162, "xhdpi": 216, "xxhdpi": 324, "xxxhdpi": 432}


def log(*a):
    print("[prepare]", *a, flush=True)


def fetch(url, timeout=30):
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        return r.read(), r.headers.get("Content-Type", ""), r.geturl()


class Links(HTMLParser):
    def __init__(self):
        super().__init__()
        self.links = []
        self.meta = {}

    def handle_starttag(self, tag, attrs):
        a = dict(attrs)
        if tag == "link" and a.get("href"):
            self.links.append(((a.get("rel") or "").lower(), a["href"], a.get("sizes") or ""))
        if tag == "meta" and a.get("name"):
            self.meta[a["name"].lower()] = a.get("content") or ""


def find_manifest(url):
    """-> (manifest dict, manifest url, html links, meta)"""
    links, meta, base = [], {}, url
    try:
        body, _, base = fetch(url)
        p = Links()
        p.feed(body.decode("utf-8", "replace"))
        links, meta = p.links, p.meta
    except Exception as e:
        log("Seite nicht ladbar:", e)
    cands = [urljoin(base, h) for rel, h, _ in links if "manifest" in rel.split()]
    cands += [urljoin(base, x) for x in ("manifest.webmanifest", "site.webmanifest", "manifest.json",
                                         "/manifest.webmanifest", "/site.webmanifest", "/manifest.json")]
    seen = set()
    for c in cands:
        if c in seen:
            continue
        seen.add(c)
        try:
            body, _, real = fetch(c)
            m = json.loads(body.decode("utf-8-sig"))
            if isinstance(m, dict) and ("icons" in m or "name" in m):
                log("Manifest:", real)
                return m, real, links, meta, base
        except Exception:
            pass
    log("kein Manifest gefunden")
    return {}, base, links, meta, base


def size_of(s):
    best = 0
    for part in (s or "").split():
        if part == "any":
            return 4096
        m = re.match(r"(\d+)x(\d+)", part)
        if m:
            best = max(best, int(m.group(1)))
    return best


def icon_candidates(manifest, murl, links, base):
    """Sortiert: maskable vor normal, PNG vor SVG, gross vor klein."""
    out = []
    for ic in manifest.get("icons", []) or []:
        src = ic.get("src")
        if not src:
            continue
        purpose = (ic.get("purpose") or "any").split()
        u = urljoin(murl, src)
        svg = u.lower().split("?")[0].endswith(".svg") or "svg" in (ic.get("type") or "")
        out.append({"url": u, "maskable": "maskable" in purpose, "svg": svg, "size": size_of(ic.get("sizes"))})
    for rel, href, sizes in links:
        rels = rel.split()
        if "apple-touch-icon" in rels or "icon" in rels:
            u = urljoin(base, href)
            out.append({"url": u, "maskable": False, "svg": u.lower().split("?")[0].endswith(".svg"),
                        "size": size_of(sizes) or (180 if "apple-touch-icon" in rels else 32)})
    out.sort(key=lambda c: (not c["maskable"], c["svg"], -c["size"]))
    return out


def svg_to_png(data, size=1024):
    tool = shutil.which("rsvg-convert")
    if tool:
        r = subprocess.run([tool, "-w", str(size), "-h", str(size), "--keep-aspect-ratio"], input=data,
                           capture_output=True, check=True)
        return r.stdout
    import cairosvg  # Fallback
    return cairosvg.svg2png(bytestring=data, output_width=size, output_height=size)


def load_image(c):
    data, ctype, _ = fetch(c["url"])
    if c["svg"] or "svg" in ctype or data.lstrip()[:5] in (b"<?xml", b"<svg ") or b"<svg" in data[:300]:
        data = svg_to_png(data)
    im = Image.open(io.BytesIO(data))
    im.load()
    return im.convert("RGBA")


def hex_of(rgb):
    return "#%02x%02x%02x" % tuple(rgb[:3])


def corners_opaque(im):
    w, h = im.size
    pts = [(1, 1), (w - 2, 1), (1, h - 2), (w - 2, h - 2)]
    return all(im.getpixel(p)[3] > 240 for p in pts)


def is_tile(im):
    w, h = im.size
    mids = [(w // 2, 3), (w // 2, h - 4), (3, h // 2), (w - 4, h // 2)]
    return all(im.getpixel(p)[3] > 240 for p in mids)


def make_foreground(im, maskable, bg_hex):
    """-> (Vordergrund 432x432, Hintergrundfarbe). Adaptive Icon: 108dp, sichtbar 72dp, sicher 66dp."""
    S = 432
    im = im.copy()
    if im.width != im.height:
        side = max(im.size)
        sq = Image.new("RGBA", (side, side), (0, 0, 0, 0))
        sq.paste(im, ((side - im.width) // 2, (side - im.height) // 2))
        im = sq
    if maskable:
        # Sicherheitszone (80 %) passt so in den sichtbaren Kreis, Rand wird mit der Randfarbe aufgefuellt
        scale, edge = 0.82, True
    elif corners_opaque(im):
        # Quadratisches Icon mit eigenem Hintergrund
        scale, edge = 0.72, True
    elif is_tile(im):
        # Abgerundete Kachel (typisches PWA-Icon): so gross, dass die Kachel den sichtbaren Kreis fuellt
        scale, edge = 0.74, False
        mids = [im.getpixel(p) for p in ((im.width // 2, 3), (im.width // 2, im.height - 4), (3, im.height // 2), (im.width - 4, im.height // 2))]
        bg_hex = hex_of(tuple(sum(m[i] for m in mids) // 4 for i in range(3)))
    else:
        # Freistehendes Motiv auf transparentem Grund
        scale, edge = 0.58, False
    if edge:
        bg = hex_of(im.getpixel((2, 2)))
        fill = Image.new("RGBA", (S, S), im.getpixel((2, 2)))
    else:
        bg = bg_hex
        fill = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    inner = int(S * scale)
    sm = im.resize((inner, inner), Image.LANCZOS)
    off = (S - inner) // 2
    fill.alpha_composite(sm, (off, off))
    return fill, bg


def letter_icon(name, color_hex):
    from PIL import ImageDraw, ImageFont
    S = 432
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    d = ImageDraw.Draw(im)
    try:
        font = ImageFont.truetype("DejaVuSans-Bold.ttf", 180)
    except Exception:
        font = ImageFont.load_default()
    ch = (name or "A")[0].upper()
    lum = sum(int(color_hex[i:i + 2], 16) for i in (1, 3, 5)) / 3
    d.text((S / 2, S / 2), ch, fill=(0, 0, 0, 255) if lum > 140 else (255, 255, 255, 255), font=font, anchor="mm")
    return im


def clean_color(v, default):
    v = (v or "").strip()
    if re.fullmatch(r"#[0-9a-fA-F]{6}", v):
        return v.lower()
    if re.fullmatch(r"#[0-9a-fA-F]{3}", v):
        return "#" + "".join(c * 2 for c in v[1:]).lower()
    return default


def main(app_id):
    with open(os.path.join(ROOT, "apps", app_id + ".json"), encoding="utf-8") as f:
        cfg = json.load(f)
    url = cfg["url"]
    manifest, murl, links, meta, base = find_manifest(url)

    name = cfg.get("name") or manifest.get("short_name") or manifest.get("name") or app_id
    theme = clean_color(cfg.get("themeColor") or manifest.get("theme_color") or meta.get("theme-color"), "#2f5e3e")
    bg = clean_color(cfg.get("backgroundColor") or manifest.get("background_color"), "#ffffff")

    icon_src = cfg.get("icon", "auto")
    cands = icon_candidates(manifest, murl, links, base) if icon_src == "auto" else \
        [{"url": urljoin(url, icon_src), "maskable": bool(cfg.get("iconMaskable")),
          "svg": icon_src.lower().endswith(".svg"), "size": 0}]
    fg, icon_bg = None, None
    for c in cands:
        try:
            im = load_image(c)
            fg, icon_bg = make_foreground(im, c["maskable"], bg)
            log("Icon:", c["url"], "maskable" if c["maskable"] else "", im.size)
            break
        except Exception as e:
            log("Icon unbrauchbar:", c["url"], e)
    if fg is None:
        log("kein Icon gefunden, nehme Buchstaben")
        icon_bg = theme
        fg = letter_icon(name, theme)
    icon_bg = clean_color(cfg.get("iconBackground"), icon_bg)

    for d, px in DENSITIES.items():
        out = os.path.join(RES, "mipmap-" + d)
        os.makedirs(out, exist_ok=True)
        fg.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_fg.png"))

    with open(os.path.join(RES, "values", "generated.xml"), "w", encoding="utf-8") as f:
        f.write('<?xml version="1.0" encoding="utf-8"?>\n<resources>\n'
                f'    <string name="app_name">{escape(name)}</string>\n'
                f'    <color name="shell_theme">{theme}</color>\n'
                f'    <color name="shell_bg">{bg}</color>\n'
                f'    <color name="icon_bg">{icon_bg}</color>\n'
                '</resources>\n')

    assets = os.path.join(APP, "src", "main", "assets")
    os.makedirs(assets, exist_ok=True)
    with open(os.path.join(assets, "config.json"), "w", encoding="utf-8") as f:
        json.dump({"name": name, "url": url, "internalHosts": cfg.get("internalHosts", []),
                   "themeColor": theme, "backgroundColor": bg}, f, ensure_ascii=False, indent=2)

    with open(os.path.join(APP, "generated.properties"), "w", encoding="utf-8") as f:
        f.write("package=%s\n" % cfg["package"])

    log(f"fertig: {name} | {cfg['package']} | theme {theme} | bg {bg} | icon_bg {icon_bg}")


if __name__ == "__main__":
    if len(sys.argv) != 2:
        sys.exit(__doc__)
    main(sys.argv[1])
