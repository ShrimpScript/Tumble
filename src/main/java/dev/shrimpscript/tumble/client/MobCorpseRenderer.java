package dev.shrimpscript.tumble.client;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.shrimpscript.tumble.entity.MobCorpseEntity;
import dev.shrimpscript.tumble.ragdoll.BodyPart;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws a dead mob's body using that mob's own model and texture.
 *
 * <p>Rather than shipping a table of models and textures per mob, this asks the game:
 * it borrows the renderer Minecraft already uses for that entity type, and uses its
 * model if that model is a humanoid one. That keeps modded humanoids working for free,
 * and means a resource pack applies to a body exactly as it does to the living mob.
 *
 * <p>Getting a texture out of a renderer needs an instance of the entity, so one
 * throwaway instance per type is created and cached. It is never added to the world.
 */
public class MobCorpseRenderer extends EntityRenderer<MobCorpseEntity> {

    /** Baby mobs are drawn at roughly half scale, and their bodies should match. */
    private static final float BABY_SCALE = 0.5F;

    private final Map<EntityType<?>, LivingEntity> probes = new HashMap<>();

    public MobCorpseRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(MobCorpseEntity entity) {
        ResourceLocation texture = resolveTexture(entity);
        return texture == null ? new ResourceLocation("minecraft", "textures/entity/zombie/zombie.png") : texture;
    }

    /** A throwaway instance of the source mob, used only to interrogate its renderer. */
    private LivingEntity probe(MobCorpseEntity entity) {
        EntityType<?> type = entity.sourceType().orElse(null);
        if (type == null || Minecraft.getInstance().level == null) {
            return null;
        }
        return probes.computeIfAbsent(type, t -> {
            Entity created = t.create(Minecraft.getInstance().level);
            return created instanceof LivingEntity living ? living : null;
        });
    }

    @SuppressWarnings("unchecked")
    private LivingEntityRenderer<LivingEntity, HumanoidModel<LivingEntity>> sourceRenderer(MobCorpseEntity entity) {
        LivingEntity probe = probe(entity);
        if (probe == null) {
            return null;
        }
        EntityRenderer<?> renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(probe);
        if (!(renderer instanceof LivingEntityRenderer<?, ?> living)) {
            return null;
        }
        if (!(living.getModel() instanceof HumanoidModel<?>)) {
            return null;
        }
        return (LivingEntityRenderer<LivingEntity, HumanoidModel<LivingEntity>>) living;
    }

    private ResourceLocation resolveTexture(MobCorpseEntity entity) {
        LivingEntityRenderer<LivingEntity, HumanoidModel<LivingEntity>> renderer = sourceRenderer(entity);
        LivingEntity probe = probe(entity);
        return renderer == null || probe == null ? null : renderer.getTextureLocation(probe);
    }

    @Override
    public void render(MobCorpseEntity entity, float entityYaw, float partialTick,
                       PoseStack poseStack, MultiBufferSource buffer, int light) {
        if (!entity.hasPose()) {
            return;
        }

        LivingEntityRenderer<LivingEntity, HumanoidModel<LivingEntity>> renderer = sourceRenderer(entity);
        if (renderer == null) {
            // Not a humanoid, or the type is unknown. Drawing nothing beats drawing a
            // zombie in place of whatever actually died.
            return;
        }

        HumanoidModel<LivingEntity> model = renderer.getModel();
        entity.samplePose(partialTick);

        VertexConsumer consumer = buffer.getBuffer(
                RenderType.entityCutoutNoCull(getTextureLocation(entity)));

        double renderX = Mth.lerp(partialTick, entity.xOld, entity.getX());
        double renderY = Mth.lerp(partialTick, entity.yOld, entity.getY());
        double renderZ = Mth.lerp(partialTick, entity.zOld, entity.getZ());

        float scale = entity.isBaby() ? BABY_SCALE : 1.0F;
        Quaternionf rotation = new Quaternionf();

        for (BodyPart part : BodyPart.values()) {
            Vec3 world = entity.partWorldPosition(part.ordinal());
            entity.partRotation(part.ordinal(), rotation);

            poseStack.pushPose();
            poseStack.translate(world.x - renderX, world.y - renderY, world.z - renderZ);
            poseStack.mulPose(rotation);
            poseStack.scale(-scale, -scale, scale);

            Vector3f centre = cuboidCentre(part);
            poseStack.translate(-centre.x / 16.0F, -centre.y / 16.0F, -centre.z / 16.0F);

            renderPart(modelPart(model, part), poseStack, consumer, light);

            poseStack.popPose();
        }

        super.render(entity, entityYaw, partialTick, poseStack, buffer, light);
    }

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
     * Cuboid centres for Minecraft's humanoid model, in model pixels.
     *
     * <p>These are the shared humanoid values. A few mobs narrow their limbs - a skeleton's
     * arms are two pixels wide rather than four - which shifts those centres by half a
     * pixel. The offset is a thirty-second of a block and is not worth reading each
     * model's private cube data to correct.
     */
    private static Vector3f cuboidCentre(BodyPart part) {
        return switch (part) {
            case HEAD -> new Vector3f(0.0F, -4.0F, 0.0F);
            case TORSO -> new Vector3f(0.0F, 6.0F, 0.0F);
            case ARM_LEFT -> new Vector3f(1.0F, 4.0F, 0.0F);
            case ARM_RIGHT -> new Vector3f(-1.0F, 4.0F, 0.0F);
            case LEG_LEFT, LEG_RIGHT -> new Vector3f(0.0F, 6.0F, 0.0F);
        };
    }
}
