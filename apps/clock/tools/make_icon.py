#!/usr/bin/env python3
"""OneSay Clock 自适应图标（代码绘制，0.1.1 重做）。三个候选：
  A dawn    晨光表盘：深靛→暖橘的晨光渐变底 + 实心白表盘、深色 10:10 指针、12 点一枚暖橘刻度
  B bubble  会说话的钟：蓝紫底 + 白色对话气泡（右下小尾巴）里一只钟，指针蓝色
  C sunrise 日出地平线：夜蓝→珊瑚天空 + 地平线上半轮暖黄太阳，太阳就是表盘（10:10 指针 + 放射刻度）
全部在 108dp 画布上按 dp 绘制，内容收在中心直径约 60dp 的安全圈内（圆形/圆角方形遮罩都不会裁到）；
16 倍超采样后 LANCZOS 缩小，描边最细 3.6dp（桌面 48px 时约 2.4px），小图不糊。不使用任何三星商标。

用法：
  python3 tools/make_icon.py build            # 三个候选各出全套 dpi 到 out-icons/<id>/res，并拼对比图 docs/icon-compare.png
  python3 tools/make_icon.py install <id>     # 把某个候选的分层 PNG 与旧式图标装进 app/res
"""
import math, os, sys, shutil
import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "app/res")
OUTI = os.path.join(ROOT, "out-icons")
S = 1728            # 108dp × 16
U = S / 108.0       # 1dp
C = S / 2.0
DENS = {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}


def hexc(h, a=255):
    h = h.lstrip("#"); return (int(h[0:2], 16), int(h[2:4], 16), int(h[4:6], 16), a)


def grad(stops, angle_deg=90, radial=None):
    """stops: [(t, '#rrggbb')]；angle 90 = 上→下。radial=(cx,cy,r) 以 dp 计时为径向。返回 RGBA。"""
    n = 432
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32) / (n - 1)
    if radial:
        cx, cy, r = radial[0] / 108, radial[1] / 108, radial[2] / 108
        t = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r
    else:
        a = math.radians(angle_deg)
        t = xx * math.cos(a) + yy * math.sin(a)
        t = (t - t.min()) / (t.max() - t.min())
    t = np.clip(t, 0, 1)
    out = np.zeros((n, n, 3), np.float32)
    ts = [s[0] for s in stops]; cs = [np.array(hexc(s[1])[:3], np.float32) for s in stops]
    for ch in range(3):
        out[..., ch] = np.interp(t, ts, [c[ch] for c in cs])
    im = Image.fromarray(out.clip(0, 255).astype(np.uint8), "RGB").resize((S, S), Image.BICUBIC)
    return im.convert("RGBA")


def glow(im, cx, cy, r, color, strength):
    n = 216
    yy, xx = np.mgrid[0:n, 0:n].astype(np.float32) / (n - 1) * 108
    d = np.sqrt((xx - cx) ** 2 + (yy - cy) ** 2) / r
    a = (np.clip(1 - d, 0, 1) ** 2 * strength * 255).astype(np.uint8)
    layer = Image.new("RGBA", (n, n), hexc(color, 0))
    layer.putalpha(Image.fromarray(a, "L"))
    return Image.alpha_composite(im, layer.resize((S, S), Image.BICUBIC))


def P(x, y): return (x * U, y * U)


def disc(d, cx, cy, r, fill):
    d.ellipse([(cx - r) * U, (cy - r) * U, (cx + r) * U, (cy + r) * U], fill=fill)


def ring(d, cx, cy, r, w, fill):
    d.ellipse([(cx - r) * U, (cy - r) * U, (cx + r) * U, (cy + r) * U], outline=fill, width=int(w * U))


def hand(d, cx, cy, ang, length, w, fill, tail=0.0):
    a = math.radians(ang - 90)
    x2, y2 = cx + math.cos(a) * length, cy + math.sin(a) * length
    x1, y1 = cx - math.cos(a) * tail, cy - math.sin(a) * tail
    d.line([P(x1, y1), P(x2, y2)], fill=fill, width=int(w * U))
    disc(d, x2, y2, w / 2, fill); disc(d, x1, y1, w / 2, fill)


def soft_shadow(layer, dy=1.4, blur=2.0, alpha=0.30, color=(10, 6, 40)):
    a = layer.split()[3].filter(ImageFilter.GaussianBlur(U * blur)).point(lambda v: int(v * alpha))
    sh = Image.new("RGBA", (S, S), color + (0,)); sh.putalpha(a)
    off = Image.new("RGBA", (S, S), (0, 0, 0, 0)); off.paste(sh, (0, int(U * dy)), sh)
    return Image.alpha_composite(off, layer)


# ---------------- A 晨光表盘
def a_bg():
    im = grad([(0, "#1B1F5E"), (0.55, "#5B3B8C"), (1.0, "#F08A5D")], angle_deg=90)
    return glow(im, 54, 112, 60, "#FFC37A", 0.75)


