package dev.shrimpscript.tumble.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.shrimpscript.tumble.Tumble;
import dev.shrimpscript.tumble.config.ConfigRegistry;
import dev.shrimpscript.tumble.menu.ConfigMenu;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side control of every setting the mod has.
 *
 * <p>Gated at permission level 2, the same bar vanilla uses for gamerules and other
 * commands that change how a world behaves: on a server only operators can reach any of
 * it, and in single player it is available once cheats are on.
 *
 * <p>Every option is discovered from {@link ConfigRegistry}, so the completions and the
 * value parsing follow the config automatically rather than being restated here.
 */
@Mod.EventBusSubscriber(modid = Tumble.MOD_ID)
public final class TumbleCommand {

    /** The same level vanilla requires for /gamerule. */
    private static final int PERMISSION_LEVEL = 2;

    /**
     * Only server settings are reachable from here.
     *
     * <p>Client settings belong to each player's own game, and on a dedicated server the
     * client file is never loaded at all, so reading one would throw.
     */
    private static List<ConfigRegistry.Entry> settings() {
        return ConfigRegistry.entries().stream().filter(ConfigRegistry.Entry::serverSide).toList();
    }

    private static final SuggestionProvider<CommandSourceStack> SETTINGS =
            (context, builder) -> SharedSuggestionProvider.suggest(
                    settings().stream().map(ConfigRegistry.Entry::path).toList(), builder);

    private TumbleCommand() {
    }

    @SubscribeEvent
    public static void onRegister(RegisterCommandsEvent event) {
        register(event.getDispatcher());
    }

    private static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        // The menu itself is open to everyone: it is the only way a player can reach
        // their own display settings in game, and it refuses every server setting to
        // anyone without the permission level. Editing by command is operators only.
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("tumble");

        root.then(Commands.literal("config")
                .executes(TumbleCommand::openMenu));

