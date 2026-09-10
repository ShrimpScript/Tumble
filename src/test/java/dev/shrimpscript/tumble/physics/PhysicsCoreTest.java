package dev.shrimpscript.tumble.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The physics core has no Minecraft dependency, so it is verified here rather than by
 * relaunching the game. Everything below runs in milliseconds.
 */
class PhysicsCoreTest {

    private static final double TICK = 1.0D / 20.0D;

    /** A floor occupying y in [-1, 0], wide enough that nothing runs off the edge. */
    private static final Aabb FLOOR = new Aabb(-64.0D, -1.0D, -64.0D, 64.0D, 0.0D, 64.0D);

    private static WorldCollider floor() {
        return (query, out) -> {
            if (query.intersects(FLOOR)) {
                out.add(FLOOR);
            }
        };
    }

    private static PhysicsWorld worldWithFloor() {
        PhysicsWorld world = new PhysicsWorld();
        world.collider = floor();
        return world;
    }

    private static RigidBody box(PhysicsWorld world, double half, double mass, double x, double y, double z) {
        RigidBody body = new RigidBody(new Vector3d(half, half, half), mass);
        body.pos.set(x, y, z);
        world.bodies.add(body);
        return body;
    }

    private static void run(PhysicsWorld world, int ticks) {
        for (int i = 0; i < ticks; i++) {
            world.step(TICK);
        }
    }

    private static void assertFinite(RigidBody body) {
        assertTrue(Double.isFinite(body.pos.x) && Double.isFinite(body.pos.y) && Double.isFinite(body.pos.z),
                "position went non-finite: " + body.pos);
        assertTrue(Double.isFinite(body.rot.x) && Double.isFinite(body.rot.y)
                        && Double.isFinite(body.rot.z) && Double.isFinite(body.rot.w),
                "orientation went non-finite: " + body.rot);
        assertTrue(Double.isFinite(body.linVel.x) && Double.isFinite(body.linVel.y) && Double.isFinite(body.linVel.z),
                "velocity went non-finite: " + body.linVel);
    }

    @Test
    void boxDroppedOnFloorRestsAtItsHalfHeight() {
        PhysicsWorld world = worldWithFloor();
        RigidBody body = box(world, 0.25D, 10.0D, 0.0D, 5.0D, 0.0D);

        run(world, 200);

        assertFinite(body);
        assertEquals(0.25D, body.pos.y, 0.02D, "box should rest with its bottom face on the floor");
        assertTrue(body.linVel.length() < 0.05D, "box should have come to rest, speed was " + body.linVel.length());
    }

    @Test
    void bodyLaunchedAtMaxSpeedDoesNotTunnelThroughTheFloor() {
        PhysicsWorld world = worldWithFloor();
        RigidBody body = box(world, 0.25D, 10.0D, 0.0D, 20.0D, 0.0D);
        // 128 blocks/s is the configured launch cap: 6.4 blocks of travel in a single tick,
        // far more than the one-block floor is thick.
        body.linVel.set(0.0D, -128.0D, 0.0D);

        run(world, 100);

        assertFinite(body);
        assertTrue(body.pos.y > 0.0D,
                "body tunnelled through the floor, ended at y=" + body.pos.y);
        assertEquals(0.25D, body.pos.y, 0.05D, "body should end up resting on the floor");
    }

    @Test
    void jointHoldsItsAnchorsTogether() {
        PhysicsWorld world = new PhysicsWorld();

        RigidBody anchor = new RigidBody(new Vector3d(0.25D, 0.375D, 0.125D), 0.0D);
        anchor.pos.set(0.0D, 4.0D, 0.0D);
        world.bodies.add(anchor);

        RigidBody hanging = new RigidBody(new Vector3d(0.125D, 0.375D, 0.125D), 5.0D);
        hanging.pos.set(0.0D, 3.25D, 0.0D);
        world.bodies.add(hanging);

        // The joint frame's X axis is the bone direction, so point it down the limb.
        Quaterniond down = new Quaterniond().rotateZ(Math.toRadians(-90.0D));

        SphericalJoint joint = new SphericalJoint(
                anchor, new Vector3d(0.0D, -0.375D, 0.0D), down,
                hanging, new Vector3d(0.0D, 0.375D, 0.0D), down);
        joint.limits(60.0D, -30.0D, 30.0D);
        world.constraints.add(joint);

        // Kick it sideways so the joint is actually loaded, not just hanging straight.
        hanging.linVel.set(6.0D, 0.0D, 2.0D);

        double peakGap = 0.0D;
        double steadyGap = 0.0D;
        for (int i = 0; i < 400; i++) {
            world.step(TICK);
            Vector3d a = anchor.toWorld(new Vector3d(0.0D, -0.375D, 0.0D), new Vector3d());
            Vector3d b = hanging.toWorld(new Vector3d(0.0D, 0.375D, 0.0D), new Vector3d());
            double gap = a.distance(b);

            peakGap = Math.max(peakGap, gap);
            if (i >= 20) {
                steadyGap = Math.max(steadyGap, gap);
            }
        }

        assertFinite(hanging);

        // Steady state is what decides whether limbs look attached. Measured 0.0011 blocks,
        // which is under a fiftieth of a pixel; this bar is a regression guard on that,
        // not a visual requirement.
        assertTrue(steadyGap < 2.0e-3D, "joint drifted to " + steadyGap + " blocks in steady state");

        // The position solve is linearised, so the tick that first loads the joint against
        // its swing limit overshoots briefly. Measured peak is ~0.01 blocks. A Minecraft
        // pixel is 1/16 of a block, so the bar is that even the spike stays sub-pixel.
        assertTrue(peakGap < 1.0D / 16.0D, "joint separated visibly, peak was " + peakGap + " blocks");
    }

