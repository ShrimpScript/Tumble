"""Packs block textures into a texture array (one 16x16 layer per frame).

Animated textures (water, lava, fire, ...) keep every frame as its own layer and
record frame count and frame time, so the shader can play them back at the
game's own 20 ticks per second.
"""
import json
import os

import numpy as np
from PIL import Image


class TextureArray:
    def __init__(self, assets):
        self.assets = assets
        self.names = []
        self.index = {}
        self.layers = []   # list of 16x16x4 uint8 arrays
        self.anim = []     # per name: (first_layer, frames, frametime)

    def layer_of(self, tex):
        i = self.index.get(tex)
        if i is not None:
            return self.anim[i][0]
        path = os.path.join(self.assets, "textures", tex + ".png")
        if os.path.exists(path):
            img = np.asarray(Image.open(path).convert("RGBA"))
        else:
            img = np.zeros((16, 16, 4), np.uint8)
            img[::2, ::2] = (255, 0, 255, 255)
            img[1::2, 1::2] = (255, 0, 255, 255)
        w = img.shape[1]
        frames = max(1, img.shape[0] // w)
        frametime = 1
        order = list(range(frames))
        meta = path + ".mcmeta"
        if os.path.exists(meta):
            with open(meta) as f:
                m = json.load(f).get("animation", {})
            frametime = m.get("frametime", 1)
            if "frames" in m:
                order = [fr if isinstance(fr, int) else fr["index"] for fr in m["frames"]]
        first = len(self.layers)
        for k in order:
            fr = img[k * w:(k + 1) * w]
            if w != 16:
                fr = np.asarray(Image.fromarray(fr).resize((16, 16), Image.NEAREST))
            self.layers.append(fr)
        i = len(self.names)
        self.names.append(tex)
        self.index[tex] = i
        self.anim.append((first, len(order), frametime))
        return first

    def save(self, prefix):
        arr = np.stack(self.layers).astype(np.uint8)
        arr.tofile(prefix + ".rgba")
        # Animation lookup keyed by first layer.
        anim = {str(f): [n, t] for (f, n, t) in self.anim if n > 1}
        with open(prefix + ".json", "w") as f:
            json.dump({"layers": len(self.layers), "names": self.names,
                       "first": [a[0] for a in self.anim], "anim": anim}, f)
