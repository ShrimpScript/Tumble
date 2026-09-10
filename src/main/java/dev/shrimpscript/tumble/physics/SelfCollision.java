package dev.shrimpscript.tumble.physics;

import org.joml.Vector3d;

import java.util.ArrayList;
import java.util.List;

/**
 * Keeps parts of one assembly from passing through each other.
 *
 * <p>Limbs are approximated by spheres strung along their long axis rather than tested as
 * oriented boxes. That is the standard ragdoll trade: a box-box manifold is far more code
 * and more ways to jitter, while nobody can tell the difference on a body that is mostly
 * limbs. Two spheres per limb is enough to stop an arm lying inside a leg.
 *
 * <p>Only non-adjacent parts are registered. Parts joined by a joint are <em>supposed</em>
 * to overlap at the shoulder or hip, and pushing them apart would fight the joint and
 * inflate the body.
 */
public final class SelfCollision implements Constraint {

    private record Sphere(RigidBody body, Vector3d centre, double radius) {
    }

    private final List<Sphere> a = new ArrayList<>();
    private final List<Sphere> b = new ArrayList<>();

    private final Vector3d pa = new Vector3d();
    private final Vector3d pb = new Vector3d();
    private final Vector3d delta = new Vector3d();

    /** Compliance keeps the push soft, so a settling pile eases apart instead of popping. */
    public double compliance = 0.0002D;

    /** Registers every sphere of one part against every sphere of another. */
    public void addPair(RigidBody first, List<Vector3d> firstCentres, double firstRadius,
                        RigidBody second, List<Vector3d> secondCentres, double secondRadius) {
        for (Vector3d one : firstCentres) {
            for (Vector3d two : secondCentres) {
                a.add(new Sphere(first, new Vector3d(one), firstRadius));
                b.add(new Sphere(second, new Vector3d(two), secondRadius));
            }
        }
    }

    public int pairCount() {
        return a.size();
    }

    /** Deepest current overlap across all registered pairs, in blocks. Zero when clear. */
    public double worstOverlap() {
        double worst = 0.0D;
        for (int i = 0; i < a.size(); i++) {
            Sphere first = a.get(i);
            Sphere second = b.get(i);

            first.body.toWorld(first.centre, pa);
            second.body.toWorld(second.centre, pb);

            double overlap = (first.radius + second.radius) - pa.distance(pb);
            worst = Math.max(worst, overlap);
        }
        return worst;
    }

    @Override
    public void solvePosition(double h) {
        for (int i = 0; i < a.size(); i++) {
            Sphere first = a.get(i);
            Sphere second = b.get(i);

            first.body.toWorld(first.centre, pa);
            second.body.toWorld(second.centre, pb);

            delta.set(pb).sub(pa);
            double distance = delta.length();
            double minimum = first.radius + second.radius;

            if (distance >= minimum || distance < 1.0e-9D) {
                continue;
            }

            // Move the first sphere away from the second, and vice versa.
            delta.div(distance).mul(-(minimum - distance));
            XpbdMath.applyPairCorrection(first.body, pa, second.body, pb, delta, compliance, h, false);
        }
    }
}