def a_fg():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    cx, cy, r = 54, 54, 25.5
    disc(d, cx, cy, r, hexc("#FFFFFF"))
    ink = hexc("#1E2260")
    for k in range(12):     # 3/6/9 小点刻度，12 点暖橘长刻度
        if k == 0: continue
        if k % 3: continue
        a = math.radians(k * 30 - 90)
        disc(d, cx + math.cos(a) * 20.5, cy + math.sin(a) * 20.5, 1.7, hexc("#C9CBE0"))
    d.line([P(cx, cy - 23.2), P(cx, cy - 17.6)], fill=hexc("#F07A45"), width=int(3.8 * U))
    disc(d, cx, cy - 23.2, 1.9, hexc("#F07A45")); disc(d, cx, cy - 17.6, 1.9, hexc("#F07A45"))
    hand(d, cx, cy, -55, 11.5, 4.6, ink)        # 时针 ~10 点
    hand(d, cx, cy, 58, 16.5, 3.8, ink)         # 分针 ~2 点（10:10）
    disc(d, cx, cy, 3.4, ink); disc(d, cx, cy, 1.4, hexc("#F07A45"))
    return soft_shadow(im, dy=1.6, blur=2.4, alpha=0.38)


# ---------------- B 会说话的钟（气泡）
def b_bg():
    im = grad([(0, "#2F6BFF"), (1.0, "#6A3DF0")], angle_deg=60)
    return glow(im, 30, 22, 55, "#9FC2FF", 0.55)


def b_fg():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(im)
    white = hexc("#FFFFFF")
    cx, cy, r = 55.5, 51.5, 24.5
    disc(d, cx, cy, r, white)
    # 左下尾巴：底边落在气泡圆上（约 115°–155°），尖端朝左下，经典对话气泡
    def on(ang, rr=r - 0.5):
        a = math.radians(ang); return P(cx + math.cos(a) * rr, cy + math.sin(a) * rr)
    d.polygon([on(108), P(34.0, 78.5), on(150)], fill=white)
    disc(d, 34.6, 77.6, 1.6, white)
    ink = hexc("#3A55F5")
    ring(d, cx, cy, 15.2, 3.6, ink)
    hand(d, cx, cy, 0, 9.0, 3.6, ink)
    hand(d, cx, cy, 90, 6.6, 3.6, ink)
    disc(d, cx, cy, 2.6, ink)
    return soft_shadow(im, dy=1.6, blur=2.2, alpha=0.32, color=(20, 10, 90))


# ---------------- C 日出地平线
def c_bg():
    im = grad([(0, "#14174A"), (0.50, "#3E2F84"), (0.80, "#C8577A"), (1.0, "#FF9A6B")], angle_deg=90)
    return glow(im, 54, 66, 34, "#FFB36B", 0.55)


def c_fg():
    im = Image.new("RGBA", (S, S), (0, 0, 0, 0))
    sun = Image.new("RGBA", (S, S), (0, 0, 0, 0)); d = ImageDraw.Draw(sun)
    cx, cy, r = 54, 62, 18.0           # 圆心在地平线上
    # 太阳：径向暖黄
    sg = grad([(0, "#FFE7A3"), (0.7, "#FFC15A"), (1.0, "#FF9F43")], radial=(54, 62, 20))
    m = Image.new("L", (S, S), 0); ImageDraw.Draw(m).ellipse([(cx - r) * U, (cy - r) * U, (cx + r) * U, (cy + r) * U], fill=255)
    sun.paste(sg, (0, 0), m)
    # 放射刻度（光芒）：-90..+90 之间 5 道
    ray = hexc("#FFD27A")
    for ang in (-90, -45, 0, 45, 90):
        a = math.radians(ang - 90)
        x1, y1 = cx + math.cos(a) * (r + 4.2), cy + math.sin(a) * (r + 4.2)
        x2, y2 = cx + math.cos(a) * (r + 8.6), cy + math.sin(a) * (r + 8.6)
        d.line([P(x1, y1), P(x2, y2)], fill=ray, width=int(3.8 * U))
        disc(d, x1, y1, 1.9, ray); disc(d, x2, y2, 1.9, ray)
    ink = hexc("#2A1F5C")
    hand(d, cx, cy, -58, 11.0, 4.2, ink)
    hand(d, cx, cy, 52, 15.0, 3.6, ink)
    disc(d, cx, cy, 3.2, ink)
    # 地平线以下裁掉（留 1.9dp 缝给地平线）
    cut = Image.new("L", (S, S), 255); ImageDraw.Draw(cut).rectangle([0, (cy + 0.2) * U, S, S], fill=0)
    sun.putalpha(Image.fromarray(np.minimum(np.array(sun.split()[3]), np.array(cut))))
    im = Image.alpha_composite(im, sun)
    d = ImageDraw.Draw(im)
    white = hexc("#FFFFFF")
    d.rounded_rectangle([P(27, cy + 2.6), P(81, cy + 2.6 + 4.0)], radius=int(2 * U), fill=white)
    d.rounded_rectangle([P(36, cy + 10.0), P(72, cy + 10.0 + 3.6)], radius=int(1.8 * U), fill=hexc("#FFFFFF", 170))
    return soft_shadow(im, dy=1.2, blur=2.0, alpha=0.28)


