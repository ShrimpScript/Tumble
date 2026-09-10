package dev.shrimpscript.tumble.entity;

import dev.shrimpscript.tumble.Tumble;
import dev.shrimpscript.tumble.TumbleRegistry;
import dev.shrimpscript.tumble.config.TumbleConfig;
import dev.shrimpscript.tumble.net.RagdollPosePacket;
import dev.shrimpscript.tumble.net.TumbleNetwork;
import dev.shrimpscript.tumble.physics.RigidBody;
import dev.shrimpscript.tumble.ragdoll.BodyPart;
import dev.shrimpscript.tumble.ragdoll.LimbPose;
import dev.shrimpscript.tumble.ragdoll.RagdollSkeleton;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.network.NetworkHooks;
import net.minecraftforge.network.PacketDistributor;
import org.joml.Quaternionf;
import org.joml.Vector3d;
import org.joml.Vector3f;

import java.util.Optional;
import java.util.UUID;

/**
 * The ragdoll itself: a plain entity in ordinary world space that owns a physics
 * assembly on the server and carries its rider along.
 *
 * <p>The player rides this entity, which is the decision that makes everything else
 * simple. Vanilla already keeps a passenger at its vehicle, already refuses to let a
 * passenger walk, and already points the camera at where the passenger is. None of the
 * teleporting, freezing or custom camera work the sub-level approach needs applies here.
 */
public class RagdollEntity extends Entity {

    public static final int PART_COUNT = BodyPart.values().length;

