"""Turns a box of the real world into render-ready chunk meshes.

Per vertex it stores what vanilla's smooth lighting needs: sky light and block
light averaged over the four cells around the corner, ambient occlusion from
the same cells, the fixed directional face shade, and the biome tint. The
shader combines those with a lightmap curve, exactly as the game does.

Only faces that can be seen are emitted: a face must look into a cell that is
either reached by sky light or belongs to the cave the trailer travels through.
"""
import json
import math
import os
import struct

import numpy as np
from PIL import Image

from models import ModelLibrary, DIR_VEC, FACE_SHADE

AIR = ("minecraft:air", "minecraft:cave_air", "minecraft:void_air")

GRASS_TINT = {"grass_block", "grass", "tall_grass", "fern", "large_fern", "sugar_cane", "potted_fern",
              "pink_petals", "short_grass"}
FOLIAGE_TINT = {"oak_leaves", "jungle_leaves", "acacia_leaves", "dark_oak_leaves", "mangrove_leaves", "vine"}
FIXED_TINT = {"birch_leaves": 0x80A755, "spruce_leaves": 0x619961, "lily_pad": 0x208030}

# Biome climate (temperature, downfall) and overrides, from 1.20.1 worldgen data.
BIOMES = {
    "plains": (0.8, 0.4), "sunflower_plains": (0.8, 0.4), "flower_forest": (0.7, 0.8), "forest": (0.7, 0.8),
    "birch_forest": (0.6, 0.6), "cherry_grove": (0.5, 0.8), "meadow": (0.5, 0.8), "river": (0.5, 0.5),
    "beach": (0.8, 0.4), "stony_shore": (0.2, 0.3), "ocean": (0.5, 0.5), "deep_ocean": (0.5, 0.5),
    "lukewarm_ocean": (0.5, 0.5), "deep_lukewarm_ocean": (0.5, 0.5), "savanna": (2.0, 0.0),
    "snowy_slopes": (-0.3, 0.9), "frozen_peaks": (-0.7, 0.9), "grove": (-0.2, 0.8), "deep_dark": (0.8, 0.4),
    "dripstone_caves": (0.8, 0.4), "lush_caves": (0.5, 0.5), "windswept_hills": (0.2, 0.3),
    "jagged_peaks": (-0.7, 0.9), "stony_peaks": (1.0, 0.3), "dark_forest": (0.7, 0.8), "taiga": (0.25, 0.8),
}
GRASS_OVERRIDE = {"cherry_grove": 0xB6DB61}
FOLIAGE_OVERRIDE = {"cherry_grove": 0xB6DB61}
WATER = {"cherry_grove": 0x5DB7EF, "meadow": 0x0E4ECF, "lukewarm_ocean": 0x45ADF2, "deep_lukewarm_ocean": 0x45ADF2,
         "frozen_peaks": 0x3F76E4}
DEFAULT_WATER = 0x3F76E4

NOCOLLIDE_WORDS = ("grass", "fern", "flower", "poppy", "dandelion", "tulip", "orchid", "allium", "bluet",
                   "daisy", "cornflower", "lily_of", "petals", "bush", "vine", "sugar_cane", "torch",
                   "lichen", "kelp", "seagrass", "water", "lava", "rail", "button", "sapling", "mushroom",
                   "carpet", "snow", "roots", "sprouts", "glow_berries", "cave_vines", "hanging_roots",
                   "spore_blossom", "pointed_dripstone", "small_dripleaf", "lever", "sign", "banner",
                   "redstone_wire", "tripwire", "cobweb", "fire", "light", "bubble_column")


def rgb(c):
    return ((c >> 16) & 255, (c >> 8) & 255, c & 255)


class Colormaps:
    def __init__(self, assets):
        self.grass = np.asarray(Image.open(os.path.join(assets, "textures/colormap/grass.png")).convert("RGB"))
        self.foliage = np.asarray(Image.open(os.path.join(assets, "textures/colormap/foliage.png")).convert("RGB"))

    def sample(self, cmap, t, d):
        t = min(max(t, 0.0), 1.0)
        d = min(max(d, 0.0), 1.0) * t
        i = int((1.0 - t) * 255.0)
        j = int((1.0 - d) * 255.0)
        return tuple(int(v) for v in cmap[j, i])


