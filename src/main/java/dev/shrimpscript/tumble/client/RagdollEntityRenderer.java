package dev.shrimpscript.tumble.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.shrimpscript.tumble.entity.CorpseEntity;
import dev.shrimpscript.tumble.entity.RagdollEntity;
import dev.shrimpscript.tumble.ragdoll.BodyPart;
import net.minecraft.client.Minecraft;
import com.mojang.math.Axis;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.renderer.ItemInHandRenderer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.item.ItemDisplayContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.client.player.AbstractClientPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.entity.player.Player;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.UUID;

/**
 * Draws the owner's player model with each cuboid placed at its own body's transform,
 * rather than at the pivot the model would normally use.
 *
 * <p>This works precisely because the ragdoll has one body per renderable cuboid. There
 * is no model surgery, no extra geometry and no custom mesh - the same six parts vanilla
 * draws, put somewhere else.
 */
public class RagdollEntityRenderer<T extends RagdollEntity> extends EntityRenderer<T> {

    private final PlayerModel<AbstractClientPlayer> wideModel;
    private final PlayerModel<AbstractClientPlayer> slimModel;

    /** Vanilla's two armour models: the slim one is used for leggings. */
    private final HumanoidModel<AbstractClientPlayer> innerArmor;
    private final HumanoidModel<AbstractClientPlayer> outerArmor;

    private final ItemInHandRenderer itemRenderer;

