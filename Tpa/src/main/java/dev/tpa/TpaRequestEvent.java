package dev.tpa;

import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** Fired when a player sends a /tpa or /tpahere request, before it is stored. */
public class TpaRequestEvent extends Event implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final Player sender;
    private final Player target;
    private final TpaManager.Type type;
    private boolean cancelled;

    public TpaRequestEvent(Player sender, Player target, TpaManager.Type type) {
        this.sender = sender;
        this.target = target;
        this.type = type;
    }

    public Player getSender() { return sender; }
    public Player getTarget() { return target; }
    public TpaManager.Type getType() { return type; }

    @Override public boolean isCancelled() { return cancelled; }
    @Override public void setCancelled(boolean cancel) { this.cancelled = cancel; }

    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
