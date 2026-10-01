"""Bakes Minecraft block states into quads, straight from the game's own JSON.

Implements the vanilla blockstate and model formats: variants (with weighted
random choice), multipart, parent chains, texture variables, element rotation
with rescale, per-face uv / rotation / cullface / tintindex, blockstate x/y
rotation and uvlock. Vertex order and uv assignment follow FaceInfo and
BlockFaceUV so textures land exactly as the game draws them.
"""
import json
import math
import os

import numpy as np
from PIL import Image

ASSETS = os.environ.get("MC_ASSETS", os.path.join(os.environ.get("TRAILER_WORK", "/home/user/work"), "assets", "assets", "minecraft"))

DIRS = ["down", "up", "north", "south", "west", "east"]
DIR_VEC = {"down": (0, -1, 0), "up": (0, 1, 0), "north": (0, 0, -1),
           "south": (0, 0, 1), "west": (-1, 0, 0), "east": (1, 0, 0)}
VEC_DIR = {v: k for k, v in DIR_VEC.items()}

# FaceInfo corner order, as (x, y, z) picks from (from, to): 0 -> min, 1 -> max.
FACE_CORNERS = {
    "down": [(0, 0, 1), (0, 0, 0), (1, 0, 0), (1, 0, 1)],
    "up": [(0, 1, 0), (0, 1, 1), (1, 1, 1), (1, 1, 0)],
    "north": [(1, 1, 0), (1, 0, 0), (0, 0, 0), (0, 1, 0)],
    "south": [(0, 1, 1), (0, 0, 1), (1, 0, 1), (1, 1, 1)],
    "west": [(0, 1, 0), (0, 0, 0), (0, 0, 1), (0, 1, 1)],
    "east": [(1, 1, 1), (1, 0, 1), (1, 0, 0), (1, 1, 0)],
}

# Vanilla directional face shading.
FACE_SHADE = {"down": 0.5, "up": 1.0, "north": 0.8, "south": 0.8, "west": 0.6, "east": 0.6}


def auto_uv(face, f, t):
    """BlockElement's default uv when a face gives none."""
    if face == "down":
        return [f[0], 16 - t[2], t[0], 16 - f[2]]
    if face == "up":
        return [f[0], f[2], t[0], t[2]]
    if face == "north":
        return [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]]
    if face == "south":
        return [f[0], 16 - t[1], t[0], 16 - f[1]]
    if face == "west":
        return [f[2], 16 - t[1], t[2], 16 - f[1]]
    return [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]]


