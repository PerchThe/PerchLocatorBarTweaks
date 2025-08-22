package me.perch;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

import java.util.Locale;
import java.util.UUID;

public class PAPIExpansion extends PlaceholderExpansion {

    private final LocatorBarTweaks plugin;

    public PAPIExpansion(LocatorBarTweaks plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "perchlocator";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Perch";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public boolean persist() { return true; }

    @Override
    public boolean canRegister() { return true; }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) return "";
        UUID id = player.getUniqueId();
        if (id == null) return "";

        switch (params.toLowerCase(Locale.ROOT)) {
            case "status": // "ON"/"OFF"
                return plugin.isReceiveDisabled(id) ? "OFF" : "ON";

            case "status_symbol": // "✔" / "✖"
                return plugin.isReceiveDisabled(id) ? "✖" : "✔";

            case "status_bool": // "true"/"false" (useful in DeluxeMenus conditions)
                return String.valueOf(!plugin.isReceiveDisabled(id));

            case "range": // "0" if OFF, otherwise remembered/global
                return String.valueOf(plugin.getRememberedRange(id));

            case "color": // formatted, correctly colored, with each word capitalized
                return formatColorDisplay(plugin.getPreferredColor(id));

            case "color_raw": // raw stored preference (name or hex or "unset")
                return plugin.getPreferredColor(id);

            case "global_range":
                return String.valueOf(plugin.getGlobalRange());

            default:
                return null; // unknown placeholder -> let PAPI try others
        }
    }

    // -------- Color formatting helpers --------

    private String formatColorDisplay(String stored) {
        if (stored == null || stored.equalsIgnoreCase("unset")) {
            return ChatColor.WHITE + titleCase("default");
        }

        String s = stored.trim();

        // Handle reset
        if (s.equalsIgnoreCase("reset")) {
            return ChatColor.WHITE + titleCase("default");
        }

        // Hex like #RRGGBB or RRGGBB
        if (s.startsWith("#")) s = s.substring(1);
        if (s.matches("(?i)^[0-9A-F]{6}$")) {
            String legacyHex = toLegacyHexColorCode(s.toUpperCase(Locale.ROOT));
            return legacyHex + "#" + s.toUpperCase(Locale.ROOT);
        }

        // Named color: normalize to enum style (DARK_RED, LIGHT_PURPLE, etc.)
        String enumName = s.toUpperCase(Locale.ROOT).replace(' ', '_');
        ChatColor cc = namedToChatColor(enumName);
        String pretty = titleCase(enumName.toLowerCase(Locale.ROOT).replace('_', ' '));

        if (cc == null) cc = ChatColor.WHITE;
        return cc + pretty;
    }

    private String titleCase(String text) {
        if (text == null || text.isEmpty()) return text;
        String[] parts = text.split("\\s+");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            String w = parts[i];
            if (!w.isEmpty()) {
                sb.append(Character.toUpperCase(w.charAt(0)));
                if (w.length() > 1) sb.append(w.substring(1));
            }
            if (i < parts.length - 1) sb.append(' ');
        }
        return sb.toString();
    }

    // Convert RRGGBB to §x§R§R§G§G§B§B
    private String toLegacyHexColorCode(String hex6) {
        StringBuilder sb = new StringBuilder("§x");
        for (char c : hex6.toCharArray()) {
            sb.append('§').append(c);
        }
        return sb.toString();
    }

    // Map vanilla color names to ChatColor
    private ChatColor namedToChatColor(String name) {
        switch (name) {
            case "BLACK": return ChatColor.BLACK;
            case "DARK_BLUE": return ChatColor.DARK_BLUE;
            case "DARK_GREEN": return ChatColor.DARK_GREEN;
            case "DARK_AQUA": return ChatColor.DARK_AQUA;
            case "DARK_RED": return ChatColor.DARK_RED;
            case "DARK_PURPLE": return ChatColor.DARK_PURPLE;
            case "GOLD": return ChatColor.GOLD;
            case "GRAY": return ChatColor.GRAY;
            case "DARK_GRAY": return ChatColor.DARK_GRAY;
            case "BLUE": return ChatColor.BLUE;
            case "GREEN": return ChatColor.GREEN;
            case "AQUA": return ChatColor.AQUA;
            case "RED": return ChatColor.RED;
            case "LIGHT_PURPLE": return ChatColor.LIGHT_PURPLE;
            case "YELLOW": return ChatColor.YELLOW;
            case "WHITE": return ChatColor.WHITE;
            default: return null;
        }
    }
}
