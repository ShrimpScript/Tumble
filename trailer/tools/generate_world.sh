#!/bin/bash
# Generates the trailer's world with the vanilla 1.20.1 dedicated server.
# Running Mojang's server requires accepting the Minecraft EULA
# (https://aka.ms/MinecraftEULA); set ACCEPT_MINECRAFT_EULA=1 to do so.
set -e
WORK="${TRAILER_WORK:-/home/user/work}"
SRV="$WORK/server"
[ "$ACCEPT_MINECRAFT_EULA" = "1" ] || { echo "Set ACCEPT_MINECRAFT_EULA=1 to accept https://aka.ms/MinecraftEULA"; exit 1; }
mkdir -p "$SRV" && cd "$SRV"
cp "$WORK/mc/server.jar" .
echo "eula=true" > eula.txt
cat > server.properties <<PROPS
level-seed=8091867987493326313
online-mode=false
view-distance=4
simulation-distance=4
spawn-monsters=false
spawn-animals=false
spawn-npcs=false
level-name=world
sync-chunk-writes=false
PROPS
rm -f cmd.fifo && mkfifo cmd.fifo
(tail -f cmd.fifo | java -Xmx6G -jar server.jar nogui > server.log 2>&1 &)
until grep -q "Done (" server.log 2>/dev/null; do sleep 2; done
# Force-load the set in 16x16 chunk batches so every chunk is fully generated.
for ((bx=80; bx<=106; bx+=16)); do for ((bz=-8; bz<=31; bz+=16)); do
  ex=$((bx+15)); ez=$((bz+15))
  echo "forceload add $((bx*16)) $((bz*16)) $((ex*16+15)) $((ez*16+15))" > cmd.fifo
  sleep 30
  echo "forceload remove all" > cmd.fifo
  echo "save-all" > cmd.fifo
  sleep 3
done; done
echo "save-all flush" > cmd.fifo
sleep 5
echo "stop" > cmd.fifo
sleep 10
pkill -f "tail -f cmd.fifo" || true
echo "world generated in $SRV/world"
