package dev.shrimpscript.tumble.client;

import dev.shrimpscript.tumble.config.TumbleConfig;
import dev.shrimpscript.tumble.entity.RagdollEntity;
import dev.shrimpscript.tumble.ragdoll.BodyPart;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.ViewportEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Puts the first-person camera inside the ragdoll's head.
 *
 * <p>Without this, going limp leaves the view hovering at standing eye height above the
 * body: the world stays level while the body tumbles underneath it, which reads as
 * watching your ragdoll rather than being it.
 *
 * <p>Minecraft's camera rotation is built as yaw about Y, then pitch about X, then roll
 * about Z, which is exactly a YXZ Euler decomposition of the head's orientation - so the
 * head quaternion converts straight into the three angles the event accepts. Position
 * needs an access transformer, because the event exposes angles but not position.
 *
 * <p>The head is a small, light body at the end of a chain, so bolting the camera rigidly
 * to it transmits every twitch of the solve. Position and orientation are therefore eased
 * toward the head rather than snapped to it, with the easing expressed as a rate per
 * second so the result does not change with framerate.
 */
public final class RagdollCamera {

    private static final int HEAD = BodyPart.HEAD.ordinal();

    /** Easing rates, per second, at the extremes of the smoothing setting. */
    private static final double RATE_RAW = 60.0D;
    private static final double RATE_SMOOTH = 7.0D;

    /** Position keeps up more tightly than orientation; drifting off the body reads worse. */
    private static final double POSITION_RATE_SCALE = 1.8D;

    /** Guards against a huge easing step after a stall or a loading pause. */
    private static final double MAX_FRAME_SECONDS = 0.1D;

    private static final Vec3 UNSET = new Vec3(Double.NaN, 0.0D, 0.0D);

    private static Vec3 smoothedPosition = UNSET;
    private static final Quaternionf smoothedRotation = new Quaternionf();
    private static int smoothedFor = -1;
    private static long lastFrameNanos;

    private RagdollCamera() {
    }

    /** True while the local player is seeing through their own ragdoll's head. */
    public static boolean isActive() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || !minecraft.options.getCameraType().isFirstPerson()) {
            return false;
        }
        if (!TumbleConfig.CLIENT.firstPersonRagdollCamera.get()) {
            return false;
        }
        return minecraft.player.getVehicle() instanceof RagdollEntity ragdoll && ragdoll.hasPose();
    }

    public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
        if (!isActive()) {
            reset();
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        RagdollEntity ragdoll = (RagdollEntity) minecraft.player.getVehicle();

        ragdoll.samplePose((float) event.getPartialTick());

        Vec3 target = ragdoll.partWorldPosition(HEAD);
        Quaternionf headRotation = ragdoll.partRotation(HEAD, new Quaternionf());

        // Snap on the first frame of a new ragdoll, so the camera does not sweep in from
        // wherever the last one ended.
        if (smoothedFor != ragdoll.getId() || Double.isNaN(smoothedPosition.x)) {
            smoothedFor = ragdoll.getId();
            smoothedPosition = target;
            smoothedRotation.set(headRotation);
            lastFrameNanos = System.nanoTime();
        } else {
            double seconds = frameSeconds();
            double rate = easingRate();

            double positionAlpha = alpha(rate * POSITION_RATE_SCALE, seconds);
            smoothedPosition = smoothedPosition.add(target.subtract(smoothedPosition).scale(positionAlpha));

            smoothedRotation.slerp(headRotation, (float) alpha(rate, seconds));
            smoothedRotation.normalize();
        }

        event.getCamera().setPosition(smoothedPosition);

        // The head looks along its own -Z, because vanilla's humanoid model faces -Z and
        // the body orientation carries vanilla's half turn. A camera looks along +Z, so
        // turn the head round before decomposing, or the view faces out of the back of
        // the skull.
        Quaternionf view = new Quaternionf(smoothedRotation).rotateY((float) Math.PI);

        // Minecraft builds its camera as RotY(-yaw) * RotX(pitch) * RotZ(roll), so the
        // YXZ decomposition maps across directly, with yaw negated.
        Vector3f euler = view.getEulerAnglesYXZ(new Vector3f());
        event.setYaw((float) -Math.toDegrees(euler.y));
        event.setPitch((float) Math.toDegrees(euler.x));

        // Roll is the component that makes people queasy, so it is scaled rather than
        // taken whole.
        event.setRoll((float) (Math.toDegrees(euler.z) * TumbleConfig.CLIENT.cameraRoll.get()));
    }

    /** Exponential easing toward a target: framerate independent by construction. */
    private static double alpha(double rate, double seconds) {
        return 1.0D - Math.exp(-rate * seconds);
    }

    private static double easingRate() {
        double smoothness = TumbleConfig.CLIENT.cameraSmoothing.get();
        return RATE_RAW + (RATE_SMOOTH - RATE_RAW) * smoothness;
    }

    private static double frameSeconds() {
        long now = System.nanoTime();
        double seconds = (now - lastFrameNanos) / 1.0e9D;
        lastFrameNanos = now;
        return Math.min(Math.max(seconds, 0.0D), MAX_FRAME_SECONDS);
    }

    private static void reset() {
        smoothedPosition = UNSET;
        smoothedFor = -1;
    }
}
