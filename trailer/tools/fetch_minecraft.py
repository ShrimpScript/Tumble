"""Downloads the vanilla 1.20.1 client jar, server jar and asset index from Mojang,
and extracts the client's block states, models, textures and fonts.

Nothing downloaded here is committed: Mojang's assets may be used in videos but not
redistributed, so the pipeline fetches them on every machine that builds the trailer."""
import json
import os
import urllib.request
import zipfile

WORK = os.environ.get("TRAILER_WORK", "/home/user/work")
MC = os.path.join(WORK, "mc")
os.makedirs(MC, exist_ok=True)


def get(url, dst):
    if not os.path.exists(dst):
        urllib.request.urlretrieve(url, dst)
    return dst


manifest = json.load(open(get("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json", os.path.join(MC, "manifest.json"))))
version = next(v for v in manifest["versions"] if v["id"] == "1.20.1")
meta = json.load(open(get(version["url"], os.path.join(MC, "1.20.1.json"))))
get(meta["downloads"]["client"]["url"], os.path.join(MC, "client.jar"))
get(meta["downloads"]["server"]["url"], os.path.join(MC, "server.jar"))
get(meta["assetIndex"]["url"], os.path.join(MC, "assets.json"))

out = os.path.join(WORK, "assets")
with zipfile.ZipFile(os.path.join(MC, "client.jar")) as z:
    for name in z.namelist():
        if name.startswith(("assets/minecraft/blockstates/", "assets/minecraft/models/", "assets/minecraft/textures/",
                            "assets/minecraft/font/", "assets/minecraft/lang/en_us.json")):
            z.extract(name, out)
print("Minecraft 1.20.1 fetched into", MC, "and extracted into", out)
