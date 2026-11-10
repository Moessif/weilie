"""Annotate a screenshot with crosshair markers for coordinate verification."""
import sys
from PIL import Image, ImageDraw

path = sys.argv[1]
out = sys.argv[2]
pts = []
for pair in sys.argv[3:]:
    x, y = pair.split(",")
    pts.append((int(x), int(y)))
img = Image.open(path).convert("RGB")
d = ImageDraw.Draw(img)
for (x, y) in pts:
    d.line([(x - 40, y), (x + 40, y)], fill=(255, 0, 0), width=3)
    d.line([(x, y - 40), (x, y + 40)], fill=(255, 0, 0), width=3)
    d.ellipse([x - 8, y - 8, x + 8, y + 8], outline=(255, 0, 0), width=3)
img.save(out)
print("saved", out, pts)
