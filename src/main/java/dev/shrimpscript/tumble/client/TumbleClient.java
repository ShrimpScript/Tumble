package dev.shrimpscript.tumble.client;

import dev.shrimpscript.tumble.Tumble;
import dev.shrimpscript.tumble.TumbleRegistry;
import dev.shrimpscript.tumble.config.TumbleConfig;
import dev.shrimpscript.tumble.entity.RagdollEntity;
import dev.shrimpscript.tumble.net.RagdollActionPacket;
import dev.shrimpscript.tumble.net.PlayerMotionPacket;
import dev.shrimpscript.tumble.net.RagdollGrabPacket;
import dev.shrimpscript.tumble.net.RagdollInputPacket;
import dev.shrimpscript.tumble.net.TumbleNetwork;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent;
import net.minecraftforge.client.event.RegisterKeyMappingsEvent;
import net.minecraftforge.client.event.RenderHandEvent;
import net.minecraftforge.client.event.RenderPlayerEvent;
import net.minecraftforge.client.event.ViewportEvent;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.client.gui.overlay.VanillaGuiOverlay;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

public final class TumbleClient {

    private TumbleClient() {
    }

    /** Mod-bus registration: keybinds and renderers. */
    @Mod.EventBusSubscriber(modid = Tumble.MOD_ID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
    public static final class Setup {

        @SubscribeEvent
        public static void registerKeys(RegisterKeyMappingsEvent event) {
            event.register(TumbleKeybinds.RAGDOLL);
            event.register(TumbleKeybinds.GRAB);
        }

        @SubscribeEvent
        public static void registerOverlays(RegisterGuiOverlaysEvent event) {
            event.registerAbove(VanillaGuiOverlay.HOTBAR.id(), "tumble_controls",
                    ControlsHintOverlay.INSTANCE);
        }

        @SubscribeEvent
        public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
            // Read the RegistryObject directly: this event fires before common setup, so
            // anything populated there is not available yet.
            event.registerEntityRenderer(TumbleRegistry.RAGDOLL.get(), RagdollEntityRenderer::new);
            event.registerEntityRenderer(TumbleRegistry.CORPSE.get(), RagdollEntityRenderer::new);
            event.registerEntityRenderer(TumbleRegistry.MOB_CORPSE.get(), MobCorpseRenderer::new);
        }
    }

    /** Forge-bus gameplay hooks. */
    @Mod.EventBusSubscriber(modid = Tumble.MOD_ID, value = Dist.CLIENT)
    public static final class Hooks {

        /** Speed, in blocks per second, above which motion is worth reporting. */
        private static final double REPORT_SPEED = 8.0D;

        /** Ticks to keep reporting after slowing down, so the stop itself is sent. */
        private static final int TAIL_TICKS = 5;

        private static int reportTail;

        @SubscribeEvent
        public static void onClientTick(TickEvent.ClientTickEvent event) {
            if (event.phase != TickEvent.Phase.END) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player == null || minecraft.level == null) {
                return;
            }

            boolean ragdolled = minecraft.player.getVehicle() instanceof RagdollEntity;

            reportMotion(minecraft, ragdolled);
            reportGrab();

            while (TumbleKeybinds.RAGDOLL.consumeClick()) {
                if (!ragdolled) {
                    TumbleNetwork.CHANNEL.sendToServer(
                            new RagdollActionPacket(RagdollActionPacket.Action.TUMBLE));
                }
            }

            if (ragdolled) {
                float strafe = minecraft.player.input.leftImpulse;
                float forward = minecraft.player.input.forwardImpulse;
                if (strafe != 0.0F || forward != 0.0F) {
                    TumbleNetwork.CHANNEL.sendToServer(new RagdollInputPacket(strafe, forward));
                }
            }

            if (ragdolled && minecraft.options.keyShift.isDown()) {
                TumbleNetwork.CHANNEL.sendToServer(
                        new RagdollActionPacket(RagdollActionPacket.Action.GET_UP));
            }
        }

        /** Whether the grab key was down last tick, so only changes are sent. */
        private static boolean grabbing;

        /**
         * Grabbing is a held key, but sending it every tick would be wasteful, so only the
         * press and the release go over the wire.
         */
        private static void reportGrab() {
            boolean down = TumbleKeybinds.GRAB.isDown();
            if (down == grabbing) {
                return;
            }
            grabbing = down;
            TumbleNetwork.CHANNEL.sendToServer(new RagdollGrabPacket(down));
        }

        /**
         * Feeds the impact trigger. The server cannot measure a player's speed accurately,
         * so the client reports it - but only while moving fast, plus a short tail so the
         * sudden stop that defines an impact is actually in the data.
         */
        private static void reportMotion(Minecraft minecraft, boolean ragdolled) {
            if (ragdolled) {
                reportTail = 0;
                return;
            }

            double speed = minecraft.player.getDeltaMovement().length() * 20.0D;
            if (speed >= REPORT_SPEED) {
                reportTail = TAIL_TICKS;
            } else if (reportTail > 0) {
                reportTail--;
            } else {
                return;
            }

            TumbleNetwork.CHANNEL.sendToServer(
                    new PlayerMotionPacket(minecraft.player.getDeltaMovement()));
        }

        @SubscribeEvent
        public static void onComputeCameraAngles(ViewportEvent.ComputeCameraAngles event) {
            RagdollCamera.onComputeCameraAngles(event);
        }

        /**
         * Hide vanilla's first-person viewmodel while ragdolled.
         *
         * <p>The hands and held item are drawn at a fixed place in front of the camera,
         * which made no sense once the camera is inside a tumbling head: the player saw
         * their real arms and item lying with the body and a second, static pair floating
         * over the top of them.
         */
        @SubscribeEvent
        public static void onRenderHand(RenderHandEvent event) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft.player != null && minecraft.player.getVehicle() instanceof RagdollEntity) {
                event.setCanceled(true);
            }
        }

        /**
         * Hide the standing player model while they are a ragdoll. Without this the
         * player renders upright inside their own flopped body.
         */
        @SubscribeEvent
        public static void onRenderPlayer(RenderPlayerEvent.Pre event) {
            if (event.getEntity().getVehicle() instanceof RagdollEntity) {
                event.setCanceled(true);
                return;
            }

            // Vanilla keeps drawing a dead player toppling over for its death animation,
            // which lands on top of the corpse: two bodies in one place, one tipping
            // through the other. The body is the death animation now.
            if (event.getEntity().deathTime > 0
                    && TumbleConfig.CLIENT.hideVanillaDeathAnimation.get()) {
                event.setCanceled(true);
            }
        }
    }
}
