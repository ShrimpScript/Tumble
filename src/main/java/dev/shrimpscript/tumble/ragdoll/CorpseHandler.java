package dev.shrimpscript.tumble.ragdoll;

import dev.shrimpscript.tumble.Tumble;
import dev.shrimpscript.tumble.config.TumbleConfig;
import dev.shrimpscript.tumble.entity.CorpseEntity;
import dev.shrimpscript.tumble.entity.MobCorpseEntity;
import dev.shrimpscript.tumble.entity.RagdollEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Turns a player's death into a body that keeps their things.
 *
 * <p>Hooked at death rather than at the drop event on purpose: emptying the inventory
 * before vanilla reaches its dropping code means there is nothing left to scatter, so
 * items land in the corpse instead of on the floor beside it. Keep-inventory is respected
 * - the body is still left behind, but the player keeps what they were carrying.
 */
@Mod.EventBusSubscriber(modid = Tumble.MOD_ID)
public final class CorpseHandler {

    private CorpseHandler() {
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onDeath(LivingDeathEvent event) {
        if (!TumbleConfig.SERVER.corpseEnabled.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player) || player.level().isClientSide) {
            return;
        }
        if (player.isSpectator()) {
            return;
        }

        // A player dying while ragdolled is already lying down; end that first so the
        // corpse is the only body left. Their own delta movement is useless here, because
        // vanilla zeroes a passenger's motion every tick - the body they were riding is
        // what actually carries the speed.
        Vec3 momentum;
        if (player.getVehicle() instanceof RagdollEntity ragdoll && ragdoll.skeleton() != null) {
            Vector3d velocity = ragdoll.skeleton().torso().linVel;
            momentum = new Vec3(velocity.x, velocity.y, velocity.z);
            ragdoll.getUp();
        } else {
            // Standing still when shot means collapsing where they stand, which is what a
            // realistic death looks like: gravity does the rest.
            momentum = player.getDeltaMovement().scale(20.0D);

            // A blast that kills outright never got the chance to knock them down first,
            // so the body would drop where it stood instead of being thrown. Give it the
            // launch the explosion trigger would have.
            Vec3 blast = event.getSource().getSourcePosition();
            if (event.getSource().is(DamageTypeTags.IS_EXPLOSION) && blast != null) {
                Vec3 away = player.position()
                        .add(0.0D, player.getBbHeight() * 0.5D, 0.0D)
                        .subtract(blast);
                if (away.lengthSqr() > 1.0e-6D) {
                    momentum = away.normalize().scale(
                            TumbleConfig.SERVER.explosionLaunchMultiplier.get() * 2.0D);
                }
            }
        }
        CorpseEntity corpse = CorpseEntity.of(player, momentum);

        if (!player.level().getGameRules().getBoolean(GameRules.RULE_KEEPINVENTORY)) {
            corpse.takeInventoryFrom(player);
        }

        player.level().addFreshEntity(corpse);
    }

    /**
     * Humanoid mobs leave a body too, in place of vanilla's toppling animation.
     *
     * <p>Which mobs qualify has to be a configured list: the ragdoll is built from the six
     * cuboids of Minecraft's humanoid model, and which model a mob uses is a client-side
     * fact the server cannot see.
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onMobDeath(LivingDeathEvent event) {
        if (!TumbleConfig.SERVER.mobCorpseEnabled.get()) {
            return;
        }
        if (event.getEntity() instanceof ServerPlayer || event.getEntity().level().isClientSide) {
            return;
        }
        if (!isRagdollable(event.getEntity().getType())) {
            return;
        }

        Vec3 momentum = event.getEntity().getDeltaMovement().scale(20.0D);
        event.getEntity().level().addFreshEntity(
                MobCorpseEntity.of(event.getEntity(), momentum));
    }

    private static boolean isRagdollable(EntityType<?> type) {
        String id = EntityType.getKey(type).toString();
        for (String entry : TumbleConfig.SERVER.mobCorpseTypes.get()) {
            if (entry.equals(id)) {
                return true;
            }
        }
        return false;
    }

    /**
     * A settled corpse has stopped sending poses, so a player arriving later would see
     * nothing. Send it once when they start tracking the body.
     */
    @SubscribeEvent
    public static void onStartTracking(PlayerEvent.StartTracking event) {
        if (event.getTarget() instanceof RagdollEntity ragdoll
                && event.getEntity() instanceof ServerPlayer player) {
            ragdoll.sendPoseTo(player);
        }
    }

    /** Convenience for other code that wants to know whether a body belongs to someone. */
    public static boolean isOwner(CorpseEntity corpse, Player player) {
        return player.getUUID().equals(corpse.getOwnerId());
    }
}
