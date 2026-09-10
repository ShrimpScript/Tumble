package dev.shrimpscript.tumble.config;

import com.electronwill.nightconfig.core.UnmodifiableConfig;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Every setting the mod has, discovered by walking the config spec itself.
 *
 * <p>The commands and the settings screen both read from here, so neither carries a
 * hand-written list of options. A new setting added to {@link TumbleConfig} appears in
 * the command completions and on the screen without another line of code, and there is no
 * second list to forget to update.
 */
public final class ConfigRegistry {

    /** What kind of control a setting needs, and how a command should parse it. */
    public enum Kind {
        BOOLEAN,
        INTEGER,
        DECIMAL,
        STRING_LIST,
        STRING
    }

    /** One leaf setting: where it lives, what it holds, and what it is allowed to hold. */
    public record Entry(String path,
                        String section,
                        String name,
                        Kind kind,
                        String comment,
                        Object defaultValue,
                        Double min,
                        Double max,
                        boolean serverSide) {

        /** A short label for the screen: the leaf name, spaced out from camel case. */
        public String label() {
            return humanise(name);
        }

        /**
         * The path between the section and the name, or empty if there is none.
         *
         * <p>Several settings are called "enabled" and only their sub-path tells them
         * apart, so the screen groups by this rather than showing eight identical rows.
         */
        public String group() {
            int first = path.indexOf('.');
            int last = path.lastIndexOf('.');
            return first == last ? "" : path.substring(first + 1, last);
        }
    }

