"""Vertical cross-section images of the world box, for location scouting."""
import sys
import numpy as np
from PIL import Image

d = np.load(sys.argv[1])
blocks, pal = d["blocks"], list(d["palette"])
x0, y0, z0 = [int(v) for v in d["origin"]]

def colour(p):
    n = p.split(":")[1].split("[")[0]
    table = [("air", None), ("water", (50, 90, 220)), ("lava", (255, 120, 0)), ("grass_block", (95, 159, 53)),
             ("leaves", (60, 130, 40)), ("log", (110, 85, 50)), ("dirt", (134, 96, 67)), ("deepslate", (70, 70, 78)),
             ("stone", (125, 125, 125)), ("andesite", (140, 140, 140)), ("granite", (150, 100, 80)),
             ("diorite", (195, 195, 195)), ("tuff", (105, 105, 95)), ("gravel", (130, 125, 120)),
             ("ore", (230, 200, 60)), ("moss", (90, 140, 50)), ("dripstone", (150, 110, 90)), ("snow", (245, 245, 255)),
             ("clay", (160, 165, 180)), ("sand", (219, 207, 163))]
    for k, c in table:
        if k in n:
            return c
    return (200, 60, 200)

lut = np.array([colour(p) or (0, 0, 0) for p in pal], dtype=np.uint8)
isair = np.array([colour(p) is None for p in pal])

def slice_x(z, xa, xb, ya, yb):
    sub = blocks[xa - x0:xb - x0, ya - y0:yb - y0, z - z0]  # [x, y]
    img = lut[sub]
    img[isair[sub]] = (175, 200, 255)
    return np.flipud(img.transpose(1, 0, 2))

def slice_z(x, za, zb, ya, yb):
    sub = blocks[x - x0, ya - y0:yb - y0, za - z0:zb - z0]  # [y, z]
    img = lut[sub.T]
    img[isair[sub.T]] = (175, 200, 255)
    return np.flipud(img.transpose(1, 0, 2))

cx, cz, r, ya, yb, out = [int(a) for a in sys.argv[2:8]]
step = int(sys.argv[8]) if len(sys.argv) > 8 else 4
rows = []
for dz in range(-12, 13, step):
    rows.append(slice_x(cz + dz, cx - r, cx + r, ya, yb))
    rows.append(np.full((2, 2 * r, 3), 255, np.uint8))
img = np.concatenate(rows, 0)
Image.fromarray(img).resize((img.shape[1] * 3, img.shape[0] * 3), Image.NEAREST).save("/home/user/work/slice_%d.png" % out)
print(img.shape)
