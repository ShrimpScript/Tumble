package dev.shrimpscript.tumble.net;

import dev.shrimpscript.tumble.Tumble;
import dev.shrimpscript.tumble.menu.ConfigMenu;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What each player's client has its own settings set to, as far as the server knows.
 *
 * <p>Client settings are presentation - the ragdoll camera, the controls hint - and live
 * in a file on that player's machine, so the server cannot read or write them directly.
 * The settings menu still shows them, which means asking the client and caching what comes
 * back. A player who never answers simply sees those rows marked unknown.
 */
@Mod.EventBusSubscriber(modid = Tumble.MOD_ID)
public final class ClientSettings {

    private static final Map<UUID, Map<String, String>> REPORTED = new ConcurrentHashMap<>();

    private ClientSettings() {
    }

    public static void request(ServerPlayer player) {
        TumbleNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ClientSettingsRequestPacket());
    }

    public static String get(ServerPlayer player, String path) {
        return REPORTED.getOrDefault(player.getUUID(), Map.of()).get(path);
    }

    /** Records what a client says its settings are, and redraws the menu if one is open. */
    public static void report(ServerPlayer player, Map<String, String> values) {
        REPORTED.put(player.getUUID(), Map.copyOf(values));
        if (player.containerMenu instanceof ConfigMenu menu) {
            menu.refresh();
        }
    }

    /** Asks a client to change one of its own settings, and assumes it will. */
    public static void set(ServerPlayer player, String path, String value) {
        Map<String, String> known = new HashMap<>(REPORTED.getOrDefault(player.getUUID(), Map.of()));
        known.put(path, value);
        REPORTED.put(player.getUUID(), Map.copyOf(known));

        TumbleNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                new ClientSettingsApplyPacket(path, value));
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        REPORTED.remove(event.getEntity().getUUID());
    }
}
