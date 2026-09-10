package dev.shrimpscript.tumble.ragdoll;

/**
 * The six parts of a ragdoll, one per cuboid of the vanilla player model.
 *
 * <p>Six is the right number rather than a simplification: a vanilla arm is a single
 * cuboid with no elbow, so splitting it would produce bodies with nothing to draw.
 * One body per renderable part makes rendering a direct mapping.
 *
 * <p>Sizes are the model's pixels converted to blocks at 16 pixels per block.
 */
public enum BodyPart {

    /** 8x8x8 pixels. */
    HEAD(0.25D, 0.25D, 0.25D, 5.0D),
    /** 8x12x4 pixels. */
    TORSO(0.25D, 0.375D, 0.125D, 30.0D),
    /** 4x12x4 pixels. */
    ARM_LEFT(0.125D, 0.375D, 0.125D, 4.0D),
    ARM_RIGHT(0.125D, 0.375D, 0.125D, 4.0D),
    /** 4x12x4 pixels. */
    LEG_LEFT(0.125D, 0.375D, 0.125D, 10.0D),
    LEG_RIGHT(0.125D, 0.375D, 0.125D, 10.0D);

    public final double halfWidth;
    public final double halfHeight;
    public final double halfDepth;
    public final double mass;

    BodyPart(double halfWidth, double halfHeight, double halfDepth, double mass) {
        this.halfWidth = halfWidth;
        this.halfHeight = halfHeight;
        this.halfDepth = halfDepth;
        this.mass = mass;
    }

    public boolean isArm() {
        return this == ARM_LEFT || this == ARM_RIGHT;
    }

    public boolean isLeg() {
        return this == LEG_LEFT || this == LEG_RIGHT;
    }
}
