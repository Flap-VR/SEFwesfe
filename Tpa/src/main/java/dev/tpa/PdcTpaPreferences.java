package dev.tpa;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

/**
 * Reads the BYTE key tpa:disable_requests from the player's PersistentDataContainer
 * (1 = "Disable TPA Requests" is ON, 0 or missing = notifications on).
 */
public final class PdcTpaPreferences implements TpaPreferences {

    private final NamespacedKey key;

    public PdcTpaPreferences(Plugin plugin) {
        this.key = new NamespacedKey(plugin, "disable_requests");
    }

    @Override
    public boolean requestsDisabled(Player player) {
        Byte value = player.getPersistentDataContainer().get(key, PersistentDataType.BYTE);
        return value != null && value != 0;
    }
}
