package dev.tpa;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class TpaManager {

    public enum Type { TO_TARGET, HERE }

    public enum AutoMode { OFF, ON, WHITELIST }

    public enum ListType { WHITELIST, BLACKLIST }

    private static final class Request {
        final UUID sender;
        final UUID target;
        final Type type;
        BukkitTask expiry;

        Request(UUID sender, UUID target, Type type) {
            this.sender = sender;
            this.target = target;
            this.type = type;
        }
    }

    private static final class Warmup {
        final UUID traveler;
        final UUID destination;
        final Location start;
        final int total;
        int ticksLeft;
        int elapsed;
        int shown = -1;
        BukkitTask task;

        Warmup(UUID traveler, UUID destination, Location start, int total) {
            this.traveler = traveler;
            this.destination = destination;
            this.start = start;
            this.total = total;
            this.ticksLeft = total;
        }
    }

    private final TpaPlugin plugin;
    private final MiniMessage mm = MiniMessage.miniMessage();
    private final NamespacedKey autoKey;
    private final NamespacedKey whitelistKey;
    private final NamespacedKey blacklistKey;

    /** target -> (sender -> request), insertion ordered so "latest" is the last entry */
    private final Map<UUID, Map<UUID, Request>> requests = new HashMap<>();
    private final Map<UUID, Warmup> warmups = new HashMap<>();
    private final Map<UUID, Long> requestCooldowns = new HashMap<>();

    public TpaManager(TpaPlugin plugin) {
        this.plugin = plugin;
        this.autoKey = new NamespacedKey(plugin, "auto_mode");
        this.whitelistKey = new NamespacedKey(plugin, "whitelist");
        this.blacklistKey = new NamespacedKey(plugin, "blacklist");
    }

    // ------------------------------------------------------------------ helpers

    private FileConfiguration cfg() {
        return plugin.getConfig();
    }

    private TpaPreferences prefs() {
        TpaPreferences p = Bukkit.getServicesManager().load(TpaPreferences.class);
        return p != null ? p : new PdcTpaPreferences(plugin);
    }

    public Component parse(String raw, String... pairs) {
        String s = raw == null ? "" : raw;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            s = Pattern.compile(Pattern.quote(pairs[i]), Pattern.CASE_INSENSITIVE)
                    .matcher(s).replaceAll(Matcher.quoteReplacement(pairs[i + 1]));
        }
        return mm.deserialize(s);
    }

    private String msg(String key) {
        return cfg().getString("messages." + key, "<red>Missing message: " + key);
    }

    public void send(CommandSender to, String key, String... pairs) {
        to.sendMessage(parse(cfg().getString("messages.prefix", "") + msg(key), pairs));
    }

    private void error(Player p, String key, String... pairs) {
        send(p, key, pairs);
        play(p, "error", null);
    }

    public void sendHelp(Player p) {
        for (String line : cfg().getStringList("messages.help")) {
            p.sendMessage(parse(line));
        }
    }

    private void play(Player p, String path, Float pitchOverride) {
        ConfigurationSection s = cfg().getConfigurationSection("sounds." + path);
        if (s == null) return;
        String name = s.getString("sound", "");
        if (name.isBlank()) return;
        float pitch = pitchOverride != null ? pitchOverride : (float) s.getDouble("pitch", 1.0);
        try {
            p.playSound(Sound.sound(Key.key(name), Sound.Source.MASTER, (float) s.getDouble("volume", 1.0), pitch));
        } catch (Exception ignored) {
            // invalid sound key in config
        }
    }

    private void showTitle(Player p, String key, String... pairs) {
        ConfigurationSection s = cfg().getConfigurationSection("titles." + key);
        if (s == null) return;
        Title.Times times = Title.Times.times(
                Duration.ofMillis(s.getLong("fade-in-ms", 0)),
                Duration.ofMillis(s.getLong("stay-ms", 1000)),
                Duration.ofMillis(s.getLong("fade-out-ms", 0)));
        p.showTitle(Title.title(parse(s.getString("title", ""), pairs), parse(s.getString("subtitle", ""), pairs), times));
    }

    private void spawn(String name, Location loc, int count, double dx, double dy, double dz, double speed) {
        if (name == null || name.isBlank() || loc.getWorld() == null) return;
        Particle particle = Registry.PARTICLE_TYPE.get(NamespacedKey.minecraft(name.toLowerCase(Locale.ROOT)));
        if (particle == null) return;
        try {
            loc.getWorld().spawnParticle(particle, loc, count, dx, dy, dz, speed);
        } catch (IllegalArgumentException ignored) {
            // particle needs extra data, not supported here
        }
    }

    private boolean blockedWorld(Player p) {
        return cfg().getStringList("settings.blocked-worlds").contains(p.getWorld().getName());
    }

    public boolean hasWarmup(UUID id) {
        return warmups.containsKey(id);
    }

    // ------------------------------------------------------------------ preferences, lists, auto mode

    private NamespacedKey key(ListType t) {
        return t == ListType.WHITELIST ? whitelistKey : blacklistKey;
    }

    private Set<UUID> readList(Player owner, ListType type) {
        Set<UUID> set = new LinkedHashSet<>();
        String raw = owner.getPersistentDataContainer().get(key(type), PersistentDataType.STRING);
        if (raw == null || raw.isBlank()) return set;
        for (String part : raw.split(",")) {
            try {
                set.add(UUID.fromString(part.trim()));
            } catch (IllegalArgumentException ignored) {
                // skip corrupt entry
            }
        }
        return set;
    }

    private void writeList(Player owner, ListType type, Set<UUID> set) {
        if (set.isEmpty()) {
            owner.getPersistentDataContainer().remove(key(type));
            return;
        }
        List<String> parts = new ArrayList<>();
        for (UUID id : set) parts.add(id.toString());
        owner.getPersistentDataContainer().set(key(type), PersistentDataType.STRING, String.join(",", parts));
    }

    public AutoMode autoMode(Player p) {
        String raw = p.getPersistentDataContainer().get(autoKey, PersistentDataType.STRING);
        if (raw == null) raw = cfg().getString("defaults.auto-mode", "off");
        try {
            return AutoMode.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return AutoMode.OFF;
        }
    }

    public void setAutoMode(Player p, AutoMode mode) {
        p.getPersistentDataContainer().set(autoKey, PersistentDataType.STRING, mode.name());
        send(p, "tpauto-" + mode.name().toLowerCase(Locale.ROOT));
    }

    public void toggleAuto(Player p) {
        setAutoMode(p, autoMode(p) == AutoMode.OFF ? AutoMode.ON : AutoMode.OFF);
    }

    private String nameOf(UUID id) {
        String name = Bukkit.getOfflinePlayer(id).getName();
        return name != null ? name : id.toString().substring(0, 8);
    }

    /** /tpa whitelist|blacklist <player|list|clear> */
    public void listCommand(Player owner, ListType type, String arg) {
        String listName = type.name().toLowerCase(Locale.ROOT);
        Set<UUID> set = readList(owner, type);

        if (arg == null || arg.equalsIgnoreCase("list")) {
            if (set.isEmpty()) {
                send(owner, "list-show-empty", "%list%", listName);
                return;
            }
            List<String> names = new ArrayList<>();
            for (UUID id : set) names.add(nameOf(id));
            send(owner, "list-show", "%list%", listName, "%count%", String.valueOf(names.size()), "%names%", String.join(", ", names));
            return;
        }
        if (arg.equalsIgnoreCase("clear")) {
            writeList(owner, type, new LinkedHashSet<>());
            send(owner, "list-cleared", "%list%", listName);
            return;
        }

        OfflinePlayer target = Bukkit.getPlayerExact(arg);
        if (target == null) target = Bukkit.getOfflinePlayerIfCached(arg);
        if (target == null) {
            error(owner, "player-not-found", "%player%", arg);
            return;
        }
        if (target.getUniqueId().equals(owner.getUniqueId())) {
            error(owner, "list-self");
            return;
        }

        String shownName = target.getName() != null ? target.getName() : arg;
        UUID id = target.getUniqueId();
        ListType other = type == ListType.WHITELIST ? ListType.BLACKLIST : ListType.WHITELIST;

        if (set.contains(id)) {
            set.remove(id);
            writeList(owner, type, set);
            send(owner, listName + "-removed", "%player%", shownName);
            return;
        }

        set.add(id);
        writeList(owner, type, set);
        Set<UUID> otherSet = readList(owner, other);
        if (otherSet.remove(id)) writeList(owner, other, otherSet);

        if (type == ListType.BLACKLIST) {
            Map<UUID, Request> incoming = requests.get(owner.getUniqueId());
            if (incoming != null) {
                Request pendingFromThem = incoming.get(id);
                if (pendingFromThem != null) removeRequest(pendingFromThem);
            }
        }
        send(owner, listName + "-added", "%player%", shownName);
    }

    // ------------------------------------------------------------------ sending requests

    public void sendRequest(Player sender, String targetName, Type type) {
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null || !sender.canSee(target)) {
            error(sender, "player-not-found", "%player%", targetName);
            return;
        }
        if (target.equals(sender)) {
            error(sender, "tpa-self");
            return;
        }
        if (warmups.containsKey(sender.getUniqueId())) {
            error(sender, "already-teleporting");
            return;
        }
        if (blockedWorld(sender) || blockedWorld(target)) {
            error(sender, "world-blocked");
            return;
        }
        if (!cfg().getBoolean("settings.cross-world", true) && !sender.getWorld().equals(target.getWorld())) {
            error(sender, "cross-world-disabled");
            return;
        }

        long expire = cfg().getLong("settings.request-expire-seconds", 60);
        String sentKey = type == Type.HERE ? "request-sent-here" : "request-sent-to";

        // Blacklist: the target never receives anything from this sender.
        if (readList(target, ListType.BLACKLIST).contains(sender.getUniqueId()) && !sender.hasPermission("tpa.bypass.blacklist")) {
            if (cfg().getBoolean("blacklist.silent-fail", false)) {
                send(sender, sentKey, "%player%", target.getName(), "%expire%", String.valueOf(expire));
            } else {
                error(sender, "blacklisted", "%player%", target.getName());
            }
            return;
        }

        long now = System.currentTimeMillis();
        if (!sender.hasPermission("tpa.bypass.cooldown")) {
            Long last = requestCooldowns.get(sender.getUniqueId());
            if (last != null) {
                long left = last + cfg().getLong("settings.request-cooldown-seconds", 5) * 1000L - now;
                if (left > 0) {
                    error(sender, "cooldown", "%seconds%", String.valueOf((long) Math.ceil(left / 1000.0)));
                    return;
                }
            }
        }

        Map<UUID, Request> incoming = requests.computeIfAbsent(target.getUniqueId(), k -> new LinkedHashMap<>());
        if (incoming.containsKey(sender.getUniqueId())) {
            error(sender, "request-duplicate", "%player%", target.getName());
            return;
        }
        if (incoming.size() >= cfg().getInt("settings.max-pending-per-target", 5)) {
            error(sender, "request-full", "%player%", target.getName());
            return;
        }

        TpaRequestEvent event = new TpaRequestEvent(sender, target, type);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) {
            if (incoming.isEmpty()) requests.remove(target.getUniqueId());
            return;
        }

        Request r = new Request(sender.getUniqueId(), target.getUniqueId(), type);
        r.expiry = Bukkit.getScheduler().runTaskLater(plugin, () -> expire(r), expire * 20L);
        incoming.put(sender.getUniqueId(), r);
        requestCooldowns.put(sender.getUniqueId(), now);

        send(sender, sentKey, "%player%", target.getName(), "%expire%", String.valueOf(expire));

        boolean whitelisted = readList(target, ListType.WHITELIST).contains(sender.getUniqueId());

        // /tpauto
        AutoMode mode = autoMode(target);
        boolean eligible = type == Type.TO_TARGET || cfg().getBoolean("tpauto.applies-to-tpahere", false);
        if (eligible && (mode == AutoMode.ON || (mode == AutoMode.WHITELIST && whitelisted))) {
            removeRequest(r);
            startFromRequest(sender, target, type, cfg().getBoolean("tpauto.skip-warmup", true));
            return;
        }

        // Silent mode ("Disable TPA Requests"): the request is still there, the target just isn't told.
        // Whitelisted senders break through the silence.
        boolean silent = prefs().requestsDisabled(target) && !whitelisted;
        if (!silent) notifyTarget(sender, target, type, expire);
    }

    private boolean flag(String name, boolean def) {
        return cfg().getBoolean("notify." + name, def);
    }

    private void notifyTarget(Player sender, Player target, Type type, long expire) {
        String exp = String.valueOf(expire);
        boolean toYou = type == Type.TO_TARGET;
        if (flag("chat", true)) {
            send(target, toYou ? "request-to-you" : "request-here-you", "%player%", sender.getName(), "%expire%", exp);
            target.sendMessage(parse(msg("request-buttons"), "%player%", sender.getName()));
        }
        if (flag("actionbar", true)) {
            target.sendActionBar(parse(msg(toYou ? "notify-actionbar-to" : "notify-actionbar-here"), "%player%", sender.getName()));
        }
        if (flag("title", false)) {
            showTitle(target, "incoming", "%player%", sender.getName());
        }
        if (flag("sound", true)) {
            play(target, "request", null);
        }
    }

    // ------------------------------------------------------------------ answering requests

    private Request findRequest(Player target, String name) {
        Map<UUID, Request> incoming = requests.get(target.getUniqueId());
        if (incoming == null || incoming.isEmpty()) return null;
        Request found = null;
        for (Request r : incoming.values()) {
            if (name == null) {
                found = r; // keeps the latest
            } else {
                Player sender = Bukkit.getPlayer(r.sender);
                if (sender != null && sender.getName().equalsIgnoreCase(name)) return r;
            }
        }
        return found;
    }

    public void accept(Player target, String name) {
        Request r = findRequest(target, name);
        if (r == null) {
            error(target, "no-request");
            return;
        }
        removeRequest(r);
        Player sender = Bukkit.getPlayer(r.sender);
        if (sender == null) {
            error(target, "sender-offline");
            return;
        }
        Player traveler = r.type == Type.TO_TARGET ? sender : target;
        if (warmups.containsKey(traveler.getUniqueId())) {
            error(target, "traveler-busy", "%player%", traveler.getName());
            return;
        }
        send(target, "request-accepted-target", "%player%", sender.getName());
        send(sender, "request-accepted-sender", "%player%", target.getName());
        startFromRequest(sender, target, r.type, false);
    }

    public void deny(Player target, String name) {
        Request r = findRequest(target, name);
        if (r == null) {
            error(target, "no-request");
            return;
        }
        removeRequest(r);
        Player sender = Bukkit.getPlayer(r.sender);
        send(target, "request-denied-target", "%player%", sender != null ? sender.getName() : "?");
        if (sender != null) send(sender, "request-denied-sender", "%player%", target.getName());
    }

    /** /tpcancel [player]: stops your warmup, otherwise your outgoing requests. */
    public void cancel(Player p, String name) {
        Warmup w = warmups.get(p.getUniqueId());
        if (w != null && name == null) {
            abort(w, p, "cancel-manual", false);
            return;
        }
        List<Request> out = outgoing(p.getUniqueId());
        if (name != null) {
            out.removeIf(r -> {
                Player t = Bukkit.getPlayer(r.target);
                return t == null || !t.getName().equalsIgnoreCase(name);
            });
        }
        if (out.isEmpty()) {
            error(p, "nothing-to-cancel");
            return;
        }
        out.forEach(this::removeRequest);
        send(p, "request-cancelled");
    }

    /** /tpa list: works even in silent mode, so nobody misses a request. */
    public void showPending(Player p) {
        Map<UUID, Request> incoming = requests.get(p.getUniqueId());
        List<Request> out = outgoing(p.getUniqueId());
        if ((incoming == null || incoming.isEmpty()) && out.isEmpty()) {
            send(p, "list-empty");
            return;
        }
        p.sendMessage(parse(msg("list-header")));
        if (incoming != null) {
            for (Request r : incoming.values()) {
                Player s = Bukkit.getPlayer(r.sender);
                if (s == null) continue;
                p.sendMessage(parse(msg("list-incoming"), "%player%", s.getName(),
                        "%type%", msg(r.type == Type.HERE ? "type-here" : "type-to")));
            }
        }
        for (Request r : out) {
            Player t = Bukkit.getPlayer(r.target);
            if (t == null) continue;
            p.sendMessage(parse(msg("list-outgoing"), "%player%", t.getName(),
                    "%type%", msg(r.type == Type.HERE ? "type-here-out" : "type-to-out")));
        }
    }

    public List<String> pendingSenderNames(Player target) {
        List<String> names = new ArrayList<>();
        Map<UUID, Request> incoming = requests.get(target.getUniqueId());
        if (incoming == null) return names;
        for (Request r : incoming.values()) {
            Player p = Bukkit.getPlayer(r.sender);
            if (p != null) names.add(p.getName());
        }
        return names;
    }

    private List<Request> outgoing(UUID id) {
        List<Request> out = new ArrayList<>();
        for (Map<UUID, Request> m : requests.values()) {
            Request r = m.get(id);
            if (r != null) out.add(r);
        }
        return out;
    }

    private void expire(Request r) {
        removeRequest(r);
        Player sender = Bukkit.getPlayer(r.sender);
        Player target = Bukkit.getPlayer(r.target);
        if (sender != null) {
            send(sender, "request-expired-sender", "%player%", target != null ? target.getName() : "?");
        }
        if (target != null) {
            send(target, "request-expired-target", "%player%", sender != null ? sender.getName() : "?");
        }
    }

    private void removeRequest(Request r) {
        if (r.expiry != null) r.expiry.cancel();
        Map<UUID, Request> m = requests.get(r.target);
        if (m != null) {
            m.remove(r.sender, r);
            if (m.isEmpty()) requests.remove(r.target);
        }
    }

    // ------------------------------------------------------------------ warmup + teleport

    private void startFromRequest(Player sender, Player target, Type type, boolean skipWarmup) {
        Player traveler = type == Type.TO_TARGET ? sender : target;
        Player dest = type == Type.TO_TARGET ? target : sender;
        begin(traveler, dest, skipWarmup);
    }

    private void begin(Player traveler, Player dest, boolean skipWarmup) {
        int seconds = Math.max(0, cfg().getInt("settings.warmup-seconds", 3));
        if (skipWarmup || traveler.hasPermission("tpa.bypass.warmup")) seconds = 0;
        if (seconds == 0) {
            teleportNow(traveler, dest);
            return;
        }
        Warmup w = new Warmup(traveler.getUniqueId(), dest.getUniqueId(), traveler.getLocation(), seconds * 20);
        warmups.put(traveler.getUniqueId(), w);
        w.task = Bukkit.getScheduler().runTaskTimer(plugin, () -> tick(w), 0L, 2L);
    }

    private void tick(Warmup w) {
        Player p = Bukkit.getPlayer(w.traveler);
        if (p == null || !p.isOnline() || p.isDead()) {
            stop(w);
            return;
        }
        Player d = Bukkit.getPlayer(w.destination);
        if (d == null || !d.isOnline()) {
            abort(w, p, "cancel-target-left", false);
            return;
        }
        if (moved(p, w)) {
            abort(w, p, "cancel-moved", true);
            return;
        }

        int secondsLeft = (int) Math.ceil(w.ticksLeft / 20.0);
        double progress = 1.0 - (double) w.ticksLeft / w.total;
        if (secondsLeft > 0 && secondsLeft != w.shown) {
            w.shown = secondsLeft;
            showTitle(p, "warmup", "%seconds%", String.valueOf(secondsLeft), "%player%", d.getName());
            play(p, "tick", (float) (0.8 + 1.2 * progress));
        }
        if (secondsLeft > 0) actionBar(p, progress, secondsLeft);
        ring(p, w, progress);

        if (w.ticksLeft <= 0) {
            stop(w);
            p.clearTitle();
            p.sendActionBar(Component.empty());
            teleportNow(p, d);
            return;
        }
        w.ticksLeft -= 2;
        w.elapsed += 2;
    }

    private boolean moved(Player p, Warmup w) {
        Location now = p.getLocation();
        if (!Objects.equals(now.getWorld(), w.start.getWorld())) return true;
        double tol = cfg().getDouble("settings.movement-tolerance", 0.1);
        return now.distanceSquared(w.start) > tol * tol; // looking around is fine
    }

    private void actionBar(Player p, double progress, int secondsLeft) {
        ConfigurationSection c = cfg().getConfigurationSection("actionbar");
        if (c == null || !c.getBoolean("enabled", true)) return;
        int len = Math.max(1, c.getInt("length", 20));
        int filled = (int) Math.round(progress * len);
        p.sendActionBar(parse(c.getString("format", "%filled%%empty% %seconds%s"),
                "%filled%", c.getString("filled-char", "█").repeat(filled),
                "%empty%", c.getString("empty-char", "░").repeat(len - filled),
                "%seconds%", String.valueOf(secondsLeft)));
    }

    private void ring(Player p, Warmup w, double progress) {
        ConfigurationSection c = cfg().getConfigurationSection("effects.warmup");
        if (c == null || !c.getBoolean("enabled", true)) return;
        String particle = c.getString("particle", "portal");
        double radius = c.getDouble("radius", 0.9) * (1.0 - 0.5 * progress);
        double rise = (w.elapsed * 0.05) % 2.0;
        int points = 8;
        Location base = p.getLocation();
        for (int i = 0; i < points; i++) {
            double angle = w.elapsed * 0.25 + i * (Math.PI * 2 / points);
            spawn(particle, base.clone().add(Math.cos(angle) * radius, rise, Math.sin(angle) * radius), 1, 0, 0, 0, 0);
        }
    }

    private void burst(Location loc) {
        ConfigurationSection c = cfg().getConfigurationSection("effects.finish");
        if (c == null || !c.getBoolean("enabled", true)) return;
        String particle = c.getString("particle", "reverse_portal");
        int points = Math.max(4, c.getInt("ring-points", 30));
        double r = c.getDouble("ring-radius", 1.2);
        for (int h = 0; h < 3; h++) {
            for (int i = 0; i < points; i++) {
                double angle = i * (Math.PI * 2 / points);
                spawn(particle, loc.clone().add(Math.cos(angle) * r, 0.2 + h * 0.8, Math.sin(angle) * r), 1, 0, 0.05, 0, 0.02);
            }
        }
        spawn(c.getString("core-particle", "end_rod"), loc.clone().add(0, 1, 0), 30, 0.4, 0.6, 0.4, 0.05);
    }

    private void teleportNow(Player traveler, Player dest) {
        if (!cfg().getBoolean("settings.cross-world", true) && !traveler.getWorld().equals(dest.getWorld())) {
            send(traveler, "cancel-cross-world");
            return;
        }
        Location to = dest.getLocation();
        to.setYaw(traveler.getLocation().getYaw());
        to.setPitch(traveler.getLocation().getPitch());

        TpaTeleportEvent event = new TpaTeleportEvent(traveler, dest, to);
        Bukkit.getPluginManager().callEvent(event);
        if (event.isCancelled()) return;

        Location from = traveler.getLocation();
        traveler.teleportAsync(event.getDestination()).thenAccept(ok ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!ok) {
                        send(traveler, "teleport-failed");
                        return;
                    }
                    play(traveler, "teleport", null);
                    burst(from);
                    burst(traveler.getLocation());
                    send(traveler, "teleported", "%player%", dest.getName());
                    if (cfg().getBoolean("settings.arrive-notify", true) && dest.isOnline()) {
                        send(dest, "arrived", "%player%", traveler.getName());
                    }
                }));
    }

    private void stop(Warmup w) {
        if (w.task != null) w.task.cancel();
        warmups.remove(w.traveler, w);
    }

    private void abort(Warmup w, Player p, String messageKey, boolean sound) {
        stop(w);
        p.clearTitle();
        p.sendActionBar(Component.empty());
        if (sound) play(p, "cancel", null);
        showTitle(p, "cancelled");
        send(p, messageKey);
    }

    public void cancelWarmup(UUID id, String messageKey) {
        Warmup w = warmups.get(id);
        if (w == null) return;
        Player p = Bukkit.getPlayer(id);
        if (p == null) {
            stop(w);
            return;
        }
        abort(w, p, messageKey, true);
    }

    // ------------------------------------------------------------------ lifecycle

    public void handleQuit(UUID id) {
        Warmup w = warmups.get(id);
        if (w != null) stop(w);

        Map<UUID, Request> incoming = requests.get(id);
        if (incoming != null) {
            Player leaving = Bukkit.getPlayer(id);
            for (Request r : new ArrayList<>(incoming.values())) {
                Player sender = Bukkit.getPlayer(r.sender);
                removeRequest(r);
                if (sender != null) {
                    send(sender, "request-target-left", "%player%", leaving != null ? leaving.getName() : "?");
                }
            }
        }
        outgoing(id).forEach(this::removeRequest);
    }

    public void shutdown() {
        for (Warmup w : new ArrayList<>(warmups.values())) {
            if (w.task != null) w.task.cancel();
        }
        warmups.clear();
        for (Map<UUID, Request> m : requests.values()) {
            for (Request r : m.values()) {
                if (r.expiry != null) r.expiry.cancel();
            }
        }
        requests.clear();
    }
}
