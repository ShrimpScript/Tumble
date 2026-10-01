"""Writes every ragdoll simulation the trailer uses, all through Tumble's own solver."""
import json
import os
import subprocess
import sys

import numpy as np

import edits
from explosion import explode

WORK = os.environ.get("TRAILER_WORK", "/home/user/work")
SIM = ["java", "-cp", WORK + "/simbin:" + WORK + "/lib/joml-1.10.5.jar", "TrailerSim", "run", WORK + "/scene"]
OUT = os.path.join(WORK, "sim")
os.makedirs(OUT, exist_ok=True)

d = np.load(os.path.join(WORK, "box_scene.npz"))
blocks, pal = d["blocks"], [str(p) for p in d["palette"]]
x0, y0, z0 = [int(v) for v in d["origin"]]


def get_block(x, y, z):
    return pal[blocks[x - x0, y - y0, z - z0]]


def sim(name, text):
    path = os.path.join(OUT, name)
    with open(path + ".txt", "w") as f:
        f.write(text)
    r = subprocess.run(SIM + [path + ".txt", path], capture_output=True, text=True)
    print(name, r.stdout.strip(), r.stderr.strip()[-300:] if r.returncode else "")


def main_fall():
    px, py, pz = edits.PLAYER_FEET
    cx, cy, cz = edits.CREEPER_FEET
    crater = explode(get_block, cx, cy, cz, 3.0, seed=edits.CRATER_SEED)
    carve = ";".join("%d,%d,%d" % p for p in sorted(crater))
    sim("fall", "feet = %f, %d, %f\nyaw = -90\nticks = 400\nhealth = 20\nblast = %f,%d,%f,3\ncarve = %s\n" % (
        px, py, pz, cx, cy, cz, carve))
    # Dying while ragdolled: CorpseHandler ends the ragdoll and CorpseEntity.of builds a
    # fresh standing body at the player's position (the ragdoll's centre) carrying the
    # torso's velocity. That body is what lies at the bottom of the cave afterwards.
    death = None
    for line in open(os.path.join(OUT, "fall.events.txt")):
        if line.split()[1:2] == ["death"]:
            death = int(line.split()[0])
    f = np.fromfile(os.path.join(OUT, "fall.f32"), dtype="<f4").reshape(-1, 6, 7)
    mass = np.array([5, 30, 4, 4, 10, 10.0])
    com = (f[death, :, :3] * mass[:, None]).sum(0) / mass.sum()
    vel = (f[death, 1, :3] - f[death - 1, 1, :3]) * 20
    sim("corpse", "feet = %f, %f, %f\nyaw = -90\nticks = 200\nhealth = 20\nlaunch = %f,%f,%f\ncarve = %s\n" % (
        com[0], com[1] + 0.1, com[2], vel[0], vel[1], vel[2], carve))
    with open(os.path.join(OUT, "fall.meta.json"), "w") as fh:
        json.dump({"deathTick": death, "player": [px, py, pz], "creeper": [cx, cy, cz]}, fh)


from mesher import NOCOLLIDE_WORDS


def collides(name):
    short = name.split(":")[1].split("[")[0]
    if short in ("air", "cave_air", "void_air", "water", "lava"):
        return False
    if short in ("grass_block", "snow_block", "moss_block", "dirt_path", "rooted_dirt"):
        return True
    return not any(w in short for w in NOCOLLIDE_WORDS)


def stand_y(x, z, y_ref):
    """Feet height for an entity near y_ref: first solid scanning down, over the footprint."""
    best = -999
    for bx in {int(np.floor(x - 0.299)), int(np.floor(x + 0.299))}:
        for bz in {int(np.floor(z - 0.299)), int(np.floor(z + 0.299))}:
            for y in range(int(y_ref + 0.6), int(y_ref) - 20, -1):
                if collides(get_block(bx, y, bz)):
                    best = max(best, y + 1)
                    break
    return best


def montage():
    # Lightning: Tumble launches the struck player straight up at lightningLaunchSpeed (12).
    lx, lz = 1508.5, 176.5
    ly = stand_y(lx, lz, 110)
    sim("lightning", "feet = %f, %d, %f\nyaw = 150\nticks = 120\nlaunch = 0, 12, 0\n" % (lx, ly, lz))

    # Going limp mid-stride (the keybind), carrying walking speed, then rolling downhill.
    gx, gz = 1482.5, 151.5
    gy = stand_y(gx, gz, 105)
    walk = 4.317 / np.sqrt(2)
    sim("limp", "feet = %f, %d, %f\nyaw = -135\nticks = 160\nmotion = %f, 0, %f\n"
        "pose = 0, 0.15, -0.55, 0.55, 0.75, -0.75, 0\nroll = 26, 110, 1, -1\n" % (gx, gy, gz, walk, -walk))

    # A zombie killed by a sword hit: vanilla knockback (0.4 back, 0.4 up per tick) becomes
    # the corpse's momentum, then Steve grabs a leg and drags the body away.
    zx, zz = 1509.5, 179.8
    zy = stand_y(zx, zz, 110)
    base = "feet = %f, %d, %f\nyaw = 180\nticks = 230\nlaunch = 0.6, 8, 8\npose = 0, 0, 0.1, -0.1, 0.3, -0.3, 0\n" % (zx, zy, zz)
    sim("zombie_probe", base)
    f = np.fromfile(os.path.join(OUT, "zombie_probe.f32"), dtype="<f4").reshape(-1, 6, 7)
    leg = f[70, 5, :3]
    # The hand sits 2 blocks out from Steve's eyes (grab holdDistance), pulling east.
    hand = []
    for tk in range(70, 221, 10):
        u = (tk - 70) / 20.0
        hand.append("%d:%f,%f,%f" % (tk, leg[0] - 1.2 - 1.6 * u, zy + 1.45, leg[2] - 0.2))
    sim("zombie", base + "grab = 5, 70, 220\ngrabLead = 1.9\nhand = %s\n" % ";".join(hand))
    os.remove(os.path.join(OUT, "zombie_probe.f32"))
    os.remove(os.path.join(OUT, "zombie_probe.events.txt"))
    os.remove(os.path.join(OUT, "zombie_probe.txt"))

    # Elytra into the lone cherry tree: the hit stops the horizontal motion, and the crash
    # trigger throws the body with what is left of it, times crashLaunchMultiplier (3).
    ex, ez = 1466.62, 141.5
    ey = 87.2
    sim("elytra", "feet = %f, %f, %f\nyaw = 90\nticks = 120\nlaunch = 0, -4.5, 0.9\n" % (ex, ey, ez))

    # Lying down next to yourself.
    fx, fz = 1503.5, 137.5
    fy = stand_y(fx, fz, 24)
    sim("flop", "feet = %f, %d, %f\nyaw = -100\nticks = 120\nlaunch = 1.6, 0, -0.4\n" % (fx, fy, fz))
    with open(os.path.join(OUT, "montage.meta.json"), "w") as fh:
        json.dump({"lightning": [lx, ly, lz], "limp": [gx, gy, gz], "zombie": [zx, zy, zz],
                   "elytra": [ex, ey, ez], "flop": [fx, fy, fz]}, fh)


if __name__ == "__main__":
    which = sys.argv[1:] or ["fall", "montage"]
    if "fall" in which:
        main_fall()
    if "montage" in which:
        montage()
    import glob
    names = sorted(os.path.basename(p)[:-4] for p in glob.glob(os.path.join(OUT, "*.f32")))
    with open(os.path.join(OUT, "index.json"), "w") as fh:
        json.dump(names, fh)
