package dev.shrimpscript.tumble;

import com.mojang.logging.LogUtils;
import dev.shrimpscript.tumble.config.TumbleConfig;
import dev.shrimpscript.tumble.net.TumbleNetwork;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

@Mod(Tumble.MOD_ID)
public class Tumble {

    public static final String MOD_ID = "tumble";
    public static final Logger LOGGER = LogUtils.getLogger();

    public Tumble() {
        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModLoadingContext ctx = ModLoadingContext.get();

        // Both specs must be registered. Registering only some of them leaves the others
        // permanently unloaded, and the first read throws "Cannot get config value before
        // config is loaded" on every tick. See MEMORY.md.
        ctx.registerConfig(ModConfig.Type.SERVER, TumbleConfig.SERVER_SPEC);
        ctx.registerConfig(ModConfig.Type.CLIENT, TumbleConfig.CLIENT_SPEC);

        TumbleRegistry.ENTITY_TYPES.register(modBus);
        TumbleNetwork.register();

        LOGGER.info("Tumble loaded");
    }
}