class Mesher:
    def __init__(self, npz, lib=None):
        d = np.load(npz)
        self.blocks = d["blocks"]
        self.sky = d["sky"]
        self.blight = d["blight"]
        self.biomes = d["biomes"]
        self.palette = [str(p) for p in d["palette"]]
        self.biome_palette = [str(p) for p in d["biome_palette"]]
        self.x0, self.y0, self.z0 = [int(v) for v in d["origin"]]
        self.lib = lib or ModelLibrary()
        self.cmaps = Colormaps(self.lib.assets)
        self._classify()

    # -- world edits -------------------------------------------------------------

    def state_id(self, key):
        if key not in self.palette:
            self.palette.append(key)
            self._classify()
        return self.palette.index(key)

    def set_block(self, x, y, z, key):
        self.blocks[x - self.x0, y - self.y0, z - self.z0] = self.state_id(key)

    def get_block(self, x, y, z):
        return self.palette[self.blocks[x - self.x0, y - self.y0, z - self.z0]]

    # -- classification ----------------------------------------------------------

    def _classify(self):
        n = len(self.palette)
        self.is_air = np.zeros(n, bool)
        self.occludes = np.zeros(n, bool)      # hides neighbouring faces
        self.ao_full = np.zeros(n, bool)       # counts as a full block for ambient occlusion
        self.translucent = np.zeros(n, bool)
        self.fluid = np.zeros(n, np.uint8)     # 1 water, 2 lava
        self.collides = np.zeros(n, bool)
        self.emission = np.zeros(n, np.uint8)
        for i, key in enumerate(self.palette):
            name, props = ModelLibrary.parse_key(key)
            short = name.split(":")[1]
            if name in AIR:
                self.is_air[i] = True
                continue
            if short == "water" or props.get("waterlogged") == "true" and short in ("water",):
                self.fluid[i] = 1
            if short in ("water", "bubble_column", "kelp", "kelp_plant", "seagrass", "tall_seagrass"):
                self.fluid[i] = 1
            if short == "lava":
                self.fluid[i] = 2
                self.emission[i] = 15
            if short in ("torch", "wall_torch", "lantern", "glowstone", "jack_o_lantern", "sea_lantern",
                         "shroomlight", "campfire"):
                self.emission[i] = 14 if "torch" in short else 15
            quads, _ = self.lib.bake_state(key)
            full = self._is_full_cube(quads)
            kinds = {self.lib.texture_alpha(q.tex) for q in quads}
            leaves = "leaves" in short
            glassy = "glass" in short or "ice" in short or short in ("slime_block", "honey_block")
            self.translucent[i] = ("translucent" in kinds and not leaves) or short in ("ice",) or "stained_glass" in short
            self.occludes[i] = full and not leaves and not glassy and kinds <= {"opaque"}
            self.ao_full[i] = full
            self.collides[i] = not any(w in short for w in NOCOLLIDE_WORDS) or short in (
                "grass_block", "snow_block", "mushroom_stem", "moss_block", "dirt_path", "rooted_dirt",
                "mud", "packed_mud", "powder_snow")
            if short in ("grass_block", "snow_block", "moss_block", "dirt_path", "rooted_dirt"):
                self.collides[i] = True

    @staticmethod
    def _is_full_cube(quads):
        faces = {q.face for q in quads if q.full}
        return len(faces) == 6

    # -- helpers -----------------------------------------------------------------

    def biome_at(self, lx, ly, lz):
        bx = min(max(lx // 4, 0), self.biomes.shape[0] - 1)
        by = min(max(ly // 4, 0), self.biomes.shape[1] - 1)
        bz = min(max(lz // 4, 0), self.biomes.shape[2] - 1)
        return self.biome_palette[self.biomes[bx, by, bz]].split(":")[1]

    def tint_for(self, short, lx, ly, lz):
        if short in FIXED_TINT:
            return rgb(FIXED_TINT[short])
        grass = short in GRASS_TINT
        foliage = short in FOLIAGE_TINT
        water = short == "water"
        if not (grass or foliage or water):
            return (255, 255, 255)
        key = (short if not grass else "g", lx // 2, lz // 2, ly // 8)
        c = self._tint_cache.get(key)
        if c is not None:
            return c
        acc = np.zeros(3)
        cnt = 0
        for dx in (-2, -1, 0, 1, 2):
            for dz in (-2, -1, 0, 1, 2):
                b = self.biome_at(lx + dx * 2, ly, lz + dz * 2)
                if water:
                    col = rgb(WATER.get(b, DEFAULT_WATER))
                else:
                    t, dd = BIOMES.get(b, (0.8, 0.4))
                    if grass:
                        col = rgb(GRASS_OVERRIDE[b]) if b in GRASS_OVERRIDE else self.cmaps.sample(self.cmaps.grass, t, dd)
                    else:
                        col = rgb(FOLIAGE_OVERRIDE[b]) if b in FOLIAGE_OVERRIDE else self.cmaps.sample(self.cmaps.foliage, t, dd)
                acc += col
                cnt += 1
        c = tuple(int(v) for v in acc / cnt)
        self._tint_cache[key] = c
        return c

    # -- visibility ----------------------------------------------------------------

    def compute_visible(self, cave_seeds=(), cave_box=None):
        """Marks the non-occluding cells a camera can look into."""
        occ = self.occludes[self.blocks]
        vis = (~occ) & (self.sky > 0)
        if cave_seeds:
            from scipy import ndimage
            x0, x1, y0, y1, z0, z1 = cave_box
            x0, x1, y0, y1, z0, z1 = (x0 - self.x0, x1 - self.x0, y0 - self.y0, y1 - self.y0,
                                      z0 - self.z0, z1 - self.z0)
            sub = ~occ[x0:x1, y0:y1, z0:z1]
            lab, _ = ndimage.label(sub)
            keep = set()
            for (sx, sy, sz) in cave_seeds:
                v = lab[sx - x0, sy - y0, sz - z0]
                if v:
                    keep.add(v)
            mask = np.isin(lab, list(keep))
            vis[x0:x1, y0:y1, z0:z1] |= mask
        self.vis = vis
        return vis

    # -- meshing -------------------------------------------------------------------

    def build(self, area, out_prefix, origin, layer_of):
        """area: (x0, x1, z0, z1, y0, y1) world coords; origin: world coords placed at 0,0,0."""
        ax0, ax1, az0, az1, ay0, ay1 = area
        self._tint_cache = {}
        blocks = self.blocks
        occ = self.occludes[blocks]
        aof = self.ao_full[blocks]
        vis = self.vis
        sky = self.sky
        bl = self.blight
        SX, SY, SZ = blocks.shape

        def inb(x, y, z):
            return 0 <= x < SX and 0 <= y < SY and 0 <= z < SZ

        chunks = []
        buffers = {k: [] for k in ("pos", "uv", "layer", "light", "tint")}
        counts = {"v": 0}

        # Candidate cells: not air, and next to a visible cell (or itself visible).
        lx0, lx1 = ax0 - self.x0, ax1 - self.x0
        lz0, lz1 = az0 - self.z0, az1 - self.z0
        ly0, ly1 = ay0 - self.y0, ay1 - self.y0
        sub_vis = vis[lx0 - 1:lx1 + 1, ly0 - 1:ly1 + 1, lz0 - 1:lz1 + 1]
        near = sub_vis[1:-1, 1:-1, 1:-1].copy()
        for a in range(3):
            for s in (-1, 1):
                sl = [slice(1, -1)] * 3
                sl[a] = slice(1 + s, sub_vis.shape[a] - 1 + s)
                near |= sub_vis[tuple(sl)]
        notair = ~self.is_air[blocks[lx0:lx1, ly0:ly1, lz0:lz1]]
        cand = near & notair

        state_cache = {}

        for cx in range(lx0 // 16, (lx1 + 15) // 16):
            for cz in range(lz0 // 16, (lz1 + 15) // 16):
                for kind in ("solid", "translucent"):
                    pass
                per_kind = {"solid": {k: [] for k in buffers}, "translucent": {k: [] for k in buffers}}
                xs, ys, zs = np.nonzero(cand[cx * 16 - lx0:cx * 16 - lx0 + 16, :, cz * 16 - lz0:cz * 16 - lz0 + 16])
                for i in range(len(xs)):
                    x = int(xs[i]) + cx * 16
                    y = int(ys[i]) + ly0
                    z = int(zs[i]) + cz * 16
                    sid = int(blocks[x, y, z])
                    key = self.palette[sid]
                    short = key.split(":")[1].split("[")[0]
                    if self.fluid[sid] and short in ("water", "lava", "bubble_column"):
                        self._emit_fluid(x, y, z, sid, short, per_kind, origin, layer_of, occ, vis, sky, bl)
                        if short != "bubble_column":
                            continue
                    pick = (x * 3129871) ^ (z * 116129781) ^ y
                    pick = (pick * pick * 42317861 + pick * 11) >> 16
                    nvar = state_cache.get(("n", sid))
                    if nvar is None:
                        nvar = self.lib.variant_count(key)
                        state_cache[("n", sid)] = nvar
                    ck = (sid, pick % nvar)
                    baked = state_cache.get(ck)
                    if baked is None:
                        baked = self.lib.bake_state(key, pick % nvar)
                        state_cache[ck] = baked
                    quads, model_ao = baked
                    if not quads:
                        continue
                    tint = None
                    tk = "translucent" if self.translucent[sid] else "solid"
                    for q in quads:
                        if q.cull is not None:
                            dx, dy, dz = DIR_VEC[q.cull]
                            nx, ny, nz = x + dx, y + dy, z + dz
                            if not inb(nx, ny, nz):
                                continue
                            if occ[nx, ny, nz] or not vis[nx, ny, nz]:
                                continue
                            nsid = blocks[nx, ny, nz]
                            if self.translucent[sid] and nsid == sid:
                                continue
                        else:
                            # Internal geometry: drawn if the cell itself, or any neighbour, is visible.
                            if not vis[x, y, z] and occ[x, y, z]:
                                continue
                        if q.tint >= 0 and tint is None:
                            tint = self.tint_for(short, x, y, z)
                        col = tint if q.tint >= 0 else (255, 255, 255)
                        self._emit_quad(q, x, y, z, col, model_ao, per_kind[tk], origin, layer_of,
                                        aof, sky, bl, occ, short)
                for kind, buf in per_kind.items():
                    if not buf["pos"]:
                        continue
                    nverts = sum(len(p) for p in buf["pos"]) // 3
                    chunks.append({"cx": cx * 16 + self.x0, "cz": cz * 16 + self.z0, "kind": kind,
                                   "first": counts["v"], "count": nverts})
                    counts["v"] += nverts
                    for k in buffers:
                        buffers[k].extend(buf[k])

        pos = np.array([v for p in buffers["pos"] for v in p], dtype=np.float32)
        uv = np.array([v for p in buffers["uv"] for v in p], dtype=np.float32)
        layer = np.array([v for p in buffers["layer"] for v in p], dtype=np.uint16)
        light = np.array([v for p in buffers["light"] for v in p], dtype=np.uint8)
        tint = np.array([v for p in buffers["tint"] for v in p], dtype=np.uint8)
        with open(out_prefix + ".bin", "wb") as f:
            offs = {}
            for name, arr in (("pos", pos), ("uv", uv), ("layer", layer), ("light", light), ("tint", tint)):
                while f.tell() % 4:
                    f.write(b"\0")
                offs[name] = [f.tell(), int(arr.size)]
                f.write(arr.tobytes())
        meta = {"origin": list(origin), "chunks": chunks, "arrays": offs, "vertices": counts["v"]}
        with open(out_prefix + ".json", "w") as f:
            json.dump(meta, f)
        return meta

    def _light_at(self, x, y, z, sky, bl, occ):
        SX, SY, SZ = sky.shape
        if not (0 <= x < SX and 0 <= y < SY and 0 <= z < SZ):
            return 15, 0, False
        return int(sky[x, y, z]), int(bl[x, y, z]), bool(occ[x, y, z])

    def _emit_quad(self, q, x, y, z, col, model_ao, buf, origin, layer_of, aof, sky, bl, occ, short):
        lay = layer_of(q.tex)
        shade = FACE_SHADE[q.face] if q.shade else 1.0
        n = DIR_VEC[q.face]
        pos = q.pos
        verts = []
        lights = []
        # Grass side overlays sit a hair proud of the face they decorate.
        eps = 0.0008 if "overlay" in q.tex else 0.0
        if q.full or self._on_boundary(q):
            fx, fy, fz = x + n[0], y + n[1], z + n[2]
            cs, cb, _ = self._light_at(fx, fy, fz, sky, bl, occ)
            if (cs, cb) == (0, 0):
                cs, cb, _ = self._light_at(x, y, z, sky, bl, occ)
            axes = [a for a in range(3) if n[a] == 0]
            for p in pos:
                sg = [1 if p[a] > 0.5 else -1 for a in axes]
                o1 = [0, 0, 0]
                o1[axes[0]] = sg[0]
                o2 = [0, 0, 0]
                o2[axes[1]] = sg[1]
                s1 = self._sample(fx + o1[0], fy + o1[1], fz + o1[2], aof, sky, bl, occ)
                s2 = self._sample(fx + o2[0], fy + o2[1], fz + o2[2], aof, sky, bl, occ)
                if s1[2] and s2[2]:
                    c = s1
                else:
                    c = self._sample(fx + o1[0] + o2[0], fy + o1[1] + o2[1], fz + o1[2] + o2[2], aof, sky, bl, occ)
                # Vanilla blend: dark (opaque) samples take the centre's value.
                ss = [cs] + [s[0] if not s[3] else cs for s in (s1, s2, c)]
                bb = [cb] + [s[1] if not s[3] else cb for s in (s1, s2, c)]
                if model_ao:
                    ao = (1.0 + sum(0.2 if s[2] else 1.0 for s in (s1, s2, c))) / 4.0
                else:
                    ao = 1.0
                lights.append((sum(ss) / 4.0, sum(bb) / 4.0, ao * shade))
        else:
            s, b, o = self._light_at(x, y, z, sky, bl, occ)
            if o or (s, b) == (0, 0):
                s2, b2, _ = self._light_at(x, y + 1, z, sky, bl, occ)
                s, b = max(s, s2), max(b, b2)
            for _ in pos:
                lights.append((s, b, shade))
        ox, oy, oz = origin
        for i, p in enumerate(pos):
            verts.extend((x + self.x0 - ox + p[0] + n[0] * eps,
                          y + self.y0 - oy + p[1] + n[1] * eps,
                          z + self.z0 - oz + p[2] + n[2] * eps))
        buf["pos"].append(verts)
        buf["uv"].append([c for uv in q.uv for c in uv])
        buf["layer"].append([lay] * 4)
        emissive = 1 if short in ("lava", "glowstone", "shroomlight", "sea_lantern", "torch", "wall_torch",
                                  "lantern", "glow_lichen", "magma_block") else 0
        buf["light"].append([v for (s, b, a) in lights
                             for v in (int(s * 17), int(b * 17), int(a * 255), emissive * 255)])
        buf["tint"].append(list(col) * 4)

    @staticmethod
    def _on_boundary(q):
        n = DIR_VEC[q.face]
        a = [i for i in range(3) if n[i] != 0][0]
        want = 1.0 if n[a] > 0 else 0.0
        return np.allclose(q.pos[:, a], want, atol=1e-4)

    def _sample(self, x, y, z, aof, sky, bl, occ):
        SX, SY, SZ = sky.shape
        if not (0 <= x < SX and 0 <= y < SY and 0 <= z < SZ):
            return (15, 0, False, False)
        return (int(sky[x, y, z]), int(bl[x, y, z]), bool(aof[x, y, z]), bool(occ[x, y, z]))

    # -- fluids --------------------------------------------------------------------

    def _fluid_height(self, x, y, z, short):
        SX, SY, SZ = self.blocks.shape
        if not (0 <= x < SX and 0 <= y < SY and 0 <= z < SZ):
            return None
        k = self.palette[self.blocks[x, y, z]]
        if short not in k:
            return None
        if y + 1 < SY and short in self.palette[self.blocks[x, y + 1, z]]:
            return 1.0
        lvl = int(ModelLibrary.parse_key(k)[1].get("level", 0))
        if lvl >= 8:
            return 1.0
        return (8 - lvl) / 9.0 if lvl > 0 else 8.0 / 9.0

    def _emit_fluid(self, x, y, z, sid, short, per_kind, origin, layer_of, occ, vis, sky, bl):
        if short == "bubble_column":
            short = "water"
        buf = per_kind["translucent" if short == "water" else "solid"]
        tex_top = "block/%s_still" % short
        col = self.tint_for("water", x, y, z) if short == "water" else (255, 255, 255)
        hs = {}
        for cxo in (0, 1):
            for czo in (0, 1):
                acc = []
                full = False
                for dx in (cxo - 1, cxo):
                    for dz in (czo - 1, czo):
                        h = self._fluid_height(x + dx, y, z + dz, short)
                        if h is not None:
                            if h >= 1.0:
                                full = True
                            acc.append(h)
                hs[(cxo, czo)] = 1.0 if full else (sum(acc) / len(acc) if acc else 8 / 9.0)
        SX, SY, SZ = self.blocks.shape
        s, b, _ = self._light_at(x, y, z, sky, bl, occ)
        if short == "lava":
            b = 15
        ox, oy, oz = origin
        bx, by, bz = x + self.x0 - ox, y + self.y0 - oy, z + self.z0 - oz
        lay = layer_of(tex_top)

        def emit(corners, uvs, shade, light):
            verts = []
            for c in corners:
                verts.extend((bx + c[0], by + c[1], bz + c[2]))
            buf["pos"].append(verts)
            buf["uv"].append([v for uv in uvs for v in uv])
            buf["layer"].append([lay] * 4)
            buf["light"].append([v for _ in range(4) for v in (int(light[0] * 17), int(light[1] * 17),
                                                                 int(shade * 255), 255 if short == "lava" else 0)])
            buf["tint"].append(list(col) * 4)

        def other(dx, dy, dz):
            nx, ny, nz = x + dx, y + dy, z + dz
            if not (0 <= nx < SX and 0 <= ny < SY and 0 <= nz < SZ):
                return True, False
            k = self.palette[self.blocks[nx, ny, nz]]
            return (short in k), bool(occ[nx, ny, nz])

        same, opq = other(0, 1, 0)
        if not same and not opq:
            h = hs
            ls = self._light_at(x, y + 1, z, sky, bl, occ)
            emit([(0, h[(0, 0)], 0), (0, h[(0, 1)], 1), (1, h[(1, 1)], 1), (1, h[(1, 0)], 0)],
                 [(0, 0), (0, 1), (1, 1), (1, 0)], 1.0, (max(s, ls[0]), max(b, ls[1])))
        for face, (dx, dz) in (("north", (0, -1)), ("south", (0, 1)), ("west", (-1, 0)), ("east", (1, 0))):
            same, opq = other(dx, 0, dz)
            if same or opq:
                continue
            nx, nz = x + dx, z + dz
            if not vis[nx, y, nz] if (0 <= nx < SX and 0 <= nz < SZ) else False:
                continue
            ls = self._light_at(nx, y, nz, sky, bl, occ)
            light = (max(s, ls[0]), max(b, ls[1]))
            if face == "north":
                c = [(1, hs[(1, 0)], 0), (1, 0, 0), (0, 0, 0), (0, hs[(0, 0)], 0)]
            elif face == "south":
                c = [(0, hs[(0, 1)], 1), (0, 0, 1), (1, 0, 1), (1, hs[(1, 1)], 1)]
            elif face == "west":
                c = [(0, hs[(0, 0)], 0), (0, 0, 0), (0, 0, 1), (0, hs[(0, 1)], 1)]
            else:
                c = [(1, hs[(1, 1)], 1), (1, 0, 1), (1, 0, 0), (1, hs[(1, 0)], 0)]
            uvs = [(0, 1 - c[0][1]), (0, 1), (1, 1), (1, 1 - c[3][1])]
            emit(c, uvs, FACE_SHADE[face], light)
        same, opq = other(0, -1, 0)
        if not same and not opq:
            emit([(0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)], [(0, 0), (0, 1), (1, 1), (1, 0)], 0.5, (s, b))
