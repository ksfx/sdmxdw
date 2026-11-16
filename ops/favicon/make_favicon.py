"""Favicon (#26), run from the repository root:
  python3 ops/favicon/make_favicon.py src/main/resources/static/img/logo-icon.png <out dir>
then copy favicon.ico to static/ and the PNGs to static/img/. Needs Pillow.

Favicon (#26): database symbol with the X from logo-icon.png, background removed, without the
pixel squares; sizes for browsers (ico 16/32/48, png 32/192/512) and iOS (180, white background)."""
import sys
from collections import deque
from PIL import Image

src = Image.open(sys.argv[1]).convert("RGBA")
out = sys.argv[2]
w, h = src.size
a = src.getchannel("A").load()
seen = [[False] * w for _ in range(h)]
comps = []
for y in range(h):
    for x in range(w):
        if a[x, y] > 40 and not seen[y][x]:
            q = deque([(x, y)]); seen[y][x] = True; pix = []
            while q:
                cx, cy = q.popleft(); pix.append((cx, cy))
                for dx in (-1, 0, 1):
                    for dy in (-1, 0, 1):
                        nx, ny = cx + dx, cy + dy
                        if 0 <= nx < w and 0 <= ny < h and not seen[ny][nx] and a[nx, ny] > 40:
                            seen[ny][nx] = True; q.append((nx, ny))
            comps.append(pix)
comps.sort(key=len, reverse=True)
print("components:", [len(c) for c in comps[:12]])
# the swooshes are large; the pieces of the cylinder are small but central, the pixel squares are
# small and at the sides
def central(c):
    cx = sum(x for x, _ in c) / len(c)
    return 0.3 * w < cx < 0.7 * w
keep = [c for c in comps if len(c) > 2000 or (central(c) and len(c) > 30)]
print("kept", len(keep), "of", len(comps))
mask = Image.new("L", (w, h), 0); m = mask.load()
for c in keep:
    for x, y in c:
        m[x, y] = 255
# soft edges: keep the original alpha inside the kept components, also their 2px halo
from PIL import ImageFilter
halo = mask.filter(ImageFilter.MaxFilter(5))
alpha = Image.composite(src.getchannel("A"), Image.new("L", (w, h), 0), halo)
sym = src.copy(); sym.putalpha(alpha)
sym = sym.crop(sym.getbbox())
print("symbol", sym.size)

def outlined(img, px):
    """Thin white outline (scaled to about 1 px in the tab), so the navy symbol stays visible on
    dark browser bars; invisible on light ones."""
    halo = img.getchannel("A").filter(ImageFilter.MaxFilter(px * 2 + 1)).filter(ImageFilter.GaussianBlur(1))
    base = Image.new("RGBA", img.size, (255, 255, 255, 0))
    base.putalpha(halo)
    base.alpha_composite(img)
    return base


def square(img, size, pad, bg=None):
    s = size - 2 * pad
    k = s / max(img.size)
    r = img.resize((max(1, round(img.width * k)), max(1, round(img.height * k))), Image.LANCZOS)
    canvas = Image.new("RGBA", (size, size), bg or (0, 0, 0, 0))
    canvas.alpha_composite(r, ((size - r.width) // 2, (size - r.height) // 2))
    return canvas

big = square(sym, 512, 24)
tab = outlined(big, 12)  # 12 px at 512 = about 1 px at 32
tab.save(f"{out}/favicon-512.png")
tab.resize((192, 192), Image.LANCZOS).save(f"{out}/favicon-192.png")
tab.resize((32, 32), Image.LANCZOS).save(f"{out}/favicon-32.png")
tab.save(f"{out}/favicon.ico", sizes=[(16, 16), (32, 32), (48, 48)])
square(sym, 180, 18, (255, 255, 255, 255)).convert("RGB").save(f"{out}/apple-touch-icon.png")
