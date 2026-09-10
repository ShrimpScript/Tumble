package dev.shrimpscript.tumble.physics;

import java.util.List;

/**
 * Supplies the solid geometry the ragdoll collides against.
 *
 * <p>This is the entire seam between the solver and Minecraft. The physics package knows
 * nothing about blocks, chunks or levels - only boxes - which is what keeps it testable
 * without the game.
 */
@FunctionalInterface
public interface WorldCollider {

    /** Appends every solid box overlapping {@code query} to {@code out}. */
    void collectSolids(Aabb query, List<Aabb> out);

    WorldCollider EMPTY = (query, out) -> {
    };
}
