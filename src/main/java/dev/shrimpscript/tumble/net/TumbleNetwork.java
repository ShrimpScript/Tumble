package dev.shrimpscript.tumble.net;

import dev.shrimpscript.tumble.Tumble;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.simple.SimpleChannel;

public final class TumbleNetwork {

    private static final String PROTOCOL = "1";

    public static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(Tumble.MOD_ID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);

    private TumbleNetwork() {
    }

    public static void register() {
        int id = 0;

        CHANNEL.messageBuilder(RagdollPosePacket.class, id++)
                .encoder(RagdollPosePacket::encode)
                .decoder(RagdollPosePacket::new)
                .consumerMainThread(RagdollPosePacket::handle)
                .add();

        CHANNEL.messageBuilder(RagdollActionPacket.class, id++)
                .encoder(RagdollActionPacket::encode)
                .decoder(RagdollActionPacket::new)
                .consumerMainThread(RagdollActionPacket::handle)
                .add();

        CHANNEL.messageBuilder(RagdollGrabPacket.class, id++)
                .encoder(RagdollGrabPacket::encode)
                .decoder(RagdollGrabPacket::new)
                .consumerMainThread(RagdollGrabPacket::handle)
                .add();

        CHANNEL.messageBuilder(PlayerMotionPacket.class, id++)
                .encoder(PlayerMotionPacket::encode)
                .decoder(PlayerMotionPacket::new)
                .consumerMainThread(PlayerMotionPacket::handle)
                .add();

        CHANNEL.messageBuilder(RagdollInputPacket.class, id++)
                .encoder(RagdollInputPacket::encode)
                .decoder(RagdollInputPacket::new)
                .consumerMainThread(RagdollInputPacket::handle)
                .add();
        CHANNEL.messageBuilder(ClientSettingsRequestPacket.class, id++)
                .encoder(ClientSettingsRequestPacket::encode)
                .decoder(ClientSettingsRequestPacket::new)
                .consumerMainThread(ClientSettingsRequestPacket::handle)
                .add();

        CHANNEL.messageBuilder(ClientSettingsPacket.class, id++)
                .encoder(ClientSettingsPacket::encode)
                .decoder(ClientSettingsPacket::new)
                .consumerMainThread(ClientSettingsPacket::handle)
                .add();

        CHANNEL.messageBuilder(ClientSettingsApplyPacket.class, id++)
                .encoder(ClientSettingsApplyPacket::encode)
                .decoder(ClientSettingsApplyPacket::new)
                .consumerMainThread(ClientSettingsApplyPacket::handle)
                .add();
    }
}
