package dev.shrimpscript.tumble.entity;

import dev.shrimpscript.tumble.TumbleRegistry;
import dev.shrimpscript.tumble.config.TumbleConfig;
import dev.shrimpscript.tumble.physics.RigidBody;
import dev.shrimpscript.tumble.ragdoll.BodyPart;
import dev.shrimpscript.tumble.ragdoll.LimbPose;
import dev.shrimpscript.tumble.ragdoll.RagdollSkeleton;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.Container;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;

import java.util.Optional;
import java.util.UUID;

/**
 * What a player leaves behind when they die: their body, still carrying their things.
 *
 * <p>Shares the living ragdoll's physics and rendering, but almost nothing of its
 * lifecycle. A corpse has no rider, persists across a restart, and settles into a fixed
 * pose rather than being simulated forever - once it has stopped moving there is nothing
 * left to compute, so it stops stepping entirely and holds its final pose.
 */
public class CorpseEntity extends SettlingBody {

    /**
     * Sized to a vanilla six-row chest rather than to a player's 41 slots. Only threeRows
     * and sixRows accept a container, and 41 does not fit in three, so the corpse can be
     * opened with Minecraft's own container UI and needs no custom screen at all. Slots
     * 41 to 53 stay empty.
     */
    public static final int SLOT_COUNT = 54;

    private static final int ARMOUR_START = 36;
    private static final int OFFHAND_SLOT = 40;

    /**
     * What the body is visibly wearing and holding.
     *
     * <p>The container itself never reaches the client - a SimpleContainer is server-side
     * state - so a corpse rendered from it alone came out as a bare character. These six
     * slots are synced so the body looks like the player who died, and they update as it
     * is looted, so taking the helmet bares its head.
     */
    private static final EntityDataAccessor<ItemStack> WORN_HEAD = worn();
    private static final EntityDataAccessor<ItemStack> WORN_CHEST = worn();
    private static final EntityDataAccessor<ItemStack> WORN_LEGS = worn();
    private static final EntityDataAccessor<ItemStack> WORN_FEET = worn();
    private static final EntityDataAccessor<ItemStack> HELD_MAIN = worn();
    private static final EntityDataAccessor<ItemStack> HELD_OFF = worn();

    private static EntityDataAccessor<ItemStack> worn() {
        return SynchedEntityData.defineId(CorpseEntity.class, EntityDataSerializers.ITEM_STACK);
    }

    private final SimpleContainer contents = new SimpleContainer(SLOT_COUNT);

    /** Which hotbar slot the player was holding, so the body keeps holding the same thing. */
    private int mainHandSlot = -1;

    private String ownerName = "";

    /**
     * Whether this body ever held anything. Without it, a death under keepInventory
     * creates an empty corpse that despawns on its first tick.
     */
    private boolean everHadItems;

    public CorpseEntity(EntityType<? extends CorpseEntity> type, Level level) {
        super(type, level);
        // Looting through the container screen changes the contents without going through
        // this class, so the sync has to be driven by the container itself.
        contents.addListener(container -> syncEquipment());
    }

    @Override
    protected void defineSynchedData() {
        super.defineSynchedData();
        entityData.define(WORN_HEAD, ItemStack.EMPTY);
        entityData.define(WORN_CHEST, ItemStack.EMPTY);
        entityData.define(WORN_LEGS, ItemStack.EMPTY);
        entityData.define(WORN_FEET, ItemStack.EMPTY);
        entityData.define(HELD_MAIN, ItemStack.EMPTY);
        entityData.define(HELD_OFF, ItemStack.EMPTY);
    }

    /** Copies the visible slots out of the container and onto the wire. */
    private void syncEquipment() {
        if (level().isClientSide) {
            return;
        }
        entityData.set(WORN_FEET, contents.getItem(ARMOUR_START).copy());
        entityData.set(WORN_LEGS, contents.getItem(ARMOUR_START + 1).copy());
        entityData.set(WORN_CHEST, contents.getItem(ARMOUR_START + 2).copy());
        entityData.set(WORN_HEAD, contents.getItem(ARMOUR_START + 3).copy());
        entityData.set(HELD_OFF, contents.getItem(OFFHAND_SLOT).copy());
        entityData.set(HELD_MAIN, mainHandSlot >= 0 && mainHandSlot < ARMOUR_START
                ? contents.getItem(mainHandSlot).copy()
                : ItemStack.EMPTY);
    }

