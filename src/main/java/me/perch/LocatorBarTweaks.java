package me.perch;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * LocatorBarTweaks
 * - EDITED: Only applies default range to players joining for the FIRST time.
 * - Per-player toggle (/locatorbar on|off|toggle|status) — only affects their own receive range
 * - Global range (/locatorrange <blocks>) — updates only players whose bar is ON
 * - Color setting (/locatorcolor <named|#RRGGBB|RRGGBB|reset>) via vanilla /waypoint modify
 * - All messages configurable in config.yml (with & color codes and simple {placeholders})
 * - Data saved in data.yml
 * - PAPI expansion auto-registers on load/enable and when PlaceholderAPI becomes enabled later
 */
public class LocatorBarTweaks extends JavaPlugin implements Listener {

    // ---------------- State ----------------
    private int globalRange;

    // persistence
    private File dataFile;
    private YamlConfiguration data;

    // caches mirrored to data.yml
    private final Set<UUID> receiveDisabled = new HashSet<>();
    private final Map<UUID, Integer> lastReceiveWhenEnabled = new HashMap<>();
    private final Map<UUID, String> preferredColor = new HashMap<>();

    // message cache
    private FileConfiguration cfg;

    // PAPI registration guard
    private boolean papiRegistered = false;

    // ---------------- Lifecycle ----------------
    @Override
    public void onLoad() {
        // If PAPI is already present during load (some loaders), register early.
        tryRegisterPapi();
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        reloadLocal();

        // Prepare data.yml
        dataFile = new File(getDataFolder(), "data.yml");
        if (!dataFile.exists()) {
            try {
                getDataFolder().mkdirs();
                dataFile.createNewFile();
            } catch (IOException e) {
                getLogger().severe("Could not create data.yml: " + e.getMessage());
            }
        }
        data = YamlConfiguration.loadConfiguration(dataFile);

        // hydrate caches
        for (String s : data.getStringList("receiveDisabled")) {
            try { receiveDisabled.add(UUID.fromString(s)); } catch (IllegalArgumentException ignored) {}
        }
        if (data.isConfigurationSection("lastReceiveWhenEnabled")) {
            for (String k : Objects.requireNonNull(data.getConfigurationSection("lastReceiveWhenEnabled")).getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    int r = Math.max(0, data.getInt("lastReceiveWhenEnabled." + k, globalRange));
                    lastReceiveWhenEnabled.put(id, r);
                } catch (IllegalArgumentException ignored) {}
            }
        }
        if (data.isConfigurationSection("preferredColor")) {
            for (String k : Objects.requireNonNull(data.getConfigurationSection("preferredColor")).getKeys(false)) {
                try {
                    UUID id = UUID.fromString(k);
                    String c = data.getString("preferredColor." + k, "unset");
                    if (c != null) preferredColor.put(id, c);
                } catch (IllegalArgumentException ignored) {}
            }
        }

        // events (includes PluginEnableEvent for late PAPI enable)
        Bukkit.getPluginManager().registerEvents(this, this);

        // NOTE: Loop to force settings on online players has been removed
        // to prevent resetting existing players on reload.

        // Try PAPI register now (in case PAPI is already enabled)
        tryRegisterPapi();

        getLogger().info("LocatorBarTweaks enabled. Global range=" + globalRange);
    }

    private void reloadLocal() {
        reloadConfig();
        cfg = getConfig();
        this.globalRange = Math.max(0, cfg.getInt("range", 250));
    }

    private void saveData() {
        data.set("receiveDisabled", receiveDisabled.stream().map(UUID::toString).collect(Collectors.toList()));
        for (Map.Entry<UUID, Integer> e : lastReceiveWhenEnabled.entrySet()) {
            data.set("lastReceiveWhenEnabled." + e.getKey(), e.getValue());
        }
        for (Map.Entry<UUID, String> e : preferredColor.entrySet()) {
            data.set("preferredColor." + e.getKey(), e.getValue());
        }
        try {
            data.save(dataFile);
        } catch (IOException e) {
            getLogger().severe("Failed to save data.yml: " + e.getMessage());
        }
    }

    // ---------------- PAPI auto-registration ----------------
    private void tryRegisterPapi() {
        if (papiRegistered) return;
        Plugin papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI");
        if (papi != null && papi.isEnabled()) {
            try {
                new PAPIExpansion(this).register();
                papiRegistered = true;
                getLogger().info("PlaceholderAPI detected — expansion registered.");
            } catch (Throwable t) {
                getLogger().warning("Failed to register PAPI expansion: " + t.getMessage());
            }
        }
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (!papiRegistered && "PlaceholderAPI".equalsIgnoreCase(event.getPlugin().getName())) {
            tryRegisterPapi();
        }
    }

    // ---------------- Messages ----------------
    private String colorize(String s) {
        if (s == null) return "";
        return ChatColor.translateAlternateColorCodes('&', s);
    }

    private boolean isBlankText(String s) {
        return s == null || ChatColor.stripColor(s).trim().isEmpty();
    }

    private String replacePlaceholders(String raw, Map<String, String> params) {
        if (raw == null) return "";
        if (params != null) {
            for (Map.Entry<String, String> e : params.entrySet()) {
                raw = raw.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
            }
        }
        return raw;
    }

    private String msg(String path, Map<String, String> params) {
        String raw = getMessageRaw(path);
        raw = replacePlaceholders(raw, params);
        return colorize(raw);
    }

    private String msg(String path) {
        return msg(path, null);
    }

    private String getMessageRaw(String path) {
        String val = cfg.getString("messages." + path);
        if (val != null) return val;

        // Fallback defaults (mirror keys in default config)
        switch (path) {
            case "prefix": return "&7[&bLocator&7] ";
            case "no-permission": return "&cNo permission.";
            case "players-only": return "&cPlayers only.";
            case "invalid-number": return "&cNot a number: {input}";
            case "range-usage": return "&eUsage: /{label} <blocks>";
            case "range-set": return "&aLocator Bar range set to &b{range}&a for players who have their bar &aON&a.";
            case "bar-usage": return "&eUsage: /{label} <on|off|toggle|status>";
            case "bar-status-on": return "&aYour locator bar is &aON&a at &b{range}&a.";
            case "bar-status-off": return "&eYour locator bar is &cOFF&e (others still see you).";
            case "bar-on": return "&aYour locator bar is now &aON&a.";
            case "bar-off": return "&aYour locator bar is now &cOFF&a (others still see you).";
            case "color-usage": return "&eUsage: /{label} <named|#RRGGBB|RRGGBB|reset>";
            case "color-updated": return "&aLocator color updated to &b{input}&a.";
            default: return "";
        }
    }

    /** Send helper: if message resolves to blank, send nothing at all (no prefix). */
    private void send(CommandSender to, String key) {
        String core = msg(key);
        if (isBlankText(core)) return; // suppress entirely
        String pref = msg("prefix");
        String out = isBlankText(pref) ? core : (pref + core);
        to.sendMessage(out);
    }

    /** Send helper with params: if message resolves to blank, send nothing at all (no prefix). */
    private void send(CommandSender to, String key, Map<String, String> params) {
        String core = msg(key, params);
        if (isBlankText(core)) return; // suppress entirely
        String pref = msg("prefix");
        String out = isBlankText(pref) ? core : (pref + core);
        to.sendMessage(out);
    }

    // ---------------- Helpers (Attributes & Color) ----------------
    private void setReceiveRange(OfflinePlayer p, int range) {
        if (p.getName() == null) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "attribute " + p.getName() + " minecraft:waypoint_receive_range base set " + range);
    }

    private void setTransmitRange(OfflinePlayer p, int range) {
        if (p.getName() == null) return;
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "attribute " + p.getName() + " minecraft:waypoint_transmit_range base set " + range);
    }

    private void applyEnabledReceive(Player p) {
        int remembered = lastReceiveWhenEnabled.getOrDefault(p.getUniqueId(), globalRange);
        setReceiveRange(p, remembered);
    }

    // Accepts: named color (e.g. "red"), hex "#RRGGBB" or "RRGGBB", or "reset"
    private void setWaypointColor(OfflinePlayer p, String input) {
        if (p.getName() == null || input == null) return;
        String arg = input.trim();

        if (arg.equalsIgnoreCase("reset")) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "waypoint modify " + p.getName() + " color reset");
            return;
        }

        if (arg.startsWith("#")) arg = arg.substring(1);
        if (Pattern.compile("(?i)^[0-9A-F]{6}$").matcher(arg).matches()) {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                    "waypoint modify " + p.getName() + " color hex " + arg.toUpperCase(Locale.ROOT));
            return;
        }

        Bukkit.dispatchCommand(Bukkit.getConsoleSender(),
                "waypoint modify " + p.getName() + " color " + arg.toLowerCase(Locale.ROOT));
    }

    // ---------------- Events ----------------
    /**
     * On join: Only if the player has NEVER played before do we enforce
     * the default receive/transmit ranges. Returning players are left alone.
     */
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();

        if (!p.hasPlayedBefore()) {
            UUID id = p.getUniqueId();
            // This is a first-time join
            lastReceiveWhenEnabled.put(id, globalRange);
            setReceiveRange(p, globalRange);
            setTransmitRange(p, globalRange);
            saveData();
        }
    }

    // ---------------- Commands ----------------
    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {

        // /locatorrange <blocks>  (admin)
        if (cmd.getName().equalsIgnoreCase("locatorrange")) {
            if (!sender.hasPermission("perchlocator.admin")) {
                send(sender, "no-permission");
                return true;
            }
            if (args.length != 1) {
                send(sender, "range-usage", map("label", label));
                return true;
            }
            try {
                int newRange = Integer.parseInt(args[0]);
                if (newRange < 0) {
                    send(sender, "invalid-number", map("input", args[0]));
                    return true;
                }
                this.globalRange = newRange;
                cfg.set("range", newRange);
                saveConfig();

                for (Player p : Bukkit.getOnlinePlayers()) {
                    UUID id = p.getUniqueId();
                    if (!receiveDisabled.contains(id)) {
                        lastReceiveWhenEnabled.put(id, newRange);
                        applyEnabledReceive(p);
                        setTransmitRange(p, newRange);
                    }
                }
                saveData();
                send(sender, "range-set", map("range", String.valueOf(newRange)));
            } catch (NumberFormatException ex) {
                send(sender, "invalid-number", map("input", args[0]));
            }
            return true;
        }

        // /locatorbar <on|off|toggle|status>  (player)
        if (cmd.getName().equalsIgnoreCase("locatorbar")) {
            if (!(sender instanceof Player)) {
                send(sender, "players-only");
                return true;
            }
            Player p = (Player) sender;
            UUID id = p.getUniqueId();

            if (args.length == 0) {
                send(sender, "bar-usage", map("label", label));
                return true;
            }

            String sub = args[0].toLowerCase(Locale.ROOT);
            switch (sub) {
                case "status": {
                    boolean off = receiveDisabled.contains(id);
                    if (off) {
                        send(sender, "bar-status-off");
                    } else {
                        int current = lastReceiveWhenEnabled.getOrDefault(id, globalRange);
                        send(sender, "bar-status-on", map("range", String.valueOf(current)));
                    }
                    return true;
                }
                case "off": {
                    if (!receiveDisabled.contains(id)) {
                        int remembered = lastReceiveWhenEnabled.getOrDefault(id, globalRange);
                        lastReceiveWhenEnabled.put(id, remembered);
                        receiveDisabled.add(id);
                        setReceiveRange(p, 0);
                        saveData();
                    }
                    send(sender, "bar-off");
                    return true;
                }
                case "on": {
                    if (receiveDisabled.remove(id)) {
                        applyEnabledReceive(p);
                        saveData();
                    } else {
                        applyEnabledReceive(p);
                    }
                    send(sender, "bar-on");
                    return true;
                }
                case "toggle": {
                    if (receiveDisabled.contains(id)) {
                        receiveDisabled.remove(id);
                        applyEnabledReceive(p);
                        saveData();
                        send(sender, "bar-on");
                    } else {
                        int remembered = lastReceiveWhenEnabled.getOrDefault(id, globalRange);
                        lastReceiveWhenEnabled.put(id, remembered);
                        receiveDisabled.add(id);
                        setReceiveRange(p, 0);
                        saveData();
                        send(sender, "bar-off");
                    }
                    return true;
                }
                default:
                    send(sender, "bar-usage", map("label", label));
                    return true;
            }
        }

        // /locatorcolor <named|#RRGGBB|RRGGBB|reset>  (player)
        if (cmd.getName().equalsIgnoreCase("locatorcolor")) {
            if (!(sender instanceof Player)) {
                send(sender, "players-only");
                return true;
            }
            if (args.length != 1) {
                send(sender, "color-usage", map("label", label));
                return true;
            }
            Player p = (Player) sender;
            String input = args[0];

            preferredColor.put(p.getUniqueId(), input);
            saveData();
            setWaypointColor(p, input);

            send(sender, "color-updated", map("input", input));
            return true;
        }

        return false;
    }

    private Map<String, String> map(String k, String v) {
        Map<String, String> m = new HashMap<>();
        m.put(k, v);
        return m;
    }

    // ---------------- Accessors for PAPI ----------------
    public boolean isReceiveDisabled(UUID id) {
        return receiveDisabled.contains(id);
    }

    public int getRememberedRange(UUID id) {
        if (isReceiveDisabled(id)) return 0;
        return lastReceiveWhenEnabled.getOrDefault(id, globalRange);
    }

    public String getPreferredColor(UUID id) {
        return preferredColor.getOrDefault(id, "unset");
    }

    public int getGlobalRange() {
        return globalRange;
    }
}