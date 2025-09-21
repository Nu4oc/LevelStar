package org.example.h4dro.levelStar;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class LevelStar extends JavaPlugin implements Listener {

    private Connection connection;
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    private final List<LevelRange> parsedRanges = new ArrayList<>();

    private ScheduledExecutorService worker;
    private int pointsPerKill;
    private int pointsPerLevel;
    private int maxLevel;
    private int minLevel;
    private int flushIntervalSeconds;
    private String levelFormat;
    private String levelUpMessage;
    private String levelUpMessageType;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();
        setupDatabase();
        loadLevelRanges();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getCommand("levelstar") != null) {
            getCommand("levelstar").setExecutor((sender, cmd, label, args) -> {
                if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    if (!sender.hasPermission("levelstar.reload")) {
                        sender.sendMessage(ChatColor.RED + "You don’t have permission!");
                        return true;
                    }
                    reloadConfig();
                    loadConfigValues();
                    loadLevelRanges();
                    sender.sendMessage(ChatColor.GREEN + "[LevelStar] Config reloaded!");
                    return true;
                }
                sender.sendMessage(ChatColor.YELLOW + "Usage: /levelstar reload");
                return true;
            });
        } else {
            getLogger().warning("Command 'levelstar' not found in plugin.yml");
        }

        worker = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "LevelStar-FlushWorker");
            t.setDaemon(true);
            return t;
        });

        worker.scheduleWithFixedDelay(this::flushCacheToDbSafe, flushIntervalSeconds, flushIntervalSeconds, TimeUnit.SECONDS);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new LevelStarExpansion(this).register();
            getLogger().info("Registered PlaceholderExpansion for levelstar");
        } else {
            getLogger().info("PlaceholderAPI not present - skipping expansion registration");
        }
    }

    @Override
    public void onDisable() {
        if (worker != null) {
            worker.shutdown();
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
            } catch (InterruptedException ignored) {
                worker.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        flushCacheToDbSafe();

        if (connection != null) {
            synchronized (connection) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    getLogger().log(Level.WARNING, "Error closing DB: " + e.getMessage(), e);
                }
            }
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        UUID uuid = e.getPlayer().getUniqueId();
        if (cache.containsKey(uuid)) return;

        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            PlayerData loaded = loadPlayerData(uuid);
            cache.put(uuid, loaded);
        });
    }

    @EventHandler
    public void onKill(PlayerDeathEvent e) {
        if (e.getEntity().getKiller() != null) {
            Player killer = e.getEntity().getKiller();
            UUID uuid = killer.getUniqueId();

            PlayerData data = cache.computeIfAbsent(uuid, id -> new PlayerData(minLevel, 0));
            data.points += pointsPerKill;

            while (data.points >= pointsPerLevel && data.level < maxLevel) {
                data.points -= pointsPerLevel;
                data.level++;
                sendLevelUpMessage(killer, data.level);
            }
        }
    }

    private void setupDatabase() {
        try {
            if (!getDataFolder().exists()) getDataFolder().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/data.db");
            try (Statement stmt = connection.createStatement()) {
                stmt.executeUpdate("CREATE TABLE IF NOT EXISTS levels (uuid TEXT PRIMARY KEY, level INTEGER, points INTEGER)");
            }
        } catch (SQLException e) {
            getLogger().severe("DB init error: " + e.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private PlayerData loadPlayerData(UUID uuid) {
        if (connection == null) return new PlayerData(minLevel, 0);
        synchronized (connection) {
            try (PreparedStatement ps = connection.prepareStatement("SELECT level, points FROM levels WHERE uuid=?")) {
                ps.setString(1, uuid.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        return new PlayerData(rs.getInt("level"), rs.getInt("points"));
                    }
                }
            } catch (SQLException e) {
                getLogger().warning("DB read error: " + e.getMessage());
            }
        }
        return new PlayerData(minLevel, 0);
    }

    private void flushCacheToDbSafe() {
        try {
            flushCacheToDb();
        } catch (Exception e) {
            getLogger().warning("DB flush error: " + e.getMessage());
        }
    }

    private void flushCacheToDb() {
        if (connection == null) return;
        synchronized (connection) {
            String sql = "INSERT INTO levels(uuid, level, points) VALUES(?,?,?) " +
                    "ON CONFLICT(uuid) DO UPDATE SET level=excluded.level, points=excluded.points";
            try (PreparedStatement ps = connection.prepareStatement(sql)) {
                connection.setAutoCommit(false);
                for (Map.Entry<UUID, PlayerData> entry : cache.entrySet()) {
                    UUID uuid = entry.getKey();
                    PlayerData data = entry.getValue();
                    ps.setString(1, uuid.toString());
                    ps.setInt(2, data.level);
                    ps.setInt(3, data.points);
                    ps.addBatch();
                }
                ps.executeBatch();
                connection.commit();
            } catch (SQLException e) {
                try {
                    connection.rollback();
                } catch (SQLException ex) {
                    getLogger().warning("Rollback failed: " + ex.getMessage());
                }
                getLogger().warning("DB write error: " + e.getMessage());
            } finally {
                try {
                    connection.setAutoCommit(true);
                } catch (SQLException ignored) {}
            }
        }
    }

    private void loadConfigValues() {
        pointsPerKill = getConfig().getInt("points.per_kill", 50);
        pointsPerLevel = getConfig().getInt("points.per_level", 5000);
        maxLevel = getConfig().getInt("max-level", 2000);
        minLevel = getConfig().getInt("min-level", 1);
        flushIntervalSeconds = Math.max(1, getConfig().getInt("flush-interval-seconds", 5));
        levelFormat = getConfig().getString("level-format", "LVL {level}");
        levelUpMessage = getConfig().getString("level-up-message", "&a[LevelStar] %player% has reached {display}!");
        levelUpMessageType = getConfig().getString("level-up-message-type", "private");
    }

    private void loadLevelRanges() {
        parsedRanges.clear();
        if (getConfig().isConfigurationSection("levels")) {
            for (String key : getConfig().getConfigurationSection("levels").getKeys(false)) {
                String color = getConfig().getString("levels." + key);
                LevelRange range = parseRangeKey(key, color);
                if (range != null) parsedRanges.add(range);
            }
        }
        parsedRanges.sort(Comparator.comparingInt(r -> r.min));
    }

    private String formatLevel(UUID uuid) {
        PlayerData data = cache.computeIfAbsent(uuid, this::loadPlayerData);
        String colorCode = getColorForLevel(data.level);
        String formatted = levelFormat.replace("{level}", String.valueOf(data.level));
        return colorize(colorCode, formatted) + ChatColor.RESET;
    }

    private String getColorForLevel(int level) {
        for (LevelRange r : parsedRanges) {
            if (level >= r.min && level <= r.max) return r.color;
        }
        return "#FFFFFF";
    }

    private void sendLevelUpMessage(Player p, int newLevel) {
        String colorCode = getColorForLevel(newLevel);
        String prefix = colorize(colorCode, levelFormat.replace("{level}", String.valueOf(newLevel))) + ChatColor.RESET;

        String msg = ChatColor.translateAlternateColorCodes('&',
                levelUpMessage.replace("%player%", p.getName())
                        .replace("{display}", prefix)
                        .replace("%level%", String.valueOf(newLevel)));

        if ("broadcast".equalsIgnoreCase(levelUpMessageType)) {
            Bukkit.broadcastMessage(msg);
        } else {
            p.sendMessage(msg);
        }
    }

    private String colorize(String color, String text) {
        if (color == null) return text;
        color = color.trim();
        try {
            if (color.startsWith("#")) {
                return net.md_5.bungee.api.ChatColor.of(color) + text;
            } else {
                return ChatColor.translateAlternateColorCodes('&', color) + text;
            }
        } catch (Exception e) {
            return ChatColor.translateAlternateColorCodes('&', color) + text;
        }
    }

    private LevelRange parseRangeKey(String key, String color) {
        if (key == null || key.isEmpty()) return null;
        key = key.trim();
        Pattern p = Pattern.compile("^(\\d+)(?:-(\\d+))?$");
        Matcher m = p.matcher(key);
        if (!m.matches()) {
            getLogger().warning("Invalid level range key: " + key);
            return null;
        }
        int min = Integer.parseInt(m.group(1));
        int max = m.group(2) != null ? Integer.parseInt(m.group(2)) : min;
        if (max < min) {
            int t = min; min = max; max = t;
        }
        return new LevelRange(min, max, color == null ? "#FFFFFF" : color);
    }

    static class PlayerData {
        int level;
        int points;

        PlayerData(int level, int points) {
            this.level = level;
            this.points = points;
        }
    }

    static class LevelRange {
        final int min;
        final int max;
        final String color;

        LevelRange(int min, int max, String color) {
            this.min = min;
            this.max = max;
            this.color = color;
        }
    }

    public static class LevelStarExpansion extends PlaceholderExpansion {
        private final LevelStar plugin;

        public LevelStarExpansion(LevelStar plugin) {
            this.plugin = plugin;
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public boolean canRegister() {
            return true;
        }

        @Override
        public String getIdentifier() {
            return "levelstar";
        }

        @Override
        public String getAuthor() {
            return "Hy4dro";
        }

        @Override
        public String getVersion() {
            return plugin.getDescription().getVersion();
        }

        @Override
        public String onPlaceholderRequest(Player p, String params) {
            if (p == null || params == null) return "";

            if (params.equalsIgnoreCase("level")) {
                return plugin.formatLevel(p.getUniqueId());
            }

            if (params.equalsIgnoreCase("level_raw")) {
                PlayerData data = plugin.cache.computeIfAbsent(p.getUniqueId(), plugin::loadPlayerData);
                return String.valueOf(data.level);
            }

            return null;
        }
    }
}
