package dev.shrimpscript.tumble.ragdoll;

import dev.shrimpscript.tumble.physics.PhysicsWorld;
import dev.shrimpscript.tumble.physics.RigidBody;
import dev.shrimpscript.tumble.physics.SelfCollision;
import dev.shrimpscript.tumble.physics.SphericalJoint;
import dev.shrimpscript.tumble.physics.WorldCollider;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * A humanoid ragdoll: six bodies and five joints, assembled standing and then let go.
 *
 * <p>The solver knows nothing about Minecraft. The caller supplies a
 * {@link WorldCollider} and reads the resulting transforms back out. Geometry comes from
 * {@link HumanoidGeometry}, so the assembly lines up with the model that will be drawn on
 * it rather than with hand-written offsets.
 */
public final class RagdollSkeleton {

    /** Bone pointing along world +Y, expressed as a joint frame whose local X is the bone. */
    private static final Quaterniond BONE_UP = new Quaterniond().rotateZ(Math.toRadians(90.0D));
    /** Bone pointing along world -Y. */
    private static final Quaterniond BONE_DOWN = new Quaterniond().rotateZ(Math.toRadians(-90.0D));

    private final Map<BodyPart, RigidBody> parts = new EnumMap<>(BodyPart.class);
    private final PhysicsWorld world = new PhysicsWorld();

    private SelfCollision selfCollisionConstraint;

    /**
     * Converts a Minecraft entity yaw into the body orientation the renderer expects.
     *
     * <p>The half turn is not arbitrary. Vanilla draws a humanoid as
     * {@code rotateY(180 - yaw)} followed by {@code scale(-1, -1, 1)}, and this renderer
     * reuses those same model parts, so the body's own rotation has to carry that same
     * half turn. Without it every ragdoll spawns facing backwards.
     */
    public static double facingFromYaw(double yawDegrees) {
        return Math.toRadians(180.0D - yawDegrees);
    }

    private final LimbPose pose;

    /**
     * @param feetX  world position of the figure's feet
     * @param facing body orientation in radians, from {@link #facingFromYaw}
     */
    public RagdollSkeleton(double feetX, double feetY, double feetZ, double facing) {
        this(feetX, feetY, feetZ, facing, true, LimbPose.STANDING);
    }

    public RagdollSkeleton(double feetX, double feetY, double feetZ, double facing, boolean selfCollision) {
        this(feetX, feetY, feetZ, facing, selfCollision, LimbPose.STANDING);
    }

    /**
     * @param pose the pose the player's model was in, so the body starts exactly where
     *             they were drawn instead of snapping to a neutral stance
     */
    public RagdollSkeleton(double feetX, double feetY, double feetZ, double facing,
                           boolean selfCollision, LimbPose pose) {
        this.pose = pose;
        Quaterniond orientation = new Quaterniond().rotateY(facing);

        for (BodyPart part : BodyPart.values()) {
            RigidBody body = new RigidBody(
                    new Vector3d(part.halfWidth, part.halfHeight, part.halfDepth), part.mass);

            Vector3d offset = HumanoidGeometry.restOffset(part, pose, new Vector3d());
            orientation.transform(offset);
            body.pos.set(feetX + offset.x, feetY + offset.y, feetZ + offset.z);

            // Body yaw, then the part's own animated rotation.
            body.rot.set(orientation).mul(
                    HumanoidGeometry.bodyRotation(part, pose, new Quaterniond()));

            // Linear damping is kept very light: it is air drag, not brakes. At 0.999 per
            // substep a launched body lost a third of its speed within a second, so an
            // explosion sagged instead of throwing anyone. Angular damping stays higher,
            // because unchecked spin is what makes a settled pile shiver.
            body.linearDamping = 0.9998D;
            body.angularDamping = 0.995D;

            parts.put(part, body);
            world.bodies.add(body);
        }

        buildJoints();

        if (selfCollision) {
            buildSelfCollision();
        }
    }

    /**
     * Registers every pair of parts that is not joined by a joint. The torso is joined to
     * all five other parts, so it never appears here.
     */
    private void buildSelfCollision() {
        SelfCollision collision = new SelfCollision();

        BodyPart[] limbs = {BodyPart.HEAD, BodyPart.ARM_LEFT, BodyPart.ARM_RIGHT,
                BodyPart.LEG_LEFT, BodyPart.LEG_RIGHT};

        for (int i = 0; i < limbs.length; i++) {
            for (int j = i + 1; j < limbs.length; j++) {
                BodyPart first = limbs[i];
                BodyPart second = limbs[j];
                collision.addPair(
                        parts.get(first), spheresFor(first), sphereRadius(first),
                        parts.get(second), spheresFor(second), sphereRadius(second));
            }
        }

        // Stiffer than the default: a soft push lets a limb sink into another under the
        // weight of a settling body, which is most of what "the limbs phase together"
        // looks like in practice.
        collision.compliance = 0.00005D;

        world.constraints.add(collision);
        selfCollisionConstraint = collision;
    }

    /**
     * Deepest overlap between parts that are not joined, in blocks. Always zero when self
     * collision is disabled, since nothing is tracked.
     */
    public double worstSelfOverlap() {
        return selfCollisionConstraint == null ? 0.0D : selfCollisionConstraint.worstOverlap();
    }

