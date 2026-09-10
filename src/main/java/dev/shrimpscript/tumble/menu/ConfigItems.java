package dev.shrimpscript.tumble.menu;

import dev.shrimpscript.tumble.config.ConfigRegistry;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds the items the settings menu is made of. */
final class ConfigItems {

    /**
     * Section icons.
     *
     * <p>Chosen so the index page can be read at a glance rather than by tooltip: TNT is
     * the triggers, a clock is the timers, a skull is the corpses.
     */
    private static final Map<String, Item> SECTION_ICONS = Map.ofEntries(
            Map.entry("general", Items.REDSTONE_TORCH),
            Map.entry("physics", Items.SLIME_BALL),
            Map.entry("controls", Items.LEAD),
            Map.entry("grab", Items.FISHING_ROD),
            Map.entry("expiry", Items.CLOCK),
            Map.entry("triggers", Items.TNT),
            Map.entry("suppressions", Items.SHIELD),
            Map.entry("impactDamage", Items.IRON_SWORD),
            Map.entry("corpse", Items.SKELETON_SKULL),
            Map.entry("mobCorpse", Items.ZOMBIE_HEAD),
            Map.entry("sound", Items.NOTE_BLOCK),
            Map.entry("display", Items.SPYGLASS));

    /** Roughly the width of a vanilla tooltip before it starts crowding the screen. */
    private static final int WRAP_WIDTH = 46;

    private ConfigItems() {
    }

    /**
     * A lore or name line.
     *
     * <p>Both are italic and oddly coloured by default, so every line sets its own style
     * rather than inheriting one.
     */
    static Component line(String text, ChatFormatting colour) {
        return Component.literal(text).setStyle(Style.EMPTY.withColor(colour).withItalic(false));
    }

    static ItemStack of(Item item, Component name, List<Component> lore) {
        ItemStack stack = new ItemStack(item);
        stack.setHoverName(name);

        if (!lore.isEmpty()) {
            ListTag tag = new ListTag();
            for (Component entry : lore) {
                tag.add(StringTag.valueOf(Component.Serializer.toJson(entry)));
            }
            stack.getOrCreateTagElement("display").put("Lore", tag);
        }
        return stack;
    }

    static ItemStack section(String name, int settings) {
        return of(SECTION_ICONS.getOrDefault(name, Items.PAPER),
                line(ConfigRegistry.humanise(name), ChatFormatting.WHITE),
                List.of(line(settings + (settings == 1 ? " setting" : " settings"),
                                ChatFormatting.DARK_GRAY),
                        Component.empty(),
                        line("Click to open", ChatFormatting.GRAY)));
    }

    static ItemStack setting(ConfigRegistry.Entry entry, String value, boolean editable,
                             boolean unavailable) {
        boolean changed = !unavailable && !value.equals(ConfigRegistry.encode(entry.defaultValue()));

        String title = entry.group().isEmpty()
                ? entry.label()
                : ConfigRegistry.humanise(entry.group()) + " · " + entry.label();

        List<Component> lore = new ArrayList<>();
        if (entry.comment() != null) {
            for (String comment : wrap(entry.comment())) {
                lore.add(line(comment, ChatFormatting.GRAY));
            }
        }
        lore.add(Component.empty());

        if (unavailable) {
            lore.add(line("Value unknown - your client has not reported it.",
                    ChatFormatting.RED));
        } else {
            Object decoded = ConfigRegistry.decode(entry, value);
            lore.add(line("Now: ", ChatFormatting.DARK_GRAY).copy().append(
                    line(ConfigRegistry.display(decoded == null ? value : decoded),
                            changed ? ChatFormatting.YELLOW : ChatFormatting.WHITE)));
            lore.add(line("Default: " + ConfigRegistry.display(entry.defaultValue()),
                    ChatFormatting.DARK_GRAY));
        }

        if (entry.min() != null) {
            lore.add(line("Range: " + ConfigRegistry.display(entry.min())
                    + " to " + ConfigRegistry.display(entry.max()), ChatFormatting.DARK_GRAY));
        }

        lore.add(Component.empty());
        if (!editable) {
            lore.add(line("Operators only.", ChatFormatting.RED));
        } else if (entry.kind() == ConfigRegistry.Kind.BOOLEAN) {
            lore.add(line("Click to toggle", ChatFormatting.GRAY));
            lore.add(line("Drop key to reset", ChatFormatting.DARK_GRAY));
        } else if (entry.min() != null) {
            lore.add(line("Left click raises · Right click lowers", ChatFormatting.GRAY));
            lore.add(line("Hold shift for ten times the step", ChatFormatting.DARK_GRAY));
            lore.add(line("Drop key to reset", ChatFormatting.DARK_GRAY));
        } else {
            lore.add(line("Change with /tumble set " + entry.path(), ChatFormatting.DARK_GRAY));
        }

        if (entry.serverSide()) {
            lore.add(line("Applies to everyone", ChatFormatting.DARK_GRAY));
        } else {
            lore.add(line("Applies to you only", ChatFormatting.DARK_GRAY));
        }

        return of(icon(entry, value, unavailable),
                line(title, changed ? ChatFormatting.YELLOW
                        : editable ? ChatFormatting.WHITE : ChatFormatting.GRAY),
                lore);
    }

    /**
     * Rewraps a config comment for a tooltip.
     *
     * <p>Minecraft does not wrap lore, so a line is exactly as wide as it was written.
     * The comments are wrapped for a text file, which is far wider than a tooltip wants
     * to be, so they are joined back up and broken again at a readable width.
     */
    private static List<String> wrap(String comment) {
        List<String> lines = new ArrayList<>();
        StringBuilder current = new StringBuilder();

        for (String word : comment.replace('\n', ' ').trim().split("\\s+")) {
            if (current.length() > 0 && current.length() + 1 + word.length() > WRAP_WIDTH) {
                lines.add(current.toString());
                current.setLength(0);
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(word);
        }
        if (current.length() > 0) {
            lines.add(current.toString());
        }
        return lines;
    }

    private static Item icon(ConfigRegistry.Entry entry, String value, boolean unavailable) {
        if (unavailable) {
            return Items.BARRIER;
        }
        return switch (entry.kind()) {
            case BOOLEAN -> Boolean.parseBoolean(value) ? Items.LIME_DYE : Items.GRAY_DYE;
            case INTEGER, DECIMAL -> Items.COMPARATOR;
            case STRING_LIST -> Items.WRITABLE_BOOK;
            case STRING -> Items.NAME_TAG;
        };
    }

    static ItemStack nav(Item item, String name, ChatFormatting colour, String... lore) {
        List<Component> lines = new ArrayList<>();
        for (String entry : lore) {
            lines.add(line(entry, ChatFormatting.DARK_GRAY));
        }
        return of(item, line(name, colour), lines);
    }
}