def _rot_y(v, deg):
    # Ry(-deg): clockwise seen from above, north -> east for +90.
    x, y, z = v
    for _ in range((deg // 90) % 4):
        x, z = -z, x
    return (x, y, z)


def _rot_x(v, deg):
    # Rx(-deg): +90 takes up to north.
    x, y, z = v
    for _ in range((deg // 90) % 4):
        y, z = z, -y
    return (x, y, z)


def rotate_vec(v, rx, ry):
    return _rot_y(_rot_x(v, rx), ry)


def rotate_dir(d, rx, ry):
    v = rotate_vec(DIR_VEC[d], rx, ry)
    return VEC_DIR[tuple(int(round(c)) for c in v)]


class Quad:
    __slots__ = ("pos", "uv", "tex", "face", "cull", "tint", "shade", "full")

    def __init__(self, pos, uv, tex, face, cull, tint, shade, full):
        self.pos = pos      # 4 x 3 floats, block-local 0..1
        self.uv = uv        # 4 x 2 floats, 0..1
        self.tex = tex      # texture name like "block/stone"
        self.face = face    # world-space nominal direction (after rotation)
        self.cull = cull    # world-space cull direction or None
        self.tint = tint    # tintindex or -1
        self.shade = shade  # element shade flag
        self.full = full    # lies on the block boundary and covers the whole face


class ModelLibrary:
    def __init__(self, assets=ASSETS):
        self.assets = assets
        self._models = {}
        self._states = {}
        self._baked = {}
        self._tex_alpha = {}

    # -- loading -------------------------------------------------------------

    def _norm(self, name):
        if ":" in name:
            name = name.split(":", 1)[1]
        return name

    def model_json(self, name):
        name = self._norm(name)
        if name not in self._models:
            path = os.path.join(self.assets, "models", name + ".json")
            with open(path) as f:
                self._models[name] = json.load(f)
        return self._models[name]

    def resolved(self, name):
        """Returns (elements, textures, ambientocclusion) after the parent chain."""
        chain = []
        cur = self._norm(name)
        while cur:
            if cur.startswith("builtin/"):
                break
            m = self.model_json(cur)
            chain.append(m)
            cur = self._norm(m["parent"]) if "parent" in m else None
        textures = {}
        elements = None
        ao = True
        for m in reversed(chain):
            textures.update(m.get("textures", {}))
            if "elements" in m:
                elements = m["elements"]
            if "ambientocclusion" in m:
                ao = m["ambientocclusion"]
        return elements or [], textures, ao

    def resolve_tex(self, ref, textures):
        seen = 0
        while ref.startswith("#") and seen < 16:
            ref = textures.get(ref[1:], "missingno")
            seen += 1
        return self._norm(ref)

    def blockstate(self, block):
        block = self._norm(block)
        if block not in self._states:
            path = os.path.join(self.assets, "blockstates", block + ".json")
            if not os.path.exists(path):
                self._states[block] = None
            else:
                with open(path) as f:
                    self._states[block] = json.load(f)
        return self._states[block]

    # -- blockstate selection ------------------------------------------------

    @staticmethod
    def parse_key(key):
        if "[" in key:
            name, props = key[:-1].split("[", 1)
            props = dict(p.split("=") for p in props.split(","))
        else:
            name, props = key, {}
        return name, props

    @staticmethod
    def _variant_matches(vkey, props):
        if vkey == "":
            return True
        for cond in vkey.split(","):
            k, v = cond.split("=")
            if props.get(k) != v:
                return False
        return True

    @staticmethod
    def _when_matches(when, props):
        if "OR" in when:
            return any(ModelLibrary._when_matches(w, props) for w in when["OR"])
        if "AND" in when:
            return all(ModelLibrary._when_matches(w, props) for w in when["AND"])
        for k, v in when.items():
            if props.get(k) not in str(v).split("|"):
                return False
        return True

    def model_choices(self, key):
        """List of alternatives; each alternative is a list of (model, x, y, uvlock)."""
        name, props = self.parse_key(key)
        bs = self.blockstate(name)
        if bs is None:
            return []
        if "variants" in bs:
            for vkey, val in bs["variants"].items():
                if self._variant_matches(vkey, props):
                    opts = val if isinstance(val, list) else [val]
                    weights = [o.get("weight", 1) for o in opts]
                    return [([(o["model"], o.get("x", 0), o.get("y", 0), o.get("uvlock", False))], w)
                            for o, w in zip(opts, weights)]
            return []
        parts = []
        for case in bs.get("multipart", []):
            if "when" in case and not self._when_matches(case["when"], props):
                continue
            ap = case["apply"]
            o = ap[0] if isinstance(ap, list) else ap
            parts.append((o["model"], o.get("x", 0), o.get("y", 0), o.get("uvlock", False)))
        return [(parts, 1)]

    # -- baking ----------------------------------------------------------------

    def bake_model(self, model, rx, ry, uvlock):
        ck = (model, rx, ry, uvlock)
        if ck in self._baked:
            return self._baked[ck]
        elements, textures, ao = self.resolved(model)
        quads = []
        for el in elements:
            f = el["from"]
            t = el["to"]
            erot = el.get("rotation")
            shade = el.get("shade", True)
            for face, fd in el.get("faces", {}).items():
                tex = self.resolve_tex(fd["texture"], textures)
                uv = fd.get("uv") or auto_uv(face, f, t)
                frot = fd.get("rotation", 0)
                corners = []
                for pick in FACE_CORNERS[face]:
                    corners.append([t[i] if pick[i] else f[i] for i in range(3)])
                corners = np.array(corners, dtype=np.float64)
                if erot:
                    corners = self._element_rotate(corners, erot)
                # BlockFaceUV vertex uvs.
                uvs = []
                for i in range(4):
                    s = (i + frot // 90) % 4
                    u = uv[0] if s in (0, 1) else uv[2]
                    v = uv[1] if s in (0, 3) else uv[3]
                    uvs.append((u / 16.0, v / 16.0))
                # Blockstate rotation about the block centre.
                wface = face
                if rx or ry:
                    c = corners - 8.0
                    corners = np.array([rotate_vec(tuple(p), rx, ry) for p in c]) + 8.0
                    wface = rotate_dir(face, rx, ry)
                    if uvlock:
                        uvs = self._uvlock(corners, wface)
                cull = fd.get("cullface")
                if cull is not None:
                    cull = {"bottom": "down", "top": "up"}.get(cull, cull)
                    if rx or ry:
                        cull = rotate_dir(cull, rx, ry)
                full = self._is_full_face(corners, wface)
                quads.append(Quad(corners / 16.0, uvs, tex, wface, cull,
                                  fd.get("tintindex", -1), shade, full))
        res = (quads, ao)
        self._baked[ck] = res
        return res

    @staticmethod
    def _element_rotate(corners, erot):
        axis = erot["axis"]
        ang = math.radians(erot["angle"])
        o = np.array(erot["origin"], dtype=np.float64)
        c, s = math.cos(ang), math.sin(ang)
        p = corners - o
        out = p.copy()
        if axis == "x":
            out[:, 1] = p[:, 1] * c - p[:, 2] * s
            out[:, 2] = p[:, 1] * s + p[:, 2] * c
            scale = np.array([1, 1, 1.0])
            if erot.get("rescale"):
                scale = np.array([1, 1 / c, 1 / c])
        elif axis == "y":
            out[:, 0] = p[:, 0] * c + p[:, 2] * s
            out[:, 2] = -p[:, 0] * s + p[:, 2] * c
            scale = np.array([1.0, 1, 1])
            if erot.get("rescale"):
                scale = np.array([1 / c, 1, 1 / c])
        else:
            out[:, 0] = p[:, 0] * c - p[:, 1] * s
            out[:, 1] = p[:, 0] * s + p[:, 1] * c
            scale = np.array([1.0, 1, 1])
            if erot.get("rescale"):
                scale = np.array([1 / c, 1 / c, 1])
        return out * scale + o

    @staticmethod
    def _uvlock(corners, face):
        """uvlock: texture coordinates taken from the rotated face's world position."""
        out = []
        for p in corners:
            if face == "up":
                u, v = p[0], p[2]
            elif face == "down":
                u, v = p[0], 16 - p[2]
            elif face == "north":
                u, v = 16 - p[0], 16 - p[1]
            elif face == "south":
                u, v = p[0], 16 - p[1]
            elif face == "west":
                u, v = p[2], 16 - p[1]
            else:
                u, v = 16 - p[2], 16 - p[1]
            out.append((u / 16.0, v / 16.0))
        return out

    @staticmethod
    def _is_full_face(c, face):
        axis = {"down": 1, "up": 1, "north": 2, "south": 2, "west": 0, "east": 0}[face]
        want = 0.0 if face in ("down", "north", "west") else 16.0
        if not np.allclose(c[:, axis], want, atol=1e-4):
            return False
        others = [i for i in range(3) if i != axis]
        for i in others:
            if not (np.isclose(c[:, i].min(), 0, atol=1e-4) and np.isclose(c[:, i].max(), 16, atol=1e-4)):
                return False
        return True

    def bake_state(self, key, pick=0):
        """Quads for a block state. pick selects among weighted random variants."""
        choices = self.model_choices(key)
        if not choices:
            return [], True
        total = sum(w for _, w in choices)
        r = pick % total
        for parts, w in choices:
            if r < w:
                break
            r -= w
        quads = []
        ao = True
        for model, rx, ry, uvlock in parts:
            q, a = self.bake_model(model, rx, ry, uvlock)
            quads.extend(q)
            ao = ao and a
        return quads, ao

    def variant_count(self, key):
        choices = self.model_choices(key)
        return max(1, sum(w for _, w in choices))

    # -- textures ----------------------------------------------------------------

    def texture_path(self, tex):
        return os.path.join(self.assets, "textures", tex + ".png")

    def texture_alpha(self, tex):
        """'opaque', 'cutout' or 'translucent' from the texture's own alpha."""
        if tex not in self._tex_alpha:
            p = self.texture_path(tex)
            kind = "opaque"
            if os.path.exists(p):
                a = np.asarray(Image.open(p).convert("RGBA"))[..., 3]
                if (a < 255).any():
                    kind = "translucent" if ((a > 0) & (a < 255)).mean() > 0.2 else "cutout"
            self._tex_alpha[tex] = kind
        return self._tex_alpha[tex]
