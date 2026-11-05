package dev.firstjoin;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Locale;

public final class FirstJoinPlugin extends JavaPlugin implements Listener {

    private VillageStart start;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        start = new VillageStart(this);
        Bukkit.getPluginManager().registerEvents(this, this);
        if (getCommand("firstjoin") != null) {
            getCommand("firstjoin").setExecutor(this);
        }
        getLogger().info("FirstJoin enabled.");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!getConfig().getBoolean("settings.enabled", true)) return;
        Player player = event.getPlayer();
        if (player.hasPlayedBefore()) return;
        start.run(player);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("firstjoin.admin")) {
            start.message(sender, "no-permission");
            return true;
        }
        if (args.length == 0) {
            start.message(sender, "usage");
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "reload" -> {
                reloadConfig();
                start.message(sender, "reloaded");
            }
            case "test" -> {
                if (sender instanceof Player player) {
                    start.message(player, "test-start");
                    start.run(player);
                } else {
                    start.message(sender, "players-only");
                }
            }
            default -> start.message(sender, "usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("firstjoin.admin")) {
            return List.of("reload", "test");
        }
        return List.of();
    }
}
