package dev.shrimpscript.tumble.net;

import dev.shrimpscript.tumble.config.TumbleConfig;
import dev.shrimpscript.tumble.entity.RagdollEntity;
import dev.shrimpscript.tumble.entity.SettlingBody;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.AABB;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A player reaching for, or letting go of, a body.
 *
 * <p>The packet says only whether the grab key is down. Which body and which limb is
 * decided entirely on the server from the player's own position and look direction, so a
 * client cannot claim to be holding something across the map or out of sight.
 */
public final class RagdollGrabPacket {

    private final boolean grabbing;

    public RagdollGrabPacket(boolean grabbing) {
        this.grabbing = grabbing;
    }

    public RagdollGrabPacket(FriendlyByteBuf buf) {
        this.grabbing = buf.readBoolean();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeBoolean(grabbing);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null || !TumbleConfig.SERVER.grabEnabled.get()) {
                return;
            }

            double reach = TumbleConfig.SERVER.grabReach.get();
            AABB search = player.getBoundingBox().inflate(reach + 2.0D);

            for (RagdollEntity body : player.level().getEntitiesOfClass(RagdollEntity.class, search)) {
                if (!grabbing) {
                    // Release whatever this player was holding, wherever it is.
                    if (body.isGrabbedBy(player)) {
                        body.releaseGrab();
                    }
                    continue;
                }

                // Never your own body: you are riding it.
                if (body == player.getVehicle()) {
                    continue;
                }
                if (body.tryGrab(player)) {
                    // A settled body has to start simulating again to be dragged anywhere.
                    if (body instanceof SettlingBody settling) {
                        settling.wake();
                    }
                    return;
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