    public RagdollEntityRenderer(EntityRendererProvider.Context context) {
        super(context);
        this.wideModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false);
        this.slimModel = new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER_SLIM), true);
        this.innerArmor = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR));
        this.outerArmor = new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR));
        this.itemRenderer = context.getItemInHandRenderer();
    }

    /**
     * The owner's entry in the player list, which is the reliable source for a skin.
     *
     * <p>Looking the player entity up by uuid fails around a death: the dying entity is
     * replaced when the player respawns, so a corpse would fall back to the default skin
     * and only correct itself once its owner came back. The player list entry survives
     * both, and exists for players who are nowhere near their body.
     */
    private PlayerInfo playerInfo(T entity) {
        UUID id = entity.getOwnerId();
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        return id == null || connection == null ? null : connection.getPlayerInfo(id);
    }

    @Override
    public ResourceLocation getTextureLocation(T entity) {
        PlayerInfo info = playerInfo(entity);
        if (info != null) {
            return info.getSkinLocation();
        }

        AbstractClientPlayer owner = owner(entity);
        if (owner != null) {
            return owner.getSkinTextureLocation();
        }
        UUID id = entity.getOwnerId();
        return id == null ? DefaultPlayerSkin.getDefaultSkin() : DefaultPlayerSkin.getDefaultSkin(id);
    }

    private static AbstractClientPlayer owner(RagdollEntity entity) {
        UUID id = entity.getOwnerId();
        if (id == null || Minecraft.getInstance().level == null) {
            return null;
        }
        Player player = Minecraft.getInstance().level.getPlayerByUUID(id);
        return player instanceof AbstractClientPlayer client ? client : null;
    }

    private boolean isSlim(T entity) {
        PlayerInfo info = playerInfo(entity);
        if (info != null) {
            return "slim".equals(info.getModelName());
        }
        AbstractClientPlayer owner = owner(entity);
        if (owner != null) {
            return "slim".equals(owner.getModelName());
        }
        UUID id = entity.getOwnerId();
        return id != null && "slim".equals(DefaultPlayerSkin.getSkinModelName(id));
    }

    @Override
    public void render(T entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int light) {
        if (!entity.hasPose()) {
            return;
        }

        // Resolve the pose once, so every limb and the camera agree on one instant.
        entity.samplePose(partialTick);

        boolean slim = isSlim(entity);
        PlayerModel<AbstractClientPlayer> model = slim ? slimModel : wideModel;
        VertexConsumer consumer = buffer.getBuffer(RenderType.entityTranslucent(getTextureLocation(entity)));

        Quaternionf rotation = new Quaternionf();

        // Where the dispatcher has already translated the pose stack to. Limb positions
        // arrive in world space, so this is subtracted back out; that keeps the body
        // rigid even when the entity's own position sync lags.
        double renderX = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double renderY = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double renderZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());

        // When the camera is inside this ragdoll's head, drawing the head would fill the
        // screen with the inside of a cuboid. Vanilla hides the player model in first
        // person for the same reason.
        Player local = Minecraft.getInstance().player;
        boolean hideHead = local != null && entity == local.getVehicle() && RagdollCamera.isActive();

        BodyPart[] parts = BodyPart.values();
        for (int i = 0; i < parts.length; i++) {
            BodyPart part = parts[i];
            if (hideHead && part == BodyPart.HEAD) {
                continue;
            }

            Vec3 world = entity.partWorldPosition(i);
            entity.partRotation(i, rotation);

            poseStack.pushPose();
            poseStack.translate(world.x - renderX, world.y - renderY, world.z - renderZ);
            poseStack.mulPose(rotation);

            // Into model space. Vanilla draws humanoids with X and Y inverted, which is
            // also what makes the skin texture come out the right way round.
            poseStack.scale(-1.0F, -1.0F, 1.0F);

            // Put the cuboid's own centre on the body's origin. Without this the part
            // would hang off its pivot, which for an arm is the shoulder.
            Vector3f centre = cuboidCentre(part, slim);
            poseStack.translate(-centre.x / 16.0F, -centre.y / 16.0F, -centre.z / 16.0F);

            renderPart(basePart(model, part), poseStack, consumer, light);
            renderPart(overlayPart(model, part), poseStack, consumer, light);

            // The stack is now at the part's own pivot in model space, which is exactly
            // where vanilla draws armour and held items from.
            RagdollEquipment.render(entity, owner(entity), part, poseStack, buffer, light,
                    innerArmor, outerArmor);
            renderHeldItem(entity, part, poseStack, buffer, light);

            poseStack.popPose();
        }

        super.render(entity, entityYaw, partialTick, poseStack, buffer, light);
    }

    /**
     * Draws whatever the owner is holding, at the hand.
     *
     * <p>Follows vanilla's ItemInHandLayer from the arm's pivot: the same rotations and
     * the same offset to the fist, so an item sits in the hand rather than floating.
     */
    private void renderHeldItem(T entity, BodyPart part, PoseStack poseStack,
                                MultiBufferSource buffer, int light) {
        if (part != BodyPart.ARM_LEFT && part != BodyPart.ARM_RIGHT) {
            return;
        }
        AbstractClientPlayer owner = owner(entity);
        if (owner == null && !(entity instanceof CorpseEntity)) {
            return;
        }

        boolean leftArm = part == BodyPart.ARM_LEFT;
        // An offline owner leaves no handedness to read, so assume the common case.
        HumanoidArm mainArm = owner == null ? HumanoidArm.RIGHT : owner.getMainArm();
        boolean isMainHand = (mainArm == HumanoidArm.LEFT) == leftArm;

        // A corpse holds what it is carrying. Reading the owner would show whatever they
        // picked up after respawning, or nothing at all.
        ItemStack stack;
        if (entity instanceof CorpseEntity corpse) {
            stack = corpse.equipment(isMainHand ? EquipmentSlot.MAINHAND : EquipmentSlot.OFFHAND);
        } else {
            stack = isMainHand ? owner.getMainHandItem() : owner.getOffhandItem();
        }

        if (stack.isEmpty()) {
            return;
        }

        poseStack.pushPose();
        poseStack.mulPose(Axis.XP.rotationDegrees(-90.0F));
        poseStack.mulPose(Axis.YP.rotationDegrees(180.0F));
        poseStack.translate((leftArm ? -1.0F : 1.0F) / 16.0F, 0.125F, -0.625F);

        itemRenderer.renderItem(owner != null ? owner : Minecraft.getInstance().player, stack,
                leftArm ? ItemDisplayContext.THIRD_PERSON_LEFT_HAND
                        : ItemDisplayContext.THIRD_PERSON_RIGHT_HAND,
                leftArm, poseStack, buffer, light);

        poseStack.popPose();
    }

    /** Neutralises the model's own pose so the cubes land where the physics put them. */
    private static void renderPart(ModelPart part, PoseStack poseStack, VertexConsumer consumer, int light) {
        if (part == null) {
            return;
        }
        part.x = 0.0F;
        part.y = 0.0F;
        part.z = 0.0F;
        part.xRot = 0.0F;
        part.yRot = 0.0F;
        part.zRot = 0.0F;
        part.visible = true;
        part.render(poseStack, consumer, light, OverlayTexture.NO_OVERLAY);
    }

    private static ModelPart basePart(PlayerModel<AbstractClientPlayer> model, BodyPart part) {
        return switch (part) {
            case HEAD -> model.head;
            case TORSO -> model.body;
            case ARM_LEFT -> model.leftArm;
            case ARM_RIGHT -> model.rightArm;
            case LEG_LEFT -> model.leftLeg;
            case LEG_RIGHT -> model.rightLeg;
        };
    }

    /** The outer skin layer: hair, jacket, sleeves, trouser legs. */
    private static ModelPart overlayPart(PlayerModel<AbstractClientPlayer> model, BodyPart part) {
        return switch (part) {
            case HEAD -> model.hat;
            case TORSO -> model.jacket;
            case ARM_LEFT -> model.leftSleeve;
            case ARM_RIGHT -> model.rightSleeve;
            case LEG_LEFT -> model.leftPants;
            case LEG_RIGHT -> model.rightPants;
        };
    }

    /**
     * Centre of each cuboid in its own part's local space, in model pixels.
     *
     * <p>These are vanilla's humanoid model constants. A head cube spans y -8..0 about
     * its pivot, a body 0..12, and an arm or leg -2..10 and 0..12 respectively; the slim
     * model's arms are three pixels wide instead of four, which shifts their centre by
     * half a pixel.
     */
    private static Vector3f cuboidCentre(BodyPart part, boolean slim) {
        return switch (part) {
            case HEAD -> new Vector3f(0.0F, -4.0F, 0.0F);
            case TORSO -> new Vector3f(0.0F, 6.0F, 0.0F);
            case ARM_LEFT -> new Vector3f(slim ? 0.5F : 1.0F, 4.0F, 0.0F);
            case ARM_RIGHT -> new Vector3f(slim ? -0.5F : -1.0F, 4.0F, 0.0F);
            case LEG_LEFT, LEG_RIGHT -> new Vector3f(0.0F, 6.0F, 0.0F);
        };
    }
}
