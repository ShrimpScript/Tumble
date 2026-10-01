"""Top-down survey of the generated area: height/biome map plus candidate
'cliff into deep hole' spots, to pick the trailer's location."""
import sys
import numpy as np
from PIL import Image
from scipy import ndimage

d = np.load(sys.argv[1])
blocks, pal = d["blocks"], list(d["palette"])
x0, y0, z0 = d["origin"]
air = np.array([p in ("minecraft:air", "minecraft:cave_air", "minecraft:void_air") for p in pal])
water = np.array([p.startswith("minecraft:water") or "kelp" in p or "seagrass" in p for p in pal])
leaves = np.array(["leaves" in p for p in pal])
plant = np.array([any(k in p for k in ("grass[", ":grass", "fern", "flower", "poppy", "dandelion", "tulip",
                                       "orchid", "allium", "bluet", "daisy", "cornflower", "lily", "petals",
                                       "bush", "snow[", "vine", "sugar_cane")) and "block" not in p
                  for p in pal])
sx, sy, sz = blocks.shape
nonair = ~air[blocks]
# Highest non-air block per column.
top = sy - 1 - np.argmax(nonair[:, ::-1, :], axis=1)
topid = np.take_along_axis(blocks, top[:, None, :], axis=1)[:, 0, :]
# Ground ignoring plants/leaves: highest "solid-ish" block.
solid = nonair & ~plant[blocks] & ~leaves[blocks]
ground = sy - 1 - np.argmax(solid[:, ::-1, :], axis=1)
gy = ground + y0

# Colour map.
cols = {"grass_block": (95, 159, 53), "water": (50, 90, 200), "sand": (219, 207, 163), "stone": (125, 125, 125),
        "snow": (240, 240, 250), "leaves": (60, 120, 40), "cherry_leaves": (240, 170, 200), "dirt": (134, 96, 67),
        "gravel": (130, 125, 120), "deepslate": (80, 80, 85), "tuff": (100, 100, 90), "andesite": (135, 135, 135),
        "granite": (150, 100, 80), "diorite": (190, 190, 190), "calcite": (220, 220, 215), "ice": (140, 170, 250)}
img = np.zeros((sz, sx, 3), dtype=np.float32)
for i, p in enumerate(pal):
    n = p.split(":")[1].split("[")[0]
    c = None
    for k in sorted(cols, key=len, reverse=True):
        if k in n:
            c = cols[k]
            break
    if c is None:
        c = (160, 160, 120)
    img[(topid == i).T] = c
shade = np.clip(0.55 + (top.T - top.T.mean()) / 120.0, 0.3, 1.4)
img *= shade[..., None]

# Candidate holes: ground far below the local ground max nearby, exposed to sky.
local_max = ndimage.maximum_filter(gy, size=9)
drop = local_max - gy
cand = (drop >= 25) & ~water[topid]
lab, n = ndimage.label(cand)
print("candidate holes:", n)
res = []
for k in range(1, n + 1):
    m = lab == k
    area = m.sum()
    if area < 6:
        continue
    xs, zs = np.nonzero(m)
    dmax = drop[m].max()
    floor = gy[m].min()
    rim = local_max[m].max()
    res.append((dmax * np.sqrt(area), int(xs.mean()) + x0, int(zs.mean()) + z0, int(area), int(dmax), int(floor), int(rim)))
res.sort(reverse=True)
for r in res[:25]:
    print("score %.0f  centre %d,%d  area %d  drop %d  floor y=%d rim y=%d" % r)
img[cand.T] = img[cand.T] * 0.3 + np.array([255, 0, 0]) * 0.7
im = Image.fromarray(np.clip(img, 0, 255).astype(np.uint8))
im.save(sys.argv[2])
# biome map
