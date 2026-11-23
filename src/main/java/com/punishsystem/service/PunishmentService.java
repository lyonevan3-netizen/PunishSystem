package com.punishsystem.service;

import com.punishsystem.db.PunishmentDatabase;
import com.punishsystem.model.PunishmentStep;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;

import java.sql.SQLException;
import java.util.*;

public final class PunishmentService {
    private final PunishmentDatabase database;
    private final Map<String, LinkedHashMap<String, List<PunishmentStep>>> definitions = new LinkedHashMap<>();

    public PunishmentService(PunishmentDatabase database) {
        this.database = database;
    }

    public void load(FileConfiguration config) {
        definitions.clear();
        for (String category : List.of("social", "moderator")) {
            LinkedHashMap<String, List<PunishmentStep>> offences = new LinkedHashMap<>();
            ConfigurationSection section = config.getConfigurationSection("punishments." + category);
            if (section == null) continue;
            for (String offence : section.getKeys(false)) {
                List<PunishmentStep> steps = new ArrayList<>();
                for (String raw : section.getStringList(offence)) {
                    String[] split = raw.split(":", 2);
                    if (split.length != 2) continue;
                    String action = split[0].trim().toUpperCase(Locale.ROOT);
                    if (!Set.of("MUTE", "BAN", "WARN").contains(action)) continue;
                    try {
                        steps.add(new PunishmentStep(action, parseDuration(split[1].trim())));
                    } catch (IllegalArgumentException ignored) {
                        // Invalid entries are skipped; the startup summary reports the remaining definitions.
                    }
                }
                if (!steps.isEmpty()) offences.put(offence, List.copyOf(steps));
            }
            definitions.put(category, offences);
        }
    }

    public Map<String, LinkedHashMap<String, List<PunishmentStep>>> definitions() {
        return Collections.unmodifiableMap(definitions);
    }

    public List<PunishmentStep> steps(String category, String offence) {
        return definitions.getOrDefault(category.toLowerCase(Locale.ROOT), new LinkedHashMap<>())
                .getOrDefault(offence, List.of());
    }

    public int offenceCount(UUID player, String category, String offence) throws SQLException {
        return database.offenceCount(player, category, offence);
    }

    public PunishmentStep nextStep(UUID player, String category, String offence) throws SQLException {
        List<PunishmentStep> steps = steps(category, offence);
        if (steps.isEmpty()) return null;
        int index = Math.min(database.offenceCount(player, category, offence), steps.size() - 1);
        return steps.get(index);
    }

    public static long parseDuration(String input) {
        if (input.equalsIgnoreCase("PERM")) return -1L;
        if (input.equals("0")) return 0L;
        long total = 0L;
        StringBuilder number = new StringBuilder();
        for (char c : input.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isDigit(c)) {
                number.append(c);
                continue;
            }
            if (number.isEmpty()) throw new IllegalArgumentException("Missing duration number");
            long value = Long.parseLong(number.toString());
            number.setLength(0);
            long multiplier = switch (c) {
                case 'm' -> 60_000L;
                case 'h' -> 3_600_000L;
                case 'd' -> 86_400_000L;
                case 'w' -> 604_800_000L;
                default -> throw new IllegalArgumentException("Unknown duration unit: " + c);
            };
            total = Math.addExact(total, Math.multiplyExact(value, multiplier));
        }
        if (!number.isEmpty()) throw new IllegalArgumentException("Duration has no unit");
        if (total <= 0) throw new IllegalArgumentException("Duration must be positive");
        return total;
    }

    public static String formatDuration(long millis) {
        if (millis < 0) return "Permanent";
        if (millis == 0) return "Instant";
        long minutes = millis / 60_000L;
        long days = minutes / 1440; minutes %= 1440;
        long hours = minutes / 60; minutes %= 60;
        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0 || parts.isEmpty()) parts.add(minutes + "m");
        return String.join(" ", parts);
    }
}
