package dev.shrimpscript.tumble.ragdoll;

import dev.shrimpscript.tumble.config.TumbleConfig;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * States in which a player must never be knocked down automatically.
 *
 * <p>This list is the least obvious and most important part of the trigger system. A
 * slime block launch, a riptide trident and an elytra takeoff all look exactly like a
 * violent impact to the physics, so without these checks the mod puts the player on the
 * floor every time they do something the game intends. Suppression applies only to
 * automatic triggers - a player asking to go down with the keybind always may.
 *
 * <p>Each state also leaves a grace period behind it, because the dangerous moment is
 * usually just after: the bounce, not the block.
 */
public final class Suppressions {

    private static final Map<UUID, Integer> GRACE = new HashMap<>();

    private Suppressions() {
    }

    /** Called every server tick for each player, to maintain the grace countdown. */
    public static void tick(ServerPlayer player) {
        if (inSuppressedState(player)) {
            GRACE.put(player.getUUID(), TumbleConfig.SERVER.suppressionGraceTicks.get());
            return;
        }
        Integer remaining = GRACE.get(player.getUUID());
        if (remaining == null) {
            return;
        }
        if (remaining <= 1) {
            GRACE.remove(player.getUUID());
        } else {
            GRACE.put(player.getUUID(), remaining - 1);
        }
    }

    public static boolean isSuppressed(ServerPlayer player) {
        return inSuppressedState(player) || GRACE.containsKey(player.getUUID());
    }

    public static void forget(UUID playerId) {
        GRACE.remove(playerId);
    }

    private static boolean inSuppressedState(ServerPlayer player) {
        TumbleConfig.Server config = TumbleConfig.SERVER;

        if (config.suppressRiptide.get() && player.isAutoSpinAttack()) {
            return true;
        }
        if (config.suppressElytraFlight.get() && player.isFallFlying()) {
            return true;
        }
        if (config.suppressCreativeFlight.get() && player.getAbilities().flying) {
            return true;
        }
        if (config.suppressClimbing.get() && player.onClimbable()) {
            return true;
        }
        if (config.suppressWater.get() && inWater(player)) {
            return true;
        }
        return config.suppressBounce.get() && onBouncyBlock(player);
    }

    /**
     * A water clutch is the case this exists for: falling a long way into a pool does no
     * damage in vanilla, but hitting the surface is a very large change in speed, which
     * is precisely what the impact trigger is watching for.
     *
     * <p>The block is checked as well as the entity flag, because the flag only turns on
     * once the player is properly in the fluid and the impact registers on the way in.
     */
    private static boolean inWater(ServerPlayer player) {
        if (player.isInWater()) {
            return true;
        }
        return player.level().getFluidState(player.blockPosition()).is(FluidTags.WATER)
                || player.level().getFluidState(player.blockPosition().below()).is(FluidTags.WATER);
    }

    /**
     * A slime block cancels fall damage but produces a huge velocity reversal, which is
     * exactly what the impact trigger looks for. Catching it at the block is what stops a
     * bounce from reading as a crash.
     */
    private static boolean onBouncyBlock(ServerPlayer player) {
        BlockState below = player.getBlockStateOn();
        return below.is(Blocks.SLIME_BLOCK) || below.is(BlockTags.BEDS);
    }
}
