package dev.tpa;

import org.bukkit.command.Command;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class TpaPlugin extends JavaPlugin {

    private static final List<String> COMMANDS =
            List.of("tpa", "tpahere", "tpauto", "tpaccept", "tpdeny", "tpcancel");

    private TpaManager manager;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        // Built-in preference at the lowest priority so the /settings plugin can override it.
        getServer().getServicesManager().register(
                TpaPreferences.class, new PdcTpaPreferences(this), this, ServicePriority.Lowest);

        manager = new TpaManager(this);
        TpaCommands handler = new TpaCommands(this, manager);
        for (String name : COMMANDS) {
            PluginCommand command = getCommand(name);
            if (command != null) {
                command.setExecutor(handler);
                command.setTabCompleter(handler);
            }
        }
        getServer().getPluginManager().registerEvents(new TpaListener(this, manager), this);

        // First tick = every plugin has finished loading, so we can safely take our commands back.
        getServer().getScheduler().runTask(this, this::takeOverCommands);
        getLogger().info("Tpa enabled.");
    }

    /** EssentialsX/CMI register /tpa etc. too and usually win the plain name. Take it back. */
    private void takeOverCommands() {
        if (!getConfig().getBoolean("settings.override-other-commands", true)) return;
        Map<String, Command> known = getServer().getCommandMap().getKnownCommands();
        int taken = 0;
        for (String name : COMMANDS) {
            PluginCommand ours = getCommand(name);
            if (ours == null) continue;
            List<String> names = new ArrayList<>();
            names.add(name);
            names.addAll(ours.getAliases());
            for (String n : names) {
                Command current = known.get(n);
                if (current != null && current != ours) {
                    known.put(n, ours);
                    taken++;
                }
            }
        }
        if (taken > 0) {
            getLogger().info("Took over " + taken + " command name(s) from other plugins (theirs stay available with their plugin prefix).");
        }
    }

    @Override
    public void onDisable() {
        if (manager != null) manager.shutdown();
    }
}
