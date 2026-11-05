package dev.tpa;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** One executor for /tpa, /tpahere, /tpauto, /tpaccept, /tpdeny and /tpcancel. */
public final class TpaCommands implements TabExecutor {

    private final TpaPlugin plugin;
    private final TpaManager manager;

    public TpaCommands(TpaPlugin plugin, TpaManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("tpa") && args.length == 1 && args[0].equalsIgnoreCase("reload") && sender.hasPermission("tpa.reload")) {
            plugin.reloadConfig();
            manager.send(sender, "reloaded");
            return true;
        }
        if (!(sender instanceof Player player)) {
            manager.send(sender, "players-only");
            return true;
        }
        if (!player.hasPermission("tpa.use")) {
            manager.send(player, "no-permission");
            return true;
        }

        String arg = args.length > 0 ? args[0] : null;
        switch (name) {
            case "tpa" -> handleTpa(player, args);
            case "tpahere" -> {
                if (!player.hasPermission("tpa.here")) {
                    manager.send(player, "no-permission");
                } else if (args.length != 1) {
                    manager.send(player, "usage");
                } else {
                    manager.sendRequest(player, args[0], TpaManager.Type.HERE);
                }
            }
            case "tpauto" -> handleAuto(player, arg);
            case "tpaccept" -> manager.accept(player, arg);
            case "tpdeny" -> manager.deny(player, arg);
            case "tpcancel" -> manager.cancel(player, arg);
            default -> manager.send(player, "usage");
        }
        return true;
    }

    private void handleTpa(Player player, String[] args) {
        if (args.length == 0) {
            manager.send(player, "usage");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "whitelist" -> lists(player, TpaManager.ListType.WHITELIST, args);
            case "blacklist" -> lists(player, TpaManager.ListType.BLACKLIST, args);
            case "list" -> manager.showPending(player);
            case "help" -> manager.sendHelp(player);
            default -> manager.sendRequest(player, args[0], TpaManager.Type.TO_TARGET);
        }
    }

    private void lists(Player player, TpaManager.ListType type, String[] args) {
        if (!player.hasPermission("tpa.lists")) {
            manager.send(player, "no-permission");
            return;
        }
        manager.listCommand(player, type, args.length > 1 ? args[1] : null);
    }

    private void handleAuto(Player player, String arg) {
        if (!player.hasPermission("tpa.auto")) {
            manager.send(player, "no-permission");
            return;
        }
        if (arg == null) {
            manager.toggleAuto(player);
            return;
        }
        switch (arg.toLowerCase(Locale.ROOT)) {
            case "on" -> manager.setAutoMode(player, TpaManager.AutoMode.ON);
            case "off" -> manager.setAutoMode(player, TpaManager.AutoMode.OFF);
            case "whitelist", "friends" -> manager.setAutoMode(player, TpaManager.AutoMode.WHITELIST);
            default -> manager.send(player, "usage");
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> out = new ArrayList<>();
        if (!(sender instanceof Player player)) return out;
        String name = command.getName().toLowerCase(Locale.ROOT);
        String prefix = args.length > 0 ? args[args.length - 1].toLowerCase(Locale.ROOT) : "";

        switch (name) {
            case "tpa" -> {
                if (args.length == 1) {
                    for (String sub : List.of("whitelist", "blacklist", "list", "help")) add(out, sub, prefix);
                    if (player.hasPermission("tpa.reload")) add(out, "reload", prefix);
                    addPlayers(out, player, prefix);
                } else if (args.length == 2 && (args[0].equalsIgnoreCase("whitelist") || args[0].equalsIgnoreCase("blacklist"))) {
                    add(out, "list", prefix);
                    add(out, "clear", prefix);
                    addPlayers(out, player, prefix);
                }
            }
            case "tpahere" -> {
                if (args.length == 1) addPlayers(out, player, prefix);
            }
            case "tpauto" -> {
                if (args.length == 1) {
                    for (String s : List.of("on", "off", "whitelist")) add(out, s, prefix);
                }
            }
            case "tpaccept", "tpdeny" -> {
                if (args.length == 1) {
                    for (String s : manager.pendingSenderNames(player)) add(out, s, prefix);
                }
            }
            default -> { }
        }
        return out;
    }

    private void add(List<String> out, String value, String prefix) {
        if (value.toLowerCase(Locale.ROOT).startsWith(prefix)) out.add(value);
    }

    private void addPlayers(List<String> out, Player self, String prefix) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.equals(self) || !self.canSee(p)) continue;
            add(out, p.getName(), prefix);
        }
    }
}