    /** Builds a corpse where a player just died, carrying the pose and motion they had. */
    public static CorpseEntity of(Player player, Vec3 momentum) {
        Level level = player.level();
        CorpseEntity corpse = new CorpseEntity(TumbleRegistry.CORPSE.get(), level);

        Vec3 feet = player.position();
        corpse.setPos(feet.x, feet.y, feet.z);
        corpse.setOwnerId(player.getUUID());
        corpse.setOwnerName(player.getGameProfile().getName());

        RagdollSkeleton skeleton = new RagdollSkeleton(feet.x, feet.y, feet.z,
                RagdollSkeleton.facingFromYaw(player.yBodyRot),
                TumbleConfig.SERVER.partSelfCollision.get(),
                LimbPose.of(player));
        skeleton.setCollider(new LevelCollider(level));
        skeleton.world().maxSpeed = TumbleConfig.SERVER.maxLaunchSpeed.get();
        skeleton.launch(new Vector3d(momentum.x, momentum.y, momentum.z));

        corpse.setSkeleton(skeleton);
        return corpse;
    }

    public SimpleContainer contents() {
        return contents;
    }

    public String ownerName() {
        return ownerName;
    }

    public void setOwnerName(String name) {
        this.ownerName = name;
    }

    /** Moves a dead player's entire inventory into the corpse. */
    public void takeInventoryFrom(Player player) {
        for (int i = 0; i < player.getInventory().items.size() && i < ARMOUR_START; i++) {
            contents.setItem(i, player.getInventory().items.get(i).copy());
            player.getInventory().items.set(i, ItemStack.EMPTY);
        }
        for (int i = 0; i < player.getInventory().armor.size(); i++) {
            contents.setItem(ARMOUR_START + i, player.getInventory().armor.get(i).copy());
            player.getInventory().armor.set(i, ItemStack.EMPTY);
        }
        contents.setItem(OFFHAND_SLOT, player.getInventory().offhand.get(0).copy());
        player.getInventory().offhand.set(0, ItemStack.EMPTY);

        mainHandSlot = player.getInventory().selected;
        everHadItems = !contents.isEmpty();
        syncEquipment();
    }

    public boolean isEmpty() {
        return contents.isEmpty();
    }

    @Override
    protected void checkLifetime() {
        if (everHadItems && isEmpty() && TumbleConfig.SERVER.corpseDespawnWhenEmpty.get()) {
            discard();
            return;
        }

        int lifetime = TumbleConfig.SERVER.corpseLifetimeTicks.get();
        // Zero means never time out, so a body is only ever removed once looted.
        if (lifetime > 0 && age() >= lifetime) {
            dropEverything();
            discard();
        }
    }

