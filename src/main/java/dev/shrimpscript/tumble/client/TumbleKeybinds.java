package dev.shrimpscript.tumble.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class TumbleKeybinds {

    public static final String CATEGORY = "key.categories.tumble";

    public static final KeyMapping RAGDOLL = new KeyMapping(
            "key.tumble.ragdoll",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_H,
            CATEGORY);

    /** Hold to take hold of a nearby body and drag it around. */
    public static final KeyMapping GRAB = new KeyMapping(
            "key.tumble.grab",
            InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_G,
            CATEGORY);

    private TumbleKeybinds() {
    }
}
