#!/bin/bash
# Builds the whole trailer from nothing: Minecraft from Mojang, a world from the vanilla
# server, Tumble's solver for every ragdoll, a frame-by-frame render and the soundtrack.
#
#   ACCEPT_MINECRAFT_EULA=1 trailer/tools/run_all.sh [out.mp4]
#
# Needs JDK 17+, Python 3 with numpy, scipy and pillow, Node 18+ with Playwright and its
# Chromium, Xvfb, and ffmpeg. Everything is written under $TRAILER_WORK.
set -e
HERE="$(cd "$(dirname "$0")/.." && pwd)"
export TRAILER_WORK="${TRAILER_WORK:-$HOME/tumble-trailer-work}"
OUT="${1:-$HERE/tumble-trailer.mp4}"
mkdir -p "$TRAILER_WORK"

python3 "$HERE/tools/fetch_minecraft.py"
"$HERE/tools/generate_world.sh"

cd "$HERE/world"
python3 - <<PY
from anvil import WorldBox
w = WorldBox("$TRAILER_WORK/server/world/region", 1296, -64, 416, 400, y0=-64, sy=320)
w.save("$TRAILER_WORK/box_scene.npz")
PY
python3 build_scene.py "$TRAILER_WORK/box_scene.npz" "$TRAILER_WORK/scene"
python3 build_scene.py "$TRAILER_WORK/box_scene.npz" "$TRAILER_WORK/scene" post

"$HERE/physics/build.sh"
python3 make_sims.py fall montage

python3 "$HERE/tools/fetch_fonts.py"

cd "$HERE/render"
mkdir -p "$TRAILER_WORK/render" && cp package.json "$TRAILER_WORK/render/" && (cd "$TRAILER_WORK/render" && npm install --silent)
ln -sfn "$TRAILER_WORK/render/node_modules" node_modules
xvfb-run -a -s "-screen 0 1920x1080x24" node render.js --out "$TRAILER_WORK/frames" --w 1920 --h 1080 --skipExisting 1

python3 "$HERE/audio/fetch_sounds.py"
python3 "$HERE/audio/score.py" "$TRAILER_WORK/music.wav"
python3 "$HERE/audio/mix.py" "$TRAILER_WORK/frames/cues.json" "$TRAILER_WORK/music.wav" "$TRAILER_WORK/mix.wav"
"$HERE/tools/encode.sh" "$TRAILER_WORK/frames" "$TRAILER_WORK/mix.wav" "$OUT"
