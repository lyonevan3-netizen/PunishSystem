package com.punishsystem;

import com.punishsystem.db.PunishmentDatabase;
import com.punishsystem.gui.MenuHolder;
import com.punishsystem.model.Punishment;
import com.punishsystem.model.PunishmentStep;
import com.punishsystem.service.PunishmentService;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.SQLException;
import java.util.*;

public final class PunishSystem extends JavaPlugin implements Listener {
    private PunishmentDatabase database;
    private PunishmentService punishments;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            database = new PunishmentDatabase(new File(getDataFolder(),
                    getConfig().getString("settings.database", "punishments.db")));
        } catch (SQLException ex) {
            getLogger().severe("Could not open punishment database: " + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        punishments = new PunishmentService(database);
        punishments.load(getConfig());

        Objects.requireNonNull(getCommand("punish")).setExecutor(new PunishCommand());
        Objects.requireNonNull(getCommand("history")).setExecutor(new HistoryCommand());
        Objects.requireNonNull(getCommand("link")).setExecutor(new LinkCommand());
        getServer().getPluginManager().registerEvents(this, this);

        getServer().getScheduler().runTaskTimerAsynchronously(this, () -> {
            try {
                database.expireOld();
            } catch (SQLException ex) {
                getLogger().warning("Could not expire old punishments: " + ex.getMessage());
            }
        }, 20L, 20L * 60L);

        int count = punishments.definitions().values().stream().mapToInt(Map::size).sum();
        getLogger().info("PunishSystem 1.0.0 enabled. Loaded " + count + " offence definitions.");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            try { database.close(); }
            catch (SQLException ex) { getLogger().warning("Could not close database: " + ex.getMessage()); }
        }
    }

    private final class PunishCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player staff)) {
                sender.sendMessage("This command can only be used in-game.");
                return true;
            }
            if (args.length != 1) {
                staff.sendMessage(color("&cUsage: /punish <player>"));
                return true;
            }
            OfflinePlayer target = findPlayer(args[0]);
            if (target == null) {
                staff.sendMessage(message("player-not-found"));
                return true;
            }
            openPunishRoot(staff, target.getUniqueId(), safeName(target, args[0]));
            return true;
        }

        @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length != 1) return List.of();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
    }

    private final class HistoryCommand implements TabExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player staff)) {
                sender.sendMessage("This command can only be used in-game.");
                return true;
            }
            if (args.length != 1) {
                staff.sendMessage(color("&cUsage: /hist <player>"));
                return true;
            }
            OfflinePlayer target = findPlayer(args[0]);
            if (target == null) {
                staff.sendMessage(message("player-not-found"));
                return true;
            }
            openHistoryRoot(staff, target.getUniqueId(), safeName(target, args[0]));
            return true;
        }

        @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length != 1) return List.of();
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
        }
    }

    private final class LinkCommand implements CommandExecutor {
        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            sender.sendMessage(color("&cDiscord linking is not enabled in this build."));
            sender.sendMessage(color("&7The separate Discord bridge will be added after the core plugin is tested."));
            return true;
        }
    }

    private OfflinePlayer findPlayer(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online;
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(name);
        if (cached != null && cached.hasPlayedBefore()) return cached;
        OfflinePlayer lookup = Bukkit.getOfflinePlayer(name);
        return lookup.hasPlayedBefore() ? lookup : null;
    }

    private String safeName(OfflinePlayer player, String fallback) {
        return player.getName() == null ? fallback : player.getName();
    }

    private void openPunishRoot(Player staff, UUID target, String targetName) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.PUNISH_ROOT, target, targetName, null, null, 0);
        Inventory inv = inventory(holder, 27, "Punishments");
        if (staff.hasPermission("punish.category.social")) {
            inv.setItem(11, item(Material.GREEN_WOOL, "&aSocial Punishments", "&7Warnings and mutes"));
        }
        if (staff.hasPermission("punish.category.moderator")) {
            inv.setItem(15, item(Material.RED_WOOL, "&cModerator Punishments", "&7Moderator-level actions"));
        }
        staff.openInventory(inv);
    }

    private void openOffences(Player staff, UUID target, String targetName, String category) {
        String permission = category.equals("social") ? "punish.category.social" : "punish.category.moderator";
        if (!staff.hasPermission(permission)) {
            staff.sendMessage(message("no-permission"));
            return;
        }
        LinkedHashMap<String, List<PunishmentStep>> offences =
                new LinkedHashMap<>(punishments.definitions().getOrDefault(category, new LinkedHashMap<>()));

        int size = Math.max(27, Math.min(54, ((offences.size() + 1 + 8) / 9) * 9));
        MenuHolder holder = new MenuHolder(MenuHolder.Type.OFFENCES, target, targetName, category, null, 0);
        Inventory inv = inventory(holder, size, category.equals("social") ? "Social Punishments" : "Moderator Punishments");

        int slot = 0;
        for (Map.Entry<String, List<PunishmentStep>> entry : offences.entrySet()) {
            List<PunishmentStep> steps = entry.getValue();
            if (!staff.hasPermission("punish.category.moderator")
                    && steps.getFirst().action().equals("BAN")) continue;

            PunishmentStep next;
            try {
                next = punishments.nextStep(target, category, entry.getKey());
            } catch (SQLException ex) {
                getLogger().warning("Could not calculate escalation: " + ex.getMessage());
                continue;
            }
            if (next == null) continue;
            Material material = switch (next.action()) {
                case "MUTE" -> Material.BLUE_WOOL;
                case "BAN" -> Material.RED_WOOL;
                default -> Material.YELLOW_WOOL;
            };
            List<String> lore = new ArrayList<>();
            lore.add("&7Escalation schedule:");
            for (int i = 0; i < steps.size(); i++) {
                PunishmentStep step = steps.get(i);
                String line = "&7" + (i + 1) + ". " + actionLabel(step.action()) + ": "
                        + PunishmentService.formatDuration(step.durationMillis());
                if (i == Math.min(getCountQuietly(target, category, entry.getKey()), steps.size() - 1)) {
                    line += " &a<-- Next";
                }
                lore.add(line);
            }
            inv.setItem(slot, item(material, "&c" + entry.getKey(), lore.toArray(String[]::new)));
            holder.mapOffence(slot, entry.getKey());
            slot++;
        }
        if (slot == 0) inv.setItem(4, item(Material.BARRIER, "&cNo offences available"));
        inv.setItem(size - 1, item(Material.ARROW, "&7Back to Punishments"));
        staff.openInventory(inv);
    }

    private int getCountQuietly(UUID target, String category, String offence) {
        try { return punishments.offenceCount(target, category, offence); }
        catch (SQLException ex) { return -1; }
    }

    private void openHistoryRoot(Player staff, UUID target, String targetName) {
        MenuHolder holder = new MenuHolder(MenuHolder.Type.HISTORY_ROOT, target, targetName, null, null, 0);
        Inventory inv = inventory(holder, 27, "Punishment History");
        inv.setItem(10, item(Material.RED_WOOL, "&cBans", "&7View ban history"));
        inv.setItem(13, item(Material.BLUE_WOOL, "&bMutes", "&7View mute history"));
        inv.setItem(16, item(Material.YELLOW_WOOL, "&eWarnings", "&7View warning history"));
        staff.openInventory(inv);
    }

    private void openHistoryList(Player staff, UUID target, String targetName, String action, int page) {
        int pageSize = Math.max(9, Math.min(45, getConfig().getInt("settings.history-page-size", 45)));
        try {
            int total = database.historyCount(target, action);
            int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            int safePage = Math.max(0, Math.min(page, pages - 1));
            List<Punishment> entries = database.history(target, action, pageSize, safePage * pageSize);
            MenuHolder holder = new MenuHolder(MenuHolder.Type.HISTORY_LIST, target, targetName, null, action, safePage);
            Inventory inv = inventory(holder, 54, "Punishment History · " + titleCase(action));
            int slot = 0;
            for (Punishment p : entries) {
                boolean active = p.active();
                Material material = p.status().equals("REVOKED") ? Material.GRAY_WOOL
                        : active ? Material.LIME_WOOL : Material.RED_WOOL;
                String state = p.status().equals("REVOKED") ? "Revoked" : active ? "Active" : "Expired";
                inv.setItem(slot, item(material, "&f#" + p.id() + " &c" + p.offence(),
                        "&7Status: " + state,
                        "&7Duration: " + (p.permanent() ? "Permanent" : PunishmentService.formatDuration(p.durationMillis())),
                        "&7Issued: " + new Date(p.issuedAt()),
                        "&7Staff: " + p.staffName(),
                        "&eClick for details"));
                holder.mapPunishment(slot, p.id());
                slot++;
            }
            if (entries.isEmpty()) inv.setItem(22, item(Material.BARRIER, "&cNo history found"));
            if (safePage > 0) inv.setItem(45, item(Material.ARROW, "&ePrevious page"));
            inv.setItem(49, item(Material.BARRIER, "&cBack to history"));
            if (safePage + 1 < pages) inv.setItem(53, item(Material.ARROW, "&eNext page"));
            staff.openInventory(inv);
        } catch (SQLException ex) {
            staff.sendMessage(color("&cCould not load punishment history."));
            getLogger().warning("History query failed: " + ex.getMessage());
        }
    }

    private void openDetails(Player staff, long id) {
        try {
            Optional<Punishment> found = database.get(id);
            if (found.isEmpty()) return;
            Punishment p = found.get();
            MenuHolder holder = new MenuHolder(MenuHolder.Type.DETAILS, p.playerUuid(), p.playerName(),
                    p.category(), Long.toString(id), 0);
            Inventory inv = inventory(holder, 27, "Punishment Details");
            inv.setItem(4, item(Material.PAPER, "&c" + p.offence(),
                    "&7ID: #" + p.id(),
                    "&7Player: " + p.playerName(),
                    "&7Category: " + titleCase(p.category()),
                    "&7Action: " + actionLabel(p.action()),
                    "&7Issued: " + new Date(p.issuedAt()),
                    "&7Expires: " + (p.warning() ? "N/A" : p.permanent() ? "Never" : new Date(p.expiresAt())),
                    "&7Duration: " + (p.permanent() ? "Permanent" : PunishmentService.formatDuration(p.durationMillis())),
                    "&7Staff: " + p.staffName(),
                    "&7Status: " + p.status(),
                    p.removalReason() == null ? "&7Removal: None" : "&7Removal: " + p.removalReason(),
                    p.removedBy() == null ? "&7Removed by: None" : "&7Removed by: " + p.removedBy()));
            if (!p.status().equals("REVOKED")) {
                String permission = removalPermission(p.action());
                if (staff.hasPermission(permission)) {
                    inv.setItem(11, item(Material.RED_WOOL, "&cRemove punishment", "&7Revoke this record"));
                }
                if (p.action().equals("BAN") && staff.hasPermission("punish.remove.ban")) {
                    inv.setItem(15, item(Material.YELLOW_WOOL, "&eAccept appeal", "&7Revoke and exclude from escalation"));
                }
            }
            inv.setItem(22, item(Material.ARROW, "&7Back to history"));
            staff.openInventory(inv);
        } catch (SQLException ex) {
            staff.sendMessage(color("&cCould not load punishment details."));
        }
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player staff)) return;
        if (!(event.getView().getTopInventory().getHolder() instanceof MenuHolder holder)) return;
        if (event.getClickedInventory() != event.getView().getTopInventory()) return;
        event.setCancelled(true);
        int slot = event.getSlot();

        try {
            switch (holder.type()) {
                case PUNISH_ROOT -> {
                    if (slot == 11) openOffences(staff, holder.target(), holder.targetName(), "social");
                    else if (slot == 15) openOffences(staff, holder.target(), holder.targetName(), "moderator");
                }
                case OFFENCES -> {
                    String offence = holder.offenceAt(slot);
                    if (offence == null) {
                        if (slot == event.getView().getTopInventory().getSize() - 1) {
                            openPunishRoot(staff, holder.target(), holder.targetName());
                        }
                        return;
                    }
                    issue(staff, holder.target(), holder.targetName(), holder.category(), offence);
                }
                case HISTORY_ROOT -> {
                    if (slot == 10) openHistoryList(staff, holder.target(), holder.targetName(), "BAN", 0);
                    else if (slot == 13) openHistoryList(staff, holder.target(), holder.targetName(), "MUTE", 0);
                    else if (slot == 16) openHistoryList(staff, holder.target(), holder.targetName(), "WARN", 0);
                }
                case HISTORY_LIST -> {
                    if (slot == 49) openHistoryRoot(staff, holder.target(), holder.targetName());
                    else if (slot == 45) openHistoryList(staff, holder.target(), holder.targetName(), holder.action(), holder.page() - 1);
                    else if (slot == 53) openHistoryList(staff, holder.target(), holder.targetName(), holder.action(), holder.page() + 1);
                    else {
                        Long id = holder.punishmentAt(slot);
                        if (id != null) openDetails(staff, id);
                    }
                }
                case DETAILS -> {
                    long id = Long.parseLong(holder.action());
                    if (slot == 22) openHistoryRoot(staff, holder.target(), holder.targetName());
                    else if (slot == 11 || slot == 15) {
                        boolean appeal = slot == 15;
                        Optional<Punishment> found = database.get(id);
                        if (found.isEmpty()) return;
                        Punishment p = found.get();
                        if (appeal && (!p.action().equals("BAN") || !staff.hasPermission("punish.remove.ban"))) return;
                        if (!staff.hasPermission(removalPermission(p.action()))) return;
                        if (database.revoke(id, appeal ? "APPEAL_ACCEPTED" : "Staff removal", staff.getName(), appeal)) {
                            staff.sendMessage(color("&aPunishment #" + id + " has been revoked."));
                            Player target = Bukkit.getPlayer(p.playerUuid());
                            if (target != null && p.action().equals("MUTE")) {
                                target.sendMessage(color("&aYour mute has been removed."));
                            }
                            staff.closeInventory();
                        }
                    }
                }
            }
        } catch (Exception ex) {
            getLogger().warning("Menu action failed: " + ex.getMessage());
            staff.sendMessage(color("&cSomething went wrong. Check the console."));
        }
    }

    private void issue(Player staff, UUID target, String targetName, String category, String offence) {
        String categoryPermission = category.equals("social") ? "punish.category.social" : "punish.category.moderator";
        if (!staff.hasPermission(categoryPermission)) {
            staff.sendMessage(message("no-permission"));
            return;
        }
        try {
            PunishmentStep step = punishments.nextStep(target, category, offence);
            if (step == null) return;
            // Helpers can use the Social menu, but cannot issue any BAN step.
            if (step.action().equals("BAN") && !staff.hasPermission("punish.category.moderator")) {
                staff.sendMessage(color("&cYou do not have permission to issue bans."));
                return;
            }

            long now = System.currentTimeMillis();
            long expiry = step.durationMillis() < 0 ? -1L
                    : step.durationMillis() == 0 ? now : Math.addExact(now, step.durationMillis());
            long id = database.issue(target, targetName, category, offence, step.action(),
                    step.durationMillis(), now, expiry, staff.getUniqueId(), staff.getName());

            staff.sendMessage(color("&aIssued &c" + offence + " &ato &f" + targetName
                    + " &7(" + actionLabel(step.action()) + ", "
                    + PunishmentService.formatDuration(step.durationMillis()) + ", #" + id + ")"));

            Player online = Bukkit.getPlayer(target);
            if (online != null) {
                switch (step.action()) {
                    case "MUTE" -> online.sendMessage(color("&cYou have been muted for &f"
                            + PunishmentService.formatDuration(step.durationMillis()) + "&c. Reason: &f" + offence));
                    case "BAN" -> online.kickPlayer("You have been banned.\nReason: " + offence
                            + (step.permanent() ? "\nDuration: Permanent"
                            : "\nDuration: " + PunishmentService.formatDuration(step.durationMillis())));
                    case "WARN" -> online.sendMessage(color("&eYou have received a warning. Reason: &f" + offence));
                }
            }
        } catch (SQLException | ArithmeticException ex) {
            staff.sendMessage(color("&cCould not issue punishment. Check console."));
            getLogger().severe("Punishment issue failed: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onChat(AsyncChatEvent event) {
        try {
            List<Punishment> active = database.activeFor(event.getPlayer().getUniqueId(), "MUTE");
            if (!active.isEmpty()) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(color("&cYou are muted. Reason: &f" + active.getFirst().offence()));
            }
        } catch (SQLException ex) {
            getLogger().warning("Mute lookup failed: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onMutedCommand(PlayerCommandPreprocessEvent event) {
        String message = event.getMessage().toLowerCase(Locale.ROOT).trim();
        String root = message.split("\\s+", 2)[0].replaceFirst("^/", "");
        String unnamespaced = root.contains(":") ? root.substring(root.indexOf(':') + 1) : root;
        List<String> blocked = getConfig().getStringList("settings.muted-blocked-commands");
        if (!blocked.contains(unnamespaced)) return;
        try {
            List<Punishment> active = database.activeFor(event.getPlayer().getUniqueId(), "MUTE");
            if (!active.isEmpty()) {
                event.setCancelled(true);
                event.getPlayer().sendMessage(color("&cYou are muted and cannot use that command. Reason: &f"
                        + active.getFirst().offence()));
            }
        } catch (SQLException ex) {
            getLogger().warning("Mute command lookup failed: " + ex.getMessage());
        }
    }

    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        try {
            List<Punishment> active = database.activeFor(event.getUniqueId(), "BAN");
            if (!active.isEmpty()) {
                Punishment punishment = active.getFirst();
                String expiry = punishment.permanent() ? "Permanent" : new Date(punishment.expiresAt()).toString();
                event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED,
                        "You are banned.\nReason: " + punishment.offence() + "\nExpires: " + expiry);
            }
        } catch (SQLException ex) {
            getLogger().warning("Ban lookup failed: " + ex.getMessage());
        }
    }

    private Inventory inventory(MenuHolder holder, int size, String title) {
        Inventory inventory = Bukkit.createInventory(holder, size, color("&8" + title));
        holder.inventory(inventory);
        return inventory;
    }

    private ItemStack item(Material material, String name, String... lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.setDisplayName(color(name));
        if (lore.length > 0) meta.setLore(Arrays.stream(lore).map(this::color).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private String removalPermission(String action) {
        return switch (action.toUpperCase(Locale.ROOT)) {
            case "BAN" -> "punish.remove.ban";
            case "MUTE" -> "punish.remove.mute";
            default -> "punish.remove.warn";
        };
    }

    private String actionLabel(String action) {
        return switch (action.toUpperCase(Locale.ROOT)) {
            case "MUTE" -> "Mute";
            case "BAN" -> "Ban";
            case "WARN" -> "Warning";
            default -> action;
        };
    }

    private String titleCase(String value) {
        if (value == null || value.isBlank()) return "";
        return value.substring(0, 1).toUpperCase(Locale.ROOT) + value.substring(1).toLowerCase(Locale.ROOT);
    }

    private String message(String key) {
        return color(getConfig().getString("settings.messages.prefix", "&8[&cPunish&8] &7")
                + getConfig().getString("settings.messages." + key, "&c" + key));
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }
}
