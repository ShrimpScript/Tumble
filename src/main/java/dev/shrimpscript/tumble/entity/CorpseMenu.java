package dev.shrimpscript.tumble.entity;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraftforge.network.NetworkHooks;

/**
 * Opens a corpse using Minecraft's own chest screen.
 *
 * <p>A player's belongings fit inside five rows, so there is no reason to ship a custom
 * menu and screen for this: vanilla's already handles slot syncing, shift-clicking and
 * the client GUI, and it is the interaction players already know.
 */
public final class CorpseMenu {

    private CorpseMenu() {
    }

    public static void open(Player player, CorpseEntity corpse) {
        if (!(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        NetworkHooks.openScreen(serverPlayer, new SimpleMenuProvider(
                (id, inventory, opener) -> ChestMenu.sixRows(id, inventory, corpse.contents()),
                corpse.displayTitle()));
    }
}
