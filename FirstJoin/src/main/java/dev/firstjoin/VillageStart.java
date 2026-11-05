package dev.firstjoin;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.generator.structure.Structure;
import org.bukkit.util.StructureSearchResult;

import java.time.Duration;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sends a player to a random spot far from spawn, sets their respawn point there,
 * and points them at the nearest village (every village type, so every biome works).
 */
public final class VillageStart {

    /** Every village variant, so plains, desert, savanna, snowy and taiga biomes are all covered. */
    private static final List<Structure> VILLAGES = List.of(
            Structure.VILLAGE_PLAINS, Structure.VILLAGE_DESERT, Structure.VILLAGE_SAVANNA,
            Structure.VILLAGE_SNOWY, Structure.VILLAGE_TAIGA);

    private static final Set<Material> UNSAFE = EnumSet.of(
            Material.LAVA, Material.CACTUS, Material.MAGMA_BLOCK, Material.CAMPFIRE, Material.SOUL_CAMPFIRE,
            Material.FIRE, Material.SOUL_FIRE, Material.POWDER_SNOW, Material.SWEET_BERRY_BUSH,
            Material.WITHER_ROSE, Material.POINTED_DRIPSTONE, Material.COBWEB);

    private final FirstJoinPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();

    public VillageStart(FirstJoinPlugin plugin) {
        this.plugin = plugin;
    }

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    public Component parse(String raw, String... pairs) {
        String s = raw == null ? "" : raw;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = Pattern.compile(Pattern.quote(pairs[i]), Pattern.CASE_INSENSITIVE)
                    .matcher(s).replaceAll(Matcher.quoteReplacement(pairs[i + 1]));
        }
        return mm.deserialize(s);
    }

    public void message(CommandSender to, String key, String... pairs) {
        to.sendMessage(parse(cfg().getString("messages.prefix", "")
                + cfg().getString("messages." + key, "<red>Missing message: " + key), pairs));
    }

    // ------------------------------------------------------------------ pipeline

    public void run(Player player) {
        World world = pickWorld(player);
        if (world == null) return;
        int attempts = Math.max(1, cfg().getInt("settings.max-attempts", 8));
        attempt(player, world, world.getSpawnLocation(), attempts, null);
    }

    private World pickWorld(Player player) {
        String name = cfg().getString("settings.world", "");
        World world = (name == null || name.isBlank()) ? player.getWorld() : Bukkit.getWorld(name);
        if (world == null) {
            plugin.getLogger().warning("settings.world '" + name + "' isn't loaded.");
            return null;
        }
        if (world.getEnvironment() != World.Environment.NORMAL) return null; // villages only exist in overworlds
        return world;
    }

    private void attempt(Player player, World world, Location spawn, int attemptsLeft, Location lastLand) {
        if (!player.isOnline()) return;
        if (attemptsLeft <= 0) {
            if (lastLand != null && cfg().getBoolean("settings.fallback-teleport-without-village", true)) {
                complete(player, lastLand, null);
            } else {
                plugin.getLogger().warning("Couldn't find a suitable spot for " + player.getName() + ", leaving them at spawn.");
            }
            return;
        }

        Location candidate = randomPoint(world, spawn);
        int bx = candidate.getBlockX();
        int bz = candidate.getBlockZ();

        world.getChunkAtAsync(bx >> 4, bz >> 4, true).whenComplete((chunk, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) return;
                    Location land = error == null ? surface(world, bx, bz) : null;
                    if (land == null) {
                        attempt(player, world, spawn, attemptsLeft - 1, lastLand); // ocean, lava, etc: try another angle
                        return;
                    }
                    Location village = findVillage(world, land);
                    if (village == null) {
                        attempt(player, world, spawn, attemptsLeft - 1, land);
                        return;
                    }
                    complete(player, land, village);
                }));
    }

    private Location randomPoint(World world, Location spawn) {
        double dist = cfg().getDouble("settings.distance", 10000);
        double variance = cfg().getDouble("settings.distance-variance", 0);
        if (variance > 0) dist += ThreadLocalRandom.current().nextDouble(-variance, variance);
        double angle = ThreadLocalRandom.current().nextDouble(Math.PI * 2);
        WorldBorder border = world.getWorldBorder();

        for (int i = 0; i < 12; i++) {
            Location loc = new Location(world, spawn.getX() + Math.cos(angle) * dist, 64, spawn.getZ() + Math.sin(angle) * dist);
            if (border.isInside(loc)) return loc;
            dist *= 0.8; // the world border is smaller than the distance: pull the spot inward
        }
        return new Location(world, spawn.getX(), 64, spawn.getZ());
    }

    /** A safe standing spot on top of the terrain, or null (ocean, lava, cactus...). */
    private Location surface(World world, int x, int z) {
        int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
        if (y <= world.getMinHeight()) return null;
        Block ground = world.getBlockAt(x, y, z);
        Material type = ground.getType();
        if (type.isAir() || ground.isLiquid() || UNSAFE.contains(type)) return null;
        Block feet = world.getBlockAt(x, y + 1, z);
        Block head = world.getBlockAt(x, y + 2, z);
        if (!feet.isPassable() || !head.isPassable() || feet.isLiquid() || head.isLiquid()) return null;
        return new Location(world, x + 0.5, y + 1, z + 0.5);
    }

    /**
     * Nearest village of ANY type within the configured radius. This can block the main thread
     * for a moment while the area is generated, which is fine for a one-time first join.
     */
    private Location findVillage(World world, Location origin) {
        int radiusBlocks = Math.max(16, cfg().getInt("village.search-radius-blocks", 1000));
        int radiusChunks = (int) Math.ceil(radiusBlocks / 16.0);
        double maxSq = (double) radiusBlocks * radiusBlocks;

        Location best = null;
        double bestSq = Double.MAX_VALUE;
        for (Structure structure : VILLAGES) {
            try {
                StructureSearchResult result = world.locateNearestStructure(origin, structure, radiusChunks, true);
                if (result == null) continue;
                Location found = result.getLocation();
                double sq = horizontalSq(origin, found);
                if (sq <= maxSq && sq < bestSq) {
                    best = found;
                    bestSq = sq;
                }
            } catch (Exception ignored) {
                // structure type unavailable in this world, skip it
            }
        }
        return best;
    }

    private double horizontalSq(Location a, Location b) {
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    // ------------------------------------------------------------------ result

    private void complete(Player player, Location land, Location village) {
        player.teleportAsync(land, PlayerTeleportEvent.TeleportCause.PLUGIN).thenAccept(ok ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!ok || !player.isOnline()) return;

                    // Silent: no sounds, no particles, no chat about the teleport itself.
                    if (cfg().getBoolean("settings.set-respawn", true)) {
                        player.setRespawnLocation(land, true);
                    }

                    if (village == null) {
                        message(player, "no-village", "%distance%", String.valueOf(cfg().getInt("village.search-radius-blocks", 1000)));
                        return;
                    }
                    if (cfg().getBoolean("settings.set-compass-target", true)) {
                        player.setCompassTarget(village);
                    }
                    showWelcome(player, land, village);
                }));
    }

    private void showWelcome(Player player, Location land, Location village) {
        String[] pairs = {
                "%x%", String.valueOf(village.getBlockX()),
                "%z%", String.valueOf(village.getBlockZ()),
                "%distance%", String.valueOf(Math.round(Math.sqrt(horizontalSq(land, village))))
        };

        ConfigurationSection t = cfg().getConfigurationSection("titles.welcome");
        if (t != null) {
            Title.Times times = Title.Times.times(
                    Duration.ofMillis(t.getLong("fade-in-ms", 300)),
                    Duration.ofMillis(t.getLong("stay-ms", 6000)),
                    Duration.ofMillis(t.getLong("fade-out-ms", 1000)));
            player.showTitle(Title.title(parse(t.getString("title", ""), pairs), parse(t.getString("subtitle", ""), pairs), times));
        }

        // The action bar fades after ~3 seconds, so refresh it for the configured duration.
        Component bar = parse(cfg().getString("actionbar.format", "<white><bold>Village: X: %x%  Z: %z%"), pairs);
        int[] sendsLeft = { Math.max(1, cfg().getInt("actionbar.duration-seconds", 20) * 20 / 40) };
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (!player.isOnline() || sendsLeft[0]-- <= 0) {
                task.cancel();
                return;
            }
            player.sendActionBar(bar);
        }, 0L, 40L);
    }
}
