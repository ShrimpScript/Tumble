"""Set dressing on top of the generated terrain, and the creeper's crater."""
import numpy as np

from explosion import explode

# The staging, in world coordinates (see PRODUCTION.md, scene 2).
PLAYER_FEET = (1497.6, 90.0, 137.5)
CREEPER_FEET = (1495.521539, 91.0, 138.7)
CRATER_SEED = 7


def crater(m):
    return explode(m.get_block, CREEPER_FEET[0], CREEPER_FEET[1], CREEPER_FEET[2], 3.0, seed=CRATER_SEED)


def carve(m, cells):
    """Removes blocks and re-lights the hole they leave."""
    air = m.state_id("minecraft:air")
    for (x, y, z) in cells:
        m.set_block(x, y, z, "minecraft:air")
    # Sky light: open columns get full light, the rest is flooded from neighbours.
    cells = sorted(cells, key=lambda c: -c[1])
    for _ in range(4):
        for (x, y, z) in cells:
            lx, ly, lz = x - m.x0, y - m.y0, z - m.z0
            col = m.blocks[lx, ly + 1:, lz]
            if not m.occludes[col].any() and not np.isin(col, np.nonzero(m.translucent)[0]).any():
                m.sky[lx, ly, lz] = 15
                continue
            best = 0
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                best = max(best, int(m.sky[lx + dx, ly + dy, lz + dz]) - 1)
            m.sky[lx, ly, lz] = max(int(m.sky[lx, ly, lz]), best)
    # Plants left floating above the crater pop off, as they would in game.
    for (x, y, z) in cells:
        above = m.get_block(x, y + 1, z)
        if not m.collides[m.state_id(above)] and above not in ("minecraft:air",):
            m.set_block(x, y + 1, z, "minecraft:air")


# A survival player had been down here before: torches on the cave floor, and along the
# slope the respawned player walks back down.
TORCHES = [(1503, 22, 144), (1509, 20, 141), (1501, 22, 138), (1511, 17, 134), (1500, 24, 140)]


def place_light(m, cells, level=14):
    """Block light flood fill from new light sources, -1 per step, stopped by opaque blocks."""
    from collections import deque
    q = deque()
    for (x, y, z) in cells:
        lx, ly, lz = x - m.x0, y - m.y0, z - m.z0
        if m.blight[lx, ly, lz] < level:
            m.blight[lx, ly, lz] = level
        q.append((lx, ly, lz))
    occ = m.occludes
    while q:
        x, y, z = q.popleft()
        v = int(m.blight[x, y, z]) - 1
        if v <= 0:
            continue
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            nx, ny, nz = x + dx, y + dy, z + dz
            if occ[m.blocks[nx, ny, nz]]:
                continue
            if m.blight[nx, ny, nz] < v:
                m.blight[nx, ny, nz] = v
                q.append((nx, ny, nz))


def apply(m, post_blast=False):
    for (x, y, z) in TORCHES:
        m.set_block(x, y, z, "minecraft:torch")
    place_light(m, TORCHES)
    if post_blast:
        carve(m, crater(m))