CANDS = {
    "A-dawn": ("A  Dawn dial", a_bg, a_fg),
    "B-bubble": ("B  Talking clock", b_bg, b_fg),
    "C-sunrise": ("C  Sunrise horizon", c_bg, c_fg),
}


def mask(kind, size):
    big = size * 4
    m = Image.new("L", (big, big), 0); d = ImageDraw.Draw(m)
    if kind == "circle": d.ellipse([0, 0, big - 1, big - 1], fill=255)
    else: d.rounded_rectangle([0, 0, big - 1, big - 1], radius=int(big * 0.30), fill=255)
    return m.resize((size, size), Image.LANCZOS)


def legacy(full, px, kind):
    crop = full.crop((int(18 * U), int(18 * U), int(90 * U), int(90 * U))).resize((px, px), Image.LANCZOS)
    out = Image.new("RGBA", (px, px), (0, 0, 0, 0)); out.paste(crop, (0, 0), mask(kind, px)); return out


def render(cid):
    _, fb, ff = CANDS[cid]
    bg, fg = fb(), ff()
    full = Image.alpha_composite(bg, fg)
    base = os.path.join(OUTI, cid, "res")
    for name, k in DENS.items():
        n = int(108 * k)
        dd = os.path.join(base, "drawable-" + name); os.makedirs(dd, exist_ok=True)
        bg.convert("RGB").resize((n, n), Image.LANCZOS).save(os.path.join(dd, "ic_launcher_bg.png"), optimize=True)
        fg.resize((n, n), Image.LANCZOS).save(os.path.join(dd, "ic_launcher_fg.png"), optimize=True)
        m = int(48 * k)
        md = os.path.join(base, "mipmap-" + name); os.makedirs(md, exist_ok=True)
        legacy(full, m, "squircle").save(os.path.join(md, "ic_launcher.png"), optimize=True)
        legacy(full, m, "circle").save(os.path.join(md, "ic_launcher_round.png"), optimize=True)
    full.convert("RGB").resize((432, 432), Image.LANCZOS).save(os.path.join(OUTI, cid, "full-108dp.png"))
    return full


def font(sz):
    for f in ("/opt/deckbuild/fonts/Inter-SemiBold.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(f): return ImageFont.truetype(f, sz)
    return ImageFont.load_default()


def compare(fulls, path):
    """每个候选一行：左浅底（256 大图 squircle + 192/144/96/72/48 squircle + 96/48 circle），右深底同样。"""
    W, RH = 2200, 380
    sheet = Image.new("RGB", (W, RH * len(fulls) + 20), (245, 245, 248))
    d = ImageDraw.Draw(sheet)
    for i, (cid, full) in enumerate(fulls):
        y0 = i * RH + 10
        dark = Image.new("RGB", (W // 2, RH - 10), (24, 24, 30)); sheet.paste(dark, (W // 2, y0))
        light = Image.new("RGB", (W // 2, RH - 10), (232, 230, 238)); sheet.paste(light, (0, y0))
        for half, tc in ((0, (40, 40, 50)), (W // 2, (230, 230, 236))):
            d.text((half + 24, y0 + 14), CANDS[cid][0] + ("" if half == 0 else "  (dark)"), font=font(30), fill=tc)
            x = half + 24
            big = full.crop((int(18 * U), int(18 * U), int(90 * U), int(90 * U)))
            for sz, kind in ((256, "squircle"), (192, "squircle"), (144, "circle"), (96, "squircle"), (72, "circle"), (48, "squircle")):
                ic = big.resize((sz, sz), Image.LANCZOS)
                yy = y0 + 70 + (256 - sz)
                sheet.paste(ic, (x, yy), mask(kind, sz)); x += sz + 22
                d.text((x - sz - 22, y0 + 70 + 262), "%dpx" % sz, font=font(18), fill=tc)
    sheet.save(path)


def main():
    cmd = sys.argv[1] if len(sys.argv) > 1 else "build"
    if cmd == "build":
        fulls = [(cid, render(cid)) for cid in CANDS]
        os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
        pv = os.path.join(ROOT, "docs/icon-compare.png"); compare(fulls, pv); print("ok", pv)
    elif cmd == "install":
        cid = sys.argv[2]; render(cid)
        src = os.path.join(OUTI, cid, "res")
        for dn in os.listdir(src):
            for fn in os.listdir(os.path.join(src, dn)):
                shutil.copy(os.path.join(src, dn, fn), os.path.join(RES, dn, fn))
        print("installed", cid)


if __name__ == "__main__": main()
