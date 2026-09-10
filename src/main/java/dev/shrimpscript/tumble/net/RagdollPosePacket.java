package dev.shrimpscript.tumble.net;

import dev.shrimpscript.tumble.entity.RagdollEntity;
import dev.shrimpscript.tumble.physics.RigidBody;
import dev.shrimpscript.tumble.client.ClientPoseHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.function.Supplier;

/**
 * One tick of ragdoll pose, sent to everyone tracking the entity.
 *
 * <p>The packet carries its own anchor rather than leaning on the entity's synced
 * position. That is what stops the body ghosting: vanilla sends entity movement as
 * quantised deltas that arrive a tick behind, so measuring limb offsets against the
 * entity's client-side position put the limbs and their anchor on two different clocks,
 * and a fast-moving ragdoll visibly tore into a second flickering body.
 *
 * <p>With the anchor in the packet, a limb's world position is known exactly from one
 * message, and the renderer simply subtracts wherever the entity happens to be drawn.
 */
public final class RagdollPosePacket {

    private final int entityId;
    private final double anchorX;
    private final double anchorY;
    private final double anchorZ;
    private final Vector3f[] positions;
    private final Quaternionf[] rotations;

    private RagdollPosePacket(int entityId, double anchorX, double anchorY, double anchorZ,
                              Vector3f[] positions, Quaternionf[] rotations) {
        this.entityId = entityId;
        this.anchorX = anchorX;
        this.anchorY = anchorY;
        this.anchorZ = anchorZ;
        this.positions = positions;
        this.rotations = rotations;
    }

    public static RagdollPosePacket of(RagdollEntity ragdoll) {
        Vector3f[] positions = new Vector3f[RagdollEntity.PART_COUNT];
        Quaternionf[] rotations = new Quaternionf[RagdollEntity.PART_COUNT];

        // The anchor is the entity's authoritative position at the moment of sending, and
        // the offsets are measured from it, so the two can never disagree.
        double ax = ragdoll.getX();
        double ay = ragdoll.getY();
        double az = ragdoll.getZ();

        for (int i = 0; i < RagdollEntity.PART_COUNT; i++) {
            RigidBody body = ragdoll.body(i);
            positions[i] = new Vector3f(
                    (float) (body.pos.x - ax),
                    (float) (body.pos.y - ay),
                    (float) (body.pos.z - az));
            rotations[i] = new Quaternionf(
                    (float) body.rot.x, (float) body.rot.y,
                    (float) body.rot.z, (float) body.rot.w);
        }
        return new RagdollPosePacket(ragdoll.getId(), ax, ay, az, positions, rotations);
    }

    public RagdollPosePacket(FriendlyByteBuf buf) {
        entityId = buf.readVarInt();
        anchorX = buf.readDouble();
        anchorY = buf.readDouble();
        anchorZ = buf.readDouble();
        positions = new Vector3f[RagdollEntity.PART_COUNT];
        rotations = new Quaternionf[RagdollEntity.PART_COUNT];

        for (int i = 0; i < RagdollEntity.PART_COUNT; i++) {
            positions[i] = new Vector3f(buf.readFloat(), buf.readFloat(), buf.readFloat());
            rotations[i] = new Quaternionf(
                    buf.readFloat(), buf.readFloat(), buf.readFloat(), buf.readFloat());
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(entityId);
        buf.writeDouble(anchorX);
        buf.writeDouble(anchorY);
        buf.writeDouble(anchorZ);
        for (int i = 0; i < RagdollEntity.PART_COUNT; i++) {
            buf.writeFloat(positions[i].x);
            buf.writeFloat(positions[i].y);
            buf.writeFloat(positions[i].z);
            buf.writeFloat(rotations[i].x);
            buf.writeFloat(rotations[i].y);
            buf.writeFloat(rotations[i].z);
            buf.writeFloat(rotations[i].w);
        }
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        // Routed through DistExecutor so the client-only handler class is never loaded on
        // a dedicated server.
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientPoseHandler.accept(
                        entityId, anchorX, anchorY, anchorZ, positions, rotations)));
        context.get().setPacketHandled(true);
    }
}
