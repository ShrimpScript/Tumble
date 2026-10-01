"""Builds the trailer's set from the generated world.

    python3 build_scene.py <box.npz> <out_dir> [edits.json]

Writes terrain meshes, the block texture array, a collision grid for the
physics harness, and a light grid used to light entities.
"""
import json
import os
import sys
import time

import numpy as np

from mesher import Mesher
from textures import TextureArray
import edits as set_dressing

BOX = sys.argv[1]
OUT = sys.argv[2]
os.makedirs(OUT, exist_ok=True)

# The cave entrance the whole trailer is built around (world coordinates).
HOLE = (1500, 139)
ORIGIN = (1500, 100, 139)
MESH_AREA = (1312, 1696, -48, 320, -62, 250)
CAVE_BOX = (1430, 1570, -64, 112, 70, 210)

t0 = time.time()
m = Mesher(BOX)
print("loaded", time.time() - t0)

POST = len(sys.argv) > 3 and sys.argv[3] == "post"
set_dressing.apply(m, post_blast=POST)

seeds = []
for y in range(-60, 105):
    for dx in range(-6, 7, 3):
        for dz in range(-6, 7, 3):
            x, z = HOLE[0] + dx, HOLE[1] + dz
            lx, ly, lz = x - m.x0, y - m.y0, z - m.z0
            if not m.occludes[m.blocks[lx, ly, lz]]:
                seeds.append((lx, ly, lz))
cb = CAVE_BOX
m.compute_visible(seeds, cb)
print("visibility", time.time() - t0, "visible cells", int(m.vis.sum()))

tex = TextureArray(m.lib.assets)
if POST:
    # Only the chunks around the crater differ; texture layers must match the main build.
    import json as _json
    names = _json.load(open(os.path.join(OUT, "blocks.json")))["names"]
    for n in names:
        tex.layer_of(n)
    cx0, cz0 = (int(set_dressing.CREEPER_FEET[0]) - 8) // 16 * 16, (int(set_dressing.CREEPER_FEET[2]) - 8) // 16 * 16
    area = (cx0, cx0 + 32, cz0, cz0 + 32, MESH_AREA[4], MESH_AREA[5])
    meta = m.build(area, os.path.join(OUT, "terrain_post"), ORIGIN, tex.layer_of)
    if len(tex.names) != len(names):
        raise SystemExit("post-blast mesh needs textures the main build lacks: %s" % tex.names[len(names):])
    print("post-blast chunks", len(meta["chunks"]))
    raise SystemExit(0)
meta = m.build(MESH_AREA, os.path.join(OUT, "terrain"), ORIGIN, tex.layer_of)
tex.save(os.path.join(OUT, "blocks"))
print("mesh", time.time() - t0, "vertices", meta["vertices"], "chunks", len(meta["chunks"]),
      "layers", len(tex.layers))

# Collision grid for the physics harness: 1 where a block has a full collision box.
cx0, cx1, cy0, cy1, cz0, cz1 = 1400, 1600, -64, 160, 40, 240
sub = m.blocks[cx0 - m.x0:cx1 - m.x0, cy0 - m.y0:cy1 - m.y0, cz0 - m.z0:cz1 - m.z0]
solid = m.collides[sub] & ~m.is_air[sub] & (m.fluid[sub] == 0)
solid.astype(np.uint8).tofile(os.path.join(OUT, "collision.u8"))
# Occupancy for camera placement: 1 solid, 2 fluid, 0 open.
occ = solid.astype(np.uint8)
occ[(m.fluid[sub] > 0) & ~solid] = 2
occ.tofile(os.path.join(OUT, "occ.u8"))
with open(os.path.join(OUT, "collision.json"), "w") as f:
    json.dump({"origin": [cx0, cy0, cz0], "size": list(solid.shape), "order": "x,y,z (z fastest)"}, f)

# Light grid for entity lighting, same box as the mesh area.
ax0, ax1, az0, az1, ay0, ay1 = MESH_AREA
sl = (slice(ax0 - m.x0, ax1 - m.x0), slice(ay0 - m.y0, ay1 - m.y0), slice(az0 - m.z0, az1 - m.z0))
light = (m.sky[sl].astype(np.uint8) << 4) | m.blight[sl].astype(np.uint8)
light.tofile(os.path.join(OUT, "light.u8"))
with open(os.path.join(OUT, "light.json"), "w") as f:
    json.dump({"origin": [ax0, ay0, az0], "size": list(light.shape), "order": "x,y,z (z fastest)",
               "sceneOrigin": list(ORIGIN)}, f)

# Surface heights near the set, for placing actors.
hx0, hx1, hz0, hz1 = 1400, 1600, 40, 240
heights = np.zeros((hx1 - hx0, hz1 - hz0), np.int16)
for x in range(hx0, hx1):
    col = m.collides[m.blocks[x - m.x0, :, hz0 - m.z0:hz1 - m.z0]] & ~m.is_air[m.blocks[x - m.x0, :, hz0 - m.z0:hz1 - m.z0]]
    top = col.shape[0] - 1 - np.argmax(col[::-1, :], axis=0)
    heights[x - hx0] = top + m.y0 + 1
heights.tofile(os.path.join(OUT, "heights.i16"))
with open(os.path.join(OUT, "heights.json"), "w") as f:
    json.dump({"origin": [hx0, hz0], "size": [hx1 - hx0, hz1 - hz0]}, f)
print("done", time.time() - t0)
