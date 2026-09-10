package dev.shrimpscript.tumble.menu;

import dev.shrimpscript.tumble.config.ConfigRegistry;
import dev.shrimpscript.tumble.net.ClientSettings;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraftforge.network.NetworkHooks;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Every setting the mod has, as a chest menu.
 *
 * <p>Built on vanilla's six-row chest type rather than a menu type of its own, which means
 * there is no client-side code at all: the server decides what the items are and what a
 * click does, and any client draws it with the chest screen it already has. It also means
 * the menu cannot be lied to - a client can send clicks, but nothing it sends is trusted
 * past {@link #mayEdit}.
 *
 * <p>The rows are read from {@link ConfigRegistry}, so a setting added to the mod appears
 * here on its own, with its comment as the tooltip and its range enforced.
 */
public final class ConfigMenu extends ChestMenu {

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;
    private static final int PAGE_SIZE = 45;

    private static final int SLOT_BACK = 45;
    private static final int SLOT_PREVIOUS = 48;
    private static final int SLOT_INFO = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_CLOSE = 53;

    /** The level vanilla requires for /gamerule, and what this requires to change anything. */
    public static final int PERMISSION_LEVEL = 2;

    private final SimpleContainer display;
    private final ServerPlayer viewer;

    /** Null on the index page, otherwise the section being shown. */
    private String section;
    private int page;

    private ConfigMenu(int id, Inventory inventory, SimpleContainer display, ServerPlayer viewer) {
        super(MenuType.GENERIC_9x6, id, inventory, display, ROWS);
        this.display = display;
        this.viewer = viewer;
    }

    public static void open(ServerPlayer player) {
        NetworkHooks.openScreen(player, new SimpleMenuProvider((id, inventory, opener) -> {
            SimpleContainer container = new SimpleContainer(SIZE);
            ConfigMenu menu = new ConfigMenu(id, inventory, container, player);
            menu.rebuild();
            return menu;
        }, Component.literal("Tumble Settings")));

        // The client's own settings live on the client, so they have to be asked for. The
        // menu opens straight away and fills those rows in when the answer arrives.
        ClientSettings.request(player);
    }

    /** Redraws an open menu, used when a player's client reports its settings. */
    public void refresh() {
        rebuild();
    }

    // ---- contents -------------------------------------------------------------------

    private List<ConfigRegistry.Entry> sectionEntries() {
        return ConfigRegistry.bySection().getOrDefault(section, List.of());
    }

    private int pageCount() {
        return Math.max(1, (sectionEntries().size() + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    private void rebuild() {
        for (int slot = 0; slot < SIZE; slot++) {
            display.setItem(slot, ItemStack.EMPTY);
        }

        if (section == null) {
            buildIndex();
        } else {
            buildSection();
        }
        buildNavigation();

        // The client predicted whatever the click looked like to it, so the whole menu is
        // resent rather than only what changed.
        sendAllDataToRemote();
    }

    private void buildIndex() {
        int slot = 0;
        for (Map.Entry<String, List<ConfigRegistry.Entry>> entry : ConfigRegistry.bySection().entrySet()) {
            if (slot >= PAGE_SIZE) {
                break;
            }
            display.setItem(slot++, ConfigItems.section(entry.getKey(), entry.getValue().size()));
        }
    }

    private void buildSection() {
        List<ConfigRegistry.Entry> entries = sectionEntries();
        int from = Math.min(page * PAGE_SIZE, entries.size());
        int to = Math.min(from + PAGE_SIZE, entries.size());

        for (int i = from; i < to; i++) {
            ConfigRegistry.Entry entry = entries.get(i);
            String value = rawValue(entry);
            display.setItem(i - from,
                    ConfigItems.setting(entry, value == null ? "" : value,
                            mayEdit(entry), value == null));
        }
    }

    private void buildNavigation() {
        if (section != null) {
            display.setItem(SLOT_BACK, ConfigItems.nav(Items.ARROW, "Back",
                    ChatFormatting.WHITE, "To the section list"));

            display.setItem(SLOT_INFO, ConfigItems.nav(Items.BOOK,
                    ConfigRegistry.humanise(section), ChatFormatting.YELLOW,
                    "Page " + (page + 1) + " of " + pageCount()));

            if (page > 0) {
                display.setItem(SLOT_PREVIOUS, ConfigItems.nav(Items.SPECTRAL_ARROW,
                        "Previous page", ChatFormatting.WHITE));
            }
            if (page < pageCount() - 1) {
                display.setItem(SLOT_NEXT, ConfigItems.nav(Items.SPECTRAL_ARROW,
                        "Next page", ChatFormatting.WHITE));
            }
        } else {
            display.setItem(SLOT_INFO, ConfigItems.nav(Items.BOOK, "Tumble",
                    ChatFormatting.YELLOW,
                    viewer.hasPermissions(PERMISSION_LEVEL)
                            ? "Changes here apply to the whole world"
                            : "You may change your own display settings only"));
        }

        display.setItem(SLOT_CLOSE, ConfigItems.nav(Items.BARRIER, "Close",
                ChatFormatting.RED));
    }

    // ---- values ---------------------------------------------------------------------

    private boolean mayEdit(ConfigRegistry.Entry entry) {
        if (!entry.serverSide()) {
            return true;
        }
        return viewer.hasPermissions(PERMISSION_LEVEL);
    }

    /** Current value, or null for a client setting this player has not reported. */
    private String rawValue(ConfigRegistry.Entry entry) {
        if (entry.serverSide()) {
            return ConfigRegistry.encode(ConfigRegistry.get(entry));
        }
        return ClientSettings.get(viewer, entry.path());
    }

    private void write(ConfigRegistry.Entry entry, Object value) {
        if (entry.serverSide()) {
            String error = ConfigRegistry.set(entry, value);
            if (error != null) {
                viewer.sendSystemMessage(ConfigItems.line(
                        "Tumble: " + entry.path() + " " + error, ChatFormatting.RED));
            }
        } else {
            ClientSettings.set(viewer, entry.path(), ConfigRegistry.encode(value));
        }
    }

    /**
     * How much one click moves a number.
     *
     * <p>Scaled to the range, because these run from fractions of a block per second up to
     * twenty-minute timeouts and a single step cannot suit both.
     */
    private static double step(ConfigRegistry.Entry entry) {
        double span = entry.max() - entry.min();
        double base = span <= 2.0D ? 0.05D
                : span <= 20.0D ? 0.25D
                : span <= 100.0D ? 1.0D
                : span <= 2000.0D ? 10.0D
                : 50.0D;
        return entry.kind() == ConfigRegistry.Kind.INTEGER ? Math.max(1.0D, Math.rint(base)) : base;
    }

    private void adjust(ConfigRegistry.Entry entry, int direction, boolean coarse) {
        double current;
        try {
            current = Double.parseDouble(rawValue(entry));
        } catch (RuntimeException unreadable) {
            return;
        }

        double moved = current + direction * step(entry) * (coarse ? 10.0D : 1.0D);
        double clamped = Math.max(entry.min(), Math.min(entry.max(), moved));

        if (entry.kind() == ConfigRegistry.Kind.INTEGER) {
            write(entry, (int) Math.round(clamped));
        } else {
            // Steps are round numbers, but floating point addition is not, so the value is
            // snapped back to three decimals rather than drifting to 0.7500000000000001.
            write(entry, Math.round(clamped * 1000.0D) / 1000.0D);
        }
    }

    // ---- input ----------------------------------------------------------------------

    @Override
    public void clicked(int slotId, int button, ClickType clickType, Player player) {
        if (!(player instanceof ServerPlayer clicker) || clicker != viewer) {
            return;
        }
        // Everything below the menu is the player's own inventory, and the menu never
        // moves items, so those clicks are simply dropped.
        if (slotId < 0 || slotId >= SIZE) {
            return;
        }

        if (handleNavigation(slotId)) {
            return;
        }
        if (section == null) {
            openSection(slotId);
            return;
        }

        List<ConfigRegistry.Entry> entries = sectionEntries();
        int index = page * PAGE_SIZE + slotId;
        if (index >= entries.size()) {
            return;
        }

        ConfigRegistry.Entry entry = entries.get(index);
        if (!mayEdit(entry)) {
            deny();
            return;
        }
        // A client setting whose value has not been reported yet: acting on it would be
        // guessing at what it currently is.
        if (rawValue(entry) == null) {
            deny();
            return;
        }

        boolean reset = clickType == ClickType.THROW;
        boolean coarse = clickType == ClickType.QUICK_MOVE;

        if (reset) {
            write(entry, entry.defaultValue());
        } else if (entry.kind() == ConfigRegistry.Kind.BOOLEAN) {
            write(entry, !Boolean.parseBoolean(rawValue(entry)));
        } else if (entry.min() != null) {
            adjust(entry, button == 1 ? -1 : 1, coarse);
        } else {
            // Lists and free text cannot be nudged; the command edits those.
            deny();
            return;
        }

        click();
        rebuild();
    }

    private boolean handleNavigation(int slotId) {
        switch (slotId) {
            case SLOT_CLOSE -> {
                viewer.closeContainer();
                return true;
            }
            case SLOT_BACK -> {
                if (section != null) {
                    section = null;
                    page = 0;
                    click();
                    rebuild();
                }
                return true;
            }
            case SLOT_PREVIOUS -> {
                if (section != null && page > 0) {
                    page--;
                    click();
                    rebuild();
                }
                return true;
            }
            case SLOT_NEXT -> {
                if (section != null && page < pageCount() - 1) {
                    page++;
                    click();
                    rebuild();
                }
                return true;
            }
            case SLOT_INFO -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private void openSection(int slotId) {
        List<String> names = new ArrayList<>(ConfigRegistry.bySection().keySet());
        if (slotId < names.size()) {
            section = names.get(slotId);
            page = 0;
            click();
            rebuild();
        }
    }

    private void click() {
        viewer.playNotifySound(SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.MASTER, 0.4F, 1.0F);
    }

    private void deny() {
        viewer.playNotifySound(SoundEvents.VILLAGER_NO, SoundSource.MASTER, 0.4F, 1.0F);
    }

    // ---- nothing here is a real container -------------------------------------------

    @Override
    public ItemStack quickMoveStack(Player player, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean stillValid(Player player) {
        return player == viewer;
    }

    @Override
    public boolean canTakeItemForPickAll(ItemStack stack, net.minecraft.world.inventory.Slot slot) {
        return false;
    }
}
