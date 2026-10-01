"""A* over walkable columns: ground = top of the highest solid that isn't leaves, with
two clear blocks above it; steps of at most one block. Prints simplified waypoints."""
import heapq
import math
import os
import sys

import numpy as np

sys.path.insert(0, ".")
from mesher import Mesher
import edits

m = Mesher(os.path.join(os.environ.get("TRAILER_WORK", "/home/user/work"), "box_scene.npz"))
edits.apply(m)
names = m.palette
leafish = np.array(["leaves" in p for p in names])
col = m.collides & ~m.is_air


def ground(x, z):
    lx, lz = x - m.x0, z - m.z0
    colm = m.blocks[lx, :, lz]
    for ly in range(m.blocks.shape[1] - 3, 0, -1):
        sid = colm[ly]
        if col[sid] and not leafish[sid]:
            # Two free cells above (feet and head).
            if col[colm[ly + 1]] or col[colm[ly + 2]]:
                return None
            return ly + m.y0 + 1
    return None


def plan(start, goal, box):
    x0, x1, z0, z1 = box
    g = {}
    for x in range(x0, x1):
        for z in range(z0, z1):
            g[(x, z)] = ground(x, z)
    openq = [(0, start)]
    came = {start: None}
    cost = {start: 0}
    while openq:
        _, cur = heapq.heappop(openq)
        if cur == goal:
            break
        for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1), (1, 1), (1, -1), (-1, 1), (-1, -1)):
            nb = (cur[0] + dx, cur[1] + dz)
            if nb not in g or g[nb] is None or g[cur] is None:
                continue
            if abs(g[nb] - g[cur]) > 1:
                continue
            if dx and dz and (g.get((cur[0] + dx, cur[1])) is None or g.get((cur[0], cur[1] + dz)) is None):
                continue
            step = math.hypot(dx, dz) + (0.6 if g[nb] > g[cur] else 0) + 0.2 * abs(g[nb] - g[cur])
            nc = cost[cur] + step
            if nb not in cost or nc < cost[nb]:
                cost[nb] = nc
                came[nb] = cur
                heapq.heappush(openq, (nc + math.hypot(goal[0] - nb[0], goal[1] - nb[1]), nb))
    path = []
    c = goal
    while c:
        path.append(c)
        c = came.get(c)
    path.reverse()
    return path, g


if __name__ == "__main__":
    sx, sz, gx, gz = [int(v) for v in sys.argv[1:5]]
    path, g = plan((sx, sz), (gx, gz), (1440, 1510, 125, 190))
    print("cells", len(path))
    print(" ".join("(%d,%d,%s)" % (x, z, g[(x, z)]) for x, z in path))
