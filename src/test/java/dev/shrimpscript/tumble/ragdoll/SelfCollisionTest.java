package dev.shrimpscript.tumble.ragdoll;

import dev.shrimpscript.tumble.physics.Aabb;
import dev.shrimpscript.tumble.physics.WorldCollider;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Self collision is what stops a settled ragdoll from reading as a flat pile with an arm
 * inside a leg. The negative case is asserted too, so the test cannot pass by doing
 * nothing.
 */
class SelfCollisionTest {

    private static final Aabb FLOOR = new Aabb(-64.0D, -1.0D, -64.0D, 64.0D, 0.0D, 64.0D);

    private static WorldCollider floor() {
        return (query, out) -> {
            if (query.intersects(FLOOR)) {
                out.add(FLOOR);
            }
        };
    }

    private static RagdollSkeleton drop(boolean selfCollision) {
        RagdollSkeleton skeleton = new RagdollSkeleton(0.5D, 3.0D, 0.5D, 0.0D, selfCollision);
        skeleton.setCollider(floor());
        // A sideways shove, so the body actually tangles rather than landing on its feet.
        skeleton.launch(new Vector3d(4.0D, 0.0D, 1.0D));

        for (int i = 0; i < 150; i++) {
            skeleton.tick();
        }
        return skeleton;
    }

    @Test
    void limbsDoNotEndUpInsideEachOther() {
        RagdollSkeleton skeleton = drop(true);

        double overlap = skeleton.worstSelfOverlap();
        // A soft compliance is used, so a sliver of overlap is expected and invisible.
        // One Minecraft pixel is 0.0625 blocks; the bar is well under that.
        assertTrue(overlap < 0.03D,
                "limbs overlapped by " + overlap + " blocks despite self collision");
    }

    @Test
    void withoutSelfCollisionLimbsDoOverlap() {
        // Same drop, self collision off, measured with a throwaway tracker.
        RagdollSkeleton unprotected = drop(false);
        RagdollSkeleton protectedDoll = drop(true);

        // The unprotected doll has no tracker, so compare a direct limb-pair distance.
        double loose = closestLimbDistance(unprotected);
        double held = closestLimbDistance(protectedDoll);

        assertTrue(held > loose,
                "self collision should hold limbs further apart: with=" + held + " without=" + loose);
    }

    /** Smallest centre-to-centre distance between two parts that are not joined. */
    private static double closestLimbDistance(RagdollSkeleton skeleton) {
        BodyPart[] limbs = {BodyPart.HEAD, BodyPart.ARM_LEFT, BodyPart.ARM_RIGHT,
                BodyPart.LEG_LEFT, BodyPart.LEG_RIGHT};

        double closest = Double.MAX_VALUE;
        for (int i = 0; i < limbs.length; i++) {
            for (int j = i + 1; j < limbs.length; j++) {
                closest = Math.min(closest,
                        skeleton.part(limbs[i]).pos.distance(skeleton.part(limbs[j]).pos));
            }
        }
        return closest;
    }
}
