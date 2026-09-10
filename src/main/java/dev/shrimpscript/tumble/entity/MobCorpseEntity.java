package dev.shrimpscript.tumble.entity;

import dev.shrimpscript.tumble.TumbleRegistry;
import dev.shrimpscript.tumble.config.TumbleConfig;
import dev.shrimpscript.tumble.ragdoll.LimbPose;
import dev.shrimpscript.tumble.ragdoll.RagdollSkeleton;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Optional;

/**
 * The body of a mob that has died, in place of vanilla's toppling death animation.
 *
 * <p>Carries no loot: a mob's drops are vanilla's business and still land on the floor.
 * This is purely the body, and it goes away on a timer.
 *
 * <p>Only humanoid mobs are given one. Which mobs count is decided on the server from a
 * configured list, because the model that would be drawn on the body is a client-side
 * thing the server cannot see.
 */
public class MobCorpseEntity extends SettlingBody {

    /** The mob this body came from, so the client knows whose model to draw. */
    private static final EntityDataAccessor<String> SOURCE_TYPE =
            SynchedEntityData.defineId(MobCorpseEntity.class, EntityDataSerializers.STRING);

    /** Baby mobs are drawn at half scale, and their bodies should be too. */
    private static final EntityDataAccessor<Boolean> BABY =
            SynchedEntityData.defineId(MobCorpseEntity.class, EntityDataSerializers.BOOLEAN);

    public MobCorpseEntity(EntityType<? extends MobCorpseEntity> type, Level level) {
        super(type, level);
    }

    /** Builds a body where a mob just died, carrying the pose and motion it had. */
    public static MobCorpseEntity of(LivingEntity mob, Vec3 momentum) {
        Level level = mob.level();
        MobCorpseEntity corpse = new MobCorpseEntity(TumbleRegistry.MOB_CORPSE.get(), level);

        Vec3 feet = mob.position();
        corpse.setPos(feet.x, feet.y, feet.z);

        ResourceLocation id = EntityType.getKey(mob.getType());
        corpse.entityData.set(SOURCE_TYPE, id.toString());
        corpse.entityData.set(BABY, mob.isBaby());

        RagdollSkeleton skeleton = new RagdollSkeleton(feet.x, feet.y, feet.z,
                RagdollSkeleton.facingFromYaw(mob.yBodyRot),
                TumbleConfig.SERVER.partSelfCollision.get(),
                LimbPose.of(mob));
        skeleton.setCollider(new LevelCollider(level));
        skeleton.world().maxSpeed = TumbleConfig.SERVER.maxLaunchSpeed.get();
        skeleton.launch(new Vector3d(momentum.x, momentum.y, momentum.z));

        corpse.setSkeleton(skeleton);
        return corpse;
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(SOURCE_TYPE, "");
        entityData.define(BABY, false);
    }

    /** The mob type whose model should be drawn, or empty if it is unknown. */
    public Optional<EntityType<?>> sourceType() {
        String id = entityData.get(SOURCE_TYPE);
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return EntityType.byString(id);
    }

    public boolean isBaby() {
        return entityData.get(BABY);
    }

    @Override
    protected void checkLifetime() {
        int lifetime = TumbleConfig.SERVER.mobCorpseLifetimeTicks.get();
        if (lifetime > 0 && age() >= lifetime) {
            discard();
        }
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        setAge(tag.getInt("Age"));
        entityData.set(SOURCE_TYPE, tag.getString("SourceType"));
        entityData.set(BABY, tag.getBoolean("Baby"));

        RagdollSkeleton skeleton = new RagdollSkeleton(getX(), getY(), getZ(), 0.0D,
                false, LimbPose.STANDING);
        skeleton.setCollider(new LevelCollider(level()));
        setSkeleton(skeleton);

        BodyPose.read(tag, skeleton);
        setFrozen(true);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Age", age());
        tag.putString("SourceType", entityData.get(SOURCE_TYPE));
        tag.putBoolean("Baby", isBaby());
        BodyPose.write(tag, skeleton());
    }
}
