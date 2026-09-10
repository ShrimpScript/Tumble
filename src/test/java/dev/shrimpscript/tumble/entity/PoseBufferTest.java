package dev.shrimpscript.tumble.entity;

import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins when a newly arrived pose becomes visible.
 *
 * <p>Reported from play: a ragdoll thrown through the air flickered in bursts while a
 * settling one looked fine. The cause was promoting a pose the moment its packet landed,
 * which put it on a different clock from partial tick.
 */
class PoseBufferTest {

    private static Vec3[] at(double x) {
        return new Vec3[]{new Vec3(x, 0.0D, 0.0D)};
    }

    private static Quaternionf[] identity() {
        return new Quaternionf[]{new Quaternionf()};
    }

    private static double sampleX(PoseBuffer buffer, float partialTick) {
        buffer.sample(partialTick);
        return buffer.position(0).x;
    }

    @Test
    void firstPoseIsVisibleImmediately() {
        PoseBuffer buffer = new PoseBuffer(1);
        assertTrue(!buffer.hasPose(), "an empty buffer has nothing to draw");

        buffer.push(at(5.0D), identity());

        assertTrue(buffer.hasPose(), "the first pose must show without waiting for a tick");
        assertEquals(5.0D, sampleX(buffer, 0.5F), 1.0e-9D);
    }

    @Test
    void blendsAcrossOneTickOfTravel() {
        PoseBuffer buffer = new PoseBuffer(1);
        buffer.push(at(0.0D), identity());

        buffer.push(at(2.0D), identity());
        buffer.onClientTick();

        assertEquals(0.0D, sampleX(buffer, 0.0F), 1.0e-9D);
        assertEquals(1.0D, sampleX(buffer, 0.5F), 1.0e-9D);
        assertEquals(2.0D, sampleX(buffer, 1.0F), 1.0e-9D);
    }

    /**
     * The regression that caused the flicker. A body moving two blocks per tick is
     * sampled all the way through several ticks; the drawn position must only ever go
     * forwards, and never jump by more than one tick of travel.
     */
    @Test
    void sampledMotionNeverJumpsBackwards() {
        PoseBuffer buffer = new PoseBuffer(1);
        double perTick = 2.0D;

        buffer.push(at(0.0D), identity());

        double previous = sampleX(buffer, 0.0F);
        for (int tick = 1; tick <= 20; tick++) {
            // The packet for this tick arrives at some arbitrary point during the previous
            // one, which is exactly what the network does.
            buffer.push(at(tick * perTick), identity());
            buffer.onClientTick();

            for (float partial = 0.0F; partial <= 1.0F; partial += 0.1F) {
                double sampled = sampleX(buffer, partial);

                assertTrue(sampled >= previous - 1.0e-6D,
                        "drawn position went backwards at tick " + tick + ", partial " + partial
                                + ": " + previous + " then " + sampled);
                assertTrue(sampled - previous <= perTick + 1.0e-6D,
                        "drawn position jumped " + (sampled - previous)
                                + " blocks, more than one tick of travel");
                previous = sampled;
            }
        }
    }

    /**
     * Two poses arriving before a single tick boundary must not tear. The older is
     * dropped and the blend still spans exactly one tick.
     */
    @Test
    void twoPosesInOneTickDoNotTear() {
        PoseBuffer buffer = new PoseBuffer(1);
        buffer.push(at(0.0D), identity());

        buffer.push(at(2.0D), identity());
        buffer.push(at(4.0D), identity());
        buffer.onClientTick();

        assertEquals(0.0D, sampleX(buffer, 0.0F), 1.0e-9D,
                "the blend must still start from the pose that was on screen");
        assertEquals(4.0D, sampleX(buffer, 1.0F), 1.0e-9D,
                "and end at the newest pose received");
    }

    @Test
    void aLatePacketHoldsRatherThanTearing() {
        PoseBuffer buffer = new PoseBuffer(1);
        buffer.push(at(0.0D), identity());
        buffer.push(at(2.0D), identity());
        buffer.onClientTick();

        // Nothing arrived this tick.
        buffer.onClientTick();

        assertEquals(2.0D, sampleX(buffer, 0.0F), 1.0e-9D);
        assertEquals(2.0D, sampleX(buffer, 1.0F), 1.0e-9D,
                "with no new pose the body holds still instead of guessing");
    }
}
