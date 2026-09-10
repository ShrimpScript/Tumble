package dev.shrimpscript.tumble.ragdoll;

import dev.shrimpscript.tumble.physics.Aabb;
import dev.shrimpscript.tumble.physics.RigidBody;
import dev.shrimpscript.tumble.physics.WorldCollider;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the full six-body assembly against a synthetic voxel world, headlessly.
 * Everything here would otherwise need a running game to see.
 */
class RagdollSkeletonTest {

    /** Solid predicate for the test world. */
    private interface Solid {
        boolean at(int x, int y, int z);
    }

    /** Ground plane at y=0, a two-block step, and a wall - the shapes a body actually meets. */
    private static final Solid TERRAIN = (x, y, z) -> {
        if (y == -1) {
            return true;
        }
        if (x >= 3 && x <= 5 && y == 0) {
            return true;
        }
        return x == 8 && y >= 0 && y <= 2;
    };

    private static WorldCollider voxels(Solid solid) {
        return (query, out) -> {
            int minX = (int) Math.floor(query.minX());
            int maxX = (int) Math.floor(query.maxX());
            int minY = (int) Math.floor(query.minY());
            int maxY = (int) Math.floor(query.maxY());
            int minZ = (int) Math.floor(query.minZ());
            int maxZ = (int) Math.floor(query.maxZ());

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        if (solid.at(x, y, z)) {
                            out.add(Aabb.ofBlock(x, y, z));
                        }
                    }
                }
            }
        };
    }

    private static RagdollSkeleton ragdollAt(double x, double y, double z) {
        RagdollSkeleton skeleton = new RagdollSkeleton(x, y, z, 0.0D);
        skeleton.setCollider(voxels(TERRAIN));
        return skeleton;
    }

    private static void run(RagdollSkeleton skeleton, int ticks) {
        for (int i = 0; i < ticks; i++) {
            skeleton.tick();
        }
    }

    private static void assertIntact(RagdollSkeleton skeleton) {
        Vector3d torso = skeleton.torso().pos;

        for (BodyPart part : BodyPart.values()) {
            RigidBody body = skeleton.part(part);

            assertTrue(Double.isFinite(body.pos.x) && Double.isFinite(body.pos.y) && Double.isFinite(body.pos.z),
                    part + " went non-finite at " + body.pos);

            // Every part hangs off the torso through a joint, so there is a hard limit on
            // how far it can get. Geometry puts the furthest reach at about 0.8 blocks
            // (a hip 0.39 out plus a leg centre 0.375 below it); anything approaching a
            // block means the body has come apart rather than crumpled.
            assertTrue(body.pos.distance(torso) < 1.0D,
                    part + " strayed " + body.pos.distance(torso) + " blocks from the torso");
        }
    }

    private static void assertAboveGround(RagdollSkeleton skeleton) {
        for (BodyPart part : BodyPart.values()) {
            RigidBody body = skeleton.part(part);
            double lowest = body.pos.y - body.halfExtents.length();
            assertTrue(lowest > -0.5D,
                    part + " sank through the floor to y=" + body.pos.y);
        }
    }

    /**
     * The ragdoll must start facing the way the player was. Reported from play: every
     * ragdoll spawned back to front.
     *
     * <p>Vanilla draws a humanoid as rotateY(180 - yaw) then scale(-1,-1,1), and this mod
     * reuses those model parts, so the body orientation has to carry the same half turn.
     * The model's face looks along its own -Z.
     */
    @Test
    void ragdollStartsFacingTheSameWayAsThePlayer() {
        // Minecraft yaw 0 faces south, which is +Z; yaw 90 faces west, which is -X.
        assertFacing(0.0D, 0.0D, 1.0D);
        assertFacing(90.0D, -1.0D, 0.0D);
        assertFacing(180.0D, 0.0D, -1.0D);
        assertFacing(-90.0D, 1.0D, 0.0D);
    }

    private static void assertFacing(double yaw, double expectedX, double expectedZ) {
        RagdollSkeleton skeleton = new RagdollSkeleton(
                0.0D, 0.0D, 0.0D, RagdollSkeleton.facingFromYaw(yaw), false);

        Vector3d forward = skeleton.torso().rot.transform(new Vector3d(0.0D, 0.0D, -1.0D));

        assertTrue(Math.abs(forward.x - expectedX) < 1.0e-6D && Math.abs(forward.z - expectedZ) < 1.0e-6D,
                "at yaw " + yaw + " the body faced (" + forward.x + ", " + forward.z
                        + ") but the player faced (" + expectedX + ", " + expectedZ + ")");
    }

    @Test
    void droppedRagdollSettlesOnTheGround() {
        RagdollSkeleton skeleton = ragdollAt(0.5D, 5.0D, 0.5D);

        run(skeleton, 100);

        assertIntact(skeleton);
        assertAboveGround(skeleton);
        assertTrue(skeleton.isSettled(0.25D),
                "ragdoll should have come to rest within 5 seconds, fastest part was "
                        + skeleton.world().maxBodySpeed());
    }

    @Test
    void ragdollLaunchedAtTheSpeedCapStaysInTheWorld() {
        RagdollSkeleton skeleton = ragdollAt(0.5D, 12.0D, 0.5D);
        // The configured launch cap, straight down: 6.4 blocks of travel per tick.
        skeleton.launch(new Vector3d(0.0D, -128.0D, 0.0D));

        run(skeleton, 120);

        assertIntact(skeleton);
        assertAboveGround(skeleton);
    }

    @Test
    void ragdollDroppedOnAStepRestsOnTopOfIt() {
        RagdollSkeleton skeleton = ragdollAt(4.5D, 6.0D, 0.5D);

        run(skeleton, 120);

        assertIntact(skeleton);
        // The step's surface is y=1, so nothing may end up inside it.
        for (BodyPart part : BodyPart.values()) {
            RigidBody body = skeleton.part(part);
            if (body.pos.x > 3.0D && body.pos.x < 6.0D) {
                assertTrue(body.pos.y + body.halfExtents.length() > 0.5D,
                        part + " sank into the step at " + body.pos);
            }
        }
    }

    /**
     * A launched body must actually fly. Reported from play: an explosion made bodies
     * crumple next to it instead of throwing them, because linear damping was bleeding a
     * third of the launch away within a second. This pins the ballistics so that cannot
     * come back quietly.
     */
    @Test
    void launchedRagdollIsActuallyThrown() {
        RagdollSkeleton skeleton = ragdollAt(0.5D, 1.0D, 0.5D);
        // Roughly what standing beside TNT now imparts.
        skeleton.launch(new Vector3d(20.0D, 30.0D, 0.0D));

        double startY = skeleton.torso().pos.y;
        double peakY = startY;
        for (int i = 0; i < 80; i++) {
            skeleton.tick();
            peakY = Math.max(peakY, skeleton.torso().pos.y);
        }

        assertIntact(skeleton);
        // Ballistics for 30 blocks/s up under Minecraft gravity put the apex near 14
        // blocks; anything under 8 means something is eating the launch.
        assertTrue(peakY - startY > 8.0D,
                "body only reached " + (peakY - startY) + " blocks up; the launch is being absorbed");
        assertTrue(skeleton.torso().pos.x > 8.0D,
                "body only travelled to x=" + skeleton.torso().pos.x + "; it should be thrown clear");
    }

    /**
     * Ground detection gates when a player may stand up, so it has to be right in both
     * directions: a body in flight must not count as landed, and a settled one must.
     */
    @Test
    void groundedOnlyReportsTrueOnceTheBodyHasLanded() {
        RagdollSkeleton skeleton = ragdollAt(0.5D, 12.0D, 0.5D);

        // Well clear of the floor and falling.
        run(skeleton, 5);
        assertTrue(!skeleton.isGrounded(), "a body in mid-air must not count as landed");

        run(skeleton, 120);
        assertTrue(skeleton.isGrounded(), "a settled body must count as landed");
    }

    /** A body thrown clear of the ground stops counting as landed again. */
    @Test
    void groundedResetsWhenTheBodyIsThrownBackIntoTheAir() {
        RagdollSkeleton skeleton = ragdollAt(0.5D, 1.0D, 0.5D);
        run(skeleton, 120);
        assertTrue(skeleton.isGrounded(), "precondition: the body should have settled");

        skeleton.launch(new Vector3d(0.0D, 30.0D, 0.0D));
        run(skeleton, 10);

        assertTrue(!skeleton.isGrounded(), "a body launched back into the air is not landed");
    }

    /**
     * Reported from play: bodies sprawled and limbs passed through each other, most
     * visibly on death. A violent tumble is still allowed to crumple, but it has to stay
     * recognisably a person.
     */
    @Test
    void aViolentTumbleStillLooksLikeABody() {
        RagdollSkeleton skeleton = new RagdollSkeleton(0.5D, 6.0D, 0.5D, 0.0D, true, LimbPose.STANDING);
        skeleton.setCollider(voxels(TERRAIN));

        // Thrown hard and spinning, which is what a death by explosion looks like.
        skeleton.launch(new Vector3d(14.0D, 6.0D, 9.0D));
        for (BodyPart part : BodyPart.values()) {
            skeleton.part(part).angVel.set(9.0D, -7.0D, 11.0D);
        }

        double worstOverlap = 0.0D;
        for (int i = 0; i < 200; i++) {
            skeleton.tick();
            worstOverlap = Math.max(worstOverlap, skeleton.worstSelfOverlap());
            assertIntact(skeleton);
        }

        // A Minecraft pixel is 0.0625 blocks. Limbs may press together; they may not sink
        // a visible amount into one another.
        assertTrue(worstOverlap < 0.0625D,
                "limbs sank " + worstOverlap + " blocks into each other during the tumble");
    }

    @Test
    void ragdollThrownAtAWallDoesNotPassThroughIt() {
        RagdollSkeleton skeleton = ragdollAt(0.5D, 1.0D, 0.5D);
        skeleton.launch(new Vector3d(60.0D, 0.0D, 0.0D));

        run(skeleton, 120);

        assertIntact(skeleton);
        // The wall occupies x in [8, 9]. Nothing should have ended up past it.
        for (BodyPart part : BodyPart.values()) {
            assertTrue(skeleton.part(part).pos.x < 9.0D,
                    part + " passed through the wall to x=" + skeleton.part(part).pos.x);
        }
    }
}
