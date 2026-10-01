#!/bin/bash
# Muxes the rendered frames and the mixed soundtrack into the final MP4.
#   tools/encode.sh FRAMES_DIR MIX.wav OUT.mp4
# Two-pass H.264 at a fixed bitrate keeps the file under GitHub's 100 MB limit while
# staying at YouTube's recommended 1080p30 upload quality.
set -e
FRAMES="$1"; MIX="$2"; OUT="$3"
RATE="${VIDEO_KBPS:-5200}"
PASSLOG="$(mktemp -d)/x264"
COMMON=(-framerate 30 -i "$FRAMES/f%05d.png")
ffmpeg -y -loglevel error "${COMMON[@]}" -c:v libx264 -preset slow -tune animation -b:v "${RATE}k" \
  -pix_fmt yuv420p -profile:v high -level 4.1 -pass 1 -passlogfile "$PASSLOG" -an -f mp4 /dev/null
ffmpeg -y -loglevel error "${COMMON[@]}" -i "$MIX" -map 0:v -map 1:a \
  -c:v libx264 -preset slow -tune animation -b:v "${RATE}k" -maxrate "$((RATE * 2))k" -bufsize "$((RATE * 4))k" \
  -pix_fmt yuv420p -profile:v high -level 4.1 -pass 2 -passlogfile "$PASSLOG" -movflags +faststart \
  -c:a aac -b:a 256k -ar 48000 -shortest "$OUT"
rm -rf "$(dirname "$PASSLOG")"
ls -la "$OUT"
