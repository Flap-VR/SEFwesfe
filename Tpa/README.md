# Tpa

Paper 1.21.11, Java 21.

## Build
```
mvn clean package
```
Drop `target/Tpa-1.0.0.jar` into `plugins/`.

## Commands
| Command | What it does |
|---|---|
| `/tpa <player>` | Ask to teleport **to** them |
| `/tpahere <player>` | Ask them to teleport **to you** |
| `/tpauto [on\|off\|whitelist]` | Auto-accept: people who `/tpa` you teleport instantly (whitelist = only your whitelist) |
| `/tpaccept [player]` / `/tpdeny [player]` | Answer a request (also clickable buttons) |
| `/tpcancel [player]` | Cancel your teleport or outgoing requests |
| `/tpa list` | Show pending requests (works in silent mode) |
| `/tpa whitelist <player\|list\|clear>` | Toggle a player on your whitelist |
| `/tpa blacklist <player\|list\|clear>` | Toggle a player on your blacklist |
| `/tpa reload` | Reload the config |

## Settings plugin: "Disable TPA Requests"
Silent mode: requests still arrive, but you get no chat/action bar/title/sound notification
(whitelisted players still notify you). Use `/tpa list` or `/tpaccept` to find them.

**Option A, no dependency.** Write a BYTE on the player:
```java
NamespacedKey key = NamespacedKey.fromString("tpa:disable_requests");
player.getPersistentDataContainer().set(key, PersistentDataType.BYTE, (byte) 1); // 1 = disabled, 0 = notifications on
```
**Option B, service override.** Depend on Tpa and register your own provider:
```java
getServer().getServicesManager().register(TpaPreferences.class, new MyPrefs(), this, ServicePriority.Normal);
```

Auto mode is stored as the STRING key `tpa:auto_mode` (`off`, `on`, `whitelist`).
Whitelist/blacklist are STRING keys `tpa:whitelist` / `tpa:blacklist` (comma separated UUIDs).

## Other plugins' commands
Like Pay, this plugin takes `/tpa`, `/tpahere`, `/tpaccept`... back from EssentialsX/CMI on startup
(`settings.override-other-commands`). Theirs stay available as `/essentials:tpa` etc.

## Events
`TpaRequestEvent` and `TpaTeleportEvent` are cancellable.
