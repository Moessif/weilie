"""Find dark circular floating buttons in a weilie map screenshot (no scipy)."""
import sys
from collections import deque
from PIL import Image
import numpy as np

path = sys.argv[1]
img = Image.open(path).convert("RGB")
w, h = img.size
a = np.asarray(img).astype(np.int16)
lum = a.sum(axis=2) / 3.0
mask = (lum < 70)

step = 4
small = mask[::step, ::step]
sh, sw = small.shape
seen = np.zeros_like(small, dtype=bool)
blobs = []
for y in range(sh):
    for x in range(sw):
        if small[y, x] and not seen[y, x]:
            q = deque([(y, x)])
            seen[y, x] = True
            pts = []
            while q:
                cy, cx = q.popleft()
                pts.append((cy, cx))
                for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1)):
                    ny, nx = cy + dy, cx + dx
                    if 0 <= ny < sh and 0 <= nx < sw and small[ny, nx] and not seen[ny, nx]:
                        seen[ny, nx] = True
                        q.append((ny, nx))
            ys = [p[0] for p in pts]
            xs = [p[1] for p in pts]
            bw = max(xs) - min(xs) + 1
            bh = max(ys) - min(ys) + 1
            area = len(pts)
            if area * step * step < 2500 or bw * step < 60 or bh * step < 60:
                continue
            if bw * step > 420 or bh * step > 420:
                continue
            fill = area / float(bw * bh)
            blobs.append((area * step * step,
                          (min(xs) * step + max(xs) * step) // 2,
                          (min(ys) * step + max(ys) * step) // 2,
                          bw * step, bh * step, fill))
for b in sorted(blobs, key=lambda t: t[2]):
    print(f"area={b[0]:7d} center=({b[1]:4d},{b[2]:4d}) size={b[3]}x{b[4]} fill={b[5]:.2f}")
print("image", w, h)