        root.then(Commands.literal("list")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .executes(context -> list(context, null))
                .then(Commands.argument("section", StringArgumentType.word())
                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                sections().keySet(), builder))
                        .executes(context -> list(context, StringArgumentType.getString(context, "section")))));

        root.then(Commands.literal("get")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.argument("setting", StringArgumentType.string())
                        .suggests(SETTINGS)
                        .executes(TumbleCommand::get)));

        root.then(Commands.literal("set")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.argument("setting", StringArgumentType.string())
                        .suggests(SETTINGS)
                        // Numbers and booleans are separate branches so Brigadier can
                        // validate and complete each properly rather than taking a string.
                        .then(Commands.argument("number", DoubleArgumentType.doubleArg())
                                .executes(context -> set(context, DoubleArgumentType.getDouble(context, "number"))))
                        .then(Commands.argument("flag", BoolArgumentType.bool())
                                .executes(context -> set(context, BoolArgumentType.getBool(context, "flag"))))
                        .then(Commands.argument("text", StringArgumentType.greedyString())
                                .executes(context -> set(context, StringArgumentType.getString(context, "text"))))));

        root.then(Commands.literal("reset")
                .requires(source -> source.hasPermission(PERMISSION_LEVEL))
                .then(Commands.literal("all").executes(TumbleCommand::resetAll))
                .then(Commands.argument("setting", StringArgumentType.string())
                        .suggests(SETTINGS)
                        .executes(TumbleCommand::reset)));

        dispatcher.register(root);
    }

    private static int openMenu(CommandContext<CommandSourceStack> context) {
        ServerPlayer player;
        try {
            player = context.getSource().getPlayerOrException();
        } catch (Exception notAPlayer) {
            context.getSource().sendFailure(Component.literal("Only a player can open the settings menu."));
            return 0;
        }

        ConfigMenu.open(player);
        return 1;
    }

    /** Sections that hold at least one server setting, in declaration order. */
    private static Map<String, List<ConfigRegistry.Entry>> sections() {
        Map<String, List<ConfigRegistry.Entry>> grouped = new LinkedHashMap<>();
        for (ConfigRegistry.Entry entry : settings()) {
            grouped.computeIfAbsent(entry.section(), key -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    private static int list(CommandContext<CommandSourceStack> context, String section) {
        Map<String, List<ConfigRegistry.Entry>> grouped = sections();

        if (section != null && !grouped.containsKey(section)) {
            context.getSource().sendFailure(Component.literal("No section called " + section + "."));
            return 0;
        }

        grouped.forEach((name, entries) -> {
            if (section != null && !section.equals(name)) {
                return;
            }
            context.getSource().sendSuccess(() ->
                    Component.literal(name).withStyle(ChatFormatting.GOLD), false);

            for (ConfigRegistry.Entry entry : entries) {
                Object value = ConfigRegistry.get(entry);
                boolean changed = !String.valueOf(value).equals(String.valueOf(entry.defaultValue()));

                context.getSource().sendSuccess(() -> Component.literal("  " + entry.path() + " = ")
                        .withStyle(ChatFormatting.GRAY)
                        .append(Component.literal(ConfigRegistry.display(value))
                                .withStyle(changed ? ChatFormatting.YELLOW : ChatFormatting.WHITE)), false);
            }
        });
        return 1;
    }

    private static int get(CommandContext<CommandSourceStack> context) {
        ConfigRegistry.Entry entry = require(context);
        if (entry == null) {
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal(entry.path() + " = ")
                .withStyle(ChatFormatting.GRAY)
                .append(Component.literal(ConfigRegistry.display(ConfigRegistry.get(entry)))
                        .withStyle(ChatFormatting.WHITE))
                .append(Component.literal("  (default " + ConfigRegistry.display(entry.defaultValue()) + ")")
                        .withStyle(ChatFormatting.DARK_GRAY)), false);

        if (entry.comment() != null) {
            for (String line : entry.comment().split("\n")) {
                context.getSource().sendSuccess(() ->
                        Component.literal("  " + line).withStyle(ChatFormatting.DARK_GRAY), false);
            }
        }
        return 1;
    }

    private static int set(CommandContext<CommandSourceStack> context, Object raw) {
        ConfigRegistry.Entry entry = require(context);
        if (entry == null) {
            return 0;
        }

        Object value = coerce(entry, raw);
        if (value == null) {
            context.getSource().sendFailure(Component.literal(
                    entry.path() + " expects " + entry.kind().name().toLowerCase() + "."));
            return 0;
        }

        String error = ConfigRegistry.set(entry, value);
        if (error != null) {
            context.getSource().sendFailure(Component.literal(entry.path() + ": " + error));
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal("Set ")
                .append(Component.literal(entry.path()).withStyle(ChatFormatting.GRAY))
                .append(Component.literal(" to " + ConfigRegistry.display(value))
                        .withStyle(ChatFormatting.YELLOW)), true);
        return 1;
    }

    /** Turns a loosely typed command argument into the type the setting actually holds. */
    private static Object coerce(ConfigRegistry.Entry entry, Object raw) {
        return switch (entry.kind()) {
            case BOOLEAN -> raw instanceof Boolean flag ? flag
                    : raw instanceof String text ? Boolean.parseBoolean(text) : null;
            case INTEGER -> raw instanceof Number number ? (int) Math.round(number.doubleValue()) : null;
            case DECIMAL -> raw instanceof Number number ? number.doubleValue() : null;
            case STRING_LIST -> raw instanceof String text
                    ? List.of(text.split("\\s*,\\s*"))
                    : null;
            case STRING -> String.valueOf(raw);
        };
    }

    private static int reset(CommandContext<CommandSourceStack> context) {
        ConfigRegistry.Entry entry = require(context);
        if (entry == null) {
            return 0;
        }

        String error = ConfigRegistry.reset(entry);
        if (error != null) {
            context.getSource().sendFailure(Component.literal(entry.path() + ": " + error));
            return 0;
        }

        context.getSource().sendSuccess(() -> Component.literal("Reset " + entry.path()
                + " to " + ConfigRegistry.display(entry.defaultValue())), true);
        return 1;
    }

    private static int resetAll(CommandContext<CommandSourceStack> context) {
        int count = 0;
        for (ConfigRegistry.Entry entry : ConfigRegistry.entries()) {
            if (entry.serverSide() && ConfigRegistry.reset(entry) == null) {
                count++;
            }
        }

        int total = count;
        context.getSource().sendSuccess(() ->
                Component.literal("Reset " + total + " settings to their defaults."), true);
        return 1;
    }

    private static ConfigRegistry.Entry require(CommandContext<CommandSourceStack> context) {
        String path = StringArgumentType.getString(context, "setting");
        ConfigRegistry.Entry entry = ConfigRegistry.find(path);
        if (entry == null || !entry.serverSide()) {
            context.getSource().sendFailure(Component.literal("No setting called " + path + "."));
            return null;
        }
        return entry;
    }
}
