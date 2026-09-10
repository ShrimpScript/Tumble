package dev.shrimpscript.tumble.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * A ball-and-socket joint with a swing cone and a twist range.
 *
 * <p>Each body carries a joint frame whose local X axis is the bone direction and whose
 * local Y axis is the twist reference. Swing is the angle between the two bone axes;
 * twist is rotation about them.
 *
 * <p>The limits are what separate a ragdoll from a bag of bricks - an unlimited spherical
 * joint lets a knee bend backwards and an elbow spin freely.
 */
public final class SphericalJoint implements Constraint {

    private static final Vector3d BONE_AXIS = new Vector3d(1.0D, 0.0D, 0.0D);
    private static final Vector3d TWIST_REF = new Vector3d(0.0D, 1.0D, 0.0D);

    public final RigidBody bodyA;
    public final RigidBody bodyB;

    private final Vector3d localAnchorA = new Vector3d();
    private final Quaterniond localFrameA = new Quaterniond();
    private final Vector3d localAnchorB = new Vector3d();
    private final Quaterniond localFrameB = new Quaterniond();

    /** Half-angle of the swing cone, in radians. */
    public double swingLimit = Math.toRadians(60.0D);
    public double minTwist = Math.toRadians(-30.0D);
    public double maxTwist = Math.toRadians(30.0D);

    /** Inverse stiffness. 0 is a rigid attachment. */
    public double compliance = 0.0D;

    private final Vector3d worldAnchorA = new Vector3d();
    private final Vector3d worldAnchorB = new Vector3d();
    private final Quaterniond worldFrameA = new Quaterniond();
    private final Quaterniond worldFrameB = new Quaterniond();

    public SphericalJoint(RigidBody bodyA, Vector3d localAnchorA, Quaterniond localFrameA,
                          RigidBody bodyB, Vector3d localAnchorB, Quaterniond localFrameB) {
        this.bodyA = bodyA;
        this.bodyB = bodyB;
        this.localAnchorA.set(localAnchorA);
        this.localFrameA.set(localFrameA);
        this.localAnchorB.set(localAnchorB);
        this.localFrameB.set(localFrameB);
    }

    public SphericalJoint limits(double swingDegrees, double minTwistDegrees, double maxTwistDegrees) {
        this.swingLimit = Math.toRadians(swingDegrees);
        this.minTwist = Math.toRadians(minTwistDegrees);
        this.maxTwist = Math.toRadians(maxTwistDegrees);
        return this;
    }

    @Override
    public void solvePosition(double h) {
        refreshFrames();
        solveSwing(h);
        solveTwist(h);

        // The angle limits rotate each body about its own centre of mass, which drags the
        // anchor points apart. Re-solving the attachment against the post-limit frames is
        // what keeps limbs looking joined; solving it only once leaves a visible gap
        // whenever a limit is loaded.
        refreshFrames();
        solveAttachment(h);
    }

    private void refreshFrames() {
        bodyA.toWorld(localAnchorA, worldAnchorA);
        bodyB.toWorld(localAnchorB, worldAnchorB);
        worldFrameA.set(bodyA.rot).mul(localFrameA);
        worldFrameB.set(bodyB.rot).mul(localFrameB);
    }

    private void solveAttachment(double h) {
        Vector3d corr = new Vector3d(worldAnchorB).sub(worldAnchorA);
        XpbdMath.applyPairCorrection(bodyA, worldAnchorA, bodyB, worldAnchorB, corr, compliance, h, false);
    }

    private void solveSwing(double h) {
        Vector3d axisA = worldFrameA.transform(new Vector3d(BONE_AXIS));
        Vector3d axisB = worldFrameB.transform(new Vector3d(BONE_AXIS));

        Vector3d n = new Vector3d(axisA).cross(axisB);
        double len = n.length();
        if (len < 1.0e-9D) {
            // Bones are collinear, so there is no swing to correct and no valid axis.
            return;
        }
        n.div(len);

        XpbdMath.limitAngle(bodyA, bodyB, n, axisA, axisB, -swingLimit, swingLimit,
                compliance, h, Math.PI);
    }

    private void solveTwist(double h) {
        Vector3d axisA = worldFrameA.transform(new Vector3d(BONE_AXIS));
        Vector3d axisB = worldFrameB.transform(new Vector3d(BONE_AXIS));

        Vector3d n = new Vector3d(axisA).add(axisB);
        double len = n.length();
        if (len < 1.0e-9D) {
            return;
        }
        n.div(len);

        // Project both twist references onto the plane perpendicular to the shared axis.
        Vector3d refA = worldFrameA.transform(new Vector3d(TWIST_REF));
        refA.fma(-refA.dot(n), n);
        Vector3d refB = worldFrameB.transform(new Vector3d(TWIST_REF));
        refB.fma(-refB.dot(n), n);

        if (refA.lengthSquared() < 1.0e-12D || refB.lengthSquared() < 1.0e-12D) {
            return;
        }
        refA.normalize();
        refB.normalize();

        XpbdMath.limitAngle(bodyA, bodyB, n, refA, refB, minTwist, maxTwist,
                compliance, h, Math.PI);
    }
}