    /** Scatters what is left, so a timed-out corpse never silently eats someone's things. */
    private void dropEverything() {
        for (int i = 0; i < contents.getContainerSize(); i++) {
            ItemStack stack = contents.getItem(i);
            if (!stack.isEmpty()) {
                spawnAtLocation(stack);
                contents.setItem(i, ItemStack.EMPTY);
            }
        }
    }

    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (hand != InteractionHand.MAIN_HAND) {
            return InteractionResult.PASS;
        }
        if (level().isClientSide) {
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()) {
            equipOnto(player);
        } else {
            CorpseMenu.open(player, this);
        }
        return InteractionResult.CONSUME;
    }

    /**
     * Puts everything back where the dead player had it: armour on, off hand filled, and
     * each item into the slot it came from where that slot is still free.
     */
    public void equipOnto(Player player) {
        for (int i = 0; i < player.getInventory().armor.size(); i++) {
            moveInto(player, ARMOUR_START + i, () -> player.getInventory().armor, i);
        }
        moveInto(player, OFFHAND_SLOT, () -> player.getInventory().offhand, 0);

        for (int i = 0; i < ARMOUR_START; i++) {
            ItemStack stack = contents.getItem(i);
            if (stack.isEmpty()) {
                continue;
            }
            if (player.getInventory().items.get(i).isEmpty()) {
                player.getInventory().items.set(i, stack);
                contents.setItem(i, ItemStack.EMPTY);
            } else if (player.getInventory().add(stack)) {
                contents.setItem(i, ItemStack.EMPTY);
            }
        }

        checkLifetime();
    }

    private void moveInto(Player player, int slot,
                          java.util.function.Supplier<net.minecraft.core.NonNullList<ItemStack>> target,
                          int index) {
        ItemStack stack = contents.getItem(slot);
        if (stack.isEmpty()) {
            return;
        }
        if (target.get().get(index).isEmpty()) {
            target.get().set(index, stack);
            contents.setItem(slot, ItemStack.EMPTY);
        } else if (player.getInventory().add(stack)) {
            contents.setItem(slot, ItemStack.EMPTY);
        }
    }

    public Component displayTitle() {
        return Component.translatable("tumble.container.corpse", ownerName);
    }

    /** Corpses persist: a player logging back in must still find their body. */
    @Override
    public boolean shouldBeSaved() {
        return true;
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        ownerName = tag.getString("OwnerName");
        setAge(tag.getInt("Age"));
        everHadItems = tag.getBoolean("HadItems");
        mainHandSlot = tag.contains("MainHandSlot") ? tag.getInt("MainHandSlot") : -1;

        if (tag.hasUUID("Owner")) {
            setOwnerId(tag.getUUID("Owner"));
        }

        ListTag items = tag.getList("Items", 10);
        for (int i = 0; i < items.size(); i++) {
            CompoundTag entry = items.getCompound(i);
            int slot = entry.getByte("Slot") & 255;
            if (slot < contents.getContainerSize()) {
                contents.setItem(slot, ItemStack.of(entry));
            }
        }

        RagdollSkeleton skeleton = new RagdollSkeleton(getX(), getY(), getZ(), 0.0D,
                false, LimbPose.STANDING);
        skeleton.setCollider(new LevelCollider(level()));
        setSkeleton(skeleton);

        // Restore the pose it settled in, or a reloaded body would stand back up.
        BodyPose.read(tag, skeleton);

        setFrozen(true);
        syncEquipment();
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putString("OwnerName", ownerName);
        tag.putInt("Age", age());

        UUID owner = getOwnerId();
        if (owner != null) {
            tag.putUUID("Owner", owner);
        }

        ListTag items = new ListTag();
        for (int i = 0; i < contents.getContainerSize(); i++) {
            ItemStack stack = contents.getItem(i);
            if (!stack.isEmpty()) {
                CompoundTag entry = new CompoundTag();
                entry.putByte("Slot", (byte) i);
                stack.save(entry);
                items.add(entry);
            }
        }
        tag.put("Items", items);
        tag.putBoolean("HadItems", everHadItems);
        tag.putInt("MainHandSlot", mainHandSlot);

        BodyPose.write(tag, skeleton());
    }

    @Override
    public boolean isPickable() {
        // Unlike a living ragdoll, a corpse has to be clickable to be looted.
        return true;
    }

    /**
     * A box around the whole body rather than the registered 0.6 cube.
     *
     * <p>The entity sits at the centre of mass, but a player aims at the limbs they can
     * see, which sprawl about a block either side of it. A default box would leave most
     * of a visible body unclickable.
     */
    @Override
    protected AABB makeBoundingBox() {
        return new AABB(getX() - 0.7D, getY() - 0.5D, getZ() - 0.7D,
                getX() + 0.7D, getY() + 0.7D, getZ() + 0.7D);
    }

    /** Lets the container drop its contents if the corpse is destroyed some other way. */
    public Container container() {
        return contents;
    }

    public Optional<UUID> owner() {
        return Optional.ofNullable(getOwnerId());
    }

    /**
     * Equipment shown on the body. Read from synced data rather than the container, so it
     * is correct on the client, where the container does not exist.
     */
    public ItemStack equipment(EquipmentSlot slot) {
        return switch (slot) {
            case HEAD -> entityData.get(WORN_HEAD);
            case CHEST -> entityData.get(WORN_CHEST);
            case LEGS -> entityData.get(WORN_LEGS);
            case FEET -> entityData.get(WORN_FEET);
            case OFFHAND -> entityData.get(HELD_OFF);
            case MAINHAND -> entityData.get(HELD_MAIN);
        };
    }
}
