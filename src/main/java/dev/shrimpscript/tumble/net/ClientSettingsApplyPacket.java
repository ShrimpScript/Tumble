package dev.shrimpscript.tumble.net;

import dev.shrimpscript.tumble.client.ClientSettingsHandler;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * Tells a client to change one of its own settings, because its player asked for it in
 * the menu. Only ever names a client-side setting; the receiving end checks that.
 */
public final class ClientSettingsApplyPacket {

    private final String path;
    private final String value;

    public ClientSettingsApplyPacket(String path, String value) {
        this.path = path;
        this.value = value;
    }

    public ClientSettingsApplyPacket(FriendlyByteBuf buf) {
        path = buf.readUtf(256);
        value = buf.readUtf(2048);
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeUtf(path, 256);
        buf.writeUtf(value, 2048);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        context.get().enqueueWork(() -> DistExecutor.unsafeRunWhenOn(Dist.CLIENT,
                () -> () -> ClientSettingsHandler.apply(path, value)));
        context.get().setPacketHandled(true);
    }
}
