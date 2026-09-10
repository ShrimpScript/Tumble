package dev.shrimpscript.tumble.net;

import dev.shrimpscript.tumble.ragdoll.TumbleTriggers;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkEvent;

import java.util.function.Supplier;

/**
 * A client reporting its own motion, so the impact trigger can see a real velocity.
 *
 * <p>The server does not know how fast a player is moving with any precision - players are
 * client-authoritative about their own movement, and the server's delta is reconstructed
 * from position packets. Detecting "was going 30 blocks per second, now is not" therefore
 * has to start on the client.
 *
 * <p>Only sent while actually moving fast, plus a short tail so the stop itself is
 * reported, so this costs nothing during normal play.
 */
public final class PlayerMotionPacket {

    private final double x;
    private final double y;
    private final double z;

    public PlayerMotionPacket(Vec3 motion) {
        this.x = motion.x;
        this.y = motion.y;
        this.z = motion.z;
    }

    public PlayerMotionPacket(FriendlyByteBuf buf) {
        this.x = buf.readFloat();
        this.y = buf.readFloat();
        this.z = buf.readFloat();
    }

    public void encode(FriendlyByteBuf buf) {
        buf.writeFloat((float) x);
        buf.writeFloat((float) y);
        buf.writeFloat((float) z);
    }

    public void handle(Supplier<NetworkEvent.Context> context) {
        NetworkEvent.Context ctx = context.get();
        ctx.enqueueWork(() -> {
            ServerPlayer player = ctx.getSender();
            if (player != null) {
                TumbleTriggers.onMotionSample(player, new Vec3(x, y, z));
            }
        });
        ctx.setPacketHandled(true);
    }
}
