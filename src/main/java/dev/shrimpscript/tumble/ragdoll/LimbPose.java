package dev.shrimpscript.tumble.ragdoll;

import net.minecraft.util.Mth;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Vector3d;

/**
 * The pose a player's model was actually in at the moment they went down.
 *
 * <p>A ragdoll that always starts in a neutral standing pose looks like a different
 * person appearing: mid-sprint the legs are apart and the arms are swung, and snapping
 * those to zero is a visible pop. These are vanilla's own humanoid animation terms,
 * recomputed from data the server already has, so the body starts exactly where the
 * player was drawn.
 *
 * <p>All angles are in radians, in model space.
 */
public record LimbPose(double headYaw, double headPitch,
                       double rightArmPitch, double leftArmPitch,
                       double rightLegPitch, double leftLegPitch,
                       double bodyPitch, boolean crouching) {

    public static final LimbPose STANDING =
            new LimbPose(0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, 0.0D, false);

    /**
     * Reproduces the parts of {@code HumanoidModel.setupAnim} that visibly change a
     * standing player: where they are looking, the walk cycle, and crouching.
     */
    public static LimbPose of(LivingEntity entity) {
        // The walk cycle vanilla drives the limbs with.
        float limbSwing = entity.walkAnimation.position();
        float limbSwingAmount = entity.walkAnimation.speed();

        double rightArm = Math.cos(limbSwing * 0.6662D + Math.PI) * 2.0D * limbSwingAmount * 0.5D;
        double leftArm = Math.cos(limbSwing * 0.6662D) * 2.0D * limbSwingAmount * 0.5D;
        double rightLeg = Math.cos(limbSwing * 0.6662D) * 1.4D * limbSwingAmount;
        double leftLeg = Math.cos(limbSwing * 0.6662D + Math.PI) * 1.4D * limbSwingAmount;

        boolean crouching = entity.isCrouching();
        double bodyPitch = 0.0D;
        if (crouching) {
            bodyPitch = 0.5D;
            rightArm += 0.4D;
            leftArm += 0.4D;
        }

        double headYaw = Math.toRadians(Mth.wrapDegrees(entity.getYHeadRot() - entity.yBodyRot));
        double headPitch = Math.toRadians(entity.getXRot());

        return new LimbPose(headYaw, headPitch, rightArm, leftArm, rightLeg, leftLeg,
                bodyPitch, crouching);
    }

    /** Model-space X rotation for a part. */
    public double xRot(BodyPart part) {
        return switch (part) {
            case HEAD -> headPitch;
            case TORSO -> bodyPitch;
            case ARM_RIGHT -> rightArmPitch;
            case ARM_LEFT -> leftArmPitch;
            case LEG_RIGHT -> rightLegPitch;
            case LEG_LEFT -> leftLegPitch;
        };
    }

    /** Model-space Y rotation for a part. Only the head turns. */
    public double yRot(BodyPart part) {
        return part == BodyPart.HEAD ? headYaw : 0.0D;
    }

    /**
     * Crouching does not only rotate: vanilla also slides the head, body and legs, which
     * is why a sneaking player is shorter rather than merely tilted.
     */
    public Vector3d pivotShift(BodyPart part) {
        if (!crouching) {
            return new Vector3d();
        }
        return switch (part) {
            case HEAD -> new Vector3d(0.0D, 4.2D, 0.0D);
            case TORSO -> new Vector3d(0.0D, 3.2D, 0.0D);
            case ARM_RIGHT, ARM_LEFT -> new Vector3d(0.0D, 3.2D, 0.0D);
            case LEG_RIGHT, LEG_LEFT -> new Vector3d(0.0D, 0.2D, 4.0D);
        };
    }
}