    @Test
    void swingLimitKeepsTheBoneInsideItsCone() {
        PhysicsWorld world = new PhysicsWorld();

        RigidBody anchor = new RigidBody(new Vector3d(0.25D, 0.375D, 0.125D), 0.0D);
        anchor.pos.set(0.0D, 4.0D, 0.0D);
        world.bodies.add(anchor);

        RigidBody limb = new RigidBody(new Vector3d(0.125D, 0.375D, 0.125D), 5.0D);
        limb.pos.set(0.0D, 3.25D, 0.0D);
        world.bodies.add(limb);

        Quaterniond down = new Quaterniond().rotateZ(Math.toRadians(-90.0D));

        SphericalJoint joint = new SphericalJoint(
                anchor, new Vector3d(0.0D, -0.375D, 0.0D), down,
                limb, new Vector3d(0.0D, 0.375D, 0.0D), down);
        joint.limits(45.0D, -30.0D, 30.0D);
        world.constraints.add(joint);

        // Hard enough to slam the limb against its limit and hold it there.
        limb.linVel.set(40.0D, 0.0D, 0.0D);
        limb.angVel.set(0.0D, 0.0D, -30.0D);

        double worstSwing = 0.0D;
        Vector3d boneAxis = new Vector3d(1.0D, 0.0D, 0.0D);
        for (int i = 0; i < 200; i++) {
            world.step(TICK);

            Vector3d a = new Quaterniond(anchor.rot).mul(down).transform(new Vector3d(boneAxis));
            Vector3d b = new Quaterniond(limb.rot).mul(down).transform(new Vector3d(boneAxis));
            worstSwing = Math.max(worstSwing, Math.toDegrees(a.angle(b)));
        }

        assertFinite(limb);
        // A little overshoot is expected: the limit is a soft constraint solved per substep.
        assertTrue(worstSwing < 60.0D,
                "swing reached " + worstSwing + " degrees, well past the 45 degree cone");
    }

    @Test
    void slidingBodyIsSlowedByFriction() {
        PhysicsWorld world = worldWithFloor();
        RigidBody body = box(world, 0.25D, 10.0D, 0.0D, 0.25D, 0.0D);
        body.linVel.set(6.0D, 0.0D, 0.0D);

        run(world, 200);

        assertFinite(body);
        assertTrue(body.linVel.length() < 1.0D,
                "friction should have stopped the box, speed was " + body.linVel.length());
    }

    @Test
    void bodyBuriedInTerrainIsPushedUpNotSideways() {
        // A floor built from individual blocks, which is what the real world looks like.
        PhysicsWorld world = new PhysicsWorld();
        world.collider = (query, out) -> {
            for (int x = (int) Math.floor(query.minX()); x <= (int) Math.floor(query.maxX()); x++) {
                for (int z = (int) Math.floor(query.minZ()); z <= (int) Math.floor(query.maxZ()); z++) {
                    if (query.minY() <= 0.0D && query.maxY() >= -1.0D) {
                        out.add(Aabb.ofBlock(x, -1, z));
                    }
                }
            }
        };

        // Buried, and deliberately close to a vertical boundary between two floor blocks.
        // The nearest face is that side face, so a naive least-penetration escape shoves
        // the body sideways into its neighbour instead of up into the open air.
        RigidBody body = new RigidBody(new Vector3d(0.1D, 0.1D, 0.1D), 5.0D);
        body.pos.set(0.95D, -0.4D, 0.5D);
        world.bodies.add(body);

        run(world, 100);

        assertFinite(body);
        assertTrue(body.pos.y > 0.0D, "buried body never surfaced, ended at y=" + body.pos.y);
        assertEquals(0.95D, body.pos.x, 0.25D,
                "buried body was pushed sideways to x=" + body.pos.x + " instead of straight up");
    }

    @Test
    void longRunStaysFinite() {
        PhysicsWorld world = worldWithFloor();
        List<RigidBody> parts = List.of(
                box(world, 0.25D, 8.0D, 0.0D, 6.0D, 0.0D),
                box(world, 0.2D, 6.0D, 0.3D, 7.0D, 0.1D),
                box(world, 0.15D, 4.0D, -0.2D, 8.0D, -0.1D));

        parts.forEach(b -> b.angVel.set(12.0D, 8.0D, -14.0D));

        run(world, 1000);

        parts.forEach(PhysicsCoreTest::assertFinite);
    }
}
