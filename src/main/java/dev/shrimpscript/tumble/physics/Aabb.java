package dev.shrimpscript.tumble.physics;

/** An immutable axis-aligned box in world space, in blocks. */
public record Aabb(double minX, double minY, double minZ,
                   double maxX, double maxY, double maxZ) {

    public static Aabb ofBlock(int x, int y, int z) {
        return new Aabb(x, y, z, x + 1.0D, y + 1.0D, z + 1.0D);
    }

    public Aabb inflate(double d) {
        return new Aabb(minX - d, minY - d, minZ - d, maxX + d, maxY + d, maxZ + d);
    }

    public boolean contains(double x, double y, double z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    public boolean intersects(Aabb o) {
        return minX < o.maxX && maxX > o.minX
                && minY < o.maxY && maxY > o.minY
                && minZ < o.maxZ && maxZ > o.minZ;
    }
}
