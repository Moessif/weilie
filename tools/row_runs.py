"""Scan a row of pixels and report color runs (for locating buttons)."""
import sys
from PIL import Image
import numpy as np

path = sys.argv[1]
y = int(sys.argv[2])
img = Image.open(path).convert("RGB")
a = np.asarray(img)
row = a[y]
runs = []
prev = None
start = 0
for x in range(row.shape[0]):
    px = tuple(int(v) for v in row[x])
    key = (px[0] // 24, px[1] // 24, px[2] // 24)
    if key != prev:
        if prev is not None:
            runs.append((start, x - 1, prev, tuple(int(v) for v in row[start])))
        prev = key
        start = x
runs.append((start, row.shape[0] - 1, prev, tuple(int(v) for v in row[start])))
for lo, hi, key, sample in runs:
    if hi - lo < 12:
        continue
    print(f"x={lo}-{hi} len={hi-lo+1} sample={sample}")
