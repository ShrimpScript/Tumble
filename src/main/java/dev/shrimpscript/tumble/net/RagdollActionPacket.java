package dev.shrimpscript.tumble.net;

import dev.shrimpscript.tumble.entity.RagdollEntity;
import dev.shrimpscript.tumble.ragdoll.RagdollController;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** A player asking to go down, or to get back up. Both are server decisions. */
public final class RagdollActionPacket {

    public enum Action {
        TUMBLE,
        GET_UP
    }

    private final Action action;

    public RagdollActionPacket(Action action) {
        this.action = action;
    }

    public RagdollActionPacket(FriendlyByteBuf buf) {
        this.action = buf.readEnum(Action.class);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeEnum(action);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player == null) {
                return;
            }
            switch (action) {
                case TUMBLE -> RagdollController.tryManualTumble(player);
                case GET_UP -> {
                    if (player.getVehicle() instanceof RagdollEntity ragdoll && ragdoll.canGetUp()) {
                        ragdoll.getUp();
                    }
                }
            }
        });
        ctx.setPacketHandled(true);
    }
}
