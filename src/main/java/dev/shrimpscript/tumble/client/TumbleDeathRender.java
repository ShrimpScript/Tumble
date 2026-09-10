package dev.shrimpscript.tumble.client;

import dev.shrimpscript.tumble.Tumble;
import dev.shrimpscript.tumble.config.TumbleConfig;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLivingEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Hides vanilla's death animation for anything that leaves a body behind.
 *
 * <p>Otherwise the dying mob keeps toppling over on top of its own corpse: two bodies in
 * the same place, one tipping through the other.
 */
@Mod.EventBusSubscriber(modid = Tumble.MOD_ID, value = Dist.CLIENT)
public final class TumbleDeathRender {

    private TumbleDeathRender() {
    }

    @SubscribeEvent
    public static void onRenderLiving(RenderLivingEvent.Pre<?, ?> event) {
        LivingEntity entity = event.getEntity();
        if (entity instanceof Player || entity.deathTime <= 0) {
            return;
        }
        if (!TumbleConfig.CLIENT.hideVanillaDeathAnimation.get()) {
            return;
        }
        if (!TumbleConfig.SERVER.mobCorpseEnabled.get()) {
            return;
        }

        String id = EntityType.getKey(entity.getType()).toString();
        for (String entry : TumbleConfig.SERVER.mobCorpseTypes.get()) {
            if (entry.equals(id)) {
                event.setCanceled(true);
                return;
            }
        }
    }
}
