package dev.shrimpscript.tumble.entity;

import dev.shrimpscript.tumble.physics.Aabb;
import dev.shrimpscript.tumble.physics.WorldCollider;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.List;

/**
 * The only bridge between the solver and Minecraft's world.
 *
 * <p>Uses the level's own collision shapes, so slabs, stairs, fences and modded blocks
 * are all handled without knowing anything about them.
 */
public final class LevelCollider implements WorldCollider {

    private final Level level;

    public LevelCollider(Level level) {
        this.level = level;
    }

    @Override
    public void collectSolids(Aabb query, List<Aabb> out) {
        AABB box = new AABB(query.minX(), query.minY(), query.minZ(),
                query.maxX(), query.maxY(), query.maxZ());

        for (VoxelShape shape : level.getBlockCollisions(null, box)) {
            for (AABB part : shape.toAabbs()) {
                out.add(new Aabb(part.minX, part.minY, part.minZ, part.maxX, part.maxY, part.maxZ));
            }
        }
    }
}
