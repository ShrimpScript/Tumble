package dev.shrimpscript.tumble.net;

import dev.shrimpscript.tumble.client.ClientSettingsHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/** Asks a client to report its own settings, so the menu can show them. */
public final class ClientSettingsRequestPacket {

    public ClientSettingsRequestPacket() {
    }

    public ClientSettingsRequestPacket(FriendlyByteBuf buf) {
    }

    public void encode(FriendlyByteBuf buf) {
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> ClientSettingsHandler::report));
        context.get().setPacketHandled(true);
    }
}
