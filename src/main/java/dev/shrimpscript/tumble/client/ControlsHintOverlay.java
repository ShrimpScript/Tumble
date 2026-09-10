package dev.shrimpscript.tumble.client;

import dev.shrimpscript.tumble.config.TumbleConfig;
import dev.shrimpscript.tumble.entity.RagdollEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.gui.overlay.ForgeGui;
import net.minecraftforge.client.gui.overlay.IGuiOverlay;

/**
 * A one-line reminder of what a downed player can still do.
 *
 * <p>Vanilla-style on purpose: Minecraft's own font, at the game's own GUI scale, with no
 * matrix scaling. Text is only ever crisp when its glyphs are drawn 1:1 into the space
 * their atlas was authored for - scaling the pose stack to make text bigger is what makes
 * mod HUDs look mushy next to vanilla's.
 *
 * <p>Vanilla already draws "Press Left Shift to Dismount" for any rider, so this sits
 * above that line rather than repeating it, and names the part vanilla cannot know about.
 */
public final class ControlsHintOverlay implements IGuiOverlay {

    public static final ControlsHintOverlay INSTANCE = new ControlsHintOverlay();

    /**
     * Clear of the hotbar and, crucially, of vanilla's own dismount prompt.
     *
     * <p>Vanilla draws that prompt around 74 above the bottom. At 75 the two lines land
     * within a pixel of each other and both become unreadable - measured from a
     * screenshot, which is the only way this kind of collision shows up.
     */
    private static final int BOTTOM_OFFSET = 88;

    private static final int TEXT_COLOUR = 0xFFFFFFFF;

    private ControlsHintOverlay() {
    }

    @Override
    public void render(ForgeGui gui, GuiGraphics graphics, float partialTick,
                       int screenWidth, int screenHeight) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.options.hideGui) {
            return;
        }
        if (!(minecraft.player.getVehicle() instanceof RagdollEntity ragdoll)) {
            return;
        }
        if (!TumbleConfig.CLIENT.showControlsHint.get()) {
            return;
        }

        // Offering a key that the server will ignore is worse than saying nothing, so the
        // hint only mentions getting up once it would actually work.
        Component line = ragdoll.canGetUpSynced()
                ? Component.translatable("tumble.hud.controls")
                : Component.translatable("tumble.hud.controls.down");

        // Integer coordinates only. A half-pixel offset is enough to blur every glyph.
        graphics.drawCenteredString(minecraft.font, line,
                screenWidth / 2, screenHeight - BOTTOM_OFFSET, TEXT_COLOUR);
    }
}
