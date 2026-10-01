"""Vanilla Explosion.explode() block selection, for the creeper crater.

Rays are cast from the centre through every cell on the surface of a 16x16x16
grid. Each ray starts with radius * (0.7 + rand * 0.6) intensity, steps 0.3
blocks at a time, loses 0.225 per step plus (resistance + 0.3) * 0.3 for every
block it passes, and destroys a block while it still has intensity left.
"""
import math
import random

# Blast resistance for the blocks found around the rim (Blocks.java values).
RESISTANCE = {
    "grass_block": 0.6, "dirt": 0.5, "coarse_dirt": 0.5, "rooted_dirt": 0.5, "podzol": 0.5,
    "stone": 6.0, "andesite": 6.0, "diorite": 6.0, "granite": 6.0, "tuff": 6.0, "deepslate": 6.0,
    "cobblestone": 6.0, "gravel": 0.6, "sand": 0.5, "clay": 0.6, "calcite": 0.75,
    "oak_log": 2.0, "birch_log": 2.0, "cherry_log": 2.0, "oak_leaves": 0.2, "birch_leaves": 0.2,
    "cherry_leaves": 0.2, "water": 100.0, "lava": 100.0, "coal_ore": 3.0, "iron_ore": 3.0,
    "copper_ore": 3.0, "moss_block": 0.1, "snow": 0.1, "snow_block": 0.2,
}
PLANT_WORDS = ("grass", "fern", "flower", "poppy", "dandelion", "tulip", "orchid", "allium", "bluet",
               "daisy", "cornflower", "lily_of", "petals", "bush")


def resistance(name):
    short = name.split(":")[1].split("[")[0]
    if short in ("air", "cave_air", "void_air"):
        return None
    if short in RESISTANCE:
        return RESISTANCE[short]
    if any(w in short for w in PLANT_WORDS) and "block" not in short:
        return 0.0
    if "ore" in short:
        return 3.0
    return 6.0


def explode(get_block, cx, cy, cz, radius=3.0, seed=1234):
    rnd = random.Random(seed)
    destroyed = set()
    for j in range(16):
        for k in range(16):
            for l in range(16):
                if not (j in (0, 15) or k in (0, 15) or l in (0, 15)):
                    continue
                d0 = j / 15.0 * 2.0 - 1.0
                d1 = k / 15.0 * 2.0 - 1.0
                d2 = l / 15.0 * 2.0 - 1.0
                d3 = math.sqrt(d0 * d0 + d1 * d1 + d2 * d2)
                d0 /= d3
                d1 /= d3
                d2 /= d3
                f = radius * (0.7 + rnd.random() * 0.6)
                x, y, z = cx, cy, cz
                while f > 0.0:
                    bp = (math.floor(x), math.floor(y), math.floor(z))
                    r = resistance(get_block(*bp))
                    if r is not None:
                        f -= (r + 0.3) * 0.3
                    if f > 0.0 and r is not None:
                        destroyed.add(bp)
                    x += d0 * 0.3
                    y += d1 * 0.3
                    z += d2 * 0.3
                    f -= 0.22500001
    return destroyed
