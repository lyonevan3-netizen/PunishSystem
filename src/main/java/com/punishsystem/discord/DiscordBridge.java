package com.punishsystem.discord;

import com.punishsystem.db.PunishmentDatabase;
import com.punishsystem.model.Punishment;
import com.punishsystem.service.PunishmentService;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.logging.Level;

/** Sends punishment events to the separate Discord bot through a durable SQLite outbox. */
public final class DiscordBridge {
    private final JavaPlugin plugin;
    private final PunishmentDatabase database;
    private final boolean enabled;
    private final URI endpoint;
    private final URI linkEndpoint;
    private final URI rankEndpoint;
    private final String secret;
    private final String serverId;
    private final HttpClient http;

    public DiscordBridge(JavaPlugin plugin, PunishmentDatabase database, FileConfiguration config) {
        this.plugin = plugin;
        this.database = database;
        this.enabled = config.getBoolean("discord.enabled", false);
        this.secret = config.getString("discord.shared-secret", "").trim();
        String configuredServerId = config.getString("discord.server-id", "Robblestone").trim();
        this.serverId = configuredServerId.isBlank() ? "Robblestone" : configuredServerId;
        URI parsed = null;
        try {
            String base = config.getString("discord.bot-url", "").trim();
            if (!base.isEmpty()) {
                URI candidate = URI.create(base.endsWith("/") ? base : base + "/");
                // Public integrations must use HTTPS. Local HTTP is intentionally not accepted.
                if ("https".equalsIgnoreCase(candidate.getScheme()) && candidate.getHost() != null) {
                    parsed = candidate;
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Invalid URL is reported by the startup validation below.
        }
        this.endpoint = parsed == null ? null : parsed.resolve("api/v1/punishments");
        this.linkEndpoint = parsed == null ? null : parsed.resolve("api/v1/link-codes");
        this.rankEndpoint = parsed == null ? null : parsed.resolve("api/v1/ranks/sync");
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(8))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();

        if (enabled && (endpoint == null || secret.isBlank() || secret.equalsIgnoreCase("CHANGE-ME"))) {
            plugin.getLogger().warning("Discord bridge is enabled but its HTTPS URL or shared secret is missing/invalid. Events will remain disabled until configuration is corrected.");
        } else if (enabled) {
            plugin.getLogger().info("Discord bridge enabled; punishment events will be queued for delivery.");
        }
    }

    public boolean enabled() {
        return enabled && endpoint != null && !secret.isBlank() && !secret.equalsIgnoreCase("CHANGE-ME");
    }

    /** Creates a short-lived, single-use code through the authenticated companion API. */
    public CompletableFuture<String> createLinkCode(UUID uuid, String username, String publicRank, String staffRank) {
        if (!enabled()) return CompletableFuture.failedFuture(new IllegalStateException("Discord bridge is disabled"));
        String payload = "{\"uuid\":" + json(uuid.toString())
                + ",\"username\":" + json(username)
                + ",\"publicRank\":" + json(publicRank == null ? "" : publicRank)
                + ",\"staffRank\":" + json(staffRank == null ? "" : staffRank) + "}";
        return post(linkEndpoint, payload).thenApply(body -> {
            Matcher matcher = Pattern.compile("\"code\"\\s*:\\s*\"([A-F0-9]{32})\"").matcher(body);
            if (!matcher.find()) throw new CompletionException(new IllegalStateException("Link API did not return a code"));
            return matcher.group(1);
        });
    }

    /** LuckPerms remains authoritative; this only mirrors its mapped roles to Discord. */
    public CompletableFuture<Void> syncRanks(UUID uuid, String username, String publicRank, String staffRank) {
        if (!enabled()) return CompletableFuture.completedFuture(null);
        String payload = "{\"uuid\":" + json(uuid.toString())
                + ",\"username\":" + json(username)
                + ",\"publicRank\":" + json(publicRank == null ? "" : publicRank)
                + ",\"staffRank\":" + json(staffRank == null ? "" : staffRank) + "}";
        return post(rankEndpoint, payload).thenApply(ignored -> null);
    }

    private CompletableFuture<String> post(URI uri, String payload) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(12))
                .header("Authorization", "Bearer " + secret)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
        return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
                .thenApply(response -> {
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        throw new CompletionException(new IllegalStateException("HTTP " + response.statusCode() + ": " + response.body()));
                    }
                    return response.body();
                });
    }

    public void enqueue(Punishment punishment) {
        if (!enabled()) return;
        try {
            String type = switch (punishment.action().toUpperCase()) {
                case "WARN" -> "WARNING";
                case "MUTE" -> "MUTE";
                case "BAN" -> "BAN";
                default -> null;
            };
            if (type == null) return;
            String eventId = serverId + "-" + punishment.id();
            String duration = punishment.warning() ? "N/A" : punishment.permanent() ? "Permanent"
                    : PunishmentService.formatDuration(punishment.durationMillis());
            String expiresAt = punishment.warning() || punishment.permanent() ? null
                    : DateTimeFormatter.ISO_INSTANT.format(Instant.ofEpochMilli(punishment.expiresAt()));
            String payload = "{"
                    + "\"punishmentId\":" + json(eventId) + ","
                    + "\"type\":" + json(type) + ","
                    + "\"player\":" + json(punishment.playerName()) + ","
                    + "\"staff\":" + json(punishment.staffName()) + ","
                    + "\"staffUuid\":" + json(punishment.staffUuid().toString()) + ","
                    + "\"reason\":" + json(punishment.offence()) + ","
                    + "\"duration\":" + json(duration)
                    + (expiresAt == null ? "" : ",\"expiresAt\":" + json(expiresAt))
                    + "}";
            database.queueDiscordEvent(eventId, payload);
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not queue Discord punishment event #" + punishment.id(), ex);
        }
    }

    /** Run only from an asynchronous Bukkit task; HTTP calls never block the server thread. */
    public void deliverPending() {
        if (!enabled()) return;
        try {
            List<PunishmentDatabase.OutboxEntry> entries = database.pendingDiscordEvents(5);
            for (PunishmentDatabase.OutboxEntry entry : entries) {
                try {
                    HttpRequest request = HttpRequest.newBuilder(endpoint)
                            .timeout(Duration.ofSeconds(12))
                            .header("Authorization", "Bearer " + secret)
                            .header("Content-Type", "application/json; charset=utf-8")
                            .header("Accept", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(entry.payloadJson(), StandardCharsets.UTF_8))
                            .build();
                    HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                    int status = response.statusCode();
                    if (status >= 200 && status < 300) {
                        database.completeDiscordEvent(entry.id());
                        plugin.getLogger().fine("Delivered a queued Discord punishment event.");
                    } else {
                        retry(entry, "HTTP " + status);
                    }
                } catch (Exception ex) {
                    retry(entry, ex.getClass().getSimpleName() + ": " + ex.getMessage());
                }
            }
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.WARNING, "Could not read Discord delivery queue", ex);
        }
    }

    private void retry(PunishmentDatabase.OutboxEntry entry, String reason) {
        int attempts = entry.attempts() + 1;
        long delaySeconds = Math.min(900L, 5L * (1L << Math.min(attempts - 1, 8)));
        long next = System.currentTimeMillis() + delaySeconds * 1000L;
        try {
            database.retryDiscordEvent(entry.id(), attempts, next);
            plugin.getLogger().warning("Discord delivery failed; queued retry " + attempts + " in " + delaySeconds + "s (" + reason + ").");
        } catch (SQLException ex) {
            plugin.getLogger().log(Level.SEVERE, "Could not schedule Discord delivery retry", ex);
        }
    }

    private static String json(String value) {
        if (value == null) return "null";
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }
}
