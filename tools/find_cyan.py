"""Find cyan text rows (loose) in a screenshot."""
import sys
from PIL import Image
import numpy as np

path = sys.argv[1]
y0 = int(sys.argv[2]) if len(sys.argv) > 2 else 0
y1 = int(sys.argv[3]) if len(sys.argv) > 3 else 10000
img = Image.open(path).convert("RGB")
a = np.asarray(img).astype(np.int16)
r, g, b = a[:, :, 0], a[:, :, 1], a[:, :, 2]
cyan = (b > 140) & (g > 130) & (r < 140) & ((b - r) > 60)
rows = cyan.sum(axis=1)
ys = np.where(rows > 8)[0]
groups = []
for y in ys:
    if groups and y - groups[-1][-1] <= 8:
        groups[-1].append(int(y))
    else:
        groups.append([int(y)])
for grp in groups:
    lo, hi = grp[0], grp[-1]
    if hi < y0 or lo > y1:
        continue
    band = cyan[lo:hi + 1]
    xs = np.where(band.any(axis=0))[0]
    if len(xs) == 0:
        continue
    print(f"y={lo}-{hi} (mid {(lo+hi)//2}) x={xs.min()}-{xs.max()} count={rows[lo:hi+1].max()}")
