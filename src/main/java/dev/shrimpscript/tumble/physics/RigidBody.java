package dev.shrimpscript.tumble.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * A box-shaped rigid body integrated with substepped XPBD.
 *
 * <p>Units are Minecraft's: positions in blocks, velocities in blocks per second.
 *
 * <p>Deliberately free of any Minecraft dependency so the solver can be tested headlessly.
 */
public final class RigidBody {

    /** Corrections larger than this many radians in one substep are clamped, to keep the solver stable. */
    private static final double MAX_ROTATION_PER_CORRECTION = 0.5D;

    public final Vector3d pos = new Vector3d();
    public final Quaterniond rot = new Quaterniond();

    public final Vector3d linVel = new Vector3d();
    public final Vector3d angVel = new Vector3d();

    /** State at the start of the current substep, used to derive velocity after the position solve. */
    final Vector3d prevPos = new Vector3d();
    final Quaterniond prevRot = new Quaterniond();

    /** Half-extents of the box, in blocks. */
    public final Vector3d halfExtents = new Vector3d();

    public double invMass;
    /** Inverse of the diagonal inertia tensor, in body space. */
    public final Vector3d invInertia = new Vector3d();

    /** Multiplied into velocity each substep. 1.0 is no damping. */
    public double linearDamping = 1.0D;
    public double angularDamping = 1.0D;

    private final Vector3d scratch = new Vector3d();

    public RigidBody(Vector3d halfExtents, double mass) {
        this.halfExtents.set(halfExtents);
        setMass(mass);
    }

    /** Sets mass and the box inertia tensor derived from the current half-extents. */
    public void setMass(double mass) {
        if (mass <= 0.0D) {
            invMass = 0.0D;
            invInertia.set(0.0D);
            return;
        }
        invMass = 1.0D / mass;

        double w = halfExtents.x * 2.0D;
        double h = halfExtents.y * 2.0D;
        double d = halfExtents.z * 2.0D;

        double ix = mass * (h * h + d * d) / 12.0D;
        double iy = mass * (w * w + d * d) / 12.0D;
        double iz = mass * (w * w + h * h) / 12.0D;

        invInertia.set(1.0D / ix, 1.0D / iy, 1.0D / iz);
    }

    public boolean isStatic() {
        return invMass == 0.0D;
    }

    /** Advances position and orientation by one substep of {@code h} seconds. */
    void integrate(double h, Vector3d gravity) {
        if (isStatic()) {
            prevPos.set(pos);
            prevRot.set(rot);
            return;
        }

        prevPos.set(pos);
        prevRot.set(rot);

        linVel.fma(h, gravity);
        linVel.mul(linearDamping);
        pos.fma(h, linVel);

        angVel.mul(angularDamping);
        applyAngularVelocity(h);
    }

    /** Integrates the orientation: q += (h/2) * omega_quat * q, then renormalises. */
    private void applyAngularVelocity(double h) {
        double wx = angVel.x;
        double wy = angVel.y;
        double wz = angVel.z;

        double qx = rot.x;
        double qy = rot.y;
        double qz = rot.z;
        double qw = rot.w;

        // (wx, wy, wz, 0) * q
        double dx = wx * qw + wy * qz - wz * qy;
        double dy = -wx * qz + wy * qw + wz * qx;
        double dz = wx * qy - wy * qx + wz * qw;
        double dw = -(wx * qx + wy * qy + wz * qz);

        double s = h * 0.5D;
        rot.set(qx + s * dx, qy + s * dy, qz + s * dz, qw + s * dw);
        rot.normalize();
    }

    /** Recovers velocities from the position change made by the constraint solve. */
    void deriveVelocity(double h) {
        if (isStatic()) {
            return;
        }

        double inv = 1.0D / h;
        linVel.set(pos).sub(prevPos).mul(inv);

        // dq = rot * prevRot^-1, the world-frame rotation applied this substep.
        double px = -prevRot.x;
        double py = -prevRot.y;
        double pz = -prevRot.z;
        double pw = prevRot.w;

        double dx = rot.w * px + rot.x * pw + rot.y * pz - rot.z * py;
        double dy = rot.w * py - rot.x * pz + rot.y * pw + rot.z * px;
        double dz = rot.w * pz + rot.x * py - rot.y * px + rot.z * pw;
        double dw = rot.w * pw - rot.x * px - rot.y * py - rot.z * pz;

        double scale = 2.0D * inv;
        angVel.set(dx * scale, dy * scale, dz * scale);
        if (dw < 0.0D) {
            angVel.negate();
        }
    }

    /**
     * Generalised inverse mass along {@code normal}.
     *
     * @param worldPoint world-space point of application, or null for a purely angular constraint
     */
    public double inverseMass(Vector3d normal, Vector3d worldPoint) {
        if (worldPoint == null) {
            scratch.set(normal);
        } else {
            scratch.set(worldPoint).sub(pos).cross(normal);
        }

        rot.transformInverse(scratch);

        double w = scratch.x * scratch.x * invInertia.x
                + scratch.y * scratch.y * invInertia.y
                + scratch.z * scratch.z * invInertia.z;

        return worldPoint == null ? w : w + invMass;
    }

    /**
     * Applies a positional (or velocity-level) correction.
     *
     * @param worldPoint world-space point of application, or null for a purely angular correction
     */
    public void applyCorrection(Vector3d corr, Vector3d worldPoint, boolean velocityLevel) {
        if (isStatic()) {
            return;
        }

        Vector3d torqueAxis = new Vector3d();
        if (worldPoint == null) {
            torqueAxis.set(corr);
        } else {
            if (velocityLevel) {
                linVel.fma(invMass, corr);
            } else {
                pos.fma(invMass, corr);
            }
            torqueAxis.set(worldPoint).sub(pos).cross(corr);
        }

        // Convert the torque axis through the inverse inertia tensor, in body space.
        rot.transformInverse(torqueAxis);
        torqueAxis.mul(invInertia);
        rot.transform(torqueAxis);

        if (velocityLevel) {
            angVel.add(torqueAxis);
        } else {
            applyRotationCorrection(torqueAxis);
        }
    }

    private void applyRotationCorrection(Vector3d axis) {
        double scale = 1.0D;
        double phi = axis.length();
        if (phi * scale > MAX_ROTATION_PER_CORRECTION) {
            scale = MAX_ROTATION_PER_CORRECTION / phi;
        }

        double ax = axis.x * scale;
        double ay = axis.y * scale;
        double az = axis.z * scale;

        double qx = rot.x;
        double qy = rot.y;
        double qz = rot.z;
        double qw = rot.w;

        double dx = ax * qw + ay * qz - az * qy;
        double dy = -ax * qz + ay * qw + az * qx;
        double dz = ax * qy - ay * qx + az * qw;
        double dw = -(ax * qx + ay * qy + az * qz);

        rot.set(qx + 0.5D * dx, qy + 0.5D * dy, qz + 0.5D * dz, qw + 0.5D * dw);
        rot.normalize();
    }

    /** Transforms a body-space point into world space. */
    public Vector3d toWorld(Vector3d local, Vector3d dest) {
        return rot.transform(dest.set(local)).add(pos);
    }

    /** Rotates a body-space direction into world space. */
    public Vector3d dirToWorld(Vector3d local, Vector3d dest) {
        return rot.transform(dest.set(local));
    }

    /** Velocity of the world-space point {@code worldPoint} on this body. */
    public Vector3d pointVelocity(Vector3d worldPoint, Vector3d dest) {
        dest.set(worldPoint).sub(pos).cross(angVel).negate().add(linVel);
        return dest;
    }
}
