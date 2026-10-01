"""Minimal reader for Minecraft 1.18+ Anvil region files (.mca).

Loads a box of the real, server-generated world into numpy arrays: block state
ids (indexing a shared palette of "name[props]" strings), sky light, block light
and biomes. Written for 1.20.1 (DataVersion 3465) chunk NBT.
"""
import os
import struct
import zlib

import numpy as np

# --- NBT -------------------------------------------------------------------

class _Reader:
    __slots__ = ("b", "i")

    def __init__(self, b):
        self.b = b
        self.i = 0

    def take(self, n):
        v = self.b[self.i:self.i + n]
        self.i += n
        return v

    def byte(self):
        v = self.b[self.i]
        self.i += 1
        return v

    def unpack(self, fmt, n):
        v = struct.unpack_from(fmt, self.b, self.i)[0]
        self.i += n
        return v

    def string(self):
        n = self.unpack(">H", 2)
        return self.take(n).decode("utf-8", "replace")


def _payload(r, t):
    if t == 1:
        return r.unpack(">b", 1)
    if t == 2:
        return r.unpack(">h", 2)
    if t == 3:
        return r.unpack(">i", 4)
    if t == 4:
        return r.unpack(">q", 8)
    if t == 5:
        return r.unpack(">f", 4)
    if t == 6:
        return r.unpack(">d", 8)
    if t == 7:
        n = r.unpack(">i", 4)
        return np.frombuffer(r.take(n), dtype=np.int8)
    if t == 8:
        return r.string()
    if t == 9:
        et = r.byte()
        n = r.unpack(">i", 4)
        return [_payload(r, et) for _ in range(n)]
    if t == 10:
        out = {}
        while True:
            ct = r.byte()
            if ct == 0:
                return out
            name = r.string()
            out[name] = _payload(r, ct)
    if t == 11:
        n = r.unpack(">i", 4)
        return np.frombuffer(r.take(4 * n), dtype=">i4")
    if t == 12:
        n = r.unpack(">i", 4)
        return np.frombuffer(r.take(8 * n), dtype=">i8")
    raise ValueError("bad tag %d" % t)


def read_nbt(data):
    r = _Reader(data)
    t = r.byte()
    r.string()
    return _payload(r, t)


# --- Region files -----------------------------------------------------------

def read_chunk(region_dir, cx, cz):
    path = os.path.join(region_dir, "r.%d.%d.mca" % (cx >> 5, cz >> 5))
    if not os.path.exists(path):
        return None
    with open(path, "rb") as f:
        idx = 4 * ((cx & 31) + (cz & 31) * 32)
        f.seek(idx)
        loc = f.read(4)
        off = (loc[0] << 16) | (loc[1] << 8) | loc[2]
        if off == 0:
            return None
        f.seek(off * 4096)
        length, comp = struct.unpack(">iB", f.read(5))
        raw = f.read(length - 1)
    if comp != 2:
        raise ValueError("unsupported compression %d" % comp)
    return read_nbt(zlib.decompress(raw))


def unpack_longs(longs, bits, count):
    """Unpacks 1.16+ packed arrays (entries never straddle a long)."""
    if bits == 0:
        return np.zeros(count, dtype=np.uint16)
    per = 64 // bits
    u = np.asarray(longs).astype(np.int64).view(np.uint64)
    shifts = (np.arange(per, dtype=np.uint64) * np.uint64(bits))
    mask = np.uint64((1 << bits) - 1)
    vals = (u[:, None] >> shifts[None, :]) & mask
    return vals.reshape(-1)[:count].astype(np.uint16)


def state_key(entry):
    name = entry["Name"]
    props = entry.get("Properties")
    if props:
        name += "[" + ",".join("%s=%s" % (k, props[k]) for k in sorted(props)) + "]"
    return name


def nibbles(arr):
    a = np.asarray(arr).view(np.uint8)
    out = np.empty(4096, dtype=np.uint8)
    out[0::2] = a & 15
    out[1::2] = a >> 4
    return out


