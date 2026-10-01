#!/bin/bash
# Compiles Tumble's own solver (unchanged) plus the trailer harness, with no Minecraft.
# Only LimbPose.of(LivingEntity) is stripped, since it reads a live entity.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$HERE/../.." && pwd)"
WORK="${TRAILER_WORK:-/home/user/work}"
JOML="$WORK/lib/joml-1.10.5.jar"
SRC="$WORK/simsrc"
rm -rf "$SRC" "$WORK/simbin"
mkdir -p "$SRC/dev/shrimpscript/tumble/physics" "$SRC/dev/shrimpscript/tumble/ragdoll" "$WORK/simbin"
M="$REPO/src/main/java/dev/shrimpscript/tumble"
cp "$M"/physics/*.java "$SRC/dev/shrimpscript/tumble/physics/"
for f in BodyPart HumanoidGeometry RagdollSkeleton; do cp "$M/ragdoll/$f.java" "$SRC/dev/shrimpscript/tumble/ragdoll/"; done
python3 - "$M/ragdoll/LimbPose.java" "$SRC/dev/shrimpscript/tumble/ragdoll/LimbPose.java" <<'PY'
import re, sys
s = open(sys.argv[1]).read()
s = "\n".join(l for l in s.splitlines() if not l.startswith("import net.minecraft"))
start = s.index("    public static LimbPose of(")
# Drop the javadoc directly above, then the method body up to its closing brace.
doc = s.rfind("    /**", 0, start)
i = s.index("{", start)
depth = 0
for j in range(i, len(s)):
    if s[j] == "{":
        depth += 1
    elif s[j] == "}":
        depth -= 1
        if depth == 0:
            break
s = s[:doc] + s[j + 1:]
open(sys.argv[2], "w").write(s)
PY
cp "$HERE"/TrailerSim.java "$SRC/"
javac -nowarn -d "$WORK/simbin" -cp "$JOML" $(find "$SRC" -name '*.java')
echo "built $WORK/simbin"
