package dev.shrimpscript.tumble.ragdoll;

import dev.shrimpscript.tumble.Tumble;
import dev.shrimpscript.tumble.entity.RagdollEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.entity.EntityMountEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Stops a downed player leaving their ragdoll before they are allowed to.
 *
 * <p>Gating the mod's own get-up packet was not enough. Sneaking is vanilla's universal
 * "leave the vehicle" gesture, and it never consults this mod, so holding shift through
 * an explosion - or tapping it on the way into the air - dismounted the player instantly
 * and cancelled the launch. Every dismount route ends at {@code removeVehicle}, which is
 * where this event fires, so guarding here covers all of them at once.
 *
 * <p>The dangerous failure here is the opposite one: a cancelled dismount that should
 * have gone through leaves a player welded to a body forever. Anything that is not a
 * player choosing to stand up early is therefore let through.
 */
@Mod.EventBusSubscriber(modid = Tumble.MOD_ID)
public final class RagdollDismountGuard {

    private RagdollDismountGuard() {
    }

    @SubscribeEvent
    public static void onDismount(EntityMountEvent event) {
        if (!event.isDismounting()) {
            return;
        }
        if (!(event.getEntityBeingMounted() instanceof RagdollEntity ragdoll)) {
            return;
        }
        if (!(event.getEntityMounting() instanceof Player rider)) {
            return;
        }

        // Let through anything that is not a player choosing to rise early: the mod
        // releasing them, the ragdoll going away, a dead rider, or a rider being removed
        // because they are logging out or changing dimension.
        if (ragdoll.isReleasing() || ragdoll.isRemoved() || rider.isRemoved() || !rider.isAlive()) {
            return;
        }

        boolean allowed = ragdoll.level().isClientSide
                ? ragdoll.canGetUpSynced()
                : ragdoll.canGetUp();

        if (!allowed) {
            event.setCanceled(true);
        }
    }
}
