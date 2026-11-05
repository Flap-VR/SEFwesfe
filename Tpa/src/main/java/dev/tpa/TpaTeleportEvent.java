package dev.tpa;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired right before a teleport happens (after warmup). */
public class TpaTeleportEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player traveler;
    private final Player destinationPlayer;
    private Location destination;
    private boolean cancelled;

    public TpaTeleportEvent(Player traveler, Player destinationPlayer, Location destination) {
        this.traveler = traveler;
        this.destinationPlayer = destinationPlayer;
        this.destination = destination;
    }

    public Player getTraveler() { return traveler; }
    public Player getDestinationPlayer() { return destinationPlayer; }
    public Location getDestination() { return destination; }
    public void setDestination(Location destination) { this.destination = destination; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
