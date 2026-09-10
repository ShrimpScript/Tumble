package dev.shrimpscript.tumble.ragdoll;

import org.joml.Quaterniond;
import org.joml.Vector3d;

/**
 * Vanilla's humanoid model geometry, and the conversion from it into world space.
 *
 * <p>Everything here is derived from the model rather than eyeballed, because eyeballing
 * it got left and right the wrong way round. Vanilla draws a humanoid as
 * {@code rotateY(180 - yaw)}, then {@code scale(-1, -1, 1)}, then a lift of 1.5 blocks.
 * That scale is not a mirror - it is a 180 degree rotation about Z - so a right arm at
 * model x = -5 belongs at world +0.3125, not -0.3125.
 *
 * <p>Because the flip is a rotation, converting a model-space rotation into a body-space
 * one is a conjugation by it, which negates the X and Y components and leaves Z alone.
 */
public final class HumanoidGeometry {

    /** Model pixels per block. */
    private static final double PIXEL = 1.0D / 16.0D;

    /** The model origin sits this far above the feet. */
    private static final double LIFT = 1.5D;

    private HumanoidGeometry() {
    }

    /** Where a part's pivot sits, in model pixels relative to the model origin. */
    public static Vector3d pivot(BodyPart part) {
        return switch (part) {
            case HEAD, TORSO -> new Vector3d(0.0D, 0.0D, 0.0D);
            case ARM_RIGHT -> new Vector3d(-5.0D, 2.0D, 0.0D);
            case ARM_LEFT -> new Vector3d(5.0D, 2.0D, 0.0D);
            case LEG_RIGHT -> new Vector3d(-1.9D, 12.0D, 0.0D);
            case LEG_LEFT -> new Vector3d(1.9D, 12.0D, 0.0D);
        };
    }

    /** Centre of a part's cuboid, in model pixels relative to that part's own pivot. */
    public static Vector3d cubeCentre(BodyPart part) {
        return switch (part) {
            case HEAD -> new Vector3d(0.0D, -4.0D, 0.0D);
            case TORSO -> new Vector3d(0.0D, 6.0D, 0.0D);
            case ARM_RIGHT -> new Vector3d(-1.0D, 4.0D, 0.0D);
            case ARM_LEFT -> new Vector3d(1.0D, 4.0D, 0.0D);
            case LEG_RIGHT, LEG_LEFT -> new Vector3d(0.0D, 6.0D, 0.0D);
        };
    }

    /**
     * The model point where a part attaches to its parent: the neck for a head, the
     * shoulder for an arm, the hip for a leg. Expressed relative to the model origin,
     * which is also the torso's pivot.
     */
    public static Vector3d attachment(BodyPart part) {
        return switch (part) {
            case HEAD -> new Vector3d(0.0D, 0.0D, 0.0D);
            case TORSO -> new Vector3d(0.0D, 0.0D, 0.0D);
            default -> pivot(part);
        };
    }

    /**
     * Converts a model-space point, in pixels relative to the model origin, into an offset
     * from the figure's feet before the body's own yaw is applied.
     */
    public static Vector3d toBodySpace(Vector3d modelPixels, Vector3d dest) {
        return dest.set(-modelPixels.x * PIXEL, -modelPixels.y * PIXEL + LIFT, modelPixels.z * PIXEL);
    }

    /**
     * Rotation of a part in body space, given its model-space rotation.
     *
     * <p>Vanilla applies model rotations as Z, then Y, then X. Conjugating that by the
     * flip negates X and Y and leaves Z, which is why the signs below are not a mistake.
     */
    public static Quaterniond toBodySpace(double xRot, double yRot, double zRot, Quaterniond dest) {
        return dest.identity().rotateZ(zRot).rotateY(-yRot).rotateX(-xRot);
    }

    /**
     * Offset from the feet to a part's cuboid centre, for the given pose, before body yaw.
     */
    public static Vector3d restOffset(BodyPart part, LimbPose pose, Vector3d dest) {
        Vector3d centre = new Vector3d(cubeCentre(part));

        // The cube swings about its own pivot, so rotate before translating.
        modelRotation(part, pose).transform(centre);
        centre.add(pivot(part)).add(pose.pivotShift(part));

        return toBodySpace(centre, dest);
    }

    /** A part's rotation in model space, from the pose. */
    public static Quaterniond modelRotation(BodyPart part, LimbPose pose) {
        return new Quaterniond()
                .rotateZ(0.0D)
                .rotateY(pose.yRot(part))
                .rotateX(pose.xRot(part));
    }

    /** A part's orientation in body space, from the pose. */
    public static Quaterniond bodyRotation(BodyPart part, LimbPose pose, Quaterniond dest) {
        return toBodySpace(pose.xRot(part), pose.yRot(part), 0.0D, dest);
    }

    /**
     * A model-space point expressed in a part's own local frame, which is what a joint
     * anchor needs. Independent of the part's rotation, since both move together.
     *
     * @param modelPoint point in model pixels, relative to the model origin
     */
    public static Vector3d anchorIn(BodyPart part, Vector3d modelPoint, LimbPose pose, Vector3d dest) {
        Vector3d relative = new Vector3d(modelPoint)
                .sub(pivot(part))
                .sub(pose.pivotShift(part))
                .sub(cubeCentre(part));

        // Undo the part's own model rotation, then flip into the part's local frame.
        modelRotation(part, pose).transformInverse(relative);
        return dest.set(-relative.x * PIXEL, -relative.y * PIXEL, relative.z * PIXEL);
    }
}
