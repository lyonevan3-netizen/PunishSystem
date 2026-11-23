# PunishSystem (early development build)

A Paper-compatible punishment management plugin for DivineMC/Paper 26.2.

## Target environment

- Minecraft/server API: `26.2`
- Paper API dependency: `26.2.build.19-stable` (matching the build reported by the server)
- Java: 25
- LuckPerms: optional soft dependency; the plugin checks Bukkit permission nodes, which LuckPerms provides.

## Current implementation

- `/punish <player>` opens a category GUI.
- Social and Moderator categories are permission-gated.
- Offence durations and action types are loaded from `config.yml`.
- Escalation is tracked per player and offence, with the last configured step capped indefinitely.
- Expired offences continue counting.
- `/history <player>` and `/hist <player>` show bans, mutes and warnings, with active/expired wool indicators.
- History details include issue date, expiry, duration, issuer and status.
- Revocation is audited in SQLite. Revoked offences no longer count toward escalation.
- Appeal-accepted revocation is a separate action for bans.
- Mutes are enforced on chat; bans are enforced at login.

## Not yet implemented

This is an early core prototype, not the complete promised release. The Discord bot integration, secure `/link` verification handshake, Discord logging/threads, nickname syncing, pagination, configurable removal-reason prompt, and broad command-based mute bypass protection still need to be built and tested. The current `/link` command is a placeholder and does not link accounts yet.

The plugin currently uses its own ban/mute enforcement. Do not install it alongside another punishment plugin that independently controls the same punishments until the integration strategy is agreed.

## Build

Requires JDK 25 and Gradle 9.x with internet access to resolve dependencies.

```bash
gradle clean shadowJar
```

The output JAR will be:

`build/libs/PunishSystem-0.1.0-alpha.jar`

Copy it into the server's `plugins/` folder and restart the server. Back up the server and test on a staging instance first.

## LuckPerms permissions

Helper:

```text
punish.command.punish
punish.command.history
punish.command.link
punish.category.social
punish.remove.mute
punish.remove.warn
```

Mod and above additionally receive:

```text
punish.category.moderator
punish.remove.ban
```

Recommended inheritance: Mod inherits Helper, Snrmod inherits Mod, Admin inherits Snrmod, Director inherits Admin.

## Punishment configuration

`config.yml` is the source of truth. Each configured step is `ACTION:DURATION`, where ACTION is `MUTE`, `BAN`, or `WARN`; duration supports `m`, `h`, `d`, `w`, or `PERM`. Steps are ordered by escalation. The last step is the cap.
