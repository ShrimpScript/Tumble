package dev.shrimpscript.tumble.entity;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;

/**
 * Holds the last two ragdoll poses and blends between them on the client tick clock.
 *
 * <p>The subtle part is <em>when</em> a newly arrived pose becomes the one being blended
 * toward. Promoting it the moment the packet lands was the cause of the tearing: partial
 * tick runs 0 to 1 across a client tick, but a packet arrives wherever the network puts
 * it. One landing 60% of the way through a tick left the renderer interpolating 70% of
 * the way across a gap that had only 40% of its time left, so the body lurched forward
 * and snapped back. The error is proportional to speed, which is why a settling body
 * looked fine and a thrown one flickered.
 *
 * <p>Promoting on the tick boundary instead means the two poses being blended are always
 * consecutive ticks, which is exactly what partial tick measures - the same scheme
 * vanilla uses for entity positions, so the body, the camera and the entity all stay on
 * one clock. A late packet costs a single held tick rather than a visible tear.
 */
public final class PoseBuffer {

    private final int partCount;

    private Vec3[] pendingPositions;
    private Quaternionf[] pendingRotations;

    private Vec3[] currentPositions;
    private Quaternionf[] currentRotations;
    private Vec3[] previousPositions;
    private Quaternionf[] previousRotations;

    private final Vec3[] sampledPositions;
    private final Quaternionf[] sampledRotations;

    public PoseBuffer(int partCount) {
        this.partCount = partCount;
        this.sampledPositions = new Vec3[partCount];
        this.sampledRotations = new Quaternionf[partCount];

        for (int i = 0; i < partCount; i++) {
            sampledPositions[i] = Vec3.ZERO;
            sampledRotations[i] = new Quaternionf();
        }
    }

    public boolean hasPose() {
        return currentPositions != null;
    }

    /** Records a snapshot. It does not become visible until the next tick boundary. */
    public void push(Vec3[] positions, Quaternionf[] rotations) {
        Quaternionf[] copied = new Quaternionf[partCount];
        for (int i = 0; i < partCount; i++) {
            copied[i] = new Quaternionf(rotations[i]);
        }
        pendingPositions = positions;
        pendingRotations = copied;

        // The very first snapshot has nothing to blend from, so show it immediately
        // rather than leaving a frame with no body.
        if (currentPositions == null) {
            promote();
        }
    }

    /** Advances the blend by one tick. Called from the entity's client tick. */
    public void onClientTick() {
        if (pendingPositions != null) {
            promote();
            return;
        }

        // Nothing arrived. Collapse the blend onto the current pose so the body holds
        // still; leaving it would replay the previous tick's motion from the start and
        // the body would visibly rewind.
        previousPositions = currentPositions;
        previousRotations = currentRotations;
    }

    private void promote() {
        previousPositions = currentPositions;
        previousRotations = currentRotations;
        currentPositions = pendingPositions;
        currentRotations = pendingRotations;
        pendingPositions = null;
        pendingRotations = null;

        if (previousPositions == null) {
            previousPositions = currentPositions;
            previousRotations = currentRotations;
        }
    }

    /** Resolves the pose for this frame. Call once before reading any part. */
    public void sample(float partialTick) {
        if (currentPositions == null) {
            return;
        }
        for (int i = 0; i < partCount; i++) {
            sampledPositions[i] = previousPositions[i].lerp(currentPositions[i], partialTick);
            sampledRotations[i].set(previousRotations[i]).slerp(currentRotations[i], partialTick);
        }
    }

    public Vec3 position(int index) {
        return sampledPositions[index];
    }

    public Quaternionf rotation(int index, Quaternionf dest) {
        return dest.set(sampledRotations[index]);
    }
}
