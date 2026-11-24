# PunishSystem 1.0.5

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

`build/libs/PunishSystem-1.0.5.jar`

The repository workflow uploads an artifact named `PunishSystem-1.0.5`.

The build includes SQLite JDBC and relocates its package into the plugin namespace.

## GitHub Actions

The repository workflow builds on pushes to `main` and can be started manually from the Actions tab. It uses Temurin Java 25 and Gradle 9.1, then uploads the JAR as a workflow artifact.

## Install / upgrade

1. Stop the test server.
2. Back up `plugins/PunishSystem/` including `punishments.db` and `config.yml`.
3. Replace the old plugin JAR with `PunishSystem-1.0.5.jar`.
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

## Discord integration (1.0.5)

Version 1.0.5 adds the first Minecraft-to-Discord delivery bridge for the existing Robblestone Punishment Bot API.

- Sends `WARNING`, `MUTE`, and `BAN` events to `POST /api/v1/punishments`.
- Uses the bot's `Authorization: Bearer <API_SECRET>` authentication and requires HTTPS.
- Uses the bot API's exact fields: `punishmentId`, `type`, `player`, `staff`, `reason`, `duration`, and optional ISO-8601 `expiresAt`.
- Adds a `discord_outbox` table to the existing SQLite database. Existing punishment rows and IDs are not rewritten.
- Retries failed delivery with exponential backoff, up to 15 minutes between attempts.
- Discord delivery is asynchronous. A bot outage does not block issuing or enforcing Minecraft punishments.
- Stable event IDs make bot-side duplicate submissions idempotent.

The bridge is disabled by default. Once the bot is hosted, configure `discord.enabled`, `discord.bot-url` (HTTPS base URL), `discord.shared-secret` (same value as the bot host's private `API_SECRET`), and `discord.server-id` in `plugins/PunishSystem/config.yml`.

**Not yet included:** `/link` account linking, Discord nickname sync, staff role verification, and sending revoke/appeal updates. The current bot API does not yet expose the link/unlink endpoints required for a secure linking flow, so `/link` remains a placeholder in 1.0.5. Evidence threads are created by the bot for received punishment events.

## Offence-specific numbering and active-punishment protection (v1.0.4)

- Offence numbers are calculated independently for each player, category and offence. The staff output distinguishes `Offence #N` from the unique database `Record #ID`.
- A player cannot receive another active punishment for the same category/offence until the existing one expires or is revoked.
- Different offences remain independent: an active Spamming mute does not block an Advertising punishment.
- The offence menu displays an `ALREADY ACTIVE` entry with the current action and remaining duration when that exact offence is already active.
- The final confirmation/issue path rechecks active status to prevent stale menus from issuing duplicate punishments.

## Testing status

This source rebuild has been created from the uploaded project and reviewed for the identified history-query and GUI-routing defects. It has **not been compiled or tested on a Minecraft server in this environment**. The available environment has Java 21 and no Gradle installation, while this target requires Java 25. Do not treat this source ZIP as a tested production JAR.


## Staff-only punishment notifications (v1.0.4)

Punishment announcements are not sent to ordinary players. To let Helper and higher staff see in-game punishment notices, grant `punish.staff.notifications` to the LuckPerms `helper` group and ensure higher staff groups inherit it. For example:

```text
/lp group helper permission set punish.staff.notifications true
```

If your LuckPerms group names use different capitalization or naming, use your actual group name. The plugin checks the permission through Bukkit, so LuckPerms inheritance applies automatically.

All offence selections now open a confirmation menu showing the target, offence, action, duration, and category before the punishment is issued.


## 1.0.5 Discord bridge configuration

Example only (do not commit a real shared secret):

```yaml
discord:
  enabled: false
  bot-url: "https://YOUR-BOT-DOMAIN"
  shared-secret: "CHANGE-ME"
  server-id: "Robblestone"
```

The plugin appends `/api/v1/punishments` to `bot-url`. Do not enable the bridge until the bot is reachable over HTTPS and the shared secret matches the bot host's `API_SECRET`. Keep the secret private and do not post it in chat or screenshots.

The 1.0.5 source has not yet been compiled against the target DivineMC server. GitHub Actions must pass, followed by a staging-server test, before replacing the live plugin JAR.