class WorldBox:
    """A box of the world: x in [x0, x0+sx), y in [y0, y0+sy), z in [z0, z0+sz)."""

    def __init__(self, region_dir, x0, z0, sx, sz, y0=-64, sy=384):
        assert x0 % 16 == 0 and z0 % 16 == 0 and sx % 16 == 0 and sz % 16 == 0
        assert y0 % 16 == 0 and sy % 16 == 0
        self.x0, self.y0, self.z0 = x0, y0, z0
        self.sx, self.sy, self.sz = sx, sy, sz
        self.palette = ["minecraft:air"]
        self.pindex = {"minecraft:air": 0}
        # Arrays are indexed [x, y, z].
        self.blocks = np.zeros((sx, sy, sz), dtype=np.uint16)
        self.sky = np.zeros((sx, sy, sz), dtype=np.uint8)
        self.blight = np.zeros((sx, sy, sz), dtype=np.uint8)
        self.biome_palette = []
        self.bindex = {}
        self.biomes = np.zeros((sx // 4, sy // 4, sz // 4), dtype=np.uint16)
        self.block_entities = []
        for cx in range(x0 // 16, (x0 + sx) // 16):
            for cz in range(z0 // 16, (z0 + sz) // 16):
                self._load(region_dir, cx, cz)

    def _pid(self, key):
        i = self.pindex.get(key)
        if i is None:
            i = len(self.palette)
            self.palette.append(key)
            self.pindex[key] = i
        return i

    def _bid(self, key):
        i = self.bindex.get(key)
        if i is None:
            i = len(self.biome_palette)
            self.biome_palette.append(key)
            self.bindex[key] = i
        return i

    def _load(self, region_dir, cx, cz):
        nbt = read_chunk(region_dir, cx, cz)
        if nbt is None:
            raise RuntimeError("chunk %d,%d not generated" % (cx, cz))
        if "full" not in str(nbt.get("Status", "")):
            raise RuntimeError("chunk %d,%d not fully generated (%s)" % (cx, cz, nbt.get("Status")))
        lx = cx * 16 - self.x0
        lz = cz * 16 - self.z0
        for be in nbt.get("block_entities", []):
            self.block_entities.append(be)
        # Sky light sections are omitted when they are all dark (below the lit part of the
        # column) or when nothing above them could change (above the highest stored one).
        lit = [s["Y"] for s in nbt.get("sections", []) if "SkyLight" in s]
        top_lit = max(lit) if lit else -100
        top_y = (top_lit + 1) * 16 - self.y0
        if top_y < self.sy:
            self.sky[lx:lx + 16, max(top_y, 0):, lz:lz + 16] = 15
        for sec in nbt.get("sections", []):
            sy = sec["Y"]
            ly = sy * 16 - self.y0
            if ly < 0 or ly >= self.sy:
                continue
            bs = sec.get("block_states")
            if bs is not None:
                pal = [self._pid(state_key(e)) for e in bs["palette"]]
                pal = np.array(pal, dtype=np.uint16)
                if len(pal) == 1:
                    vals = np.zeros(4096, dtype=np.uint16)
                else:
                    bits = max(4, int(np.ceil(np.log2(len(pal)))))
                    vals = unpack_longs(bs["data"], bits, 4096)
                # Index order is y, z, x.
                arr = pal[vals].reshape(16, 16, 16).transpose(2, 0, 1)
                self.blocks[lx:lx + 16, ly:ly + 16, lz:lz + 16] = arr
            if "SkyLight" in sec:
                self.sky[lx:lx + 16, ly:ly + 16, lz:lz + 16] = \
                    nibbles(sec["SkyLight"]).reshape(16, 16, 16).transpose(2, 0, 1)
            if "BlockLight" in sec:
                self.blight[lx:lx + 16, ly:ly + 16, lz:lz + 16] = \
                    nibbles(sec["BlockLight"]).reshape(16, 16, 16).transpose(2, 0, 1)
            bi = sec.get("biomes")
            if bi is not None:
                bpal = np.array([self._bid(n) for n in bi["palette"]], dtype=np.uint16)
                if len(bpal) == 1:
                    bv = np.zeros(64, dtype=np.uint16)
                else:
                    bits = int(np.ceil(np.log2(len(bpal))))
                    bv = unpack_longs(bi["data"], bits, 64)
                self.biomes[lx // 4:lx // 4 + 4, ly // 4:ly // 4 + 4, lz // 4:lz // 4 + 4] = \
                    bpal[bv].reshape(4, 4, 4).transpose(2, 0, 1)

    def name(self, x, y, z):
        return self.palette[self.blocks[x - self.x0, y - self.y0, z - self.z0]]

    def save(self, path):
        np.savez_compressed(path, blocks=self.blocks, sky=self.sky, blight=self.blight,
                            biomes=self.biomes, palette=np.array(self.palette),
                            biome_palette=np.array(self.biome_palette),
                            origin=np.array([self.x0, self.y0, self.z0]))
