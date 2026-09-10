package dev.shrimpscript.tumble.physics;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Shared XPBD primitives.
 *
 * <p>Implements the substepped rigid-body formulation from Müller, Macklin, Chentanez,
 * Jeschke and Kim, "Detailed Rigid Body Simulation with Extended Position Based Dynamics"
 * (2020). Both routines below are the paper's, expressed in Java.
 */
public final class XpbdMath {

    private XpbdMath() {
    }

    /**
     * Eliminates {@code corr} between two bodies, splitting the correction by generalised
     * inverse mass.
     *
     * <p>Either body may be null (treated as immovable). A null world point means the
     * correction is purely angular.
     *
     * @param compliance inverse stiffness; 0 is perfectly rigid
     * @return the Lagrange multiplier applied, which callers use as the impulse magnitude
     */
    public static double applyPairCorrection(RigidBody a, Vector3d pointA,
                                             RigidBody b, Vector3d pointB,
                                             Vector3d corr, double compliance, double h,
                                             boolean velocityLevel) {
        double c = corr.length();
        if (c < 1.0e-12D) {
            return 0.0D;
        }

        Vector3d normal = new Vector3d(corr).div(c);

        double wa = a == null ? 0.0D : a.inverseMass(normal, pointA);
        double wb = b == null ? 0.0D : b.inverseMass(normal, pointB);
        double w = wa + wb;
        if (w < 1.0e-12D) {
            return 0.0D;
        }

        double lambda = c / (w + compliance / (h * h));
        normal.mul(lambda);

        if (a != null) {
            a.applyCorrection(normal, pointA, velocityLevel);
        }
        if (b != null) {
            b.applyCorrection(normal.negate(), pointB, velocityLevel);
        }
        return lambda;
    }

    /**
     * Constrains the signed angle from {@code a} to {@code b} about axis {@code n} to
     * [{@code minAngle}, {@code maxAngle}] radians. This is the primitive behind every
     * joint limit; a swing cone and a twist range are each one call.
     *
     * @return true if the limit was violated and a correction was applied
     */
    public static boolean limitAngle(RigidBody body0, RigidBody body1,
                                     Vector3d n, Vector3d a, Vector3d b,
                                     double minAngle, double maxAngle,
                                     double compliance, double h, double maxCorrection) {
        Vector3d c = new Vector3d(a).cross(b);

        double phi = Math.asin(clamp(c.dot(n), -1.0D, 1.0D));
        if (a.dot(b) < 0.0D) {
            phi = Math.PI - phi;
        }
        if (phi > Math.PI) {
            phi -= 2.0D * Math.PI;
        }
        if (phi < -Math.PI) {
            phi += 2.0D * Math.PI;
        }

        if (phi >= minAngle && phi <= maxAngle) {
            return false;
        }

        phi = clamp(phi, minAngle, maxAngle);

        Quaterniond q = new Quaterniond().fromAxisAngleRad(n.x, n.y, n.z, phi);
        Vector3d omega = q.transform(new Vector3d(a)).cross(b);

        double len = omega.length();
        if (len > maxCorrection) {
            omega.mul(maxCorrection / len);
        }

        applyPairCorrection(body0, null, body1, null, omega, compliance, h, false);
        return true;
    }

    public static double clamp(double v, double min, double max) {
        return v < min ? min : Math.min(v, max);
    }
}
