<img src="src/main/resources/logo.png" alt="Tumble" width="180">

# Tumble

[![build](https://github.com/ShrimpScript/Tumble/actions/workflows/build.yml/badge.svg)](https://github.com/ShrimpScript/Tumble/actions/workflows/build.yml)
[![license](https://img.shields.io/badge/license-MIT-blue)](LICENSE)
[![Minecraft](https://img.shields.io/badge/Minecraft-1.20.1-brightgreen)](https://www.minecraft.net)
[![Forge](https://img.shields.io/badge/Forge-47.4.10-orange)](https://files.minecraftforge.net)

Ragdoll physics for Minecraft players and mobs. Forge 1.20.1.

Go limp from a hard landing, a heavy hit, an explosion, a lightning strike or an elytra
crash, or on demand from a keybind. Flop against the world, then get back up.

Ragdolls are simulated on the server and synchronised to everyone, so the body other
players see is the body that is actually there.

## Status

Playable. Ragdolls, death corpses and mob bodies all work in game.

## What is in it

- A purpose-built rigid-body solver: substepped XPBD, six bodies and five limited
  joints per ragdoll, matching the vanilla player model part for part.
- No native libraries. Pure Java in one jar, so it runs wherever Forge does.
- Server-authoritative, so ragdolls are consistent in multiplayer.
- Triggers with individual thresholds, and a suppression list so the mod does not fight
  riptide, slime blocks, elytra flight, creative flight, climbing or a water clutch.
- Death leaves your body behind holding everything you carried. Right click to open it,
  shift right click to put it all back exactly where it was.
- Humanoid mobs leave bodies too, in place of vanilla's toppling death animation.
- Bodies can be grabbed and dragged around.
- Projectile damage gets its own much smaller knockback, so a bullet drops you where you
  stand rather than launching you.

## Configuring it

`/tumble config` opens a settings menu in game. It is an ordinary chest menu, built and
driven entirely by the server, so it needs nothing installed on the client.

- Pick a section, then click a setting. Left click raises a number, right click lowers it,
  holding shift moves ten steps at a time, and the drop key puts a setting back to its
  default. Booleans toggle on any click.
- Every tooltip carries the setting's own description, its current value, its default and
  its allowed range.
- Server settings change how the mod behaves for everyone on the world and are editable
  only at permission level 2, the same bar vanilla uses for `/gamerule`. Everyone else
  sees them, marked read only.
- The Display section (camera, hint overlay) belongs to each player, so anyone may open
  the menu and change their own. Those settings affect nobody else.

Commands, all at permission level 2 apart from `/tumble config`:

```
/tumble config                  open the menu
/tumble list [section]          print settings and their values
/tumble get <setting>           one setting, with its description
/tumble set <setting> <value>   change one setting
/tumble reset <setting>|all     put settings back to their defaults
```

Settings are read from the config spec itself, so the menu, the completions and the
config file never disagree about what exists.

## Building

Needs JDK 17.

```sh
./gradlew build        # jar lands in build/libs
./gradlew test         # physics core, headless, no Minecraft needed
./gradlew runClient    # dev client
```

The physics package has no Minecraft dependency, which is deliberate: the solver is
verified by unit tests rather than by launching the game.

## Credits

Inspired by [Sable: Ragdolls](https://modrinth.com/mod/sable-player-ragdoll) and
[Ragdoll Reactions](https://modrinth.com/mod/ragdoll-reactions) by Leo-T22, which
defined the feel this mod is chasing, particularly the trigger set and the idea that a
ragdoll should be something you recover from rather than just a death animation.

Tumble is an independent implementation. It shares no code with those mods, with Sable,
or with any other physics engine, and its solver was written from the published XPBD
formulation (Müller, Macklin, Chentanez, Jeschke and Kim, *Detailed Rigid Body
Simulation with Extended Position Based Dynamics*, 2020).

## Licence

MIT. See [LICENSE](LICENSE).
