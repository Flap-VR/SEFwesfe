# FirstJoin

Paper 1.21.11, Java 21.

## Build
```
mvn clean package
```
Drop `target/FirstJoin-1.0.0.jar` into `plugins/`.

## What it does
When a player joins for the very first time:
1. They are teleported exactly `settings.distance` blocks (default 10000) from the world spawn, at a safe land spot. Silent, no sounds or particles.
2. That spot becomes their respawn point (even without a bed).
3. The nearest village within `village.search-radius-blocks` (default 1000) is located. Every village type is searched (plains, desert, savanna, snowy, taiga), so it works in all biomes.
4. They get a bold cyan **Welcome** title, a bold orange subtitle, and the village coordinates in a bold white action bar. Their compass also points at the village.

If a spot is ocean/lava or has no village nearby, a new random direction is tried (`settings.max-attempts`).

## Commands
`/firstjoin test` runs the whole thing on yourself (so you can test without a fresh account; it WILL teleport you and move your respawn point). `/firstjoin reload` reloads the config.

## Performance note
Locating a village in never-visited terrain generates chunks on the main thread, so the server can hitch for a second or
two when a new player joins. Pre-generating around your 10k ring with Chunky avoids that.