    private static final EntityDataAccessor<Optional<UUID>> OWNER =
            SynchedEntityData.defineId(RagdollEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** Synced so the controls hint can stop offering a key that would do nothing. */
    private static final EntityDataAccessor<Boolean> CAN_GET_UP =
            SynchedEntityData.defineId(RagdollEntity.class, EntityDataSerializers.BOOLEAN);

    /** Server side only. Null on the client, which just draws what it is sent. */
    private RagdollSkeleton skeleton;

    /** Client side render state: snapshots in world space, played back on a delay. */
    private final PoseBuffer poseBuffer = new PoseBuffer(PART_COUNT);

    private int ticksAlive;

    /** How quickly a roll reaches its target speed. A tick is 1/20 s, so this is brisk. */
    private static final double ACCELERATION = 0.25D;

    /** How far off the line of sight a limb may sit and still be grabbed, in blocks. */
    private static final double GRAB_PICK_RADIUS = 0.6D;

    /** Latest roll input from the rider, replaced each time a packet arrives. */
    private float rollStrafe;
    private float rollForward;

    private int impactDamageCooldown;

    /** Consecutive ticks the body has been resting on something. */
    private int groundedTicks;

    /** Who is holding this body, which limb, and since when. Server side only. */
    private UUID grabberId;
    private int grabbedPart = -1;

    /** Set while the mod is deliberately releasing the rider, so the guard lets it through. */
    private boolean releasing;

    public RagdollEntity(EntityType<? extends RagdollEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;

        // Limbs are drawn up to a block beyond this entity's own small box, so leaving it
        // to the frustum test makes a fast-moving body blink out for a frame or two as the
        // box leaves the view while the limbs are still plainly on screen.
        this.noCulling = true;
    }

    /** Creates a ragdoll standing where the player is, and seats the player in it. */
    public static RagdollEntity spawnFor(Player player) {
        Level level = player.level();
        RagdollEntity ragdoll = new RagdollEntity(TumbleRegistry.RAGDOLL.get(), level);

        Vec3 feet = player.position();
        ragdoll.setPos(feet.x, feet.y, feet.z);
        ragdoll.entityData.set(OWNER, Optional.of(player.getUUID()));

        // Start from the pose the player was actually drawn in, so a sprinting player's
        // legs stay where they were instead of snapping to a neutral stance.
        ragdoll.skeleton = new RagdollSkeleton(feet.x, feet.y, feet.z,
                RagdollSkeleton.facingFromYaw(player.yBodyRot),
                TumbleConfig.SERVER.partSelfCollision.get(),
                LimbPose.of(player));
        ragdoll.skeleton.setCollider(new LevelCollider(level));
        ragdoll.skeleton.world().maxSpeed = TumbleConfig.SERVER.maxLaunchSpeed.get();

        // Carry the player's own motion into the ragdoll, so running off a ledge keeps
        // going rather than dropping straight down.
        Vec3 motion = player.getDeltaMovement().scale(20.0D);
        ragdoll.skeleton.launch(new Vector3d(motion.x, motion.y, motion.z));

        level.addFreshEntity(ragdoll);
        player.startRiding(ragdoll, true);
        return ragdoll;
    }

    @Override
    protected void defineSynchedData() {
        entityData.define(OWNER, Optional.empty());
        entityData.define(CAN_GET_UP, false);
    }

    public UUID getOwnerId() {
        return entityData.get(OWNER).orElse(null);
    }

    protected void setOwnerId(UUID id) {
        entityData.set(OWNER, Optional.ofNullable(id));
    }

    public RagdollSkeleton skeleton() {
        return skeleton;
    }

    public int ticksAlive() {
        return ticksAlive;
    }

    @Override
    public void tick() {
        ticksAlive++;

        if (level().isClientSide) {
            // The entity is only an anchor for tracking and culling; every visible limb
            // position comes from the pose packet in world coordinates. Pinning the
            // previous position keeps its own box from lagging, which matters for
            // culling but no longer for where the limbs are drawn.
            xOld = getX();
            yOld = getY();
            zOld = getZ();

            // Promote the newest pose here rather than when its packet landed, so the two
            // poses being blended are always consecutive ticks.
            poseBuffer.onClientTick();
            return;
        }

        if (skeleton == null) {
            discard();
            return;
        }

        serverTick();
    }

    /** The living ragdoll's behaviour: a rider who can squirm, get hurt, and get up. */
    protected void serverTick() {
        applyRollInput();
        applyGrab();
        stepPhysics();
        updateGrounded();
        applyImpactDamage();

        if (checkExpiry()) {
            return;
        }

        sendPose();

        if (getFirstPassenger() == null && ticksAlive > 5) {
            discard();
        }
    }

    /**
     * Advances the simulation and moves the entity to follow the body, so vanilla tracking
     * and chunk loading stay correct without any special handling.
     */
    protected void stepPhysics() {
        skeleton.tick();
        Vector3d centre = skeleton.centreOfMass(new Vector3d());
        setPos(centre.x, centre.y, centre.z);
    }

    protected void sendPose() {
        TumbleNetwork.CHANNEL.send(
                PacketDistributor.TRACKING_ENTITY.with(() -> this),
                RagdollPosePacket.of(this));
    }

    /** Sends the current pose to one player, for a body that is not moving any more. */
    public void sendPoseTo(net.minecraft.server.level.ServerPlayer player) {
        if (skeleton == null) {
            return;
        }
        TumbleNetwork.CHANNEL.send(PacketDistributor.PLAYER.with(() -> player),
                RagdollPosePacket.of(this));
    }

    protected void setSkeleton(RagdollSkeleton skeleton) {
        this.skeleton = skeleton;
    }

    /**
     * Takes hold of one limb. The part is chosen by the server from the grabber's own
     * look direction, so a client cannot claim to have grabbed something it is not
     * looking at, or something across the map.
     *
     * @return true if a limb was within reach
     */
    public boolean tryGrab(Player grabber) {
        if (skeleton == null || !TumbleConfig.SERVER.grabEnabled.get()) {
            return false;
        }

        Vec3 eye = grabber.getEyePosition();
        Vec3 look = grabber.getLookAngle();
        double reach = TumbleConfig.SERVER.grabReach.get();

        int best = -1;
        double bestDistance = Double.MAX_VALUE;

        for (BodyPart part : BodyPart.values()) {
            Vector3d pos = skeleton.part(part).pos;
            Vec3 toPart = new Vec3(pos.x, pos.y, pos.z).subtract(eye);

            double along = toPart.dot(look);
            if (along < 0.0D || along > reach) {
                continue;
            }

            // How far the limb sits from the line of sight, and how near it is along it.
            double offAxis = toPart.subtract(look.scale(along)).length();
            if (offAxis > GRAB_PICK_RADIUS || along >= bestDistance) {
                continue;
            }
            bestDistance = along;
            best = part.ordinal();
        }

        if (best < 0) {
            return false;
        }

        grabberId = grabber.getUUID();
        grabbedPart = best;
        return true;
    }

    public void releaseGrab() {
        grabberId = null;
        grabbedPart = -1;
    }

    /** True while somebody is holding this body. */
    public boolean isGrabbed() {
        return grabbedPart >= 0 && grabberId != null;
    }

    public boolean isGrabbedBy(Player player) {
        return grabberId != null && grabberId.equals(player.getUUID());
    }

    /**
     * Drags the held limb toward the grabber's hand.
     *
     * <p>Velocity is steered rather than the position being set outright, so the body
     * still collides with the world on the way: a corpse dragged into a wall catches on
     * it instead of passing through, falls behind the hand, and the grip breaks.
     */
    protected void applyGrab() {
        if (grabbedPart < 0 || grabberId == null) {
            return;
        }

        Player grabber = level().getPlayerByUUID(grabberId);
        if (grabber == null || !grabber.isAlive() || grabber.isSpectator()) {
            releaseGrab();
            return;
        }

        Vec3 hand = grabber.getEyePosition()
                .add(grabber.getLookAngle().scale(TumbleConfig.SERVER.grabHoldDistance.get()));

        RigidBody body = skeleton.part(BodyPart.values()[grabbedPart]);
        Vec3 delta = hand.subtract(body.pos.x, body.pos.y, body.pos.z);

        if (delta.length() > TumbleConfig.SERVER.grabBreakDistance.get()) {
            releaseGrab();
            return;
        }

        double strength = TumbleConfig.SERVER.grabStrength.get();
        body.linVel.set(delta.x * strength, delta.y * strength, delta.z * strength);
    }

    /** Records roll input from the rider's client. */
    public void setRollInput(float strafe, float forward) {
        this.rollStrafe = strafe;
        this.rollForward = forward;
    }

    /**
     * Lets a downed player squirm.
     *
     * <p>The input direction becomes a spin about the horizontal axis across it, which is
     * what rolling actually is - shoving the body along the direction instead just makes
     * it skate.
     */
    private void applyRollInput() {
        if (!TumbleConfig.SERVER.rollEnabled.get()) {
            return;
        }
        if (!(getFirstPassenger() instanceof Player rider)) {
            return;
        }

        double strafe = rollStrafe;
        double forward = rollForward;

        // Input is consumed, so releasing the key stops the roll even if no packet says so.
        rollStrafe = 0.0F;
        rollForward = 0.0F;

        if (strafe == 0.0D && forward == 0.0D) {
            return;
        }

        double yaw = Math.toRadians(rider.getYRot());
        double sin = Math.sin(yaw);
        double cos = Math.cos(yaw);

        // Minecraft yaw 0 faces +Z, so forward is (-sin, 0, cos) and right is (cos, 0, sin).
        Vector3d direction = new Vector3d(
                -sin * forward + cos * strafe,
                0.0D,
                cos * forward + sin * strafe);

        if (direction.lengthSquared() < 1.0e-6D) {
            return;
        }
        direction.normalize();

        // Squirming is not a push the ground can resist, so it is not modelled as one.
        //
        // Two earlier attempts failed, both measured rather than guessed. Torque alone does
        // nothing: a flat torso spun about a horizontal axis just rocks, because friction and
        // five attached limbs anchor it, and the spin is re-absorbed every tick (angular
        // velocity held at 4 rad/s while the body moved 0.0006 blocks over 4 ticks). Adding
        // velocity fails for the same reason from the other side: the contact solver's
        // friction budget is proportional to the normal impulse and comfortably exceeds a
        // walking-pace push, so it cancels the injection inside the same tick.
        //
        // A player dragging themselves along is levering against the ground, not sliding on
        // it, so the body is stepped directly. This runs before the solve, so the substep
        // integrator captures it as the new resting position rather than as a velocity
        // spike, and contacts still stop the body dead against a wall.
        double step = TumbleConfig.SERVER.rollSpeed.get() / 20.0D;
        Vector3d axis = new Vector3d(0.0D, 1.0D, 0.0D).cross(direction);

        for (BodyPart part : BodyPart.values()) {
            RigidBody body = skeleton.part(part);
            body.pos.x += direction.x * step;
            body.pos.z += direction.z * step;
        }

        // Cosmetic only: makes the body tumble along instead of gliding.
        skeleton.torso().angVel.fma(TumbleConfig.SERVER.rollSpin.get(), axis);
    }

    /** Hurts the rider when their body is slammed into something hard. */
    private void applyImpactDamage() {
        TumbleConfig.Server config = TumbleConfig.SERVER;
        if (!config.impactDamageEnabled.get()) {
            return;
        }
        if (impactDamageCooldown > 0) {
            impactDamageCooldown--;
            return;
        }
        if (!(getFirstPassenger() instanceof Player rider)) {
            return;
        }

        double threshold = config.impactDamageThreshold.get();
        double impact = skeleton.lastImpact();
        if (impact < threshold) {
            return;
        }

        double damage = Math.min((impact - threshold) * config.impactDamageMultiplier.get(),
                config.impactDamageMax.get());
        if (damage <= 0.0D) {
            return;
        }

        // A vanilla damage type, so no data-driven damage type registration is needed.
        // Being hurt while already down cannot start another ragdoll, because a player
        // with a vehicle is never eligible.
        rider.hurt(damageSources().flyIntoWall(), (float) damage);
        impactDamageCooldown = config.impactDamageCooldownTicks.get();
    }

    /** @return true when the ragdoll ended this tick */
    private boolean checkExpiry() {
        // A dead rider must not be left riding a body.
        if (getFirstPassenger() instanceof Player rider && !rider.isAlive()) {
            getUp();
            return true;
        }
        if (ticksAlive >= TumbleConfig.SERVER.safetyTimeoutTicks.get()) {
            getUp();
            return true;
        }
        if (TumbleConfig.SERVER.expireWhenSlow.get() && canGetUp()
                && skeleton.isSettled(TumbleConfig.SERVER.releaseSpeedThreshold.get())) {
            getUp();
            return true;
        }
        return false;
    }

    /** Throws the whole assembly, clamped so nothing leaves the world. */
    public void launch(Vec3 velocity) {
        if (skeleton == null) {
            return;
        }
        double cap = TumbleConfig.SERVER.maxLaunchSpeed.get();
        Vec3 clamped = velocity.length() > cap ? velocity.normalize().scale(cap) : velocity;
        skeleton.launch(new Vector3d(clamped.x, clamped.y, clamped.z));
    }

    /** True while this ragdoll is deliberately letting its rider go. */
    public boolean isReleasing() {
        return releasing;
    }

    /** Ends the ragdoll, standing the rider back up where the body came to rest. */
    public void getUp() {
        // The dismount guard blocks a player leaving early, so the mod has to announce
        // its own release or it would block itself.
        releasing = true;

        Entity rider = getFirstPassenger();
        if (rider != null) {
            rider.stopRiding();
            // Place them on their feet at the body, not inside it.
            rider.teleportTo(getX(), getY() + 0.1D, getZ());
            rider.setDeltaMovement(Vec3.ZERO);
        }
        discard();
    }

    /**
     * Tracks how long the body has been down.
     *
     * <p>The counter resets the moment the body leaves the ground, so a body that clips a
     * ledge on the way past, or bounces, has to settle properly before its player can
     * rise.
     */
    private void updateGrounded() {
        if (skeleton.isGrounded()) {
            groundedTicks++;
        } else {
            groundedTicks = 0;
        }
        entityData.set(CAN_GET_UP, canGetUp());
    }

    public boolean canGetUp() {
        if (ticksAlive < TumbleConfig.SERVER.minGetUpTicks.get()) {
            return false;
        }
        if (!TumbleConfig.SERVER.requireGroundedToGetUp.get()) {
            return true;
        }
        return groundedTicks >= TumbleConfig.SERVER.groundedTicksBeforeGetUp.get();
    }

    /** Client-visible copy of {@link #canGetUp()}, for the controls hint. */
    public boolean canGetUpSynced() {
        return entityData.get(CAN_GET_UP);
    }

    @Override
    protected void positionRider(Entity passenger, Entity.MoveFunction moveFunction) {
        if (!hasPassenger(passenger)) {
            return;
        }
        // Sit the rider just above the body's centre of mass.
        //
        // Not below it: a prone ragdoll's centre sits about a fifth of a block off the
        // ground, so an offset downwards puts the rider inside the floor, the third-person
        // camera collides with terrain and pulls in tight, and the player ends up staring
        // at their own head from two inches away.
        moveFunction.accept(passenger, getX(), getY() + 0.2D, getZ());
    }

    @Override
    public double getPassengersRidingOffset() {
        return 0.0D;
    }

    @Override
    public void lerpTo(double x, double y, double z, float yaw, float pitch, int steps, boolean teleport) {
        // Snap rather than interpolate, for the reason described in tick().
        setPos(x, y, z);
        setRot(yaw, pitch);
    }

    @Override
    public boolean isPickable() {
        return false;
    }

    @Override
    public boolean canBeCollidedWith() {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    /** Called by the client when a pose snapshot arrives. Offsets are relative to the anchor. */
    public void applyPose(double anchorX, double anchorY, double anchorZ,
                          Vector3f[] positions, Quaternionf[] rotations) {
        Vec3[] world = new Vec3[PART_COUNT];
        for (int i = 0; i < PART_COUNT; i++) {
            world[i] = new Vec3(
                    anchorX + positions[i].x,
                    anchorY + positions[i].y,
                    anchorZ + positions[i].z);
        }
        poseBuffer.push(world, rotations);
    }

    public boolean hasPose() {
        return poseBuffer.hasPose();
    }

    /**
     * Resolves the pose to draw this frame. Call once before reading any part, so the
     * renderer and the camera agree on the same instant.
     */
    public void samplePose(float partialTick) {
        poseBuffer.sample(partialTick);
    }

    /** World position of a part, from the sampled pose. */
    public Vec3 partWorldPosition(int index) {
        return poseBuffer.position(index);
    }

    /** Orientation of a part, from the sampled pose. */
    public Quaternionf partRotation(int index, Quaternionf dest) {
        return poseBuffer.rotation(index, dest);
    }

    /** Server-side body transform, used when building a pose packet. */
    public RigidBody body(int index) {
        return skeleton.part(BodyPart.values()[index]);
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        // Ragdolls are transient. A saved one has no physics state worth restoring, and
        // reviving it would leave a body with no rider lying in the world.
        discard();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public Packet<ClientGamePacketListener> getAddEntityPacket() {
        return NetworkHooks.getEntitySpawningPacket(this);
    }

    static {
        // Fail loudly during development if the model and the packet disagree.
        if (PART_COUNT != 6) {
            Tumble.LOGGER.warn("Ragdoll part count is {}, expected 6", PART_COUNT);
        }
    }
}
