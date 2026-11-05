package dev.tpa;

import org.bukkit.entity.Player;

/**
 * Hook for the /settings plugin ("Disable TPA Requests").
 * Either write the BYTE key tpa:disable_requests on the player (1 = disabled),
 * or register your own implementation as a Bukkit service at Normal priority or higher.
 */
public interface TpaPreferences {

    /**
     * When true the player gets NO notification (chat, action bar, title, sound) about incoming
     * requests. The requests still arrive and can be answered with /tpaccept or found with /tpa list.
     * Whitelisted senders ignore this and always notify.
     */
    boolean requestsDisabled(Player player);
}