    /**
     * Sphere centres along a part's long axis, in body space.
     *
     * <p>Three per limb, reaching the ends. Two spheres at half height left the outer
     * 12% of each limb - the hands and the feet - with no collision volume at all, which
     * is why limbs visibly passed through each other: the parts that swing furthest and
     * read most clearly were exactly the parts not being tested.
     */
    private static List<Vector3d> spheresFor(BodyPart part) {
        if (part == BodyPart.HEAD) {
            return List.of(new Vector3d(0.0D, 0.0D, 0.0D));
        }
        double reach = part.halfHeight - sphereRadius(part);
        return List.of(
                new Vector3d(0.0D, reach, 0.0D),
                new Vector3d(0.0D, 0.0D, 0.0D),
                new Vector3d(0.0D, -reach, 0.0D));
    }

    /**
     * Just under half the gap between the two legs, deliberately.
     *
     * <p>Vanilla's legs are 0.25 wide but their pivots are only 0.2375 apart, so at the
     * full inscribed radius the two limbs the model puts side by side would be in
     * permanent conflict: the solver pushes every tick, never resolves, and the settled
     * body carries a visible overlap. Sizing to the gap instead is the largest radius
     * that still lets adjacent limbs rest against each other.
     */
    private static double sphereRadius(BodyPart part) {
        if (part == BodyPart.HEAD) {
            return 0.22D;
        }
        return 0.115D;
    }

    private void buildJoints() {
        // Each limb hangs off a real point on the model: the neck, a shoulder, a hip.
        // Anchors are derived from that point in both parts' own frames, so they stay
        // correct whatever pose the ragdoll started in.
        joinAt(BodyPart.HEAD, BONE_UP, 50.0D, -70.0D, 70.0D);
        joinAt(BodyPart.ARM_RIGHT, BONE_DOWN, 100.0D, -45.0D, 45.0D);
        joinAt(BodyPart.ARM_LEFT, BONE_DOWN, 100.0D, -45.0D, 45.0D);
        joinAt(BodyPart.LEG_RIGHT, BONE_DOWN, 70.0D, -25.0D, 25.0D);
        joinAt(BodyPart.LEG_LEFT, BONE_DOWN, 70.0D, -25.0D, 25.0D);
    }

    private void joinAt(BodyPart limb, Quaterniond boneFrame,
                        double swingDegrees, double minTwistDegrees, double maxTwistDegrees) {
        Vector3d attachment = HumanoidGeometry.attachment(limb);

        join(parts.get(BodyPart.TORSO),
                HumanoidGeometry.anchorIn(BodyPart.TORSO, attachment, pose, new Vector3d()), boneFrame,
                parts.get(limb),
                HumanoidGeometry.anchorIn(limb, attachment, pose, new Vector3d()), boneFrame,
                swingDegrees, minTwistDegrees, maxTwistDegrees);
    }

    private void join(RigidBody a, Vector3d anchorA, Quaterniond frameA,
                      RigidBody b, Vector3d anchorB, Quaterniond frameB,
                      double swingDegrees, double minTwistDegrees, double maxTwistDegrees) {
        SphericalJoint joint = new SphericalJoint(a, anchorA, frameA, b, anchorB, frameB);
        joint.limits(swingDegrees, minTwistDegrees, maxTwistDegrees);
        world.constraints.add(joint);
    }

    public PhysicsWorld world() {
        return world;
    }

    public RigidBody part(BodyPart part) {
        return parts.get(part);
    }

    public RigidBody torso() {
        return parts.get(BodyPart.TORSO);
    }

    public void setCollider(WorldCollider collider) {
        world.collider = collider;
    }

    private double lastImpact;

    /** Advances the ragdoll by one Minecraft tick. */
    public void tick() {
        double before = world.maxBodySpeed();
        world.step(1.0D / 20.0D);
        // How much speed the world took away this tick. A hard landing or a wall shows up
        // here as a large drop; sliding to a halt does not.
        lastImpact = Math.max(0.0D, before - world.maxBodySpeed());
    }

    /** Speed lost to collisions in the last tick, in blocks per second. */
    public double lastImpact() {
        return lastImpact;
    }

    /**
     * Applies a launch impulse to every part, so the whole body is thrown rather than
     * being torn apart by a shove to one limb.
     */
    public void launch(Vector3d velocity) {
        for (RigidBody body : parts.values()) {
            body.linVel.add(velocity);
        }
    }

    /** True while any part is resting on a surface. */
    public boolean isGrounded() {
        return world.isGrounded();
    }

    /** True once every part has slowed below {@code threshold} blocks per second. */
    public boolean isSettled(double threshold) {
        return world.maxBodySpeed() < threshold;
    }

    /** Centre of mass, which is where the player entity should be kept. */
    public Vector3d centreOfMass(Vector3d dest) {
        dest.set(0.0D);
        double total = 0.0D;
        for (Map.Entry<BodyPart, RigidBody> entry : parts.entrySet()) {
            double mass = entry.getKey().mass;
            dest.fma(mass, entry.getValue().pos);
            total += mass;
        }
        return dest.div(total);
    }
}
