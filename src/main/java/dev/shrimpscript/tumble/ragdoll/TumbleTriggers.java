package dev.shrimpscript.tumble.ragdoll;

import dev.shrimpscript.tumble.Tumble;
import dev.shrimpscript.tumble.config.TumbleConfig;
import dev.shrimpscript.tumble.entity.CorpseEntity;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.damagesource.DamageTypes;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityStruckByLightningEvent;
import net.minecraftforge.event.entity.living.LivingDamageEvent;
import net.minecraftforge.event.entity.living.LivingFallEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/**
 * Everything that knocks a player down without them asking.
 *
 * <p>All of these route through {@link RagdollController#tumbleFromTrigger}, so the
 * cooldown, the eligibility rules and the suppression list are enforced once rather than
 * repeated per trigger.
 */
@Mod.EventBusSubscriber(modid = Tumble.MOD_ID)
public final class TumbleTriggers {

    /** Last reported motion per player, for spotting a sudden stop. */
    private static final java.util.Map<java.util.UUID, Vec3> LAST_MOTION = new java.util.HashMap<>();

    private TumbleTriggers() {
    }

    @SubscribeEvent
    public static void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        if (event.player instanceof ServerPlayer player) {
            Suppressions.tick(player);
        }
    }

    /**
     * Damage-driven triggers: a hard landing, an elytra crash, or a heavy blow.
     *
     * <p>Hooked at damage rather than at hurt so the numbers are post-armour, which is what
     * the thresholds are expressed in - being saved by netherite should also save you from
     * being floored.
     */
    @SubscribeEvent
    public static void onDamage(LivingDamageEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        DamageSource source = event.getSource();
        float amount = event.getAmount();
        TumbleConfig.Server config = TumbleConfig.SERVER;

        if (source.is(DamageTypes.FALL)) {
            // Zero means height is the only condition, so damage should not fire at all.
            double minDamage = config.fallMinDamage.get();
            if (config.fallEnabled.get() && minDamage > 0.0D && amount >= minDamage) {
                // Drive the body into the ground with a share of the landing speed.
                double speed = Math.abs(player.getDeltaMovement().y) * 20.0D;
                RagdollController.tumbleFromTrigger(player,
                        new Vec3(0.0D, -speed * config.fallSlamMultiplier.get(), 0.0D));
            }
            return;
        }

        if (source.is(DamageTypes.FLY_INTO_WALL)) {
            if (config.crashEnabled.get() && amount >= config.crashMinDamage.get()) {
                RagdollController.tumbleFromTrigger(player,
                        player.getDeltaMovement().scale(20.0D * config.crashLaunchMultiplier.get()));
            }
            return;
        }

        // Explosions are handled at detonation, where the blast geometry is known.
        if (source.is(DamageTypes.EXPLOSION) || source.is(DamageTypes.PLAYER_EXPLOSION)) {
            return;
        }

        if (config.hitEnabled.get() && amount >= config.hitMinDamage.get()) {
            // Scaled by the blow itself, so a glancing hit staggers and a crit throws you.
            //
            // Projectiles are scaled far lower. A bullet does a lot of damage while
            // carrying almost no momentum, so tying its knockback to its damage sends
            // people flying from something that should drop them where they stand.
            double multiplier = isProjectile(source)
                    ? config.hitProjectileLaunchMultiplier.get()
                    : config.hitLaunchMultiplier.get();

            // Clamped, because scaling by damage alone means a modded weapon doing thirty
            // damage throws a player at forty five blocks per second. Two of the gun mods
            // in use define no data-driven damage types at all, so no list of ids can be
            // complete and the ceiling is what actually guarantees this stays sane.
            double speed = Math.min(multiplier * amount, config.hitMaxLaunchSpeed.get());

            RagdollController.tumbleFromTrigger(player, awayFrom(source, player).scale(speed));
        }
    }

    /**
     * Impact detection, fed by client motion samples.
     *
     * <p>An impact is a sudden loss of speed, so it takes two samples: the tick before and
     * the tick of the stop. The body is then thrown along the direction it was already
     * travelling, which crumples it into whatever it hit rather than bouncing it back.
     */
    public static void onMotionSample(ServerPlayer player, Vec3 motion) {
        TumbleConfig.Server config = TumbleConfig.SERVER;

        Vec3 previous = LAST_MOTION.put(player.getUUID(), motion);
        if (previous == null || !config.impactEnabled.get()) {
            return;
        }

        double delta = previous.subtract(motion).length() * 20.0D;
        if (delta < config.impactMinVelocityDelta.get()) {
            return;
        }

        double launch = Math.min(delta, config.impactMaxVelocityDelta.get());
        if (previous.lengthSqr() < 1.0e-8D) {
            return;
        }
        RagdollController.tumbleFromTrigger(player, previous.normalize().scale(launch));
    }

    public static void forget(java.util.UUID playerId) {
        LAST_MOTION.remove(playerId);
    }

    /**
     * Blast knockdown, driven by geometry rather than by damage.
     *
     * <p>Reading explosion damage does not work. A creative player takes none at all, so
     * nothing ever fired while testing in creative; and TNT damage falls away so sharply
     * that only a player standing inside the charge cleared any sensible threshold. Reach
     * and force come from the blast's own radius instead, which is what makes a nearby
     * explosion throw you rather than having to be swallowed by it.
     *
     * <p>Vanilla itself affects entities out to twice the radius, so that is the reach
     * used here, plus a configurable margin.
     */
    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        TumbleConfig.Server config = TumbleConfig.SERVER;
        if (!config.explosionEnabled.get()) {
            return;
        }

        double power = event.getExplosion().radius;
        if (power < config.explosionMinPower.get()) {
            return;
        }

        Vec3 centre = event.getExplosion().getPosition();
        double reach = power * 2.0D + config.explosionRadiusPadding.get();

        for (Entity entity : event.getAffectedEntities()) {
            // A body lying in a blast should be thrown by it, the same as a living one.
            if (entity instanceof CorpseEntity corpse) {
                Vec3 offset = corpse.position().subtract(centre);
                double distance = offset.length();
                if (distance <= reach && distance > 1.0e-3D) {
                    double falloff = 1.0D - (distance / reach);
                    corpse.launchAndWake(offset.normalize()
                            .scale(config.explosionLaunchMultiplier.get() * power * falloff));
                }
                continue;
            }

            if (!(entity instanceof ServerPlayer player)) {
                continue;
            }

            // Measured from the chest, so a blast at foot level still throws you upward
            // and outward rather than purely sideways.
            Vec3 offset = player.position()
                    .add(0.0D, player.getBbHeight() * 0.5D, 0.0D)
                    .subtract(centre);

            double distance = offset.length();
            if (distance > reach) {
                continue;
            }
            if (distance < 1.0e-3D) {
                offset = new Vec3(0.0D, 1.0D, 0.0D);
                distance = 1.0e-3D;
            }

            double falloff = 1.0D - (distance / reach);
            double speed = config.explosionLaunchMultiplier.get() * power * falloff;

            RagdollController.tumbleFromTrigger(player, offset.normalize().scale(speed));
        }
    }

    /**
     * The other half of the hard landing: how far they fell, regardless of what it cost
     * them.
     *
     * <p>Damage alone is a poor proxy for a heavy landing. Armour, feather falling and
     * resistance all cut it, so a player can drop thirty blocks and take four points.
     * This fires on the distance itself, and the two conditions are independent - either
     * one is enough, and either can be switched off by setting its threshold to zero.
     *
     * <p>Landing in water never reaches here at all: vanilla clears fall distance on
     * entering the fluid, so the event does not fire.
     */
    @SubscribeEvent
    public static void onFall(LivingFallEvent event) {
        TumbleConfig.Server config = TumbleConfig.SERVER;
        if (!config.fallEnabled.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        double minHeight = config.fallMinHeight.get();
        if (minHeight <= 0.0D || event.getDistance() < minHeight) {
            return;
        }

        double speed = Math.abs(player.getDeltaMovement().y) * 20.0D;
        RagdollController.tumbleFromTrigger(player,
                new Vec3(0.0D, -speed * config.fallSlamMultiplier.get(), 0.0D));
    }

    @SubscribeEvent
    public static void onLightning(EntityStruckByLightningEvent event) {
        TumbleConfig.Server config = TumbleConfig.SERVER;
        if (!config.lightningEnabled.get()) {
            return;
        }
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        RagdollController.tumbleFromTrigger(player,
                new Vec3(0.0D, config.lightningLaunchSpeed.get(), 0.0D));
    }

    /**
     * Whether a blow should be treated as a bullet or arrow rather than a shove.
     *
     * <p>The vanilla tag alone is not enough. Gun mods register their own damage types and
     * generally do not join minecraft:is_projectile - TACZ declares tacz:bullets and
     * Superb Warfare declares superbwarfare:gun_damage, and neither appears in the vanilla
     * tag - so the extra ids are configurable, and each is tried both as a damage type and
     * as a tag.
     */
    private static boolean isProjectile(DamageSource source) {
        if (source.is(DamageTypeTags.IS_PROJECTILE)) {
            return true;
        }

        for (String entry : TumbleConfig.SERVER.projectileDamageTypes.get()) {
            ResourceLocation id = ResourceLocation.tryParse(entry);
            if (id == null) {
                continue;
            }
            boolean matchesType = source.typeHolder().unwrapKey()
                    .map(key -> key.location().equals(id))
                    .orElse(false);
            if (matchesType || source.is(TagKey.create(Registries.DAMAGE_TYPE, id))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Direction to throw someone hit by {@code source}. Falls back to straight up, so a
     * blow from an unknown origin still reads as a knockdown rather than doing nothing.
     */
    private static Vec3 awayFrom(DamageSource source, ServerPlayer player) {
        Vec3 origin = source.getSourcePosition();
        if (origin == null) {
            return new Vec3(0.0D, 1.0D, 0.0D);
        }
        Vec3 away = player.position().subtract(origin);
        if (away.lengthSqr() < 1.0e-6D) {
            return new Vec3(0.0D, 1.0D, 0.0D);
        }
        // Bias upward, or a hit just skids the body along the floor.
        return away.normalize().add(0.0D, 0.5D, 0.0D).normalize();
    }
}
