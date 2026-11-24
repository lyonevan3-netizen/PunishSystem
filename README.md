# PunishSystem 1.1.0

A Paper-compatible punishment plugin for Minecraft 26.2 / Java 25, with SQLite punishment history, configurable escalation, Discord punishment logging, self-service account linking, and LuckPerms-to-Discord role synchronisation.

## Included

- `/punish <player>` and `/history <player>` (`/hist`) with the existing staff permission nodes and menus.
- `/link` is self-service and available to every player. It requests a one-time 32-character hexadecimal Discord code from the companion bot; the code expires after 10 minutes.
- Discord `/link code:<code>` redeems the code. Discord `/unlink` removes the account association and only the roles managed by this integration.
- LuckPerms primary-group mapping is configurable in `config.yml`. Public rank roles are intended for Main Discord only; staff roles are intended for both Discord servers.
- Online players are reconciled on join and every configured interval. LuckPerms remains authoritative; Discord roles do not grant Minecraft permissions.
- Existing punishment rows and the SQLite punishment database schema are retained. New linking data is stored by the bot in a separate `account-links.sqlite` file.
- Existing Discord punishment outbox and retry behaviour is retained.

## Build

Requires Java 25 and Gradle 9.1+:

```bash
gradle clean build
```

The plugin JAR is `build/libs/PunishSystem-1.1.0.jar`.

## Discord companion bot

The companion bot must include the updated `index.js`, `deploy-commands.js`, and new `linking-db.js` files. Configure `MAIN_GUILD_ID` and `STAFF_GUILD_ID` in the bot host's private environment variables. The bot needs the Server Members intent enabled and Manage Roles in both guilds; its bot role must be above every role it manages.

The plugin's `discord.enabled` remains `false` until the bot has a public HTTPS address and the same private shared secret is configured on both sides. Do not put secrets in this repository.

## LuckPerms mapping

Edit `rank-sync.public-groups` and `rank-sync.staff-groups` to match the actual LuckPerms primary group names. Values are role keys understood by the companion bot. `owner: director` is included as an alias. Public role keys are only ever applied in the Main Discord. Staff role keys are only ever applied in both guilds.

## Safety / testing notes

This source package has been updated but has not been compiled or tested against the live DivineMC server in this environment. Build it with the included GitHub Actions workflow (Java 25) before installing. Back up `plugins/PunishSystem/punishments.db` before any upgrade. Do not replace or delete that database during installation.
