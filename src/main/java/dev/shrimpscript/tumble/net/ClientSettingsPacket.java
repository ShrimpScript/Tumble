package dev.shrimpscript.tumble.net;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.network.NetworkEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** A client telling the server what its own settings are currently set to. */
public final class ClientSettingsPacket {

    /** Enough for every client setting several times over, and small enough to be safe. */
    private static final int MAX_ENTRIES = 128;

    private final Map<String, String> values;

    public ClientSettingsPacket(Map<String, String> values) {
        this.values = values;
    }

    public ClientSettingsPacket(FriendlyByteBuf buf) {
        int count = Math.min(buf.readVarInt(), MAX_ENTRIES);
        values = new HashMap<>(count);
        for (int i = 0; i < count; i++) {
            values.put(buf.readUtf(256), buf.readUtf(2048));
        }
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeVarInt(values.size());
        values.forEach((path, value) -> {
            buf.writeUtf(path, 256);
            buf.writeUtf(value, 2048);
        });
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                ClientSettings.report(player, values);
            }
        });
        ctx.setPacketHandled(true);
    }
}
