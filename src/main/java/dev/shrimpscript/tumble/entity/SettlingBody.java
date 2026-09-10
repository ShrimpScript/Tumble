package dev.shrimpscript.tumble.entity;

import dev.shrimpscript.tumble.config.TumbleConfig;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * A body that has been left behind: it falls, settles, and then stops costing anything.
 *
 * <p>Shared by player corpses and mob bodies, which differ only in what they carry and
 * how long they last. The interesting part is the freeze: simulating a body that has
 * stopped moving is pure waste, but a body that can <em>never</em> move again is scenery
 * rather than a ragdoll, so a frozen one still checks now and then whether the ground it
 * was lying on is still there.
 */
public abstract class SettlingBody extends RagdollEntity {

    /**
     * How often a frozen body checks that it is still supported. One step a second is a
     * twentieth of the cost of simulating it, and far quicker than anyone can dig.
     */
    private static final int WAKE_CHECK_INTERVAL = 20;

    private boolean frozen;
    private int settledTicks;
    private int wakeCheck;
    private int age;

    protected SettlingBody(EntityType<? extends SettlingBody> type, Level level) {
        super(type, level);
    }

    public int age() {
        return age;
    }

    protected void setAge(int age) {
        this.age = age;
    }

    protected boolean isFrozen() {
        return frozen;
    }

    protected void setFrozen(boolean frozen) {
        this.frozen = frozen;
    }

    @Override
    protected void serverTick() {
        age++;

        // Being dragged is motion, so a held body must not freeze under someone's hand.
        if (isGrabbed()) {
            wake();
        }

        if (frozen) {
            checkStillSupported();
        } else {
            applyGrab();
            stepPhysics();
            sendPose();

            if (!isGrabbed()
                    && skeleton().isSettled(TumbleConfig.SERVER.releaseSpeedThreshold.get())) {
                settledTicks++;
                if (settledTicks >= TumbleConfig.SERVER.corpseFreezeTicks.get()) {
                    frozen = true;
                }
            } else {
                settledTicks = 0;
            }
        }

        checkLifetime();
    }

    /** Steps once in a while to notice if the ground it was lying on has gone. */
    private void checkStillSupported() {
        if (++wakeCheck < WAKE_CHECK_INTERVAL) {
            return;
        }
        wakeCheck = 0;

        stepPhysics();
        sendPose();

        if (!skeleton().isGrounded()) {
            wake();
        }
    }

    /** Puts a frozen body back under simulation, for anything that disturbs it. */
    public void wake() {
        frozen = false;
        settledTicks = 0;
    }

    /** Throws the body and wakes it, so a blast moves it like it moves the living. */
    public void launchAndWake(Vec3 velocity) {
        wake();
        launch(velocity);
    }

    /** Decides when this body goes away. Called every tick. */
    protected abstract void checkLifetime();

    @Override
    public boolean shouldBeSaved() {
        return true;
    }

    @Override
    public boolean isPickable() {
        return true;
    }

    /**
     * A box around the whole body rather than the registered cube.
     *
     * <p>The entity sits at the centre of mass, but a player aims at the limbs they can
     * see, which sprawl about a block either side of it.
     */
    @Override
    protected AABB makeBoundingBox() {
        return new AABB(getX() - 0.7D, getY() - 0.5D, getZ() - 0.7D,
                getX() + 0.7D, getY() + 0.7D, getZ() + 0.7D);
    }
}
