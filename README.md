# PunishSystem 1.0.2

A rebuilt Paper-compatible punishment plugin for Minecraft 26.2, using Java 25, SQLite, configurable escalation and inventory-based staff menus.

## Included in this rebuild

- `/punish <player>` with clean `Punishments`, `Social Punishments` and `Moderator Punishments` menus.
- `/history <player>` and `/hist <player>` with Bans, Mutes and Warnings, paginated history, and details.
- Custom inventory holders keep internal menu routing separate from visible titles.
- Social category button is green; mute offences use blue wool; offence names are red; escalation lore is grey.
- Every offence menu item displays its complete configured escalation schedule and marks the next step.
- LuckPerms-compatible permission nodes (Bukkit permission checks).
- SQLite records issue time, expiry, issuer, status, removal reason and remover.
- Expired offences continue to count towards escalation.
- Any revoked or removed offence is excluded from escalation. Accepted appeals are recorded as `APPEAL_ACCEPTED`.
- Mutes block chat and configurable command roots.
- Bans are enforced when a player attempts to join.
- Timed punishments are periodically marked expired.
- Existing SQLite table layout is retained, so an existing `punishments.db` can be backed up and reused.

## Target environment

- Minecraft / Paper API: 26.2
- Java: 25
- Gradle: 9.1+
- LuckPerms: optional; permissions are checked through the Bukkit permission API.

The Gradle dependency is pinned to Paper API `26.2.build.129-stable`. The supplied DivineMC server reports `26.2.build.19-stable`; this project has not yet been tested against that exact server build. The code uses public Bukkit/Paper APIs, but server compatibility still needs a staging-server test.

## Build

```bash
gradle clean build
```

The shaded plugin JAR will be created at:

`build/libs/PunishSystem-1.0.2.jar`

The build includes SQLite JDBC and relocates its package into the plugin namespace.

## GitHub Actions

The repository workflow builds on pushes to `main` and can be started manually from the Actions tab. It uses Temurin Java 25 and Gradle 9.1, then uploads the JAR as a workflow artifact.

## Install / upgrade

1. Stop the test server.
2. Back up `plugins/PunishSystem/` including `punishments.db` and `config.yml`.
3. Replace the old plugin JAR with `PunishSystem-1.0.2.jar`.
4. Keep the existing `plugins/PunishSystem/punishments.db` to preserve history.
5. Start a staging server first and inspect the console for startup errors.
6. Confirm the menus, permissions, punishments, expiry, mute and ban enforcement before production rollout.

Do not run two punishment plugins that both enforce bans or mutes for the same players.

## Permissions

Helper:
- `punish.command.punish`
- `punish.command.history`
- `punish.command.link`
- `punish.category.social`
- `punish.remove.mute`
- `punish.remove.warn`

Mod and above additionally:
- `punish.category.moderator`
- `punish.remove.ban`

Recommended LuckPerms inheritance:

`Mod` inherits `Helper`; `Snrmod` inherits `Mod`; `Admin` inherits `Snrmod`; `Director` inherits `Admin`.

The plugin checks `punish.category.moderator` before issuing a BAN step. Helpers can access Social punishments and issue mutes, but cannot issue bans. Social offences whose first step is a BAN are hidden from Helpers.

## Configuration

`config.yml` is the source of truth for the offence schedule. Each entry is an ordered list of `ACTION:DURATION` values:

- `MUTE`, `BAN`, or `WARN`
- `m`, `h`, `d`, `w`, or `PERM`
- `WARN:0` records a warning.

The final step is capped: further offences use that final step again. The `settings.muted-blocked-commands` list controls command roots blocked while muted.

## Discord integration status

Discord bot linking, nickname sync, evidence threads and channel logging are **not implemented in this core rebuild**. `/link` explicitly reports that the Discord bridge is not enabled. Those features are planned as a separate integration stage so a Discord outage cannot prevent Minecraft punishments from being recorded.

## Testing status

This source rebuild has been created from the uploaded project and reviewed for the identified history-query and GUI-routing defects. It has **not been compiled or tested on a Minecraft server in this environment**. The available environment has Java 21 and no Gradle installation, while this target requires Java 25. Do not treat this source ZIP as a tested production JAR.