    /** Turns a config key into a readable label: "impactDamage" becomes "Impact Damage". */
    public static String humanise(String key) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (i > 0 && Character.isUpperCase(c) && !Character.isUpperCase(key.charAt(i - 1))) {
                out.append(' ');
            }
            out.append(i == 0 ? Character.toUpperCase(c) : c);
        }
        return out.toString();
    }

    private static List<Entry> entries;

    private ConfigRegistry() {
    }

    public static synchronized List<Entry> entries() {
        if (entries == null) {
            List<Entry> found = new ArrayList<>();
            collect(TumbleConfig.SERVER_SPEC, true, found);
            collect(TumbleConfig.CLIENT_SPEC, false, found);
            entries = List.copyOf(found);
        }
        return entries;
    }

    public static Entry find(String path) {
        for (Entry entry : entries()) {
            if (entry.path().equalsIgnoreCase(path)) {
                return entry;
            }
        }
        return null;
    }

    /** Entries grouped by their top-level section, in declaration order. */
    public static Map<String, List<Entry>> bySection() {
        Map<String, List<Entry>> grouped = new LinkedHashMap<>();
        for (Entry entry : entries()) {
            grouped.computeIfAbsent(entry.section(), key -> new ArrayList<>()).add(entry);
        }
        return grouped;
    }

    private static void collect(ForgeConfigSpec spec, boolean serverSide, List<Entry> out) {
        walk(spec.getSpec(), "", spec, serverSide, out);
    }

    private static void walk(UnmodifiableConfig config, String prefix,
                             ForgeConfigSpec spec, boolean serverSide, List<Entry> out) {
        for (Map.Entry<String, Object> child : config.valueMap().entrySet()) {
            String path = prefix.isEmpty() ? child.getKey() : prefix + "." + child.getKey();
            Object value = child.getValue();

            if (value instanceof UnmodifiableConfig nested) {
                walk(nested, path, spec, serverSide, out);
                continue;
            }
            if (value instanceof ForgeConfigSpec.ValueSpec valueSpec) {
                out.add(describe(path, valueSpec, serverSide));
            }
        }
    }

    private static Entry describe(String path, ForgeConfigSpec.ValueSpec spec, boolean serverSide) {
        int dot = path.lastIndexOf('.');
        String section = dot < 0 ? "general" : path.substring(0, path.indexOf('.'));
        String name = dot < 0 ? path : path.substring(dot + 1);

        Object fallback = spec.getDefault();
        Kind kind = kindOf(fallback);

        Double min = null;
        Double max = null;
        ForgeConfigSpec.Range<?> range = spec.getRange();
        if (range != null && range.getMin() instanceof Number low && range.getMax() instanceof Number high) {
            min = low.doubleValue();
            max = high.doubleValue();
        }

        return new Entry(path, section, name, kind, spec.getComment(), fallback, min, max, serverSide);
    }

    private static Kind kindOf(Object value) {
        if (value instanceof Boolean) {
            return Kind.BOOLEAN;
        }
        if (value instanceof Integer || value instanceof Long) {
            return Kind.INTEGER;
        }
        if (value instanceof Double || value instanceof Float) {
            return Kind.DECIMAL;
        }
        if (value instanceof List<?>) {
            return Kind.STRING_LIST;
        }
        return Kind.STRING;
    }

    /**
     * Whether the file behind a setting has actually been loaded.
     *
     * <p>Server settings do not exist at the main menu, and reading one there throws.
     */
    public static boolean isLoaded(Entry entry) {
        return (entry.serverSide() ? TumbleConfig.SERVER_SPEC : TumbleConfig.CLIENT_SPEC).isLoaded();
    }

    /** Current value of a setting, read from the live config. */
    @SuppressWarnings("unchecked")
    public static Object get(Entry entry) {
        ForgeConfigSpec spec = entry.serverSide() ? TumbleConfig.SERVER_SPEC : TumbleConfig.CLIENT_SPEC;
        UnmodifiableConfig values = spec.getValues();

        Object holder = values.get(entry.path());
        if (holder instanceof ForgeConfigSpec.ConfigValue<?> configValue) {
            return configValue.get();
        }
        return holder;
    }

    /**
     * Writes a setting and persists it.
     *
     * @return an error message, or null if the value was accepted
     */
    @SuppressWarnings("unchecked")
    public static String set(Entry entry, Object value) {
        ForgeConfigSpec spec = entry.serverSide() ? TumbleConfig.SERVER_SPEC : TumbleConfig.CLIENT_SPEC;

        if (entry.min() != null && value instanceof Number number) {
            double d = number.doubleValue();
            if (d < entry.min() || d > entry.max()) {
                return "must be between " + trim(entry.min()) + " and " + trim(entry.max());
            }
        }

        Object holder = spec.getValues().get(entry.path());
        if (!(holder instanceof ForgeConfigSpec.ConfigValue<?> configValue)) {
            return "no such setting";
        }

        try {
            ((ForgeConfigSpec.ConfigValue<Object>) configValue).set(value);
            spec.save();
            return null;
        } catch (RuntimeException failure) {
            return failure.getMessage() == null ? "rejected" : failure.getMessage();
        }
    }

    public static String reset(Entry entry) {
        return set(entry, entry.defaultValue());
    }

    /** Wire form of a value: exact, unlike the display form, so it round-trips. */
    public static String encode(Object value) {
        if (value instanceof List<?> list) {
            return String.join(",", list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    /** Parses a wire value back into the type a setting holds, or null if it will not. */
    public static Object decode(Entry entry, String raw) {
        try {
            return switch (entry.kind()) {
                case BOOLEAN -> Boolean.parseBoolean(raw);
                case INTEGER -> (int) Math.round(Double.parseDouble(raw));
                case DECIMAL -> Double.parseDouble(raw);
                case STRING_LIST -> raw.isBlank() ? List.of() : List.of(raw.split("\\s*,\\s*"));
                case STRING -> raw;
            };
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }

    /** Formats a value for display, without a trailing .0 on whole numbers. */
    public static String display(Object value) {
        if (value instanceof Double d) {
            return trim(d);
        }
        if (value instanceof List<?> list) {
            return list.isEmpty() ? "(empty)" : String.join(", ",
                    list.stream().map(String::valueOf).toList());
        }
        return String.valueOf(value);
    }

    private static String trim(double value) {
        if (value == Math.rint(value) && Math.abs(value) < 1.0e9D) {
            return String.valueOf((long) value);
        }
        return String.format(Locale.ROOT, "%.3f", value).replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
