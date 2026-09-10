package dev.shrimpscript.tumble.client;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.shrimpscript.tumble.entity.CorpseEntity;
import dev.shrimpscript.tumble.entity.RagdollEntity;
import dev.shrimpscript.tumble.ragdoll.BodyPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ArmorItem;
import net.minecraft.world.item.DyeableLeatherItem;
import net.minecraft.world.item.ItemStack;

/**
 * Draws a ragdoll's armour, so a player in full diamond does not go down looking like
 * someone else entirely.
 *
 * <p>Reuses vanilla's own armour models and textures with the same per-cuboid placement
 * the body model uses. Equipment is read straight off the owning player rather than
 * synced separately: the owner is riding the ragdoll, so they are always in range and
 * their equipment is already replicated.
 */
public final class RagdollEquipment {

    private RagdollEquipment() {
    }

    /** Which armour slot covers a given body part. Legs are covered twice. */
    private static EquipmentSlot primarySlot(BodyPart part) {
        return switch (part) {
            case HEAD -> EquipmentSlot.HEAD;
            case TORSO, ARM_LEFT, ARM_RIGHT -> EquipmentSlot.CHEST;
            case LEG_LEFT, LEG_RIGHT -> EquipmentSlot.LEGS;
        };
    }

    /**
     * Renders whatever armour covers this part, at the transform already on the stack.
     *
     * @param inner  the tighter model vanilla uses for leggings
     * @param outer  the model vanilla uses for everything else
     */
    public static void render(RagdollEntity entity, Player owner, BodyPart part,
                              PoseStack poseStack, MultiBufferSource buffer, int light,
                              HumanoidModel<?> inner, HumanoidModel<?> outer) {
        EquipmentSlot slot = primarySlot(part);
        // Leggings use the inner, slimmer model; everything else uses the outer one.
        boolean useInner = slot == EquipmentSlot.LEGS;

        renderPiece(equipment(entity, owner, slot), part, poseStack, buffer, light,
                useInner ? inner : outer, useInner);

        // A leg also carries a boot, drawn over the legging.
        if (part == BodyPart.LEG_LEFT || part == BodyPart.LEG_RIGHT) {
            renderPiece(equipment(entity, owner, EquipmentSlot.FEET), part, poseStack, buffer,
                    light, outer, false);
        }
    }

    /**
     * A corpse wears what it is carrying, not what its owner is wearing now. Reading the
     * live player would strip a body the moment its owner respawned in fresh gear.
     */
    private static ItemStack equipment(RagdollEntity entity, Player owner, EquipmentSlot slot) {
        if (entity instanceof CorpseEntity corpse) {
            return corpse.equipment(slot);
        }
        return owner == null ? ItemStack.EMPTY : owner.getItemBySlot(slot);
    }

    private static void renderPiece(ItemStack stack, BodyPart part, PoseStack poseStack,
                                    MultiBufferSource buffer, int light,
                                    HumanoidModel<?> model, boolean inner) {
        if (!(stack.getItem() instanceof ArmorItem armor)) {
            return;
        }

        ModelPart piece = modelPart(model, part);
        if (piece == null) {
            return;
        }

        // Leather is tinted by its dye, with an undyed overlay drawn on top.
        if (stack.getItem() instanceof DyeableLeatherItem dyeable) {
            int colour = dyeable.getColor(stack);
            draw(piece, poseStack, buffer, light, texture(armor, inner, null),
                    (colour >> 16 & 255) / 255.0F,
                    (colour >> 8 & 255) / 255.0F,
                    (colour & 255) / 255.0F);
            draw(piece, poseStack, buffer, light, texture(armor, inner, "overlay"), 1.0F, 1.0F, 1.0F);
            return;
        }

        draw(piece, poseStack, buffer, light, texture(armor, inner, null), 1.0F, 1.0F, 1.0F);
    }

    private static void draw(ModelPart piece, PoseStack poseStack, MultiBufferSource buffer,
                             int light, ResourceLocation texture, float red, float green, float blue) {
        piece.x = 0.0F;
        piece.y = 0.0F;
        piece.z = 0.0F;
        piece.xRot = 0.0F;
        piece.yRot = 0.0F;
        piece.zRot = 0.0F;
        piece.visible = true;
        piece.render(poseStack, buffer.getBuffer(RenderType.armorCutoutNoCull(texture)),
                light, OverlayTexture.NO_OVERLAY, red, green, blue, 1.0F);
    }

    private static ModelPart modelPart(HumanoidModel<?> model, BodyPart part) {
        return switch (part) {
            case HEAD -> model.head;
            case TORSO -> model.body;
            case ARM_LEFT -> model.leftArm;
            case ARM_RIGHT -> model.rightArm;
            case LEG_LEFT -> model.leftLeg;
            case LEG_RIGHT -> model.rightLeg;
        };
    }

    /**
     * Vanilla's armour texture naming: layer 2 is the slim leggings sheet, layer 1 is
     * everything else. Modded materials may carry their own namespace.
     */
    private static ResourceLocation texture(ArmorItem armor, boolean inner, String overlay) {
        String material = armor.getMaterial().getName();
        String namespace = "minecraft";

        int colon = material.indexOf(':');
        if (colon >= 0) {
            namespace = material.substring(0, colon);
            material = material.substring(colon + 1);
        }

        String path = "textures/models/armor/" + material + "_layer_" + (inner ? 2 : 1)
                + (overlay == null ? "" : "_" + overlay) + ".png";

        ResourceLocation location = new ResourceLocation(namespace, path);
        // Fall back to nothing rather than crashing on a material with no sheet.
        return Minecraft.getInstance().getResourceManager().getResource(location).isPresent()
                ? location
                : new ResourceLocation("minecraft", "textures/models/armor/leather_layer_1.png");
    }
}
