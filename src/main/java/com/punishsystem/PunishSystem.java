package com.punishsystem;

import com.punishsystem.db.PunishmentDatabase;
import com.punishsystem.model.Punishment;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.time.Duration;
import java.util.*;

public final class PunishSystem extends JavaPlugin implements Listener, CommandExecutor {
    private PunishmentDatabase database;
    private final Map<String, List<Step>> definitions = new LinkedHashMap<>();
    private static final String SOCIAL = "social";
    private static final String MODERATOR = "moderator";

    private record Step(String action, long durationMillis) {}

    @Override public void onEnable() {
        saveDefaultConfig();
        loadDefinitions();
        try { database = new PunishmentDatabase(new File(getDataFolder(), getConfig().getString("settings.database", "punishments.db"))); }
        catch (SQLException ex) { getLogger().severe("Could not open punishment database: " + ex.getMessage()); getServer().getPluginManager().disablePlugin(this); return; }
        for (String cmd : List.of("punish", "history", "link")) {
            PluginCommand command = getCommand(cmd);
            if (command != null) { command.setExecutor(this); if (cmd.equals("history")) command.setAliases(List.of("hist")); }
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try { database.expireOld(); } catch (SQLException e) { getLogger().warning("Expiry update failed: " + e.getMessage()); }
        }, 20L, 20L * 60L);
        getLogger().info("PunishSystem enabled. Loaded " + definitions.size() + " offence definitions.");
    }

    @Override public void onDisable() { if (database != null) try { database.close(); } catch (SQLException ignored) {} }

    private void loadDefinitions() {
        definitions.clear();
        FileConfiguration c = getConfig();
        for (String category : List.of(SOCIAL, MODERATOR)) {
            ConfigurationSection section = c.getConfigurationSection("punishments." + category);
            if (section == null) continue;
            for (String offence : section.getKeys(false)) {
                List<Step> steps = new ArrayList<>();
                for (String raw : section.getStringList(offence)) {
                    String[] split = raw.split(":", 2);
                    if (split.length != 2) continue;
                    String action = split[0].trim().toUpperCase(Locale.ROOT);
                    long duration = split[1].equalsIgnoreCase("PERM") ? -1 : parseDuration(split[1]);
                    if (List.of("MUTE", "BAN", "WARN").contains(action) && (duration >= 0 || duration == -1)) steps.add(new Step(action, duration));
                }
                if (!steps.isEmpty()) definitions.put(category + ":" + offence, steps);
            }
        }
    }

    private long parseDuration(String input) {
        if (input.equals("0")) return 0;
        long total = 0; StringBuilder number = new StringBuilder();
        for (char ch : input.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isDigit(ch)) number.append(ch);
            else if (!number.isEmpty()) {
                long n = Long.parseLong(number.toString()); number.setLength(0);
                total += switch (ch) { case 's' -> n * 1000L; case 'm' -> n * 60_000L; case 'h' -> n * 3_600_000L; case 'd' -> n * 86_400_000L; case 'w' -> n * 604_800_000L; default -> 0; };
            }
        }
        return total;
    }

    private boolean allowed(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) return true;
        sender.sendMessage(color(getConfig().getString("settings.messages.no-permission", "&cNo permission.")));
        return false;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player staff)) { sender.sendMessage("This command can only be used in-game."); return true; }
        switch (command.getName().toLowerCase(Locale.ROOT)) {
            case "punish" -> {
                if (!allowed(staff, "punish.command.punish")) return true;
                if (args.length != 1) { staff.sendMessage(color("&cUsage: /punish <player>")); return true; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                if (!target.hasPlayedBefore() && !target.isOnline()) { staff.sendMessage(color("&cThat player has never joined this server.")); return true; }
                openPunishRoot(staff, target.getUniqueId(), target.getName() == null ? args[0] : target.getName());
            }
            case "history" -> {
                if (!allowed(staff, "punish.command.history")) return true;
                if (args.length != 1) { staff.sendMessage(color("&cUsage: /history <player>")); return true; }
                OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);
                if (!target.hasPlayedBefore() && !target.isOnline()) { staff.sendMessage(color("&cThat player has never joined this server.")); return true; }
                openHistoryRoot(staff, target.getUniqueId(), target.getName() == null ? args[0] : target.getName());
            }
            case "link" -> {
                if (!allowed(staff, "punish.command.link")) return true;
                staff.sendMessage(color("&eDiscord linking will be enabled when the companion bot is configured. Your account UUID is &f" + staff.getUniqueId()));
            }
        }
        return true;
    }

    private void openPunishRoot(Player staff, UUID target, String name) {
        Inventory inv = Bukkit.createInventory(null, 27, "PS:PUNISH:" + target + ":" + name);
        inv.setItem(11, item(Material.GREEN_WOOL, "&aSocial Punishments", "&7Mutes and social offences"));
        if (staff.hasPermission("punish.category.moderator")) inv.setItem(15, item(Material.RED_WOOL, "&cModerator Punishments", "&7Bans and moderator offences"));
        staff.openInventory(inv);
    }

    private void openOffences(Player staff, UUID target, String name, String category) {
        if (category.equals(SOCIAL) && !staff.hasPermission("punish.category.social")) return;
        if (category.equals(MODERATOR) && !staff.hasPermission("punish.category.moderator")) return;
        List<String> offences = definitions.keySet().stream().filter(k -> k.startsWith(category + ":"))
                .filter(k -> staff.hasPermission("punish.category.moderator") || definitions.get(k).stream().noneMatch(step -> step.action.equals("BAN")))
                .map(k -> k.substring(category.length() + 1)).toList();
        Inventory inv = Bukkit.createInventory(null, Math.max(9, ((offences.size() + 8) / 9) * 9), "PS:OFFENCE:" + category + ":" + target + ":" + name);
        int slot = 0; for (String offence : offences) inv.setItem(slot++, item(category.equals(SOCIAL) ? Material.GREEN_WOOL : Material.RED_WOOL, "&f" + offence, "&7Click to issue the next escalation"));
        staff.openInventory(inv);
    }

    private void issue(Player staff, UUID target, String targetName, String category, String offence) {
        try {
            List<Step> steps = definitions.get(category + ":" + offence);
            if (steps == null) return;
            int count = database.offenceCount(target, offence);
            Step step = steps.get(Math.min(count, steps.size() - 1));
            if (step.action.equals("BAN") && !staff.hasPermission("punish.category.moderator")) {
                staff.sendMessage(color("&cYou do not have permission to issue bans.")); return;
            }
            long now = System.currentTimeMillis();
            long expiry = step.durationMillis < 0 ? -1 : step.durationMillis == 0 ? now : now + step.durationMillis;
            database.issue(target, targetName, category, offence, step.action, step.durationMillis, now, expiry, staff.getUniqueId(), staff.getName());
            String duration = step.action.equals("WARN") ? "warning" : step.durationMillis < 0 ? "permanent" : formatDuration(step.durationMillis);
            staff.sendMessage(color("&aIssued &f" + offence + " &ato &f" + targetName + " &7(" + step.action + ", " + duration + ")"));
            Player online = Bukkit.getPlayer(target);
            if (online != null) {
                if (step.action.equals("MUTE")) online.sendMessage(color("&cYou have been muted for &f" + duration + "&c. Reason: &f" + offence));
                else if (step.action.equals("BAN")) online.kickPlayer("You have been banned. Reason: " + offence + (step.durationMillis < 0 ? "" : " | Duration: " + duration));
                else online.sendMessage(color("&eYou have received a warning. Reason: &f" + offence));
            }
        } catch (SQLException ex) { staff.sendMessage(color("&cDatabase error issuing punishment.")); getLogger().severe(ex.getMessage()); }
    }

    private void openHistoryRoot(Player staff, UUID target, String name) {
        Inventory inv = Bukkit.createInventory(null, 27, "PS:HIST:" + target + ":" + name);
        inv.setItem(11, item(Material.RED_WOOL, "&cBans", "&7View ban history"));
        inv.setItem(13, item(Material.GREEN_WOOL, "&aMutes", "&7View mute history"));
        inv.setItem(15, item(Material.YELLOW_WOOL, "&eWarnings", "&7View warning history"));
        staff.openInventory(inv);
    }

    private void openHistoryList(Player staff, UUID target, String category) {
        String action = switch (category) { case "bans" -> "BAN"; case "mutes" -> "MUTE"; default -> "WARN"; };
        try {
            List<Punishment> entries = database.history(target, action);
            Inventory inv = Bukkit.createInventory(null, Math.max(9, Math.min(54, ((entries.size() + 8) / 9) * 9)), "PS:LIST:" + target + ":" + category);
            int slot = 0;
            for (Punishment p : entries.stream().limit(54).toList()) {
                boolean active = p.active();
                Material wool = active ? Material.GREEN_WOOL : Material.RED_WOOL;
                String state = p.status().equals("REVOKED") ? "Revoked" : active ? "Active" : "Expired";
                inv.setItem(slot++, item(wool, "&f#" + p.id() + " &7" + p.offence(), "&7Status: " + state,
                        "&7Issued: " + new Date(p.issuedAt()), "&7Expires: " + (p.permanent() ? "Never" : p.warning() ? "N/A" : new Date(p.expiresAt())),
                        "&7Duration: " + (p.permanent() ? "Permanent" : formatDuration(p.durationMillis())),
                        "&7Staff: " + p.staffName(), "&eClick for details"));
            }
            if (entries.isEmpty()) inv.setItem(4, item(Material.BARRIER, "&cNo history found"));
            staff.openInventory(inv);
        } catch (SQLException ex) { staff.sendMessage(color("&cCould not load history.")); }
    }

    private void openDetails(Player staff, long id) {
        try {
            Optional<Punishment> found = database.get(id); if (found.isEmpty()) return;
            Punishment p = found.get();
            Inventory inv = Bukkit.createInventory(null, 27, "PS:DETAIL:" + id);
            inv.setItem(4, item(Material.PAPER, "&f" + p.offence(), "&7Player: " + p.playerName(), "&7Category: " + p.category(), "&7Action: " + p.action(), "&7Issued: " + new Date(p.issuedAt()), "&7Expires: " + (p.permanent() ? "Never" : p.warning() ? "N/A" : new Date(p.expiresAt())), "&7Duration: " + (p.permanent() ? "Permanent" : formatDuration(p.durationMillis())), "&7Staff: " + p.staffName(), "&7Status: " + p.status()));
            if (!p.status().equals("REVOKED")) {
                String perm = p.action().equals("BAN") ? "punish.remove.ban" : p.action().equals("MUTE") ? "punish.remove.mute" : "punish.remove.warn";
                if (staff.hasPermission(perm)) inv.setItem(11, item(Material.RED_WOOL, "&cRemove punishment", "&7Ordinary staff correction"));
                if (p.action().equals("BAN") && staff.hasPermission("punish.remove.ban")) inv.setItem(15, item(Material.YELLOW_WOOL, "&eAppeal accepted", "&7Revoke and exclude from escalation"));
            }
            staff.openInventory(inv);
        } catch (SQLException ex) { staff.sendMessage(color("&cCould not load punishment.")); }
    }

    @EventHandler public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player staff)) return;
        String title = event.getView().getTitle(); if (!title.startsWith("PS:")) return;
        event.setCancelled(true); ItemStack clicked = event.getCurrentItem(); if (clicked == null || clicked.getType().isAir() || !clicked.hasItemMeta()) return;
        String display = ChatColor.stripColor(Objects.requireNonNullElse(clicked.getItemMeta().getDisplayName(), ""));
        try {
            if (title.startsWith("PS:PUNISH:")) {
                String[] p = title.split(":", 4); UUID target = UUID.fromString(p[2]); String name = p[3];
                if (event.getSlot() == 11) openOffences(staff, target, name, SOCIAL);
                if (event.getSlot() == 15 && staff.hasPermission("punish.category.moderator")) openOffences(staff, target, name, MODERATOR);
            } else if (title.startsWith("PS:OFFENCE:")) {
                String[] p = title.split(":", 5); String category = p[2]; UUID target = UUID.fromString(p[3]); String name = p[4];
                String offence = display;
                if (definitions.containsKey(category + ":" + offence)) { staff.closeInventory(); issue(staff, target, name, category, offence); }
            } else if (title.startsWith("PS:HIST:")) {
                String[] p = title.split(":", 4); UUID target = UUID.fromString(p[2]);
                if (event.getSlot() == 11) openHistoryList(staff, target, "bans");
                if (event.getSlot() == 13) openHistoryList(staff, target, "mutes");
                if (event.getSlot() == 15) openHistoryList(staff, target, "warns");
            } else if (title.startsWith("PS:LIST:")) {
                String[] p = title.split(":", 4); long id = Long.parseLong(display.substring(1, display.indexOf(' '))); openDetails(staff, id);
            } else if (title.startsWith("PS:DETAIL:")) {
                long id = Long.parseLong(title.substring("PS:DETAIL:".length()));
                boolean appeal = clicked.getType() == Material.YELLOW_WOOL;
                if (clicked.getType() == Material.RED_WOOL || appeal) {
                    Punishment punishment = database.get(id).orElse(null); if (punishment == null) return;
                    String permission = punishment.action().equals("BAN") ? "punish.remove.ban" : punishment.action().equals("MUTE") ? "punish.remove.mute" : "punish.remove.warn";
                    if (!staff.hasPermission(permission)) return;
                    if (database.revoke(id, "Staff removal", staff.getName(), appeal)) {
                        staff.sendMessage(color("&aPunishment #" + id + " revoked."));
                        Player target = Bukkit.getPlayer(punishment.playerUuid());
                        if (target != null && punishment.action().equals("MUTE")) target.sendMessage(color("&aYour mute has been removed."));
                    }
                    staff.closeInventory();
                }
            }
        } catch (Exception ex) { getLogger().warning("GUI action failed: " + ex.getMessage()); staff.sendMessage(color("&cSomething went wrong. Check console.")); }
    }

    @EventHandler public void onChat(AsyncChatEvent event) {
        try {
            List<Punishment> mutes = database.activeFor(event.getPlayer().getUniqueId(), "MUTE");
            if (!mutes.isEmpty()) { event.setCancelled(true); event.getPlayer().sendMessage(color("&cYou are muted. Reason: &f" + mutes.getFirst().offence())); }
        } catch (SQLException ex) { getLogger().warning("Mute check failed: " + ex.getMessage()); }
    }


    @EventHandler public void onMutedCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().toLowerCase(Locale.ROOT).trim();
        String root = message.split("\\s+", 2)[0].replaceFirst("^/", "");
        if (!Set.of("msg", "tell", "w", "whisper", "message", "reply", "r", "me", "minecraft:msg", "minecraft:tell", "minecraft:w").contains(root)) return;
        try {
            List<Punishment> mutes = database.activeFor(event.getPlayer().getUniqueId(), "MUTE");
            if (!mutes.isEmpty()) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(color("&cYou are muted and cannot use messaging commands. Reason: &f" + mutes.getFirst().offence()));
            }
        } catch (SQLException ex) { getLogger().warning("Mute command check failed: " + ex.getMessage()); }
    }

    @EventHandler public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        try {
            List<Punishment> bans = database.activeFor(event.getUniqueId(), "BAN");
            if (!bans.isEmpty()) {
                Punishment p = bans.getFirst();
                String expiry = p.permanent() ? "Permanent" : new Date(p.expiresAt()).toString();
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "You are banned.\nReason: " + p.offence() + "\nExpires: " + expiry);
            }
        } catch (SQLException ex) { getLogger().warning("Ban check failed: " + ex.getMessage()); }
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material); ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name)); if (lore.length > 0) meta.setLore(Arrays.stream(lore).map(this::color).toList());
        stack.setItemMeta(meta); return stack;
    }
    private String color(String text) { return ChatColor.translateAlternateColorCodes('&', text); }
    private String formatDuration(long ms) {
        if (ms < 0) return "Permanent"; if (ms == 0) return "Instant";
        Duration d = Duration.ofMillis(ms); long days = d.toDays(), hours = d.toHoursPart(), minutes = d.toMinutesPart();
        if (days > 0) return days + "d" + (hours > 0 ? " " + hours + "h" : "");
        if (d.toHours() > 0) return d.toHours() + "h" + (minutes > 0 ? " " + minutes + "m" : "");
        return Math.max(1, d.toMinutes()) + "m";
    }
}
