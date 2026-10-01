"""Searches creeper positions behind the player for the most entertaining fall,
with each candidate's real crater carved out before the ragdoll is simulated."""
import concurrent.futures as cf
import math
import os
import subprocess
import sys

import numpy as np

from explosion import explode

WORK = os.environ.get("TRAILER_WORK", "/home/user/work")
d = np.load(os.path.join(WORK, "box_scene.npz"))
blocks, pal = d["blocks"], [str(p) for p in d["palette"]]
x0, y0, z0 = [int(v) for v in d["origin"]]


def get_block(x, y, z):
    return pal[blocks[x - x0, y - y0, z - z0]]


from mesher import NOCOLLIDE_WORDS


def collides(name):
    short = name.split(":")[1].split("[")[0]
    if short in ("air", "cave_air", "void_air", "water", "lava"):
        return False
    if short in ("grass_block", "snow_block", "moss_block", "dirt_path", "rooted_dirt"):
        return True
    return not any(w in short for w in NOCOLLIDE_WORDS)


def stand_y(x, z, start=110, half=0.3):
    """Feet height of an entity of half-width `half` standing at x, z (highest support)."""
    best = -999
    for bx in range(int(np.floor(x - half + 1e-6)), int(np.floor(x + half - 1e-6)) + 1):
        for bz in range(int(np.floor(z - half + 1e-6)), int(np.floor(z + half - 1e-6)) + 1):
            for y in range(start, 0, -1):
                if collides(get_block(bx, y, bz)):
                    best = max(best, y + 1)
                    break
    return best


SIM = ["java", "-cp", WORK + "/simbin:" + WORK + "/lib/joml-1.10.5.jar", "TrailerSim", "run", WORK + "/scene"]


def run(args):
    px, pz, dist, ang = args
    cx = px + math.cos(math.radians(ang)) * dist
    cz = pz + math.sin(math.radians(ang)) * dist
    py = stand_y(px, pz)
    cy = stand_y(cx, cz)
    if cy - py > 1 or cy < py - 1:
        return None
    crater = explode(get_block, cx, cy, cz, 3.0, seed=7)
    name = "/tmp/claude-0/c_%d_%d_%d_%d" % (int(px * 10), int(pz * 10), int(dist * 10), ang)
    with open(name + ".txt", "w") as f:
        f.write("feet = %f, %d, %f\nyaw = -90\nticks = 320\nhealth = 20\nblast = %f,%d,%f,3\n" % (px, py, pz, cx, cy, cz))
        f.write("carve = " + ";".join("%d,%d,%d" % p for p in sorted(crater)) + "\n")
    subprocess.run(SIM + [name + ".txt", name], capture_output=True)
    ev = open(name + ".events.txt").read().splitlines()[1:]
    hits = [l for l in ev if " hurt " in l or " bump " in l]
    big = [l for l in ev if " hurt " in l]
    ys = [round(float(l.split(" at ")[1].split()[1])) for l in hits]
    ticks = [int(l.split()[0]) for l in hits]
    health = 20 - sum(float(l.split()[2]) for l in big)
    return (args, py, round(cx, 4), cy, round(cz, 4), len(crater), len(hits), ticks, ys, round(health, 1))


if __name__ == "__main__":
    jobs = [(px, pz, dd, a) for px in (1497.5, 1497.6) for pz in (136.5, 137.0, 137.5)
            for dd in (1.4, 1.7, 2.0, 2.4) for a in range(120, 241, 6)]
    with cf.ThreadPoolExecutor(4) as ex:
        res = [r for r in ex.map(run, jobs) if r]
    good = [r for r in res if r[8] and min(r[8]) < 21]
    good.sort(key=lambda r: (-len(set(r[8])), -r[6]))
    for r in good[:30]:
        print(r)
