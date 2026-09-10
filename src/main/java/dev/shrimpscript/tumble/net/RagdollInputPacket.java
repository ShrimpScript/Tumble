package dev.shrimpscript.tumble.net;

import dev.shrimpscript.tumble.entity.RagdollEntity;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Movement input from a downed player, for rolling.
 *
 * <p>Vanilla only forwards rider input for a vehicle that names a controlling passenger,
 * and declaring one would also let the client push the vehicle around with
 * ServerboundMoveVehiclePacket. This mod is server-authoritative about where a body is,
 * so it carries its own input instead and keeps the client out of the physics.
 *
 * <p>Only sent on ticks where there is actually input, so an idle ragdoll costs nothing.
 */
public final class RagdollInputPacket {

    private final float strafe;
    private final float forward;

    public RagdollInputPacket(float strafe, float forward) {
        this.strafe = strafe;
        this.forward = forward;
    }

    public RagdollInputPacket(FriendlyByteBuf buf) {
        this.strafe = buf.readFloat();
        this.forward = buf.readFloat();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeFloat(strafe);
        buf.writeFloat(forward);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null && player.getVehicle() instanceof RagdollEntity ragdoll) {
                // Clamped, because the value arrives from the client.
                ragdoll.setRollInput(
                        Math.max(-1.0F, Math.min(1.0F, strafe)),
                        Math.max(-1.0F, Math.min(1.0F, forward)));
            }
        });
        ctx.setPacketHandled(true);
    }
}
