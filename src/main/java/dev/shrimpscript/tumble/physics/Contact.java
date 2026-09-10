package dev.shrimpscript.tumble.physics;

import org.joml.Vector3d;

/**
 * One sample point on a body resting against one solid world box.
 *
 * <p>The normal is chosen once, when the contact is generated, and then held for the
 * whole step. Re-picking the least-penetrating axis every substep makes a body sitting
 * on an edge flip between normals and buzz.
 */
public final class Contact {

    /** Blocks per second at which pre-existing overlap is pushed out. */
    private static final double RECOVERY_SPEED = 2.0D;

    public final RigidBody body;
    private final Vector3d localPoint = new Vector3d();
    private final Aabb box;
    private final Vector3d normal = new Vector3d();

    public double staticFriction = 0.9D;
    public double dynamicFriction = 0.8D;
    /** Flesh does not bounce. Kept configurable for tuning. */
    public double restitution = 0.0D;

    private final Vector3d worldPoint = new Vector3d();
    private final Vector3d prevWorldPoint = new Vector3d();
    private final Vector3d scratch = new Vector3d();

    private double normalLambda;
    private double normalVelocityBefore;
    private boolean active;

    Contact(RigidBody body, Vector3d localPoint, Aabb box, Vector3d normal) {
        this.body = body;
        this.localPoint.set(localPoint);
        this.box = box;
        this.normal.set(normal);
    }

    /** Depth of {@code p} inside {@code box} measured along {@code normal}. Negative when outside. */
    private static double depthAlong(Aabb box, Vector3d p, Vector3d n) {
        if (p.x < box.minX() || p.x > box.maxX()
                || p.y < box.minY() || p.y > box.maxY()
                || p.z < box.minZ() || p.z > box.maxZ()) {
            return -1.0D;
        }
        if (n.x > 0.5D) {
            return box.maxX() - p.x;
        }
        if (n.x < -0.5D) {
            return p.x - box.minX();
        }
        if (n.y > 0.5D) {
            return box.maxY() - p.y;
        }
        if (n.y < -0.5D) {
            return p.y - box.minY();
        }
        if (n.z > 0.5D) {
            return box.maxZ() - p.z;
        }
        return p.z - box.minZ();
    }

    void beginSubstep() {
        normalLambda = 0.0D;
        active = false;

        // Point velocity before the position solve, for restitution.
        body.toWorld(localPoint, worldPoint);
        normalVelocityBefore = body.pointVelocity(worldPoint, scratch).dot(normal);

        // Where this material point was at the start of the substep, for static friction.
        prevWorldPoint.set(localPoint);
        body.prevRot.transform(prevWorldPoint).add(body.prevPos);
    }

    void solvePosition(double h) {
        body.toWorld(localPoint, worldPoint);

        double depth = depthAlong(box, worldPoint, normal);
        if (depth <= 0.0D) {
            return;
        }
        active = true;

        // Cap how much overlap is undone in one substep.
        //
        // Undoing a deep overlap all at once turns into velocity: the position solve
        // derives speed from the distance moved, so ejecting a limb 0.4 blocks in a 3 ms
        // substep hands it 128 blocks/s and fires it across the map. That happens for real
        // whenever a body is driven into terrain or spawned inside a block.
        //
        // The budget is whatever the point travelled into the surface this substep - which
        // keeps genuine impacts fully resolved, so nothing tunnels - plus a bounded
        // recovery rate for overlap that was already there.
        double approach = Math.max(0.0D, -normalVelocityBefore) * h;
        double allowed = approach + RECOVERY_SPEED * h;
        double corrected = Math.min(depth, allowed);

        Vector3d corr = new Vector3d(normal).mul(corrected);
        normalLambda += XpbdMath.applyPairCorrection(body, worldPoint, null, null, corr, 0.0D, h, false);

        // Static friction: undo tangential drift of this material point since the substep
        // began. This is what actually stops a settled body from sliding downhill.
        body.toWorld(localPoint, worldPoint);
        Vector3d slide = new Vector3d(worldPoint).sub(prevWorldPoint);
        slide.fma(-slide.dot(normal), normal);

        double slideLength = slide.length();
        if (slideLength < 1.0e-9D) {
            return;
        }

        double limit = staticFriction * corrected;
        if (slideLength > limit) {
            slide.mul(limit / slideLength);
        }
        XpbdMath.applyPairCorrection(body, worldPoint, null, null, slide.negate(), 0.0D, h, false);
    }

    /** True when this contact is carrying weight from below, rather than a wall or ceiling. */
    boolean isSupporting() {
        return active && normal.y > 0.5D;
    }

    void solveVelocity(double h) {
        if (!active) {
            return;
        }

        body.toWorld(localPoint, worldPoint);
        Vector3d v = body.pointVelocity(worldPoint, new Vector3d());

        double vn = v.dot(normal);
        Vector3d vt = new Vector3d(v).fma(-vn, normal);
        double vtLength = vt.length();

        Vector3d dv = new Vector3d();

        // Dynamic friction, bounded by the Coulomb cone against the normal impulse.
        if (vtLength > 1.0e-9D) {
            double maxFriction = dynamicFriction * normalLambda / h;
            dv.set(vt).mul(-Math.min(maxFriction, vtLength) / vtLength);
        }

        // Restitution. Suppressed for slow impacts, or resting bodies jitter forever.
        double e = Math.abs(normalVelocityBefore) < 2.0D ? 0.0D : restitution;
        dv.fma(-vn + Math.max(-e * normalVelocityBefore, 0.0D), normal);

        XpbdMath.applyPairCorrection(body, worldPoint, null, null, dv, 0.0D, h, true);
    }
}
