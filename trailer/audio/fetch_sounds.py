"""Downloads the vanilla 1.20.1 sounds the trailer uses from Mojang's asset index and
converts them to 48 kHz WAV. Nothing is committed: the files are fetched at build time."""
import json
import os
import subprocess
import sys
import urllib.request

WORK = os.environ.get("TRAILER_WORK", "/home/user/work")
INDEX = os.path.join(WORK, "mc", "assets.json")
OUT = os.path.join(WORK, "sounds")

PREFIXES = [
    "step/grass", "step/stone", "random/fuse", "random/explode", "damage/hit", "damage/fallbig", "damage/fallsmall",
    "random/click", "random/pop", "random/chestopen", "random/chestclosed", "ambient/weather/thunder",
    "ambient/weather/rain", "ambient/cave/cave", "mob/zombie/say", "mob/zombie/hurt", "mob/zombie/death",
    "entity/player/attack/strong", "entity/player/attack/crit", "entity/player/attack/sweep", "item/elytra/elytra_loop",
    "note/", "random/levelup", "random/orb", "dig/stone", "dig/grass", "random/swim", "liquid/water",
    "random/burp", "mob/creeper/say",
]

objects = json.load(open(INDEX))["objects"]
os.makedirs(OUT, exist_ok=True)
n = 0
for key, obj in objects.items():
    if not key.startswith("minecraft/sounds/") or not key.endswith(".ogg"):
        continue
    rel = key[len("minecraft/sounds/"):-4]
    if not any(rel.startswith(p) for p in PREFIXES):
        continue
    dst = os.path.join(OUT, rel + ".wav")
    if os.path.exists(dst):
        continue
    os.makedirs(os.path.dirname(dst), exist_ok=True)
    h = obj["hash"]
    url = "https://resources.download.minecraft.net/%s/%s" % (h[:2], h)
    ogg = dst[:-4] + ".ogg"
    urllib.request.urlretrieve(url, ogg)
    subprocess.run(["ffmpeg", "-loglevel", "error", "-y", "-i", ogg, "-ar", "48000", "-ac", "2", dst], check=True)
    os.remove(ogg)
    n += 1
print("fetched", n, "sounds into", OUT)
